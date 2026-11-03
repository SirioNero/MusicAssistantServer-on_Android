package it.xonos.ma.playback

import android.util.Log
import android.util.LruCache
import it.xonos.ma.data.Library
import it.xonos.ma.data.Logged
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import it.xonos.ma.data.MaException
import it.xonos.ma.data.Problem
import kotlinx.coroutines.CancellationException

/** Una voce del menu dell'auto, prima che [BrowseTree] la trasformi in un MediaItem. */
data class MenuNode(
    val id: String,
    val title: String,
    val subtitle: String?,
    val image: MaImage?,
    /** L'icona disegnata da Xonos quando non c'è un'immagine (vedi [Artwork.ICONS]). */
    val icon: String,
    /** Il tipo di Music Assistant ("track", "album", "podcast"...) oppure "folder". */
    val type: String,
    val browsable: Boolean,
    val playable: Boolean,
)

/** Le scritte del menu, nella lingua scelta sul telefono. */
interface MenuLabels {
    /** Una voce del menu principale: "recent", "foryou", "playlists", "albums", "tracks", "favorites", ... */
    fun category(id: String): String

    /** Un tipo di elemento, con le chiavi della ricerca di Music Assistant: "tracks", "albums", "podcasts", "radio", ... */
    fun kind(type: String): String

    /** Quanti risultati di un tipo ha trovato una ricerca. */
    fun results(count: Int): String

    /** Quanti elementi di un tipo ("tracks", "albums", "artists", ...) ci sono. */
    fun count(type: String, count: Int): String

    val topTracks: String
    val allTracks: String
}

/**
 * Il menu musicale che Gearslip mostra: le voci della libreria di Music Assistant, la ricerca,
 * e cosa succede quando se ne sceglie una. Non usa Media3, così si prova anche fuori dal telefono.
 *
 * Gli identificativi dicono già cosa fare:
 * - `root`, `cat|<voce>`, `abc|<iniziale>|<cartella>`, `rec|<provider>|<id>`, `dir|<percorso>` sono cartelle;
 * - `find|<tipo>|<ricerca>` sono i risultati di un tipo (brani, album, podcast...) di una ricerca;
 * - `open|<uri>` è un album, una playlist, un artista, un podcast o un genere: si apre, o si suona tutto;
 * - `top|<artista>` e `tracks|<artista o genere>` sono i suoi brani: si aprono, o si suonano tutti;
 * - `play|<uri>` si suona da solo;
 * - `in|<album o playlist>|<brano>` suona l'album o la playlist a partire da quel brano;
 * - `list|<cartella>|<n>` suona i brani di una cartella a partire dall'n-esimo;
 * - `part|<k>|<cartella>` è il k-esimo pezzo (da [PART] voci) di un elenco troppo lungo per l'auto.
 */
