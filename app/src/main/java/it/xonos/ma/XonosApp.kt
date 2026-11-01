package it.xonos.ma

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import it.xonos.ma.data.ArtCache
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.NsdFinder
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import it.xonos.ma.server.PhoneServer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class XonosApp : Application(), SingletonImageLoader.Factory {
    val settings: Settings by lazy { Settings(this) }
    val repo: MaRepository by lazy { MaRepository(settings) }
    val library: Library by lazy { Library(repo) }

    /** Le copertine scaricate per l'auto, la notifica e il lettore (vedi playback/Artwork). */
    val artCache: ArtCache by lazy { ArtCache(File(cacheDir, "art"), repo.http) }

    /** Vero quando il servizio ha fatto del telefono un lettore di Music Assistant (API e audio collegati). */
    val playerReady = MutableStateFlow(false)

    /** Music Assistant sul telefono stesso, scaricato su richiesta (null su un telefono a 32 bit). */
    val phoneServer: PhoneServerControl? by lazy { PhoneServer.create(this, repo.http) }

    /** Lo stato dell'app sul telefono: vive quanto l'app, così resta uguale se lo schermo ruota o cambia lingua. */
    val phone: PhoneModel by lazy {
        PhoneModel(
            repo,
            library,
            settings,
            ScreenCache(File(cacheDir, "screens")),
            NsdFinder(this),
            "Xonos (${Build.MODEL})",
            { settings.playerId },
            playerReady,
            MainScope(),
            phoneServer,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Se Xonos usa il Music Assistant sul telefono, lo riavvia con l'app: per lo schermo, per il
        // servizio che suona, per Gearslip e per myCar, che arrivano anche ad app chiusa. E se il suo processo
        // si ferma (Android lo ha chiuso per fare spazio), la connessione non riesce e lo si fa ripartire.
        val server = phoneServer ?: return
        val scope = MainScope()
        fun startIfUsed() {
            if (settings.load().homeUrl == server.url && server.installed &&
                server.state != State.STARTING && server.state != State.RUNNING
            ) {
                // Appena risponde ci si collega, senza aspettare il prossimo tentativo (che dopo qualche errore tarda).
                scope.launch { if (server.start()) repo.reconnect() }
            }
        }
        startIfUsed()
        scope.launch {
            repo.connection.collect { connection -> if (connection is Connection.Failed) startIfUsed() }
        }
    }

    /** Le copertine sullo schermo del telefono, con la stessa connessione dell'API (ma un limite di tempo). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val http = repo.http.newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                // Le icone dei servizi di Music Assistant.
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
