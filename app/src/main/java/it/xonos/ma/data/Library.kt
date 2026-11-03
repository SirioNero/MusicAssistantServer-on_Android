package it.xonos.ma.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Le domande che il menu dell'auto fa alla libreria di Music Assistant, e i comandi della coda. */
class Library(private val repo: MaRepository) {

    /** Spotify (Soloist) occupato da un ascolto appeso: lo si sblocca e si riprova (vedi [SoloistUnlock]). */
    private val unlock = SoloistUnlock(repo)

    /**
     * Quanto si aspetta la risposta di play_media: Music Assistant risponde quando la musica parte, dopo aver letto
     * tutto l'elenco. Col server sul telefono le richieste a Spotify rispettano il suo limite (30 ogni 30 s), e il primo
     * play di una playlist lunga o di un artista può volerci di più: lì l'app lo allunga (vedi XonosApp).
     */
    @Volatile var playTimeoutMs = PLAY_TIMEOUT_MS

    /**
     * Un elenco di elementi: diventano MaItem man mano che arrivano, lontano dal thread principale.
     * [onPart] riceve quelli arrivati fin lì a ogni pezzo (gli elenchi lunghi arrivano a pezzi di 500).
     */
    private suspend fun items(
        command: String,
        timeoutMs: Long = 30_000,
        onPart: ((List<MaItem>) -> Unit)? = null,
        fill: JSONObject.() -> Unit = {},
    ): List<MaItem> = repo.items(command, timeoutMs, MaItem::from, fill, onPart)

    /**
     * Un elenco intero, a pagine di [PAGE] come le chiede Music Assistant stesso: dopo la prima
     * ne chiede alcune insieme, finché una non arriva incompleta.
     */
    private suspend fun paged(command: String, fill: JSONObject.() -> Unit): List<MaItem> = coroutineScope {
        val all = items(command) {
            fill()
            put("limit", PAGE)
            put("offset", 0)
        }.toMutableList()
        var offset = PAGE
        var complete = all.size < PAGE
        while (!complete && offset < MAX_ITEMS) {
            val pages = (0 until PAGES_AT_ONCE).map { i ->
                async {
                    items(command) {
                        fill()
                        put("limit", PAGE)
                        put("offset", offset + i * PAGE)
                    }
                }
            }.awaitAll()
            for (page in pages) {
                all += page
                if (page.size < PAGE) {
                    complete = true
                    break
                }
            }
            offset += PAGES_AT_ONCE * PAGE
        }
        all
    }

    /** Gli ultimi ascolti, anche quelli interrotti a metà. */
    suspend fun recent(limit: Int = 40) = items("music/recently_played_items") {
        put("limit", limit)
        put("fully_played_only", false)
    }

    /**
     * Una pagina della libreria, per gli elenchi del telefono che si caricano scorrendo.
     * [orderBy]: "sort_name", "sort_name_desc", "timestamp_added_desc", "last_played_desc", "play_count_desc", "year".
     */
    suspend fun libraryPage(
        kind: String,
        offset: Int,
        limit: Int = PHONE_PAGE,
        orderBy: String = "sort_name",
        favorite: Boolean? = null,
        search: String? = null,
    ) = items("music/$kind/library_items") {
        if (favorite != null) put("favorite", favorite)
        if (!search.isNullOrBlank()) put("search", search)
        if (kind == "genres") {
            put("hide_empty", true)
            put("content_type", "music")
        }
        put("order_by", orderBy)
        put("limit", limit)
        put("offset", offset)
    }

    /** Tutti gli elementi della libreria di un tipo ("albums", "artists", "playlists", "tracks", "radios", ...). */
    suspend fun libraryItems(kind: String, favorite: Boolean? = null, orderBy: String = "sort_name") =
        paged("music/$kind/library_items") {
            if (favorite != null) put("favorite", favorite)
            put("order_by", orderBy)
        }

    /** I generi musicali che hanno qualcosa dentro (Music Assistant 2.10 o successivo: prima i generi non c'erano). */
    suspend fun genres() = paged("music/genres/library_items") {
        put("hide_empty", true)
        put("content_type", "music")
        put("order_by", "sort_name")
    }

    /** Se c'è almeno un genere musicale con qualcosa dentro. */
    suspend fun hasGenres() = items("music/genres/library_items") {
        put("hide_empty", true)
        put("content_type", "music")
        put("limit", 1)
    }.isNotEmpty()

