package harness

import it.xonos.ma.data.SkipSender
import it.xonos.ma.data.SkipTrace
import it.xonos.ma.playback.SkipPlan
import it.xonos.ma.playback.SkipPlan.Jump
import it.xonos.ma.playback.SkipPlan.Next
import it.xonos.ma.playback.SkipPlan.Previous
import it.xonos.ma.playback.SkipPlan.Restart
import it.xonos.ma.playback.SkipPlan.Stay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * I salti senza server.
 *
 * - [SkipSender]: quanto aspetta prima di mandare (col Music Assistant sul telefono 250 ms, con gli altri 400 ms, subito
 *   con i servizi a più flussi) e che una raffica diventi un comando solo, verso l'ultimo brano.
 * - [SkipPlan]: dove vanno avanti e indietro. Media3 non si compila qui: il brano che chiede lo calcola [media3Next] e
 *   [media3Previous], copiati da BasePlayer.seekToNext/seekToPrevious di Media3 1.11.1, sulla finestra della coda.
 */
fun main() = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    // ---------- SkipSender, a tempo vero ----------

    class Sent(val index: Int, val afterLastTapMs: Long)
    fun now() = System.nanoTime() / 1_000_000
    suspend fun taps(gatherMs: Long, count: Int, gapMs: Long): Pair<List<Sent>, Int> {
        val sent = Collections.synchronizedList(mutableListOf<Sent>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lastTap = AtomicLong()
        val sender = SkipSender(scope, trace = true) { index ->
            sent += Sent(index, now() - lastTap.get())
            delay(80) // la risposta di Music Assistant
        }
        val done = AtomicInteger()
        for (i in 1..count) {
            SkipTrace.touch()
            lastTap.set(now())
            sender.request(i, gatherMs) { done.incrementAndGet() }
            if (i < count) delay(gapMs)
        }
        delay(gatherMs + 700)
        scope.cancel()
        return sent.toList() to done.get()
    }
    fun describe(sent: List<Sent>) = sent.joinToString { "${it.index} dopo ${it.afterLastTapMs} ms" }

    run {
        val (sent, done) = taps(SkipSender.GATHER_PHONE_MS, 1, 0)
        val ms = sent.singleOrNull()?.afterLastTapMs ?: -1
        check("Server sul telefono, un tocco: parte dopo 250 ms", sent.map { it.index } == listOf(1) && ms in 250..399 && done == 1, describe(sent))
    }
    run {
        val (sent, done) = taps(SkipSender.GATHER_PHONE_MS, 3, 100)
        val ms = sent.singleOrNull()?.afterLastTapMs ?: -1
        check(
            "Server sul telefono, tre tocchi a 100 ms: un invio solo, all'ultimo, 250 ms dopo l'ultimo tocco",
            sent.map { it.index } == listOf(3) && ms in 250..399 && done == 3,
            "${describe(sent)}, conclusi $done",
        )
    }
    run {
        val (sent, done) = taps(SkipSender.GATHER_MS, 1, 0)
        val ms = sent.singleOrNull()?.afterLastTapMs ?: -1
        check("Altri Music Assistant, un tocco: parte dopo 400 ms", sent.map { it.index } == listOf(1) && ms in 400..520 && done == 1, describe(sent))
    }
    run {
        val (sent, done) = taps(SkipSender.GATHER_MS, 3, 300)
        check(
            "Altri Music Assistant, tre tocchi a 300 ms: ancora un invio solo",
            sent.map { it.index } == listOf(3) && done == 3,
            "${describe(sent)}, conclusi $done",
        )
    }
    run {
        val (sent, done) = taps(SkipSender.GATHER_PHONE_MS, 3, 300)
        // Tocchi più distanti dell'attesa: ognuno parte, uno alla volta, e l'ultimo è il brano chiesto per ultimo.
        check(
            "Server sul telefono, tre tocchi a 300 ms: tre invii, in ordine",
            sent.map { it.index } == listOf(1, 2, 3) && done == 3,
            "${describe(sent)}, conclusi $done",
        )
    }
    run {
        val (sent, _) = taps(0L, 1, 0)
        val ms = sent.singleOrNull()?.afterLastTapMs ?: -1
        check("Servizio a più flussi: parte subito", sent.map { it.index } == listOf(1) && ms in 0..60, describe(sent))
    }

    // ---------- SkipTrace, con un orologio finto ----------

    run {
        var now = 1_000L
        val real = SkipTrace.clock
        SkipTrace.clock = { now }
        SkipTrace.touch()
        now += 300
        val sent = SkipTrace.sinceTouch()
        now += 1_200
        val out = SkipTrace.musicOut()
        val again = SkipTrace.musicOut()
        now += 2_000 // l'uscita dice i suoi tempi veri poco dopo: conta ancora
        val late = SkipTrace.sinceTouch()
        val heard = SkipTrace.heard(190) // e la musica si sente 190 ms dopo
        now += 4_000 // poi una pausa o un play non sono più del salto
        val after = SkipTrace.sinceTouch()
        val heardAfter = SkipTrace.heard(190)
        SkipTrace.touch()
        now += SkipTrace.WINDOW_MS + 1 // un tocco senza musica fuori vale al massimo 30 s
        val stale = SkipTrace.sinceTouch()
        SkipTrace.clock = real
        check(
            "Orologio del salto: ms dal tocco, musica fuori una volta, udibile, poi chiuso",
            sent == 300L && out == 1_500L && again == null && late == 3_500L && heard == 3_690L && after == null &&
                heardAfter == null && stale == null,
            "mando $sent, fuori $out, di nuovo $again, uscita $late, udibile $heard, dopo $after e $heardAfter, vecchio $stale",
        )
    }

    // ---------- SkipPlan ----------

    // Una coda di 100 brani, Music Assistant sul brano 10, la finestra di MaPlayer dal 7 al 60 (3 prima, 50 dopo).
    val big = Window(start = 7, size = 54, queue = 100, current = 10)
    // La finestra ridotta al brano in corso (subito dopo un casuale o una coda nuova, finché non arriva quella vera).
    val reduced = Window(start = 10, size = 1, queue = 100, current = 10)
    // L'ultimo brano della coda.
    val last = Window(start = 7, size = 4, queue = 11, current = 10)
    // La finestra che finisce col brano in corso, ma la coda va avanti (la finestra nuova non è ancora arrivata).
    val edge = Window(start = 57, size = 4, queue = 100, current = 60)
    // Un salto verso l'ultimo brano della coda (11) in viaggio: Media3 lo mostra già, Music Assistant è ancora sul 10.
    val lastSkip = Window(start = 7, size = 5, queue = 12, current = 10)
    // Il primo brano della coda, con la finestra intera dopo.
    val first = Window(start = 0, size = 51, queue = 100, current = 0)
    // Una coda di un brano solo.
    val single = Window(start = 0, size = 1, queue = 1, current = 0)
    // La coda dice meno brani di quello in corso (il numero non è ancora arrivato): da lì non si sa dov'è la fine.
    val unknown = Window(start = 10, size = 1, queue = 0, current = 10)

    fun next(w: Window, repeat: String, shown: Int = w.current - w.start, skipping: Boolean = false): SkipPlan.Step {
        val q = w.view(shown, repeat, skipping)
        return SkipPlan.next(media3Next(shown, w.size, repeat), q)
    }
    fun previous(
        w: Window,
        repeat: String,
        positionMs: Long,
        shown: Int = w.current - w.start,
        skipping: Boolean = false,
        mediaItem: Boolean = false,
    ): SkipPlan.Step {
        val q = w.view(shown, repeat, skipping)
        val (requested, defaultPosition) =
            if (mediaItem) media3PreviousMediaItem(shown, w.size, repeat) else media3Previous(shown, w.size, repeat, positionMs)
        return SkipPlan.previous(requested, defaultPosition, positionMs <= MAX_SEEK_TO_PREVIOUS_MS, q)
    }
    fun expect(label: String, got: SkipPlan.Step, want: SkipPlan.Step) = check(label, got == want, "$got")

    println()
    println("Avanti")
    expect("brano dopo nella finestra", next(big, "off"), Jump(11))
    expect("brano dopo nella finestra, ripeti uno (Media3 naviga come senza ripeti)", next(big, "one"), Jump(11))
    expect("brano dopo nella finestra, ripeti tutto", next(big, "all"), Jump(11))
    expect("finestra ridotta, ripeti spento: senza indice, ma il brano dopo è noto", next(reduced, "off"), Jump(11))
    // Con ripeti uno e ripeti tutto il brano dopo è quello che darebbe next di Music Assistant (con un salto ripeti uno
    // non conta), ma senza il suo secondo di attesa.
    expect("finestra ridotta, ripeti uno: il brano dopo, come Music Assistant", next(reduced, "one"), Jump(11))
    expect("finestra ridotta, ripeti tutto (Media3 ripete il brano in corso): il brano dopo, come Music Assistant", next(reduced, "all"), Jump(11))
    expect("ultimo brano della finestra (non della coda), ripeti spento: senza indice, ma il brano dopo è noto", next(edge, "off"), Jump(61))
    expect("ultimo brano della finestra (non della coda), ripeti uno: il brano dopo, come Music Assistant", next(edge, "one"), Jump(61))
    expect("ultimo brano della coda, ripeti spento: niente (next di Music Assistant non farebbe niente)", next(last, "off"), Stay)
    expect("ultimo brano della coda, ripeti uno: niente (con un salto ripeti uno non conta)", next(last, "one"), Stay)
    expect("ultimo brano della coda, ripeti tutto: il primo della coda, come Music Assistant (non il primo della finestra, il 7)", next(last, "all"), Jump(0))
    expect("ultimo della finestra (non della coda), ripeti tutto: il brano dopo (non il 57)", next(edge, "all"), Jump(61))
    expect("coda di un brano solo, ripeti tutto: lo stesso da capo, come Music Assistant", next(single, "all"), Jump(0))
    expect("coda di un brano solo, ripeti uno: niente", next(single, "one"), Stay)
    expect("coda di un brano solo, ripeti spento: niente (il registro dell'utente con la 0.1.56)", next(single, "off"), Stay)
    expect("coda più corta del brano mostrato (non ancora aggiornata), ripeti tutto: decide Music Assistant", next(unknown, "all"), Next)
    expect("coda più corta del brano mostrato (non ancora aggiornata), ripeti spento: decide Music Assistant", next(unknown, "off"), Next)
    // Durante un salto Media3 mostra già il brano chiesto (11), Music Assistant è ancora sul 10.
    expect("durante un salto: dal brano mostrato", next(big, "off", shown = 4, skipping = true), Jump(12))
    expect("durante un salto all'ultimo brano, ripeti tutto: al primo della coda", next(lastSkip, "all", shown = 4, skipping = true), Jump(0))
    expect("durante un salto all'ultimo brano, ripeti spento: niente", next(lastSkip, "off", shown = 4, skipping = true), Stay)
    expect("coda senza brano in corso", SkipPlan.next(null, SkipPlan.Queue(0, 0, 0, null, "off", false)), Next)

    println()
    println("Indietro")
    expect("nei primi 3 s: il brano prima", previous(big, "off", 1_000), Jump(9))
    expect("dopo 3 s: il brano da capo", previous(big, "off", 20_000), Restart)
    expect("nei primi 3 s, ripeti uno", previous(big, "one", 1_000), Jump(9))
    expect("finestra ridotta, nei primi 3 s, ripeti spento: il brano prima, che la finestra non ha", previous(reduced, "off", 1_000), Jump(9))
    expect("finestra ridotta, dopo 3 s: da capo", previous(reduced, "off", 20_000), Restart)
    // previous di Music Assistant va al brano prima con ogni ripeti, e dal primo resta sul primo: lo stesso, senza il suo
    // secondo di attesa.
    expect("finestra ridotta, nei primi 3 s, ripeti uno: il brano prima, come Music Assistant", previous(reduced, "one", 1_000), Jump(9))
    expect("finestra ridotta, nei primi 3 s, ripeti tutto: il brano prima, come Music Assistant", previous(reduced, "all", 1_000), Jump(9))
    expect("finestra ridotta, dopo 3 s, ripeti uno: da capo", previous(reduced, "one", 20_000), Restart)
    expect("nei primi 3 s, ripeti tutto: il brano prima", previous(big, "all", 1_000), Jump(9))
    expect("primo brano della coda, nei primi 3 s: da capo", previous(first, "off", 1_000), Restart)
    expect("primo brano della coda, ripeti uno, nei primi 3 s: da capo", previous(first, "one", 1_000), Restart)
    expect(
        "primo brano della coda, ripeti tutto (Media3 va all'ultimo della finestra): il primo, come Music Assistant (non il 50)",
        previous(first, "all", 1_000),
        Jump(0),
    )
    expect("primo brano della coda, ripeti tutto, dopo 3 s: da capo", previous(first, "all", 20_000), Restart)
    expect("brano prima chiesto per nome (seekToPreviousMediaItem), finestra ridotta", previous(reduced, "off", 20_000, mediaItem = true), Jump(9))
    expect("brano prima chiesto per nome, ripeti tutto, finestra ridotta", previous(reduced, "all", 20_000, mediaItem = true), Jump(9))
    expect("brano prima chiesto per nome, primo della coda: il primo, come Music Assistant", previous(first, "off", 1_000, mediaItem = true), Jump(0))
    // Durante un salto: avanti appena premuto (Media3 mostra l'11, posizione 0) e poi indietro.
    expect("durante un salto: dal brano mostrato", previous(big, "off", 0, shown = 4, skipping = true), Jump(10))
    expect(
        "durante un salto al primo della finestra: il brano prima nella coda",
        previous(big, "off", 0, shown = 0, skipping = true),
        Jump(6),
    )
    expect(
        "durante un salto, ripeti tutto, al primo della finestra (Media3 va all'ultimo): il brano prima nella coda",
        previous(big, "all", 0, shown = 0, skipping = true),
        Jump(6),
    )
    expect("coda senza brano in corso", SkipPlan.previous(null, true, true, SkipPlan.Queue(0, 0, 0, null, "off", false)), Previous)

    println()
    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(if (failures == 0) 0 else 1)
}

