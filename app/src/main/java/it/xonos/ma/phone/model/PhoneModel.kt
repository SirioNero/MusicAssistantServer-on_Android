package it.xonos.ma.phone.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.xonos.ma.data.Account
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Device
import it.xonos.ma.data.Devices
import it.xonos.ma.data.HomeSnapshot
import it.xonos.ma.data.LastListen
import it.xonos.ma.data.LastListenStore
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
import it.xonos.ma.data.cachedItems
import it.xonos.ma.data.str
import it.xonos.ma.data.toCache
import it.xonos.ma.data.taste.JournalCounts
import it.xonos.ma.playback.Listening
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
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

    /**
     * Vero mentre una scheda ha un flusso per aggiungere o configurare un servizio ([FlowPage]), anche sotto altre
     * schermate o in una scheda non scelta: il flusso è ancora aperto in Music Assistant (per esempio mentre la persona
     * entra in Spotify nel browser).
     */
    val inSetupFlow: Boolean get() = stacks.values.any { stack -> stack.any { it is FlowPage } }

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
        val top = stack.last()
        when {
            playerOpen -> playerOpen = false
            // Un flusso a metà: prima si chiede. Mentre si chiede, indietro chiude la domanda (lo fa la finestra del
            // dialogo, come Continua): si esce solo con Interrompi.
            stack.size > 1 && top is FlowPage && services.asksBeforeLeaving(top) -> top.confirmLeave = true
            stack.size > 1 -> removed(stack.removeAt(stack.lastIndex))
            tab != Tab.HOME -> tab = Tab.HOME
            else -> return false
        }
        return true
    }

    /** Confermato: si lascia il flusso a metà, come con indietro (Music Assistant lo chiude). */
    fun leaveFlow(page: FlowPage) {
        page.confirmLeave = false
        // Finito proprio adesso (il servizio c'è già): si esce come dalla sua fine, con l'elenco ricaricato.
        if (page.step.value?.done == true) return services.close(page)
        val stack = stacks.getValue(tab)
        if (stack.size > 1 && stack.last() === page) removed(stack.removeAt(stack.lastIndex))
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
            // Il server salvato (anche dopo averne toccato un altro): tornano il suo token e il suo accesso.
            val config = settings.load()
            page.token = config.token
            page.signedIn = settings.username
            page.useToken = config.token.isNotBlank() && settings.username == null
            page.remote = server.remoteUrl ?: config.remoteUrl
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

    // ---------- Home (0.1.58, vedi docs/per-te-piano.md, capitolo 2) ----------

    /**
     * La Home. Prima ciò che c'è sul telefono ([showLocal]: la Home salvata e la copia dell'ultimo ascolto, in pochi
     * millisecondi e anche col server fermo), poi Music Assistant ([refreshHome]). Aggiornandola ([refresh]) resta quello
     * che c'era finché non arriva il nuovo, e una sezione che non si carica non toglie quella di prima.
     */
    private fun loadHome(page: HomePage, refresh: Boolean = false) {
        page.loadedAt = System.currentTimeMillis()
        if (refresh) page.refreshing = true
        page.job = scope.launch {
            val keep = refresh || showLocal(page)
            refreshHome(page, keep)
            page.refreshing = false
            page.shown = true
            page.fresh = page.recent is Load.Ready && page.rows.value?.none { it.items is Load.Loading } == true
            saveHome(page)
        }
    }

    /**
     * Ciò che la Home sa senza Music Assistant: la Home salvata l'ultima volta (versione 2, o la 1 delle versioni di
     * prima) e la copia dell'ultimo ascolto per "Riprendi". Vero se si sono potute mostrare le sezioni: allora restano
     * finché non arrivano quelle nuove.
     */
    internal suspend fun showLocal(page: HomePage): Boolean {
        page.hiddenResume = settings.hiddenResume
        page.moreOpen = settings.homeMoreOpen
        val (saved, listen) = withContext(Dispatchers.IO) { cache.read(HOME_CACHE)?.let(HomeSnapshot::from) to readLastListen() }
        var lists = false
        if (saved != null) {
            savedBase = savedBase ?: saved.base
            page.serverId = page.serverId ?: saved.serverId
            page.queueResume = saved.resume
            if (saved.recent.isNotEmpty() || saved.rows.isNotEmpty()) {
                val rows = saved.rows.map { HomePage.Row(it.row, it.items?.let { items -> Load.Ready(items) } ?: Load.Loading) }
                page.recent = Load.Ready(saved.recent)
                page.rows = Load.Ready(rows.filter { it.place.section in SHOWN_SECTIONS })
                page.more = rows.filter { it.place.section == HomeSection.OTHER }
                page.inProgress = Load.Ready(saved.inProgress)
                page.mixes = saved.mixes
                page.genres = saved.genres
                lists = true
            }
        }
        showResume(page, listen)
        if (lists || page.resume != null) page.shown = true
        // La copia la apre il servizio appena parte (lo schermo lo fa partire collegandosi): se non c'è ancora, la si
        // aspetta un attimo, senza fermare il resto.
        if (lastListen() == null) {
            scope.launch {
                withTimeoutOrNull(LISTENING_WAIT_MS) { while (lastListen() == null) delay(100) } ?: return@launch
                val copy = withContext(Dispatchers.IO) { readLastListen() } ?: return@launch
                showResume(page, copy)
                page.shown = true
            }
        }
        return lists
    }

    /** La copia dell'ultimo ascolto, se il servizio l'ha già aperta. Fuori dal thread principale. */
    private fun readLastListen(): LastListen? = try {
        lastListen()?.read()
    } catch (e: Exception) {
        null
    }

    /**
     * "Riprendi": la copia sul telefono [local] o quella della coda di Music Assistant, la più recente delle due, se è
     * di questo server. Finché il server non si sa (la prima apertura dopo l'aggiornamento, col server fermo) si mostra la
     * più recente: appena collegati si controlla.
     */
    private fun showResume(page: HomePage, local: LastListen?) {
        val serverId = connectedServerId() ?: page.serverId
        page.resume = if (serverId == null) {
            listOfNotNull(local, page.queueResume).maxByOrNull { it.timeMs }
        } else {
            ResumePlan.shown(local, page.queueResume, serverId)
        }
    }

    /** Il Music Assistant collegato adesso (server_id); null se non è collegato. */
    private fun connectedServerId(): String? = (repo.connection.value as? Connection.Connected)?.server?.id?.takeIf { it.isNotEmpty() }

    /**
     * Tutto quello che la Home chiede a Music Assistant, insieme: le Scelte rapide, le righe, In corso, le Infinite Mix,
     * i generi e la coda del telefono per "Riprendi". Nessuna di queste chiama Spotify (le righe di "Altro" sì, alcune:
     * per questo si chiedono solo con "Altro" aperta). Con [keep] un errore lascia quello che si vedeva.
     */
    private suspend fun refreshHome(page: HomePage, keep: Boolean) = coroutineScope {
        if (!keep) {
            page.recent = Load.Loading
            page.rows = Load.Loading
            page.inProgress = Load.Loading
        }
        launch {
            try {
                awaitConnected()
                page.recent = Load.Ready(library.recentContexts())
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
        launch {
            try {
                awaitConnected()
                loadInProgress(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!keep || page.inProgress !is Load.Ready) page.inProgress = Load.Failed(e)
            }
        }
        // Mix, generi e coda sono un di più: se non arrivano resta quello di prima.
        launch { quietly { page.mixes = infiniteMixes() } }
        launch { quietly { page.genres = library.genreTiles() } }
        launch { quietly { loadQueueResume(page) } }
    }

    /** Collegati, [block]; un errore non ferma la Home (resta quello che c'era). */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            awaitConnected()
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Niente da dire: la sezione resta com'era.
        }
    }

    private suspend fun saveHome(page: HomePage) {
        val base = repo.baseUrl ?: return
        val recent = page.recent.value ?: return
        val rows = page.rows.value?.mapNotNull { row -> row.items.value?.let { HomeSnapshot.Row(row.row, it) } } ?: return
        val more = page.more.map { HomeSnapshot.Row(it.row, it.items.value) }
        val snapshot = HomeSnapshot(
            base = base,
            serverId = page.serverId ?: connectedServerId(),
            timeMs = System.currentTimeMillis(),
            recent = recent,
            rows = rows + more,
            inProgress = page.inProgress.value.orEmpty(),
            mixes = page.mixes,
            genres = page.genres,
            resume = page.resume,
        )
        withContext(Dispatchers.IO) { cache.write(HOME_CACHE, snapshot.toJson()) }
    }

    /**
     * Le righe di Music Assistant, ognuna al suo posto (HomeRows). Quelle di "Riprendi" e delle Scelte rapide non
     * servono (la Home le fa da sé); quelle di "Altro" si caricano solo aperta, e intanto tengono quello di prima.
     */
    private suspend fun loadRows(page: HomePage, refresh: Boolean) {
        val all = HomeRows.arrange(library.recommendations()).map { placed ->
            HomePage.Row(placed.row, if (placed.row.inline.isNotEmpty()) Load.Ready(placed.row.inline) else Load.Loading)
        }
        val rows = all.filter { it.place.section in SHOWN_SECTIONS }
        val before = page.more.associateBy { rowKey(it.row) }
        val more = all.filter { it.place.section == HomeSection.OTHER }.onEach { row ->
            val old = before[rowKey(row.row)]?.items
            if (row.items is Load.Loading && old is Load.Ready) row.items = old
        }
        // La prima volta le righe compaiono man mano; aggiornando, tutte insieme alla fine.
        if (!refresh) page.rows = Load.Ready(rows)
        page.more = more
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
        if (page.moreOpen) loadMoreRows(page)
    }

    /** "Altro" aperta o chiusa (si ricorda); aprendola le sue righe si caricano. */
    fun toggleMore(page: HomePage) {
        page.moreOpen = !page.moreOpen
        settings.homeMoreOpen = page.moreOpen
        if (page.moreOpen && (System.currentTimeMillis() - page.moreLoadedAt > STALE_MS || page.more.any { it.items !is Load.Ready })) {
            loadMoreRows(page)
        }
    }

    /** Il contenuto delle righe di "Altro". Una riga che non arriva tiene quello di prima, o sparisce. */
    private fun loadMoreRows(page: HomePage) {
        page.moreJob?.cancel()
        page.moreJob = scope.launch {
            try {
                awaitConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                page.more.filter { it.items is Load.Loading }.forEach { it.items = Load.Failed(e) }
                return@launch
            }
            coroutineScope {
                page.more.map { row ->
                    async {
                        row.items = try {
                            Load.Ready(library.recommendationItems(row.row))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            row.items.takeIf { it is Load.Ready } ?: Load.Failed(e)
                        }
                    }
                }.awaitAll()
            }
            page.moreLoadedAt = System.currentTimeMillis()
            if (page.job?.isActive != true) saveHome(page)
        }
    }

    /**
     * In corso: episodi e audiolibri a metà. Music Assistant non dice dove si era arrivati: lo si chiede (item_by_uri)
     * per le prime [HomePage.IN_PROGRESS_DETAILS] card, quelle che si vedono, mai per gli episodi di Spotify (sarebbe una
     * richiesta a Spotify per card). Intanto valgono le posizioni che si sapevano già.
     */
    private suspend fun loadInProgress(page: HomePage) {
        val items = library.inProgress()
        val known = page.inProgress.value.orEmpty().associateBy { it.uri }
        page.inProgress = Load.Ready(items.map { item -> known[item.uri]?.let(item::withProgressOf) ?: item })
        val detailed = coroutineScope {
            items.mapIndexed { i, item ->
                async {
                    if (i >= HomePage.IN_PROGRESS_DETAILS || item.providerDomain == SPOTIFY) return@async null
                    try {
                        library.item(item.uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        page.inProgress = Load.Ready(
            items.mapIndexed { i, item -> detailed[i]?.let(item::withProgressOf) ?: known[item.uri]?.let(item::withProgressOf) ?: item },
        )
    }

    /** Le playlist Infinite Mix di Music Assistant (del servizio builtin, se la persona non le ha spente). */
    private suspend fun infiniteMixes(): List<MaItem> =
        repo.items("music/playlists/library_items", 30_000, MaItem::from, {
            put("provider", BUILTIN)
            put("limit", MIX_LOOKUP)
            put("order_by", "sort_name")
        }).filter(HomeRows::isInfiniteMix).sortedBy { HomePage.mixKind(it) != HomePage.MIX_LIBRARY }

    /** La coda del telefono in Music Assistant, come "Riprendi": con la copia sul telefono vince la più recente. */
    private suspend fun loadQueueResume(page: HomePage) {
        val serverId = connectedServerId() ?: return
        page.serverId = serverId
        val queueId = queueId()
        val queue = library.queue(queueId)
        val index = queue?.currentIndex
        val next = if (queue?.current != null && index != null) {
            try {
                library.queueItems(queueId, index + 1, LastListen.NEXT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        page.queueResume = queue?.let { LastListen.of(serverId, it, next, it.resumeSec, (it.elapsedUpdatedAt * 1000).toLong()) }
        showResume(page, withContext(Dispatchers.IO) { readLastListen() })
    }

    /** Il telefono ha smesso di suonare: il servizio ha appena scritto la copia, e "Riprendi" la mostra. */
    private fun homeAfterPlaying() {
        val home = stacks.getValue(Tab.HOME).first() as HomePage
        if (!home.started) return
        scope.launch {
            delay(COPY_SETTLE_MS)
            showResume(home, withContext(Dispatchers.IO) { readLastListen() })
        }
    }

    // ---------- Riprendi ----------

    /** Vero mentre il telefono suona (lo dice lo schermo): allora la Home non mostra "Riprendi", c'è il mini-lettore. */
    var phoneIsPlaying by mutableStateOf(false)
        private set

    /**
     * La copia dell'ultimo ascolto (`last-listen.json`): quella dei file del servizio ([listening]). Le prove e le
     * schermate la cambiano.
     */
    var lastListen: () -> LastListenStore? = { listening()?.lastListen }

    private var resumeJob: Job? = null

    /** Si vede "Riprendi": c'è un ascolto recente, il telefono non suona e la persona non l'ha nascosto. */
    fun resumeVisible(page: HomePage, nowMs: Long = System.currentTimeMillis()): Boolean =
        ResumePlan.visible(page.resume, nowMs, phoneIsPlaying, page.hiddenResume)

    /**
     * "Riprendi" (o "Ascolta di nuovo"), e "Ricomincia" con [restart]: ResumePlan decide con la coda di Music Assistant
     * di adesso, poi i suoi comandi. Come "suona": si aspetta che il telefono sia un lettore (col server sul telefono
     * fino a un minuto, se sta ripartendo), intanto la rotella; un secondo tocco mentre parte non conta.
     */
    fun resume(page: HomePage, restart: Boolean = false) {
        val listen = page.resume ?: return
        if (resumeJob?.isActive == true) return
        starting = true
        resumeJob = scope.launch {
            try {
                awaitReady()
                val queueId = queueId()
                val serverId = connectedServerId().orEmpty()
                val queue = try {
                    library.queue(queueId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val step = if (restart) {
                    ResumePlan.restart(listen, queue, queueId, serverId)
                } else {
                    ResumePlan.decide(listen, queue, queueId, serverId)
                }
                // Un episodio o un audiolibro Music Assistant lo fa ripartire da dove era arrivato: play_index senza punto
                // (0) vuol dire "dal punto ricordato", e la voce della coda tiene il punto di quando è entrata. Sulla stessa
                // voce si chiede il secondo 1; rifacendo la coda, prima gli si fa dimenticare il punto (provato in Docker).
                val spokenRestart = restart && listen.spoken
                when {
                    step == null -> Unit
                    spokenRestart && step is ResumeStep.PlayIndex -> library.playIndex(step.queueId, step.queueItemId, SPOKEN_RESTART_S)
                    spokenRestart -> {
                        uriRef(listen.track.uri, listen.track.mediaType, listen.track.name)?.let { ref ->
                            repo.command("music/mark_unplayed") { put("media_item", ref) }
                        }
                        step.run(library)
                    }
                    else -> step.run(library)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                starting = pendingPlay != null
            }
        }
    }

    /** "Apri": da dove suonava l'ultimo ascolto (album, playlist, artista...), o l'album del brano. */
    fun openResume(page: HomePage) {
        val uri = resumeContext(page.resume) ?: return
        scope.launch {
            try {
                awaitConnected()
                library.item(uri)?.let(::open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            }
        }
    }

    /** "Nascondi": l'eroe sparisce fino al prossimo ascolto; per qualche secondo si può annullare. */
    fun hideResume(page: HomePage) {
        val key = page.resume?.key ?: return
        settings.hiddenResume = key
        page.hiddenResume = key
        page.justHidden = key
        scope.launch {
            delay(UNDO_MS)
            if (page.justHidden == key) page.justHidden = null
        }
    }

    fun unhideResume(page: HomePage) {
        settings.hiddenResume = null
        page.hiddenResume = null
        page.justHidden = null
    }

    // ---------- In corso ----------

    /** I "segna come ascoltato" in viaggio, per uri: "Annulla" aspetta che il primo sia arrivato. */
    private val marking = mutableMapOf<String, Job>()

    /** "Segna come ascoltato": subito; la card resta qualche secondo con "Annulla", poi sparisce. */
    fun markPlayed(page: HomePage, item: MaItem) {
        if (item.uri in page.marked) return
        page.marked[item.uri] = (item.resumeMs / 1000).toInt()
        marking[item.uri] = scope.launch {
            try {
                awaitConnected()
                repo.command("music/mark_played") {
                    put("media_item", itemRef(item))
                    put("fully_played", true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                page.marked.remove(item.uri)
                notice = Notice.Failed(e)
            }
        }
        scope.launch {
            delay(UNDO_MS)
            if (page.marked.remove(item.uri) != null) {
                page.inProgress.value?.let { list -> page.inProgress = Load.Ready(list.filter { it.uri != item.uri }) }
            }
        }
    }

    /** "Annulla": torna a metà, dove era arrivato. */
    fun undoMarkPlayed(page: HomePage, item: MaItem) {
        val seconds = page.marked.remove(item.uri) ?: return
        scope.launch {
            try {
                // Due comandi insieme Music Assistant li fa in parallelo: prima deve finire il primo.
                marking.remove(item.uri)?.join()
                awaitConnected()
                repo.command("music/mark_played") {
                    put("media_item", itemRef(item))
                    put("fully_played", false)
                    put("seconds_played", seconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            }
        }
    }

    /** "Dall'inizio": Music Assistant dimentica dove si era arrivati (mark_unplayed), poi suona da capo. */
    fun playFromStart(item: MaItem) {
        scope.launch {
            try {
                awaitConnected()
                repo.command("music/mark_unplayed") { put("media_item", itemRef(item)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
                return@launch
            }
            play(item)
        }
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
        if (home.started && System.currentTimeMillis() - home.loadedAt > STALE_MS) {
            refresh(home)
        } else if (home.started) {
            // Anche senza aggiornare: la copia dell'ultimo ascolto può essere cambiata (la musica dall'auto, da myCar).
            scope.launch { showResume(home, withContext(Dispatchers.IO) { readLastListen() }) }
        }
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

    /** Un artista: i più ascoltati e gli album. Tutti i brani solo quando si aprono ([openArtistTracks]). */
    private fun loadArtist(page: ArtistPage) {
        val artist = page.artist
        page.top = Load.Loading
        page.albums = Load.Loading
        page.albumsMore = false
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
        page.more = false
        val part = parts({ page.more || page.items is Load.Loading }) { page.more = true; page.items = Load.Ready(it) }
        launchLoad({ error -> page.more = false; partFailed(error, page.items is Load.Ready) { page.items = Load.Failed(error) } }) {
            val all = page.source(part)
            page.more = false
            page.items = Load.Ready(all)
        }
    }

    /**
     * I brani più ascoltati ([top]) o tutti i brani di un artista, su una schermata loro. Tutti i brani si chiedono
     * solo qui (prima la libreria, poi ogni servizio, a pezzi), e restano nella pagina dell'artista.
     */
    fun openArtistTracks(page: ArtistPage, title: String, top: Boolean) {
        val artist = page.artist
        if (top) {
            val tracks = page.top.value
            push(ListPage(title, artist.name, artist.image) { tracks ?: library.artistTopTracks(artist) })
            return
        }
        val tracks = page.tracks?.value
        push(
            ListPage(title, artist.name, artist.image) { part ->
                // Arriva sul thread di [scope] (launchLoad), come gli altri caricamenti.
                tracks ?: library.artistTracks(artist, part).also { page.tracks = Load.Ready(it) }
            },
        )
    }

    /** Una riga di "Per te" intera, col titolo di Xonos ([title]; di base quello di Music Assistant). */
    fun openRow(row: HomePage.Row, title: String = row.row.name) {
        val items = row.items.value
        // Il sottotitolo di Music Assistant va solo con il suo nome: accanto al titolo di Xonos resterebbe in inglese.
        val subtitle = row.row.subtitle.takeIf { !row.place.known }
        push(ListPage(title, subtitle, null) { items ?: library.recommendationItems(row.row) })
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
     * Nei servizi si cerca solo dopo una pausa più lunga ([EVERYWHERE_PAUSE_MS]): ogni ricerca costa richieste a
     * Spotify, che blocca chi ne fa troppe, e scrivendo ogni lettera ne faceva una nuova. Con [now] si cerca subito
     * dappertutto (per il tasto cerca della tastiera).
     */
    fun search(page: SearchPage, text: String, now: Boolean = false) {
        page.query = text
        page.job?.cancel()
        val query = text.trim()
        if (query.length < MIN_QUERY) {
            page.results = null
            page.searching = false
            return
        }
        page.job = scope.launch {
            if (!now) delay(SEARCH_PAUSE_MS)
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
                        // Scrivendo ancora, la ricerca si cancella prima di arrivare ai servizi.
                        if (!now) delay(EVERYWHERE_PAUSE_MS - SEARCH_PAUSE_MS)
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
                    starting = resumeJob?.isActive == true
                }
            }
        }
    }

    /**
     * Aspetta che il telefono sia un lettore di Music Assistant: appena aperta l'app ci vuole un attimo. Col server sul
     * telefono di più: fermato perché nessuno lo usava, riparte quando si torna nell'app.
     */
    private suspend fun awaitReady() {
        withTimeoutOrNull(if (onPhoneServer) PHONE_READY_TIMEOUT_MS else READY_TIMEOUT_MS) { ready.first { it } }
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
        val stopped = phoneIsPlaying && !playing
        phoneIsPlaying = playing
        if (stopped) homeAfterPlaying()
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

    // ---------- Impostazioni › Per te (0.1.58): la cronologia degli ascolti ----------
    // Tutto qui, in un blocco solo: la pagina sta sopra le impostazioni (vedi SettingsScreen.PerTeScreen), i file sono
    // quelli del servizio (playback/Listening.kt).

    /** Lo stato della pagina "Per te" delle impostazioni. */
    class PerTeSettings internal constructor(
        enabled: Boolean,
        /** Le impostazioni da cui si è aperta (il suo [Page.id]): sopra altre impostazioni non si mostra. */
        val over: Long,
    ) {
        /** La cronologia degli ascolti accesa. */
        var enabled by mutableStateOf(enabled)
            internal set

        /** I numeri del diario (di tutti i Music Assistant su questo telefono). */
        var counts by mutableStateOf<Load<JournalCounts>>(Load.Loading)
            internal set

        /** La domanda prima di cancellare. */
        var confirmClear by mutableStateOf(false)
            internal set
        var clearing by mutableStateOf(false)
            internal set

        /** Appena cancellata: lo si dice nella pagina. */
        var cleared by mutableStateOf(false)
            internal set

        /** Quanti mesi copre il diario, contando anche quello del primo ascolto e quello di adesso (0 se è vuoto). */
        fun months(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
            val first = counts.value?.firstMs ?: return 0
            val from = YearMonth.from(Instant.ofEpochMilli(first).atZone(zone))
            val to = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
            return (ChronoUnit.MONTHS.between(from, to) + 1).toInt().coerceAtLeast(1)
        }
    }

    /** La pagina "Per te" delle impostazioni, aperta sopra di loro; null: chiusa. */
    var perTe by mutableStateOf<PerTeSettings?>(null)
        private set

    /**
     * I file degli ascolti: quelli che il servizio apre appena parte (lo schermo lo fa partire subito, collegandosi al
     * lettore). Null finché non ci sono. Le prove lo cambiano.
     */
    var listening: () -> Listening? = { Listening.shared }

    fun openPerTe(page: SettingsPage) {
        val state = PerTeSettings(settings.journalEnabled, page.id)
        perTe = state
        loadPerTe(state)
    }

    fun closePerTe() {
        perTe = null
    }

    /** Accende o spegne la cronologia: spenta, Xonos non scrive più ascolti (nemmeno quello in corso). */
    fun setJournal(state: PerTeSettings, on: Boolean) {
        settings.journalEnabled = on
        state.enabled = on
        state.cleared = false
    }

    fun askClearJournal(state: PerTeSettings) {
        state.confirmClear = true
    }

    fun dismissClearJournal(state: PerTeSettings) {
        state.confirmClear = false
    }

    /** "Cancella la cronologia", dopo la conferma: tutti gli ascolti, l'ascolto in corso e i viaggi in auto. */
    fun clearJournal(state: PerTeSettings) {
        state.confirmClear = false
        if (state.clearing) return
        state.clearing = true
        scope.launch {
            try {
                val files = awaitListening() ?: throw IllegalStateException("Diario non disponibile")
                files.forget()
                state.cleared = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice.Failed(e)
            } finally {
                state.clearing = false
            }
            loadPerTe(state)
        }
    }

    private fun loadPerTe(state: PerTeSettings) {
        scope.launch {
            state.counts = try {
                val files = awaitListening() ?: throw IllegalStateException("Diario non disponibile")
                Load.Ready(files.counts())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
        }
    }

    /** I file degli ascolti, aspettando un attimo il servizio se sta partendo. */
    private suspend fun awaitListening(): Listening? =
        listening() ?: withTimeoutOrNull(LISTENING_WAIT_MS) {
            while (listening() == null) delay(100)
            listening()
        }

    // ---------- Fine del blocco "Per te" ----------

    // ---------- Music Assistant sul telefono ----------

    /**
     * Avvisa chi tiene acceso il server sul telefono (ServerKeeper) che lo si avvia qui, fuori da lui (alla fine del
     * download, "Riprova"): un suo arresto in arrivo non deve fermarlo mentre parte, e dopo deve poterlo fermare.
     */
    var keepServer: () -> Unit = {}

    /** Xonos può far girare Music Assistant su questo telefono (a 64 bit), scaricandolo su richiesta. */
    val phoneServerAvailable: Boolean get() = phoneServer != null

    /** Il pacchetto c'era ma per una versione precedente di Xonos: va riscaricato. */
    val phoneServerOutdated: Boolean get() = phoneServer?.outdated == true

    /**
     * Xonos usa il Music Assistant sul telefono e quello sta ancora partendo (all'apertura dell'app ci vuole un po').
     * Anche fermo, se il pacchetto c'è: quando nessuno lo usa ServerKeeper lo ferma, e all'apertura riparte da solo.
     */
    val phoneServerStarting: Boolean
        get() = onPhoneServer && phoneServer?.let {
            it.state == PhoneServerControl.State.STARTING ||
                (it.state == PhoneServerControl.State.STOPPED && it.installed && !it.outdated)
        } == true

    /** Xonos usa il Music Assistant sul telefono e quello non è partito o si è fermato: il perché. */
    val phoneServerFailure: String?
        get() = phoneServer?.takeIf { onPhoneServer && it.state == PhoneServerControl.State.FAILED }
            ?.let { it.error.ifBlank { it.state.name } }

    /** Mentre "Riprova" fa ripartire il server sul telefono: la Home mostra subito la rotella, e un altro tocco non conta. */
    var phoneServerRetrying by mutableStateOf(false)
        private set

    /** "Riprova" quando il server sul telefono non è partito (dopo qualche tentativo Xonos smette da solo). */
    fun retryPhoneServer() {
        val server = phoneServer ?: return
        if (phoneServerRetrying) return
        phoneServerRetrying = true
        scope.launch {
            try {
                keepServer()
                if (server.start() && repo.connection.value !is Connection.Connected) repo.reconnect()
            } finally {
                phoneServerRetrying = false
            }
        }
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
                keepServer()
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
        // Un altro server: le schermate salvate sono dell'altro, e anche quelle aperte. Non se cambia solo l'indirizzo
        // da fuori casa dello stesso server (si perdevano le schede aperte e "Suona su").
        if (Settings.normalizeUrl(config.homeUrl) != Settings.normalizeUrl(settings.load().homeUrl)) {
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
        // Anche i testi (per uri, che tra due server può essere lo stesso) e gli elenchi aperti di casse e playlist.
        lyrics.clear()
        picker = null
        playlistPicker = null
        for ((t, stack) in stacks) {
            val keep = settings.takeIf { it in stack }
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
        // Arriva sul thread di OkHttp: si passa a quello dello schermo.
        repo.addEventListener { event, _, _ -> if (event == SYNC_COMPLETED) scope.launch { libraryChanged() } }
    }

    /**
     * Music Assistant ha finito di leggere la libreria (un servizio appena aggiunto, la cartella Music del telefono):
     * Home e Libreria già aperte mostrano la musica nuova da sole. Prima la Home vuota continuava a dire "Aggiungi un
     * servizio" finché non la si trascinava in giù.
     */
    private fun libraryChanged() {
        for (page in stacks.values.flatten()) {
            if (!page.started) continue
            when (page) {
                is HomePage -> if (page.job?.isActive != true) loadHome(page, refresh = true)
                is LibraryPage -> {
                    loadShelves(page)
                    page.lists.values.forEach { refresh(it, quiet = true) }
                }
                else -> Unit
            }
        }
    }

    private fun failed(page: Page): Boolean = when (page) {
        // Anche una Home mostrata dal disco e mai aggiornata, se non ci sta già provando.
        is HomePage -> page.job?.isActive != true && (page.recent is Load.Failed || page.rows is Load.Failed || !page.fresh)
        is LibraryPage -> page.lists.values.any { it.error != null }
        is ItemPage -> page.tracks is Load.Failed || page.albums is Load.Failed
        is ArtistPage -> page.top is Load.Failed || page.albums is Load.Failed
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

        /** Quanto si aspetta senza scrivere prima di cercare anche nei servizi (Spotify...): circa un secondo. */
        const val EVERYWHERE_PAUSE_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val READY_TIMEOUT_MS = 20_000L

        /** Lo stesso col Music Assistant sul telefono, che può star ripartendo (vedi [awaitReady]). */
        const val PHONE_READY_TIMEOUT_MS = 60_000L
        const val LYRICS_KEPT = 20

        /** Per quanto, dopo una modifica da qui, i brani di una playlist si chiedono senza cache. */
        const val EDITED_MS = 10 * 60_000L
        const val LYRICS_WAIT_MS = 8_000L
        const val STALE_MS = 10 * 60_000L
        const val HOME_CACHE = "home"

        /** Le sezioni della Home che hanno righe di Music Assistant da caricare subito (le altre: "Altro", a richiesta). */
        val SHOWN_SECTIONS = setOf(HomeSection.PER_TE, HomeSection.REDISCOVER, HomeSection.LIBRARY)

        /** Per quanto si può annullare "Nascondi" e "Segna come ascoltato". */
        const val UNDO_MS = 6_000L

        /** Quanto si aspetta, dopo che il telefono ha smesso di suonare, che il servizio scriva la copia dell'ultimo ascolto. */
        const val COPY_SETTLE_MS = 1_500L

        /** Il servizio builtin di Music Assistant (le sue playlist, tra cui le Infinite Mix). */
        private const val BUILTIN = "builtin"
        private const val SPOTIFY = "spotify"

        /** Quante playlist del servizio builtin si guardano per trovare le Infinite Mix (sono 8, più quelle della persona). */
        private const val MIX_LOOKUP = 100

        /** "Ricomincia" un episodio sulla stessa voce della coda: dal secondo 1 (lo 0 per Music Assistant è "riprendi"). */
        private const val SPOKEN_RESTART_S = 1

        /** Le sorgenti di un ascolto che "Apri" apre: le altre (radio, audiolibro) si suonerebbero. */
        private val OPENABLE = setOf("album", "playlist", "artist", "podcast", "genre")

        /**
         * Cosa apre "Apri" sull'eroe: la sorgente se si può aprire, sennò l'album del brano; per un episodio scelto da solo
         * il suo podcast (nella copia sta al posto dell'artista). Null se niente.
         */
        fun resumeContext(listen: LastListen?): String? {
            listen ?: return null
            listen.source?.takeIf { it.mediaType in OPENABLE }?.let { return it.uri }
            listen.track.album?.let { return it.uri }
            return listen.track.artists.firstOrNull { it.uri.substringAfter("://").startsWith("podcast/") }?.uri
        }

        /** Un elemento di cui si sa solo l'uri ("servizio://tipo/id"), come lo vogliono i comandi di Music Assistant. */
        private fun uriRef(uri: String, mediaType: String, name: String): JSONObject? {
            val provider = uri.substringBefore("://", "").ifEmpty { return null }
            val id = uri.substringAfter("://").substringAfter("/", "").ifEmpty { return null }
            return JSONObject().put("media_type", mediaType).put("item_id", id).put("provider", provider).put("name", name).put("uri", uri)
        }

        /** Un elemento come lo vuole Music Assistant nei comandi che prendono un media_item (un ItemMapping). */
        private fun itemRef(item: MaItem): JSONObject = JSONObject()
            .put("media_type", item.mediaType)
            .put("item_id", item.itemId)
            .put("provider", item.provider)
            .put("name", item.name)
            .put("uri", item.uri)

        /** La chiave di una riga: servizio e item_id. */
        private fun rowKey(row: MaItem) = "${row.provider}|${row.itemId}"
        const val SHELVES_CACHE = "shelves"

        val TRACK_LIKE = setOf("track", "podcast_episode", "audiobook")

        /** Nella scelta "Suona su", il telefono stesso. */
        const val PHONE = "phone"
        const val MOVE_SETTLE_MS = 5_000L

        /** La password più corta che Music Assistant accetta per un account nuovo. */
        const val MIN_PASSWORD = 8

        /** L'evento di Music Assistant quando ha finito di leggere la libreria dai servizi. */
        const val SYNC_COMPLETED = "music_sync_completed"

        /** Il nome dell'account che Xonos si crea nel Music Assistant sul telefono. */
        const val PHONE_SERVER_USER = "xonos"

        /** Quanto la pagina "Per te" aspetta i file degli ascolti, se il servizio sta partendo. */
        const val LISTENING_WAIT_MS = 5_000L
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

/** Un episodio o audiolibro di "In corso" con la posizione e la durata di [full] (da item_by_uri). */
private fun MaItem.withProgressOf(full: MaItem): MaItem = copy(
    resumeMs = full.resumeMs,
    fullyPlayed = full.fullyPlayed,
    durationSec = full.durationSec.takeIf { it > 0 } ?: durationSec,
    subtitle = subtitle ?: full.subtitle,
    image = image ?: full.image,
)
