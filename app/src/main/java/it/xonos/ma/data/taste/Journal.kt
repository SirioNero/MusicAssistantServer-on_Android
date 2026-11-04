package it.xonos.ma.data.taste

import android.util.Log
import it.xonos.ma.data.ArtistRef
import it.xonos.ma.data.Logged
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Ref
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** Un ascolto nel diario: un brano (o un episodio, una radio) suonato sul telefono. */
data class Listen(
    /** Quando è cominciato, in ms dal 1970. */
    val timeMs: Long,
    /** L'uri nel servizio ("spotify--Ab12://track/x"); se non si sa, quello di Music Assistant. */
    val uri: String,
    /** L'uri della libreria ("library://track/12"), se il brano è in libreria. */
    val libraryUri: String?,
    val name: String,
    /** "track", "podcast_episode", "audiobook", "radio"... */
    val mediaType: String,
    val artists: List<ArtistRef>,
    val album: Ref?,
    /** 0 se non si sa (le radio). */
    val durationSec: Int,
    /** Quanti secondi si sono ascoltati davvero (senza i salti dentro il brano). */
    val seconds: Double,
    /** Arrivato (quasi) in fondo. */
    val finished: Boolean,
    /** Saltato: meno di 30 s, non finito, e un altro brano sulla stessa coda entro 10 s. */
    val skipped: Boolean,
    /** Da dove suonava (l'uri della sorgente della coda), se si sa. */
    val context: String?,
    /** [Journal.PLACE_CAR] o [Journal.PLACE_PHONE]. */
    val place: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", timeMs)
        .put("uri", uri)
        .putOpt("library_uri", libraryUri)
        .put("name", name)
        .put("type", mediaType)
        .put("artists", JSONArray(artists.map { a -> JSONArray().put(a.uri).put(a.name).put(a.mbid ?: JSONObject.NULL) }))
        .putOpt("album", album?.let { JSONArray().put(it.uri).put(it.name) })
        .put("duration", durationSec)
        .put("seconds", Math.round(seconds * 10) / 10.0)
        .put("finished", finished)
        .put("skipped", skipped)
        .putOpt("context", context)
        .put("place", place)

    companion object {
        /**
         * Una riga del diario; null se è rovinata (una scrittura interrotta) o di un formato che non si capisce. Legge
         * da sé il JSON delle righe che scrive [toJson], senza org.json: un anno di ascolti (20.000 righe, circa 6 MB)
         * deve leggersi in meno di 300 ms anche su un telefono.
         */
        fun parse(line: String): Listen? = try {
            LineReader(line).listen()
        } catch (e: IllegalStateException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/** Il lettore di una riga del diario: un oggetto JSON, i suoi campi e gli elenchi degli artisti. */
private class LineReader(private val s: String) {
    private var i = 0

    fun listen(): Listen? {
        var time = 0L
        var uri: String? = null
        var libraryUri: String? = null
        var name = ""
        var type = "track"
        var artists: List<ArtistRef> = emptyList()
        var album: Ref? = null
        var duration = 0
        var seconds = 0.0
        var finished = false
        var skipped = false
        var context: String? = null
        var place = Journal.PLACE_PHONE
        expect('{')
        if (peek() == '}') return null
        while (true) {
            val key = string()
            expect(':')
            when (key) {
                "time" -> time = number().toLong()
                "uri" -> uri = stringOrNull()
                "library_uri" -> libraryUri = stringOrNull()
                "name" -> name = stringOrNull().orEmpty()
                "type" -> type = stringOrNull() ?: "track"
                "artists" -> artists = artists()
                "album" -> album = pair()?.let { (u, n) -> Ref(u, n) }
                "duration" -> duration = number().toInt()
                "seconds" -> seconds = number()
                "finished" -> finished = bool()
                "skipped" -> skipped = bool()
                "context" -> context = stringOrNull()
                "place" -> place = stringOrNull() ?: Journal.PLACE_PHONE
                else -> skip()
            }
            if (peek() == ',') {
                i++
                continue
            }
            expect('}')
            break
        }
        val found = uri ?: return null
        if (time <= 0) return null
        return Listen(time, found, libraryUri, name, type, artists, album, duration, seconds, finished, skipped, context, place)
    }

    /** [[uri, nome, mbid], ...]: gli artisti senza uri si saltano. */
    private fun artists(): List<ArtistRef> {
        if (peek() == 'n') return literal().let { emptyList() }
        expect('[')
        val out = ArrayList<ArtistRef>(2)
        if (peek() == ']') return out.also { i++ }
        while (true) {
            expect('[')
            val values = ArrayList<String?>(3)
            if (peek() != ']') {
                while (true) {
                    values += if (peek() == '"' || peek() == 'n') stringOrNull() else null.also { skip() }
                    if (peek() == ',') i++ else break
                }
            }
            expect(']')
            val artistUri = values.getOrNull(0)
            if (!artistUri.isNullOrEmpty()) out += ArtistRef(artistUri, values.getOrNull(1).orEmpty(), values.getOrNull(2)?.ifEmpty { null })
            if (peek() == ',') i++ else break
        }
        expect(']')
        return out
    }

    /** [uri, nome] (l'album); null se manca. */
    private fun pair(): Pair<String, String>? {
        if (peek() == 'n') return literal().let { null }
        expect('[')
        val first = stringOrNull()
        var second: String? = null
        if (peek() == ',') {
            i++
            second = stringOrNull()
        }
        while (peek() == ',') {
            i++
            skip()
        }
        expect(']')
        return first?.takeIf { it.isNotEmpty() }?.let { it to second.orEmpty() }
    }

    private fun peek(): Char {
        while (i < s.length && s[i] <= ' ') i++
        return if (i < s.length) s[i] else '\u0000'
    }

    private fun expect(c: Char) {
        if (peek() != c) throw IllegalStateException("atteso $c")
        i++
    }

    private fun stringOrNull(): String? = if (peek() == 'n') literal().let { null } else string()

    private fun string(): String {
        expect('"')
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return s.substring(start, i).also { i++ }
            if (c == '\\') break
            i++
        }
        // Con caratteri preceduti dalla barra rovesciata (virgolette, a capo, lettere accentate): si ricostruisce.
        val out = StringBuilder(s.length - start).append(s, start, i)
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= s.length) break
                    when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) throw IllegalStateException("\\u a metà")
                            out.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
        }
        throw IllegalStateException("testo a metà")
    }

    private fun number(): Double {
        peek()
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        if (start == i) throw IllegalStateException("numero")
        return s.substring(start, i).toDouble()
    }

    private fun bool(): Boolean = when (val v = literal()) {
        "true" -> true
        "false", "null" -> false
        else -> throw IllegalStateException(v)
    }

    private fun literal(): String {
        peek()
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        return s.substring(start, i).also { if (it.isEmpty()) throw IllegalStateException("valore") }
    }

    /** Un valore che non serve (un campo aggiunto da una versione nuova): si salta tutto, anche annidato. */
    private fun skip() {
        when (peek()) {
            '"' -> string()
            '{', '[' -> {
                var depth = 0
                while (i < s.length) {
                    when (s[i]) {
                        '"' -> {
                            string()
                            continue
                        }
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) {
                                i++
                                return
                            }
                        }
                    }
                    i++
                }
                throw IllegalStateException("elenco a metà")
            }
            't', 'f', 'n' -> literal()
            else -> number()
        }
    }
}

