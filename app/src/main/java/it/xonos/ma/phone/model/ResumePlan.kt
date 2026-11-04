package it.xonos.ma.phone.model

import it.xonos.ma.data.LastListen
import it.xonos.ma.data.Library
import it.xonos.ma.data.QueueState

/**
 * Cosa fa "Riprendi" (vedi [ResumePlan]). Si esegue con [run].
 */
sealed interface ResumeStep {
    /** La coda su cui si suona: quella del telefono. */
    val queueId: String

    /** (a) Music Assistant ha ancora la coda al punto giusto: basta play. */
    data class Play(override val queueId: String) : ResumeStep

    /** (b) Stesso brano, ma la copia è più avanti: si suona quella voce dal punto della copia. */
    data class PlayIndex(override val queueId: String, val queueItemId: String, val seekSec: Int) : ResumeStep

    /**
     * (c) La coda non c'è più o è un'altra: la si rifà da [media] (la sorgente, o il brano da solo) partendo da
     * [startItem], poi si sposta a [seekSec] se serve. Casuale e ripeti come erano.
     */
    data class Rebuild(
        override val queueId: String,
        val media: String,
        val startItem: String?,
        val seekSec: Int,
        val shuffle: Boolean = false,
        val repeat: String = "off",
    ) : ResumeStep

    /** Il primo brano della coda, dall'inizio (una coda arrivata in fondo che Music Assistant non sa finita). */
    data class First(override val queueId: String) : ResumeStep

    /** La coda era finita: "Ascolta di nuovo" la rifà dall'inizio, con [then]. */
    data class ListenAgain(val then: ResumeStep) : ResumeStep {
        override val queueId: String get() = then.queueId
    }
}

/**
 * Riprendi: da dove ripartire, con le due fonti che non chiamano Spotify. La coda del telefono in Music Assistant
 * (`player_queues/get`) e la copia locale dell'ultimo ascolto ([LastListen]), che c'è anche col server fermo e regge un
 * Music Assistant ucciso prima di salvare la coda.
 *
 * - (a) Coda con la stessa voce (queue_item_id) e al massimo [SAME_SPOT_S] di differenza: player_queues/play.
 * - (b) Stessa voce, ma la copia è più recente e più avanti di oltre [SAME_SPOT_S]: play_index con seek.
 * - (c) Coda assente o diversa (e la copia più recente): play_media della sorgente con start_item, poi seek oltre
 *   [SEEK_FROM_S]; senza sorgente il brano da solo.
 * - Coda finita: "Ascolta di nuovo". Una copia di un altro Music Assistant non conta.
 *
 * Senza Android: ResumeCheck lo prova da solo e contro il Music Assistant di prova.
 */
object ResumePlan {
    /** Fin qui la posizione della coda e quella della copia sono "lo stesso punto". */
    const val SAME_SPOT_S = 10.0

    /** Dopo una ricostruzione si va al punto solo oltre questo: sotto si riparte dall'inizio del brano. */
    const val SEEK_FROM_S = 15.0

    /**
     * Quanto prima della coda può essere la copia ed essere ancora "più recente" (orologi e salvataggi di Music
     * Assistant non vanno a tempo con quelli del telefono). La coda del telefono la comanda solo Xonos.
     */
    const val NEWER_SLACK_MS = 60_000L

    /** Per quanti giorni si propone di riprendere la musica, e podcast e audiolibri. */
    const val MUSIC_DAYS = 14
    const val SPOKEN_DAYS = 30

    private const val DAY_MS = 86_400_000L

    /** La copia, se è di questo Music Assistant ([serverId]); null se è di un altro o se il server non si sa. */
    fun usable(listen: LastListen?, serverId: String): LastListen? =
        listen?.takeIf { serverId.isNotEmpty() && it.serverId == serverId }

    /**
     * Cosa mostra l'eroe "Riprendi": la copia o la coda di Music Assistant, la più recente delle due (la coda va
     * prima trasformata in copia con [LastListen.of], coi tre brani dopo). Null se non c'è niente da riprendere.
     */
    fun shown(listen: LastListen?, fromQueue: LastListen?, serverId: String): LastListen? {
        val own = usable(listen, serverId)
        val queue = usable(fromQueue, serverId)
        return when {
            own == null -> queue
            queue == null -> own
            // La stessa voce: la copia sa di più (posizione del lettore), salvo che la coda sia molto più recente.
            queue.queueItemId == own.queueItemId -> if (queue.timeMs > own.timeMs + NEWER_SLACK_MS) queue else own
            queue.timeMs > own.timeMs + NEWER_SLACK_MS -> queue
            else -> own
        }
    }

    /**
     * Si vede l'eroe? Solo se il telefono non suona, se c'è un ascolto di meno di [MUSIC_DAYS] giorni (podcast e
     * audiolibri [SPOKEN_DAYS]) e se la persona non ha nascosto proprio quello ([hiddenKey], vedi [LastListen.key]).
     */
    fun visible(listen: LastListen?, nowMs: Long, phonePlaying: Boolean, hiddenKey: String?): Boolean {
        if (listen == null || phonePlaying) return false
        if (hiddenKey != null && hiddenKey == listen.key) return false
        val days = if (listen.spoken) SPOKEN_DAYS else MUSIC_DAYS
        return nowMs - listen.timeMs < days * DAY_MS
    }

