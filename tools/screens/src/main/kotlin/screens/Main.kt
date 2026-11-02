package screens

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.data.Device
import it.xonos.ma.data.FlowStep
import it.xonos.ma.data.ServiceConfig
import it.xonos.ma.data.ServiceKind
import it.xonos.ma.phone.model.AddServicePage
import it.xonos.ma.phone.model.ArtistPage
import it.xonos.ma.phone.model.FlowPage
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.FoundServer
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.PlayerPanel
import it.xonos.ma.phone.model.QueueEntry
import it.xonos.ma.phone.model.Repeat
import it.xonos.ma.phone.model.SearchPage
import it.xonos.ma.phone.model.SearchResults
import it.xonos.ma.phone.model.ServicePage
import it.xonos.ma.phone.model.ServicesModel
import it.xonos.ma.phone.model.ServicesPage
import it.xonos.ma.phone.model.Shelf
import it.xonos.ma.phone.model.initialValues
import it.xonos.ma.phone.model.Tab
import it.xonos.ma.phone.ui.Host
import it.xonos.ma.phone.ui.XonosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Disegna le schermate dell'app del telefono in PNG, con Compose Desktop e dati finti: le stesse
 * composable dell'app, lo stesso PhoneModel, un Music Assistant finto ([FakeServer]).
 *
 * -Dxonos.out=cartella (predefinita out/), -Dxonos.lang=it|en|... (predefinita it),
 * -Dxonos.only=home,player (solo alcune scene).
 */
fun main() {
    // Un errore non deve lasciare il processo appeso ai thread del server finto e di OkHttp.
    val code = try {
        draw()
        0
    } catch (e: Throwable) {
        e.printStackTrace()
        1
    }
    exitProcess(code)
}

private fun draw() {
    val server = FakeServer(::answer)
    Catalog.colors.forEach { (id, colors) -> server.covers[id] = colors }
    System.setProperty("xonos.url", server.start())
    System.setProperty("xonos.token", "screens")
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory())
            add(SvgDecoder.Factory())
        }.build()
    }
    val out = File(System.getProperty("xonos.out") ?: "out").apply { mkdirs() }
    val lang = System.getProperty("xonos.lang") ?: "it"
    val only = System.getProperty("xonos.only")?.split(',')?.toSet()
    for ((name, scene) in SCENES) {
        if (only != null && name !in only) continue
        val t0 = System.nanoTime()
        println("$name-$lang...")
        watchdog(name) { shoot(File(out, "$name-$lang.png"), lang, scene) }
        println("$name-$lang.png in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
    // Il server finto non si spegne: aspetterebbe i WebSocket ancora aperti. Ci pensa exitProcess.
}

/** Se una scena non finisce in un minuto, si stampano i thread (per capire dove si è fermata) e si esce. */
private fun watchdog(name: String, block: () -> Unit) {
    val timer = java.util.Timer(true)
    timer.schedule(
        object : java.util.TimerTask() {
            override fun run() {
                System.err.println("La scena $name non finisce: ecco i thread")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    System.err.println("--- ${thread.name}")
                    stack.take(25).forEach { System.err.println("    at $it") }
                }
                System.err.flush()
                Runtime.getRuntime().halt(2)
            }
        },
        60_000,
    )
    try {
        block()
    } finally {
        timer.cancel()
    }
}

private const val WIDTH = 1080
// Più alta con -Dxonos.height, per vedere intere le pagine lunghe (le impostazioni).
private val HEIGHT = System.getProperty("xonos.height")?.toIntOrNull() ?: 2340
private const val DENSITY = 2.625f

/**
 * Una scena: prepara il modello (schede, pagine, lettore) e dice cosa sta suonando. [server] è Music Assistant sul
 * telefono (come su un telefono a 64 bit, dove la scheda c'è); null, come su uno a 32 bit.
 */
private class Scene(
    val now: NowPlaying? = PLAYING,
    val configured: Boolean = true,
    val server: PhoneServerControl? = ScreenServer(),
    /** Indirizzi e token salvati, se non quelli del server finto. */
    val config: Settings.Config? = null,
    val setUp: PhoneModel.() -> Unit,
)

