package it.xonos.ma

import android.app.Application
import android.os.Build
import android.os.SystemClock
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
import it.xonos.ma.data.Logged
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.NsdFinder
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import it.xonos.ma.phone.model.ServerKeeper
import it.xonos.ma.server.PhoneServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    // perché, con "Riprova". Si ricomincia a contare quando Xonos si collega, o quando il server si ferma perché non
    // serve più (chi ne avrà bisogno dopo merita altri tentativi).
    private var failedStarts = 0

    /** Xonos ha smesso di far ripartire da solo il server sul telefono, che non parte (vedi [failedStarts]). */
    val phoneServerGivenUp: Boolean get() = failedStarts >= AUTO_STARTS

    /**
     * Il Music Assistant sul telefono acceso solo finché serve: allo schermo, alla musica, all'auto, a myCar. Prima
     * partiva con l'app e restava acceso finché Android non chiudeva tutto, e con Xonos in disparte Android lo chiudeva
     * per "excessive cpu". Chi ne ha bisogno lo dice al keeper, che lo avvia e lo ferma.
     */
    val keeper: ServerKeeper by lazy {
        ServerKeeper(
            scope,
            used = ::phoneServerUsed,
            start = ::startPhoneServer,
            stop = ::stopPhoneServer,
            clock = SystemClock::elapsedRealtime,
            log = { Log.i("Xonos", it) },
        )
    }

    /** Xonos usa il Music Assistant sul telefono, e il pacchetto di questa versione c'è. */
    private fun phoneServerUsed(): Boolean =
        phoneServer?.let { settings.load().homeUrl == it.url && it.installed } == true

    /** Per il keeper: avvia il server sul telefono se non gira e aspetta che risponda. Sul thread principale. */
    private suspend fun startPhoneServer() {
        val server = phoneServer ?: return
        if (!phoneServerUsed() || failedStarts >= AUTO_STARTS) return
        // start() guarda il processo, non lo stato: subito dopo un arresto lo stato può dire ancora RUNNING.
        val running = server.state == State.RUNNING
        if (server.start()) {
            // Appena risponde ci si collega, senza aspettare il prossimo tentativo (che dopo qualche errore tarda). Non
            // se il ciclo di MaRepository si è già collegato o ci sta provando: ricollegarsi fermerebbe la coda.
            if (!running && repo.connection.value is Connection.Failed) repo.reconnect()
        } else {
            failedStarts++
            val why = Logged.text(server.error, server.error.substringBefore(':'))
            Log.w("Xonos", "Music Assistant sul telefono non è partito ($failedStarts): $why")
        }
    }

    /** Per il keeper: nessuno ha più bisogno del server sul telefono, lo ferma (Music Assistant chiude database e servizi). */
    private suspend fun stopPhoneServer() {
        val server = phoneServer ?: return
        failedStarts = 0
        if (server.state != State.RUNNING && server.state != State.STARTING) return
        Log.i("Xonos", "Fermo Music Assistant sul telefono: non serve a nessuno")
        withContext(Dispatchers.IO) {
            server.stop()
            // Lo stato diventa STOPPED un attimo dopo, quando finisce il registro del processo: chi lo guarda (la Home,
            // l'auto) deve vederlo fermo, e un avvio subito dopo deve trovarlo aggiornato.
            withTimeoutOrNull(STOP_SETTLE_MS) {
                while (server.state == State.RUNNING || server.state == State.STARTING) delay(100)
            }
        }
    }

    override fun onCreate() {
        // Prima di tutto: nel logcat i dettagli (titoli, indirizzi, testi d'errore) solo nella build di prova.
        Logged.details = BuildConfig.DEBUG
        super.onCreate()
        // Il Music Assistant sul telefono non parte più con l'app: lo avvia chi ne ha bisogno (lo schermo, la musica,
        // l'auto, myCar), vedi [keeper]. Se il suo processo si ferma da solo (Android lo ha chiuso per fare spazio)
        // mentre serve, la connessione non riesce e lo si fa ripartire.
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
                    Log.w("Xonos", "Nuovo accesso al server sul telefono non riuscito: ${Logged.error(e)}")
                }
            }
        }
        scope.launch {
            repo.connection.collect { connection ->
                if (connection is Connection.Connected) failedStarts = 0
                if (connection is Connection.Failed) {
                    // Solo se qualcuno ne ha bisogno: fermato dal keeper perché non serviva, resta fermo.
                    if (keeper.wanted && server.state != State.STARTING && server.state != State.RUNNING) keeper.revive()
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
        /** Quante volte di fila Xonos prova da solo a far partire il server sul telefono (vedi [failedStarts]). */
        const val AUTO_STARTS = 3

        /** Quanto si aspetta, dopo l'arresto, che lo stato del server lo dica. */
        const val STOP_SETTLE_MS = 5_000L
    }
}