/** I numeri del diario per la pagina Impostazioni › Per te. */
data class JournalCounts(
    val listens: Int,
    /** Brani e artisti diversi. */
    val tracks: Int,
    val artists: Int,
    /** Il primo ascolto ancora tenuto, in ms dal 1970; null col diario vuoto. */
    val firstMs: Long?,
    /** Quanto occupa sul telefono. */
    val bytes: Long,
)

/**
 * Il diario degli ascolti, sul telefono: un file per mese, `per-te/journal/<server_id>/AAAA-MM.jsonl` dentro
 * noBackupFilesDir (fuori dal backup di Android), una riga JSON per ascolto, circa 300 byte. Si tiene un anno: i mesi
 * finiti da più di [KEEP_DAYS] giorni si cancellano, e le righe più vecchie non si leggono.
 *
 * Serve al motore dei consigli (tappa 4 del piano), che lo legge con [read]. Si scrive solo se [enabled] (Impostazioni ›
 * Per te, acceso di base); [forget] cancella tutto. Nel registro solo numeri e tipi d'errore, mai titoli né uri.
 * Fuori dal thread principale: legge e scrive file.
 */
class Journal(
    /** La cartella `per-te` (vedi [at]). */
    private val dir: File,
    private val enabled: () -> Boolean = { true },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val journalDir = File(dir, "journal")

    /** Gli intervalli in auto, che ServerKeeper scrive: dicono il luogo degli ascolti. */
    val cars = CarSessions(File(dir, CarSessions.FILE), clock)

    @Volatile private var prunedAt = 0L

    /** Il luogo di un ascolto cominciato a [timeMs]. */
    fun placeAt(timeMs: Long): String = if (cars.isCar(timeMs)) PLACE_CAR else PLACE_PHONE

    /** Aggiunge un ascolto al diario di [serverId]. Niente se il diario è spento. */
    @Synchronized
    fun add(serverId: String, listen: Listen) {
        if (!enabled() || serverId.isEmpty()) return
        val file = monthFile(serverId, YearMonth.from(Instant.ofEpochMilli(listen.timeMs).atZone(zone)))
        try {
            file.parentFile?.mkdirs()
            // Una riga sola in una scrittura: se si interrompe, la lettura salta la riga a metà. Dopo una riga a metà si
            // va a capo, sennò la nuova le resterebbe attaccata e si perderebbe anche lei.
            val line = listen.toJson().toString() + "\n"
            file.appendBytes((if (endsMidLine(file)) "\n$line" else line).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Diario: ascolto non scritto (${Logged.error(e)})")
            return
        }
        val now = clock()
        if (now - prunedAt > DAY_MS) prune(now)
    }

    /**
     * Gli ascolti di [serverId] dal momento [sinceMs] (di base: l'ultimo anno), dal più vecchio. Le righe rovinate si
     * saltano.
     */
    @Synchronized
    fun read(serverId: String, sinceMs: Long = clock() - KEEP_DAYS * DAY_MS): List<Listen> {
        val from = max(sinceMs, clock() - KEEP_DAYS * DAY_MS)
        val firstMonth = YearMonth.from(Instant.ofEpochMilli(from).atZone(zone))
        val out = ArrayList<Listen>()
        for ((month, file) in months(serverId)) {
            if (month < firstMonth) continue
            try {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val listen = Listen.parse(line) ?: continue
                        if (listen.timeMs >= from) out += listen
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Diario: un mese illeggibile (${Logged.error(e)})")
            }
        }
        // I file sono già in ordine; dentro un mese di solito anche le righe, ma un orologio cambiato no.
        out.sortBy { it.timeMs }
        return out
    }

    /** I numeri del diario: di [serverId], o di tutti i Music Assistant con null. */
    @Synchronized
    fun counts(serverId: String? = null): JournalCounts {
        val servers = if (serverId != null) listOf(serverId) else servers()
        var listens = 0
        val tracks = HashSet<String>()
        val artists = HashSet<String>()
        var first: Long? = null
        var bytes = 0L
        for (server in servers) {
            months(server).forEach { (_, file) -> bytes += file.length() }
            for (listen in read(server)) {
                listens++
                tracks += listen.libraryUri ?: listen.uri
                listen.artists.forEach { artists += it.uri }
                if (first == null || listen.timeMs < first!!) first = listen.timeMs
            }
        }
        return JournalCounts(listens, tracks.size, artists.size, first, bytes)
    }

    /** Toglie i mesi finiti da più di un anno. Lo fa da sé una volta al giorno, scrivendo. */
    @Synchronized
    fun prune(nowMs: Long = clock()) {
        prunedAt = nowMs
        val limit = nowMs - KEEP_DAYS * DAY_MS
        var removed = 0
        for (server in servers()) {
            for ((month, file) in months(server)) {
                // La fine del mese (l'inizio del mese dopo) prima del limite: tutto il mese è più vecchio di un anno.
                val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (end <= limit && file.delete()) removed++
            }
        }
        cars.prune(nowMs)
        if (removed > 0) Log.i(TAG, "Diario: mesi più vecchi di un anno tolti: $removed")
    }

    /** "Cancella la cronologia": via tutti gli ascolti di tutti i Music Assistant, e gli intervalli in auto. */
    @Synchronized
    fun forget() {
        journalDir.deleteRecursively()
        cars.clear()
        Log.i(TAG, "Diario: cronologia cancellata")
    }

    private fun endsMidLine(file: File): Boolean {
        val length = file.length()
        if (length == 0L) return false
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - 1)
            return raf.read() != '\n'.code
        }
    }

    /** I Music Assistant con un diario. */
    fun servers(): List<String> = journalDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    private fun serverDir(serverId: String) = File(journalDir, serverId.replace(UNSAFE, "_"))

    private fun monthFile(serverId: String, month: YearMonth) = File(serverDir(serverId), "$month.jsonl")

    /** I file dei mesi di un server, in ordine; i nomi che non sono mesi si lasciano stare. */
    private fun months(serverId: String): List<Pair<YearMonth, File>> =
        serverDir(serverId).listFiles()?.mapNotNull { file ->
            if (!file.name.endsWith(".jsonl")) return@mapNotNull null
            val month = runCatching { YearMonth.parse(file.name.removeSuffix(".jsonl")) }.getOrNull() ?: return@mapNotNull null
            month to file
        }?.sortedBy { it.first }.orEmpty()

    companion object {
        /** Quanto si tiene: un anno (decisione dell'utente). */
        const val KEEP_DAYS = 365L

        const val PLACE_CAR = "auto"
        const val PLACE_PHONE = "telefono"

        internal const val DAY_MS = 86_400_000L
        private const val TAG = "Xonos"
        private val UNSAFE = Regex("[^A-Za-z0-9_.-]")

        /** La cartella `per-te` dentro [noBackupDir] (nell'app context.noBackupFilesDir). */
        fun at(noBackupDir: File): File = File(noBackupDir, "per-te")
    }
}