    suspend fun genreTracks(genre: MaItem) = paged("music/genres/tracks") { put("item_id", genre.itemId) }

    suspend fun genreAlbums(genre: MaItem) = paged("music/genres/albums") { put("item_id", genre.itemId) }

    /** Quanti elementi di un tipo ci sono in libreria; -1 se il server non lo sa dire. */
    suspend fun count(kind: String, favoriteOnly: Boolean = false): Int =
        (repo.command("music/$kind/count") { put("favorite_only", favoriteOnly) } as? Number)?.toInt() ?: -1

    suspend fun albumTracks(album: MaItem) = items("music/albums/album_tracks") { identify(album) }

    /**
     * Le playlist lunghe arrivano a pezzi e un servizio come Spotify può metterci un po': [onPart]
     * riceve i brani arrivati fin lì, per mostrarli subito. Music Assistant tiene i brani delle
     * playlist in una cache, che dopo una modifica a volte resta vecchia: [fresh] la salta.
     */
    suspend fun playlistTracks(playlist: MaItem, onPart: ((List<MaItem>) -> Unit)? = null, fresh: Boolean = false) =
        items("music/playlists/playlist_tracks", 90_000, onPart) {
            identify(playlist)
            if (fresh) put("force_refresh", true)
        }

    /** Gli album di un artista: quelli della libreria e tutti quelli dei servizi dove l'artista c'è. */
    suspend fun artistAlbums(artist: MaItem, onPart: ((List<MaItem>) -> Unit)? = null) = everywhere(artist, onPart) { provider, id ->
        items("music/artists/artist_albums", 60_000) { identify(provider, id) }
    }

    /** I brani di un artista: quelli della libreria e quelli che ne conoscono i servizi. */
    suspend fun artistTracks(artist: MaItem, onPart: ((List<MaItem>) -> Unit)? = null) = everywhere(artist, onPart) { provider, id ->
        items("music/artists/artist_tracks", 60_000) { identify(provider, id) }
    }

    /** I brani più ascoltati di un artista, da tutti i servizi; vuoto se il server è più vecchio della 2.10. */
    suspend fun artistTopTracks(artist: MaItem): List<MaItem> = try {
        items("music/artists/top_tracks", 60_000) { identify(artist.provider, artist.itemId) }
    } catch (e: MaException) {
        emptyList()
    }

    suspend fun podcastEpisodes(podcast: MaItem, onPart: ((List<MaItem>) -> Unit)? = null) =
        items("music/podcasts/podcast_episodes", 60_000, onPart) { identify(podcast) }

    /** Le righe di "Per te" (consigli della libreria e dei servizi), senza il loro contenuto. */
    suspend fun recommendations() = items("music/recommendations", 60_000)

    /** Il contenuto di una riga di "Per te". */
    suspend fun recommendationItems(row: MaItem): List<MaItem> {
        if (row.inline.isNotEmpty()) return row.inline
        return items("music/recommendations/items", 60_000) {
            put("provider", row.provider)
            put("item_id", row.itemId)
        }
    }

    /** "Sfoglia": i servizi e le loro cartelle, senza la voce per risalire. */
    suspend fun browse(path: String?) = items("music/browse", 60_000) {
        if (path != null) put("path", path)
    }.filter { it.name != ".." }

