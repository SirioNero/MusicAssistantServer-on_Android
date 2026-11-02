package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.PhoneModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Music Assistant manda [PhoneModel.SYNC_COMPLETED] quando finisce di leggere la libreria (music/sync): è il segnale con
 * cui Home e Libreria si aggiornano da sole. Variabili: MA_URL, MA_TOKEN.
 */
fun main(): Unit = runBlocking {
    val repo = MaRepository(Settings())
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val done = CompletableDeferred<Long>()
    val start = System.nanoTime()
    repo.addEventListener { event, _, _ -> if (event == PhoneModel.SYNC_COMPLETED) done.complete((System.nanoTime() - start) / 1_000_000) }
    repo.command("music/sync")
    val ms = withTimeoutOrNull(120_000) { done.await() }
    println((if (ms != null) "OK   " else "MALE ") + "Evento ${PhoneModel.SYNC_COMPLETED} dopo music/sync: ${ms ?: "mai"} ms")
    repo.release()
    kotlin.system.exitProcess(if (ms != null) 0 else 1)
}