/**
 * Quando il telefono era in auto (Gearslip o un'altra app che comanda il lettore da fuori): coppie inizio-fine in
 * `per-te/car-sessions.json`. Le scrive ServerKeeper col bisogno CAR ([begin] e [end]); il diario le legge per dire
 * "auto" o "telefono". Una sessione rimasta aperta (l'app chiusa senza [end]) conta al massimo [MAX_OPEN_MS].
 */
class CarSessions(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var loaded = false
    private val sessions = ArrayList<LongArray>()
    private var open: Long? = null

    /** L'auto si è collegata: comincia un intervallo, se non ce n'è già uno aperto. */
    @Synchronized
    fun begin(nowMs: Long = clock()) {
        load()
        val started = open
        if (started != null) {
            if (nowMs - started <= MAX_OPEN_MS) return
            // Aperta da troppo: l'app era stata chiusa senza fine. Si chiude al massimo che può valere.
            sessions += longArrayOf(started, started + MAX_OPEN_MS)
        }
        open = nowMs
        save()
    }

    /** L'auto se n'è andata: chiude l'intervallo aperto. */
    @Synchronized
    fun end(nowMs: Long = clock()) {
        load()
        val started = open ?: return
        sessions += longArrayOf(started, max(started, min(nowMs, started + MAX_OPEN_MS)))
        open = null
        save()
    }

    /** Gli intervalli, anche quello aperto (fino ad adesso). */
    @Synchronized
    fun intervals(nowMs: Long = clock()): List<LongRange> {
        load()
        val closed = sessions.map { it[0]..it[1] }
        val started = open ?: return closed
        return closed + listOf(started..min(nowMs, started + MAX_OPEN_MS))
    }

    /** Vero se [timeMs] cade in un intervallo in auto. */
    fun isCar(timeMs: Long): Boolean = intervals().any { timeMs in it }

    /** Via gli intervalli di più di un anno. */
    @Synchronized
    fun prune(nowMs: Long = clock()) {
        load()
        val limit = nowMs - Journal.KEEP_DAYS * Journal.DAY_MS
        if (sessions.removeAll { it[1] < limit }) save()
    }

    @Synchronized
    fun clear() {
        sessions.clear()
        open = null
        loaded = true
        file.delete()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            o.optJSONArray("sessions")?.let { array ->
                for (i in 0 until array.length()) {
                    val pair = array.optJSONArray(i) ?: continue
                    val start = pair.optLong(0, -1)
                    val end = pair.optLong(1, -1)
                    if (start > 0 && end >= start) sessions += longArrayOf(start, end)
                }
            }
            open = if (o.has("open") && !o.isNull("open")) o.optLong("open").takeIf { it > 0 } else null
        } catch (e: Exception) {
            Log.w(TAG, "Auto: intervalli illeggibili (${e.javaClass.simpleName})")
        }
    }

    private fun save() {
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        try {
            file.parentFile?.mkdirs()
            val o = JSONObject()
                .put("v", 1)
                .put("sessions", JSONArray(sessions.map { JSONArray().put(it[0]).put(it[1]) }))
                .put("open", open ?: JSONObject.NULL)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(o.toString())
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Auto: intervalli non salvati (${Logged.error(e)})")
        }
    }

    companion object {
        const val FILE = "car-sessions.json"

        /** Più di così un viaggio senza fine non conta. */
        const val MAX_OPEN_MS = 4 * 3_600_000L

        private const val MAX_SESSIONS = 5000
        private const val TAG = "Xonos"

        /** Il file dentro [noBackupDir] (nell'app context.noBackupFilesDir). */
        fun at(noBackupDir: File): CarSessions = CarSessions(File(Journal.at(noBackupDir), FILE))
    }
}

