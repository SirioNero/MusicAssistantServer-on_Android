package harness

import it.xonos.ma.data.Account
import it.xonos.ma.data.Connection
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fuori casa ([MaRepository.HOME_CONNECT_S]): l'indirizzo di casa non risponde (un indirizzo dove i pacchetti si
 * perdono), quello remoto è il Music Assistant di prova. Quanto ci mette Xonos a collegarsi: con l'attesa di
 * prima erano circa 8 s. Variabili: MA_URL, MA_TOKEN (o MA_USER e MA_PASSWORD), HOME_URL.
 */
fun main(): Unit = runBlocking {
    val remote = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val home = System.getenv("HOME_URL") ?: "http://10.255.255.1:8095"
    val token = System.getenv("MA_TOKEN") ?: Account(MaRepository(Settings())).login(
        remote,
        System.getenv("MA_USER") ?: "prova",
        System.getenv("MA_PASSWORD") ?: "prova-xonos",
        "Xonos (prova fuori casa)",
    ).token
    val settings = Settings().apply { save(Settings.Config(home, remote, token)) }
    val repo = MaRepository(settings)
    val start = System.nanoTime()
    repo.acquire()
    val ok = withTimeoutOrNull(30_000) {
        while (repo.connection.value !is Connection.Connected) delay(50)
        true
    } == true
    val ms = (System.nanoTime() - start) / 1_000_000
    val url = (repo.connection.value as? Connection.Connected)?.url
    val good = ok && url == remote && ms < (MaRepository.HOME_CONNECT_S + 2) * 1000
    println((if (good) "OK   " else "MALE ") + "Fuori casa: collegato al remoto in $ms ms ($url)")
    repo.release()
    kotlin.system.exitProcess(if (good) 0 else 1)
}