private fun shoot(file: File, lang: String, scene: Scene) {
    Texts.language = lang
    val settings = Settings()
    if (!scene.configured) settings.save(Settings.Config("", "", ""))
    scene.config?.let { settings.save(it) }
    val repo = MaRepository(settings)
    repo.acquire()
    // Una scena con un indirizzo suo (un server che non risponde) non aspetta il collegamento.
    if (scene.configured && scene.config == null) {
        runBlocking { withTimeout(10_000) { repo.connection.first { it is Connection.Connected } } }
    }
    val model = PhoneModel(
        repo,
        Library(repo),
        settings,
        ScreenCache(null),
        FixedFinder(FOUND),
        "Xonos (schermate)",
        { "xonos-screens" },
        MutableStateFlow(true),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        scene.server,
    )
    scene.setUp(model)
    val host = FakeHost(Language.of(if (lang == "zh") "zh-CN" else lang))
    val composeScene = ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) { XonosScreen(model, FakeLink(scene.now), host) }
    // Il tempo di caricare le copertine e di finire le animazioni.
    val start = System.nanoTime()
    while (System.nanoTime() - start < SETTLE_NS) {
        Snapshot.sendApplyNotifications()
        composeScene.render(System.nanoTime() - start)
        Thread.sleep(40)
    }
    Snapshot.sendApplyNotifications()
    val image = composeScene.render(System.nanoTime() - start)
    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    composeScene.close()
    repo.release()
}

private const val SETTLE_NS = 2_500_000_000L

/** Music Assistant sul telefono, ed è quello a cui Xonos è collegato (il finto server delle schermate). */
private class ServerInUse : PhoneServerControl by ScreenServer() {
    override val url: String get() = Settings().load().homeUrl
}

/** Music Assistant sul telefono, in uso ma non partito. */
private class ServerFailed : PhoneServerControl by ScreenServer() {
    override val url = URL
    override val state = PhoneServerControl.State.FAILED
    override val error = "it stopped (exit 1): ModuleNotFoundError: No module named 'music_assistant'"
    override val installed = true

    companion object {
        const val URL = "http://127.0.0.1:9"
    }
}

/** Music Assistant sul telefono, non ancora scaricato: le schermate mostrano solo la scheda e la domanda. */
private class ScreenServer : PhoneServerControl {
    override val url = "http://127.0.0.1:8095"
    override val state = PhoneServerControl.State.STOPPED
    override val error = ""
    override val installed = false
    override val outdated = false

    override suspend fun downloadSize() = PACKAGE_SIZE

    override suspend fun download(progress: (Long, Long) -> Unit) = Unit

    override suspend fun remove() = Unit

    override suspend fun log() = ""

    override suspend fun start() = false

    override fun stop() = Unit
}

/** Quanto pesa il pacchetto di Music Assistant per arm64, all'incirca. */
private const val PACKAGE_SIZE = 74L * 1024 * 1024

/** I Music Assistant "trovati nella rete" del benvenuto e delle impostazioni. */
private val FOUND = listOf(
    FoundServer("casa", "Music Assistant", "2.10.8", "http://192.168.1.20:8095", "https://musica.example.org"),
    FoundServer("studio", "Music Assistant (studio)", "2.11.0", "http://192.168.1.31:8095", null),
)