/**
 * Cosa suona adesso, come lo vede il registratore: lo stato della coda del telefono e la posizione del lettore.
 */
data class NowListening(
    val serverId: String,
    val queueId: String,
    val queueItemId: String,
    val uri: String,
    val libraryUri: String?,
    val name: String,
    val mediaType: String,
    val artists: List<ArtistRef>,
    val album: Ref?,
    val durationSec: Int,
    val context: String?,
    val positionSec: Double,
    /** Suona davvero (non in pausa, non fermo). */
    val playing: Boolean,
) {
    companion object {
        /** Dallo stato della coda; null senza brano in corso. [positionSec] è quella del lettore. */
        fun of(serverId: String, queue: QueueState, positionSec: Double, playing: Boolean = queue.playing): NowListening? {
            val item = queue.current ?: return null
            val uri = item.uri ?: return null
            return NowListening(
                serverId = serverId,
                queueId = queue.queueId,
                queueItemId = item.id,
                uri = item.serviceUri ?: uri,
                libraryUri = item.libraryUri,
                name = item.title,
                mediaType = item.mediaType ?: "track",
                artists = item.artists.ifEmpty { listOfNotNull(item.artistRef?.let { ArtistRef(it.uri, it.name) }) },
                album = item.albumRef,
                durationSec = item.durationSec,
                context = queue.source?.uri,
                positionSec = positionSec,
                playing = playing,
            )
        }
    }
}

