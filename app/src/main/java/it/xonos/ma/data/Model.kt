package it.xonos.ma.data

import android.os.Trace
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** Lo stato del collegamento con Music Assistant. */
sealed interface Connection {
    data object NotConfigured : Connection
    data class Connecting(val url: String) : Connection
    data class Connected(val url: String, val server: ServerInfo) : Connection

    /** [message] va nei log; a chi usa l'app si dice [problem], nella sua lingua. */
    data class Failed(val message: String, val problem: Problem = Problem.NETWORK, val detail: String? = null) : Connection
}

/**
 * Cosa è andato storto con Music Assistant. L'app lo spiega con parole sue, nella lingua scelta
 * sul telefono; il dettaglio (un indirizzo, una versione, un codice HTTP) va dentro la frase.
 */
enum class Problem {
    /** L'indirizzo scritto non è un indirizzo. */
    BAD_ADDRESS,

    /** Nessuna risposta in tempo (dettaglio: l'indirizzo, se si sa). */
    NO_ANSWER,

    /** A quell'indirizzo risponde qualcos'altro. */
    NOT_MUSIC_ASSISTANT,

    /** Music Assistant più vecchio della 2.7 (dettaglio: la versione). */
    TOO_OLD,

    /** Il token non è valido o è scaduto. */
    BAD_TOKEN,

    /** L'accesso è negato. */
    DENIED,

    /** Un altro errore HTTP (dettaglio: il codice). */
    HTTP,

    /** La rete: niente connessione, nome sconosciuto, server spento (dettaglio: cosa dice il sistema). */
    NETWORK,

    /** La connessione c'era ed è caduta. */
    LOST,

    /** Non c'è una connessione aperta. */
    NOT_CONNECTED,

    /** Nome utente o password sbagliati. */
    BAD_LOGIN,

    /** Un Music Assistant appena installato, senza nessun account: va creato il primo. */
    SETUP_REQUIRED,

    /** Music Assistant ha rifiutato la richiesta (dettaglio: il suo messaggio). */
    REFUSED,
}

/** Cosa dice di sé il server appena collegato. */
data class ServerInfo(
    val name: String,
    val version: String,
    val schemaVersion: Int,
    /**
     * L'utente vede solo alcuni lettori: il nostro gli è permesso solo se la connessione audio
     * si apre dopo quella dell'API, quindi va riaperta a ogni riconnessione dell'API.
     */
    val limitedPlayers: Boolean = false,
    /** Chi ha fatto l'accesso (il nome da mostrare) e il suo ruolo: "admin", "user", "guest"... */
    val user: String? = null,
    val role: String? = null,
    /** L'identità del server (server_id): la stessa da casa e da fuori, diversa per ogni Music Assistant. */
    val id: String = "",
) {
    /** Solo gli amministratori gestiscono i servizi di Music Assistant. */
    val admin: Boolean get() = role == "admin"

    companion object {
        fun from(info: JSONObject): ServerInfo? {
            if (info.str("server_id") == null) return null
            return ServerInfo(
                id = info.str("server_id").orEmpty(),
                name = info.str("name") ?: "Music Assistant",
                version = info.str("server_version") ?: "?",
                schemaVersion = info.optInt("schema_version"),
            )
        }
    }
}

/** Un'immagine come la descrive Music Assistant. */
data class MaImage(val path: String, val provider: String, val proxyId: String?, val remote: Boolean) {
    companion object {
        fun from(o: JSONObject?): MaImage? {
            if (o == null) return null
            val path = o.str("path") ?: return null
            return MaImage(path, o.str("provider").orEmpty(), o.str("proxy_id"), o.optBoolean("remotely_accessible"))
        }

        /** L'immagine di un elemento: la sua, quella dei metadati (la copertina, se c'è), o quella dell'album. */
        fun of(item: JSONObject?): MaImage? {
            if (item == null) return null
            from(item.optJSONObject("image"))?.let { return it }
            val images = item.optJSONObject("metadata")?.optJSONArray("images")
            var first: MaImage? = null
            for (o in images.objects()) {
                val image = from(o) ?: continue
                if (o.str("type") == "thumb") return image
                if (first == null) first = image
            }
            return first ?: item.optJSONObject("album")?.let { from(it.optJSONObject("image")) }
        }
    }
}

