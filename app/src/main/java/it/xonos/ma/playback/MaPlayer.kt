package it.xonos.ma.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Problem
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueItem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.SkipSender
import it.xonos.ma.data.SoloistUnlock
import it.xonos.ma.data.objects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Il lettore che vede Gearslip: rispecchia la coda del telefono in Music Assistant.
 *
 * La coda, il brano e la posizione li decide Music Assistant e arrivano con i suoi eventi; play,
 * pausa, salti, ricerca, casuale e ripeti diventano comandi alla coda. L'audio vero lo suona
 * [it.xonos.ma.sendspin.AudioEngine], che arriva per conto suo con Sendspin.
 *
 * Tutto gira sul thread principale.
 */
@OptIn(UnstableApi::class)
class MaPlayer(
    looper: Looper,
    private val repo: MaRepository,
    private val queueId: String,
    /** Vero quando API e audio sono collegati: solo allora la coda del telefono esiste. */
    private val ready: StateFlow<Boolean>,
    /** Vero mentre la musica esce davvero dal telefono. */
    private val outputting: StateFlow<Boolean>,
    private val artwork: (MaImage?, Int) -> Uri?,
    /** Di quanto ciò che si sente è indietro rispetto alla posizione di Music Assistant (vedi AudioEngine.lateMs). */
    private val lateMs: () -> Long = { 0L },
    /** Cosa dire in auto di una connessione che non va, nella lingua scelta sul telefono. */
    private val describe: (Connection) -> String?,
) : SimpleBasePlayer(looper) {

    /** Suona un elemento scelto dall'auto (vedi [BrowseTree.play]). */
    var onPlayRequest: (suspend (MediaItem) -> Unit)? = null

    private val handler = Handler(looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var queue: QueueState? = null
    private var window: List<QueueItem> = emptyList()
    private var windowStart = 0
    private var windowJob: Job? = null

    // La posizione nel brano: l'ultima detta da Music Assistant e quando.
    private var positionMs = 0L
    private var positionAt = 0L
    private var discontinuity = false

    private var audible = false
    private var problem: String? = null
    private val favorites = mutableMapOf<String, Boolean>()

    /** L'inizio della finestra della coda usata nell'ultimo stato pubblicato: serve a tradurre gli indici. */
    private var stateStart = 0

    /** I brani dell'ultimo stato pubblicato, nell'ordine dei suoi indici, e quello in corso. */
    private var stateItems: List<QueueItem> = emptyList()
    private var stateCurrent = 0

    // Le modifiche alla coda in attesa: finiscono quando la finestra della coda le mostra.
    private class WindowExpectation(val test: (List<QueueItem>, Int) -> Boolean, val future: SettableFuture<Void?>)
    private val windowExpectations = mutableListOf<WindowExpectation>()

    /** Quando la rete cade a metà di un brano: per riprendere lì appena torna. */
    private class Drop(val index: Int, val positionMs: Long, val at: Long, val server: String?)
    private var drop: Drop? = null
    private var lastPlayingAt = 0L

    /** Quando si è staccata l'ultima uscita esterna (Bluetooth, cuffie): vedi [forgetDrop]. */
    private var outputGoneAt = Long.MIN_VALUE

    private class Expectation(val test: (QueueState?) -> Boolean, val future: SettableFuture<Void?>)
    private val expectations = mutableListOf<Expectation>()

    /** Spotify (Soloist) occupato da un ascolto appeso: lo si sblocca e si riprova il comando. */
    private val unlock = SoloistUnlock(repo)

    // Il salto in attesa (vedi [skip]): uno per volta, verso l'ultimo brano chiesto.
    private var pendingSkip: SettableFuture<Void?>? = null
    private val skips = SkipSender(scope) { index ->
        unlock.retrying(queueId) {
            repo.command("player_queues/play_index") {
                put("queue_id", queueId)
                put("index", index)
            }
        }
    }

    private val listener: (String, String?, Any?) -> Unit = { event, objectId, data ->
        if (objectId == queueId) handler.post { onEvent(event, data) }
    }

    init {
        repo.addEventListener(listener)
        scope.launch {
            repo.connection.collect { connection ->
                // Mentre si ricollega resta il problema di prima, se c'era.
                problem = if (connection is Connection.Connecting) problem else describe(connection)
                invalidateState()
            }
        }
        scope.launch {
            ready.collect { isReady ->
                if (isReady) onReady() else onLost()
            }
        }
        scope.launch {
            outputting.collect { on ->
                if (on != audible) {
                    // Da qui la posizione corre (o si ferma) davvero.
                    positionMs = currentPositionMs()
                    positionAt = SystemClock.elapsedRealtime()
                    audible = on
                    invalidateState()
                }
            }
        }
    }

    // ---------- Stato ----------

    override fun getState(): State {
        val q = queue
        val builder = State.Builder()
            .setAvailableCommands(commands(q))
            .setPlayWhenReady(q?.playing == true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setShuffleModeEnabled(q?.shuffle == true)
            .setRepeatMode(
                when (q?.repeat) {
                    "one" -> Player.REPEAT_MODE_ONE
                    "all" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                },
            )
            .setAudioAttributes(AUDIO_ATTRIBUTES)

        val (start, items) = visibleWindow(q)
        stateStart = start
        stateItems = items
        if (q == null || items.isEmpty()) {
            builder.setPlaylist(emptyList()).setPlaybackState(Player.STATE_IDLE)
            problem?.let { builder.setPlayerError(PlaybackException(it, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)) }
            return builder.build()
        }
        val current = ((q.currentIndex ?: start) - start).coerceIn(0, items.size - 1)
        stateCurrent = current
        builder
            .setPlaylist(items.mapIndexed { i, item -> itemData(item, i == current, q, q.firstEditable - start) })
            .setCurrentMediaItemIndex(current)
            .setContentPositionMs(positionSupplier(q, items[current]))
            .setPlaybackState(if (q.playing && !audible) Player.STATE_BUFFERING else Player.STATE_READY)
        if (discontinuity) {
            discontinuity = false
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL, currentPositionMs())
        }
        return builder.build()
    }

    /** La parte di coda da mostrare (qualche brano prima e una cinquantina dopo), con il suo inizio. */
    private fun visibleWindow(q: QueueState?): Pair<Int, List<QueueItem>> {
        val index = q?.currentIndex
        val current = q?.current
        if (q == null || index == null || current == null) return 0 to emptyList()
        // Finché la finestra non è aggiornata (dopo un salto o un casuale) si mostra solo il brano in corso:
        // un brano non deve mai comparire due volte.
        val position = index - windowStart
        if (window.getOrNull(position)?.id != current.id) return index to listOf(current)
        // Il brano in corso viene dall'evento più recente: per le radio cambia il titolo trasmesso.
        val items = window.mapIndexed { i, item -> if (i == position) current else item }
        if (items.distinctBy { it.id }.size != items.size) return index to listOf(current)
        return windowStart to items
    }

    private fun commands(q: QueueState?): Player.Commands {
        val builder = Player.Commands.Builder().addAll(*BASE_COMMANDS)
        val current = q?.current
        if (current != null && current.durationSec > 0 && current.mediaType != "radio") {
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        return builder.build()
    }

    private fun itemData(item: QueueItem, isCurrent: Boolean, q: QueueState, editableFrom: Int): MediaItemData {
        val radio = item.mediaType == "radio"
        val streamTitle = q.streamTitle?.takeIf { isCurrent && radio && it.isNotBlank() }
        val favorite = favorites[item.id] ?: item.favorite
        val metadata = MediaMetadata.Builder()
            // Per le radio il titolo è quello che sta trasmettendo, e la stazione fa da artista.
            .setTitle(streamTitle ?: item.title)
            .setArtist(if (streamTitle != null) item.title else item.artist)
            .setAlbumTitle(item.album)
            .setArtworkUri(artwork(item.image, NOW_PLAYING_ART))
            .setMediaType(
                when (item.mediaType) {
                    "radio" -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                    "podcast_episode" -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                    "audiobook" -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                    else -> MediaMetadata.MEDIA_TYPE_MUSIC
                },
            )
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setUserRating(HeartRating(favorite))
            // Per lo schermo del telefono: il testo del brano, e da lì l'album e l'artista.
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_URI, item.uri)
                    putString(EXTRA_TYPE, item.mediaType)
                    putString(EXTRA_ALBUM, item.albumRef?.uri)
                    putString(EXTRA_ARTIST, item.artistRef?.uri)
                    // Sul brano in corso: da dove in poi la coda si può cambiare.
                    if (isCurrent) putInt(EXTRA_EDITABLE_FROM, editableFrom)
                },
            )
            .apply { if (item.durationSec > 0) setDurationMs(item.durationSec * 1000L) }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.uri ?: item.id)
            .setMediaMetadata(metadata)
            .build()
        val seekable = item.durationSec > 0 && !radio
        return MediaItemData.Builder(item.id)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(if (seekable) item.durationSec * 1_000_000L else C.TIME_UNSET)
            .setIsSeekable(seekable)
            .build()
    }

    private fun positionSupplier(q: QueueState, current: QueueItem): PositionSupplier {
        val base = positionMs
        val at = positionAt
        val moving = q.playing && audible
        val duration = current.durationSec * 1000L
        return PositionSupplier {
            // Si mostra ciò che si sente: dopo un avvio lento, un po' indietro rispetto al server.
            val now = ((if (moving) base + (SystemClock.elapsedRealtime() - at) else base) - lateMs()).coerceAtLeast(0)
            if (duration > 0) min(now, duration) else now
        }
    }

    private fun currentPositionMs(): Long {
        val q = queue ?: return positionMs
        val moving = q.playing && audible
        return if (moving) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs
    }

    // ---------- Eventi di Music Assistant ----------

    private fun onEvent(event: String, data: Any?) {
        when (event) {
            "queue_updated", "queue_added" -> QueueState.from(data as? JSONObject)?.let { apply(it) }
            "queue_items_updated" -> {
                QueueState.from(data as? JSONObject)?.let { apply(it, itemsChanged = true) }
                refreshWindow()
            }
            "queue_time_updated" -> (data as? Number)?.let { setElapsed(it.toDouble(), fromServer = true) }
            "player_added" -> if (queue == null) fetchQueue()
        }
    }

    private fun apply(q: QueueState, itemsChanged: Boolean = false) {
        val old = queue
        queue = q
        if (q.playing) lastPlayingAt = SystemClock.elapsedRealtime()
        val jumped = old == null || old.currentIndex != q.currentIndex || old.current?.id != q.current?.id
        setElapsed(q.elapsedSec, fromServer = !jumped)
        val index = q.currentIndex
        val needsWindow = itemsChanged || old == null || old.items != q.items || old.shuffle != q.shuffle ||
            (index != null && (index < windowStart || index >= windowStart + window.size - AHEAD_MARGIN))
        if (needsWindow && !itemsChanged) refreshWindow()
        checkExpectations()
        invalidateState()
    }

    /** Nuova posizione dal server; se è vicina a quella prevista non si disturba nessuno. */
    private fun setElapsed(seconds: Double, fromServer: Boolean) {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
        val expected = currentPositionMs()
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
        if (abs(expected - ms) > POSITION_TOLERANCE_MS) {
            if (fromServer) discontinuity = true
            invalidateState()
        }
    }

    private fun refreshWindow() {
        windowJob?.cancel()
        windowJob = scope.launch {
            delay(WINDOW_DEBOUNCE_MS)
            val q = queue ?: return@launch
            val start = max(0, (q.currentIndex ?: 0) - BEHIND)
            val items = try {
                repo.command("player_queues/items") {
                    put("queue_id", queueId)
                    put("offset", start)
                    put("limit", BEHIND + AHEAD + 1)
                } as? JSONArray
            } catch (e: Exception) {
                Log.w(TAG, "Coda non disponibile: ${e.message}")
                null
            } ?: return@launch
            window = items.objects().mapNotNull(QueueItem::from)
            windowStart = start
            favorites.clear()
            invalidateState()
            windowExpectations.removeAll { e -> e.test(window, windowStart).also { if (it) e.future.set(null) } }
        }
    }

    private fun fetchQueue(then: (() -> Unit)? = null) {
        scope.launch {
            val q = try {
                QueueState.from(repo.command("player_queues/get") { put("queue_id", queueId) } as? JSONObject)
            } catch (e: Exception) {
                null
            }
            if (q != null) {
                apply(q)
                refreshWindow()
            }
            then?.invoke()
        }
    }

    private fun onReady() {
        fetchQueue { resumeAfterDrop() }
    }

    /** La rete è caduta: se stava suonando, ci si ricorda dove, per riprendere lì. */
    private fun onLost() {
        val q = queue ?: return
        val now = SystemClock.elapsedRealtime()
        val index = q.currentIndex ?: return
        if (q.playing || now - lastPlayingAt < RECENT_PLAY_MS) drop = Drop(index, currentPositionMs(), now, repo.baseUrl)
    }

    /**
     * Music Assistant ferma la coda quando il telefono sparisce. Al ritorno si riparte da dove la
     * musica sarebbe arrivata: nel frattempo il telefono ha suonato quello che aveva già ricevuto.
     */
    private fun resumeAfterDrop() {
        val d = drop ?: return
        drop = null
        val q = queue ?: return
        val away = SystemClock.elapsedRealtime() - d.at
        if (away > RESUME_WINDOW_MS || q.playing || q.currentIndex != d.index) return
        // Un'uscita esterna si è staccata dopo l'ultima volta che si suonava (anche prima che Xonos vedesse il calo,
        // che col ping arriva 20-40 s dopo): la musica uscirebbe dall'altoparlante. E un altro server non è il calo.
        if (outputGoneAt > lastPlayingAt || (d.server != null && repo.baseUrl != d.server)) return
        val target = d.positionMs + min(away, BUFFER_GUESS_MS)
        val duration = (q.current?.durationSec ?: 0) * 1000L
        Log.i(TAG, "Riprendo dopo ${away / 1000} s senza rete")
        scope.launch {
            try {
                // Dopo un buco di rete l'ascolto di prima può tenere occupato Spotify (Soloist).
                unlock.retrying(queueId) {
                    if (duration > 0 && target < duration - 2_000) {
                        repo.command("player_queues/play_index") {
                            put("queue_id", queueId)
                            put("index", d.index)
                            put("seek_position", target / 1000)
                        }
                    } else {
                        repo.command("player_queues/play") { put("queue_id", queueId) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ripresa non riuscita: ${e.message}")
            }
        }
    }

    // ---------- Comandi ----------

    /** Scelte dall'auto (handleSetMediaItems) per cui Music Assistant sta ancora preparando la musica. */
    private var choosing = 0

    /** Pausa toccata mentre Music Assistant preparava una scelta: si mette appena la musica parte. */
    private var pauseAfterChoice = false

    /** Il play mandato a Music Assistant finché la musica non parte (con Soloist circa 7 s). */
    private var pendingPlay: ListenableFuture<*>? = null

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (choosing > 0) {
            // Dall'auto arrivano insieme la scelta e "play": il play_media della scelta fa già partire la musica, e
            // un player_queues/play adesso (la coda non è in pausa) la farebbe ripartire da capo (Soloist: 7 s).
            pauseAfterChoice = !playWhenReady
            return Futures.immediateVoidFuture()
        }
        val pending = pendingPlay?.takeUnless { it.isDone }
        if (pending != null) {
            // Un secondo play (cuffie, auto) mentre la musica parte: per Music Assistant sarebbe un resume, che con la
            // coda già in partenza fa ripartire il brano da capo. Niente di nuovo.
            if (playWhenReady) return pending
            // Una pausa adesso: "pause" arriverebbe prima dell'avvio e Music Assistant lo ignorerebbe (la musica
            // partirebbe lo stesso); "stop" aspetta che l'avvio finisca e poi ferma.
            pendingPlay = null
            return command("player_queues/stop") { it?.playing != true }
        }
        val q = queue ?: return Futures.immediateVoidFuture()
        if (q.playing == playWhenReady) return Futures.immediateVoidFuture()
        val future = command(if (playWhenReady) "player_queues/play" else "player_queues/pause") { it?.playing == playWhenReady }
        if (playWhenReady) pendingPlay = future
        return future
    }

    /**
     * Mentre un play aspetta Music Assistant, chi guarda (auto, notifica, telefono) vede la rotella: il segnaposto
     * di Media3 cambierebbe solo playWhenReady, cioè "sta suonando" senza suono.
     */
    override fun getPlaceholderState(suggestedPlaceholderState: State): State =
        if (suggestedPlaceholderState.playWhenReady &&
            suggestedPlaceholderState.playbackState == Player.STATE_READY &&
            queue?.playing != true
        ) {
            suggestedPlaceholderState.buildUpon().setPlaybackState(Player.STATE_BUFFERING).build()
        } else {
            suggestedPlaceholderState
        }

    override fun handlePrepare(): ListenableFuture<*> {
        // Solo se il collegamento è fallito: mentre si collega, ricominciare da capo a ogni play (Media3 chiama
        // prepare a ogni play finché la coda non c'è) farebbe ripartire ogni volta dall'indirizzo di casa.
        if (repo.connection.value is Connection.Failed) repo.reconnect()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> = command("player_queues/stop") { it?.playing != true }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val q = queue ?: return Futures.immediateVoidFuture()
        val index = q.currentIndex
        val target = stateStart + mediaItemIndex
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                if (mediaItemIndex == C.INDEX_UNSET) command("player_queues/next") { it?.currentIndex != index } else skip(q, target)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                when {
                    target == index -> seekWithin(0)
                    mediaItemIndex == C.INDEX_UNSET -> command("player_queues/previous") { it?.currentIndex != index }
                    else -> skip(q, target)
                }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> seekWithin(positionMs)
            else ->
                if (target == index) {
                    seekWithin(if (positionMs == C.TIME_UNSET) 0 else positionMs)
                } else {
                    command("player_queues/play_index", args = {
                        put("index", target)
                        if (positionMs != C.TIME_UNSET && positionMs > 0) put("seek_position", positionMs / 1000)
                    }) { it?.currentIndex == target }
                }
        }
    }

    /**
     * Un salto (anche dall'auto). Avanti e indietro premuti in fretta finiscono al brano dove ci si
     * ferma, con un comando alla volta; con i servizi a un flusso alla volta (Spotify Soloist, Tidal)
     * si aspetta che i tocchi si fermino (vedi [SkipSender]). Intanto Media3 mostra già il brano scelto.
     */
    private fun skip(q: QueueState, target: Int): ListenableFuture<*> {
        // Il salto di prima confluisce in questo.
        pendingSkip?.set(null)
        val future = SettableFuture.create<Void?>()
        pendingSkip = future
        skips.request(target, gather = q.current?.singleStream != false) {
            handler.post {
                if (future.isDone) return@post
                // Mandato: il futuro si chiude quando Music Assistant ci arriva, o dopo qualche secondo.
                val expectation = Expectation({ it?.currentIndex == target }, future)
                expectations += expectation
                handler.postDelayed({ if (expectations.remove(expectation)) future.set(null) }, EXPECT_TIMEOUT_MS)
                checkExpectations()
            }
        }
        return future
    }

    private fun seekWithin(positionMs: Long): ListenableFuture<*> {
        val seconds = (positionMs.coerceAtLeast(0) / 1000).toInt()
        this.positionMs = seconds * 1000L
        positionAt = SystemClock.elapsedRealtime()
        return command("player_queues/seek", args = { put("position", seconds) }) {
            it != null && abs(it.elapsedSec - seconds) < 2
        }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        command("player_queues/shuffle", args = { put("shuffle_enabled", shuffleModeEnabled) }) {
            it?.shuffle == shuffleModeEnabled
        }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }
        return command("player_queues/repeat", args = { put("repeat_mode", mode) }) { it?.repeat == mode }
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        val request = onPlayRequest
        if (item == null || request == null) return Futures.immediateVoidFuture()
        val done = SettableFuture.create<Void?>()
        val before = queue
        choosing++
        pauseAfterChoice = false
        scope.launch {
            try {
                awaitReady()
                request(item)
                // Si aspetta che la coda lo mostri, così l'auto non vede un attimo il brano di prima.
                withTimeoutOrNull(PLAY_SETTLE_MS) {
                    while (true) {
                        val q = queue
                        if (q != null && q.playing && (before == null || q.current?.id != before.current?.id || !before.playing)) break
                        delay(100)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Non riesco a suonare ${item.mediaId}: ${e.message}")
                onFailure?.invoke(e)
            } finally {
                choosing--
                if (choosing == 0 && pauseAfterChoice) {
                    pauseAfterChoice = false
                    pauseFromSystem()
                }
                done.set(null)
            }
        }
        return done
    }

    // ---------- Modifiche alla coda (dallo schermo del telefono) ----------

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val ids = stateItems.subList(fromIndex.coerceIn(0, stateItems.size), toIndex.coerceIn(0, stateItems.size)).map { it.id }
        if (ids.isEmpty()) return Futures.immediateVoidFuture()
        // Media3 mostra già la coda senza quei brani: una seconda modifica fatta prima della risposta di Music
        // Assistant arriva con le posizioni di quella coda, quindi anche qui si tolgono subito.
        stateItems = stateItems.filterNot { it.id in ids }
        return windowCommand({ window, _ -> window.none { it.id in ids } }) {
            for (id in ids) {
                repo.command("player_queues/delete_item") {
                    put("queue_id", queueId)
                    put("item_id_or_index", id)
                }
            }
        }
    }

    /** Si sposta un brano alla volta: subito dopo quello in corso ("suona dopo") o di qualche posto. */
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val item = stateItems.getOrNull(fromIndex)
        if (item == null || toIndex != fromIndex + 1 || newIndex == fromIndex) return Futures.immediateVoidFuture()
        // Per Music Assistant uno spostamento di 0 vuol dire "in testa ai prossimi".
        val shift = if (newIndex == stateCurrent + 1) 0 else newIndex - fromIndex
        val target = stateStart + newIndex
        // Come per i brani tolti: le posizioni della prossima modifica sono quelle della coda già spostata.
        stateItems = stateItems.toMutableList().apply { add(newIndex.coerceAtMost(size - 1), removeAt(fromIndex)) }
        return windowCommand({ window, start -> window.indexOfFirst { it.id == item.id }.let { it >= 0 && start + it == target } }) {
            repo.command("player_queues/move_item") {
                put("queue_id", queueId)
                put("queue_item_id", item.id)
                put("pos_shift", shift)
            }
        }
    }

    // Aggiungere si fa dal menu (suona dopo, in coda), non da qui.
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun windowCommand(test: (List<QueueItem>, Int) -> Boolean, send: suspend () -> Unit): ListenableFuture<*> {
        val future = SettableFuture.create<Void?>()
        val expectation = WindowExpectation(test, future)
        windowExpectations += expectation
        handler.postDelayed({ if (windowExpectations.remove(expectation)) future.set(null) }, EXPECT_TIMEOUT_MS)
        scope.launch {
            try {
                send()
            } catch (e: Exception) {
                Log.w(TAG, "Coda non cambiata: ${e.message}")
                if (windowExpectations.remove(expectation)) future.set(null)
            }
        }
        return future
    }

    override fun handleRelease(): ListenableFuture<*> {
        repo.removeEventListener(listener)
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        expectations.forEach { it.future.set(null) }
        expectations.clear()
        return Futures.immediateVoidFuture()
    }

    /**
     * Si è staccato il Bluetooth (o le cuffie) mentre la musica era ferma per un calo di rete: al ritorno della rete
     * non si riprende da soli, perché uscirebbe dall'altoparlante del telefono.
     */
    fun forgetDrop() {
        if (drop != null) Log.i(TAG, "Uscita audio staccata: dopo il calo di rete non riprendo da solo")
        drop = null
        outputGoneAt = SystemClock.elapsedRealtime()
    }

    /** Lo stesso play o pausa, ma deciso dal telefono (chiamata in arrivo, cuffie staccate). */
    fun pauseFromSystem() {
        val q = queue ?: return
        if (q.playing) command("player_queues/pause") { it?.playing != true }
    }

    fun resumeFromSystem() {
        val q = queue ?: return
        if (!q.playing && q.current != null) command("player_queues/play") { it?.playing == true }
    }

    val isPlayingOnServer: Boolean get() = queue?.playing == true

    /** Il brano in corso, per i preferiti. */
    val currentItem: QueueItem? get() = queue?.current

    /** Cambia subito il cuore del brano, prima che Music Assistant confermi. */
    fun showFavorite(item: QueueItem, favorite: Boolean) {
        favorites[item.id] = favorite
        invalidateState()
    }

    /** Aspetta che il telefono sia un lettore di Music Assistant: al primo avvio ci vuole un attimo. */
    /**
     * Un comando chiesto da chi comanda il lettore (l'auto, la notifica, il telefono) non è riuscito: il perché, da
     * mostrare. Prima restava nel logcat, e in auto la scelta compariva un attimo e poi niente (per esempio "Qui non
     * c'è niente da suonare: ..." di Music Assistant). Sul thread principale.
     */
    var onFailure: ((Exception) -> Unit)? = null

    private suspend fun awaitReady() {
        withTimeoutOrNull(READY_TIMEOUT_MS) { ready.first { it } }
            ?: throw MaException("Music Assistant non è pronto", problem = Problem.NOT_CONNECTED)
    }

    /**
     * Manda un comando alla coda e restituisce un futuro che si completa quando Music Assistant
     * mostra l'effetto (o dopo qualche secondo): fino ad allora l'auto vede già il nuovo stato.
     */
    private fun command(
        name: String,
        args: JSONObject.() -> Unit = {},
        until: (QueueState?) -> Boolean,
    ): ListenableFuture<*> {
        val future = SettableFuture.create<Void?>()
        val expectation = Expectation(until, future)
        expectations += expectation
        // Un limite in ogni caso, se la risposta non arriva mai.
        handler.postDelayed({ if (expectations.remove(expectation)) future.set(null) }, COMMAND_CAP_MS)
        scope.launch {
            try {
                unlock.retrying(queueId) {
                    repo.command(name) {
                        put("queue_id", queueId)
                        args()
                    }
                }
                checkExpectations()
                // Il tempo per vedere l'effetto conta dalla risposta: player_queues/play risponde solo quando la musica
                // parte (con Soloist circa 7 s). Contato dall'invio, dopo 3 s l'auto e il telefono tornavano a "play"
                // mentre la musica stava ancora partendo, e un secondo tocco la faceva ripartire da capo.
                handler.postDelayed({ if (expectations.remove(expectation)) future.set(null) }, EXPECT_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name non riuscito: ${e.message}")
                if (expectations.remove(expectation)) future.set(null)
                onFailure?.invoke(e)
            }
        }
        return future
    }

    private fun checkExpectations() {
        val q = queue
        val iterator = expectations.iterator()
        while (iterator.hasNext()) {
            val expectation = iterator.next()
            if (expectation.test(q)) {
                iterator.remove()
                expectation.future.set(null)
            }
        }
    }

    companion object {
        /** Negli extra dei metadati: l'uri di Music Assistant del brano, il suo tipo, il suo album e il suo artista. */
        const val EXTRA_URI = "xonos.uri"
        const val EXTRA_EDITABLE_FROM = "xonos.editable_from"
        const val EXTRA_TYPE = "xonos.type"
        const val EXTRA_ALBUM = "xonos.album"
        const val EXTRA_ARTIST = "xonos.artist"

        private const val TAG = "Xonos"
        private const val BEHIND = 3
        private const val AHEAD = 50
        private const val AHEAD_MARGIN = 5
        private const val NOW_PLAYING_ART = 512
        private const val POSITION_TOLERANCE_MS = 1_500L
        private const val WINDOW_DEBOUNCE_MS = 150L
        private const val EXPECT_TIMEOUT_MS = 3_000L
        private const val COMMAND_CAP_MS = 60_000L

        private const val PLAY_SETTLE_MS = 4_000L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val RECENT_PLAY_MS = 30_000L
        private const val RESUME_WINDOW_MS = 10 * 60_000L

        /** Quanto audio il telefono ha di solito in serbo quando la rete cade. */
        private const val BUFFER_GUESS_MS = 25_000L

        private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        private val BASE_COMMANDS = intArrayOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        )
    }
}
