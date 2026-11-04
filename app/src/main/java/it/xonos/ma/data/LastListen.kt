package it.xonos.ma.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * L'ultimo ascolto sul telefono, come lo ricorda Xonos: basta per disegnare "Riprendi" anche col server fermo, e per
 * rifare la coda se Music Assistant l'ha persa (vedi phone/model/ResumePlan.kt). Lo scrive il servizio dallo stato del
 * lettore: a pausa, stop e cambio di brano, ogni 30 s mentre suona, quando l'app si chiude.
 *
 * L'immagine resta quella di Music Assistant ([MaImage]: percorso e proxy_id), mai un indirizzo firmato.
 */
data class LastListen(
    /** Il Music Assistant (server_id): una copia di un altro server non vale. */
    val serverId: String,
    val queueId: String,
    /** La voce della coda (queue_item_id). */
    val queueItemId: String,
    val track: Track,
    /** Dove era arrivato, in secondi. */
    val positionSec: Double,
    /** Il posto del brano nella coda (da 0) e quante voci aveva. */
    val index: Int,
    val count: Int,
    /** Da dove suonava (la prima delle `sources` della coda); null per un brano scelto da solo. */
    val source: QueueSource?,
    /** I brani dopo (al massimo [NEXT]). */
    val next: List<Upcoming>,
    val shuffle: Boolean,
    /** "off", "one" o "all". */
    val repeat: String,
    /** La coda era arrivata in fondo: si propone "Ascolta di nuovo". */
    val ended: Boolean,
    /** Quando è stata fatta la copia, in ms dal 1970. */
    val timeMs: Long,
) {
    /** Il brano. [durationSec] 0 se non si sa (le radio). */
    data class Track(
        val uri: String,
        val name: String,
        val mediaType: String,
        val artists: List<ArtistRef>,
        val album: Ref?,
        val image: MaImage?,
        val durationSec: Int,
    ) {
        /** Gli artisti, separati da virgole. */
        val artist: String? get() = artists.joinToString(", ") { it.name }.ifEmpty { null }
    }

    /** Un brano che veniva dopo: quanto basta per il "poi:" della Home. */
    data class Upcoming(val uri: String?, val name: String, val artist: String?, val image: MaImage?)

    /** Un podcast o un audiolibro: per loro "Riprendi" resta di più e mostra quanto manca. */
    val spoken: Boolean get() = track.mediaType == "podcast_episode" || track.mediaType == "audiobook"

    /** Identifica questo ascolto (per nasconderlo "fino al prossimo ascolto"). */
    val key: String get() = "$queueItemId@$timeMs"

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("server_id", serverId)
        .put("queue_id", queueId)
        .put("queue_item_id", queueItemId)
        .put(
            "track",
            JSONObject()
                .put("uri", track.uri)
                .put("name", track.name)
                .put("type", track.mediaType)
                .put("artists", JSONArray(track.artists.map(::artistJson)))
                .putOpt("album", track.album?.let { JSONObject().put("uri", it.uri).put("name", it.name) })
                .putOpt("image", track.image?.let(::imageJson))
                .put("duration", track.durationSec),
        )
        .put("position", positionSec)
        .put("index", index)
        .put("count", count)
        .putOpt("source", source?.let { s ->
            JSONObject().put("uri", s.uri).put("name", s.name).put("type", s.mediaType).putOpt("image", s.image?.let(::imageJson))
        })
        .put("next", JSONArray(next.map { n ->
            JSONObject().putOpt("uri", n.uri).put("name", n.name).putOpt("artist", n.artist).putOpt("image", n.image?.let(::imageJson))
        }))
        .put("shuffle", shuffle)
        .put("repeat", repeat)
        .put("ended", ended)
        .put("time", timeMs)

    companion object {
        const val VERSION = 1

        /** Quanti brani dopo si ricordano. */
        const val NEXT = 3

        /**
         * La copia dallo stato della coda [queue] e dai brani dopo [next] (quelli da `current_index + 1`). [positionSec]
         * è la posizione che si sente (quella del lettore, più fresca di quella di Music Assistant). Null senza brano.
         */
        fun of(
            serverId: String,
            queue: QueueState,
            next: List<QueueItem>,
            positionSec: Double,
            timeMs: Long,
        ): LastListen? {
            val item = queue.current ?: return null
            val uri = item.uri ?: return null
            return LastListen(
                serverId = serverId,
                queueId = queue.queueId,
                queueItemId = item.id,
                track = Track(
                    uri = uri,
                    name = item.title,
                    mediaType = item.mediaType ?: "track",
                    artists = item.artists.ifEmpty {
                        listOfNotNull(item.artistRef?.let { ArtistRef(it.uri, it.name) })
                    },
                    album = item.albumRef,
                    image = item.image,
                    durationSec = item.durationSec,
                ),
                positionSec = positionSec.coerceAtLeast(0.0),
                index = queue.currentIndex ?: 0,
                count = queue.items,
                source = queue.source,
                next = next.take(NEXT).map { Upcoming(it.uri, it.title, it.artist, it.image) },
                shuffle = queue.shuffle,
                repeat = queue.repeat,
                ended = queue.ended,
                timeMs = timeMs,
            )
        }

        /** Il contrario di [toJson]; tollerante: i campi che mancano prendono un valore vuoto, senza brano null. */
        fun from(o: JSONObject?): LastListen? {
            if (o == null) return null
            val t = o.optJSONObject("track") ?: return null
            val uri = t.str("uri") ?: return null
            val itemId = o.str("queue_item_id") ?: return null
            val position = o.optDouble("position", 0.0)
            return LastListen(
                serverId = o.str("server_id").orEmpty(),
                queueId = o.str("queue_id").orEmpty(),
                queueItemId = itemId,
                track = Track(
                    uri = uri,
                    name = t.str("name").orEmpty(),
                    mediaType = t.str("type") ?: "track",
                    artists = t.optJSONArray("artists").objects().mapNotNull { a ->
                        val artistUri = a.str("uri") ?: return@mapNotNull null
                        ArtistRef(artistUri, a.str("name") ?: return@mapNotNull null, a.str("mbid"))
                    },
                    album = t.optJSONObject("album")?.let { a -> a.str("uri")?.let { Ref(it, a.str("name").orEmpty()) } },
                    image = t.optJSONObject("image")?.let(::imageFrom),
                    durationSec = t.optInt("duration"),
                ),
                positionSec = if (position.isNaN() || position < 0) 0.0 else position,
                index = o.optInt("index"),
                count = o.optInt("count"),
                source = o.optJSONObject("source")?.let { s ->
                    val sourceUri = s.str("uri") ?: return@let null
                    QueueSource(sourceUri, s.str("name").orEmpty(), s.str("type") ?: "playlist", s.optJSONObject("image")?.let(::imageFrom))
                },
                next = o.optJSONArray("next").objects().map { n ->
                    Upcoming(n.str("uri"), n.str("name").orEmpty(), n.str("artist"), n.optJSONObject("image")?.let(::imageFrom))
                },
                shuffle = o.optBoolean("shuffle"),
                repeat = o.str("repeat") ?: "off",
                ended = o.optBoolean("ended"),
                timeMs = o.optLong("time"),
            )
        }

        private fun artistJson(a: ArtistRef) = JSONObject().put("uri", a.uri).put("name", a.name).putOpt("mbid", a.mbid)

        private fun imageJson(i: MaImage) = JSONObject()
            .put("path", i.path)
            .put("provider", i.provider)
            .putOpt("proxy_id", i.proxyId)
            .put("remote", i.remote)

        private fun imageFrom(o: JSONObject): MaImage? {
            val path = o.str("path") ?: return null
            return MaImage(path, o.str("provider").orEmpty(), o.str("proxy_id"), o.optBoolean("remote"))
        }
    }
}

