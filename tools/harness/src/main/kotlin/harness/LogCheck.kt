package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.PhoneModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

/**
 * "Registro di Music Assistant": il registro del server collegato (`logging/get`), tagliato alle ultime
 * righe intere, e dal `PhoneModel` con versione, nome e indirizzo del server in testa.
 */
fun main(): Unit = runBlocking {
    System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)
    repo.acquire()
    val connected = withTimeout(15_000) { repo.connection.first { it is Connection.Connected } } as Connection.Connected
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    val whole = library.serverLog(Int.MAX_VALUE)
    check("Il registro intero arriva", whole.contains("music_assistant"), "${whole.length} caratteri")
    val tail = library.serverLog(2_000)
    check("Le ultime righe: al massimo quanto chiesto", tail.length in 1..2_000, "${tail.length} caratteri")
    check("Da una riga intera, la fine del registro", whole.endsWith(tail) && whole[whole.length - tail.length - 1] == '\n')

    val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + main)
    val model = PhoneModel(repo, library, settings, ScreenCache(null), FixedFinder(), "Xonos (prova)", { "xonos-log" }, MutableStateFlow(true), scope)
    val shared = CompletableDeferred<String>()
    withContext(main) { model.shareServerLog { shared.complete(it) } }
    val text = withTimeout(30_000) { shared.await() }
    val head = text.lineSequence().first()
    check(
        "Dal telefono: versione, nome e indirizzo in testa",
        head == "Music Assistant ${connected.server.version} (${connected.server.name}) ${connected.url}",
        head,
    )
    check("Poi le ultime righe del registro", text.length > head.length + 100 && text.length <= head.length + 2 + Library.LOG_CHARS)
    check("Finito, il tasto torna libero", withContext(main) { !model.fetchingServerLog })

    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    repo.release()
    main.close()
    System.exit(0)
}
