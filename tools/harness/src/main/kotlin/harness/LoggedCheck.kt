package harness

import it.xonos.ma.data.Logged
import it.xonos.ma.data.MaClient
import it.xonos.ma.data.MaException
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.file.Files

/**
 * Cosa può finire nel logcat nelle build vere (Logged.details spento): le parole piantate negli errori, negli id del
 * menu, nei testi, nei messaggi rotti di Music Assistant e nella cache rovinata non devono arrivarci. Senza server.
 */
private val PLANTED = listOf("Canzone segreta", "Mario Rossi", "casa.example.org", "192.168.1.5", "vasco", "4uLU6hMC", "Musica")

fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    fun clean(text: String) = PLANTED.none { text.contains(it, ignoreCase = true) }

    Logged.details = false

    // ---------- Errori ----------
    val refused = MaException(
        "There is nothing to play here: Canzone segreta di Mario Rossi",
        0,
        Problem.REFUSED,
        "Canzone segreta",
        "xonos_no_playable_items",
        listOf("Canzone segreta"),
    )
    val said = Logged.error(refused)
    check("Errore di Music Assistant: problema e nome, senza il testo", said == "REFUSED xonos_no_playable_items" && clean(said), said)
    val coded = Logged.error(MaException("Token di Mario Rossi", 20, Problem.BAD_TOKEN))
    check("Errore con codice: il codice resta", coded == "BAD_TOKEN 20", coded)
    val network = Logged.error(ConnectException("Failed to connect to casa.example.org/192.168.1.5:8095"))
    check("Errore di rete: solo la classe", network == "ConnectException", network)
    val host = Logged.error(UnknownHostException("Unable to resolve host \"casa.example.org\""))
    check("Indirizzo sconosciuto: solo la classe", host == "UnknownHostException", host)

    // ---------- Id del menu ----------
    val ids = mapOf(
        "find|tracks|vasco rossi" to "find",
        "play|spotify://track/4uLU6hMCjMI75M1A2tKUQC" to "play track",
        "dir|/storage/emulated/0/Musica/Mario Rossi/Canzone segreta" to "dir",
        "in|library://album/12|filesystem_local://track/Musica/Canzone segreta.flac" to "in album",
        "open|filesystem_local://artist/Mario Rossi" to "open artist",
        "tracks|spotify://artist/4uLU6hMC" to "tracks artist",
        "list|cat|tracks|7" to "list",
        "" to "(vuoto)",
    )
    for ((id, expected) in ids) {
        val shown = Logged.id(id)
        check("Id \"${id.substringBefore('|')}\": solo la forma", shown == expected && clean(shown), shown)
    }
    check("Id mancante", Logged.id(null) == "(vuoto)")

    // ---------- Testi ----------
    check("Testo: nelle build vere quello corto", Logged.text("Mario Rossi su http://casa.example.org:8095", "") == "")
    check("Testo: nelle build vere quello corto (non vuoto)", Logged.text("failed RuntimeError: Canzone segreta", "failed RuntimeError") == "failed RuntimeError")

    // ---------- Pile degli errori ----------
    val nested = RuntimeException("Non trovo Canzone segreta", IOException("casa.example.org/192.168.1.5", MaException("Mario Rossi")))
    val trace = Logged.trace(nested)
    val printed = StringWriter().also { trace.printStackTrace(PrintWriter(it)) }.toString()
    check("Pila: niente testi", clean(trace.toString()) && clean(trace.message.orEmpty()) && clean(printed), printed.lineSequence().first())
    check(
        "Pila: restano le classi e i punti del codice",
        printed.contains("java.lang.RuntimeException") && printed.contains("java.io.IOException") &&
            printed.contains("MaException") && printed.contains("LoggedCheck"),
    )

    // ---------- Build di prova ----------
    Logged.details = true
    check("Build di prova: l'errore col suo testo", Logged.error(refused).contains("Canzone segreta"))
    check("Build di prova: l'id intero", Logged.id("find|tracks|vasco rossi") == "find|tracks|vasco rossi")
    check("Build di prova: il testo lungo", Logged.text("Mario Rossi", "") == "Mario Rossi")
    check("Build di prova: la pila vera", Logged.trace(nested) === nested)
    Logged.details = false

    // ---------- Messaggi rotti di Music Assistant ----------
    val client = MaClient(OkHttpClient())
    val listener = MaClient::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(client) as WebSocketListener
    val socket = FakeSocket()
    val broken = """{"message_id": "7", "partial": true, "result": [{"name": "Canzone segreta", "artists": [{"name": "Mario Rossi"}]"""
    val brokenLog = captured { listener.onMessage(socket, broken) }
    check("JSON rotto: la classe e la lunghezza, non il messaggio", brokenLog.contains("Messaggio non valido") && brokenLog.contains("${broken.length} caratteri") && clean(brokenLog), brokenLog.trim())
    client.onEvent = { _, _, _ -> throw IllegalStateException("Evento di Mario Rossi: Canzone segreta") }
    val eventLog = captured { listener.onMessage(socket, """{"event": "queue_updated", "object_id": "x", "data": {"name": "Canzone segreta"}}""") }
    check("Evento che fallisce: la classe, non il testo", eventLog.contains("IllegalStateException") && clean(eventLog), eventLog.trim())

    // ---------- Cache rovinata ----------
    val dir = Files.createTempDirectory("xonos-cache").toFile()
    val cache = ScreenCache(dir)
    dir.resolve("home.json").writeText("""{"recent": [{"name": "Canzone segreta", "artist": "Mario Rossi"""")
    var read: Any? = Unit
    val cacheLog = captured { read = cache.read("home") }
    check("Cache rovinata: si butta e si dice solo quale", read == null && !dir.resolve("home.json").exists() && cacheLog.contains("home") && clean(cacheLog), cacheLog.trim())
    dir.deleteRecursively()

    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(if (failures == 0) 0 else 1)
}

/** Quello che [block] scrive su stdout, dove il Log finto del banco di prova manda le righe del logcat. */
private fun captured(block: () -> Unit): String {
    val before = System.out
    val buffer = ByteArrayOutputStream()
    System.setOut(PrintStream(buffer, true, "UTF-8"))
    try {
        block()
    } finally {
        System.setOut(before)
    }
    return buffer.toString("UTF-8")
}

private class FakeSocket : WebSocket {
    override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean = true
    override fun send(bytes: ByteString): Boolean = true
    override fun close(code: Int, reason: String?): Boolean = true
    override fun cancel() = Unit
}