/**
 * Dove sta la copia dell'ultimo ascolto: nell'app `noBackupFilesDir/last-listen.json` (fuori dal backup di Android),
 * nelle prove un file qualsiasi. Scrive tutto o niente (un file a parte, poi lo mette al posto di quello vecchio) e
 * legge con tolleranza: un file rovinato vale come nessuna copia. Fuori dal thread principale.
 */
class LastListenStore(private val file: File) {
    @Volatile private var loggedAt = 0L

    @Synchronized
    fun read(): LastListen? {
        if (!file.exists()) return null
        return try {
            LastListen.from(JSONObject(file.readText()))
        } catch (e: Exception) {
            // Senza il testo dell'errore: org.json ci metterebbe il contenuto (titoli).
            Log.w(TAG, "Ripresa: copia illeggibile (${e.javaClass.simpleName})")
            null
        }
    }

    @Synchronized
    fun write(listen: LastListen) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(listen.toJson().toString())
            if (!temp.renameTo(file)) {
                temp.delete()
                Log.w(TAG, "Ripresa: copia non salvata (rename)")
                return
            }
            // Solo questo nel registro, e non a ogni scrittura (mentre suona se ne fa una ogni 30 s).
            val now = System.currentTimeMillis()
            if (now - loggedAt > LOG_EVERY_MS) {
                loggedAt = now
                Log.i(TAG, "Ripresa: copia salvata")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ripresa: copia non salvata (${Logged.error(e)})")
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object {
        const val TAG = "Xonos"
        const val LOG_EVERY_MS = 10 * 60_000L
    }
}
