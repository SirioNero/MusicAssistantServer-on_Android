package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Settings
import it.xonos.ma.playback.Menu
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Ciò che si sceglie nel menu suona davvero: coda, brano corrente e audio che arriva. */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val playerId = "xonos-playcheck"
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val outputs = mutableListOf<FakeOutput>()
    val engine = AudioEngine({ r, c -> FakeOutput(r, c).also { outputs += it } }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    // MODERN=1: saluto e stato senza i campi superati (come col server sul telefono e Music Assistant 2.10.8 o più nuovo).
    val identity = SendspinClient.Identity(
        playerId, "Prova menu", "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), 800, 300, 50, false,
        modernWire = System.getenv("MODERN") == "1",
    )
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    val menu = Menu(library, XmlLabels(System.getenv("LANG_XONOS") ?: "en")) { playerId }

    suspend fun state(): QueueState? = QueueState.from(repo.command("player_queues/get") { put("queue_id", playerId) } as? JSONObject)
    suspend fun check(label: String, id: String, query: String? = null) {
        val before = outputs.sumOf { it.audioFrames.get() }
        val t0 = System.nanoTime()
        try {
            menu.play(id, query)
        } catch (e: Exception) {
            println("== $label: ERRORE ${e.message}")
            return
        }
        delay(5_000)
        val q = state()
        val heard = (outputs.sumOf { it.audioFrames.get() } - before) / 48000.0
        println("== $label (${(System.nanoTime() - t0) / 1_000_000} ms): ${q?.state} coda=${q?.items} #${q?.currentIndex} \"${q?.current?.title}\" di ${q?.current?.artist} · audio ${"%.1f".format(heard)} s")
    }

    // Prima si percorre ciò che serve, come farebbe l'auto.
    menu.children(Menu.ROOT)
    val letters = menu.children("cat|tracks")
    menu.children("cat|artists")
    val vasco = menu.search("vasco")
    val podcast = menu.search("morning").first { it.type == "podcast" }
    // Un podcast lungo si divide in cartelle anche lui: gli episodi della prima.
    val episodes = menu.children(podcast.id).let { list ->
        list.firstOrNull()?.takeIf { it.id.startsWith("part|") }?.let { menu.children(it.id) } ?: list
    }
    val genre = menu.children("cat|genres").firstOrNull()

    check("episodio di un podcast trovato con la ricerca", episodes[2].id)
    check("podcast intero", podcast.id)
    check("cartella Brani di una ricerca", vasco.first { it.id.startsWith("find|tracks|") }.id)
    check("brano dai risultati", vasco.first { it.id.startsWith("play|") }.id)
    // Una lettera con almeno sei brani (nella libreria di prova la "A" ne ha uno solo).
    // Oltre Menu.PART voci la lettera si divide in cartelle ("1–150"...): allora si entra nella prima.
    val letter = letters.first { l -> menu.children(l.id).let { it.size >= 6 || it.firstOrNull()?.id?.startsWith("part|") == true } }
    val inLetter = menu.children(letter.id).let { list ->
        list.firstOrNull()?.takeIf { it.id.startsWith("part|") }?.let { menu.children(it.id) } ?: list
    }
    check("brano 6 della lettera ${letter.title}, da lì in poi", inLetter[5].id)
    check("tutti i brani di un artista", menu.children(vasco.first { it.type == "artist" }.id).first { it.id.startsWith("tracks|") }.id)
    genre?.let { check("genere ${it.title}", it.id) }
    check("ricerca a voce: podcast tienimi bordone", "", "il podcast tienimi bordone")
    check("ricerca a voce: vasco rossi", "", "vasco rossi")
    check("ripresa", Menu.RESUME)
    repo.command("player_queues/stop") { put("queue_id", playerId) }
    delay(500)
    client.stop()
    engine.release()
    repo.release()
    kotlin.system.exitProcess(0)
}