/** Un elemento di Music Assistant da mostrare nel menu: brano, album, artista, playlist, radio, cartella... */
data class MaItem(
    val uri: String,
    val mediaType: String,
    val itemId: String,
    val provider: String,
    val name: String,
    val subtitle: String?,
    val image: MaImage?,
    val playable: Boolean,
    val favorite: Boolean,
    val durationSec: Int,
    /** Solo le cartelle di "Sfoglia": il percorso da chiedere a music/browse. */
    val path: String?,
    /** Le righe di "Per te" che le versioni vecchie mandano già piene. */
    val inline: List<MaItem> = emptyList(),
    /** Il nome con cui Music Assistant mette in ordine alfabetico (minuscolo, senza articoli). */
    val sortName: String? = null,
    val year: Int = 0,
    /** In quali servizi si trova: istanza del provider e id dell'elemento lì. */
    val mappings: List<Pair<String, String>> = emptyList(),
    /** Gli artisti di un brano o di un album, per aprirli dal telefono. */
    val artists: List<Ref> = emptyList(),
    /** L'album di un brano. */
    val album: Ref? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    /** Episodi e audiolibri: dove si era arrivati e se è stato ascoltato fino in fondo. */
    val resumeMs: Long = 0,
    val fullyPlayed: Boolean = false,
    /** Il posto di un brano nella sua playlist (da 1): serve per toglierlo. */
    val position: Int = 0,
    /** Una playlist in cui si possono aggiungere e togliere brani. */
    val editable: Boolean = false,
    /**
     * Falso se il servizio dell'elemento non è caricato adesso (lo dicono gli ascolti recenti di Music Assistant, per
     * esempio dopo che Spotify si è scaricato): si mostra, ma suonarlo non va. Vero quando Music Assistant non lo dice.
     */
    val available: Boolean = true,
) {
    /** Cosa si apre come elenco; il resto si suona e basta. */
    val browsable: Boolean get() = mediaType in BROWSABLE

    /**
     * Il dominio del servizio ("spotify", "recommendations"...): [provider] è l'istanza ("spotify--Ab12"). Le righe di
     * "Per te" si riconoscono da qui e da [itemId]: Music Assistant su /ws non manda la loro translation_key.
     */
    val providerDomain: String get() = QueueItem.domainOf(provider)

    companion object {
        private val BROWSABLE = setOf("album", "playlist", "artist", "podcast", "genre", "folder")

        fun from(o: JSONObject): MaItem? {
            val uri = o.str("uri") ?: return null
            val type = o.str("media_type") ?: return null
            val name = o.str("name") ?: return null
            val items = o.optJSONArray("items")
            return MaItem(
                uri = uri,
                mediaType = type,
                itemId = o.str("item_id").orEmpty(),
                provider = o.str("provider").orEmpty(),
                name = name,
                subtitle = subtitleOf(o, type),
                image = MaImage.of(o),
                playable = if (o.has("is_playable")) o.optBoolean("is_playable") else type != "folder",
                favorite = o.optBoolean("favorite"),
                durationSec = o.optInt("duration"),
                path = o.str("path"),
                inline = items.objects().mapNotNull(::from),
                sortName = o.str("sort_name"),
                year = o.optInt("year"),
                mappings = o.optJSONArray("provider_mappings").objects().mapNotNull { m ->
                    val instance = m.str("provider_instance") ?: return@mapNotNull null
                    val id = m.str("item_id") ?: return@mapNotNull null
                    instance to id
                },
                artists = o.optJSONArray("artists").objects().mapNotNull(Ref::from),
                album = Ref.from(o.optJSONObject("album")),
                trackNumber = o.optInt("track_number"),
                discNumber = o.optInt("disc_number"),
                resumeMs = o.optLong("resume_position_ms"),
                fullyPlayed = o.optBoolean("fully_played"),
                position = o.optInt("position"),
                editable = o.optBoolean("is_editable"),
                available = !o.has("available") || o.isNull("available") || o.optBoolean("available", true),
            )
        }

        private fun subtitleOf(o: JSONObject, type: String): String? = when (type) {
            "track" -> listOfNotNull(artistsOf(o), o.optJSONObject("album")?.str("name")).joinToString(" · ")
            "album" -> listOfNotNull(artistsOf(o), o.optInt("year").takeIf { it > 0 }?.toString()).joinToString(" · ")
            "playlist" -> o.str("owner")
            "podcast" -> o.str("publisher")
            "podcast_episode" -> o.optJSONObject("podcast")?.str("name")
            "audiobook" -> o.optJSONArray("authors")?.let { authors ->
                (0 until authors.length()).mapNotNull { authors.optString(it).takeIf(String::isNotEmpty) }.joinToString(", ")
            }
            "folder" -> o.str("subtitle")
            else -> null
        }?.ifEmpty { null }
    }
}