/** La finestra della coda che MaPlayer mostra a Media3: dove comincia, quanti brani, la coda intera e il brano in corso. */
private data class Window(val start: Int, val size: Int, val queue: Int, val current: Int) {
    fun view(shown: Int, repeat: String, skipping: Boolean) = SkipPlan.Queue(shown, start, queue, current, repeat, skipping)
}

private const val MAX_SEEK_TO_PREVIOUS_MS = 3_000L

/** Ripeti uno, per Media3, nella navigazione vale come spento (BasePlayer.getRepeatModeForNavigation). */
private fun navigation(repeat: String) = if (repeat == "one") "off" else repeat

private fun nextIndex(shown: Int, size: Int, repeat: String): Int? = when (navigation(repeat)) {
    "all" -> if (shown == size - 1) 0 else shown + 1
    else -> if (shown == size - 1) null else shown + 1
}

private fun previousIndex(shown: Int, size: Int, repeat: String): Int? = when (navigation(repeat)) {
    "all" -> if (shown == 0) size - 1 else shown - 1
    else -> if (shown == 0) null else shown - 1
}

/**
 * BasePlayer.seekToNext: l'indice chiesto (null: C.INDEX_UNSET, ignoreSeek), sempre dalla posizione di partenza
 * (seekToDefaultPosition, o repeatCurrentMediaItem se è lo stesso brano).
 */
private fun media3Next(shown: Int, size: Int, repeat: String): Int? = nextIndex(shown, size, repeat)

/** BasePlayer.seekToPrevious (brani non in diretta): l'indice chiesto e se la posizione è quella di partenza (C.TIME_UNSET). */
private fun media3Previous(shown: Int, size: Int, repeat: String, positionMs: Long): Pair<Int?, Boolean> {
    val previous = previousIndex(shown, size, repeat)
    if (previous != null && positionMs <= MAX_SEEK_TO_PREVIOUS_MS) return previous to true
    return shown to false // seekToCurrentItem(0)
}

/** BasePlayer.seekToPreviousMediaItem. */
private fun media3PreviousMediaItem(shown: Int, size: Int, repeat: String): Pair<Int?, Boolean> =
    previousIndex(shown, size, repeat) to true
