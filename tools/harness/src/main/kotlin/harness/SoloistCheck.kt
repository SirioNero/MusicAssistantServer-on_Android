package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Settings
import it.xonos.ma.data.SoloistUnlock
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Lo sblocco di Spotify (Soloist) occupato da un ascolto appeso, senza Spotify: al suo posto il
 * servizio dei file del Music Assistant di prova. L'errore `soloist_session_busy` si finge.
 * 1. si riconosce l'errore (dal nome e dal testo inglese), non gli altri;
 * 2. se un altro lettore sta suonando quel servizio non si tocca niente e l'errore resta;
 * 3. altrimenti il servizio si ricarica e il comando si riprova una volta, e riesce;
 * 4. un secondo errore subito dopo non ricarica di nuovo, riprova soltanto.
 * Le ricariche si vedono nel registro di Music Assistant ("Loaded music provider").
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val playerId = "xonos-soloistcheck"
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    // 1. L'errore.
    val busy = MaException("Spotify [Francesco] is already playing something else", key = "soloist_session_busy", args = listOf("Spotify [Francesco]"))
    check("Riconosce soloist_session_busy", SoloistUnlock.busy(busy))
    check("Riconosce il testo inglese", SoloistUnlock.busy(MaException("Spotify is already playing something else. Spotify Soloist plays one thing at a time, so stop the other player first.")))
    // Music Assistant 2.10 manda gli errori già tradotti, senza translation_key: i testi delle sue traduzioni e di
    // quella italiana del server sul telefono (server/ma/ma-strings.json), con {0} = il nome del servizio.
    val translated = mapOf(
        "it" to "Spotify sta già suonando qualcos'altro. Spotify Soloist suona una cosa alla volta: ferma prima l'altro lettore.",
        "de" to "Spotify spielt bereits etwas anderes ab. Spotify Soloist spielt jeweils nur einen Titel ab. Beende daher zunächst das andere Wiedergabegerät.",
        "es" to "Spotify ya está reproduciendo otra cosa. Spotify Soloist solo puede reproducir una cosa a la vez, así que detén primero el otro reproductor.",
        "nl" to "Spotify speelt al iets anders af. Spotify Soloist speelt slechts één stream tegelijk af, dus stop eerst de andere speler.",
        "ru" to "Spotify уже воспроизводит что-то другое. Spotify Soloist воспроизводит только один трек за раз, поэтому сначала остановите другой плеер.",
        "sr" to "Spotify već reprodukuje nešto drugo. Spotify Soloist reprodukuje samo jednu stvar istovremeno, pa prvo zaustavite drugi plejer.",
    )
    for ((lang, text) in translated) check("Riconosce il testo tradotto ($lang)", SoloistUnlock.busy(MaException(text)))
    check("Non scambia altri errori", !SoloistUnlock.busy(MaException("No playable items found")) && !SoloistUnlock.busy(IllegalStateException("x")))
    check("Non scambia altri errori di Soloist", !SoloistUnlock.busy(MaException("The Spotify Soloist daemon could not be stopped")))

    // 2. Un lettore suona davvero un brano dei file.
    val engine = AudioEngine({ r, c -> FakeOutput(r, c) }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val identity = SendspinClient.Identity(playerId, "Prova Soloist", "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), 800, 300, 50, false)
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    val track = library.search("brava")["tracks"]?.first() ?: error("nessun brano")
    library.playMedia(playerId, listOf(track.uri))
    val playing = withTimeoutOrNull(20_000) {
        while (QueueState.from(repo.command("player_queues/get") { put("queue_id", playerId) } as? JSONObject)?.playing != true) delay(300)
        true
    } == true
    check("Il lettore di prova suona", playing)

    val unlock = SoloistUnlock(repo, domain = "filesystem_local")
    val t0 = System.currentTimeMillis()
    var calls = 0
    val kept = runCatching { unlock.retrying("altra-coda") { calls++; throw busy } }
    check(
        "Un altro lettore lo sta suonando: niente ricarica, resta l'errore",
        kept.exceptionOrNull() === busy && calls == 1,
        "chiamate $calls",
    )

    // 3. Il lettore occupato è il nostro (l'ascolto appeso): ricarica e riprova.
    calls = 0
    val result = runCatching { unlock.retrying(playerId) { if (calls++ == 0) throw busy else "suona" } }
    val reloadMs = System.currentTimeMillis() - t0
    check("Ricarica il servizio e riprova: riesce", result.getOrNull() == "suona" && calls == 2, "chiamate $calls, ${reloadMs} ms")
    val services = (repo.command("config/providers") as org.json.JSONArray)
    val loaded = (0 until services.length()).map { services.getJSONObject(it) }
        .filter { it.optString("domain") == "filesystem_local" }
    check("Il servizio dopo la ricarica c'è ed è acceso", loaded.isNotEmpty() && loaded.all { it.optBoolean("enabled") })

    // 4. Subito dopo: niente seconda ricarica.
    calls = 0
    val again = runCatching { unlock.retrying(playerId) { if (calls++ == 0) throw busy else "ancora" } }
    check("Un secondo errore subito dopo: riprova senza ricaricare", again.getOrNull() == "ancora" && calls == 2)
    println("Inizio delle prove di ricarica: ${java.time.Instant.ofEpochMilli(t0)}")

    repo.command("player_queues/stop") { put("queue_id", playerId) }
    client.stop()
    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    repo.release()
    System.exit(0)
}