/** Un rimando a un altro elemento (l'album o l'artista di un brano): basta per aprirlo. */
data class Ref(val uri: String, val name: String) {
    companion object {
        fun from(o: JSONObject?): Ref? {
            if (o == null) return null
            return Ref(o.str("uri") ?: return null, o.str("name") ?: return null)
        }
    }
}

/**
 * Un artista di un brano, con l'id di MusicBrainz quando Music Assistant lo conosce (serve ai consigli: novità e
 * artisti simili si chiedono per mbid).
 */
data class ArtistRef(val uri: String, val name: String, val mbid: String? = null) {
    companion object {
        fun from(o: JSONObject?): ArtistRef? {
            if (o == null) return null
            val uri = o.str("uri") ?: return null
            return ArtistRef(uri, o.str("name") ?: return null, mbidOf(o, "musicbrainz_artistid"))
        }
    }
}

/**
 * L'id di MusicBrainz di un elemento ([kind]: "musicbrainz_artistid", "musicbrainz_recordingid"...). Music Assistant
 * manda `external_ids` come elenco di coppie [tipo, valore].
 */
internal fun mbidOf(o: JSONObject, kind: String): String? {
    val ids = o.optJSONArray("external_ids") ?: return null
    for (i in 0 until ids.length()) {
        val pair = ids.optJSONArray(i) ?: continue
        if (pair.optString(0) == kind) return pair.optString(1).takeIf { it.isNotEmpty() && it != "null" }
    }
    return null
}

/**
 * Da dove suona una coda (la prima delle sue `sources`): l'album, la playlist, l'artista, il genere, il podcast o la
 * radio scelti. Per "In riproduzione da" e per ricostruire la coda se Music Assistant l'ha persa.
 */
data class QueueSource(val uri: String, val name: String, val mediaType: String, val image: MaImage?) {
    companion object {
        fun from(o: JSONObject?): QueueSource? {
            if (o == null) return null
            val uri = o.str("uri") ?: return null
            val type = o.str("media_type") ?: uri.substringAfter("://").substringBefore('/').ifEmpty { return null }
            return QueueSource(uri, o.str("name").orEmpty(), type, MaImage.from(o.optJSONObject("image")))
        }
    }
}

