package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueItem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Settings
import it.xonos.ma.data.objects
import it.xonos.ma.data.taste.Journal
import it.xonos.ma.phone.model.ServerKeeper
import it.xonos.ma.phone.model.ServerKeeper.Need
import it.xonos.ma.playback.Listening
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Dal lettore ai file (playback/Listening.kt), come fa il servizio: la copia dell'ultimo ascolto scritta solo per un
 * brano sentito suonare (a ogni brano nuovo, alla pausa, ogni 30 s, alla chiusura), il diario con finiti, saltati e il
 * luogo "auto" dagli intervalli che segna ServerKeeper col bisogno CAR, l'interruttore, i numeri e "Cancella". Nel
 * registro solo numeri. Con MA_TOKEN anche la riga di diagnosi degli artisti e una coda vera di Music Assistant.
 */
fun main(): Unit = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    // Il registro (lo stub di Log scrive su stdout): lo si tiene per controllare che non ci siano titoli né uri.
    val realOut = System.out
    val captured = ByteArrayOutputStream()
    System.setOut(PrintStream(object : java.io.OutputStream() {
        override fun write(b: Int) {
            captured.write(b)
            realOut.write(b)
        }
    }, true, "UTF-8"))

    val root = Files.createTempDirectory("xonos-listening").toFile()
    var now = 1_793_000_000_000L
    var enabled = true
    val inline = Executor { it.run() }
    val listening = Listening(root, { enabled }, inline) { now }
    val server = "a3a33a8c90bc40a68fb77cd4e7e640b7"
    val queueId = "telefono-prova"
    val lastFile = File(root, Listening.LAST_LISTEN_FILE)

    fun queue(i: Int, playing: Boolean, elapsed: Double, duration: Int = 200, index: Int = i, items: Int = 12): QueueState =
        QueueState.from(
            JSONObject()
                .put("queue_id", queueId)
                .put("state", if (playing) "playing" else "paused")
                .put("items", items)
                .put("current_index", index)
                .put("elapsed_time", elapsed)
                .put("shuffle_enabled", false)
                .put("repeat_mode", "off")
                .put("sources", JSONArray().put(JSONObject().put("uri", "library://playlist/4").put("name", "Viaggio in auto").put("media_type", "playlist")))
                .put(
                    "current_item",
                    JSONObject()
                        .put("queue_item_id", "voce-$i")
                        .put("name", "Brano segreto $i")
                        .put("duration", duration)
                        .put(
                            "media_item",
                            JSONObject()
                                .put("uri", "library://track/$i")
                                .put("name", "Brano segreto $i")
                                .put("media_type", "track")
                                .put("item_id", "$i")
                                .put("provider", "library")
                                .put("artists", JSONArray().put(JSONObject().put("uri", "library://artist/7").put("name", "Cantante Riservato").put("external_ids", JSONArray().put(JSONArray().put("musicbrainz_artistid").put("mbid-7")))))
                                .put("album", JSONObject().put("uri", "library://album/3").put("name", "Album Privato"))
                                .put("provider_mappings", JSONArray().put(JSONObject().put("provider_instance", "spotify--Ab12").put("provider_domain", "spotify").put("item_id", "sp$i").put("available", true))),
                        ),
                ),
        )!!
    fun upcoming(i: Int) = (i + 1..i + 5).map { n ->
        QueueItem.from(JSONObject().put("queue_item_id", "voce-$n").put("media_item", JSONObject().put("uri", "library://track/$n").put("name", "Brano segreto $n").put("media_type", "track")))!!
    }
    fun feed(q: QueueState?, position: Double, force: Boolean = false, serverId: String? = server) =
        listening.update(serverId, q, { upcoming(q?.currentIndex ?: 0) }, position, force)
    fun copy(): LastListen? = listening.lastListen.read()
    fun lines() = listening.journal.read(server, 0)

    // ---------- La copia dell'ultimo ascolto ----------
    feed(queue(1, playing = true, elapsed = 0.0), 0.0, serverId = null)
    check("Prima della prima connessione: niente", !lastFile.exists() && listening.journal.servers().isEmpty())

    // All'apertura Music Assistant dà una coda in pausa, magari vecchia: non deve prendere il posto della copia.
    val old = LastListen.of(server, queue(9, playing = false, elapsed = 120.0), emptyList(), 120.0, now - 3_600_000)!!
    listening.lastListen.write(old)
    feed(queue(1, playing = false, elapsed = 5.0), 5.0)
    feed(queue(1, playing = false, elapsed = 5.0), 5.0, force = true)
    check("Una coda mai sentita suonare non tocca la copia (neanche alla chiusura)", copy()?.queueItemId == "voce-9", copy()?.queueItemId.orEmpty())

    feed(queue(1, playing = true, elapsed = 0.0), 0.0)
    check("Brano che suona: copia nuova", copy()?.queueItemId == "voce-1" && copy()?.positionSec == 0.0, "${copy()?.queueItemId} a ${copy()?.positionSec}")
    check("La copia ha sorgente e i 3 brani dopo", copy()?.source?.uri == "library://playlist/4" && copy()?.next?.map { it.uri } == listOf("library://track/2", "library://track/3", "library://track/4"))
    now += 10_000
    feed(queue(1, playing = true, elapsed = 10.0), 10.0)
    check("Dopo 10 s la copia non si rifà", copy()?.positionSec == 0.0)
    now += 20_000
    feed(queue(1, playing = true, elapsed = 30.0), 30.0)
    check("Dopo 30 s sì", copy()?.positionSec == 30.0, "${copy()?.positionSec}")
    now += 12_000
    feed(queue(1, playing = false, elapsed = 42.0), 42.0)
    check("Alla pausa sì", copy()?.positionSec == 42.0 && copy()?.timeMs == now, "${copy()?.positionSec}")
    now += 5_000
    feed(queue(1, playing = true, elapsed = 42.0), 42.0)
    now += 4_000
    feed(queue(1, playing = false, elapsed = 46.0), 46.0)
    check("Seconda pausa a pochi secondi dalla prima: di nuovo", copy()?.positionSec == 46.0, "${copy()?.positionSec}")
    now += 2_000
    feed(queue(1, playing = false, elapsed = 46.0), 51.0, force = true)
    check("Alla chiusura (force) per la voce sentita: sì", copy()?.positionSec == 51.0)
    check("Nel diario ancora niente (il brano è in pausa, non finito)", lines().isEmpty())

    // ---------- Il diario ----------
    // Riprende e arriva in fondo; poi il 2.
    now += 1_000
    feed(queue(1, playing = true, elapsed = 46.0), 46.0)
    now += 154_000
    feed(queue(1, playing = true, elapsed = 200.0), 200.0)
    now += 500
    feed(queue(2, playing = true, elapsed = 0.0), 0.0)
    var all = lines()
    check("Brano finito: una riga, finito", all.size == 1 && all[0].finished && !all[0].skipped, all.joinToString { "${it.uri} ${it.seconds}s fin=${it.finished}" })
    check("La riga ha uri del servizio, uri di libreria, artisti con mbid, album, contesto", all.firstOrNull()?.let {
        it.uri == "spotify--Ab12://track/sp1" && it.libraryUri == "library://track/1" && it.artists.firstOrNull()?.mbid == "mbid-7" &&
            it.album?.uri == "library://album/3" && it.context == "library://playlist/4"
    } == true)
    check("Luogo: telefono", all.firstOrNull()?.place == Journal.PLACE_PHONE)
    check("Il brano nuovo è la copia", copy()?.queueItemId == "voce-2")

    // Il 2 saltato dopo 6 s; intanto arriva l'auto. Il keeper segna il viaggio col bisogno CAR (inizio e fine), e il
    // diario lo usa per il luogo.
    val keeper = ServerKeeper(CoroutineScope(Dispatchers.Unconfined), used = { false }, start = {}, stop = {}, wallClock = { now })
    keeper.recordCars(listening.cars, inline)
    keeper.hold(Need.SCREEN)
    keeper.touch(Need.CAR)
    check("Un tocco dell'auto (il menu) non apre un viaggio", listening.cars.intervals(now).isEmpty())
    now += 6_000
    feed(queue(2, playing = true, elapsed = 6.0), 6.0)
    now += 300
    keeper.hold(Need.CAR)
    val carStart = now
    keeper.hold(Need.CAR)
    feed(queue(3, playing = true, elapsed = 0.0), 0.0)
    all = lines()
    check("Saltato dopo 6 s: saltato", all.size == 2 && all[1].skipped && !all[1].finished, "${all.getOrNull(1)?.seconds} s")
    now += 100_000
    feed(queue(3, playing = true, elapsed = 100.0), 100.0)
    now += 1_000
    keeper.release(Need.CAR)
    check("Due prese del bisogno CAR: il viaggio resta aperto finché non si lasciano tutte e due", listening.cars.intervals(now).singleOrNull()?.last == now)
    keeper.release(Need.CAR)
    now += 60_000
    keeper.release(Need.CAR)
    val trips = listening.cars.intervals(now)
    check("Un viaggio, chiuso quando l'auto se n'è andata", trips.size == 1 && trips[0].first == carStart && trips[0].last == carStart + 101_000, trips.toString())
    val saved = JSONObject(File(Journal.at(root), "car-sessions.json").readText())
    check("car-sessions.json: solo inizio e fine", saved.getJSONArray("sessions").getJSONArray(0).length() == 2 && saved.isNull("open"), saved.toString())
    feed(queue(4, playing = true, elapsed = 0.0), 0.0)
    all = lines()
    check("Il brano cominciato in auto: luogo auto", all.getOrNull(2)?.place == Journal.PLACE_CAR, all.getOrNull(2)?.place.orEmpty())

    // Il 4 suona 50 s, poi l'app si chiude: flush.
    now += 50_000
    feed(queue(4, playing = true, elapsed = 50.0), 50.0)
    now += 1_000
    feed(queue(4, playing = false, elapsed = 51.0), 51.0)
    listening.flush()
    all = lines()
    check("Alla chiusura l'ascolto in corso va nel diario (né finito né saltato)", all.size == 4 && !all[3].finished && !all[3].skipped && all[3].seconds in 49.0..52.0, "${all.getOrNull(3)?.seconds} s")

    // Spenta: niente di nuovo; riaccesa: di nuovo.
    enabled = false
    now += 1_000
    feed(queue(5, playing = true, elapsed = 0.0), 0.0)
    now += 199_000
    feed(queue(5, playing = true, elapsed = 199.0), 199.0)
    now += 1_000
    feed(queue(6, playing = true, elapsed = 0.0), 0.0)
    check("Cronologia spenta: niente righe", lines().size == 4)
    check("…ma la copia per Riprendi sì", copy()?.queueItemId == "voce-6")
    enabled = true
    now += 100_000
    feed(queue(6, playing = true, elapsed = 100.0), 100.0)
    now += 1_000
    feed(queue(7, playing = true, elapsed = 0.0), 0.0)
    check("Riaccesa: di nuovo", lines().size == 5)

    // I numeri della pagina e "Cancella la cronologia".
    val counts = listening.counts()
    check("Numeri: 5 ascolti, 5 brani, 1 artista", counts.listens == 5 && counts.tracks == 5 && counts.artists == 1 && counts.firstMs != null, counts.toString())
    now += 30_000
    feed(queue(7, playing = true, elapsed = 30.0), 30.0)
    listening.forget()
    listening.flush()
    check("Cancellata: niente ascolti, niente viaggi, e l'ascolto in corso non torna", listening.counts().listens == 0 && listening.cars.intervals(now).isEmpty() && lines().isEmpty())
    check("La copia per Riprendi resta (è la coda di Music Assistant)", copy() != null)
    now += 1_000
    feed(queue(8, playing = true, elapsed = 0.0), 0.0)
    now += 199_000
    feed(queue(8, playing = true, elapsed = 199.0), 199.0)
    now += 1_000
    feed(queue(9, playing = true, elapsed = 0.0), 0.0)
    check("Dopo la cancellazione si scrive di nuovo", lines().size == 1)

    // Con l'executor vero (un thread solo): le scritture restano in ordine e fuori dal thread di chi chiama.
    val threaded = Listening(Files.createTempDirectory("xonos-listening-t").toFile(), { true }) { now }
    for (i in 1..40) {
        threaded.update(server, queue(i, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
        now += 3_000
        threaded.update(server, queue(i, playing = true, elapsed = 3.0), { emptyList() }, 3.0)
        now += 100
    }
    threaded.flush()
    val tCounts = threaded.counts()
    val tLines = threaded.journal.read(server, 0)
    check("Thread delle scritture: 40 ascolti in ordine, 39 saltati", tCounts.listens == 40 && tLines.map { it.libraryUri } == (1..40).map { "library://track/$it" } && tLines.count { it.skipped } == 39, "${tCounts.listens}")

    // ---------- Con un Music Assistant vero ----------
    if (System.getenv("MA_TOKEN") != null) {
        val repo = MaRepository(Settings())
        repo.acquire()
        val connected = withTimeoutOrNull(15_000) { repo.connection.first { it is Connection.Connected } } as? Connection.Connected
        check("Collegato a Music Assistant", connected != null)
        if (connected != null) {
            val counted = Listening.artistCounts(repo)
            val total = (repo.command("music/artists/count") as? Number)?.toInt() ?: -1
            val line = Listening.diagnosis(counted)
            println("    $line")
            check("Diagnosi: tutti gli artisti della libreria contati", counted.artists == total && counted.withGenres <= total && counted.withMbid <= total, "$counted, count $total")
            check("Diagnosi: solo numeri", line.replace(Regex("\\d+"), "N") == "Per te: artisti N, con generi N, con MBID N")
            // Una coda vera, così come la manda Music Assistant: fino al diario e alla copia.
            val queues = (repo.command("player_queues/all") as? JSONArray).objects()
            val real = queues.mapNotNull(QueueState::from).firstOrNull { it.current?.uri != null }
            if (real == null) {
                println("    (nessuna coda con un brano in Music Assistant: salto la prova con una coda vera)")
            } else {
                val dir = Files.createTempDirectory("xonos-listening-real").toFile()
                val live = Listening(dir, { true }, inline) { now }
                val serverId = connected.server.id
                val playing = real.copy(state = "playing")
                live.update(serverId, playing, { emptyList() }, 0.0)
                now += 40_000
                live.update(serverId, playing, { emptyList() }, 40.0)
                live.flush()
                val got = live.journal.read(serverId, 0)
                val duration = playing.current?.durationSec ?: 0
                val expected = if (duration in 1..39) duration.toDouble() else 40.0
                check("Coda vera: una riga nel diario", got.size == 1 && got[0].seconds in expected - 1..expected + 1 && got[0].name == playing.current?.title, got.firstOrNull()?.let { "${it.mediaType} ${it.seconds}s, artisti ${it.artists.size}, contesto ${it.context != null}" }.orEmpty())
                check("Coda vera: la copia", live.lastListen.read()?.queueItemId == playing.current?.id && live.lastListen.read()?.serverId == serverId)
            }
        }
        repo.release()
    }

    // ---------- Il registro ----------
    System.out.flush()
    System.setOut(realOut)
    val log = captured.toString("UTF-8").lines().filter { it.startsWith("I/") || it.startsWith("W/") || it.startsWith("E/") || it.startsWith("D/") }
    val secret = listOf("Brano segreto", "Cantante Riservato", "Album Privato", "Viaggio in auto", "library://", "spotify--", "mbid-7", queueId, server)
    check("Nel registro nessun titolo, nome, uri o id", log.none { line -> secret.any { line.contains(it) } }, log.filter { line -> secret.any { line.contains(it) } }.joinToString(" | "))
    val templates = setOf(
        "I/Xonos: Per te: diario, ascolti scritti da quando Xonos è partito N (finiti N, saltati N)",
        "I/Xonos: Per te: artisti N, con generi N, con MBID N",
    )
    val perTe = log.filter { it.contains("Per te:") }
    check("Nel registro le righe di Per te sono solo numeri", perTe.isNotEmpty() && perTe.all { it.replace(Regex("\\d+"), "N") in templates }, perTe.joinToString(" | "))
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
