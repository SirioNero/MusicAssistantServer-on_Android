package it.xonos.ma.playback

import android.util.Log
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.LastListenStore
import it.xonos.ma.data.Logged
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueItem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.objects
import it.xonos.ma.data.taste.CarSessions
import it.xonos.ma.data.taste.Journal
import it.xonos.ma.data.taste.JournalCounts
import it.xonos.ma.data.taste.Listen
import it.xonos.ma.data.taste.ListenRecorder
import it.xonos.ma.data.taste.NowListening
import it.xonos.ma.phone.model.ResumePlan
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cosa resta degli ascolti sul telefono, dallo stato del lettore: la copia dell'ultimo ascolto (per "Riprendi", vedi
 * data/LastListen.kt) e il diario degli ascolti (per "Per te", vedi data/taste/Journal.kt), con il luogo (auto o
 * telefono) dai viaggi in auto che il servizio segna con [carTrip].
 *
 * Il servizio gli passa lo stato a ogni cambio del lettore e ogni [SAVE_EVERY_MS] mentre suona ([update]), e lo chiude
 * quando l'app può fermarsi ([flush]). Intanto l'ascolto in corso si copia in [PENDING_FILE], così un processo che muore
 * senza chiuderlo (aggiornamento, Android che lo chiude) non lo perde: lo scrive il processo dopo. I file si scrivono su
 * un thread solo, in ordine ([io]): mai sul thread principale. Nel registro solo numeri. Senza Android: lo prova
 * ListeningCheck.
 *
 * Un'istanza sola per l'app ([open]): il servizio scrive, le impostazioni contano e cancellano, e il diario tiene in
 * memoria gli intervalli in auto, quindi chi li scrive deve usare la stessa.
 */
