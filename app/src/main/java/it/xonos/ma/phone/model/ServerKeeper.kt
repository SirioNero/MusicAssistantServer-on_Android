package it.xonos.ma.phone.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val MINUTE = 60_000L

/**
 * Tiene acceso il Music Assistant sul telefono finché qualcuno ne ha bisogno, e lo ferma quando nessuno lo usa più.
 * Acceso costa processore e batteria anche fermo, e con Xonos in disparte Android lo congela o lo chiude ("excessive
 * cpu"): prima restava acceso dall'apertura dell'app fino a quando Android chiudeva tutto.
 *
 * I bisogni ([Need]): lo schermo di Xonos, la musica che suona o sta per suonare, chi comanda da fuori (Gearslip,
 * l'autoradio col Bluetooth), il ponte di myCar, l'account chiesto da myCar. [hold] tiene un bisogno e fa partire il
 * server, se Xonos lo usa; [release] lo lascia, ma il server resta acceso ancora per l'attesa del bisogno (tornando
 * subito non si aspetta l'avvio); [touch] è un bisogno di un momento. Quando tutti i bisogni sono scaduti il server si
 * ferma, e il primo che arriva dopo lo fa ripartire. Avvio e arresto vanno uno alla volta.
 *
 * Un bisogno si può tenere più volte (due schermate aperte insieme): conta quante, e si lascia quando l'ultima lo lascia.
 *
 * Senza Android: il banco di prova (KeeperCheck) lo prova con un tempo finto.
 */
class ServerKeeper(
    private val scope: CoroutineScope,
    /** Xonos usa il Music Assistant sul telefono ed è scaricato: solo allora si avvia. */
    private val used: () -> Boolean,
    /**
     * Lo avvia, se non gira, e aspetta che risponda. Deve guardare il processo, non lo stato: subito dopo un arresto lo
     * stato può dire ancora "acceso".
     */
    private val start: suspend () -> Unit,
    /** Lo ferma, se gira, e aspetta che sia fermo. */
    private val stop: suspend () -> Unit,
    /** Il tempo in ms. Su Android quello che conta anche il sonno del telefono (elapsedRealtime). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** L'attesa del timer: le prove la sostituiscono col loro tempo finto. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Una riga nel registro. */
    private val log: (String) -> Unit = {},
) {
    /** Chi ha bisogno del server, con quanto resta acceso dopo che lo lascia. */
    enum class Need(val graceMs: Long) {
        /** Una schermata di Xonos in vista. */
        SCREEN(3 * MINUTE),

        /**
         * La coda suona o sta per suonare. Dopo la pausa 8 minuti: Media3 tiene il servizio in primo piano per 10, e
         * l'arresto deve finire prima che Android possa congelare l'app.
         */
        PLAYING(8 * MINUTE),

        /** Un'app che comanda il lettore da fuori: Gearslip, l'autoradio col Bluetooth. */
        CAR(2 * MINUTE),

        /** myCar legata al ponte (vedi MyCarBridge). */
        MYCAR(1 * MINUTE),

        /** myCar ha chiesto l'account. */
        ACCOUNT(2 * MINUTE),
    }

    private class Hold {
        /** Quante volte è tenuto adesso. */
        var count = 0

        /** Lasciato: fino a quando conta ancora. */
        var until = Long.MIN_VALUE
    }

    private val lock = Any()
    private val holds = Need.entries.associateWith { Hold() }
    private var timer: Job? = null
    private val turn = Mutex()

    /** Qualcuno ha bisogno del server adesso (tenuto, o lasciato da poco). */
    val wanted: Boolean get() = synchronized(lock) { wantedAt(clock()) }

    private fun wantedAt(now: Long) = holds.values.any { it.count > 0 || it.until > now }

    /** [need] comincia: il server resta acceso (e parte, se Xonos lo usa e non gira) finché non lo si lascia. */
    fun hold(need: Need) {
        val first = synchronized(lock) {
            val hold = holds.getValue(need)
            hold.count++
            timer?.cancel()
            timer = null
            hold.count == 1
        }
        if (first) note("Server sul telefono: serve per $need")
        launchStart()
    }

    /**
     * [need] finisce: il server resta acceso ancora [graceMs] per lui. Un bisogno non tenuto ma ancora in attesa può solo
     * accorciarla (Xonos tolto dai recenti, il servizio chiuso); uno mai tenuto o già scaduto resta com'è.
     */
    fun release(need: Need, graceMs: Long = need.graceMs) {
        synchronized(lock) {
            val hold = holds.getValue(need)
            val now = clock()
            when {
                hold.count > 0 -> {
                    hold.count--
                    if (hold.count > 0) return
                    hold.until = now + graceMs
                }
                hold.until > now -> hold.until = min(hold.until, now + graceMs)
                else -> return
            }
            plan()
        }
        note("Server sul telefono: $need lasciato, resta acceso ancora ${graceMs / 1000} s per lui")
    }

    /**
     * Un bisogno di un momento (myCar che chiede l'account, un play col server fermo): il server parte, se serve, e resta
     * acceso almeno [graceMs]. Non accorcia niente: un bisogno tenuto resta tenuto.
     */
    fun touch(need: Need, graceMs: Long = need.graceMs) {
        synchronized(lock) {
            val hold = holds.getValue(need)
            val now = clock()
            hold.until = max(hold.until, now + graceMs)
            plan()
        }
        launchStart()
    }

    /** Il server si è fermato da solo (Android l'ha chiuso) mentre serve ancora: riparte. Se non serve, niente. */
    fun revive() {
        if (wanted) launchStart()
    }

    /** Una riga nel registro, solo se Xonos usa il server sul telefono: agli altri non dice niente. */
    private fun note(text: String) {
        if (used()) log(text)
    }

    private fun launchStart() {
        scope.launch {
            turn.withLock {
                if (!wanted || !used()) return@withLock
                try {
                    start()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Server sul telefono: avvio non riuscito (${e.javaClass.simpleName})")
                }
            }
        }
    }

    /** Il timer per l'ultima scadenza, se nessuno tiene il server: uno solo, rifatto a ogni cambio. Col lock tenuto. */
    private fun plan() {
        timer?.cancel()
        timer = null
        if (holds.values.any { it.count > 0 }) return
        val last = holds.values.maxOf { it.until }
        if (last == Long.MIN_VALUE) return
        timer = scope.launch {
            // A passi: se il telefono dorme, l'attesa di Android si allunga, l'orologio no.
            while (true) {
                val left = last - clock()
                if (left <= 0) break
                sleep(min(left, STEP_MS))
            }
            turn.withLock {
                if (wanted) return@withLock
                note("Server sul telefono: non serve più")
                // Cominciato, l'arresto va finito: un bisogno che arriva adesso aspetta e poi lo fa ripartire.
                withContext(NonCancellable) {
                    try {
                        stop()
                    } catch (e: Exception) {
                        log("Server sul telefono: arresto non riuscito (${e.javaClass.simpleName})")
                    }
                }
            }
        }
    }

    companion object {
        /** Lo schermo lasciato durante l'accesso a un servizio nel browser o l'abbinamento di Soloist nell'app Spotify. */
        const val SETUP_SCREEN_MS = 15 * MINUTE

        /** Ogni quanto il timer guarda l'orologio. */
        const val STEP_MS = 30_000L
    }
}
