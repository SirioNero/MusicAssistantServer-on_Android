package it.xonos.ma.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manda i salti (avanti, indietro, un brano della coda) a Music Assistant uno alla volta, sempre
 * verso l'ultimo brano chiesto. Due comandi insieme Music Assistant li esegue in parallelo, e
 * l'ultimo a finire vince: può essere il primo, e la coda resta sul brano sbagliato.
 *
 * Con un servizio che dà un flusso alla volta (vedi [QueueItem.singleStream]) si aspetta anche che
 * i tocchi si fermino per un po' (il gatherMs di [request]): ogni salto fa partire un flusso, e una raffica
 * finirebbe in "limite di flussi raggiunto". Quell'attesa si paga a ogni tocco, anche da solo: [GATHER_MS] con
 * un Music Assistant qualsiasi (protegge Tidal, un flusso solo), [GATHER_PHONE_MS] con quello sul telefono, che
 * Tidal non ce l'ha e tiene Spotify (Soloist) acceso tra un brano e l'altro. Con gli altri servizi si salta subito.
 */
class SkipSender(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Scrive nel registro quando manda e quando Music Assistant risponde (vedi [SkipTrace]): per il lettore del telefono. */
    private val trace: Boolean = false,
    private val send: suspend (index: Int) -> Unit,
) {
    private val lock = Any()
    private var target: Int? = null
    private var gatherMs = 0L
    private var lastAt = 0L
    private var running = false
    private val waiting = mutableListOf<() -> Unit>()

    /**
     * Salta al brano [index] della coda, dopo [gatherMs] senza altri tocchi (0: subito); [done] quando il
     * comando è arrivato (o è confluito in uno dopo).
     */
    fun request(index: Int, gatherMs: Long, done: () -> Unit = {}) {
        synchronized(lock) {
            target = index
            this.gatherMs = gatherMs
            lastAt = clock()
            waiting += done
            if (running) return
            running = true
        }
        scope.launch { pump() }
    }

    private suspend fun pump() {
        while (true) {
            var wait = 0L
            var index: Int? = null
            var done: List<() -> Unit> = emptyList()
            synchronized(lock) {
                wait = if (gatherMs > 0) lastAt + gatherMs - clock() else 0L
                if (wait <= 0) {
                    index = target
                    target = null
                    done = waiting.toList()
                    waiting.clear()
                    if (index == null) running = false
                }
            }
            if (wait > 0) {
                delay(wait)
                continue
            }
            val next = index ?: return
            if (trace) Log.i(TAG, "Salto: mando $next " + (SkipTrace.sinceTouch()?.let { "dopo $it ms dal tocco" } ?: "(nessun tocco recente)"))
            val sentAt = clock()
            try {
                // Mandato, il comando va finito: solo dopo può partire il prossimo.
                withContext(NonCancellable) { send(next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lo stato vero torna con gli eventi della coda.
            }
            if (trace) Log.i(TAG, "Salto: Music Assistant ha risposto in ${clock() - sentAt} ms${SkipTrace.suffix()}")
            done.forEach { it() }
        }
    }

    companion object {
        /** Quanto si aspetta un altro tocco prima di saltare, con i servizi a un flusso alla volta. */
        const val GATHER_MS = 400L

        /**
         * Lo stesso col Music Assistant sul telefono: lì i servizi a un flusso sono Spotify con Soloist, che resta acceso
         * tra un brano e l'altro e cambia brano in fretta, e Pandora. I salti partono comunque uno alla volta.
         */
        const val GATHER_PHONE_MS = 250L

        private const val TAG = "Xonos"
    }
}