class Listening(
    /** Nell'app context.noBackupFilesDir: fuori dal backup di Android. */
    noBackupDir: File,
    /** La cronologia è accesa (Impostazioni › Per te). */
    private val enabled: () -> Boolean,
    /** Dove si scrivono e leggono i file: un thread solo, così le scritture restano in ordine. */
    val io: Executor = newWriter(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val journal = Journal(Journal.at(noBackupDir), enabled, clock = clock)

    /** Gli intervalli in auto (vedi [carTrip]): il diario li legge per il luogo. */
    val cars: CarSessions get() = journal.cars

    /** La copia dell'ultimo ascolto, `last-listen.json`. */
    val lastListen = LastListenStore(File(noBackupDir, LAST_LISTEN_FILE))

    /** L'ascolto in corso, finché non è nel diario (vedi [PENDING_FILE]). */
    private val pendingFile = File(Journal.at(noBackupDir), PENDING_FILE)

    private val recorder = ListenRecorder(journal::placeAt, ::written)

    // Solo sul thread principale: quando scrivere la copia.
    private var savedKey: String? = null
    private var savedAt = 0L
    private var savedEnded = false
    private var lastKey: String? = null
    private var lastPlaying = false

    /**
     * La voce della coda che si è sentita suonare in questo processo. Solo per lei si scrive la copia: all'apertura
     * Music Assistant può dare una coda vecchia (dopo un distacco ripristina quella salvata prima, con un altro brano e
     * la posizione a 0), e scriverla cancellerebbe proprio la copia che serve a rimettere a posto la coda.
     */
    private var heardKey: String? = null

    /** Fin dove si è sentita [heardKey], in secondi: l'ultima posizione mentre suonava, o quella dell'ultima copia. */
    private var heardPos = 0.0

    /** [heardKey] ha suonato dopo l'ultima copia: solo allora la chiusura dell'app ([update] con force) la rifà. */
    private var playedSinceSave = false

    // Solo sul thread [io]: i numeri per il registro, e quando si è copiato l'ascolto in corso.
    private var written = 0
    private var finished = 0
    private var skipped = 0
    private var loggedAt = 0L
    private var pendingAt = 0L
    private var pendingPlaying = false

    init {
        // Una volta per processo, sul thread dei file: l'ascolto rimasto a metà nel processo di prima, e la potatura
        // dell'anno (scrivendo la fa il diario da sé, ma con la cronologia spenta non si scrive). Un errore qui non deve
        // chiudere l'app: il thread [io] non ha nessuno che lo prenda.
        io.execute {
            runCatching { recoverPending() }
            runCatching { journal.prune() }
        }
    }

    /**
     * Lo stato del lettore: a ogni cambio (brano, play, pausa, stop) e ogni [SAVE_EVERY_MS] mentre suona. [serverId] è
     * il Music Assistant della coda (null prima della prima connessione: allora niente); [next] dà i brani dopo quello
     * in corso, chiesti solo se si scrive la copia; [positionSec] è la posizione che si sente. Con [force] (l'app si
     * chiude) la copia si rifà anche se niente è cambiato, ma solo se la voce sentita ha suonato dopo l'ultima copia: così
     * chiave e ora restano quelle dell'ascolto (un "Riprendi" nascosto resta nascosto).
     * Sul thread principale.
     */
    fun update(
        serverId: String?,
        queue: QueueState?,
        next: () -> List<QueueItem>,
        positionSec: Double,
        force: Boolean = false,
    ) {
        val server = serverId?.takeIf { it.isNotEmpty() } ?: return
        val now = clock()
        val listening = queue?.let { NowListening.of(server, it, positionSec) }
        io.execute {
            recorder.update(listening, now)
            savePending(now, listening?.playing == true)
        }

        val q = queue ?: return
        val item = q.current ?: return
        val key = "$server|${q.queueId}|${item.id}"
        val wasPlaying = lastPlaying && lastKey == key
        lastKey = key
        lastPlaying = q.playing
        if (q.playing) {
            heardKey = key
            heardPos = positionSec
            playedSinceSave = true
        }
        val due = when {
            heardKey != key -> false
            // Un brano nuovo (che suona: sennò non sarebbe [heardKey]).
            key != savedKey -> true
            // Pausa, stop, o la coda arrivata in fondo.
            wasPlaying && !q.playing -> true
            // Music Assistant segna la fine della coda qualche secondo dopo l'ultimo stato.
            q.ended && !savedEnded -> true
            force -> playedSinceSave
            q.playing -> now - savedAt >= SAVE_EVERY_MS
            else -> false
        }
        if (!due) return
        // Senza suonare la posizione non torna indietro: dopo un distacco Music Assistant ripristina la coda col punto del
        // suo ultimo salvataggio (pochi secondi dopo l'inizio del brano), e alla fine della coda dà 0. Si tiene quello
        // sentito.
        val position = if (!q.playing && positionSec < heardPos - ResumePlan.SAME_SPOT_S) heardPos else positionSec
        val copy = LastListen.of(server, q, next(), position, now) ?: return
        savedKey = key
        savedAt = now
        savedEnded = copy.ended
        heardPos = copy.positionSec
        playedSinceSave = q.playing
        io.execute { lastListen.write(copy) }
    }

    /**
     * Un viaggio in auto comincia ([on]) o finisce: un'app che sfoglia il menu da fuori (Gearslip, Android Auto) è
     * arrivata, o l'ultima se n'è andata (lo dice il servizio). Solo inizio e fine, all'ora di adesso. Con la cronologia
     * spenta non ne comincia nessuno.
     */
    fun carTrip(on: Boolean) {
        val now = clock()
        io.execute { if (on) cars.begin(now) else cars.end(now) }
    }

    /** L'app può fermarsi (chiusa, congelata da Android): l'ascolto in corso va nel diario adesso. */
    fun flush() {
        val now = clock()
        io.execute { recorder.flush(now) }
    }

    /** I numeri del diario, di tutti i Music Assistant (Impostazioni › Per te). Legge i file: dal thread [io]. */
    suspend fun counts(): JournalCounts = onIo { journal.counts() }

    /** "Cancella la cronologia": tutti gli ascolti, l'ascolto in corso e gli intervalli in auto. */
    suspend fun forget() = onIo {
        recorder.drop()
        pendingFile.delete()
        journal.forget()
    }

    /**
     * Copia l'ascolto in corso in [pendingFile] ogni [SAVE_EVERY_MS], e subito quando smette di suonare. Sul thread [io],
     * dopo [ListenRecorder.update]. Tutto o niente: un file a parte, poi al posto di quello vecchio.
     */
    private fun savePending(now: Long, playing: Boolean) {
        if (!enabled() || (now - pendingAt < SAVE_EVERY_MS && playing == pendingPlaying)) return
        val (serverId, listen) = recorder.snapshot() ?: return
        pendingAt = now
        pendingPlaying = playing
        try {
            pendingFile.parentFile?.mkdirs()
            val temp = File(pendingFile.parentFile, "${pendingFile.name}.tmp")
            temp.writeText(JSONObject().put("server_id", serverId).put("listen", listen.toJson()).toString())
            if (!temp.renameTo(pendingFile)) temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Per te: ascolto in corso non copiato (${Logged.error(e)})")
        }
    }

    /** L'ascolto rimasto in [pendingFile] da un processo che non l'ha chiuso: nel diario, poi via. Sul thread [io]. */
    private fun recoverPending() {
        if (!pendingFile.exists()) return
        try {
            val o = JSONObject(pendingFile.readText())
            val serverId = o.optString("server_id")
            val listen = o.optJSONObject("listen")?.let { Listen.parse(it.toString()) }
            if (listen != null && serverId.isNotEmpty()) written(serverId, listen)
        } catch (e: Exception) {
            // Senza il testo dell'errore: org.json ci metterebbe il contenuto (titoli).
            Log.w(TAG, "Per te: ascolto in corso illeggibile (${e.javaClass.simpleName})")
        }
        pendingFile.delete()
    }

    private fun written(serverId: String, listen: Listen) {
        // Nel diario, o non più da scrivere: la copia dell'ascolto in corso non serve più.
        pendingFile.delete()
        if (!enabled()) return
        journal.add(serverId, listen)
        written++
        if (listen.finished) finished++
        if (listen.skipped) skipped++
        val now = clock()
        // Il primo subito, poi al massimo uno ogni [LOG_EVERY_MS]: il registro di Xonos che si condivide è corto.
        if (loggedAt == 0L || now - loggedAt >= LOG_EVERY_MS) {
            loggedAt = now
            Log.i(TAG, "Per te: diario, ascolti scritti da quando Xonos è partito $written (finiti $finished, saltati $skipped)")
        }
    }

    private suspend fun <T> onIo(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        io.execute {
            try {
                continuation.resume(block())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    /** Gli artisti della libreria: quanti, quanti con generi, quanti con l'id di MusicBrainz. */
    data class ArtistCounts(val artists: Int, val withGenres: Int, val withMbid: Int)

    companion object {
        const val LAST_LISTEN_FILE = "last-listen.json"

        /** L'ascolto in corso, dentro la cartella `per-te` (vedi [Journal.at]). */
        const val PENDING_FILE = "pending-listen.json"

        /** Ogni quanto, mentre suona, si rifà la copia dell'ultimo ascolto. */
        const val SAVE_EVERY_MS = 30_000L

        private const val LOG_EVERY_MS = 10 * 60_000L
        private const val TAG = "Xonos"
        private const val PAGE = 500

        /** L'istanza dell'app, se il servizio (o chi altro) l'ha già aperta. */
        @Volatile
        var shared: Listening? = null
            internal set

        /** L'istanza dell'app: la prima volta la crea. */
        @Synchronized
        fun open(noBackupDir: File, enabled: () -> Boolean): Listening =
            shared ?: Listening(noBackupDir, enabled).also { shared = it }

        private fun newWriter(): Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "xonos-ascolti").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        /**
         * Quanti artisti della libreria hanno generi e un id di MusicBrainz: dice se i consigli potranno contare su
         * quei dati. Solo la libreria di Music Assistant (a pagine da 500), nessuna richiesta ai servizi.
         */
        suspend fun artistCounts(repo: MaRepository): ArtistCounts {
            var artists = 0
            var withGenres = 0
            var withMbid = 0
            var offset = 0
            while (true) {
                val page = repo.command("music/artists/library_items", 60_000) {
                    put("limit", PAGE)
                    put("offset", offset)
                    put("order_by", "sort_name")
                } as? JSONArray ?: break
                for (artist in page.objects()) {
                    artists++
                    if (hasGenres(artist)) withGenres++
                    if (hasMbid(artist)) withMbid++
                }
                if (page.length() < PAGE) break
                offset += PAGE
            }
            return ArtistCounts(artists, withGenres, withMbid)
        }

        /** La riga per il registro: solo numeri. */
        fun diagnosis(counts: ArtistCounts): String =
            "Per te: artisti ${counts.artists}, con generi ${counts.withGenres}, con MBID ${counts.withMbid}"

        /**
         * I numeri della diagnosi già fatta per [key] (il Music Assistant e la versione di Xonos), da [saved] (vedi
         * Settings.perTeDiagnosis); null se non c'è o è di un altro server o di un'altra versione. Così tutti gli artisti
         * della libreria si scaricano una volta per versione, non a ogni avvio del processo.
         */
        fun savedDiagnosis(saved: String?, key: String): ArtistCounts? {
            val parts = saved?.split(' ') ?: return null
            if (parts.size != 4 || parts[0] != key) return null
            val numbers = parts.drop(1).map { it.toIntOrNull() ?: return null }
            return ArtistCounts(numbers[0], numbers[1], numbers[2])
        }

        /** Quello che si salva per [savedDiagnosis]. */
        fun saveDiagnosis(key: String, counts: ArtistCounts): String =
            "$key ${counts.artists} ${counts.withGenres} ${counts.withMbid}"

        private fun hasGenres(artist: JSONObject): Boolean {
            val genres = artist.optJSONObject("metadata")?.optJSONArray("genres") ?: artist.optJSONArray("genres")
            return genres != null && genres.length() > 0
        }

        private fun hasMbid(artist: JSONObject): Boolean {
            val ids = artist.optJSONArray("external_ids") ?: return false
            for (i in 0 until ids.length()) {
                val pair = ids.optJSONArray(i) ?: continue
                if (pair.optString(0) == "musicbrainz_artistid" && pair.optString(1).let { it.isNotEmpty() && it != "null" }) return true
            }
            return false
        }
    }
}