/** Cosa risponde il Music Assistant finto: il brano che suona e il suo testo; il resto è vuoto. */
private fun answer(command: String, args: JSONObject): Any? = when (command) {
    "music/item_by_uri" -> {
        val uri = args.optString("uri")
        val id = uri.substringAfterLast('/')
        val album = id.substringBefore('-')
        val n = id.substringAfter('-').toIntOrNull()
        if (n != null && album in Catalog.ALBUMS) Catalog.trackJson(album, n) else null
    }
    "metadata/get_track_lyrics" -> JSONArray().put(JSONObject.NULL).put(Catalog.LYRICS)
    // Le playlist in cui si può aggiungere (le prime due di Music Assistant, l'ultima di Spotify).
    "music/playlists/library_items" -> JSONArray(
        listOf(
            Catalog.playlistJson("viaggio", "Viaggio in auto", "Xonos", "builtin"),
            Catalog.playlistJson("cantautori", "Cantautori", "Xonos", "builtin"),
            Catalog.playlistJson("estate", "Estate 2026", "Spotify", "spotify--abc"),
        ),
    )
    // "Suona su": le casse di casa; nel salotto suona Emozioni.
    "players/all" -> JSONArray(PLAYERS.map { JSONObject(it) })
    "player_queues/get" -> if (args.optString("queue_id") == "salotto") salottoQueue() else null
    "player_queues/items" -> if (args.optString("queue_id") == "salotto") JSONArray((1..Catalog.tracks("emozioni").size).map { queueItem(it) }) else null
    // Le icone dei servizi, come le manda Music Assistant: SVG in un data URI.
    "providers/icon" -> ICONS[args.optString("provider")]?.let { (color, letter) ->
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle cx="12" cy="12" r="11" fill="$color"/>""" +
            """<text x="12" y="16.5" font-size="12" font-family="sans-serif" font-weight="bold" fill="#fff" text-anchor="middle">$letter</text></svg>"""
        "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(svg.toByteArray())
    }
    else -> null
}

private val EMOZIONI = Catalog.tracks("emozioni")

/** Suona il secondo brano di Emozioni, a 25 secondi. */
private val PLAYING = NowPlaying(
    title = EMOZIONI[1].name,
    artist = "Lucio Battisti",
    album = "Emozioni",
    artwork = null,
    uri = EMOZIONI[1].uri,
    mediaType = "track",
    albumUri = "library://album/emozioni",
    artistUri = "library://artist/battisti",
    durationMs = EMOZIONI[1].durationSec * 1000L,
    playing = true,
    buffering = false,
    canSeek = true,
    shuffle = false,
    repeat = Repeat.ALL,
    favorite = true,
    queue = EMOZIONI.map { QueueEntry(it.name, "Lucio Battisti", null) },
    queueIndex = 1,
)

/** Il lettore finto: fermo a 25 secondi; i comandi non fanno niente. */
private class FakeLink(now: NowPlaying?) : PlayerLink {
    override val nowPlaying = MutableStateFlow(now?.withArtwork())
    override val problem = MutableStateFlow<String?>(null)
    override fun positionMs() = 25_500L
    override fun playPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(on: Boolean) = Unit
    override fun setRepeat(mode: Repeat) = Unit
    override fun setFavorite(on: Boolean) = Unit
    override fun skipTo(index: Int) = Unit

    /** Le copertine del lettore vengono dal server finto, come quelle del menu. */
    private fun NowPlaying.withArtwork(): NowPlaying {
        val base = System.getProperty("xonos.url")
        val art = "$base/imageproxy/emozioni?size=512&fmt=jpg"
        return copy(artwork = art, queue = queue.map { it.copy(artwork = "$base/imageproxy/emozioni?size=256&fmt=jpg") })
    }
}

private class FakeHost(override val language: Language) : Host {
    override fun describe(connection: Connection): String? = when (connection) {
        is Connection.Failed -> connection.message
        Connection.NotConfigured -> Texts.string(R.string.problem_not_configured)
        else -> null
    }

    override fun describe(error: Throwable): String = when ((error as? MaException)?.problem) {
        Problem.NO_ANSWER -> Texts.string(R.string.problem_no_answer_from).format((error as MaException).detail)
        else -> error.message ?: Texts.string(R.string.error_unknown)
    }

    override fun changeLanguage(language: Language) = Unit
    override val batteryRestricted = true
    override fun askBatteryExemption() = Unit
    override fun askMusicFiles() = Unit
    override fun shareText(subject: String, text: String) = Unit
    override val version = "0.1.17"
}

private fun <T> ready(value: T) = Load.Ready(value)

private val SCENES = listOf(
    "home" to Scene {
        val home = page as HomePage
        home.recent = ready(
            listOf(
                Catalog.album("emozioni"),
                Catalog.playlist("viaggio", "Viaggio in auto", "Xonos"),
                Catalog.podcast("morning", "Morning", "Il Post"),
                Catalog.album("bollicine"),
                Catalog.radio("deejay", "Radio Deejay"),
            ),
        )
        home.rows = ready(
            listOf(
                HomePage.Row(Catalog.folder("added", "Recently added albums", null), ready(listOf("padrone", "creuza", "nero", "mare").map(Catalog::album))),
                HomePage.Row(Catalog.folder("artists", "Random artists", null), ready(listOf("battisti", "vasco", "mina", "dalla", "battiato").map { Catalog.artist(it) })),
                HomePage.Row(
                    Catalog.folder("playlists", "Favorite playlists", "Your playlists"),
                    ready(listOf(Catalog.playlist("domenica", "Domenica mattina", "Spotify"), Catalog.playlist("ottanta", "Anni Ottanta", "Spotify"), Catalog.playlist("viaggio", "Viaggio in auto", "Xonos"))),
                ),
            ),
        )
        home.started = true
    },
    "library" to Scene {
        select(Tab.LIBRARY)
        val library = page as LibraryPage
        library.shelves = listOf(Shelf.PLAYLISTS, Shelf.ALBUMS, Shelf.ARTISTS, Shelf.TRACKS, Shelf.FAVORITES, Shelf.PODCASTS, Shelf.RADIOS, Shelf.GENRES, Shelf.BROWSE)
        library.shelf = Shelf.ALBUMS
        library.started = true
        shelf(library, Shelf.ALBUMS).apply {
            items.addAll(Catalog.ALBUMS.keys.map(Catalog::album))
            end = true
        }
    },
    "album" to Scene {
        select(Tab.LIBRARY)
        push(ItemPage(Catalog.album("emozioni")).apply { tracks = ready(EMOZIONI); started = true })
    },
    "artist" to Scene {
        select(Tab.LIBRARY)
        push(
            ArtistPage(Catalog.artist("battisti")).apply {
                top = ready(listOf(EMOZIONI[0], EMOZIONI[2], Catalog.track("canto", 4), EMOZIONI[1], Catalog.track("anima", 3), EMOZIONI[3]))
                albums = ready(listOf("anima", "canto", "emozioni").map(Catalog::album))
                tracks = ready(EMOZIONI + Catalog.tracks("anima") + Catalog.tracks("canto"))
                started = true
            },
        )
    },
    // "Aggiungi a una playlist" da un brano dell'album.
    "add-to-playlist" to Scene {
        select(Tab.LIBRARY)
        push(ItemPage(Catalog.album("emozioni")).apply { tracks = ready(EMOZIONI); started = true })
        addToPlaylist(EMOZIONI[0].uri)
    },
    // L'artista mentre i servizi rispondono ancora: si vede la libreria, con le rotelle (senza i più
    // ascoltati, che li danno solo i servizi).
    "artist-more" to Scene {
        select(Tab.LIBRARY)
        push(
            ArtistPage(Catalog.artist("battisti")).apply {
                top = ready(emptyList())
                albums = ready(listOf("emozioni").map(Catalog::album))
                tracks = ready(EMOZIONI)
                albumsMore = true
                tracksMore = true
                started = true
            },
        )
    },
    "search" to Scene {
        select(Tab.SEARCH)
        val search = page as SearchPage
        search.query = "battisti"
        search.results = ready(
            SearchResults(
                "battisti",
                Catalog.artist("battisti"),
                mapOf(
                    "artists" to listOf(Catalog.artist("battisti")),
                    "tracks" to listOf(EMOZIONI[0], EMOZIONI[2], Catalog.track("canto", 4), EMOZIONI[1], EMOZIONI[3]),
                    "albums" to listOf("emozioni", "anima", "canto").map(Catalog::album),
                    "playlists" to listOf(Catalog.playlist("viaggio", "Battisti in auto", "Spotify")),
                ),
            ),
        )
        search.started = true
    },
    "player" to Scene {
        (page as HomePage).started = true
        playerOpen = true
    },
    "lyrics" to Scene {
        (page as HomePage).started = true
        playerOpen = true
        playerPanel = PlayerPanel.LYRICS
    },
    // Dal brano dopo quello in corso la coda si può cambiare: i tre puntini.
    "queue" to Scene(now = PLAYING.copy(queueEditableFrom = 2)) {
        (page as HomePage).started = true
        playerOpen = true
        playerPanel = PlayerPanel.QUEUE
    },
    "podcast" to Scene {
        select(Tab.LIBRARY)
        push(
            ItemPage(Catalog.podcast("morning", "Morning", "Il Post")).apply {
                tracks = ready(
                    listOf(
                        Catalog.episode("morning", 5, "Morning – 25 ottobre", 28),
                        Catalog.episode("morning", 4, "Morning – 24 ottobre", 31, resumeMinutes = 12),
                        Catalog.episode("morning", 3, "Morning – 23 ottobre", 27, played = true),
                        Catalog.episode("morning", 2, "Morning – 22 ottobre", 30, played = true),
                    ),
                )
                started = true
            },
        )
    },
    "settings" to Scene {
        (page as HomePage).started = true
        val settings = settingsPage().apply {
            results = listOf(
                ConnectionCheck.Result.Ok(ConnectionCheck.Place.HOME, "2.10.8", 12, ConnectionCheck.Audio.Ready),
                ConnectionCheck.Result.Failed(ConnectionCheck.Place.AWAY, MaException("x", problem = Problem.NO_ANSWER, detail = "https://musica.example.com")),
            )
            tried = true
        }
        push(settings)
    },
    "welcome" to Scene(now = null, configured = false) {},
    // "Fai diventare Xonos il tuo server Music Assistant": la domanda con quanto pesa, poi il download.
    "phone-server-ask" to Scene {
        (page as HomePage).started = true
        push(settingsPage().apply { phoneServerAsk = true; phoneServerSize = PACKAGE_SIZE })
    },
    "phone-server-download" to Scene {
        (page as HomePage).started = true
        push(settingsPage().apply { phoneServerProgress = PACKAGE_SIZE * 2 / 5 to PACKAGE_SIZE })
    },
    // Scaricato: si controlla la firma e si estrae (senza Annulla).
    "phone-server-preparing" to Scene {
        (page as HomePage).started = true
        push(settingsPage().apply { phoneServerProgress = PACKAGE_SIZE to PACKAGE_SIZE })
    },
    // Il server sul telefono non è partito: il perché nella Home, con Riprova.
    "phone-server-failed" to Scene(server = ServerFailed(), config = Settings.Config(ServerFailed.URL, "", "screens")) {
        (page as HomePage).started = true
    },
    "play-on" to Scene {
        (page as HomePage).started = true
        playerOpen = true
        openDevices()
    },
    "remote" to Scene {
        (page as HomePage).started = true
        playOn(Device.from(JSONObject(PLAYERS[0])))
        playerOpen = true
    },
    "remote-mini" to Scene(now = null) {
        (page as HomePage).started = true
        playOn(Device.from(JSONObject(PLAYERS[0])))
    },
    "services" to Scene {
        (page as HomePage).started = true
        push(ServicesPage().apply { services = ready(SERVICES.mapNotNull { ServiceConfig.from(JSONObject(it)) }); started = true })
    },
    "add-service" to Scene {
        (page as HomePage).started = true
        push(AddServicePage().apply { kinds = ready(KINDS.mapNotNull { ServiceKind.from(JSONObject(it)) }); started = true })
    },
    "service-flow" to Scene {
        (page as HomePage).started = true
        val step = FlowStep.from(JSONObject(FLOW_FORM))!!
        push(FlowPage("Jellyfin", "jellyfin") { step }.apply { this.step = ready(step); values.putAll(initialValues(step.fields)); started = true })
    },
    "service-login" to Scene {
        (page as HomePage).started = true
        val step = FlowStep.from(JSONObject(FLOW_EXTERNAL))!!
        push(FlowPage("Spotify", "spotify") { step }.apply { this.step = ready(step); started = true })
    },
    // Spotify sul Music Assistant del telefono: l'avviso sugli account recenti.
    "spotify-phone" to Scene(server = ServerInUse()) {
        (page as HomePage).started = true
        val step = FlowStep.from(JSONObject(FLOW_EXTERNAL))!!
        push(FlowPage("Spotify", "spotify") { step }.apply { this.step = ready(step); started = true })
    },
    "flow-leave" to Scene(server = ServerInUse()) {
        (page as HomePage).started = true
        val step = FlowStep.from(JSONObject(FLOW_EXTERNAL))!!
        push(FlowPage("Spotify", "spotify") { step }.apply { this.step = ready(step); started = true; confirmLeave = true })
    },
    "service" to Scene {
        (page as HomePage).started = true
        val service = ServiceConfig.from(JSONObject(SERVICE))!!
        push(
            ServicePage(service.instanceId, service.name).apply {
                this.service = ready(service)
                values.putAll(initialValues(service.fields) + (ServicesModel.ENABLED to true))
                values["bitrate"] = 256
                started = true
            },
        )
    },
)

private val PLAYERS = listOf(
    """{"player_id":"salotto","name":"Salotto","type":"player","available":true,"enabled":true,"hide_in_ui":false,"private":false,"playback_state":"playing","volume_level":35}""",
    """{"player_id":"cucina","name":"Cucina","type":"player","available":true,"enabled":true,"hide_in_ui":false,"private":false,"playback_state":"idle","volume_level":20}""",
    """{"player_id":"casa","name":"Tutta la casa","type":"group","available":true,"enabled":true,"hide_in_ui":false,"private":false,"playback_state":"idle","volume_level":30}""",
    """{"player_id":"studio","name":"Studio (spento)","type":"player","available":false,"enabled":true,"hide_in_ui":false,"private":false,"playback_state":"idle"}""",
    """{"player_id":"xonos-screens","name":"Xonos","type":"player","available":true,"enabled":true,"hide_in_ui":true,"private":true,"playback_state":"idle"}""",
)

private fun queueItem(n: Int): JSONObject {
    val track = Catalog.trackJson("emozioni", n)
    return JSONObject().put("queue_item_id", "q$n").put("name", track.optString("name")).put("duration", track.optInt("duration")).put("media_item", track)
}

private fun salottoQueue(): JSONObject = JSONObject()
    .put("queue_id", "salotto")
    .put("state", "playing")
    .put("items", Catalog.tracks("emozioni").size)
    .put("current_index", 1)
    .put("elapsed_time", 25.5)
    .put("shuffle_enabled", false)
    .put("repeat_mode", "all")
    .put("current_item", queueItem(2))

/** Colore e lettera delle icone finte dei servizi. */
private val ICONS = mapOf(
    "spotify" to ("#1DB954" to "S"),
    "jellyfin" to ("#7B4FD6" to "J"),
    "tidal" to ("#222222" to "T"),
    "podcastfeed" to ("#E8590C" to "P"),
    "radiobrowser" to ("#1C7ED6" to "R"),
    "chromecast" to ("#F59F00" to "C"),
)

private val SERVICES = listOf(
    """{"instance_id":"spotify--a1","domain":"spotify","type":"music","name":"Spotify (Sirio)","enabled":true,"status":"loaded"}""",
    """{"instance_id":"jellyfin--b2","domain":"jellyfin","type":"music","name":"Jellyfin","enabled":true,"status":"error","last_error":{"error_code":999,"message":"Server non raggiungibile: http://192.168.1.40:8096"}}""",
    """{"instance_id":"podcastfeed--c3","domain":"podcastfeed","type":"music","name":"Il Post","enabled":true,"status":"loaded"}""",
    """{"instance_id":"tidal--d4","domain":"tidal","type":"music","name":"Tidal","enabled":false,"status":"disabled"}""",
    """{"instance_id":"radiobrowser--e5","domain":"radiobrowser","type":"music","name":"Radio Browser","enabled":true,"status":"loaded"}""",
    """{"instance_id":"chromecast--f6","domain":"chromecast","type":"player","name":"Chromecast","enabled":true,"status":"loaded"}""",
    """{"instance_id":"lrclib--g7","domain":"lrclib","type":"metadata","name":"LRCLIB","enabled":true,"status":"loaded"}""",
)

private val KINDS = listOf(
    """{"domain":"spotify","type":"music","name":"Spotify","description":"Ascolta la tua libreria e le playlist di Spotify (serve Premium).","stage":"stable","multi_instance":true}""",
    """{"domain":"tidal","type":"music","name":"Tidal","description":"Brani in alta qualità dal tuo account Tidal.","stage":"stable","multi_instance":true}""",
    """{"domain":"jellyfin","type":"music","name":"Jellyfin","description":"La musica del tuo server Jellyfin.","stage":"stable","multi_instance":true}""",
    """{"domain":"opensubsonic","type":"music","name":"Subsonic / Navidrome","description":"Server compatibili con Subsonic.","stage":"stable","multi_instance":true}""",
    """{"domain":"ytmusic","type":"music","name":"YouTube Music","description":"La tua libreria di YouTube Music.","stage":"beta","multi_instance":true}""",
    """{"domain":"podcastfeed","type":"music","name":"Podcast RSS","description":"Un podcast dal suo feed RSS.","stage":"stable","multi_instance":true}""",
    """{"domain":"chromecast","type":"player","name":"Chromecast","description":"Casse e TV con Google Cast.","stage":"stable","multi_instance":false}""",
    """{"domain":"lrclib","type":"metadata","name":"LRCLIB","description":"Testi sincronizzati.","stage":"stable","multi_instance":false}""",
)

private val FLOW_FORM = """
{"flow_id":"f1","step_id":"user","type":"form","title":"Collega Jellyfin",
 "description":"Scrivi l'indirizzo del tuo server **Jellyfin** e accedi. Non hai un server? Vedi [la guida](https://jellyfin.org/docs/).",
 "last_step":true,
 "errors":{"password":"Nome utente o password sbagliati"},
 "entries":[
  {"key":"url","type":"string","label":"Indirizzo del server","description":"Per esempio http://192.168.1.40:8096","value":"http://192.168.1.40:8096","required":true},
  {"key":"username","type":"string","label":"Nome utente","value":"sirio","required":true},
  {"key":"password","type":"secure_string","label":"Password","required":true},
  {"key":"quality","type":"string","label":"Qualità","default_value":"original","options":[{"title":"Originale","value":"original"},{"title":"Alta (320 kbps)","value":"high"},{"title":"Bassa (128 kbps)","value":"low"}]},
  {"key":"verify_ssl","type":"boolean","label":"Controlla il certificato","description":"Spegnilo solo per un server con certificato fatto in casa.","default_value":true,"advanced":true}
 ]}
"""

private val FLOW_EXTERNAL = """
{"flow_id":"f2","step_id":"auth","type":"external","title":"Accedi a Spotify",
 "description":"Si apre la pagina di Spotify: accedi e consenti a Music Assistant di usare il tuo account.\n\n- Serve un account **Premium**\n- La pagina torna a Music Assistant da sola",
 "url":"https://accounts.spotify.com/authorize?client_id=x"}
"""

private val SERVICE = """
{"instance_id":"xonos_test--x1","domain":"jellyfin","type":"music","name":"Jellyfin","enabled":true,"status":"loaded",
 "values":{
  "nickname":{"key":"nickname","type":"string","label":"Nome da mostrare","value":"Casa"},
  "password":{"key":"password","type":"secure_string","label":"Password","value":"this_value_is_encrypted"},
  "genres":{"key":"genres","type":"string","label":"Generi da importare","multi_value":true,"value":["rock","jazz"],"options":[{"title":"Rock","value":"rock"},{"title":"Jazz","value":"jazz"},{"title":"Classica","value":"classical"},{"title":"Pop","value":"pop"}]},
  "divider":{"key":"divider","type":"divider","label":""},
  "bitrate":{"key":"bitrate","type":"integer","label":"Bitrate massimo (kbps)","range":[64,320],"default_value":192},
  "sync":{"key":"sync","type":"boolean","label":"Sincronizza i preferiti","description":"I preferiti di Xonos finiscono anche in Jellyfin.","value":true},
  "warning":{"key":"warning","type":"alert","label":"Dopo aver cambiato i generi la libreria si aggiorna da sola."},
  "test":{"key":"test","type":"action","label":"Prova","action":"test","action_label":"Prova la connessione","description":"Controlla che il server risponda."}
 }}
"""
