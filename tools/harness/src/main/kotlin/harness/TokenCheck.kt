package harness

import it.xonos.ma.data.Account
import it.xonos.ma.data.Connection
import it.xonos.ma.data.MaClient
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.data.TokenInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Il rinnovo del token ([MaRepository.renewToken]) contro Music Assistant 2.10: il token a lunga durata che Xonos si
 * fa dare scade dopo un anno; collegati a meno di 30 giorni dalla scadenza (l'ora del repository spostata avanti)
 * Xonos ne chiede uno nuovo con lo stesso nome e lo salva, e tutti e due funzionano. Lontano dalla scadenza non
 * cambia niente. Variabili: MA_URL, MA_USER, MA_PASSWORD.
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val user = System.getenv("MA_USER") ?: "prova"
    val password = System.getenv("MA_PASSWORD") ?: "prova-xonos"
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "OK   " else "MALE ") + name + if (detail.isNotEmpty()) ": $detail" else "")
        if (!ok) failures++
    }
    val day = 86_400L
    val now = System.currentTimeMillis() / 1000

    val first = Account(MaRepository(Settings())).login(url, user, password, "Xonos (prova token)")
    val info = TokenInfo.read(first.token)
    check("Token letto", info != null, "$info")
    check("Token a lunga durata col nome di Xonos", info?.longLived == true && info.name == "Xonos (prova token)")
    val days = ((info?.expiresAt ?: 0) - now) / day
    check("Scade dopo un anno (Music Assistant 2.10)", days in 364..365, "$days giorni")
    check("Non un JWT: niente", TokenInfo.read("abc") == null && TokenInfo.read("a.b.c") == null)

    suspend fun connected(repo: MaRepository) = withTimeoutOrNull(20_000) {
        while (repo.connection.value !is Connection.Connected) delay(100)
        true
    } == true

    // Lontano dalla scadenza: il token resta quello.
    val calm = Settings().apply { save(Settings.Config(url, "", first.token)) }
    val repoCalm = MaRepository(calm)
    repoCalm.acquire()
    check("Collegato col token", connected(repoCalm))
    delay(3_000)
    check("Lontano dalla scadenza: stesso token", calm.load().token == first.token)
    repoCalm.release()

    // A 25 giorni dalla scadenza: un token nuovo, salvato.
    val settings = Settings().apply { save(Settings.Config(url, "", first.token)) }
    val repo = MaRepository(settings)
    repo.clock = { (info!!.expiresAt - 25 * day) * 1000 }
    repo.acquire()
    check("Collegato vicino alla scadenza", connected(repo))
    val fresh = withTimeoutOrNull(10_000) {
        while (settings.load().token == first.token) delay(100)
        settings.load().token
    }
    check("Token nuovo salvato", fresh != null)
    repo.release()
    val freshInfo = fresh?.let { TokenInfo.read(it) }
    check(
        "Il nuovo: stesso nome, a lunga durata, un anno",
        freshInfo?.name == "Xonos (prova token)" && freshInfo.longLived && (freshInfo.expiresAt - now) / day in 364..365,
        "$freshInfo",
    )
    val http = OkHttpClient()
    for ((name, token) in listOf("nuovo" to fresh.orEmpty(), "vecchio" to first.token)) {
        val client = MaClient(http, "en_US")
        val ok = runCatching { client.connect(url, token) }.isSuccess
        client.close()
        check("Il token $name funziona", ok)
    }
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()

    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
