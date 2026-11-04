package it.xonos.ma.playback

import android.util.Log
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.LastListenStore
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
 * telefono) dagli intervalli che scrive ServerKeeper.
 *
 * Il servizio gli passa lo stato a ogni cambio del lettore e ogni [SAVE_EVERY_MS] mentre suona ([update]), e lo chiude
 * quando l'app può fermarsi ([flush]). I file si scrivono su un thread solo, in ordine ([io]): mai sul thread
 * principale. Nel registro solo numeri. Senza Android: lo prova ListeningCheck.
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

    /** Gli intervalli in auto: li scrive ServerKeeper, il diario li legge per il luogo. */
    val cars: CarSessions get() = journal.cars

    /** La copia dell'ultimo ascolto, `last-listen.json`. */
    val lastListen = LastListenStore(File(noBackupDir, LAST_LISTEN_FILE))

    private val recorder = ListenRecorder(journal::placeAt, ::written)

    // Solo sul thread principale: quando scrivere la copia.
    private var savedKey: String? = null
    private var savedAt = 0L
    private var lastKey: String? = null
    private var lastPlaying = false

    /**
     * La voce della coda che si è sentita suonare in questo processo. Solo per lei si scrive la copia: all'apertura
     * Music Assistant può dare una coda vecchia (dopo un distacco ripristina quella salvata prima, con un altro brano e
     * la posizione a 0), e scriverla cancellerebbe proprio la copia che serve a rimettere a posto la coda.
     */
    private var heardKey: String? = null

    // Solo sul thread [io]: i numeri per il registro.
    private var written = 0
    private var finished = 0
    private var skipped = 0
    private var loggedAt = 0L

    /**
     * Lo stato del lettore: a ogni cambio (brano, play, pausa, stop) e ogni [SAVE_EVERY_MS] mentre suona. [serverId] è
     * il Music Assistant dell'ultima connessione (null prima della prima: allora niente); [next] dà i brani dopo quello
     * in corso, chiesti solo se si scrive la copia; [positionSec] è la posizione che si sente. Con [force] la copia si
     * scrive anche se niente è cambiato (l'app si chiude), ma sempre solo per una voce sentita suonare.
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
        io.execute { recorder.update(listening, now) }

        val q = queue ?: return
        val item = q.current ?: return
        val key = "$server|${q.queueId}|${item.id}"
        val wasPlaying = lastPlaying && lastKey == key
        lastKey = key
        lastPlaying = q.playing
        if (q.playing) heardKey = key
        val due = when {
            heardKey != key -> false
            force -> true
            // Un brano nuovo (che suona: sennò non sarebbe [heardKey]).
            key != savedKey -> true
            // Pausa, stop, o la coda arrivata in fondo.
            wasPlaying && !q.playing -> true
            q.playing -> now - savedAt >= SAVE_EVERY_MS
            else -> false
        }
        if (!due) return
        val copy = LastListen.of(server, q, next(), positionSec, now) ?: return
        savedKey = key
        savedAt = now
        io.execute { lastListen.write(copy) }
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
        journal.forget()
    }

    private fun written(serverId: String, listen: Listen) {
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
