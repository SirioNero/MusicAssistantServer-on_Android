package it.xonos.ma.phone.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.xonos.ma.data.Account
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Device
import it.xonos.ma.data.Devices
import it.xonos.ma.data.Library
import it.xonos.ma.data.Lyrics
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaImage
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.PlaylistRemover
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.data.cachedItem
import it.xonos.ma.data.cachedItems
import it.xonos.ma.data.objects
import it.xonos.ma.data.str
import it.xonos.ma.data.toCache
import it.xonos.ma.playback.Menu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Le schede in fondo allo schermo. */
enum class Tab { HOME, SEARCH, LIBRARY }

/** Cosa mostra il lettore al centro: la copertina, il testo o la coda. */
enum class PlayerPanel { COVER, LYRICS, QUEUE }

/** Un avviso breve in fondo allo schermo, dopo un comando. */
sealed interface Notice {
    data object PlayNext : Notice
    data object Added : Notice
    data object FavoriteAdded : Notice
    data object FavoriteRemoved : Notice
    data object Saved : Notice

    /** Messo nella playlist [name] (anche appena creata). */
    data class AddedTo(val name: String) : Notice

    /** Tolto dalla playlist [name]. */
    data class RemovedFrom(val name: String) : Notice
    data object PlaylistDeleted : Notice
    data object Removed : Notice

    /** La musica ora suona su questo lettore (null: sul telefono). */
    data class Moved(val device: String?) : Notice
    data class Failed(val error: Throwable) : Notice
}

/**
 * Lo stato dell'app sul telefono: le schede con le loro pile di schermate, i caricamenti dalla
 * libreria, la ricerca, i comandi di riproduzione e i testi. Non usa Android: lo schermo lo
 * disegna Compose, e il banco di prova lo usa contro un Music Assistant vero.
 *
 * Lo stato è fatto di valori osservabili di Compose; i comandi partono su [scope].
 */