    /**
     * Cerca nella libreria e in tutti i servizi collegati, ogni tipo di elemento: i risultati
     * arrivano divisi per tipo ("artists", "tracks", "podcasts"...), i più pertinenti per primi.
     */
    /**
     * Cerca nella libreria e in tutti i servizi. Con [libraryOnly] solo nella libreria: risponde
     * subito, mentre i servizi possono metterci secondi.
     */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT, libraryOnly: Boolean = false): Map<String, List<MaItem>> {
        val result = repo.command("music/search", 45_000) {
            put("search_query", query)
            put("limit", limit)
            if (libraryOnly) {
                // library_only c'è dalla 2.7; dalla 2.10 si dice anche con providers.
                put("library_only", true)
                put("providers", JSONArray().put("library"))
            }
        } as? JSONObject ?: return emptyMap()
        // Lo stesso podcast o brano può arrivare da più servizi: resta il primo, che è il più pertinente.
        return withContext(Dispatchers.Default) {
            traced("Xonos.items") {
                SEARCH_TYPES.associateWith { key -> dedupe(result.optJSONArray(key).objects().mapNotNull(MaItem::from)) }
                    .filterValues { it.isNotEmpty() }
            }
        }
    }

    suspend fun item(uri: String): MaItem? =
        (repo.command("music/item_by_uri") { put("uri", uri) } as? JSONObject)?.let(MaItem::from)

    /**
     * Il testo di un brano: quello che il brano ha già (tag o file .lrc accanto), altrimenti quello
     * dei servizi di testi attivi in Music Assistant (per esempio LRCLIB). Null se non c'è.
     */
    suspend fun lyrics(uri: String): Lyrics? {
        val track = repo.command("music/item_by_uri") { put("uri", uri) } as? JSONObject ?: return null
        if (track.str("media_type") != "track") return null
        // Risponde con [testo semplice, testo LRC].
        val result = repo.command("metadata/get_track_lyrics", 60_000) { put("track", track) } as? JSONArray ?: return null
        val plain = if (result.isNull(0)) null else result.optString(0)
        val lrc = if (result.length() < 2 || result.isNull(1)) null else result.optString(1)
        return Lyrics.of(plain, lrc)
    }

    /**
     * Suona sulla coda [queueId]: uno o più indirizzi, eventualmente partendo da [startItem].
     * [option]: "replace" sostituisce la coda, "next" mette dopo il brano in corso, "add" in fondo.
     * [shuffle] mescola ciò che parte.
     */
    suspend fun playMedia(
        queueId: String,
        media: List<String>,
        startItem: String? = null,
        option: String = "replace",
        shuffle: Boolean = false,
    ) {
        if (shuffle) {
            // Prima della 2.10 play_media non ha "shuffle" (lo ignora): vale il casuale della coda.
            repo.command("player_queues/shuffle") {
                put("queue_id", queueId)
                put("shuffle_enabled", true)
            }
        }
        unlock.retrying(queueId) {
            repo.command("player_queues/play_media", playTimeoutMs) {
                put("queue_id", queueId)
                put("media", if (media.size == 1) media[0] else JSONArray(media))
                put("option", option)
                if (startItem != null) put("start_item", startItem)
                // Dalla 2.10 un album suona in ordine anche con il casuale acceso, se non si chiede altro.
                if (shuffle) put("shuffle", true)
            }
        }
    }

    /** Le playlist della libreria in cui si possono mettere brani: quelle di Music Assistant e dei servizi che lo permettono. */
    suspend fun editablePlaylists(): List<MaItem> = libraryItems("playlists").filter { it.editable && it.provider == "library" }

    /**
     * Mette in fondo alla playlist [playlist] (della libreria) brani, album o playlist: Music
     * Assistant apre album e playlist nei loro brani, salta quelli che ci sono già e, per la
     * playlist di un servizio, cerca lì lo stesso brano. Dalla 2.10 lo fa poco dopo, in disparte.
     */
    suspend fun addToPlaylist(playlist: MaItem, uris: List<String>) {
        repo.command("music/playlists/add_playlist_tracks", 60_000) {
            put("db_playlist_id", playlist.itemId)
            put("uris", JSONArray(uris))
        }
    }

    /** Toglie dalla playlist i brani ai posti [positions] (da 1, vedi [MaItem.position]). */
    suspend fun removeFromPlaylist(playlist: MaItem, positions: List<Int>) {
        repo.command("music/playlists/remove_playlist_tracks", 60_000) {
            put("db_playlist_id", playlist.itemId)
            put("positions_to_remove", JSONArray(positions))
        }
    }

    /** Una playlist nuova di Music Assistant, che accetta brani di tutti i servizi. */
    suspend fun createPlaylist(name: String): MaItem =
        (repo.command("music/playlists/create_playlist") { put("name", name) } as? JSONObject)?.let(MaItem::from)
            ?: throw MaException("Playlist non creata")

    /** Toglie un elemento dalla libreria; una playlist di Music Assistant sparisce del tutto. */
    suspend fun removeFromLibrary(item: MaItem) {
        repo.command("music/library/remove_item") {
            put("media_type", item.mediaType)
            put("library_item_id", item.itemId)
        }
    }

    /**
     * Le ultime righe del registro di Music Assistant (`logging/get`, solo per gli amministratori): il
     * registro intero può essere di qualche MB, a chi aiuta servono le ultime [chars] lettere, da una riga intera.
     */
    suspend fun serverLog(chars: Int = LOG_CHARS): String {
        val log = repo.command("logging/get", 60_000) as? String ?: return ""
        if (log.length <= chars) return log
        val start = log.length - chars
        val line = log.indexOf('\n', start)
        return log.substring(if (line >= 0) line + 1 else start)
    }

    /** Riprende la coda da dove si era fermata (play su una coda ferma la fa ripartire). */
    suspend fun resume(queueId: String) {
        unlock.retrying(queueId) { repo.command("player_queues/play") { put("queue_id", queueId) } }
    }

    /** Aggiunge o toglie dai preferiti; per toglierlo serve l'elemento della libreria. */
    suspend fun setFavorite(uri: String, mediaType: String, provider: String?, itemId: String?, favorite: Boolean) {
        if (favorite) {
            repo.command("music/favorites/add_item") { put("item", uri) }
            return
        }
        val libraryId = if (provider == "library") itemId else item(uri)?.takeIf { it.provider == "library" }?.itemId
        if (libraryId.isNullOrEmpty()) throw MaException("Non è nella libreria")
        repo.command("music/favorites/remove_item") {
            put("media_type", mediaType)
            put("library_item_id", libraryId)
        }
    }

    /**
     * Chiede la stessa cosa all'elemento e a ciascuno dei servizi dove si trova: per un artista
     * della libreria, Music Assistant risponde solo con ciò che è in libreria, i servizi con tutto.
     */
    private suspend fun everywhere(
        item: MaItem,
        onPart: ((List<MaItem>) -> Unit)?,
        fetch: suspend (provider: String, id: String) -> List<MaItem>,
    ): List<MaItem> = coroutineScope {
        val others = item.mappings.filter { (provider, _) -> provider != item.provider }.distinct().map { (provider, id) ->
            // Un servizio che non risponde non deve togliere il resto.
            async {
                try {
                    fetch(provider, id)
                } catch (e: MaException) {
                    emptyList()
                }
            }
        }
        val own = fetch(item.provider, item.itemId)
        if (onPart != null && others.isNotEmpty()) {
            // Prima la libreria, poi ogni servizio appena risponde, sempre nello stesso ordine.
            val done = arrayOfNulls<List<MaItem>>(others.size)
            onPart(dedupe(own))
            others.forEachIndexed { i, other ->
                launch {
                    val part = other.await()
                    synchronized(done) {
                        done[i] = part
                        onPart(dedupe(own + done.filterNotNull().flatten()))
                    }
                }
            }
        }
        dedupe(own + others.awaitAll().flatten())
    }

    /** Lo stesso elemento visto da più servizi una volta sola: vince il primo, di solito quello della libreria. */
    private fun dedupe(items: List<MaItem>): List<MaItem> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val keys = listOf("${item.provider}/${item.itemId}") + item.mappings.map { (provider, id) -> "$provider/$id" } +
                "${item.mediaType}:${item.name.lowercase()}|${item.subtitle?.lowercase()}"
            val fresh = keys.none { it in seen }
            seen += keys
            fresh
        }
    }

    private fun JSONObject.identify(item: MaItem) = identify(item.provider, item.itemId)

    private fun JSONObject.identify(provider: String, itemId: String) {
        put("item_id", itemId)
        put("provider_instance_id_or_domain", provider)
    }

    companion object {
        private const val PAGE = 500
        private const val PAGES_AT_ONCE = 4

        /** Quanto si aspetta play_media di solito (vedi [playTimeoutMs]). */
        const val PLAY_TIMEOUT_MS = 60_000L

        /** Lo stesso col Music Assistant sul telefono (vedi [playTimeoutMs]). */
        const val PHONE_PLAY_TIMEOUT_MS = 180_000L

        /** Le pagine degli elenchi del telefono: abbastanza per riempire lo schermo più volte. */
        const val PHONE_PAGE = 100

        /** Un tetto solo contro gli errori: nessuna libreria vera ci arriva vicino. */
        private const val MAX_ITEMS = 50_000

        /** Risultati per tipo: il valore predefinito di Music Assistant. */
        const val SEARCH_LIMIT = 25

        /** Quanto registro di Music Assistant si condivide: come per il server sul telefono, circa 96 KB. */
        const val LOG_CHARS = 96 * 1024

        val SEARCH_TYPES = listOf("artists", "tracks", "albums", "playlists", "podcasts", "audiobooks", "radio", "genres")
    }
}
