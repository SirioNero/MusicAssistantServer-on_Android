package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueItem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Settings
import it.xonos.ma.data.objects
import it.xonos.ma.data.taste.Journal
import it.xonos.ma.data.taste.Listen
import it.xonos.ma.playback.Listening
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
 * brano sentito suonare (a ogni brano nuovo, alla pausa, ogni 30 s, alla chiusura se ha suonato dopo l'ultima copia,
 * alla fine della coda), mai indietro senza suonare (la coda ripristinata dopo un distacco), il diario con finiti,
 * saltati e il luogo "auto" dai viaggi in auto ([Listening.carTrip]), l'ascolto in corso ritrovato da un processo nuovo,
 * la potatura all'avvio, l'interruttore, i numeri e "Cancella". Nel registro solo numeri. Con MA_TOKEN anche la riga di
 * diagnosi degli artisti e una coda vera di Music Assistant.
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

    fun queue(i: Int, playing: Boolean, elapsed: Double, duration: Int = 200, index: Int = i, items: Int = 12, state: String? = null, ended: Boolean = false): QueueState =
        QueueState.from(
            JSONObject()
                .put("queue_id", queueId)
                .put("state", state ?: if (playing) "playing" else "paused")
                .put("ended", ended)
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
    val pausedKey = copy()?.key
    now += 2_000
    feed(queue(1, playing = false, elapsed = 46.0), 46.0, force = true)
    check("Alla chiusura (force) senza aver suonato dopo la pausa: la copia resta com'era (chiave e ora)", copy()?.key == pausedKey && copy()?.positionSec == 46.0, "${copy()?.key} ${copy()?.positionSec}")
    now += 600_000
    feed(queue(1, playing = false, elapsed = 46.0), 46.0, force = true)
    check("…anche 10 minuti dopo (la fine del primo piano): «Nascondi» regge", copy()?.key == pausedKey)
    now += 1_000
    feed(queue(1, playing = true, elapsed = 46.0), 46.0)
    now += 3_000
    feed(queue(1, playing = true, elapsed = 49.0), 49.0, force = true)
    check("Alla chiusura dopo aver suonato: sì", copy()?.positionSec == 49.0 && copy()?.key != pausedKey)
    now += 2_000
    feed(queue(1, playing = false, elapsed = 51.0), 51.0)
    check("Nel diario ancora niente (il brano è in pausa, non finito)", lines().isEmpty())

    // ---------- Il diario ----------
    // Riprende e arriva in fondo; poi il 2.
    now += 1_000
    feed(queue(1, playing = true, elapsed = 51.0), 51.0)
    now += 149_000
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

    // Il 2 saltato dopo 6 s; intanto arriva l'auto. Il servizio segna il viaggio quando un'app sfoglia il menu da fuori
    // e quando l'ultima se ne va (inizio e fine), e il diario lo usa per il luogo.
    now += 6_000
    feed(queue(2, playing = true, elapsed = 6.0), 6.0)
    now += 300
    listening.carTrip(true)
    val carStart = now
    feed(queue(3, playing = true, elapsed = 0.0), 0.0)
    all = lines()
    check("Saltato dopo 6 s: saltato", all.size == 2 && all[1].skipped && !all[1].finished, "${all.getOrNull(1)?.seconds} s")
    now += 100_000
    feed(queue(3, playing = true, elapsed = 100.0), 100.0)
    now += 1_000
    check("Viaggio in corso: aperto fino ad adesso", listening.cars.intervals(now).singleOrNull()?.last == now)
    listening.carTrip(false)
    now += 60_000
    listening.carTrip(false)
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

    // Spenta: niente di nuovo, nemmeno viaggi; riaccesa: di nuovo.
    enabled = false
    listening.carTrip(true)
    now += 1_000
    listening.carTrip(false)
    check("Cronologia spenta: nessun viaggio nuovo", listening.cars.intervals(now).size == 1)
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

    // ---------- La copia dopo un distacco, alla fine della coda ----------
    val other = Listening(Files.createTempDirectory("xonos-listening-copy").toFile(), { true }, inline) { now }
    fun feedOther(q: QueueState?, position: Double, force: Boolean = false) =
        other.update(server, q, { upcoming(q?.currentIndex ?: 0) }, position, force)
    feedOther(queue(1, playing = true, elapsed = 150.0, duration = 300), 150.0)
    now += 30_000
    feedOther(queue(1, playing = true, elapsed = 180.0, duration = 300), 180.0)
    // La rete cade: l'audio ricevuto suona ancora un po', poi si ferma; lo stato resta l'ultimo "playing".
    now += 15_000
    feedOther(queue(1, playing = true, elapsed = 180.0, duration = 300), 195.0)
    // Al ritorno Music Assistant ripristina la stessa voce col punto del suo ultimo salvataggio.
    now += 60_000
    feedOther(queue(1, playing = false, elapsed = 4.5, duration = 300, state = "idle"), 4.5)
    check("Coda ripristinata dopo un distacco (stessa voce a 4,5 s): la copia tiene il punto sentito", other.lastListen.read()?.positionSec == 195.0, "${other.lastListen.read()?.positionSec}")
    now += 600_000
    feedOther(queue(1, playing = false, elapsed = 4.5, duration = 300, state = "idle"), 4.5, force = true)
    check("…anche alla chiusura dell'app", other.lastListen.read()?.positionSec == 195.0, "${other.lastListen.read()?.positionSec}")
    // Riparte da lì: la posizione vale di nuovo quella che si sente, anche indietro.
    now += 1_000
    feedOther(queue(1, playing = true, elapsed = 195.0, duration = 300), 195.0)
    now += 2_000
    feedOther(queue(1, playing = true, elapsed = 10.0, duration = 300), 10.0)
    feedOther(queue(1, playing = false, elapsed = 10.0, duration = 300), 10.0)
    check("Spostato indietro mentre suona, poi pausa: la copia segue", other.lastListen.read()?.positionSec == 10.0, "${other.lastListen.read()?.positionSec}")
    // L'ultimo brano della coda finisce: prima lo stato fermo, qualche secondo dopo "ended" (con la posizione a 0).
    now += 1_000
    feedOther(queue(11, playing = true, elapsed = 190.0, duration = 200, index = 11), 190.0)
    now += 9_900
    feedOther(queue(11, playing = false, elapsed = 199.9, duration = 200, index = 11, state = "idle"), 199.9)
    check("Fine dell'ultimo brano: copia ferma, non ancora finita", other.lastListen.read()?.let { !it.ended && it.queueItemId == "voce-11" } == true)
    now += 5_000
    feedOther(queue(11, playing = false, elapsed = 0.0, duration = 200, index = 11, state = "idle", ended = true), 0.0)
    val ended = other.lastListen.read()
    check("Music Assistant segna la fine della coda: la copia dice finita (Ascolta di nuovo)", ended?.ended == true && ended.positionSec == 199.9, "${ended?.ended} ${ended?.positionSec}")
    val endedKey = ended?.key
    now += 600_000
    feedOther(queue(11, playing = false, elapsed = 0.0, duration = 200, index = 11, state = "idle", ended = true), 0.0, force = true)
    check("…e alla chiusura non si riscrive", other.lastListen.read()?.key == endedKey)

    // ---------- L'ascolto in corso, se il processo muore ----------
    val crashDir = Files.createTempDirectory("xonos-listening-crash").toFile()
    val pendingFile = File(Journal.at(crashDir), Listening.PENDING_FILE)
    val dying = Listening(crashDir, { true }, inline) { now }
    dying.update(server, queue(1, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
    now += 5_000
    dying.update(server, queue(1, playing = true, elapsed = 5.0), { emptyList() }, 5.0)
    check("Copia dell'ascolto in corso: subito appena si sente", pendingFile.exists())
    now += 30_000
    dying.update(server, queue(1, playing = true, elapsed = 35.0), { emptyList() }, 35.0)
    now += 30_000
    dying.update(server, queue(1, playing = true, elapsed = 65.0), { emptyList() }, 65.0)
    now += 20_000
    // Il processo muore qui (aggiornamento, Android): nessuna chiusura. Il processo dopo la trova.
    val reborn = Listening(crashDir, { true }, inline) { now }
    val recovered = reborn.journal.read(server, 0)
    check("Processo morto mentre suonava: l'ascolto c'è nel processo dopo (65 s)", recovered.size == 1 && recovered[0].seconds in 64.0..66.0 && !recovered[0].finished && !recovered[0].skipped, recovered.joinToString { "${it.seconds}/${it.finished}" })
    check("…e la copia dell'ascolto in corso non c'è più", !pendingFile.exists())
    val reborn2 = Listening(crashDir, { true }, inline) { now }
    check("…una volta sola", reborn2.journal.read(server, 0).size == 1)
    // Chiuso per bene: niente copia, e niente doppioni.
    reborn2.update(server, queue(2, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
    now += 40_000
    reborn2.update(server, queue(2, playing = true, elapsed = 40.0), { emptyList() }, 40.0)
    reborn2.update(server, queue(3, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
    check("Ascolto chiuso: nel diario, e la sua copia via", reborn2.journal.read(server, 0).size == 2 && !pendingFile.exists())
    // La copia scritta e la chiusura vera entrambe nel diario (il processo muore fra le due): se ne legge una, la più avanti.
    Journal.at(crashDir).mkdirs()
    val partial = Listen(now - 50_000, "spotify--Ab12://track/sp9", "library://track/9", "Brano segreto 9", "track", emptyList(), null, 200, 20.0, false, false, null, Journal.PLACE_PHONE)
    reborn2.journal.add(server, partial.copy(seconds = 45.0))
    pendingFile.writeText(JSONObject().put("server_id", server).put("listen", partial.toJson()).toString())
    val reborn3 = Listening(crashDir, { true }, inline) { now }
    val nine = reborn3.journal.read(server, 0).filter { it.libraryUri == "library://track/9" }
    check("Copia e chiusura dello stesso ascolto: uno solo, quello più avanti", nine.size == 1 && nine[0].seconds == 45.0, nine.joinToString { "${it.seconds}" })
    pendingFile.writeText("{\"server_id\": \"x\", \"listen\": {\"name\": \"Brano segreto")
    Listening(crashDir, { true }, inline) { now }
    check("Copia rovinata: via, senza errori", !pendingFile.exists())
    // Cronologia spenta: la copia non si usa.
    pendingFile.writeText(JSONObject().put("server_id", server).put("listen", partial.copy(timeMs = now - 10_000).toJson()).toString())
    val offAfter = Listening(crashDir, { false }, inline) { now }
    check("Cronologia spenta: la copia dell'ascolto in corso si butta", !pendingFile.exists() && offAfter.journal.read(server, 0).size == reborn3.journal.read(server, 0).size)

    // ---------- Pausa lunga: l'app lascia il primo piano, poi si riprende ----------
    val pauseDir = Files.createTempDirectory("xonos-listening-pause").toFile()
    val paused = Listening(pauseDir, { true }, inline) { now }
    paused.update(server, queue(1, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
    now += 60_000
    paused.update(server, queue(1, playing = false, elapsed = 60.0), { emptyList() }, 60.0)
    now += 600_000
    // Come onUpdateNotification alla fine del primo piano.
    paused.update(server, queue(1, playing = false, elapsed = 60.0), { emptyList() }, 60.0, force = true)
    paused.flush()
    now += 300_000
    paused.update(server, queue(1, playing = true, elapsed = 60.0), { emptyList() }, 60.0)
    now += 140_000
    paused.update(server, queue(1, playing = true, elapsed = 200.0), { emptyList() }, 200.0)
    paused.update(server, queue(2, playing = true, elapsed = 0.0), { emptyList() }, 0.0)
    val one = paused.journal.read(server, 0)
    check("Pausa di 15 minuti con la fine del primo piano: un ascolto solo, finito", one.size == 1 && one[0].finished && one[0].seconds in 199.0..200.0, one.joinToString { "${it.seconds}/${it.finished}" })

    // ---------- La potatura all'avvio, anche con la cronologia spenta ----------
    val pruneDir = Files.createTempDirectory("xonos-listening-prune").toFile()
    val oldMonth = File(Journal.at(pruneDir), "journal/$server/2024-01.jsonl").also { it.parentFile.mkdirs(); it.writeText("{}\n") }
    val keptMonth = File(Journal.at(pruneDir), "journal/$server/2026-10.jsonl").also { it.writeText("{}\n") }
    Listening(pruneDir, { false }, inline) { now }
    check("All'avvio i mesi di più di un anno si tolgono (cronologia spenta)", !oldMonth.exists() && keptMonth.exists())

    // ---------- La diagnosi una volta per versione ----------
    val counted = Listening.ArtistCounts(1200, 800, 950)
    val savedLine = Listening.saveDiagnosis("$server/58", counted)
    check(
        "Diagnosi salvata: la stessa versione e lo stesso server la ritrovano, gli altri no",
        Listening.savedDiagnosis(savedLine, "$server/58") == counted && Listening.savedDiagnosis(savedLine, "$server/59") == null &&
            Listening.savedDiagnosis(savedLine, "altro/58") == null && Listening.savedDiagnosis(null, "$server/58") == null &&
            Listening.savedDiagnosis("$server/58 1 x 3", "$server/58") == null,
        savedLine,
    )

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
        "W/Xonos: Per te: ascolto in corso illeggibile (JSONException)",
    )
    val perTe = log.filter { it.contains("Per te:") }
    check("Nel registro le righe di Per te sono solo numeri", perTe.isNotEmpty() && perTe.all { it.replace(Regex("\\d+"), "N") in templates }, perTe.joinToString(" | "))
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
