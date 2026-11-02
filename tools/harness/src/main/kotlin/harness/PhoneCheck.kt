package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.ArtistPage
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.ListPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.ORDER_ADDED
import it.xonos.ma.phone.model.FoundServer
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.SearchPage
import it.xonos.ma.phone.model.SettingsPage
import it.xonos.ma.phone.model.Shelf
import it.xonos.ma.phone.model.Tab
import it.xonos.ma.phone.model.value
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * L'app del telefono senza schermo: il PhoneModel contro un Music Assistant vero, come se si
 * toccassero le schermate. Controlla caricamenti, pagine della libreria, ricerca, riproduzione,
 * coda, preferiti e testi.
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val playerId = "xonos-phonecheck"
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }

    // Il telefono come lettore, come fa il servizio sul telefono.
    val outputs = mutableListOf<FakeOutput>()
    val engine = AudioEngine({ r, c -> FakeOutput(r, c).also { outputs += it } }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val identity = SendspinClient.Identity(playerId, "Prova telefono", "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), 800, 300, 50, false)
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }
    val ready = MutableStateFlow(false)
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    ready.value = true

    // Un solo thread per il modello, come il thread principale del telefono.
    val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // Le schermate salvate vanno in una cartella temporanea: alla fine le legge una seconda app senza server.
    val cache = ScreenCache(Files.createTempDirectory("xonos-cache").toFile())
    val model = PhoneModel(repo, library, settings, cache, FixedFinder(), "Xonos (prova)", { playerId }, ready, CoroutineScope(SupervisorJob() + main))
    var failures = 0

    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    suspend fun <T> loaded(what: () -> Load<T>?): T? = withTimeoutOrNull(60_000) {
        while (true) {
            when (val load = what()) {
                is Load.Ready -> return@withTimeoutOrNull load.value
                is Load.Failed -> {
                    println("   errore: ${load.error.message}")
                    return@withTimeoutOrNull null
                }
                else -> delay(50)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    suspend fun queue(): QueueState? = QueueState.from(repo.command("player_queues/get") { put("queue_id", playerId) } as? JSONObject)

    suspend fun settle(until: (QueueState?) -> Boolean): QueueState? {
        var q: QueueState? = null
        withTimeoutOrNull(10_000) {
            while (true) {
                q = queue()
                if (until(q)) break
                delay(200)
            }
        }
        return q
    }

    // ---------- Home ----------
    val home = model.page as HomePage
    model.start(home)
    val rows = loaded { home.rows }
    check("Home: righe di Per te", !rows.isNullOrEmpty(), "${rows?.size} righe: ${rows?.take(4)?.joinToString { it.row.name }}")
    delay(2_000)
    rows?.take(6)?.forEach { row -> println("   - ${row.row.name}: ${row.items.value?.size ?: row.items}") }
    check("Home: ascoltati di recente", loaded { home.recent } != null)
    model.refresh(home)
    check("Home: aggiornando resta quello di prima", home.refreshing && home.recent is Load.Ready && home.rows is Load.Ready)
    withTimeoutOrNull(30_000) { while (home.refreshing) delay(50) }
    check("Home: aggiornata", !home.refreshing && (home.rows as? Load.Ready)?.value?.size == rows?.size)

    // ---------- Libreria ----------
    model.select(Tab.LIBRARY)
    val lib = model.page as LibraryPage
    model.start(lib)
    withTimeoutOrNull(20_000) { while (lib.shelves == null) delay(50) }
    check("Libreria: voci non vuote", lib.shelves?.contains(Shelf.ALBUMS) == true && lib.shelves?.contains(Shelf.AUDIOBOOKS) == false, "${lib.shelves}")
    val tracks = model.shelf(lib, Shelf.TRACKS)
    model.loadMore(tracks)
    withTimeoutOrNull(20_000) { while (tracks.loading || tracks.items.isEmpty()) delay(50) }
    check("Libreria: prima pagina di brani", tracks.items.size == 100, "${tracks.items.size}, primo ${tracks.items.firstOrNull()?.name}")
    model.loadMore(tracks)
    withTimeoutOrNull(20_000) { while (tracks.loading || tracks.items.size < 200) delay(50) }
    check("Libreria: seconda pagina", tracks.items.size == 200 && tracks.items.distinctBy { it.uri }.size == 200)
    model.order(tracks, ORDER_ADDED)
    withTimeoutOrNull(20_000) { while (tracks.loading || tracks.items.isEmpty()) delay(50) }
    check("Libreria: ordine per data di aggiunta", tracks.items.size == 100, "primo ${tracks.items.firstOrNull()?.name}")
    model.loadMore(tracks)
    withTimeoutOrNull(20_000) { while (tracks.loading || tracks.items.size < 200) delay(50) }
    model.refresh(tracks)
    withTimeoutOrNull(20_000) { while (tracks.refreshing) delay(50) }
    check("Libreria: aggiornando si torna alla prima pagina", tracks.items.size == 100 && !tracks.end && tracks.error == null)
    val albums = model.shelf(lib, Shelf.ALBUMS)
    model.loadMore(albums)
    withTimeoutOrNull(20_000) { while (albums.loading || albums.items.isEmpty()) delay(50) }
    check("Libreria: prima pagina di album", albums.items.size == 100)
    withTimeoutOrNull(20_000) { while (albums.loading) delay(50) }
    model.loadMore(albums)
    withTimeoutOrNull(20_000) { while (albums.loading) delay(50) }
    check("Libreria: album finiti", albums.end && albums.items.size == 124, "${albums.items.size}")
    check("Libreria: copertina di un album", albums.items.first { it.name == "Emozioni" }.image?.proxyId != null)

    // ---------- Un album ----------
    val emozioni = albums.items.first { it.name == "Emozioni" }
    model.open(emozioni)
    val albumPage = model.page as ItemPage
    model.start(albumPage)
    val albumTracks = loaded { albumPage.tracks }.orEmpty()
    check("Album: brani in ordine", albumTracks.map { it.trackNumber } == listOf(1, 2, 3, 4), albumTracks.joinToString { "${it.trackNumber} ${it.name}" })
    check("Album: artisti e album dei brani", albumTracks.first().artists.firstOrNull()?.name == "Lucio Battisti" && albumTracks.first().album?.uri == emozioni.uri)
    model.play(albumTracks, 1, albumPage.context)
    var q = settle { it?.playing == true && it.current?.title == albumTracks[1].name }
    // Music Assistant lascia fuori i brani prima di quello scelto.
    check("Suona l'album dal secondo brano", q?.current?.title == albumTracks[1].name && q.items == 3, "${q?.state} ${q?.items} \"${q?.current?.title}\"")
    check("Brano in coda con album e artista", q?.current?.albumRef?.uri == emozioni.uri && q.current.artistRef?.name == "Lucio Battisti")

    // Testo sincronizzato del brano in corso.
    val lyricsState = model.lyrics(albumTracks[1].uri)
    val lyrics = loaded { lyricsState.load }
    check("Testo sincronizzato", lyrics?.synced == true && lyrics.lines.size == 10, "${lyrics?.lines?.take(2)}")
    check("Riga a 13 s", lyrics?.indexAt(13_000) == 2)

    // Suona dopo e in coda.
    val vasco = library.search("bollicine")["albums"]?.firstOrNull()
    check("Ricerca diretta: Bollicine", vasco != null)
    if (vasco != null) {
        model.playNext(vasco)
        q = settle { (it?.items ?: 0) >= 7 }
        check("Suona dopo: l'album entra in coda", q?.items == 7, "${q?.items} brani")
        val brava = library.search("brava")["tracks"]?.firstOrNull()
        brava?.let { model.addToQueue(it) }
        q = settle { (it?.items ?: 0) >= 8 }
        check("In coda: il brano va in fondo", q?.items == 8)
        println("   avviso: ${model.notice}")
        val items = repo.command("player_queues/items") { put("queue_id", playerId); put("limit", 20); put("offset", 0) } as org.json.JSONArray
        println("   coda: " + (0 until items.length()).joinToString { items.getJSONObject(it).optString("name") })
    }

    // Album mescolato.
    model.play(emozioni, shuffle = true)
    q = settle { it?.shuffle == true && it.items == 4 }
    check("Album mescolato", q?.shuffle == true && q.items == 4, "casuale ${q?.shuffle}, ${q?.items} brani")
    repo.command("player_queues/shuffle") { put("queue_id", playerId); put("shuffle_enabled", false) }

    // Testi semplici e senza testo.
    val mina = library.search("brava")["tracks"]?.first()
    val plain = mina?.let { loaded { model.lyrics(it.uri).load } }
    check("Testo semplice", plain?.synced == false && plain.lines.firstOrNull()?.text == "Brava", "${plain?.lines?.size} righe")
    val bollicine = library.search("vita spericolata")["tracks"]?.first()
    val tagged = bollicine?.let { loaded { model.lyrics(it.uri).load } }
    check("Testo LRC nel tag", tagged?.synced == true && tagged.lines.size == 10)
    // Senza testo Music Assistant lo cerca nei servizi di testi: senza rete ci mette molto.
    val west = library.search("armonica")["tracks"]?.firstOrNull()
    if (west != null) {
        val asked = System.nanoTime()
        val none = loaded { model.lyrics(west.uri).load }
        val waited = (System.nanoTime() - asked) / 1_000_000
        check("Senza testo: \"nessun testo\" entro ${PhoneModel.LYRICS_WAIT_MS / 1000} s", none == null && waited < PhoneModel.LYRICS_WAIT_MS + 2_000, "$waited ms")
    }

    // ---------- Artista ----------
    model.select(Tab.LIBRARY)
    val artists = model.shelf(lib, Shelf.ARTISTS)
    model.loadMore(artists)
    withTimeoutOrNull(20_000) { while (artists.loading || artists.items.isEmpty()) delay(50) }
    model.open(artists.items.first { it.name == "Vasco Rossi" })
    val artistPage = model.page as ArtistPage
    model.start(artistPage)
    // Prima arriva la libreria, poi i servizi: si aspetta l'elenco intero.
    val artistAlbums = loaded { artistPage.albums.takeUnless { artistPage.albumsMore } }.orEmpty()
    val artistTracks = loaded { artistPage.tracks.takeUnless { artistPage.tracksMore } }.orEmpty()
    check("Artista: album dal più recente", artistAlbums.firstOrNull()?.year == 2014 && artistAlbums.size == 5, artistAlbums.joinToString { "${it.name} ${it.year}" })
    check("Artista: tutti i brani", artistTracks.size == 28)
    model.openArtistTracks(artistPage, "Tutti i brani", top = false)
    val list = model.page as ListPage
    model.start(list)
    check("Artista: schermata dei brani", loaded { list.items }?.size == 28)
    model.play(artistTracks, 26, null)
    q = settle { it?.playing == true && it.current?.title == artistTracks[26].name }
    check("Brano 27 di 28: suonano lui e il successivo", q?.items == 2 && q.current?.title == artistTracks[26].name, "${q?.items} brani")

    // ---------- Podcast ----------
    val podcasts = model.shelf(lib, Shelf.PODCASTS)
    model.loadMore(podcasts)
    withTimeoutOrNull(20_000) { while (podcasts.loading || podcasts.items.isEmpty()) delay(50) }
    model.open(podcasts.items.first())
    val podcastPage = model.page as ItemPage
    model.start(podcastPage)
    val episodes = loaded { podcastPage.tracks }.orEmpty()
    check("Podcast: episodi", episodes.size == 8 && episodes.first().mediaType == "podcast_episode")
    model.play(episodes, 2, podcastPage.context)
    q = settle { it?.playing == true && it.current?.title == episodes[2].name }
    check("Podcast: suona l'episodio scelto", q?.current?.title == episodes[2].name, "${q?.items} in coda")

    // ---------- Una playlist lunga: i primi brani si vedono prima del resto ----------
    val big = library.libraryPage("playlists", 0, search = "Playlist grande").firstOrNull { it.name == "Playlist grande" }
    check("Playlist grande nella libreria", big != null)
    if (big != null) {
        model.open(big)
        val bigPage = model.page as ItemPage
        model.start(bigPage)
        val first = loaded { bigPage.tracks }.orEmpty()
        val partial = bigPage.more
        withTimeoutOrNull(60_000) { while (bigPage.more) delay(20) }
        val all = bigPage.tracks.value.orEmpty()
        check("Playlist lunga: prima un pezzo, poi tutta", partial && first.size == 500 && all.size == 2880, "${first.size} poi ${all.size}")
        model.back()
    }

    // ---------- Ricerca ----------
    model.select(Tab.SEARCH)
    val search = model.page as SearchPage
    model.search(search, "v")
    check("Ricerca: una lettera non cerca", search.results == null)
    model.search(search, "vasc")
    model.search(search, "vasco")
    val results = loaded { search.results }
    check("Ricerca: risultati per tipo", results?.query == "vasco" && results.byType["artists"]?.firstOrNull()?.name == "Vasco Rossi", "${results?.byType?.mapValues { it.value.size }}")
    check("Ricerca: risultato migliore", results?.best?.name == "Vasco Rossi", "${results?.best?.name} (${results?.best?.mediaType})")
    model.search(search, "podcast morning")
    val morning = loaded { search.results?.takeIf { (it as? Load.Ready)?.value?.query != "vasco" } }
    check("Ricerca: \"podcast morning\" mette davanti il podcast", morning?.best?.mediaType == "podcast", "${morning?.best?.name}")

    // ---------- Preferiti ----------
    model.select(Tab.LIBRARY)
    model.open(emozioni)
    val again = model.page as ItemPage
    model.setFavorite(again, true)
    delay(1_500)
    check("Preferito: album aggiunto", library.item(emozioni.uri)?.favorite == true, "${model.notice}")
    model.setFavorite(again, false)
    delay(1_500)
    check("Preferito: album tolto", library.item(emozioni.uri)?.favorite == false)

    // ---------- Navigazione ----------
    val depth = model.depth
    model.back()
    check("Indietro toglie una schermata", model.depth == depth - 1)
    model.select(Tab.LIBRARY)
    check("Toccare di nuovo la scheda torna all'inizio", model.depth == 0 && model.page is LibraryPage)
    model.playerOpen = true
    model.back()
    check("Indietro chiude prima il lettore", !model.playerOpen && model.tab == Tab.LIBRARY)
    model.back()
    check("Dalla prima schermata di una scheda torna alla Home", model.tab == Tab.HOME)
    check("Dalla Home indietro esce", !model.back())

    // ---------- Schermate salvate ----------
    // Un'altra app con le stesse schermate salvate, ma un server che non risponde: Home e album arrivano dal disco.
    val offlineSettings = Settings().also { it.save(Settings.Config("http://127.0.0.1:1", "", "x")) }
    val offlineRepo = MaRepository(offlineSettings)
    offlineRepo.acquire()
    val offline = PhoneModel(
        offlineRepo,
        Library(offlineRepo),
        offlineSettings,
        cache,
        FixedFinder(),
        "Xonos (prova)",
        { playerId },
        MutableStateFlow(false),
        CoroutineScope(SupervisorJob() + main),
    )
    val offlineHome = offline.page as HomePage
    val shownAt = System.nanoTime()
    offline.start(offlineHome)
    withTimeoutOrNull(3_000) { while (offlineHome.rows !is Load.Ready) delay(10) }
    val savedRows = offlineHome.rows.value.orEmpty()
    check(
        "Cache: la Home senza server",
        offlineHome.recent is Load.Ready && savedRows.isNotEmpty() && savedRows.all { it.items is Load.Ready },
        "${savedRows.size} righe in ${(System.nanoTime() - shownAt) / 1_000_000} ms",
    )
    val savedCover = savedRows.flatMap { it.items.value.orEmpty() }.firstOrNull { it.image != null }
    check("Cache: copertine dall'indirizzo salvato", offline.image(savedCover?.image, 256)?.startsWith(url) == true, "${offline.image(savedCover?.image, 256)}")
    offline.select(Tab.LIBRARY)
    val offlineLibrary = offline.page as LibraryPage
    offline.start(offlineLibrary)
    withTimeoutOrNull(3_000) { while (offlineLibrary.shelves == null) delay(10) }
    check("Cache: voci della libreria", offlineLibrary.shelves?.contains(Shelf.ALBUMS) == true, "${offlineLibrary.shelves}")
    val offlineAlbums = offline.shelf(offlineLibrary, Shelf.ALBUMS)
    offline.show(offlineAlbums)
    withTimeoutOrNull(3_000) { while (offlineAlbums.items.isEmpty()) delay(10) }
    check("Cache: prima pagina degli album", offlineAlbums.items.size == 100 && offlineAlbums.loading, "${offlineAlbums.items.size}")
    offlineRepo.release()

    // ---------- Impostazioni ----------
    model.openSettings()
    val settingsPage = model.page as SettingsPage
    settingsPage.remote = "127.0.0.1:1"
    model.save(settingsPage)
    withTimeoutOrNull(30_000) { while (settingsPage.checking || settingsPage.results.size < 2) delay(100) }
    check("Impostazioni: prova di casa e fuori casa", settingsPage.results.size == 2, settingsPage.results.joinToString())
    settingsPage.remote = ""
    model.save(settingsPage)
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }

    // ---------- Un server trovato in rete: il token di quello salvato non gli si manda ----------
    val chooser = model.settingsPage()
    val savedUrl = settings.load().homeUrl
    model.choose(chooser, FoundServer("altro", "Altro", "2.10.8", "http://192.0.2.77:8095", null))
    check(
        "Server trovato: un altro non riceve il token",
        chooser.token.isEmpty() && chooser.signedIn == null && chooser.remote.isEmpty() && chooser.home == "http://192.0.2.77:8095",
    )
    val same = model.settingsPage()
    model.choose(same, FoundServer("stesso", "Stesso", "2.10.8", "$savedUrl/", null))
    check("Server trovato: lo stesso tiene il token", same.token == settings.load().token && same.token.isNotEmpty())
    model.choose(chooser, FoundServer("stesso", "Stesso", "2.10.8", savedUrl, null))
    check("Server trovato: tornando a quello salvato il token torna", chooser.token == settings.load().token && chooser.home == savedUrl)

    // ---------- Accesso con nome utente e password ----------
    val login = model.settingsPage()
    login.useToken = false
    login.username = System.getenv("MA_USER") ?: "prova"
    login.password = "sbagliata"
    model.save(login)
    withTimeoutOrNull(30_000) { while (login.checking || login.results.isEmpty()) delay(100) }
    val refused = (login.results.firstOrNull() as? ConnectionCheck.Result.Failed)?.error as? MaException
    check("Accesso: password sbagliata", refused?.problem == Problem.BAD_LOGIN && settings.load().token == token, "${refused?.problem}")
    login.password = System.getenv("MA_PASSWORD") ?: "prova-xonos"
    model.save(login)
    withTimeoutOrNull(30_000) { while (login.checking || login.results.isEmpty()) delay(100) }
    check(
        "Accesso: nome utente e password",
        login.results.any { it is ConnectionCheck.Result.Ok } && settings.load().token != token && settings.username == login.username && login.password.isEmpty(),
        login.results.joinToString(),
    )
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected && it.server.admin } }

    repo.command("player_queues/stop") { put("queue_id", playerId) }
    delay(500)
    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    client.stop()
    engine.release()
    repo.release()
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
