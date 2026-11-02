package it.xonos.ma.phone.model

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Device
import it.xonos.ma.data.Devices
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueItem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.SkipSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Un lettore di Music Assistant comandato dallo schermo del telefono: la musica esce da lì (una
 * cassa, una TV), non dal telefono. Stato e coda arrivano con gli eventi della sua coda, come per
 * il telefono in [it.xonos.ma.playback.MaPlayer]; i comandi vanno alla sua coda.
 */
class RemoteLink internal constructor(
    val device: Device,
    private val repo: MaRepository,
    private val devices: Devices,
    private val library: Library,
    /** L'indirizzo di una copertina alla misura chiesta. */
    private val image: (MaImage?, Int) -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : PlayerLink {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    // Il telefono non ha niente da dire sui problemi di un altro lettore: Music Assistant li mostra nella sua app.
    override val problem: StateFlow<String?> = MutableStateFlow(null)

    private val _volume = MutableStateFlow(device.volume)

    /** Il volume del lettore (0-100), se lo dice. */
    val volume: StateFlow<Int?> = _volume.asStateFlow()

    private val lock = Any()
    private var queue: QueueState? = null
    private var window: List<QueueItem> = emptyList()
    private var windowStart = 0
    private var elapsedMs = 0L
    private var elapsedAt = 0L
    private val favorites = mutableMapOf<String, Boolean>()
    private var windowJob: Job? = null
    private var sync: Job? = null

    /** Il brano verso cui si sta saltando: lo schermo lo mostra subito, Music Assistant ci arriva dopo. */
    private var pendingIndex: Int? = null
    private val skips = SkipSender(scope) { index -> devices.command("play_index", device.id) { put("index", index) } }

    private val listener: (String, String?, Any?) -> Unit = { event, objectId, data ->
        if (objectId == device.id) onEvent(event, data)
    }

    /** Comincia a seguire il lettore. */
    fun start() {
        repo.addEventListener(listener)
        // A ogni collegamento, anche il primo: con l'app chiusa la connessione cade e gli eventi della cassa si
        // perdono, quindi al ritorno si rilegge la sua coda (invece di mostrare il brano di quando la si era lasciata).
        sync = scope.launch {
            repo.connection.collect { connection ->
                if (connection !is Connection.Connected) return@collect
                try {
                    devices.queue(device.id)?.let { apply(it) }
                    refreshWindow(now = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Arriverà con gli eventi.
                }
            }
        }
    }

    fun stop() {
        repo.removeEventListener(listener)
        sync?.cancel()
        windowJob?.cancel()
    }

    // ---------- Eventi ----------

    private fun onEvent(event: String, data: Any?) {
        when (event) {
            "queue_updated", "queue_added" -> QueueState.from(data as? JSONObject)?.let { apply(it) }
            "queue_items_updated" -> {
                QueueState.from(data as? JSONObject)?.let { apply(it) }
                refreshWindow()
            }
            "queue_time_updated" -> (data as? Number)?.let { seconds ->
                synchronized(lock) {
                    elapsedMs = (seconds.toDouble() * 1000).toLong()
                    elapsedAt = clock()
                }
                publish()
            }
            "player_updated" -> (data as? JSONObject)?.let { player ->
                if (!player.isNull("volume_level")) _volume.value = player.optInt("volume_level")
            }
        }
    }

    private fun apply(q: QueueState) {
        val needsWindow = synchronized(lock) {
            val old = queue
            queue = q
            elapsedMs = (q.elapsedSec * 1000).toLong()
            elapsedAt = clock()
            val index = q.currentIndex
            if (index != null && index == pendingIndex) pendingIndex = null
            old == null || old.items != q.items || old.shuffle != q.shuffle ||
                (index != null && (index < windowStart || index >= windowStart + window.size - AHEAD_MARGIN))
        }
        if (needsWindow) refreshWindow()
        publish()
    }

    /** Il tratto di coda intorno al brano in corso (quello che mostra il pannello della coda). */
    private fun refreshWindow(now: Boolean = false) {
        windowJob?.cancel()
        windowJob = scope.launch {
            if (!now) delay(WINDOW_DEBOUNCE_MS)
            val current = synchronized(lock) { queue }?.currentIndex ?: 0
            val start = max(0, current - BEHIND)
            val items = try {
                devices.items(device.id, start, BEHIND + AHEAD + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            synchronized(lock) {
                window = items
                windowStart = start
            }
            publish()
        }
    }

    private fun publish() {
        _nowPlaying.value = synchronized(lock) { build() }
    }

    private fun build(): NowPlaying? {
        val q = queue ?: return null
        if (q.items == 0) return null
        val pending = pendingIndex?.let { it - windowStart }?.takeIf { it in window.indices }
        val current = pending?.let { window[it] } ?: q.current ?: return null
        val duration = current.durationSec * 1000L
        val index = pending ?: q.currentIndex?.let { it - windowStart }?.takeIf { it in window.indices } ?: -1
        val radio = current.mediaType == "radio"
        return NowPlaying(
            title = if (radio) q.streamTitle ?: current.title else current.title,
            artist = if (radio) current.title else current.artist,
            album = current.album,
            artwork = image(current.image, 512),
            uri = current.uri,
            mediaType = current.mediaType,
            albumUri = current.albumRef?.uri,
            artistUri = current.artistRef?.uri,
            durationMs = duration,
            playing = q.playing,
            buffering = false,
            canSeek = duration > 0 && !radio,
            shuffle = q.shuffle,
            repeat = when (q.repeat) {
                "one" -> Repeat.ONE
                "all" -> Repeat.ALL
                else -> Repeat.OFF
            },
            favorite = current.uri?.let { favorites[it] } ?: current.favorite,
            queue = window.map { QueueEntry(it.title, it.artist, image(it.image, 256)) },
            queueIndex = index,
            queueEditableFrom = q.firstEditable - windowStart,
        )
    }

    override fun positionMs(): Long = synchronized(lock) {
        val q = queue ?: return 0
        if (pendingIndex != null) return 0
        val duration = (q.current?.durationSec ?: 0) * 1000L
        val now = if (q.playing) elapsedMs + (clock() - elapsedAt) else elapsedMs
        if (duration > 0) min(now, duration) else now
    }

    // ---------- Comandi ----------

    private fun send(name: String, fill: JSONObject.() -> Unit = {}) {
        scope.launch {
            try {
                devices.command(name, device.id, fill)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lo stato vero torna con gli eventi.
            }
        }
    }

    override fun playPause() = send("play_pause")

    override fun next() = skipBy(1)

    override fun previous() {
        // Dopo i primi secondi "indietro" ricomincia il brano.
        if (synchronized(lock) { pendingIndex == null } && positionMs() > RESTART_MS) return send("previous")
        skipBy(-1)
    }

    /** Avanti e indietro premuti in fretta finiscono al brano dove ci si ferma (vedi [SkipSender]). */
    private fun skipBy(step: Int) {
        var gather = true
        val target = synchronized(lock) {
            val q = queue ?: return
            gather = q.current?.singleStream != false
            val base = pendingIndex ?: q.currentIndex ?: return
            (base + step).coerceIn(0, max(0, q.items - 1)).also { pendingIndex = it }
        }
        publish()
        jumpTo(target, gather)
    }

    private fun jumpTo(target: Int, gather: Boolean) {
        skips.request(target, if (gather) SkipSender.GATHER_MS else 0L) {
            // Se Music Assistant non ci arriva, lo schermo torna a quello che dice lui.
            scope.launch {
                delay(PENDING_TIMEOUT_MS)
                synchronized(lock) { if (pendingIndex == target) pendingIndex = null }
                publish()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val seconds = (positionMs.coerceAtLeast(0) / 1000).toInt()
        synchronized(lock) {
            elapsedMs = seconds * 1000L
            elapsedAt = clock()
        }
        send("seek") { put("position", seconds) }
    }

    override fun setShuffle(on: Boolean) = send("shuffle") { put("shuffle_enabled", on) }

    override fun setRepeat(mode: Repeat) = send("repeat") {
        put(
            "repeat_mode",
            when (mode) {
                Repeat.OFF -> "off"
                Repeat.ALL -> "all"
                Repeat.ONE -> "one"
            },
        )
    }

    override fun setFavorite(on: Boolean) {
        val current = synchronized(lock) { queue?.current } ?: return
        val uri = current.uri ?: return
        val type = current.mediaType ?: return
        synchronized(lock) { favorites[uri] = on }
        publish()
        scope.launch {
            try {
                library.setFavorite(uri, type, current.provider, current.itemId, on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) { favorites.remove(uri) }
                publish()
            }
        }
    }

    override fun skipTo(index: Int) {
        val target = synchronized(lock) { (windowStart + index).also { pendingIndex = it } }
        publish()
        jumpTo(target, gather = false)
    }

    override fun removeFromQueue(index: Int) {
        // Sparisce subito; la coda vera arriva poi con gli eventi.
        val id = synchronized(lock) {
            window.getOrNull(index)?.id?.also { window = window.filterIndexed { i, _ -> i != index } }
        } ?: return
        publish()
        send("delete_item") { put("item_id_or_index", id) }
    }

    override fun playNextInQueue(index: Int) {
        val id = synchronized(lock) {
            val item = window.getOrNull(index) ?: return
            val next = ((queue?.currentIndex ?: return) - windowStart + 1).coerceIn(0, window.size - 1)
            window = window.toMutableList().apply { add(next, removeAt(index)) }
            item.id
        }
        publish()
        // Per Music Assistant uno spostamento di 0 vuol dire "in testa ai prossimi".
        send("move_item") {
            put("queue_item_id", id)
            put("pos_shift", 0)
        }
    }

    fun setVolume(level: Int) {
        _volume.value = level
        scope.launch {
            try {
                devices.setVolume(device.id, level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Il volume vero torna con gli eventi.
            }
        }
    }

    private companion object {
        const val BEHIND = 20
        const val AHEAD = 60
        const val AHEAD_MARGIN = 10
        const val WINDOW_DEBOUNCE_MS = 300L
        const val PENDING_TIMEOUT_MS = 8_000L
        const val RESTART_MS = 3_000L
    }
}
