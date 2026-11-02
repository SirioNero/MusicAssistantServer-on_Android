package it.xonos.ma

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import android.util.Log
import it.xonos.ma.data.Account
import it.xonos.ma.data.ArtCache
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.NsdFinder
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import it.xonos.ma.server.PhoneServer
import kotlinx.coroutines.CancellationException
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

    private val scope = MainScope()

    // Un server che non riesce a partire (sempre lo stesso errore) si riavviava a ogni connessione fallita, cioè ogni
    // 20 s per sempre, e ogni avvio di Music Assistant pesa sulla batteria: dopo tre di fila Xonos smette e la Home dice
    // perché, con "Riprova". Si ricomincia a contare quando Xonos si collega.
    private var failedStarts = 0

    /**
     * Fa partire il Music Assistant sul telefono se Xonos lo usa e non gira: all'avvio, quando la connessione non
     * riesce, e quando myCar chiede l'account (myCar si collega da sé, anche con l'app di Xonos chiusa).
     */
    fun startPhoneServerIfUsed() {
        val server = phoneServer ?: return
        if (settings.load().homeUrl == server.url && server.installed && failedStarts < AUTO_STARTS &&
            server.state != State.STARTING && server.state != State.RUNNING
        ) {
            // Appena risponde ci si collega, senza aspettare il prossimo tentativo (che dopo qualche errore tarda).
            scope.launch {
                if (server.start()) {
                    // Il ciclo di MaRepository può essersi già collegato mentre i servizi si caricavano: quella
                    // connessione è buona, ricollegarsi fermerebbe la coda.
                    if (repo.connection.value !is Connection.Connected) repo.reconnect()
                } else {
                    failedStarts++
                    Log.w("Xonos", "Music Assistant sul telefono non è partito ($failedStarts): ${server.error}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Se Xonos usa il Music Assistant sul telefono, lo riavvia con l'app: per lo schermo, per il
        // servizio che suona, per Gearslip e per myCar, che arrivano anche ad app chiusa. E se il suo processo
        // si ferma (Android lo ha chiuso per fare spazio), la connessione non riesce e lo si fa ripartire.
        val server = phoneServer ?: return
        // Il server sul telefono non accetta più il token (scaduto: dalla 2.10 durano un anno, e di solito
        // MaRepository lo rinnova prima). Xonos ha la password del suo account e se ne fa dare uno nuovo, una volta
        // per token: se non riesce resta l'avviso del token, come con ogni Music Assistant.
        var refused = ""
        fun signInAgain() {
            val config = settings.load()
            val password = settings.phoneServerPassword ?: return
            if (config.homeUrl != server.url || config.token == refused) return
            refused = config.token
            scope.launch {
                try {
                    val login = Account(repo).login(server.url, PhoneModel.PHONE_SERVER_USER, password, "Xonos (${Build.MODEL})")
                    val now = settings.load()
                    if (now.token == config.token) {
                        settings.save(now.copy(token = login.token))
                        repo.reconnect()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Xonos", "Nuovo accesso al server sul telefono non riuscito: ${e.message}")
                }
            }
        }
        startPhoneServerIfUsed()
        scope.launch {
            repo.connection.collect { connection ->
                if (connection is Connection.Connected) failedStarts = 0
                if (connection is Connection.Failed) {
                    startPhoneServerIfUsed()
                    if (connection.problem == Problem.BAD_TOKEN) signInAgain()
                }
            }
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

    private companion object {
        /** Quante volte di fila Xonos prova da solo a far partire il server sul telefono (vedi onCreate). */
        const val AUTO_STARTS = 3
    }
}