/**
 * Trasforma gli stati del lettore in ascolti del diario: si chiama [update] a ogni cambio (brano, play, pausa,
 * posizione) e [flush] quando l'app si chiude. Un ascolto si chiude quando cambia brano, dopo una pausa lunga o con
 * [flush], e va a [onListen]. I secondi ascoltati contano la posizione che avanza mentre suona (non i salti dentro il
 * brano); "saltato" vuol dire meno di [SKIP_BEFORE_S], non finito e un altro brano sulla stessa coda entro
 * [SKIP_GAP_MS]. Senza Android e senza file: lo prova JournalCheck.
 */
class ListenRecorder(
    /** Il luogo di un ascolto cominciato a quel momento (vedi [Journal.placeAt]). */
    private val place: (Long) -> String,
    private val onListen: (serverId: String, listen: Listen) -> Unit,
) {
    private class Pending(val now: NowListening, val createdMs: Long) {
        var startedMs: Long? = null
        var listened = 0.0
        var lastPos = now.positionSec
        var reached = now.positionSec
        var lastMs = createdMs
        var playing = false

        /** Fin quando si sa che suonava. */
        var aliveMs = createdMs
    }

    private var pending: Pending? = null

    /** Lo stato di adesso ([now] null: niente in corso, per esempio la coda vuota). */
    @Synchronized
    fun update(now: NowListening?, nowMs: Long) {
        // Una pausa lunga: l'ascolto è finito lì, e chi riprende dopo comincia un ascolto nuovo.
        pending?.let { paused ->
            if (!paused.playing && nowMs - paused.aliveMs > PAUSE_ENDS_MS) {
                close(paused, nextOnSameQueueAt = null)
                pending = null
            }
        }
        val current = pending
        if (current != null) {
            val same = now != null && now.serverId == current.now.serverId && now.queueId == current.now.queueId &&
                now.queueItemId == current.now.queueItemId
            if (same) {
                advance(current, now!!.positionSec, nowMs)
                // Ripeti un brano: la stessa voce torna all'inizio dopo essere arrivata in fondo.
                if (current.playing && finished(current) && now.positionSec + RESTART_BACK_S < current.reached) {
                    close(current, nextOnSameQueueAt = null)
                    pending = Pending(now, nowMs).also { start(it, now, nowMs) }
                    return
                }
                current.playing = now.playing
                if (now.playing) {
                    current.aliveMs = nowMs
                    if (current.startedMs == null) current.startedMs = nowMs
                }
                return
            }
            // Un altro brano (o niente): si chiude quello di prima.
            advance(current, null, nowMs)
            val sameQueue = now != null && now.serverId == current.now.serverId && now.queueId == current.now.queueId
            close(current, nextOnSameQueueAt = if (sameQueue) nowMs else null)
            pending = null
        }
        if (now != null) pending = Pending(now, nowMs).also { start(it, now, nowMs) }
    }

    /** Chiude l'ascolto in corso (l'app si chiude, il diario si spegne). */
    @Synchronized
    fun flush(nowMs: Long) {
        val current = pending ?: return
        advance(current, null, nowMs)
        close(current, nextOnSameQueueAt = null)
        pending = null
    }

    /** Dimentica l'ascolto in corso senza scriverlo ("Cancella la cronologia"). */
    @Synchronized
    fun drop() {
        pending = null
    }

    private fun start(p: Pending, now: NowListening, nowMs: Long) {
        p.playing = now.playing
        if (now.playing) {
            p.startedMs = nowMs
            p.aliveMs = nowMs
        }
    }

    /**
     * Porta avanti i secondi ascoltati fino a [nowMs]. Con [position] null (il brano non è più quello mostrato) la
     * posizione si stima: se suonava è andato avanti col tempo, fino alla fine del brano.
     */
    private fun advance(p: Pending, position: Double?, nowMs: Long) {
        val wall = max(0L, nowMs - p.lastMs) / 1000.0
        val duration = p.now.durationSec.toDouble()
        val pos = position ?: if (p.playing) {
            val estimate = p.lastPos + wall
            if (duration > 0) min(estimate, duration) else estimate
        } else {
            p.lastPos
        }
        if (p.playing) {
            val delta = pos - p.lastPos
            p.listened += when {
                // La posizione va avanti col tempo: tanto si è ascoltato (ferma vuol dire che aspetta l'audio).
                delta >= 0 && delta <= wall + SLACK_S -> delta
                // Uno spostamento (avanti più del tempo passato, o indietro): intanto suonava, circa quanto il tempo.
                delta > wall + SLACK_S || delta < -SLACK_S -> wall
                // Piccoli scarti all'indietro tra un aggiornamento e l'altro.
                else -> 0.0
            }
            if (duration > 0) p.listened = min(p.listened, duration)
            p.aliveMs = nowMs
        }
        p.reached = max(p.reached, pos)
        p.lastPos = pos
        p.lastMs = nowMs
    }

    private fun finished(p: Pending): Boolean {
        val duration = p.now.durationSec
        if (duration <= 0) return false
        val spoken = p.now.mediaType == "podcast_episode" || p.now.mediaType == "audiobook"
        return if (spoken) p.reached >= duration - SPOKEN_END_S else p.reached >= duration * FINISHED_SHARE
    }

    private fun close(p: Pending, nextOnSameQueueAt: Long?) {
        if (p.listened < MIN_LISTEN_S) return
        val done = finished(p)
        val skipped = !done && p.listened < SKIP_BEFORE_S && nextOnSameQueueAt != null && nextOnSameQueueAt - p.aliveMs <= SKIP_GAP_MS
        val started = p.startedMs ?: p.createdMs
        val n = p.now
        onListen(
            n.serverId,
            Listen(
                timeMs = started,
                uri = n.uri,
                libraryUri = n.libraryUri,
                name = n.name,
                mediaType = n.mediaType,
                artists = n.artists,
                album = n.album,
                durationSec = n.durationSec,
                seconds = p.listened,
                finished = done,
                skipped = skipped,
                context = n.context,
                place = place(started),
            ),
        )
    }

    companion object {
        const val SKIP_BEFORE_S = 30.0
        const val SKIP_GAP_MS = 10_000L

        /** Sotto questo un brano non si è sentito: non si scrive. */
        const val MIN_LISTEN_S = 0.5

        /** Finito: il 90% del brano, o per podcast e audiolibri l'ultimo minuto (come Music Assistant). */
        const val FINISHED_SHARE = 0.9
        const val SPOKEN_END_S = 60

        /** Una pausa più lunga chiude l'ascolto. */
        const val PAUSE_ENDS_MS = 30 * 60_000L

        private const val SLACK_S = 2.0
        private const val RESTART_BACK_S = 10.0
    }
}
