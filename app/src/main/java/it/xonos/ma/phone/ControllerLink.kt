package it.xonos.ma.phone

import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.PlayContext
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.QueueEntry
import it.xonos.ma.phone.model.Repeat
import it.xonos.ma.playback.MaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il lettore del servizio visto dallo schermo del telefono, attraverso un MediaController: lo
 * stesso stato e gli stessi comandi che vede Gearslip (vedi [MaPlayer]). Tutto sul thread principale.
 */
class ControllerLink : PlayerLink, Player.Listener {

    private var controller: MediaController? = null
    private val window = Timeline.Window()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(this)
        update()
    }

    fun detach() {
        controller?.removeListener(this)
        controller = null
    }

    override fun onEvents(player: Player, events: Player.Events) = update()

    private fun update() {
        val c = controller ?: return
        _problem.value = c.playerError?.message
        if (c.currentMediaItem == null) {
            _nowPlaying.value = null
            return
        }
        val metadata = c.mediaMetadata
        val extras = metadata.extras
        val sourceUri = extras?.getString(MaPlayer.EXTRA_SOURCE_URI)
        val timeline = c.currentTimeline
        val queue = (0 until timeline.windowCount).map { i ->
            val item = timeline.getWindow(i, window).mediaItem.mediaMetadata
            QueueEntry(item.title?.toString().orEmpty(), item.artist?.toString(), item.artworkUri?.toString())
        }
        _nowPlaying.value = NowPlaying(
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            artwork = metadata.artworkUri?.toString(),
            uri = extras?.getString(MaPlayer.EXTRA_URI),
            mediaType = extras?.getString(MaPlayer.EXTRA_TYPE),
            albumUri = extras?.getString(MaPlayer.EXTRA_ALBUM),
            artistUri = extras?.getString(MaPlayer.EXTRA_ARTIST),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            playing = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            canSeek = c.isCurrentMediaItemSeekable,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> Repeat.ONE
                Player.REPEAT_MODE_ALL -> Repeat.ALL
                else -> Repeat.OFF
            },
            favorite = (metadata.userRating as? HeartRating)?.isHeart == true,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            queueEditableFrom = extras?.getInt(MaPlayer.EXTRA_EDITABLE_FROM, Int.MAX_VALUE) ?: Int.MAX_VALUE,
            // Da dove suona la coda ("In riproduzione da"), se MaPlayer lo sa.
            context = if (extras != null && sourceUri != null) {
                PlayContext(
                    uri = sourceUri,
                    name = extras.getString(MaPlayer.EXTRA_SOURCE_NAME).orEmpty(),
                    type = extras.getString(MaPlayer.EXTRA_SOURCE_TYPE) ?: sourceUri.substringAfter("://").substringBefore('/'),
                    image = extras.getString(MaPlayer.EXTRA_SOURCE_IMAGE),
                )
            } else {
                null
            },
        )
    }

    override fun positionMs(): Long = controller?.currentPosition ?: 0

    override fun playPause() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else c.play()
    }

    override fun next() {
        controller?.seekToNext()
    }

    override fun previous() {
        controller?.seekToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
    }

    override fun setRepeat(mode: Repeat) {
        controller?.repeatMode = when (mode) {
            Repeat.OFF -> Player.REPEAT_MODE_OFF
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setFavorite(on: Boolean) {
        controller?.setRating(HeartRating(on))
    }

    override fun skipTo(index: Int) {
        controller?.seekToDefaultPosition(index)
    }

    override fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    override fun playNextInQueue(index: Int) {
        val c = controller ?: return
        c.moveMediaItem(index, c.currentMediaItemIndex + 1)
    }
}
