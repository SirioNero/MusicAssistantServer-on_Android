package harness

import it.xonos.ma.data.ArtistRef
import it.xonos.ma.data.Connection
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.LastListenStore
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueSource
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Ref
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.ResumePlan
import it.xonos.ma.phone.model.ResumeStep
import it.xonos.ma.phone.model.run
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * "Riprendi" (ResumePlan e LastListen): i casi a, b, c, la coda finita, la copia di un altro server, la copia locale
 * scritta e riletta. Con MA_TOKEN anche dal vero: un lettore del banco suona un album e va in pausa; staccato e
 * riattaccato riprende lo stesso brano (a); una copia più avanti va al suo punto (b); coda persa, si rifà dalla
 * sorgente col brano e il punto (c), anche col brano da solo; a fine coda "Ascolta di nuovo".
 */
fun main(): Unit = runBlocking {
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    val server = "a3a33a8c90bc40a68fb77cd4e7e640b7"
    val phone = "xonos-1234"
    val now = 1_793_800_000_000L
    val album = QueueSource("library://album/124", "Emozioni", "album", MaImage("Lucio Battisti/Emozioni/cover.jpg", "filesystem_local--x", "3d1a", false))

    fun copy(item: String = "qi-4", pos: Double = 102.0, time: Long = now, source: QueueSource? = album, ended: Boolean = false, type: String = "track") = LastListen(
        serverId = server,
        queueId = phone,
        queueItemId = item,
        track = LastListen.Track("library://track/729", "Mi ritorni in mente", type, listOf(ArtistRef("library://artist/32", "Lucio Battisti")), Ref("library://album/124", "Emozioni"), album.image, 238),
        positionSec = pos,
        index = 3,
        count = 12,
        source = source,
        next = listOf(LastListen.Upcoming("library://track/730", "Emozioni", "Lucio Battisti", null)),
        shuffle = false,
        repeat = "off",
        ended = ended,
        timeMs = time,
    )

    fun queue(item: String? = "qi-4", pos: Double = 102.0, resume: Int = 102, updatedMs: Long = now, state: String = "idle", ended: Boolean = false, items: Int = 12, dynamic: Boolean = false): QueueState =
        QueueState.from(
            JSONObject()
                .put("queue_id", phone)
                .put("is_dynamic", dynamic)
                .put("state", state)
                .put("items", items)
                .put("current_index", 3)
                .put("elapsed_time", pos)
                .put("elapsed_time_last_updated", updatedMs / 1000.0)
                .put("resume_pos", resume)
                .put("ended", ended)
                .put("shuffle_enabled", false)
                .put("repeat_mode", "off")
                .put("sources", JSONArray().put(JSONObject().put("uri", album.uri).put("name", album.name).put("media_type", "album")))
                .put(
                    "current_item",
                    if (item == null) JSONObject.NULL else JSONObject()
                        .put("queue_item_id", item)
                        .put("name", "Lucio Battisti - Mi ritorni in mente")
                        .put("duration", 238)
                        .put("media_item", JSONObject().put("uri", "library://track/729").put("media_type", "track").put("name", "Mi ritorni in mente")),
                ),
        )!!

    // ---------- QueueState: i campi nuovi e quelli di prima ----------
    val q = queue(pos = 90.0, resume = 102)
    check("QueueState: resume_pos, sources, ended, elapsed_time_last_updated", q.resumePosSec == 102 && q.source == QueueSource(album.uri, album.name, "album", null) && !q.ended && q.elapsedUpdatedAt == now / 1000.0 && q.queueId == phone)
    check("Il punto di Music Assistant: resume_pos se c'è, sennò il tempo trascorso", q.resumeSec == 102.0 && queue(pos = 90.0, resume = 0).resumeSec == 90.0)
    val old = QueueState.from(JSONObject().put("queue_id", "x").put("state", "playing").put("items", 3).put("current_index", 1).put("elapsed_time", 12.5))
    check("QueueState di un Music Assistant vecchio (senza i campi nuovi)", old != null && old.sources.isEmpty() && old.resumePosSec == 0 && !old.ended && old.elapsedUpdatedAt == 0.0 && old.resumeSec == 12.5)
    val legacy = QueueState.from(JSONObject().put("queue_id", "x").put("items", 1).put("radio_source", JSONArray().put(JSONObject().put("uri", "spotify://track/x").put("name", "Radio").put("media_type", "track"))))
    check("Prima della 2.10: radio_source", legacy?.source?.uri == "spotify://track/x")
    check("Posizione adesso mentre suona", abs(queue(pos = 10.0, state = "playing", updatedMs = now).elapsedAt(now / 1000.0 + 5) - 15.0) < 0.01)
    check("QueueState: is_dynamic (Infinite Mix), falso se manca", queue(dynamic = true).dynamic && !queue().dynamic && old?.dynamic == false)
    check("Copia dalla coda: dinamica come la coda", LastListen.of(server, queue(dynamic = true), emptyList(), 1.0, now)?.dynamic == true && LastListen.of(server, queue(), emptyList(), 1.0, now)?.dynamic == false)

    // ---------- La decisione ----------
    check("Niente copia né coda: niente", ResumePlan.decide(null, null, phone, server) == null)
    check("(a) stessa voce, stesso punto: play", ResumePlan.decide(copy(pos = 105.0), queue(resume = 102), phone, server) == ResumeStep.Play(phone))
    check("(a) Music Assistant più avanti della copia: play (decide lui)", ResumePlan.decide(copy(pos = 60.0), queue(resume = 102), phone, server) == ResumeStep.Play(phone))
    check("(b) copia più recente e più avanti di oltre 10 s: play_index col punto", ResumePlan.decide(copy(pos = 140.0), queue(resume = 102, updatedMs = now - 30_000), phone, server) == ResumeStep.PlayIndex(phone, "qi-4", 140))
    check("(b) no se la copia è molto più vecchia della coda", ResumePlan.decide(copy(pos = 140.0, time = now - 600_000), queue(resume = 102), phone, server) == ResumeStep.Play(phone))
    check("(c) senza coda: dalla sorgente col brano e il punto", ResumePlan.decide(copy(pos = 102.0), null, phone, server) == ResumeStep.Rebuild(phone, album.uri, "library://track/729", 102))
    check("(c) coda vuota come senza coda", ResumePlan.decide(copy(pos = 102.0), queue(item = null, items = 0), phone, server) is ResumeStep.Rebuild)
    check("(c) sotto i 15 s si riparte dall'inizio del brano", (ResumePlan.decide(copy(pos = 12.0), null, phone, server) as ResumeStep.Rebuild).seekSec == 0)
    check("(c) un'altra coda e la copia più recente: si rifà quella della copia", ResumePlan.decide(copy(), queue(item = "qi-other", updatedMs = now - 5_000), phone, server) is ResumeStep.Rebuild)
    check("(c) un'altra coda più recente della copia: decide Music Assistant", ResumePlan.decide(copy(time = now - 3_600_000), queue(item = "qi-other"), phone, server) == ResumeStep.Play(phone))
    check("(c) senza sorgente: il brano da solo", ResumePlan.decide(copy(source = null, pos = 80.0), null, phone, server) == ResumeStep.Rebuild(phone, "library://track/729", null, 80))
    check("(c) una radio non ha un punto", (ResumePlan.decide(copy(source = null, pos = 800.0, type = "radio"), null, phone, server) as ResumeStep.Rebuild).seekSec == 0)
    // Solo album, playlist e podcast partono dal brano chiesto: un artista e un genere mescolano, una mix non risolve brani.
    fun from(type: String) = QueueSource("library://$type/7", "Sorgente", type, null)
    for (type in listOf("album", "playlist", "podcast")) {
        val step = ResumePlan.decide(copy(source = from(type)), null, phone, server)
        check("(c) da $type: dalla sorgente col brano", step == ResumeStep.Rebuild(phone, "library://$type/7", "library://track/729", 102), step.toString())
    }
    for (type in listOf("artist", "genre", "audiobook", "radio")) {
        val step = ResumePlan.decide(copy(source = from(type)), null, phone, server)
        check("(c) da $type: il brano da solo (start_item lì non vale)", step == ResumeStep.Rebuild(phone, "library://track/729", null, 102), step.toString())
    }
    val mixStep = ResumePlan.decide(copy(source = from("playlist")).copy(dynamic = true), null, phone, server)
    check("(c) da una coda dinamica (Infinite Mix): il brano da solo", mixStep == ResumeStep.Rebuild(phone, "library://track/729", null, 102), mixStep.toString())
    check(
        "(c) casuale e ripeti della copia",
        ResumePlan.decide(copy().copy(shuffle = true, repeat = "all"), null, phone, server) == ResumeStep.Rebuild(phone, album.uri, "library://track/729", 102, true, "all"),
    )
    check(
        "Coda finita da un artista: Ascolta di nuovo dall'artista",
        ResumePlan.decide(copy(source = from("artist"), ended = true), null, phone, server) == ResumeStep.ListenAgain(ResumeStep.Rebuild(phone, "library://artist/7", null, 0)),
    )
    check("Coda finita: Ascolta di nuovo (play rifà dal primo)", ResumePlan.decide(copy(), queue(ended = true), phone, server) == ResumeStep.ListenAgain(ResumeStep.Play(phone)))
    check("Coda finita senza copia: Ascolta di nuovo", ResumePlan.decide(null, queue(ended = true), phone, server) == ResumeStep.ListenAgain(ResumeStep.Play(phone)))
    check("Copia finita, coda che non lo sa: dal primo brano", ResumePlan.decide(copy(ended = true), queue(), phone, server) == ResumeStep.ListenAgain(ResumeStep.First(phone)))
    check("Copia finita senza coda: dalla sorgente dall'inizio", ResumePlan.decide(copy(ended = true), null, phone, server) == ResumeStep.ListenAgain(ResumeStep.Rebuild(phone, album.uri, null, 0)))
    check("La coda suona già: niente (play la farebbe ripartire)", ResumePlan.decide(copy(), queue(state = "playing"), phone, server) == null)
    check("Copia di un altro Music Assistant: ignorata", ResumePlan.decide(copy().copy(serverId = "altro"), null, phone, server) == null)
    check("Copia di un altro Music Assistant con la coda: decide la coda", ResumePlan.decide(copy(pos = 200.0).copy(serverId = "altro"), queue(), phone, server) == ResumeStep.Play(phone))
    check("Senza server collegato la copia non vale", ResumePlan.decide(copy(), null, phone, "") == null)
    check("Ricomincia: lo stesso brano da 0", ResumePlan.restart(copy(), queue(), phone, server) == ResumeStep.PlayIndex(phone, "qi-4", 0))
    check("Ricomincia senza coda: dalla sorgente col brano, da 0", ResumePlan.restart(copy(), null, phone, server) == ResumeStep.Rebuild(phone, album.uri, "library://track/729", 0))
    check("Ricomincia senza coda da un artista: il brano da solo, da 0", ResumePlan.restart(copy(source = from("artist")), null, phone, server) == ResumeStep.Rebuild(phone, "library://track/729", null, 0))
    check(
        "Ricomincia un episodio senza coda: dal podcast, dall'inizio anche per Music Assistant (start_from_beginning)",
        ResumePlan.restart(copy(source = from("podcast"), type = "podcast_episode"), null, phone, server) ==
            ResumeStep.Rebuild(phone, "library://podcast/7", "library://track/729", 0, fromStart = true),
    )

    // ---------- Cosa mostra l'eroe ----------
    val fromQueue = LastListen.of(server, queue(resume = 90), emptyList(), 90.0, now - 120_000)
    check("Eroe: la copia più fresca della coda", ResumePlan.shown(copy(), fromQueue, server) == copy())
    check("Eroe: la coda se è molto più recente", ResumePlan.shown(copy(time = now - 3_600_000), fromQueue, server) == fromQueue)
    check("Eroe: la coda se la copia è di un altro server", ResumePlan.shown(copy().copy(serverId = "x"), fromQueue, server) == fromQueue)
    val day = 86_400_000L
    check("Eroe visibile: 13 giorni sì, 15 no", ResumePlan.visible(copy(time = now - 13 * day), now, false, null) && !ResumePlan.visible(copy(time = now - 15 * day), now, false, null))
    check("Eroe visibile: podcast fino a 30 giorni", ResumePlan.visible(copy(time = now - 29 * day, type = "podcast_episode"), now, false, null) && !ResumePlan.visible(copy(time = now - 31 * day, type = "audiobook"), now, false, null))
    check("Eroe nascosto mentre suona e se tolto (fino al prossimo ascolto)", !ResumePlan.visible(copy(), now, true, null) && !ResumePlan.visible(copy(), now, false, copy().key) && ResumePlan.visible(copy(time = now + 1), now + 1, false, copy().key))

    // ---------- La copia locale ----------
    val dir = Files.createTempDirectory("xonos-resume").toFile()
    val store = LastListenStore(File(dir, "last-listen.json"))
    check("Senza file: niente", store.read() == null)
    store.write(copy())
    check("Scritta e riletta uguale", store.read() == copy(), store.read().toString())
    store.write(copy().copy(dynamic = true))
    check("Scritta e riletta uguale, anche dinamica", store.read() == copy().copy(dynamic = true))
    check("Niente file temporanei lasciati", dir.list()!!.toList() == listOf("last-listen.json"))
    check("Meno di 4 KB", File(dir, "last-listen.json").length() < 4096, "${File(dir, "last-listen.json").length()} byte")
    File(dir, "last-listen.json").writeText("{\"server_id\": \"x\", \"track\": {\"uri\"")
    check("File rovinato: niente, senza errori", store.read() == null)
    File(dir, "last-listen.json").writeText("{\"server_id\":\"$server\",\"queue_item_id\":\"q\",\"track\":{\"uri\":\"library://track/1\"},\"futuro\":[1,2]}")
    val sparse = store.read()
    check("Campi mancanti: valori vuoti", sparse != null && sparse.track.name == "" && sparse.positionSec == 0.0 && sparse.next.isEmpty() && sparse.source == null && sparse.repeat == "off" && !sparse.dynamic)
    store.clear()
    check("Tolta", store.read() == null)
    dir.deleteRecursively()

    // ---------- Dal vero, col Music Assistant di prova ----------
    val token = System.getenv("MA_TOKEN")
    if (token.isNullOrBlank()) {
        println("(senza MA_TOKEN: niente prove contro Music Assistant)")
    } else {
        // Non "failures += live(...)": il valore di prima si leggerebbe prima, e i fallimenti contati dentro si perderebbero.
        live(token, ::check)
    }

    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Il caso vero: un lettore Sendspin del banco, un album della libreria di prova. Ritorna 0 (i fallimenti li conta check). */
private suspend fun live(token: String, check: (String, Boolean, String) -> Unit): Int {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val playerId = "xonos-resumecheck"
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    val connected = withTimeoutOrNull(20_000) { repo.connection.first { it is Connection.Connected } } as? Connection.Connected
    check("Collegato a Music Assistant", connected != null, "")
    if (connected == null) return 0
    val serverId = connected.server.id
    val engine = AudioEngine({ r, c -> FakeOutput(r, c) }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val identity = SendspinClient.Identity(playerId, "Prova Riprendi", "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), 800, 300, 50, false)
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }

    /** Il lettore c'è ed è disponibile (staccato, Music Assistant lo toglie del tutto: players/get non lo trova). */
    suspend fun available(): Boolean = try {
        (repo.command("players/get") { put("player_id", playerId) } as? JSONObject)?.optBoolean("available") ?: false
    } catch (e: Exception) {
        false
    }

    suspend fun player(available: Boolean): Boolean = withTimeoutOrNull(30_000) {
        while (available() != available) delay(300)
        true
    } ?: false

    suspend fun state(): QueueState? = library.queue(playerId)

    /** Aspetta che la coda soddisfi [test] (al massimo [ms]). */
    suspend fun until(ms: Long = 20_000, test: (QueueState) -> Boolean): QueueState? = withTimeoutOrNull(ms) {
        var s = state()
        while (s == null || !test(s)) {
            delay(250)
            s = state()
        }
        s
    }

    fun nowSec() = System.currentTimeMillis() / 1000.0

    suspend fun pause(): QueueState? {
        repo.command("player_queues/pause") { put("queue_id", playerId) }
        return until { !it.playing }
    }

    client.start(url, token)
    withTimeout(45_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    check("Lettore del banco disponibile", player(true), playerId)

    // Un album della libreria con almeno 4 brani da un minuto (gli album di prova con copertine e testi).
    val albums = library.libraryItems("albums")
    var chosen: Pair<MaItem, List<MaItem>>? = null
    for (a in albums) {
        val tracks = library.albumTracks(a)
        if (tracks.size >= 4 && tracks.all { it.durationSec >= 55 }) {
            chosen = a to tracks
            break
        }
    }
    check("Un album di prova", chosen != null, chosen?.first?.name.orEmpty())
    val (albumItem, tracks) = chosen ?: return 0
    try {
        // Suona dal secondo brano, poi va a 20 s e si ferma.
        library.setShuffle(playerId, false)
        library.setRepeat(playerId, "off")
        library.playMedia(playerId, listOf(albumItem.uri), startItem = tracks[1].uri)
        until { it.playing && it.current != null }
        library.seek(playerId, 20)
        delay(2_500)
        val paused = pause()
        println("    in pausa: ${paused?.current?.title} a ${paused?.resumeSec} s (resume_pos ${paused?.resumePosSec}, elapsed ${paused?.elapsedSec}, segnato ${paused?.elapsedUpdatedAt?.let { "%.1f s fa".format(nowSec() - it) }}), sorgente ${paused?.source?.mediaType} ${paused?.source?.name}, finita ${paused?.ended}")
        check("La coda dice la sua sorgente (l'album)", paused?.source?.uri == albumItem.uri, paused?.source?.uri.orEmpty())
        check("In pausa con resume_pos", paused != null && paused.resumePosSec in 20..26 && abs(paused.resumeSec - 22.5) < 3.5, "${paused?.resumePosSec}")
        paused ?: return 0
        val next = library.queueItems(playerId, (paused.currentIndex ?: 0) + 1, LastListen.NEXT)
        val expectedNext = minOf(LastListen.NEXT, paused.items - (paused.currentIndex ?: 0) - 1)
        check("I brani dopo (player_queues/items, al massimo 3)", next.size == expectedNext && next.all { it.title.isNotEmpty() }, next.joinToString { it.title })
        val snapshot = LastListen.of(serverId, paused, next, paused.resumeSec, System.currentTimeMillis())!!
        val item = paused.current!!
        check("Voce della coda: artisti, uri nel servizio e della libreria", item.artists.isNotEmpty() && item.serviceUri?.startsWith("filesystem_local") == true && item.libraryUri == item.uri, "${item.artists.size} artisti")
        check("Copia dalla coda: brano, sorgente, posto", snapshot.track.uri.isNotEmpty() && snapshot.source?.uri == albumItem.uri && snapshot.index == paused.currentIndex && snapshot.count == paused.items && snapshot.next.size == expectedNext, "")

        // (a) In pausa, la coda è ancora lì: play, stessa voce, stesso punto (±2 s).
        val stepA = ResumePlan.decide(snapshot, state(), playerId, serverId)
        check("(a) decisione: play", stepA == ResumeStep.Play(playerId), stepA.toString())
        stepA?.run(library)
        val a = until { it.playing }
        val posA = a?.elapsedAt(nowSec()) ?: -1.0
        check("(a) stessa voce, stesso punto (±2 s)", a?.current?.id == snapshot.queueItemId && abs(posA - snapshot.positionSec) <= 2.0, "%.1f s invece di %.1f".format(posA, snapshot.positionSec))

        // Staccato e riattaccato (Android che chiude Xonos, la rete che cade): stesso brano, stesso punto (±2 s).
        val pausedA = pause() ?: return 0
        val detached = LastListen.of(serverId, pausedA, next, pausedA.resumeSec, System.currentTimeMillis())!!
        client.stop()
        val t0 = System.nanoTime()
        check("Lettore staccato", player(false), "dopo ${(System.nanoTime() - t0) / 1_000_000} ms")
        val gone = state()
        println("    staccato: voci ${gone?.items}, voce ${gone?.current?.id?.take(8)} (copia ${snapshot.queueItemId.take(8)}), resume_pos ${gone?.resumePosSec}, elapsed ${gone?.elapsedSec}, stato ${gone?.state}")
        client.start(url, token)
        withTimeout(45_000) { client.phase.first { it == SendspinClient.Phase.READY } }
        check("Lettore riattaccato", player(true), "")
        val back = state()
        println("    riattaccato: voci ${back?.items}, voce ${back?.current?.id?.take(8)} (copia ${detached.queueItemId.take(8)}), resume_pos ${back?.resumePosSec}, elapsed ${back?.elapsedSec}, stato ${back?.state}, segnato ${back?.elapsedUpdatedAt?.let { "%.1f s fa".format(nowSec() - it) }}")
        val stepR = ResumePlan.decide(detached, back, playerId, serverId)
        println("    decisione dopo il distacco: $stepR")
        check("Riattaccato: una decisione", stepR != null, stepR.toString())
        stepR?.run(library)
        val r = until { it.playing && it.current != null }
        val posR = r?.elapsedAt(nowSec()) ?: -1.0
        check("Riattaccato: stesso brano, stesso punto (±2 s)", r?.current?.uri == detached.track.uri && abs(posR - detached.positionSec) <= 2.0, "${r?.current?.title}, %.1f s invece di %.1f".format(posR, detached.positionSec))

        // (b) La copia è più recente e più avanti di 15 s: si va al suo punto.
        val pausedB = pause() ?: return 0
        val ahead = LastListen.of(serverId, pausedB, next, pausedB.resumeSec + 15, System.currentTimeMillis())!!
        val stepB = ResumePlan.decide(ahead, pausedB, playerId, serverId)
        check("(b) decisione: play_index col punto della copia", stepB is ResumeStep.PlayIndex && stepB.seekSec == ahead.positionSec.toInt(), stepB.toString())
        stepB?.run(library)
        val b = until { it.playing }
        val posB = b?.elapsedAt(nowSec()) ?: -1.0
        check("(b) stesso brano, al punto della copia (±2 s)", b?.current?.id == ahead.queueItemId && abs(posB - ahead.positionSec) <= 2.0, "%.1f s invece di %.1f".format(posB, ahead.positionSec))

        // (c) Coda persa: si rifà dalla sorgente, dal brano, al punto.
        val pausedC = pause() ?: return 0
        val lost = LastListen.of(serverId, pausedC, next, pausedC.resumeSec, System.currentTimeMillis())!!
        repo.command("player_queues/clear") { put("queue_id", playerId) }
        val empty = until { it.items == 0 }
        check("Coda svuotata", empty != null, "")
        val stepC = ResumePlan.decide(lost, empty, playerId, serverId)
        check("(c) decisione: dalla sorgente col brano e il punto", stepC == ResumeStep.Rebuild(playerId, albumItem.uri, lost.track.uri, lost.positionSec.toInt()), stepC.toString())
        stepC?.run(library)
        val c = until { it.playing && it.current != null }
        val posC = c?.elapsedAt(nowSec()) ?: -1.0
        check("(c) stesso brano dalla sorgente", c?.current?.uri == lost.track.uri && c.source?.uri == albumItem.uri, "${c?.current?.title} da ${c?.source?.name}")
        check("(c) al punto della copia (±3 s, dopo un secondo avvio)", abs(posC - lost.positionSec) <= 3.0, "%.1f s invece di %.1f".format(posC, lost.positionSec))

        // (c) Senza sorgente: il brano da solo, al punto.
        val pausedD = pause() ?: return 0
        val lone = LastListen.of(serverId, pausedD, emptyList(), 30.0, System.currentTimeMillis())!!.copy(source = null)
        repo.command("player_queues/clear") { put("queue_id", playerId) }
        until { it.items == 0 }
        val stepD = ResumePlan.decide(lone, state(), playerId, serverId)
        check("(c) senza sorgente: il brano da solo", stepD == ResumeStep.Rebuild(playerId, lone.track.uri, null, 30), stepD.toString())
        stepD?.run(library)
        val d = until { it.playing && it.current != null }
        val posD = d?.elapsedAt(nowSec()) ?: -1.0
        check("(c) un brano solo in coda, al punto (±3 s)", d?.items == 1 && d.current?.uri == lone.track.uri && abs(posD - 30.0) <= 3.0, "voci ${d?.items}, %.1f s".format(posD))

        // A fine coda: "Ascolta di nuovo" riparte dal primo brano.
        library.playIndex(playerId, 0, (d?.current?.durationSec ?: 60) - 3)
        val ended = until(30_000) { it.ended }
        check("La coda arriva in fondo (ended)", ended != null, "")
        if (ended != null) {
            val stepE = ResumePlan.decide(LastListen.of(serverId, ended, emptyList(), ended.resumeSec, System.currentTimeMillis()), ended, playerId, serverId)
            check("Coda finita: Ascolta di nuovo", stepE == ResumeStep.ListenAgain(ResumeStep.Play(playerId)), stepE.toString())
            stepE?.run(library)
            val e = until { it.playing }
            check("Ascolta di nuovo: dal primo brano, dall'inizio", e?.currentIndex == 0 && (e.elapsedAt(nowSec())) < 4.0, "brano ${e?.currentIndex}")
        }

        // Le sorgenti con cui start_item non vale, e i casi che Music Assistant rifiuta (revisione della 0.1.58).
        try {
            sources(library, repo, serverId, playerId, albumItem, tracks, ::state, ::until, ::pause, ::nowSec, check)
        } catch (e: Exception) {
            check("Sorgenti: senza errori", false, "${e.javaClass.simpleName}: ${e.message}")
        }
    } finally {
        runCatching { repo.command("player_queues/stop") { put("queue_id", playerId) } }
        client.stop()
        repo.release()
    }
    return 0
}

/**
 * (c) con le sorgenti che start_item ignora o che non hanno il brano: si riprende sempre lo stesso brano. Un artista (Music
 * Assistant mescola), un'Infinite Mix (coda dinamica: casuale e ripeti rifiutati), un brano arrivato con "Suona dopo"
 * (non è nell'album della sorgente), l'album col casuale (tutto l'album, col brano per primo), e "dall'inizio" per un
 * episodio con un punto ricordato.
 */
private suspend fun sources(
    library: Library,
    repo: MaRepository,
    serverId: String,
    playerId: String,
    albumItem: MaItem,
    tracks: List<MaItem>,
    state: suspend () -> QueueState?,
    until: suspend (Long, (QueueState) -> Boolean) -> QueueState?,
    pause: suspend () -> QueueState?,
    nowSec: () -> Double,
    check: (String, Boolean, String) -> Unit,
) {
    suspend fun clear() {
        repo.command("player_queues/clear") { put("queue_id", playerId) }
        until(20_000) { it.items == 0 }
    }

    /** La copia della coda in pausa, la coda persa, la decisione e i comandi: suona lo stesso brano, allo stesso punto? */
    suspend fun lostAndBack(label: String, copy: LastListen, expected: ResumeStep?): QueueState? {
        clear()
        val step = ResumePlan.decide(copy, state(), playerId, serverId)
        check("$label: decisione", expected == null || step == expected, step.toString())
        try {
            step?.run(library)
        } catch (e: Exception) {
            check("$label: comandi senza errori", false, "${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val back = until(20_000) { it.playing && it.current != null }
        val pos = back?.elapsedAt(nowSec()) ?: -1.0
        val wanted = if (copy.positionSec > ResumePlan.SEEK_FROM_S) copy.positionSec else 0.0
        check(
            "$label: stesso brano, stesso punto (±3 s)",
            back?.current?.uri == copy.track.uri && abs(pos - wanted) <= 3.0,
            "${back?.current?.uri} (copia ${copy.track.uri}) a %.1f s invece di %.1f".format(pos, wanted),
        )
        return back
    }

    suspend fun pausedCopy(): LastListen? {
        val paused = pause() ?: return null
        val next = library.queueItems(playerId, (paused.currentIndex ?: 0) + 1, LastListen.NEXT)
        return LastListen.of(serverId, paused, next, paused.resumeSec, System.currentTimeMillis())
    }

    library.setShuffle(playerId, false)
    library.setRepeat(playerId, "off")

    // Un artista: Music Assistant mescola i suoi brani e ignora start_item. Prima ripartiva un altro brano.
    val artist = library.libraryItems("artists").firstOrNull { library.artistTracks(it).size >= 3 }
    check("Un artista con almeno 3 brani", artist != null, artist?.name.orEmpty())
    if (artist != null) {
        library.playMedia(playerId, listOf(artist.uri))
        val playing = until(20_000) { it.playing && it.current != null }
        if ((playing?.current?.durationSec ?: 0) > 40) library.seek(playerId, 20)
        println("    artista: ${playing?.current?.title} di ${playing?.current?.durationSec} s")
        delay(2_000)
        val copy = pausedCopy()
        check("Artista: la sorgente è l'artista", copy?.source?.mediaType == "artist", "${copy?.source?.mediaType}")
        if (copy != null) lostAndBack("(c) da un artista", copy, ResumeStep.Rebuild(playerId, copy.track.uri, null, if (copy.positionSec > ResumePlan.SEEK_FROM_S) copy.positionSec.toInt() else 0))
    }

    // Un'Infinite Mix: la coda diventa dinamica (casuale acceso e bloccato).
    // Quella della libreria: quella dei preferiti è vuota senza preferiti ("There is nothing to play here").
    val mix = library.libraryItems("playlists").filter { it.name.contains("Infinite Mix") }.minByOrNull { if (it.name.contains("library")) 0 else 1 }
    check("Un'Infinite Mix di Music Assistant", mix != null, mix?.uri.orEmpty())
    if (mix != null) {
        library.playMedia(playerId, listOf(mix.uri))
        val mixing = until(20_000) { it.playing && it.current != null }
        check("Infinite Mix: la coda dice is_dynamic", mixing?.dynamic == true && mixing.shuffle, "dinamica ${mixing?.dynamic}, casuale ${mixing?.shuffle}")
        delay(2_000)
        val mixCopy = pausedCopy()
        check("Infinite Mix: la copia è dinamica", mixCopy?.dynamic == true, "")
        // Una copia di un album (senza casuale, con ripeti) da rifare mentre Music Assistant ha la mix in coda: prima
        // player_queues/shuffle false era rifiutato e non suonava niente.
        val dynamicNow = state()
        val albumCopy = LastListen(
            serverId, playerId, "copia-album",
            LastListen.Track(tracks[1].uri, tracks[1].name, "track", emptyList(), null, null, tracks[1].durationSec),
            25.0, 1, tracks.size, it.xonos.ma.data.QueueSource(albumItem.uri, albumItem.name, "album", null), emptyList(), false, "all", false,
            System.currentTimeMillis() + 120_000,
        )
        val step = ResumePlan.decide(albumCopy, dynamicNow, playerId, serverId)
        check("Coda dinamica e copia di un album: si rifà l'album", step == ResumeStep.Rebuild(playerId, albumItem.uri, tracks[1].uri, 25, false, "all"), step.toString())
        try {
            step?.run(library)
            val back = until(20_000) { it.playing && it.current != null }
            val pos = back?.elapsedAt(nowSec()) ?: -1.0
            check(
                "Dalla coda dinamica: l'album, dal brano e dal punto, ripeti come la copia",
                back?.current?.uri == tracks[1].uri && !back.dynamic && !back.shuffle && back.repeat == "all" && abs(pos - 25.0) <= 3.0,
                "${back?.current?.uri}, dinamica ${back?.dynamic}, casuale ${back?.shuffle}, ripeti ${back?.repeat}, %.1f s".format(pos),
            )
        } catch (e: Exception) {
            check("Dalla coda dinamica: comandi senza errori", false, "${e.javaClass.simpleName}: ${e.message}")
        }
        library.setRepeat(playerId, "off")
        // La copia della mix stessa, con la coda persa: il brano da solo (prima ripartiva la mix da un altro brano).
        if (mixCopy != null) lostAndBack("(c) da un'Infinite Mix", mixCopy, ResumeStep.Rebuild(playerId, mixCopy.track.uri, null, 0, mixCopy.shuffle, mixCopy.repeat))
    }

    // "Suona dopo": il brano entra in coda ma non nella sorgente (l'album). Music Assistant rifiuta l'album con start_item.
    pause()
    library.setShuffle(playerId, false)
    val other = library.libraryItems("albums").firstOrNull { it.uri != albumItem.uri }?.let { library.albumTracks(it).firstOrNull() }
    check("Un brano di un altro album", other != null, other?.name.orEmpty())
    if (other != null) {
        library.playMedia(playerId, listOf(albumItem.uri), startItem = tracks[0].uri)
        until(20_000) { it.playing && it.current?.uri == tracks[0].uri }
        library.playMedia(playerId, listOf(other.uri), option = "next")
        delay(1_000)
        repo.command("player_queues/next") { put("queue_id", playerId) }
        until(20_000) { it.playing && it.current?.uri == other.uri }
        delay(2_000)
        val copy = pausedCopy()
        check("Suona dopo: brano di un altro album, sorgente l'album", copy?.track?.uri == other.uri && copy.source?.uri == albumItem.uri, "${copy?.track?.uri} da ${copy?.source?.uri}")
        if (copy != null) {
            val back = lostAndBack("(c) brano arrivato con Suona dopo", copy, ResumeStep.Rebuild(playerId, albumItem.uri, other.uri, 0))
            check("Suona dopo: il brano da solo", back?.items == 1, "voci ${back?.items}")
        }
    }

    // Col casuale: tutto l'album, col brano per primo (prima restavano solo i brani dopo quello ripreso).
    pause()
    library.playMedia(playerId, listOf(albumItem.uri), startItem = tracks[2].uri, shuffle = true)
    until(20_000) { it.playing && it.current?.uri == tracks[2].uri }
    library.seek(playerId, 20)
    delay(2_000)
    val shuffled = pausedCopy()
    check("Casuale: la copia lo sa", shuffled?.shuffle == true, "")
    if (shuffled != null) {
        val back = lostAndBack("(c) col casuale", shuffled, ResumeStep.Rebuild(playerId, albumItem.uri, tracks[2].uri, shuffled.positionSec.toInt(), true, "off"))
        check("Col casuale: tutto l'album, casuale acceso", back?.items == tracks.size && back.shuffle && back.currentIndex == 0, "voci ${back?.items} su ${tracks.size}, casuale ${back?.shuffle}, posto ${back?.currentIndex}")
    }
    pause()
    library.setShuffle(playerId, false)

    // "Dall'inizio" di un episodio con un punto ricordato: start_from_beginning, e backToStart per chi non lo conosce.
    val allEpisodes = library.libraryItems("podcasts").flatMap { library.podcastEpisodes(it) }
    val episode = allEpisodes.filter { it.durationSec >= 30 }.maxByOrNull { it.durationSec }
    check("Un episodio di almeno 30 s", episode != null, "${episode?.name} di ${episode?.durationSec} s")
    if (episode != null) {
        val point = minOf(200, episode.durationSec / 2)
        suspend fun remember(seconds: Int) = repo.command("music/mark_played") {
            put("media_item", JSONObject().put("media_type", episode.mediaType).put("item_id", episode.itemId).put("provider", episode.provider).put("name", episode.name).put("uri", episode.uri))
            put("fully_played", false)
            put("seconds_played", seconds)
        }
        remember(point)
        library.playMedia(playerId, listOf(episode.uri))
        val resumed = until(20_000) { it.playing && it.current?.uri == episode.uri }
        check("Episodio: di suo Music Assistant riprende dal punto", abs((resumed?.elapsedAt(nowSec()) ?: 0.0) - point) < 5, "%.1f s invece di $point".format(resumed?.elapsedAt(nowSec())))
        library.backToStart(playerId)
        val back = until(20_000) { it.playing && it.elapsedAt(nowSec()) < point - 5 }
        check("Episodio: backToStart lo riporta all'inizio", back != null && back.elapsedAt(nowSec()) < 5, "%.1f s".format(back?.elapsedAt(nowSec())))
        pause()
        remember(point)
        library.playMedia(playerId, listOf(episode.uri), fromStart = true)
        val fresh = until(20_000) { it.playing && it.current?.uri == episode.uri }
        check("Episodio: play_media dall'inizio (start_from_beginning)", fresh != null && fresh.elapsedAt(nowSec()) < 5, "%.1f s".format(fresh?.elapsedAt(nowSec())))
        pause()
        runCatching {
            repo.command("music/mark_unplayed") {
                put("media_item", JSONObject().put("media_type", episode.mediaType).put("item_id", episode.itemId).put("provider", episode.provider).put("name", episode.name).put("uri", episode.uri))
            }
        }
    }
}
