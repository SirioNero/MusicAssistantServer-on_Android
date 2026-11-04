package harness

import it.xonos.ma.data.ArtistRef
import it.xonos.ma.data.Ref
import it.xonos.ma.data.taste.CarSessions
import it.xonos.ma.data.taste.Journal
import it.xonos.ma.data.taste.Listen
import it.xonos.ma.data.taste.ListenRecorder
import it.xonos.ma.data.taste.NowListening
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Il diario degli ascolti (data/taste/Journal.kt), senza server: file mensili, saltati e finiti, luogo "auto" dagli
 * intervalli di ServerKeeper, un anno e non di più, 20.000 righe lette in meno di 300 ms, interruttore, "Cancella".
 */
fun main() {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    val zone = ZoneId.of("Europe/Rome")
    fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    val root = Files.createTempDirectory("xonos-journal").toFile()
    var now = at(2026, 11, 4)
    var enabled = true
    val dir = Journal.at(root)
    val journal = Journal(dir, { enabled }, zone) { now }
    val server = "a3a33a8c90bc40a68fb77cd4e7e640b7"

    fun listen(t: Long, i: Int = 0, seconds: Double = 200.0, finished: Boolean = true, skipped: Boolean = false) = Listen(
        timeMs = t,
        uri = "spotify--Ab12://track/t$i",
        libraryUri = "library://track/$i",
        name = "Brano $i",
        mediaType = "track",
        artists = listOf(ArtistRef("library://artist/${i % 40}", "Artista ${i % 40}", if (i % 3 == 0) "mbid-${i % 40}" else null)),
        album = Ref("library://album/${i % 90}", "Album ${i % 90}"),
        durationSec = 210,
        seconds = seconds,
        finished = finished,
        skipped = skipped,
        context = "library://playlist/4",
        place = Journal.PLACE_PHONE,
    )

    // ---------- File mensili ----------
    journal.add(server, listen(at(2026, 10, 31, 23, 50), 1))
    journal.add(server, listen(at(2026, 11, 1, 0, 10), 2))
    journal.add(server, listen(at(2026, 11, 3), 3))
    val serverDir = File(dir, "journal/$server")
    val files = serverDir.listFiles()?.map { it.name }?.sorted().orEmpty()
    check("Un file per mese (ora locale)", files == listOf("2026-10.jsonl", "2026-11.jsonl"), files.joinToString())
    val back = journal.read(server)
    check("Si rilegge tutto, in ordine", back.map { it.uri.substringAfterLast('/') } == listOf("t1", "t2", "t3"))
    check("Una riga uguale a com'era", back.first() == listen(at(2026, 10, 31, 23, 50), 1), back.first().toString())
    val line = File(serverDir, "2026-11.jsonl").readLines().first()
    check("Una riga è piccola (circa 300 byte)", line.length in 150..450, "${line.length} byte")
    println("    $line")
    check("Il diario di un altro server è a parte", journal.read("altro").isEmpty())

    // Il lettore delle righe fatto in casa: testi con virgolette, barre, a capo, accenti, cinese; campi in più annidati.
    val odd = listen(at(2026, 9, 2), 11).copy(
        name = "Ça \"va\" \\ bene\nè 你好 \u0001",
        artists = listOf(ArtistRef("library://artist/1", "Λένα «Ψ»", "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"), ArtistRef("library://artist/2", "", null)),
        album = null,
        context = null,
        libraryUri = null,
    )
    check("Testi strani: andata e ritorno", Listen.parse(odd.toJson().toString()) == odd, Listen.parse(odd.toJson().toString()).toString())
    val future = odd.toJson().put("mood", org.json.JSONObject().put("a", org.json.JSONArray().put("x]}").put(1.5e3))).put("rating", 4.5).put("tags", org.json.JSONArray())
    check("Campi di una versione nuova: si saltano", Listen.parse(future.toString()) == odd)
    check("Righe rotte: null", listOf("", "{}", "{\"uri\":\"x\"}", "[1,2]", "{\"time\":12,\"uri\":\"a", "nonsense").all { Listen.parse(it) == null })

    // Una riga rovinata (scrittura interrotta) si salta, le altre restano.
    File(serverDir, "2026-11.jsonl").appendText("{\"time\": 17305, \"uri\": \"spo")
    journal.add(server, listen(at(2026, 11, 4, 8), 4))
    val tolerant = journal.read(server)
    check("Riga a metà saltata, la riga dopo letta", tolerant.size == 4 && tolerant.last().uri.endsWith("t4"), "${tolerant.size} righe")

    // ---------- Interruttore ----------
    enabled = false
    journal.add(server, listen(at(2026, 11, 4, 9), 5))
    enabled = true
    check("Spento non scrive", journal.read(server).size == 4)

    // ---------- Conteggi ----------
    val counts = journal.counts(server)
    check("Conteggi", counts.listens == 4 && counts.tracks == 4 && counts.artists == 4 && counts.firstMs == at(2026, 10, 31, 23, 50) && counts.bytes > 0, counts.toString())

    // ---------- Un anno e non di più ----------
    journal.add(server, listen(at(2025, 10, 20), 6))
    journal.add(server, listen(at(2025, 11, 10), 7))
    journal.add(server, listen(at(2025, 11, 2), 8))
    now = at(2026, 11, 5)
    journal.prune(now)
    val afterPrune = serverDir.listFiles()?.map { it.name }?.sorted().orEmpty()
    check("Il mese finito da più di 365 giorni si cancella", "2025-10.jsonl" !in afterPrune && "2025-11.jsonl" in afterPrune, afterPrune.joinToString())
    val year = journal.read(server)
    check("Le righe di più di 365 giorni non si leggono", year.none { it.timeMs < now - 365 * 86_400_000L } && year.any { it.uri.endsWith("t7") } && year.none { it.uri.endsWith("t8") }, year.joinToString { it.uri.substringAfterLast('/') })
    check("Da una data: solo da lì", journal.read(server, at(2026, 11, 1, 0, 0)).map { it.uri.substringAfterLast('/') } == listOf("t2", "t3", "t4"))

    // ---------- Luogo: intervalli in auto ----------
    val cars = CarSessions(File(dir, CarSessions.FILE)) { now }
    cars.begin(at(2026, 11, 5, 8))
    cars.end(at(2026, 11, 5, 9))
    cars.begin(at(2026, 11, 5, 18))
    val reread = Journal(dir, { true }, zone) { at(2026, 11, 5, 18, 30) }
    check("In auto (intervallo chiuso)", reread.placeAt(at(2026, 11, 5, 8, 30)) == Journal.PLACE_CAR)
    check("Fuori dall'auto", reread.placeAt(at(2026, 11, 5, 12)) == Journal.PLACE_PHONE)
    check("In auto (intervallo ancora aperto)", reread.placeAt(at(2026, 11, 5, 18, 20)) == Journal.PLACE_CAR)
    check("Un intervallo aperto conta al massimo 4 ore", reread.cars.intervals(at(2026, 11, 6, 12)).last().last == at(2026, 11, 5, 22))

    // ---------- Il registratore: finiti, saltati, pause ----------
    val written = mutableListOf<Listen>()
    val recorder = ListenRecorder({ t -> reread.placeAt(t) }) { _, l -> written += l }
    fun np(item: String, pos: Double, playing: Boolean = true, queue: String = "xonos-1", duration: Int = 200, type: String = "track") =
        NowListening(server, queue, item, "spotify--Ab12://track/$item", null, "Brano $item", type, emptyList(), null, duration, "library://album/1", pos, playing)
    var t = at(2026, 11, 5, 8, 10)
    // Brano A suona fino in fondo, poi parte B (cambio naturale).
    recorder.update(np("A", 0.0), t)
    for (s in 1..19) { t += 10_000; recorder.update(np("A", s * 10.0), t) }
    t += 9_000
    recorder.update(np("B", 0.0), t)
    // B: avanti dopo 12 s (saltato).
    t += 12_000
    recorder.update(np("B", 12.0), t)
    t += 500
    recorder.update(np("C", 0.0), t)
    // C: 45 s, poi avanti (non saltato: oltre 30 s).
    t += 45_000
    recorder.update(np("C", 45.0), t)
    recorder.update(np("D", 0.0), t + 300)
    t += 300
    // D: 10 s, pausa; 20 minuti dopo un altro brano (non saltato: più di 10 s dopo).
    t += 10_000
    recorder.update(np("D", 10.0, playing = false), t)
    t += 20 * 60_000
    recorder.update(np("E", 0.0), t)
    // E: 8 s, poi un brano su un'altra coda (non saltato: un'altra coda).
    t += 8_000
    recorder.update(np("F", 0.0, queue = "altra"), t)
    // F: si sposta in avanti di 150 s dopo 5 s (non conta), poi 10 s e chiusura dell'app.
    t += 5_000
    recorder.update(np("F", 155.0, queue = "altra"), t)
    t += 10_000
    recorder.update(np("F", 165.0, queue = "altra"), t)
    recorder.flush(t)
    val byName = written.associateBy { it.name.removePrefix("Brano ") }
    fun show(k: String) = byName[k]?.let { "${"%.1f".format(it.seconds)} s fin=${it.finished} skip=${it.skipped} ${it.place}" } ?: "manca"
    check("A finito, non saltato, in auto", byName["A"]?.let { it.finished && !it.skipped && it.seconds in 195.0..200.0 && it.place == Journal.PLACE_CAR } == true, show("A"))
    check("B saltato (12 s, altro brano sulla stessa coda subito)", byName["B"]?.let { it.skipped && !it.finished && it.seconds in 11.0..13.0 } == true, show("B"))
    check("C non saltato (45 s)", byName["C"]?.let { !it.skipped && !it.finished } == true, show("C"))
    check("D non saltato (pausa, poi altro dopo 20 minuti)", byName["D"]?.let { !it.skipped && it.seconds in 9.0..11.0 } == true, show("D"))
    check("E non saltato (il brano dopo è su un'altra coda)", byName["E"]?.let { !it.skipped } == true, show("E"))
    check("F: lo spostamento in avanti non conta come ascolto", byName["F"]?.let { it.seconds in 14.0..16.5 } == true, show("F"))
    check("Ognuno una volta", written.size == 6 && written.map { it.name }.toSet().size == 6, written.joinToString { it.name })

    // Ripeti un brano: la stessa voce torna all'inizio = un ascolto nuovo.
    written.clear()
    t += 60_000
    recorder.update(np("R", 0.0, duration = 60), t)
    for (s in 1..6) { t += 10_000; recorder.update(np("R", s * 10.0, duration = 60), t) }
    t += 1_000
    recorder.update(np("R", 1.0, duration = 60), t)
    t += 20_000
    recorder.update(np("R", 21.0, duration = 60), t)
    recorder.update(null, t)
    check("Ripeti uno: due ascolti, il primo finito", written.size == 2 && written[0].finished && !written[1].finished, written.joinToString { "${it.seconds}/${it.finished}" })

    // Un brano mai suonato (la coda caricata in pausa) non si scrive.
    written.clear()
    recorder.update(np("P", 0.0, playing = false), t)
    recorder.update(np("Q", 0.0, playing = false), t + 2_000)
    recorder.update(null, t + 4_000)
    check("Mai suonato: niente", written.isEmpty())

    // Una pausa più lunga di 30 minuti chiude l'ascolto; la ripresa ne comincia uno nuovo.
    written.clear()
    t += 3_600_000
    recorder.update(np("L", 0.0, duration = 3000, type = "podcast_episode"), t)
    t += 600_000
    recorder.update(np("L", 600.0, playing = false, duration = 3000, type = "podcast_episode"), t)
    t += 3_600_000
    recorder.update(np("L", 600.0, duration = 3000, type = "podcast_episode"), t)
    t += 2_350_000
    recorder.update(np("L", 2950.0, duration = 3000, type = "podcast_episode"), t)
    recorder.flush(t)
    check(
        "Episodio: pausa lunga = due ascolti; il secondo finito (ultimo minuto)",
        written.size == 2 && !written[0].finished && written[1].finished && written[0].seconds in 590.0..610.0,
        written.joinToString { "${it.seconds}/${it.finished}" },
    )

    // ---------- 20.000 righe in meno di 300 ms ----------
    val big = Files.createTempDirectory("xonos-journal-big").toFile()
    now = at(2026, 11, 4)
    val bigJournal = Journal(Journal.at(big), { true }, zone) { now }
    val start = now - 360 * 86_400_000L
    val write0 = System.nanoTime()
    for (i in 0 until 20_000) bigJournal.add(server, listen(start + i * 1_555_000L, i, seconds = (i % 210).toDouble(), finished = i % 4 != 0, skipped = i % 7 == 0))
    val writeMs = (System.nanoTime() - write0) / 1_000_000
    val size = File(Journal.at(big), "journal/$server").listFiles()!!.sumOf { it.length() }
    var best = Long.MAX_VALUE
    var first = 0L
    var count = 0
    for (round in 0 until 5) {
        val r0 = System.nanoTime()
        count = bigJournal.read(server).size
        val ms = (System.nanoTime() - r0) / 1_000_000
        if (round == 0) first = ms
        best = minOf(best, ms)
    }
    println("    20.000 righe: scritte in $writeMs ms, ${size / 1024} KB in ${File(Journal.at(big), "journal/$server").list()!!.size} file; lette in $first ms la prima volta, $best ms al meglio")
    check("20.000 righe lette", count == 20_000, "$count")
    check("20.000 righe in meno di 300 ms (già caldo)", best < 300, "$best ms")
    check("20.000 righe in meno di 300 ms anche la prima volta", first < 300, "$first ms")

    // ---------- Nel registro niente titoli, nomi, uri ----------
    val secret = Listen(
        timeMs = at(2026, 11, 4, 9), uri = "spotify--Ab12://track/4uLU6hMC", libraryUri = "library://track/77", name = "Canzone segreta",
        mediaType = "track", artists = listOf(ArtistRef("library://artist/9", "Mario Rossi", null)), album = Ref("library://album/3", "Album segreto"),
        durationSec = 200, seconds = 50.0, finished = false, skipped = false, context = "library://playlist/vasco", place = Journal.PLACE_PHONE,
    )
    val captured = java.io.ByteArrayOutputStream()
    val console = System.out
    System.setOut(java.io.PrintStream(captured, true, "UTF-8"))
    try {
        val logged = Journal(Journal.at(Files.createTempDirectory("xonos-journal-log").toFile()), { true }, zone) { at(2026, 11, 4, 10) }
        logged.add("srv", secret)
        logged.read("srv")
        logged.counts()
        logged.prune(at(2028, 1, 1))
        logged.forget()
        // Un errore di scrittura (la cartella del server è un file) e un mese illeggibile (è una cartella).
        val broken = Journal.at(Files.createTempDirectory("xonos-journal-err").toFile())
        File(broken, "journal").mkdirs()
        File(broken, "journal/srv").writeText("x")
        Journal(broken, { true }, zone) { at(2026, 11, 4, 10) }.add("srv", secret)
        File(broken, "journal/srv").delete()
        File(broken, "journal/srv/2026-11.jsonl").mkdirs()
        Journal(broken, { true }, zone) { at(2026, 11, 4, 10) }.read("srv")
        val store = it.xonos.ma.data.LastListenStore(File(broken, "last-listen.json"))
        File(broken, "last-listen.json").writeText("{\"track\": {\"name\": \"Canzone segreta\"")
        store.read()
    } finally {
        System.out.flush()
        System.setOut(console)
    }
    val logText = captured.toString("UTF-8")
    println(logText.lines().filter { it.isNotBlank() }.joinToString("\n") { "    $it" })
    val leaks = listOf("Canzone segreta", "Mario Rossi", "Album segreto", "4uLU6hMC", "vasco", "library://", "spotify").filter { logText.contains(it) }
    check("Nel registro solo numeri e tipi d'errore", leaks.isEmpty() && logText.contains("Diario"), leaks.joinToString())

    // ---------- Cancella la cronologia ----------
    journal.forget()
    check("Cancella: niente ascolti", journal.read(server).isEmpty() && journal.counts().listens == 0 && journal.servers().isEmpty())
    check("Cancella: niente intervalli in auto", !File(dir, CarSessions.FILE).exists() && journal.cars.intervals().isEmpty())
    journal.add(server, listen(at(2026, 11, 4, 10), 9))
    check("Dopo Cancella si scrive di nuovo", journal.read(server).size == 1)

    root.deleteRecursively()
    big.deleteRecursively()
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