class PhoneModel(
    private val repo: MaRepository,
    private val library: Library,
    private val settings: Settings,
    /** Le ultime schermate caricate, da mostrare subito alla prossima apertura. */
    private val cache: ScreenCache,
    /** Chi cerca Music Assistant nella rete (per le impostazioni). */
    val finder: ServerFinder,
    /** Il nome del token di Xonos in Music Assistant, per esempio "Xonos (Pixel 8)". */
    private val deviceName: String,
    /** La coda del telefono in Music Assistant (l'id del lettore). */
    private val queueId: () -> String,
    /** Vero quando il telefono è un lettore di Music Assistant: solo allora si può suonare. */
    private val ready: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    /** Music Assistant sul telefono stesso, scaricato su richiesta; null dove non può girare (32 bit). */
    private val phoneServer: PhoneServerControl? = null,
) {
    val connection: StateFlow<Connection> get() = repo.connection

    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stacks = Tab.entries.associateWith { tab ->
        mutableStateListOf(
            when (tab) {
                Tab.HOME -> HomePage()
                Tab.SEARCH -> SearchPage()
                Tab.LIBRARY -> LibraryPage()
            },
        )
    }

    /** La schermata in cima alla scheda scelta. */
    val page: Page get() = stacks.getValue(tab).last()

    /** Quante schermate ci sono sotto quella mostrata: con zero, "indietro" esce o torna alla Home. */
    val depth: Int get() = stacks.getValue(tab).size - 1

    /** Il lettore a schermo intero, sopra tutto il resto. */
    var playerOpen by mutableStateOf(false)

    /** Resta quella scelta: chi legge il testo lo ritrova riaprendo il lettore. */
    var playerPanel by mutableStateOf(PlayerPanel.COVER)

    var notice by mutableStateOf<Notice?>(null)

    /** Le impostazioni vanno mostrate per prime finché non c'è almeno un indirizzo e il token. */
    val configured: Boolean get() = settings.load().isComplete

    /** Il nome del telefono tra i lettori di Music Assistant. */
    val playerName: String get() = settings.playerName

    // ---------- Navigazione ----------

    fun select(tab: Tab) {
        if (tab == this.tab) {
            // Toccare di nuovo la scheda torna alla sua prima schermata.
            val stack = stacks.getValue(tab)
            while (stack.size > 1) removed(stack.removeAt(stack.lastIndex))
        }
        this.tab = tab
    }

    fun push(page: Page) {
        stacks.getValue(tab).add(page)
    }

    /** Una schermata tolta dalla pila: un flusso di configurazione lasciato a metà si chiude anche in Music Assistant. */
    private fun removed(page: Page) {
        if (page is FlowPage) services.leave(page)
    }

    /** Indietro: chiude il lettore, poi toglie una schermata, poi torna alla Home. Falso se non c'è altro. */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        when {
            playerOpen -> playerOpen = false
            stack.size > 1 -> removed(stack.removeAt(stack.lastIndex))
            tab != Tab.HOME -> tab = Tab.HOME
            else -> return false
        }
        return true
    }

    fun openSettings() = push(settingsPage())

    /** Le impostazioni con i valori salvati. */
    fun settingsPage(): SettingsPage {
        val config = settings.load()
        return SettingsPage(config.homeUrl, config.remoteUrl, config.token, settings.playerName, settings.username).also {
            it.phoneServerInstalled = phoneServer?.installed == true
            it.phoneServerError = phoneServerFailure
        }
    }

    private var welcome: SettingsPage? = null

    /**
     * La pagina del benvenuto. Vive qui, come il resto del modello: download del server, accesso e prova della
     * connessione restano sullo schermo anche se il telefono ruota o cambia lingua.
     */
    fun welcomePage(): SettingsPage = welcome ?: settingsPage().also { welcome = it }

    /** Finito il benvenuto: se un giorno ricompare (tolto il server, per esempio) riparte da capo. */
    fun welcomeDone() {
        welcome = null
    }

    /** Un server trovato nella rete: i suoi indirizzi vanno nel modulo. */
    fun choose(page: SettingsPage, server: FoundServer) {
        val saved = settings.load().urls.map { Settings.normalizeUrl(it) }
        if (Settings.normalizeUrl(server.url) !in saved) {
            // Un altro server: il token (e l'accesso) di quello salvato non gli vanno mandati. Qualsiasi apparecchio
            // della rete può annunciarsi come Music Assistant, e il token (spesso di un amministratore) viaggerebbe
            // in chiaro. Anche l'indirizzo remoto era di quell'altro.
            page.token = ""
            page.signedIn = null
            page.useToken = false
            page.remote = server.remoteUrl.orEmpty()
        } else {
            server.remoteUrl?.let { page.remote = it }
        }
        page.home = server.url
        page.setupNeeded = false
    }

    /** Apre un elemento: album, playlist, artista, podcast, genere o cartella; il resto lo suona. */
    fun open(item: MaItem) {
        playerOpen = false
        when {
            item.mediaType == "artist" -> push(ArtistPage(item))
            item.mediaType == "folder" -> push(ListPage(item.name, item.subtitle, item.image) { library.browse(item.path) })
            item.browsable -> push(ItemPage(item))
            else -> play(listOf(item.uri))
        }
    }

    /** Apre un album o un artista di cui si sa solo l'uri (dal lettore). */
    fun openUri(uri: String) {
        scope.launch {
            try {
                library.item(uri)?.let(::open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            }
        }
    }

    // ---------- Caricamenti ----------

    /** Carica i dati di una schermata la prima volta che compare. */
    fun start(page: Page) {
        if (page.started) return
        page.started = true
        when (page) {
            is HomePage -> loadHome(page)
            is LibraryPage -> loadShelves(page)
            is ItemPage -> loadItem(page)
            is ArtistPage -> loadArtist(page)
            is ListPage -> load(page)
            is ServicesPage -> services.load(page)
            is AddServicePage -> services.load(page)
            is FlowPage -> services.start(page)
            is ServicePage -> services.load(page)
            is SearchPage, is SettingsPage -> Unit
        }
    }

    /** Ricarica una schermata (per esempio dopo un errore di rete). */
    fun reload(page: Page) {
        if (page is LibraryPage) {
            page.lists.values.filter { it.error != null }.forEach(::loadMore)
            if (page.shelves != null) return
        }
        page.started = false
        start(page)
    }

    /**
     * La Home. Aggiornandola ([refresh]) resta quello che c'era finché non arriva il nuovo, e una
     * riga che non si carica non toglie quella di prima. La prima volta si mostra subito la Home
     * salvata l'ultima volta (se c'è), e intanto si aggiorna allo stesso modo.
     */
    private fun loadHome(page: HomePage, refresh: Boolean = false) {
        page.loadedAt = System.currentTimeMillis()
        if (refresh) page.refreshing = true
        page.job = scope.launch {
            val keep = refresh || showSavedHome(page)
            if (!keep) {
                page.recent = Load.Loading
                page.rows = Load.Loading
            }
            coroutineScope {
                launch {
                    try {
                        awaitConnected()
                        page.recent = Load.Ready(library.recent(RECENT))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!keep || page.recent !is Load.Ready) page.recent = Load.Failed(e)
                    }
                }
                launch {
                    try {
                        awaitConnected()
                        loadRows(page, keep)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!keep || page.rows !is Load.Ready) page.rows = Load.Failed(e)
                    }
                }
            }
            page.refreshing = false
            page.fresh = page.recent is Load.Ready && page.rows is Load.Ready && (page.rows as Load.Ready).value.none { it.items is Load.Loading }
            saveHome(page)
        }
    }

    /** La Home dell'ultima volta, dal disco; falso se non c'è. */
    private suspend fun showSavedHome(page: HomePage): Boolean {
        val saved = withContext(Dispatchers.IO) {
            val o = cache.read(HOME_CACHE) ?: return@withContext null
            val rows = o.optJSONArray("rows").objects().mapNotNull { row ->
                val item = row.optJSONObject("row")?.let(::cachedItem) ?: return@mapNotNull null
                HomePage.Row(item, Load.Ready(cachedItems(row.optJSONArray("items"))))
            }
            Triple(o.str("base"), cachedItems(o.optJSONArray("recent")), rows)
        } ?: return false
        val (base, recent, rows) = saved
        if (recent.isEmpty() && rows.isEmpty()) return false
        savedBase = base
        page.recent = Load.Ready(recent)
        page.rows = Load.Ready(rows)
        return true
    }

    private suspend fun saveHome(page: HomePage) {
        val base = repo.baseUrl ?: return
        val recent = page.recent.value ?: return
        val rows = page.rows.value?.mapNotNull { row -> row.items.value?.let { row.row to it } } ?: return
        withContext(Dispatchers.IO) {
            val saved = JSONObject()
                .put("base", base)
                .put("recent", recent.toCache())
                .put("rows", JSONArray(rows.map { (row, items) -> JSONObject().put("row", row.toCache()).put("items", items.toCache()) }))
            cache.write(HOME_CACHE, saved)
        }
    }

    private suspend fun loadRows(page: HomePage, refresh: Boolean) {
        // La riga "Recently played" dei consigli ripete la prima della Home.
        val rows = library.recommendations().filter { it.mediaType == "folder" && it.itemId != "recently_played" }.map { row ->
            HomePage.Row(row, if (row.inline.isNotEmpty()) Load.Ready(row.inline) else Load.Loading)
        }
        // La prima volta le righe compaiono man mano; aggiornando, tutte insieme alla fine.
        if (!refresh) page.rows = Load.Ready(rows)
        coroutineScope {
            rows.filter { it.items is Load.Loading }.map { row ->
                async {
                    row.items = try {
                        Load.Ready(library.recommendationItems(row.row))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Load.Failed(e)
                    }
                }
            }.awaitAll()
        }
        if (refresh) page.rows = Load.Ready(rows)
    }

    /** Trascinando in giù: si ricarica la Home o l'elenco della libreria, tenendo quello di prima intanto. */
    fun refresh(page: HomePage) {
        if (!page.refreshing) loadHome(page, refresh = true)
    }

    /**
     * Ricarica la prima pagina di un elenco. [quiet]: senza la rotella in alto, per aggiornare
     * l'elenco salvato l'ultima volta (intanto la rotella è in fondo, come quando si carica altro).
     */
    fun refresh(list: ShelfList, quiet: Boolean = false) {
        if (list.refreshing || (quiet && list.loading)) return
        list.job?.cancel()
        if (quiet) list.loading = true else list.refreshing = true
        if (!quiet) list.loading = false
        list.job = scope.launch {
            try {
                awaitConnected()
                val shelf = list.shelf
                val first = when (shelf) {
                    Shelf.BROWSE -> library.browse(null)
                    else -> library.libraryPage(shelf.kind, 0, orderBy = list.order, favorite = if (shelf.favorite) true else null)
                }
                list.items.clear()
                list.items.addAll(first)
                list.end = shelf == Shelf.BROWSE || first.size < Library.PHONE_PAGE
                list.error = null
                list.stale = false
                saveFirstPage(list, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                list.error = e
            } finally {
                list.refreshing = false
                list.loading = false
            }
        }
    }

    /** Mostra un elenco della libreria: la prima pagina salvata l'ultima volta e intanto quella nuova, o la si carica. */
    fun show(list: ShelfList) {
        if (list.shown) return
        list.shown = true
        if (list.items.isNotEmpty()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                cache.read(list.cacheName)?.let { it.str("base") to cachedItems(it.optJSONArray("items")) }
            }
            if (saved != null && saved.second.isNotEmpty() && list.items.isEmpty()) {
                savedBase = savedBase ?: saved.first
                list.items.addAll(saved.second)
                list.stale = true
                refresh(list, quiet = true)
            } else {
                loadMore(list)
            }
        }
    }

    private fun saveFirstPage(list: ShelfList, first: List<MaItem>) {
        val base = repo.baseUrl ?: return
        val name = list.cacheName
        scope.launch(Dispatchers.IO) { cache.write(name, JSONObject().put("base", base).put("items", first.toCache())) }
    }

    /** Tornando nell'app: una Home vecchia di più di dieci minuti si aggiorna da sola. */
    fun wake() {
        val home = stacks.getValue(Tab.HOME).first() as HomePage
        if (home.started && System.currentTimeMillis() - home.loadedAt > STALE_MS) refresh(home)
        // Di ritorno dal browser (l'accesso a un servizio): il flusso può essere andato avanti.
        (page as? FlowPage)?.let(services::refresh)
    }

    private fun loadShelves(page: LibraryPage) {
        scope.launch {
            // Le voci dell'ultima volta subito, poi quelle vere.
            val saved = withContext(Dispatchers.IO) {
                cache.read(SHELVES_CACHE)?.optJSONArray("shelves")?.let { names ->
                    (0 until names.length()).mapNotNull { i -> Shelf.entries.firstOrNull { it.name == names.optString(i) } }
                }
            }
            if (page.shelves == null && !saved.isNullOrEmpty()) {
                page.shelves = saved
                if (page.shelf !in saved) page.shelf = saved.first()
            }
        }
        launchLoad({ if (page.shelves == null) page.shelves = Shelf.entries }) {
            // Si mostrano solo le voci con qualcosa dentro, come in auto.
            val shelves = coroutineScope {
                Shelf.entries.map { shelf -> async { shelf to has(shelf) } }.awaitAll()
            }.filter { it.second }.map { it.first }
            page.shelves = shelves
            if (page.shelf !in shelves) shelves.firstOrNull()?.let { page.shelf = it }
            withContext(Dispatchers.IO) { cache.write(SHELVES_CACHE, JSONObject().put("shelves", JSONArray(shelves.map { it.name }))) }
        }
    }

    private suspend fun has(shelf: Shelf): Boolean = try {
        when (shelf) {
            Shelf.BROWSE -> true
            // Music Assistant conta anche i generi senza musica, e prima della 2.10 non li ha.
            Shelf.GENRES -> library.hasGenres()
            else -> library.count(shelf.kind, shelf.favorite) != 0
        }
    } catch (e: MaException) {
        shelf != Shelf.GENRES
    }

    /** L'elenco di una voce della libreria, che si carica a pagine. */
    fun shelf(page: LibraryPage, shelf: Shelf): ShelfList = page.lists.getOrPut(shelf) { ShelfList(shelf) }

    /** Chiede la pagina successiva di un elenco della libreria, se non la sta già chiedendo. */
    fun loadMore(list: ShelfList) {
        // Un elenco ancora quello salvato: prima va rifatta la sua prima pagina.
        if (list.stale) return refresh(list, quiet = true)
        if (list.loading || list.end) return
        list.loading = true
        list.error = null
        list.job = scope.launch {
            try {
                awaitConnected()
                val shelf = list.shelf
                val page = when (shelf) {
                    Shelf.BROWSE -> library.browse(null).also { list.end = true }
                    else -> library.libraryPage(shelf.kind, list.items.size, orderBy = list.order, favorite = if (shelf.favorite) true else null)
                }
                list.items.addAll(page)
                if (shelf != Shelf.BROWSE && page.size < Library.PHONE_PAGE) list.end = true
                if (list.items.size == page.size) saveFirstPage(list, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                list.error = e
            } finally {
                list.loading = false
            }
        }
    }

    /** Cambia l'ordine di un elenco della libreria e lo ricarica da capo. */
    fun order(list: ShelfList, order: String) {
        if (list.order == order) return
        list.job?.cancel()
        list.order = order
        list.items.clear()
        list.end = false
        list.loading = false
        loadMore(list)
    }

    private fun loadItem(page: ItemPage) {
        val item = page.item
        page.tracks = Load.Loading
        page.more = false
        // Le playlist lunghe arrivano a pezzi: quelli arrivati si vedono subito.
        val part = parts({ page.more || page.tracks is Load.Loading }) { page.more = true; page.tracks = Load.Ready(it) }
        launchLoad({ error -> page.more = false; partFailed(error, page.tracks is Load.Ready) { page.tracks = Load.Failed(error) } }) {
            val all = when (item.mediaType) {
                "album" -> library.albumTracks(item)
                "playlist" -> library.playlistTracks(item, part, fresh = recentlyEdited(item))
                "podcast" -> library.podcastEpisodes(item, part)
                "genre" -> library.genreTracks(item)
                else -> emptyList()
            }
            page.more = false
            page.tracks = Load.Ready(all)
        }
        if (item.mediaType == "genre") {
            page.albums = Load.Loading
            launchLoad({ page.albums = Load.Failed(it) }) { page.albums = Load.Ready(library.genreAlbums(item)) }
        }
    }

    private fun loadArtist(page: ArtistPage) {
        val artist = page.artist
        page.top = Load.Loading
        page.albums = Load.Loading
        page.tracks = Load.Loading
        page.albumsMore = false
        page.tracksMore = false
        launchLoad({ page.top = Load.Failed(it) }) { page.top = Load.Ready(library.artistTopTracks(artist)) }
        // Prima la libreria, poi la discografia di ogni servizio appena risponde.
        val albums = parts({ page.albumsMore || page.albums is Load.Loading }) { part ->
            page.albumsMore = true
            page.albums = Load.Ready(part.sortedByDescending { it.year })
        }
        launchLoad({ error -> page.albumsMore = false; partFailed(error, page.albums is Load.Ready) { page.albums = Load.Failed(error) } }) {
            val all = library.artistAlbums(artist, albums).sortedByDescending { it.year }
            page.albumsMore = false
            page.albums = Load.Ready(all)
        }
        val tracks = parts({ page.tracksMore || page.tracks is Load.Loading }) { page.tracksMore = true; page.tracks = Load.Ready(it) }
        launchLoad({ error -> page.tracksMore = false; partFailed(error, page.tracks is Load.Ready) { page.tracks = Load.Failed(error) } }) {
            val all = library.artistTracks(artist, tracks)
            page.tracksMore = false
            page.tracks = Load.Ready(all)
        }
    }

    /**
     * I pezzi di un elenco che arriva a poco a poco: si mostrano con [show] finché [waiting] (finché
     * non è arrivato l'elenco intero). Un pezzo in ritardo non torna indietro sull'elenco intero.
     */
    private fun parts(waiting: () -> Boolean, show: (List<MaItem>) -> Unit): (List<MaItem>) -> Unit = { part ->
        scope.launch { if (waiting()) show(part) }
    }

    /** Un elenco interrotto a metà: il pezzo che si vede resta, con l'avviso; se non si vedeva niente, l'errore. */
    private fun partFailed(error: Throwable, shown: Boolean, fail: () -> Unit) {
        if (shown) notice = Notice.Failed(error) else fail()
    }

    private fun load(page: ListPage) {
        page.items = Load.Loading
        launchLoad({ page.items = Load.Failed(it) }) { page.items = Load.Ready(page.source()) }
    }

    /** Tutti i brani di un artista, su una schermata loro. */
    fun openArtistTracks(page: ArtistPage, title: String, top: Boolean) {
        // Se i brani arrivano ancora, la pagina se li carica interi.
        val tracks = if (top) page.top.value else page.tracks.value.takeUnless { page.tracksMore }
        push(
            ListPage(title, page.artist.name, page.artist.image) {
                tracks ?: if (top) library.artistTopTracks(page.artist) else library.artistTracks(page.artist)
            },
        )
    }

    /** Una riga di "Per te" intera. */
    fun openRow(row: HomePage.Row) {
        val items = row.items.value
        push(ListPage(row.row.name, row.row.subtitle, null) { items ?: library.recommendationItems(row.row) })
    }

    /** Tutti i risultati di un tipo di una ricerca. */
    fun openResults(results: SearchResults, type: String, title: String) {
        val items = results.byType[type].orEmpty()
        push(ListPage(title, results.query, null) { items })
    }

    /** Aspetta la connessione (al primo avvio ci vuole un attimo), poi carica; un errore va in [failed]. */
    internal fun launchLoad(failed: (Throwable) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                awaitConnected()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    internal suspend fun awaitConnected() {
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { repo.connection.first { it is Connection.Connected } }
            ?: throw MaException("Music Assistant non è collegato", problem = Problem.NOT_CONNECTED)
    }

    // ---------- Ricerca ----------

    /**
     * Cerca mentre si scrive: aspetta una breve pausa, e una ricerca nuova cancella la precedente.
     * La libreria risponde subito, i servizi (Spotify...) anche in secondi: intanto si vede lei.
     */
    fun search(page: SearchPage, text: String) {
        page.query = text
        page.job?.cancel()
        val query = text.trim()
        if (query.length < MIN_QUERY) {
            page.results = null
            page.searching = false
            return
        }
        page.job = scope.launch {
            delay(SEARCH_PAUSE_MS)
            page.searching = true
            if (page.results !is Load.Ready) page.results = Load.Loading
            try {
                awaitConnected()
                val (wanted, rest) = Menu.typeHint(query)
                fun show(found: Map<String, List<MaItem>>) {
                    page.results = Load.Ready(SearchResults(query, Menu.bestMatches(rest, found, wanted).firstOrNull()?.second, found))
                }
                coroutineScope {
                    // Un errore di una delle due ricerche non deve fermare l'altra.
                    val everywhere = async {
                        try {
                            Result.success(library.search(rest))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                    val own = try {
                        library.search(rest, libraryOnly = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val shownOwn = !own.isNullOrEmpty() && !everywhere.isCompleted
                    if (shownOwn) show(own!!)
                    everywhere.await().fold(::show) { error ->
                        // Se si vede già la libreria resta, con l'avviso.
                        if (shownOwn) notice = Notice.Failed(error) else throw error
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                page.results = Load.Failed(e)
            } finally {
                // Una ricerca cancellata da una più nuova lascia a quella la rotella.
                if (page.job === coroutineContext[Job]) page.searching = false
            }
        }
    }

    // ---------- Riproduzione ----------

    /** Suona un elemento intero (album, playlist, artista, radio...), eventualmente mescolato. */
    fun play(item: MaItem, shuffle: Boolean = false) = play(listOf(item.uri), shuffle = shuffle)

    /**
     * Suona il brano [index] di un elenco. Dentro un album o una playlist ([context]) parte tutto
     * da lì; altrimenti suonano quel brano e quelli che lo seguono.
     */
    fun play(list: List<MaItem>, index: Int, context: String?) {
        val item = list.getOrNull(index) ?: return
        when {
            context != null -> play(listOf(context), start = item.uri)
            item.mediaType in TRACK_LIKE -> play(list.drop(index).filter { it.mediaType in TRACK_LIKE }.take(MAX_QUEUE).map { it.uri })
            item.browsable -> open(item)
            else -> play(listOf(item.uri))
        }
    }

    /** Suona tutti i brani di un elenco, dal primo o mescolati. */
    fun playAll(list: List<MaItem>, shuffle: Boolean) {
        val uris = list.filter { it.mediaType in TRACK_LIKE }.take(MAX_QUEUE).map { it.uri }
        if (uris.isNotEmpty()) play(uris, shuffle = shuffle)
    }

    fun playNext(item: MaItem) = play(listOf(item.uri), option = "next")

    fun addToQueue(item: MaItem) = play(listOf(item.uri), option = "add")

    /** Un "suona" (non "dopo" né "in coda") mandato a Music Assistant, che non ha ancora risposto. */
    private data class PlayRequest(val target: String?, val media: List<String>, val start: String?, val shuffle: Boolean)

    private var pendingPlay: PlayRequest? = null

    /**
     * Music Assistant sta preparando un "suona": risponde solo quando la musica parte, e con Spotify (Soloist) ci
     * vogliono diversi secondi. Intanto il mini-lettore mostra la rotella, anche se prima non suonava niente.
     */
    var starting by mutableStateOf(false)
        private set

    private fun play(media: List<String>, start: String? = null, option: String = "replace", shuffle: Boolean = false) {
        // Suona dove si è scelto con "Suona su": il telefono, o un lettore di Music Assistant.
        val target = remote?.device?.id
        val request = if (option == "replace") PlayRequest(target, media, start, shuffle) else null
        // Lo stesso "suona" toccato di nuovo mentre Music Assistant prepara il primo: con Spotify (Soloist) farebbe
        // ripartire tutto da capo. Un "suona" diverso invece passa (Music Assistant li fa uno dopo l'altro).
        if (request != null && request == pendingPlay) return
        if (request != null) {
            pendingPlay = request
            starting = true
        }
        scope.launch {
            try {
                if (target == null) awaitReady()
                library.playMedia(target ?: queueId(), media, start, option, shuffle)
                when (option) {
                    "next" -> notice = Notice.PlayNext
                    "add" -> notice = Notice.Added
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                if (request != null && pendingPlay === request) {
                    pendingPlay = null
                    starting = false
                }
            }
        }
    }

    /** Aspetta che il telefono sia un lettore di Music Assistant: appena aperta l'app ci vuole un attimo. */
    private suspend fun awaitReady() {
        withTimeoutOrNull(READY_TIMEOUT_MS) { ready.first { it } }
            ?: throw MaException("Il lettore del telefono non è pronto", problem = Problem.NOT_CONNECTED)
    }

    // ---------- Suona su ----------

    private val devices = Devices(repo)

    /** Il lettore di Music Assistant comandato dallo schermo; null quando la musica esce dal telefono. */
    var remote by mutableStateOf<RemoteLink?>(null)
        private set

    /** La scelta "Suona su", quando è aperta. */
    var picker by mutableStateOf<DevicePicker?>(null)
        private set

    /** Quando si è cambiato lettore l'ultima volta (vedi [phonePlaying]). */
    private var movedAt = 0L

    /** Apre la scelta del lettore e cerca quelli che Music Assistant conosce. */
    fun openDevices() {
        val picker = DevicePicker()
        this.picker = picker
        loadDevices(picker)
    }

    fun loadDevices(picker: DevicePicker) {
        picker.devices = Load.Loading
        launchLoad({ picker.devices = Load.Failed(it) }) { picker.devices = Load.Ready(devices.targets(queueId())) }
    }

    fun closeDevices() {
        picker = null
    }

    /**
     * Fa suonare la musica su [device] (null: sul telefono). Se dove suona adesso c'è una coda, la
     * coda passa al lettore nuovo e riparte dallo stesso punto; quello di prima si ferma.
     */
    fun playOn(device: Device?) {
        val picker = picker
        val old = remote
        if (device?.id == old?.device?.id) return closeDevices()
        picker?.moving = device?.id ?: PHONE
        scope.launch {
            try {
                val from = old?.device?.id ?: queueId().takeIf { ready.value }
                val to = device?.id ?: run {
                    awaitReady()
                    queueId()
                }
                if (from != null && (devices.queue(from)?.items ?: 0) > 0) devices.transfer(from, to)
                old?.stop()
                remote = device?.let { RemoteLink(it, repo, devices, library, ::image, scope).also(RemoteLink::start) }
                movedAt = System.currentTimeMillis()
                this@PhoneModel.picker = null
                notice = Notice.Moved(device?.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                picker?.moving = null
            }
        }
    }

    /**
     * Il telefono ha cominciato a suonare per conto suo (per esempio dall'auto): lo schermo torna a
     * mostrare il telefono, senza toccare l'altro lettore. Appena dopo un cambio non vale: la coda
     * del telefono sta ancora finendo.
     */
    fun phonePlaying(playing: Boolean) {
        val old = remote ?: return
        if (!playing || System.currentTimeMillis() - movedAt < MOVE_SETTLE_MS) return
        old.stop()
        remote = null
    }

    // ---------- Playlist ----------

    /** "Aggiungi a una playlist", quando è aperta. */
    var playlistPicker by mutableStateOf<PlaylistPicker?>(null)
        private set

    /** Le playlist cambiate da qui, e quando: per un po' i loro brani si chiedono senza la cache di Music Assistant. */
    private val edited = mutableMapOf<String, Long>()
    private val removers = mutableMapOf<String, PlaylistRemover>()

    private fun recentlyEdited(playlist: MaItem) = edited[playlist.uri]?.let { System.currentTimeMillis() - it < EDITED_MS } == true

    /** Una playlist in cui da qui si possono mettere e togliere brani (le playlist della libreria che lo permettono). */
    fun canEdit(item: MaItem) = item.mediaType == "playlist" && item.provider == "library" && item.editable

    /** Le playlist di Music Assistant stesso si possono anche cancellare; quelle dei servizi no. */
    fun canDelete(item: MaItem) = canEdit(item) && item.mappings.any { (provider, _) -> provider == "builtin" }

    /** Apre "Aggiungi a una playlist" per un brano, un album o una playlist. */
    fun addToPlaylist(uri: String) {
        val picker = PlaylistPicker(uri)
        playlistPicker = picker
        loadPlaylists(picker)
    }

    fun loadPlaylists(picker: PlaylistPicker) {
        picker.playlists = Load.Loading
        launchLoad({ picker.playlists = Load.Failed(it) }) { picker.playlists = Load.Ready(library.editablePlaylists()) }
    }

    fun closePlaylists() {
        playlistPicker = null
    }

    /** Mette in fondo a [playlist] ciò che si era scelto: Music Assistant salta i brani che ci sono già. */
    fun addTo(picker: PlaylistPicker, playlist: MaItem) {
        playlistPicker = null
        scope.launch {
            try {
                library.addToPlaylist(playlist, listOf(picker.uri))
                edited[playlist.uri] = System.currentTimeMillis()
                notice = Notice.AddedTo(playlist.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            }
        }
    }

    /** Una playlist nuova di Music Assistant, con dentro ciò che si era scelto. */
    fun createPlaylist(picker: PlaylistPicker, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || picker.creating) return
        picker.creating = true
        scope.launch {
            try {
                val playlist = library.createPlaylist(clean)
                library.addToPlaylist(playlist, listOf(picker.uri))
                edited[playlist.uri] = System.currentTimeMillis()
                if (playlistPicker === picker) playlistPicker = null
                notice = Notice.AddedTo(playlist.name)
                playlistsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                picker.creating = false
            }
        }
    }

    /**
     * Toglie un brano dalla playlist aperta: sparisce subito dall'elenco, Music Assistant lo toglie
     * poco dopo (vedi [PlaylistRemover]). Se non ci riesce, l'elenco torna quello vero.
     */
    fun removeFromPlaylist(page: ItemPage, track: MaItem) {
        val playlist = page.item
        val list = page.tracks.value ?: return
        val index = list.indexOf(track)
        if (index < 0) return
        page.tracks = Load.Ready(list.toMutableList().apply { removeAt(index) })
        edited[playlist.uri] = System.currentTimeMillis()
        removers.getOrPut(playlist.uri) {
            PlaylistRemover(scope, library, playlist, { _, result ->
                edited[playlist.uri] = System.currentTimeMillis()
                result.onFailure { error ->
                    notice = Notice.Failed(error)
                    // La playlist mostrata in quel momento torna quella vera.
                    (this@PhoneModel.page as? ItemPage)?.takeIf { it.item.uri == playlist.uri }?.let(::reload)
                }
            })
        }.remove(track.uri)
        notice = Notice.RemovedFrom(playlist.name)
    }

    /** Cancella una playlist di Music Assistant (vedi [canDelete]) e torna indietro. */
    fun deletePlaylist(page: ItemPage) {
        val playlist = page.item
        scope.launch {
            try {
                library.removeFromLibrary(playlist)
                // La sua pagina si chiude, anche se nel frattempo si è cambiata scheda.
                stacks.values.firstOrNull { it.size > 1 && it.last() === page }?.let { it.removeAt(it.lastIndex) }
                playlistsChanged(gone = playlist.uri)
                notice = Notice.PlaylistDeleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            }
        }
    }

    /** Le playlist della libreria sono cambiate: gli elenchi già aperti si aggiornano. */
    private fun playlistsChanged(gone: String? = null) {
        for (stack in stacks.values) {
            for (page in stack) {
                if (page !is LibraryPage) continue
                val list = page.lists[Shelf.PLAYLISTS] ?: continue
                if (gone != null) list.items.removeAll { it.uri == gone }
                if (list.shown) refresh(list, quiet = true)
            }
        }
    }

    // ---------- Preferiti ----------

    fun setFavorite(page: ItemPage, favorite: Boolean) = favorite(page.item, favorite) { page.favorite = it }

    fun setFavorite(page: ArtistPage, favorite: Boolean) = favorite(page.artist, favorite) { page.favorite = it }

    private fun favorite(item: MaItem, favorite: Boolean, show: (Boolean) -> Unit) {
        show(favorite)
        scope.launch {
            try {
                library.setFavorite(item.uri, item.mediaType, item.provider, item.itemId, favorite)
                notice = if (favorite) Notice.FavoriteAdded else Notice.FavoriteRemoved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(!favorite)
                notice = Notice.Failed(e)
            }
        }
    }

    // ---------- Testi ----------

    private val account = Account(repo)

    /** I servizi di Music Assistant (solo per gli amministratori). */
    val services = ServicesModel(this, repo, scope)

    private val lyrics = object : LinkedHashMap<String, LyricsState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState>?) = size > LYRICS_KEPT
    }

    /**
     * Il testo di un brano, chiesto una volta sola. Se il brano non ha testo, Music Assistant lo
     * cerca nei servizi di testi (LRCLIB...): senza rete può metterci decine di secondi. Dopo
     * [LYRICS_WAIT_MS] si dice "nessun testo"; se poi arriva, si mostra. Dopo un errore, la volta
     * dopo si chiede di nuovo.
     */
    fun lyrics(uri: String): LyricsState {
        lyrics[uri]?.let { return it }
        val state = LyricsState()
        lyrics[uri] = state
        val wait = scope.launch {
            delay(LYRICS_WAIT_MS)
            if (state.load is Load.Loading) state.load = Load.Ready(null)
        }
        scope.launch {
            val result: Load<Lyrics?> = try {
                awaitConnected()
                Load.Ready(library.lyrics(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
            wait.cancel()
            if (result is Load.Failed) {
                if (lyrics[uri] === state) lyrics.remove(uri)
                // Se si dice già "nessun testo", resta.
                if (state.load is Load.Loading) state.load = result
            } else {
                state.load = result
            }
        }
        return state
    }

    // ---------- Impostazioni ----------

    /**
     * Salva le impostazioni, ricollega Xonos e prova ogni indirizzo. Con nome utente e password
     * prima si accede (al primo indirizzo che risponde) e Music Assistant dà a Xonos un token suo.
     */
    fun save(page: SettingsPage) {
        tidy(page)
        if (page.useToken || page.password.isEmpty()) return finish(page)
        signIn(page) { url -> account.login(url, page.username, page.password, deviceName) }
    }

    /** Un Music Assistant appena installato: crea il primo account (l'amministratore) ed entra. */
    fun createAccount(page: SettingsPage) {
        tidy(page)
        page.passwordShort = page.password.length < MIN_PASSWORD
        if (page.passwordShort || page.username.length < 2) return
        signIn(page) { url -> account.createFirst(url, page.username, page.password, deviceName) }
    }

    // ---------- Music Assistant sul telefono ----------

    /** Xonos può far girare Music Assistant su questo telefono (a 64 bit), scaricandolo su richiesta. */
    val phoneServerAvailable: Boolean get() = phoneServer != null

    /** Il pacchetto c'era ma per una versione precedente di Xonos: va riscaricato. */
    val phoneServerOutdated: Boolean get() = phoneServer?.outdated == true

    /** Xonos usa il Music Assistant sul telefono e quello sta ancora partendo (all'apertura dell'app ci vuole un po'). */
    val phoneServerStarting: Boolean get() = onPhoneServer && phoneServer?.state == PhoneServerControl.State.STARTING

    /** Xonos usa il Music Assistant sul telefono e quello non è partito o si è fermato: il perché. */
    val phoneServerFailure: String?
        get() = phoneServer?.takeIf { onPhoneServer && it.state == PhoneServerControl.State.FAILED }
            ?.let { it.error.ifBlank { it.state.name } }

    /** "Riprova" quando il server sul telefono non è partito (dopo qualche tentativo Xonos smette da solo). */
    fun retryPhoneServer() {
        val server = phoneServer ?: return
        scope.launch { if (server.start()) repo.reconnect() }
    }

    /** Il modulo indica il Music Assistant sul telefono. */
    fun usesPhoneServer(page: SettingsPage): Boolean = phoneServer != null && page.home == phoneServer.url

    /** Xonos è collegato al Music Assistant sul telefono (quello salvato, non il modulo). */
    val onPhoneServer: Boolean get() = phoneServer != null && settings.load().homeUrl == phoneServer.url

    /**
     * Ha senso "Suona su": il Music Assistant sul telefono non vede casse né TV (niente servizi di casse, risponde
     * solo al telefono), e l'elenco sarebbe sempre vuoto con l'invito ad aggiungere Chromecast e AirPlay.
     */
    val canPlayElsewhere: Boolean get() = remote != null || !onPhoneServer

    /** Passa a [share] (la condivisione di Android) il registro di Music Assistant sul telefono, per chi aiuta. */
    fun sharePhoneServerLog(share: (String) -> Unit) {
        val server = phoneServer ?: return
        scope.launch { share(runCatching { server.log() }.getOrElse { "Xonos: ${it.message ?: it}" }) }
    }

    /** Si sta prendendo il registro del Music Assistant collegato (può volerci qualche secondo). */
    var fetchingServerLog by mutableStateOf(false)
        private set

    /**
     * Passa a [share] le ultime righe del registro del Music Assistant a cui Xonos è collegato (umbrel,
     * un computer, il telefono), con versione e indirizzo in testa. Solo per gli amministratori.
     */
    fun shareServerLog(share: (String) -> Unit) {
        val connected = connection.value as? Connection.Connected ?: return
        if (fetchingServerLog) return
        fetchingServerLog = true
        scope.launch {
            try {
                val log = library.serverLog()
                share("Music Assistant ${connected.server.version} (${connected.server.name}) ${connected.url}\n\n$log")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                fetchingServerLog = false
            }
        }
    }

    /**
     * Avvia Music Assistant sul telefono e ci collega Xonos. La prima volta crea l'account (è
     * l'amministratore, così si possono aggiungere i servizi): nome e password li sceglie Xonos e la
     * password la tiene per sé, per rifarsi dare il token se lo perde. Poi è come con qualsiasi Music Assistant.
     */
    fun usePhoneServer(page: SettingsPage) {
        val server = phoneServer ?: return
        page.results = emptyList()
        page.phoneServerError = null
        page.setupNeeded = false
        page.passwordShort = false
        page.checking = true
        scope.launch {
            var connecting = false
            try {
                if (!server.start()) {
                    page.phoneServerError = server.error.ifBlank { server.state.name }
                    return@launch
                }
                val url = server.url
                val password = settings.phoneServerPassword ?: newPassword().also { settings.phoneServerPassword = it }
                val login = if (account.needsSetup(url)) {
                    account.createFirst(url, PHONE_SERVER_USER, password, deviceName)
                } else {
                    account.login(url, PHONE_SERVER_USER, password, deviceName)
                }
                settings.username = login.user
                page.home = url
                page.remote = ""
                page.useToken = false
                page.token = login.token
                page.username = login.user
                page.password = ""
                page.signedIn = login.user
                page.tried = true
                connecting = true
                finish(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                page.results = listOf(ConnectionCheck.Result.Failed(ConnectionCheck.Place.HOME, e))
            } finally {
                if (!connecting) page.checking = false
            }
        }
    }

    /**
     * "Fai diventare Xonos il tuo server Music Assistant": prima la domanda, con quanto pesa il pacchetto. Si
     * scarica solo se la persona dice di sì ([downloadPhoneServer]).
     */
    fun askPhoneServerDownload(page: SettingsPage) {
        val server = phoneServer ?: return
        page.phoneServerAsk = true
        page.phoneServerDownloadError = null
        if (page.phoneServerSize == null) scope.launch { page.phoneServerSize = server.downloadSize() }
    }

    fun dismissPhoneServerDownload(page: SettingsPage) {
        page.phoneServerAsk = false
    }

    /** Scarica il pacchetto e poi fa come "Usa Music Assistant su questo telefono". */
    fun downloadPhoneServer(page: SettingsPage) {
        val server = phoneServer ?: return
        page.phoneServerAsk = false
        page.phoneServerDownloadError = null
        page.phoneServerError = null
        page.phoneServerProgress = 0L to (page.phoneServerSize ?: -1L)
        // La prima volta ci si collega da soli; un aggiornamento sposta Xonos (e myCar) sul server del telefono solo
        // se lo stava già usando: chi usa un altro Music Assistant resta lì.
        val switchTo = usesPhoneServer(page) || !server.outdated
        downloading?.cancel()
        downloading = scope.launch {
            // Se lo si sta ancora togliendo, prima si finisce.
            removing?.join()
            var shown = 0L
            try {
                server.download { done, total ->
                    // Lo schermo si aggiorna ogni mezzo mega, non a ogni pezzo.
                    if (done - shown >= PROGRESS_STEP || done == total) {
                        shown = done
                        page.phoneServerProgress = done to total
                    }
                }
                page.phoneServerInstalled = server.installed
                page.phoneServerProgress = null
                if (switchTo) usePhoneServer(page)
            } catch (e: CancellationException) {
                page.phoneServerProgress = null
                throw e
            } catch (e: Exception) {
                page.phoneServerProgress = null
                page.phoneServerInstalled = server.installed
                page.phoneServerDownloadError = e.message ?: e.toString()
            }
        }
    }

    fun cancelPhoneServerDownload(page: SettingsPage) {
        downloading?.cancel()
        downloading = null
        page.phoneServerProgress = null
    }

    /**
     * Toglie Music Assistant dal telefono: il pacchetto e i suoi dati (l'account che Xonos si era creato, i
     * servizi, la libreria). Se Xonos lo stava usando, il modulo torna vuoto.
     */
    fun removePhoneServer(page: SettingsPage) {
        val server = phoneServer ?: return
        if (usesPhoneServer(page)) leavePhoneServer(page)
        // Anche se il modulo mostra già un altro Music Assistant ("Usa un altro Music Assistant" senza salvare):
        // finché le impostazioni salvate sono quelle del server sul telefono, Xonos e myCar restano lì, e Xonos lo
        // farebbe ripartire mentre lo si cancella.
        if (onPhoneServer) {
            settings.save(Settings.Config("", "", ""))
            repo.reconnect()
        }
        settings.phoneServerPassword = null
        page.phoneServerInstalled = false
        page.phoneServerSize = null
        page.phoneServerError = null
        removing = scope.launch { server.remove() }
    }

    /** Per collegarsi a un altro Music Assistant: il modulo torna vuoto. Il server sul telefono resta com'è. */
    fun leavePhoneServer(page: SettingsPage) {
        page.home = ""
        page.remote = ""
        page.token = ""
        page.username = ""
        page.signedIn = null
        page.results = emptyList()
        page.phoneServerError = null
    }

    private var downloading: Job? = null
    private var removing: Job? = null

    private fun newPassword(): String {
        val random = SecureRandom()
        return (1..24).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)] }.joinToString("")
    }

    private fun tidy(page: SettingsPage) {
        page.home = Settings.normalizeUrl(page.home)
        page.remote = Settings.normalizeUrl(page.remote)
        page.token = page.token.trim()
        page.username = page.username.trim()
        settings.playerName = page.name
        page.name = settings.playerName
        page.results = emptyList()
        page.tried = true
        page.passwordShort = false
    }

    /** Accede con [how] al primo indirizzo che risponde; poi salva il token e prova la connessione. */
    private fun signIn(page: SettingsPage, how: suspend (String) -> Account.Login) {
        val places = listOf(ConnectionCheck.Place.HOME to page.home, ConnectionCheck.Place.AWAY to page.remote).filter { it.second.isNotBlank() }
        if (places.isEmpty() || page.username.isEmpty()) return
        page.checking = true
        scope.launch {
            var failure: ConnectionCheck.Result? = null
            for ((place, url) in places) {
                try {
                    val login = how(url)
                    page.token = login.token
                    page.password = ""
                    page.setupNeeded = false
                    settings.username = login.user
                    page.signedIn = login.user
                    finish(page)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = ConnectionCheck.Result.Failed(place, e)
                    val problem = (e as? MaException)?.problem
                    if (problem == Problem.SETUP_REQUIRED) page.setupNeeded = true
                    // Il server ha risposto (password sbagliata, nessun account...): l'altro indirizzo è lo stesso server.
                    if (problem in ANSWERED) break
                }
            }
            page.results = listOfNotNull(failure)
            page.checking = false
        }
    }

    private fun finish(page: SettingsPage) {
        val config = Settings.Config(homeUrl = page.home, remoteUrl = page.remote, token = page.token)
        // Un altro server: le schermate salvate sono dell'altro, e anche quelle aperte.
        if (config.urls != settings.load().urls) {
            scope.launch(Dispatchers.IO) { cache.clear() }
            forgetServer(page)
            // Lasciato il Music Assistant sul telefono, non serve più che giri (processore e batteria): lo si
            // riaccende con "Usa Music Assistant su questo telefono".
            val server = phoneServer
            if (server != null && onPhoneServer && config.homeUrl != server.url) scope.launch(Dispatchers.IO) { server.stop() }
        }
        settings.save(config)
        repo.reconnect()
        page.results = emptyList()
        if (!config.isComplete) {
            page.checking = false
            return
        }
        page.checking = true
        scope.launch {
            val check = ConnectionCheck(repo)
            val places = listOf(ConnectionCheck.Place.HOME to config.homeUrl, ConnectionCheck.Place.AWAY to config.remoteUrl)
            try {
                for ((place, url) in places) {
                    if (url.isBlank()) continue
                    page.results = page.results + check.check(place, url, config.token)
                }
            } finally {
                page.checking = false
            }
        }
    }

    /**
     * Un altro Music Assistant: Home, Cerca e Libreria (con quello che c'era sopra) e la cassa di "Suona su" erano del
     * server di prima. Ogni scheda torna alla sua prima schermata, che si carica dal nuovo; [settings] (da dove si è
     * cambiato server) resta in cima.
     */
    private fun forgetServer(settings: SettingsPage) {
        remote?.stop()
        remote = null
        for ((t, stack) in stacks) {
            val keep = settings.takeIf { t == tab && it in stack }
            stack.clear()
            stack.add(
                when (t) {
                    Tab.HOME -> HomePage()
                    Tab.SEARCH -> SearchPage()
                    Tab.LIBRARY -> LibraryPage()
                },
            )
            keep?.let { stack.add(it) }
        }
    }

    init {
        // Tornata la connessione, le schermate che non si erano caricate ci riprovano da sole.
        scope.launch {
            repo.connection.collect { connection ->
                if (connection !is Connection.Connected) return@collect
                stacks.values.flatten().filter { it.started && failed(it) }.forEach(::reload)
            }
        }
    }

    private fun failed(page: Page): Boolean = when (page) {
        // Anche una Home mostrata dal disco e mai aggiornata, se non ci sta già provando.
        is HomePage -> page.job?.isActive != true && (page.recent is Load.Failed || page.rows is Load.Failed || !page.fresh)
        is LibraryPage -> page.lists.values.any { it.error != null }
        is ItemPage -> page.tracks is Load.Failed || page.albums is Load.Failed
        is ArtistPage -> page.top is Load.Failed || page.albums is Load.Failed || page.tracks is Load.Failed
        is ListPage -> page.items is Load.Failed
        is ServicesPage -> page.services is Load.Failed
        is AddServicePage -> page.kinds is Load.Failed
        is ServicePage -> page.service is Load.Failed
        // Un flusso non si ricomincia da solo: lo si rifà a mano.
        is FlowPage, is SearchPage, is SettingsPage -> false
    }

    // ---------- Immagini ----------

    /** L'indirizzo con cui sono state salvate le schermate mostrate prima di collegarsi. */
    private var savedBase: String? = null

    /**
     * L'indirizzo di un'immagine di Music Assistant alla misura chiesta (256 o 512). Prima di
     * collegarsi vale quello delle schermate salvate: Coil le ha già sul disco.
     */
    fun image(image: MaImage?, size: Int): String? {
        image ?: return null
        val base = repo.baseUrl ?: savedBase ?: return null
        return MaRepository.imageUrl(base, image, size)
    }

    companion object {
        const val RECENT = 30
        const val MAX_QUEUE = 200
        const val MIN_QUERY = 2
        const val SEARCH_PAUSE_MS = 350L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val READY_TIMEOUT_MS = 20_000L
        const val LYRICS_KEPT = 20

        /** Per quanto, dopo una modifica da qui, i brani di una playlist si chiedono senza cache. */
        const val EDITED_MS = 10 * 60_000L
        const val LYRICS_WAIT_MS = 8_000L
        const val STALE_MS = 10 * 60_000L
        const val HOME_CACHE = "home"
        const val SHELVES_CACHE = "shelves"

        val TRACK_LIKE = setOf("track", "podcast_episode", "audiobook")

        /** Nella scelta "Suona su", il telefono stesso. */
        const val PHONE = "phone"
        const val MOVE_SETTLE_MS = 5_000L

        /** La password più corta che Music Assistant accetta per un account nuovo. */
        const val MIN_PASSWORD = 8

        /** Il nome dell'account che Xonos si crea nel Music Assistant sul telefono. */
        const val PHONE_SERVER_USER = "xonos"
        private const val PROGRESS_STEP = 512L * 1024
        private const val PASSWORD_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Problemi che vengono dal server stesso: provare l'altro indirizzo non cambierebbe niente. */
        private val ANSWERED = setOf(Problem.BAD_LOGIN, Problem.SETUP_REQUIRED, Problem.REFUSED, Problem.DENIED, Problem.TOO_OLD)
    }
}

/** Il testo di un brano mentre arriva: Ready(null) se il brano non ne ha. */
class LyricsState {
    var load by mutableStateOf<Load<Lyrics?>>(Load.Loading)
}