/** Un elemento della coda di Music Assistant. */
data class QueueItem(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int,
    val image: MaImage?,
    val uri: String?,
    val mediaType: String?,
    val favorite: Boolean,
    val itemId: String?,
    val provider: String?,
    /** Per aprire dal telefono l'album e l'artista del brano. */
    val albumRef: Ref? = null,
    val artistRef: Ref? = null,
    /**
     * I servizi da cui può arrivare l'audio (domini, come "spotify" o "filesystem_local"): quello che
     * Music Assistant ha scelto, se il brano ha già suonato, altrimenti tutti quelli che lo hanno.
     */
    val sources: Set<String> = emptySet(),
    /** Tutti gli artisti del brano, con l'mbid se c'è (per il diario degli ascolti e la copia dell'ultimo ascolto). */
    val artists: List<ArtistRef> = emptyList(),
    /**
     * L'uri del brano in un servizio ("spotify--Ab12://track/x"), anche quando [uri] è quello della libreria
     * ("library://track/12"): il primo servizio disponibile che lo ha. Null se non si sa.
     */
    val serviceUri: String? = null,
) {
    /** L'uri della libreria di Music Assistant, se il brano è in libreria. */
    val libraryUri: String? get() = uri?.takeIf { it.startsWith("library://") }

    /**
     * Vero se l'audio può arrivare da un servizio che dà un flusso alla volta: lì i salti fatti in
     * fretta vanno raccolti in uno. Senza notizie si fa come se lo fosse.
     */
    val singleStream: Boolean get() = sources.isEmpty() || sources.any { it in SINGLE_STREAM_SOURCES }

    companion object {
        /**
         * I servizi con un solo flusso alla volta in Music Assistant (max_concurrent_streams = 1):
         * Spotify con il motore Soloist (dal telefono non si distingue da librespot, che ne ha 3),
         * Tidal, Apple Music, Pandora.
         */
        val SINGLE_STREAM_SOURCES = setOf("spotify", "tidal", "apple_music", "pandora")

        /** Il dominio di un servizio dal suo instance_id ("spotify--Ab12" → "spotify"). */
        fun domainOf(instanceId: String): String = instanceId.substringBefore("--")

        fun from(o: JSONObject?): QueueItem? {
            if (o == null) return null
            val id = o.str("queue_item_id") ?: return null
            val media = o.optJSONObject("media_item")
            return QueueItem(
                id = id,
                title = media?.str("name") ?: o.str("name").orEmpty(),
                artist = media?.let { artistsOf(it) ?: it.optJSONObject("podcast")?.str("name") },
                album = media?.optJSONObject("album")?.str("name"),
                durationSec = o.optInt("duration").takeIf { it > 0 } ?: media?.optInt("duration") ?: 0,
                image = MaImage.from(o.optJSONObject("image")) ?: MaImage.of(media),
                uri = media?.str("uri"),
                mediaType = media?.str("media_type"),
                favorite = media?.optBoolean("favorite") ?: false,
                itemId = media?.str("item_id"),
                provider = media?.str("provider"),
                albumRef = Ref.from(media?.optJSONObject("album")),
                artistRef = media?.optJSONArray("artists").objects().firstNotNullOfOrNull(Ref::from)
                    ?: Ref.from(media?.optJSONObject("podcast")),
                sources = sourcesOf(o, media),
                artists = media?.optJSONArray("artists").objects().mapNotNull(ArtistRef::from),
                serviceUri = serviceUriOf(media),
            )
        }

        /** L'uri nel servizio: quello dell'elemento se non è della libreria, sennò dal primo servizio disponibile. */
        private fun serviceUriOf(media: JSONObject?): String? {
            if (media == null) return null
            val uri = media.str("uri")
            if (uri != null && !uri.startsWith("library://")) return uri
            val type = media.str("media_type") ?: return null
            val mappings = media.optJSONArray("provider_mappings").objects()
            val mapping = mappings.firstOrNull { it.optBoolean("available", true) } ?: mappings.firstOrNull() ?: return null
            val instance = mapping.str("provider_instance") ?: return null
            val id = mapping.str("item_id") ?: return null
            return "$instance://$type/$id"
        }

        private fun sourcesOf(o: JSONObject, media: JSONObject?): Set<String> {
            o.optJSONObject("streamdetails")?.str("provider")?.let { return setOf(domainOf(it)) }
            val mapped = media?.optJSONArray("provider_mappings").objects().mapNotNull { m ->
                m.str("provider_domain") ?: m.str("provider_instance")?.let(::domainOf)
            }
            if (mapped.isNotEmpty()) return mapped.toSet()
            return setOfNotNull(media?.str("provider")?.takeIf { it != "library" }?.let(::domainOf))
        }
    }
}

