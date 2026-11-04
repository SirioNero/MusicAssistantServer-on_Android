package it.xonos.ma.phone.model

import kotlinx.coroutines.flow.StateFlow

/** Ripeti: niente, tutta la coda, il brano. */
enum class Repeat { OFF, ALL, ONE }

/** Un brano della coda come lo mostra il telefono. */
data class QueueEntry(val title: String, val artist: String?, val artwork: String?)

/**
 * Cosa sta suonando il telefono, come lo mostrano mini-lettore e lettore. [artwork] è ciò che si
 * passa a Coil: un indirizzo content:// dell'app sul telefono, uno http nel banco di prova.
 */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val album: String?,
    val artwork: String?,
    /** L'uri di Music Assistant del brano, per il testo e i preferiti. */
    val uri: String?,
    /** "track", "radio", "podcast_episode", "audiobook"... */
    val mediaType: String?,
    val albumUri: String?,
    val artistUri: String?,
    /** 0 se non si sa (le radio). */
    val durationMs: Long,
    /** Vero se deve suonare (anche mentre aspetta l'audio). */
    val playing: Boolean,
    /** In attesa dell'audio: la posizione è ferma. */
    val buffering: Boolean,
    val canSeek: Boolean,
    val shuffle: Boolean,
    val repeat: Repeat,
    val favorite: Boolean,
    val queue: List<QueueEntry>,
    /** Il brano in corso dentro [queue]. */
    val queueIndex: Int,
    /** Da qui in poi, in [queue], i brani si possono togliere o spostare (prima: già suonati, in corso o nel buffer). */
    val queueEditableFrom: Int = Int.MAX_VALUE,
    /** Da dove suona la coda, per "In riproduzione da" nel lettore; null se non si sa (un brano scelto da solo). */
    val context: PlayContext? = null,
)

/**
 * La sorgente della coda (la prima delle `sources` di Music Assistant): album, playlist, artista, genere, podcast,
 * radio. [type] è il media_type di Music Assistant; [image] è ciò che si passa a Coil, come [NowPlaying.artwork].
 */
data class PlayContext(val uri: String, val name: String, val type: String, val image: String?)

/** Il lettore del telefono visto dallo schermo: sul telefono il MediaController del servizio. */
interface PlayerLink {
    /** Null quando la coda è vuota. */
    val nowPlaying: StateFlow<NowPlaying?>

    /** Il problema da mostrare (rete, Music Assistant), nella lingua scelta; null se va tutto bene. */
    val problem: StateFlow<String?>

    /** La posizione nel brano adesso, stimata tra un aggiornamento e l'altro. */
    fun positionMs(): Long

    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: Repeat)
    fun setFavorite(on: Boolean)

    /** Salta al brano numero [index] di [NowPlaying.queue]. */
    fun skipTo(index: Int)

    /** Toglie dalla coda il brano numero [index] di [NowPlaying.queue] (vedi [NowPlaying.queueEditableFrom]). */
    fun removeFromQueue(index: Int) {}

    /** Sposta il brano numero [index] di [NowPlaying.queue] subito dopo quello in corso. */
    fun playNextInQueue(index: Int) {}
}