class Menu(
    private val library: Library,
    private val labels: MenuLabels,
    private val queueId: () -> String,
) {

    private enum class Category(
        val id: String,
        val icon: String,
        val kind: String?,
        val favoriteOnly: Boolean = false,
        /** Il tipo che contiene, per dividerla per iniziale quando è lunga; null se non è in ordine alfabetico. */
        val counted: String? = null,
    ) {
        RECENT("recent", "recent", null),
        FOR_YOU("foryou", "foryou", null),
        PLAYLISTS("playlists", "playlist", "playlists", counted = "playlists"),
        ALBUMS("albums", "album", "albums", counted = "albums"),
        ARTISTS("artists", "artist", "artists", counted = "artists"),
        TRACKS("tracks", "track", "tracks", counted = "tracks"),
        FAVORITES("favorites", "favorite", "tracks", favoriteOnly = true),
        GENRES("genres", "genre", "genres", counted = "genres"),
        RADIOS("radios", "radio", "radios", counted = "radio"),
        PODCASTS("podcasts", "podcast", "podcasts", counted = "podcasts"),
        AUDIOBOOKS("audiobooks", "audiobook", "audiobooks", counted = "audiobooks"),
        BROWSE("browse", "browse", null),
    }

    /** Gli elenchi già chiesti, per aprire le iniziali e suonare "da qui in poi" senza richiederli. */
    private val lists = LruCache<String, List<MaItem>>(48)

    /** Gli elementi già visti, per uri: un artista porta con sé i servizi dove cercarne gli album. */
    private val items = LruCache<String, MaItem>(2_000)

    /** Le voci già mostrate: servono a Media3 per un elemento singolo e all'auto per vedere subito titolo e copertina. */
    private val known = LruCache<String, MenuNode>(2_000)

    /** Le righe di "Per te", con il loro contenuto se il server l'ha già mandato. */
    private val rows = LruCache<String, MaItem>(64)

    /** Le ultime ricerche, divise per tipo: le loro cartelle si aprono senza cercare di nuovo. */
    private val searches = LruCache<String, Map<String, List<MaItem>>>(16)

    fun root(): MenuNode = folder(ROOT, "Xonos", null, "track")

    fun node(id: String): MenuNode? = if (id == ROOT) root() else known.get(id)

    suspend fun children(parentId: String): List<MenuNode> = when {
        parentId == ROOT -> rootChildren()
        parentId.startsWith("cat|") -> category(parentId)
        parentId.startsWith("abc|") -> initial(parentId)
        parentId.startsWith("rec|") -> entries(parentId, recommendation(parentId))
        parentId.startsWith("dir|") -> entries(parentId, library.browse(parentId.removePrefix("dir|")))
        parentId.startsWith("find|") -> entries(parentId, found(parentId))
        parentId.startsWith("open|") -> open(parentId)
        parentId.startsWith("top|") || parentId.startsWith("tracks|") -> entries(parentId, tracksOf(parentId))
        parentId.startsWith("part|") -> part(parentId)
        else -> emptyList()
    }

    // ---------- Ricerca ----------

    /**
     * Cerca dappertutto. In cima i risultati migliori, al massimo uno per tipo; poi una cartella per
     * ogni tipo con tutti i suoi risultati, così niente resta in fondo a un elenco lungo.
     * "podcast ...", "radio ...", "album ..." e simili mettono davanti quel tipo.
     */
    suspend fun search(query: String): List<MenuNode> {
        val (wanted, text) = typeHint(query)
        // Sempre di nuovo: un servizio lento (oltre 8 s) poteva mancare la prima volta, e la stessa ricerca ridava per
        // tutto il viaggio quei risultati. Le cartelle dei risultati invece usano quelli appena mostrati.
        val found = fresh(text)
        val best = bestMatches(text, found, wanted)
        val shown = best.map { it.second.uri }.toSet()
        val nodes = best.map { (type, item) -> entry(item, "find|$type|$text", -1) }
        val folders = Library.SEARCH_TYPES.mapNotNull { type ->
            val list = found[type] ?: return@mapNotNull null
            // Una cartella che ripeterebbe solo ciò che è già in cima non serve.
            if (list.all { it.uri in shown }) return@mapNotNull null
            folder("find|$type|$text", labels.kind(type), labels.results(list.size), TYPE_ICONS.getValue(type), playable = type == "tracks")
        }
        return (nodes + folders).onEach { known.put(it.id, it) }
    }

    private suspend fun lookup(text: String): Map<String, List<MaItem>> = searches.get(text) ?: fresh(text)

    private suspend fun fresh(text: String): Map<String, List<MaItem>> =
        library.search(text).also { found ->
            searches.put(text, found)
            found.values.flatten().forEach { items.put(it.uri, it) }
        }

    private suspend fun found(id: String): List<MaItem> {
        val rest = id.removePrefix("find|")
        return lookup(rest.substringAfter('|'))[rest.substringBefore('|')].orEmpty()
    }

    // ---------- Riproduzione ----------

    /** Suona ciò che l'auto ha scelto: un elemento del menu, una ricerca a voce o la ripresa. */
    suspend fun play(id: String, query: String?) {
        val queue = queueId()
        when {
            id == RESUME -> library.resume(queue)
            !query.isNullOrBlank() && id.isEmpty() -> playSearch(queue, query)
            id.startsWith("play|") -> library.playMedia(queue, listOf(id.removePrefix("play|")))
            id.startsWith("open|") -> library.playMedia(queue, listOf(id.removePrefix("open|")))
            id.startsWith("in|") -> {
                val (context, track) = id.removePrefix("in|").split('|', limit = 2).let { it[0] to it.getOrNull(1) }
                library.playMedia(queue, listOf(context), startItem = track)
            }
            id.startsWith("list|") -> {
                val rest = id.removePrefix("list|")
                playList(queue, rest.substringBeforeLast('|'), rest.substringAfterLast('|').toIntOrNull() ?: 0)
            }
            // Le cartelle di brani suonano tutto, dal primo.
            TRACK_FOLDERS.any(id::startsWith) -> playList(queue, id, 0)
            else -> Log.w(TAG, "Non so suonare ${Logged.id(id)}")
        }
    }

    private suspend fun playList(queue: String, parent: String, start: Int) {
        val items = lists.get(parent) ?: run {
            children(parent)
            lists.get(parent)
        } ?: return
        val uris = items.drop(start).filter { it.mediaType in TRACK_LIKE }.take(MAX_QUEUE).map { it.uri }
        if (uris.isNotEmpty()) library.playMedia(queue, uris)
    }

    /** "Metti ..." detto all'auto: si suona il risultato migliore. */
    private suspend fun playSearch(queue: String, query: String) {
        val (wanted, text) = typeHint(query)
        val best = bestMatches(text, fresh(text), wanted).map { it.second }.firstOrNull { it.playable } ?: return
        library.playMedia(queue, listOf(best.uri))
    }

    // ---------- Cartelle ----------

    private suspend fun rootChildren(): List<MenuNode> = coroutineScope {
        // Le voci vuote non servono in auto: si chiede quanti elementi ci sono, tutti insieme.
        val counts = Category.entries.associateWith { category ->
            category.kind?.let { kind -> async { count(category, kind) } }
        }
        Category.entries.mapNotNull { category ->
            val count = counts[category]?.await() ?: -1
            if (count == 0) null else folder("cat|${category.id}", labels.category(category.id), null, category.icon)
        }.onEach { known.put(it.id, it) }
    }

    private suspend fun count(category: Category, kind: String): Int = when (category) {
        // Music Assistant conta anche i generi senza musica, e prima della 2.10 non li ha (rifiuta il comando). Senza
        // collegamento non si sa: la cartella resta, come le altre, invece di sparire dalla radice che l'auto tiene.
        Category.GENRES -> try {
            if (library.hasGenres()) 1 else 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: MaException) {
            if (e.problem == Problem.REFUSED) 0 else -1
        } catch (e: Exception) {
            -1
        }
        else -> runCatching { library.count(kind, category.favoriteOnly) }.getOrDefault(-1)
    }

    private suspend fun category(id: String): List<MenuNode> {
        val category = Category.entries.firstOrNull { "cat|${it.id}" == id } ?: return emptyList()
        val items = when (category) {
            Category.RECENT -> library.recent()
            Category.FOR_YOU -> return library.recommendations().filter { it.mediaType == "folder" }.map { row ->
                rows.put("rec|${row.provider}|${row.itemId}", row)
                folder("rec|${row.provider}|${row.itemId}", row.name, row.subtitle, "foryou")
            }.onEach { known.put(it.id, it) }
            Category.FAVORITES -> library.libraryItems("tracks", favorite = true, orderBy = "timestamp_added_desc")
            Category.GENRES -> library.genres()
            Category.BROWSE -> library.browse(null)
            else -> library.libraryItems(category.kind ?: return emptyList())
        }
        val counted = category.counted
        return if (counted != null && items.size > INITIALS_FROM) initials(id, items, counted, category.icon) else entries(id, items)
    }

    /** Un elenco troppo lungo da scorrere in auto: una cartella per iniziale, nell'ordine dell'elenco. */
    private fun initials(parentId: String, items: List<MaItem>, counted: String, icon: String): List<MenuNode> {
        val groups = items.groupBy(::initialOf)
        // Se cominciano tutti con la stessa lettera, dividerli non aiuta.
        if (groups.size < 2) return entries(parentId, items)
        lists.put(parentId, items)
        return groups.map { (initial, group) ->
            folder("abc|$initial|$parentId", initial, labels.count(counted, group.size), icon)
        }.onEach { known.put(it.id, it) }
    }

    private suspend fun initial(id: String): List<MenuNode> {
        val rest = id.removePrefix("abc|")
        val initial = rest.substringBefore('|')
        val parent = rest.substringAfter('|')
        val items = lists.get(parent) ?: run {
            children(parent)
            lists.get(parent)
        } ?: return emptyList()
        return entries(id, items.filter { initialOf(it) == initial })
    }

    private suspend fun recommendation(id: String): List<MaItem> {
        val row = rows.get(id) ?: run {
            val (provider, itemId) = id.removePrefix("rec|").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            MaItem("", "folder", itemId, provider, "", null, null, false, false, 0, null)
        }
        return library.recommendationItems(row)
    }

    private suspend fun open(id: String): List<MenuNode> {
        val item = itemFor(id.removePrefix("open|")) ?: return emptyList()
        return when (item.mediaType) {
            // Dentro un album o una playlist un brano fa partire tutto da lì: avanti e indietro restano nell'elenco.
            "album" -> entries(id, library.albumTracks(item), contextUri = item.uri)
            "playlist" -> entries(id, library.playlistTracks(item), contextUri = item.uri)
            "artist" -> artist(id, item)
            "genre" -> genre(id, item)
            "podcast" -> entries(id, library.podcastEpisodes(item))
            else -> emptyList()
        }
    }

    /**
     * Un artista: i brani più ascoltati, tutti gli album (dal più recente) e tutti i brani. Tutti i brani si chiedono
     * solo quando si apre (o si suona) la loro cartella, come per un genere ([tracksOf]): per un artista di Spotify sono
     * circa tre richieste per album, Spotify blocca chi ne fa troppe, e l'auto aspettava l'artista per minuti.
     */
    private suspend fun artist(id: String, artist: MaItem): List<MenuNode> = coroutineScope {
        val top = async { library.artistTopTracks(artist) }
        val albums = async { library.artistAlbums(artist) }
        val topTracks = top.await()
        lists.put("top|${artist.uri}", topTracks)
        buildList {
            if (topTracks.isNotEmpty()) {
                add(folder("top|${artist.uri}", labels.topTracks, labels.count("tracks", topTracks.size), "track", artist.image, playable = true))
            }
            addAll(entries(id, albums.await().sortedByDescending { it.year }))
            add(folder("tracks|${artist.uri}", labels.allTracks, null, "track", playable = true))
        }.onEach { known.put(it.id, it) }
    }

    /** Un genere: tutti i suoi brani, poi i suoi album. */
    private suspend fun genre(id: String, genre: MaItem): List<MenuNode> {
        val tracks = folder("tracks|${genre.uri}", labels.allTracks, null, "track", genre.image, playable = true)
        known.put(tracks.id, tracks)
        return listOf(tracks) + entries(id, library.genreAlbums(genre))
    }

    private suspend fun tracksOf(id: String): List<MaItem> {
        lists.get(id)?.let { return it }
        val item = itemFor(id.substringAfter('|')) ?: return emptyList()
        return when {
            id.startsWith("top|") -> library.artistTopTracks(item)
            item.mediaType == "genre" -> library.genreTracks(item)
            else -> library.artistTracks(item)
        }
    }

    /** L'elemento dietro un uri: quello già visto, che sa dove si trova, o quello chiesto al server. */
    private suspend fun itemFor(uri: String): MaItem? =
        items.get(uri) ?: library.item(uri)?.also { items.put(uri, it) }

    private fun entries(parentId: String, list: List<MaItem>, contextUri: String? = null): List<MenuNode> {
        lists.put(parentId, list)
        list.forEach { items.put(it.uri, it) }
        // Media3 taglia l'elenco che manda all'auto a 256 KB (truncateListBySize, per i browser come Gearslip): oltre
        // qualche centinaio di voci il resto spariva senza dirlo. Un elenco lungo diventa cartelle da [PART] voci.
        if (list.size > PART) {
            return list.indices.step(PART).mapIndexed { k, start ->
                val end = minOf(start + PART, list.size)
                folder("part|$k|$parentId", "${start + 1}–$end", list[start].name, "browse")
            }.onEach { known.put(it.id, it) }
        }
        return entryNodes(parentId, list, 0, contextUri)
    }

    /** Il pezzo di un elenco lungo: le voci hanno gli stessi identificativi che avrebbero nell'elenco intero. */
    private suspend fun part(id: String): List<MenuNode> {
        val rest = id.removePrefix("part|")
        val k = rest.substringBefore('|').toIntOrNull() ?: return emptyList()
        val parent = rest.substringAfter('|')
        val list = lists.get(parent) ?: run {
            children(parent)
            lists.get(parent)
        } ?: return emptyList()
        val start = k * PART
        if (start >= list.size) return emptyList()
        // Dentro un album o una playlist un brano suona tutto da lì, come nell'elenco intero.
        val context = parent.removePrefix("open|").takeIf { parent.startsWith("open|") }?.takeIf { uri ->
            items.get(uri)?.mediaType.let { it == "album" || it == "playlist" }
        }
        return entryNodes(parent, list.subList(start, minOf(start + PART, list.size)), start, context)
    }

    private fun entryNodes(parentId: String, list: List<MaItem>, first: Int, contextUri: String?): List<MenuNode> {
        return list.mapIndexedNotNull { offset, item ->
            val index = first + offset
            when {
                item.mediaType == "folder" -> {
                    val path = item.path ?: return@mapIndexedNotNull null
                    folder("dir|$path", item.name, item.subtitle, "browse", item.image)
                }
                !item.browsable && !item.playable -> null
                else -> entry(item, parentId, index, contextUri)
            }
        }.onEach { known.put(it.id, it) }
    }

    /** La voce di un elemento: si apre se è un contenitore, altrimenti si suona, se serve insieme a ciò che ha intorno. */
    private fun entry(item: MaItem, parentId: String, index: Int, contextUri: String? = null): MenuNode {
        val id = when {
            item.browsable -> "open|${item.uri}"
            item.mediaType == "track" && contextUri != null -> "in|$contextUri|${item.uri}"
            item.mediaType == "track" && index >= 0 && LIST_PARENTS.any(parentId::startsWith) -> "list|$parentId|$index"
            else -> "play|${item.uri}"
        }
        return MenuNode(id, item.name, item.subtitle, item.image, iconFor(item.mediaType), item.mediaType, item.browsable, item.playable)
    }

    private fun folder(id: String, title: String, subtitle: String?, icon: String, image: MaImage? = null, playable: Boolean = false) =
        MenuNode(id, title, subtitle, image, icon, "folder", browsable = true, playable = playable)

    companion object {
        private const val TAG = "Xonos"
        const val ROOT = "root"

        /** La ripresa della coda quando l'auto preme play e il telefono non ha ancora niente in mano. */
        const val RESUME = "resume"

        private const val MAX_QUEUE = 100

        /** Oltre questi elementi una voce della libreria si divide per iniziale. */
        private const val INITIALS_FROM = 150

        /**
         * Le voci di un pezzo di elenco ([entries]): con copertina, titolo e sottotitolo una voce pesa meno di 1 KB, e
         * 150 stanno sotto i 256 KB che Media3 lascia passare verso l'auto.
         */
        const val PART = 150

        /** Tra quanti primi risultati di un tipo si sceglie il migliore. */
        private const val PICK_FROM = 5

        /** Quanti risultati del tipo chiesto ("podcast ...") vanno in cima. */
        private const val WANTED_FIRST = 3

        private val TRACK_LIKE = setOf("track", "podcast_episode", "audiobook")

        /** Le cartelle dove un brano suona insieme a quelli che lo seguono; tra i risultati di una ricerca suona da solo. */
        private val LIST_PARENTS = listOf("cat|", "abc|", "open|", "top|", "tracks|", "rec|")

        /** Le cartelle di brani, che si possono anche suonare intere. */
        private val TRACK_FOLDERS = listOf("find|tracks|", "top|", "tracks|")

        private val TYPE_ICONS = mapOf(
            "artists" to "artist",
            "tracks" to "track",
            "albums" to "album",
            "playlists" to "playlist",
            "podcasts" to "podcast",
            "audiobooks" to "audiobook",
            "radio" to "radio",
            "genres" to "genre",
        )

        /**
         * Le parole con cui si chiede un tipo, all'inizio o in fondo alla ricerca, nelle lingue di Xonos
         * e già senza accenti come le lascia [fold]. Niente "canzone" o "song": sono in troppi titoli.
         */
        private val TYPE_WORDS = mapOf(
            "artista" to "artists", "artist" to "artists", "artiste" to "artists", "kunstler" to "artists",
            "καλλιτεχνης" to "artists", "исполнитель" to "artists", "артист" to "artists", "艺人" to "artists", "歌手" to "artists",
            "album" to "albums", "αλμπουμ" to "albums", "альбом" to "albums", "专辑" to "albums",
            "playlist" to "playlists", "плеилист" to "playlists", "播放列表" to "playlists",
            "podcast" to "podcasts", "подкаст" to "podcasts", "播客" to "podcasts",
            "audiolibro" to "audiobooks", "audiobook" to "audiobooks", "horbuch" to "audiobooks", "audiolivro" to "audiobooks",
            "аудиокнига" to "audiobooks", "有声书" to "audiobooks",
            "radio" to "radio", "ραδιοφωνο" to "radio", "радио" to "radio", "电台" to "radio",
            "genere" to "genres", "genre" to "genres", "genero" to "genres", "жанр" to "genres", "流派" to "genres",
        )

        /** Gli articoli che possono precedere il tipo: "il podcast ...", "der Podcast ...", "o podcast ...". */
        private val ARTICLES = setOf(
            "il", "lo", "la", "l", "i", "gli", "le", "un", "uno", "una",
            "the", "a", "el", "los", "las", "der", "die", "das", "ein", "eine", "les", "une",
            "o", "os", "as", "um", "uma", "ο", "η", "το", "ενα",
        )

        private val MARKS = Regex("\\p{M}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Minuscolo, senza accenti né punteggiatura: per confrontare ciò che si cerca con i nomi. */
        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(SEPARATORS, " ").trim()

        /**
         * I risultati migliori: per ogni tipo il più somigliante tra i primi, poi in ordine di somiglianza.
         * Con [wanted] ("podcasts", ...) i primi di quel tipo vanno davanti. Li usa anche il telefono.
         */
        fun bestMatches(text: String, found: Map<String, List<MaItem>>, wanted: String?): List<Pair<String, MaItem>> {
            val query = fold(text)
            val words = query.split(' ').filter { it.isNotEmpty() }
            fun score(item: MaItem): Int {
                val name = fold(item.name)
                return when {
                    name == query -> 3
                    name.startsWith(query) -> 2
                    words.isNotEmpty() && words.all { it in name } -> 1
                    else -> 0
                }
            }
            val picks = Library.SEARCH_TYPES.mapNotNull { type ->
                val candidates = found[type]?.take(PICK_FROM) ?: return@mapNotNull null
                // A parità vince l'ordine di Music Assistant, che mette davanti i nomi uguali e la libreria.
                val best = candidates.maxByOrNull(::score) ?: return@mapNotNull null
                Triple(type, best, score(best))
            }
            val anyMatch = picks.any { it.third > 0 }
            val chosen = picks.filter { !anyMatch || it.third > 0 }
                .sortedByDescending { it.third }
                .map { it.first to it.second }
            if (wanted == null) return chosen
            val first = found[wanted].orEmpty().take(WANTED_FIRST).map { wanted to it }
            return first + chosen.filter { pick -> first.none { it.second.uri == pick.second.uri } }
        }

        /** "podcast morning", "il podcast morning" o "morning podcast": il tipo cercato e il resto. */
        fun typeHint(query: String): Pair<String?, String> {
            val words = query.trim().split(' ').filter { it.isNotEmpty() }
            val start = if (words.size > 2 && fold(words[0]) in ARTICLES) 1 else 0
            val leading = words.getOrNull(start)?.let { TYPE_WORDS[fold(it)] }
            if (leading != null && words.size > start + 1) return leading to words.drop(start + 1).joinToString(" ")
            val trailing = words.lastOrNull()?.let { TYPE_WORDS[fold(it)] }
            if (trailing != null && words.size > 1) return trailing to words.dropLast(1).joinToString(" ")
            return null to words.joinToString(" ")
        }

        /** L'iniziale con cui Music Assistant mette in ordine l'elemento; "#" per numeri e simboli. */
        private fun initialOf(item: MaItem): String {
            val c = fold(item.sortName ?: item.name).firstOrNull() ?: return "#"
            return if (c in 'a'..'z') c.uppercase() else "#"
        }

        private fun iconFor(type: String) = when (type) {
            "album" -> "album"
            "artist" -> "artist"
            "playlist" -> "playlist"
            "radio" -> "radio"
            "podcast", "podcast_episode" -> "podcast"
            "audiobook" -> "audiobook"
            "genre" -> "genre"
            "folder" -> "browse"
            else -> "track"
        }
    }
}