/** Lo stato della coda del nostro lettore in Music Assistant. */
data class QueueState(
    /** "playing", "paused" o "idle": per questo lettore la pausa è un "idle" con la posizione ricordata. */
    val state: String,
    val items: Int,
    val currentIndex: Int?,
    val elapsedSec: Double,
    val shuffle: Boolean,
    /** "off", "one" o "all". */
    val repeat: String,
    val current: QueueItem?,
    /** Per le radio: il titolo che la stazione sta trasmettendo. */
    val streamTitle: String?,
    /** Il brano che il server sta già mandando al lettore: fin lì la coda non si può cambiare. */
    val indexInBuffer: Int? = null,
    /** L'id della coda (per il telefono è l'id del lettore). */
    val queueId: String = "",
    /**
     * Dove riprende la coda dopo una pausa, in secondi (`resume_pos`): Music Assistant lo segna alla pausa e lo azzera a
     * ogni play_index. 0 se non c'è.
     */
    val resumePosSec: Int = 0,
    /** Da dove suona: album, playlist, artista... La prima è quella scelta (vedi [source]). */
    val sources: List<QueueSource> = emptyList(),
    /** La coda è arrivata in fondo: play la fa ripartire dal primo brano (Music Assistant 2.10.8 o più nuovo). */
    val ended: Boolean = false,
    /** Quando Music Assistant ha segnato [elapsedSec], in secondi dal 1970 (`elapsed_time_last_updated`); 0 se non si sa. */
    val elapsedUpdatedAt: Double = 0.0,
    /**
     * La coda è dinamica (`is_dynamic`): si riempie da sé da una sorgente come un'Infinite Mix. Allora casuale e ripeti
     * non si cambiano, e i brani non vengono da una sorgente con un ordine. Falso prima della 2.10.8.
     */
    val dynamic: Boolean = false,
) {
    val playing: Boolean get() = state == "playing"

    /** Il primo brano che si può togliere o spostare: quelli già in corso o nel buffer no. */
    val firstEditable: Int get() = max(currentIndex ?: -1, indexInBuffer ?: -1) + 1

    /** La sorgente scelta, quella di "In riproduzione da". */
    val source: QueueSource? get() = sources.firstOrNull()

    /** Il punto dove riprenderebbe Music Assistant: `resume_pos` se c'è, sennò il tempo trascorso (la sua regola). */
    val resumeSec: Double get() = if (resumePosSec > 0) resumePosSec.toDouble() else elapsedSec

    /** Il punto nel brano adesso ([nowSec] in secondi dal 1970): mentre suona va avanti dal momento in cui è stato segnato. */
    fun elapsedAt(nowSec: Double): Double =
        if (playing && elapsedUpdatedAt > 0) elapsedSec + max(0.0, nowSec - elapsedUpdatedAt) else elapsedSec

    companion object {
        fun from(o: JSONObject?): QueueState? {
            val queueId = o?.str("queue_id") ?: return null
            val current = o.optJSONObject("current_item")
            return QueueState(
                state = o.str("state") ?: "idle",
                items = o.optInt("items"),
                currentIndex = if (o.isNull("current_index")) null else o.optInt("current_index"),
                elapsedSec = o.optDouble("elapsed_time", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                shuffle = o.optBoolean("shuffle_enabled"),
                repeat = o.str("repeat_mode") ?: "off",
                current = QueueItem.from(current),
                streamTitle = current?.optJSONObject("streamdetails")?.str("stream_title"),
                indexInBuffer = if (o.isNull("index_in_buffer")) null else o.optInt("index_in_buffer"),
                queueId = queueId,
                resumePosSec = o.optDouble("resume_pos", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
                // Prima della 2.10 la sorgente si chiamava radio_source (ora Music Assistant la manda sempre vuota).
                sources = (o.optJSONArray("sources")?.takeIf { it.length() > 0 } ?: o.optJSONArray("radio_source"))
                    .objects().mapNotNull(QueueSource::from),
                ended = o.optBoolean("ended"),
                elapsedUpdatedAt = o.optDouble("elapsed_time_last_updated", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                dynamic = o.optBoolean("is_dynamic"),
            )
        }
    }
}

/** Gli artisti di un brano o di un album, separati da virgole. */
internal fun artistsOf(o: JSONObject): String? {
    val names = o.optJSONArray("artists").objects().mapNotNull { it.str("name") }
    return names.joinToString(", ").ifEmpty { null }
}

/** Come optString, ma restituisce null per valori assenti, null o vuoti (org.json restituirebbe "null"). */
fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Gli oggetti di un array JSON, saltando ciò che non è un oggetto; vuoto se l'array manca. */
fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * Un lavoro con il suo nome nelle tracce di sistema (Perfetto), con "@main" se gira sul thread
 * principale: così le misure dicono quanto costa e dove.
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(if (Thread.currentThread().name == "main") "$name@main" else name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