    /**
     * Cosa fare per riprendere. [listen] è la copia locale, [queue] la coda del telefono in Music Assistant ora (null se
     * non si è potuta leggere), [queueId] l'id della coda del telefono, [serverId] il Music Assistant collegato.
     * Null se non c'è niente da riprendere o se la coda sta già suonando (un play la farebbe ripartire).
     */
    fun decide(listen: LastListen?, queue: QueueState?, queueId: String, serverId: String): ResumeStep? {
        val own = usable(listen, serverId)
        val live = queue?.takeIf { it.items > 0 && it.current != null }
        if (live?.playing == true) return null
        if (live == null) return own?.let { rebuild(it, queueId) }
        val queueMs = (live.elapsedUpdatedAt * 1000).toLong()
        // Senza copia, o con una copia più vecchia della coda: decide Music Assistant.
        if (own == null || (queueMs > 0 && own.timeMs + NEWER_SLACK_MS < queueMs)) {
            return if (live.ended) ResumeStep.ListenAgain(ResumeStep.Play(queueId)) else ResumeStep.Play(queueId)
        }
        val sameItem = live.current?.id == own.queueItemId && (live.queueId.isEmpty() || live.queueId == queueId)
        if (!sameItem) {
            // La coda è un'altra, e la copia è più recente: si rifà quella della copia.
            return rebuild(own, queueId)
        }
        // Music Assistant fa ripartire da sé dal primo brano una coda finita; se lo sa solo la copia, glielo si dice.
        if (live.ended) return ResumeStep.ListenAgain(ResumeStep.Play(queueId))
        if (own.ended) return ResumeStep.ListenAgain(ResumeStep.First(queueId))
        val ahead = own.positionSec - live.resumeSec
        return if (ahead > SAME_SPOT_S) {
            ResumeStep.PlayIndex(queueId, own.queueItemId, own.positionSec.toInt())
        } else {
            ResumeStep.Play(queueId)
        }
    }

    /**
     * "Ricomincia": lo stesso brano dall'inizio. Sulla coda, se ha ancora quella voce; sennò la si rifà.
     */
    fun restart(listen: LastListen?, queue: QueueState?, queueId: String, serverId: String): ResumeStep? {
        val own = usable(listen, serverId)
        val live = queue?.takeIf { it.items > 0 && it.current != null }
        if (own == null) return live?.current?.let { ResumeStep.PlayIndex(queueId, it.id, 0) }
        if (live?.current?.id == own.queueItemId) return ResumeStep.PlayIndex(queueId, own.queueItemId, 0)
        return rebuild(own.copy(positionSec = 0.0, ended = false), queueId)
    }

    /** (c): dalla sorgente partendo dal brano, o il brano da solo; una coda finita riparte dall'inizio. */
    private fun rebuild(listen: LastListen, queueId: String): ResumeStep {
        val source = listen.source?.takeIf { it.uri != listen.track.uri }
        if (listen.ended) {
            return ResumeStep.ListenAgain(
                ResumeStep.Rebuild(queueId, source?.uri ?: listen.track.uri, null, 0, listen.shuffle, listen.repeat),
            )
        }
        // Una radio non ha un punto: si riprende e basta.
        val seek = if (listen.track.mediaType != "radio" && listen.positionSec > SEEK_FROM_S) listen.positionSec.toInt() else 0
        return ResumeStep.Rebuild(
            queueId = queueId,
            media = source?.uri ?: listen.track.uri,
            startItem = if (source != null) listen.track.uri else null,
            seekSec = seek,
            shuffle = listen.shuffle,
            repeat = listen.repeat,
        )
    }
}

/**
 * Esegue il passo con i comandi di Music Assistant. Ritorna quando la musica è partita (Music Assistant risponde allora;
 * con Spotify Soloist anche 7 s). Con la ricostruzione lo spostamento al punto è un secondo avvio.
 */
suspend fun ResumeStep.run(library: Library) {
    when (this) {
        is ResumeStep.Play -> library.resume(queueId)
        is ResumeStep.PlayIndex -> library.playIndex(queueId, queueItemId, seekSec)
        is ResumeStep.First -> library.playIndex(queueId, 0)
        is ResumeStep.Rebuild -> {
            // Col casuale acceso start_item non mette il brano in testa: lo si spegne prima e lo si riaccende dopo.
            library.setShuffle(queueId, false)
            library.playMedia(queueId, listOf(media), startItem = startItem)
            if (seekSec > 0) library.seek(queueId, seekSec)
            if (shuffle) library.setShuffle(queueId, true)
            library.setRepeat(queueId, repeat)
        }
        is ResumeStep.ListenAgain -> then.run(library)
    }
}
