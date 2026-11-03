package it.xonos.ma.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import it.xonos.ma.XonosApp
import it.xonos.ma.describe
import it.xonos.ma.wrap
import it.xonos.ma.BuildConfig
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Logged
import it.xonos.ma.data.Problem
import it.xonos.ma.data.MaException
import it.xonos.ma.data.SkipTrace
import it.xonos.ma.phone.MainActivity
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.ServerKeeper.Need
import it.xonos.ma.sendspin.AndroidAudio
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.min

/**
 * Il cuore dell'app: è il "lettore musicale" che Gearslip trova e apre, con il menu della libreria
 * e i comandi. Tiene il telefono collegato a Music Assistant come lettore e lo fa suonare.
 *
 * Gearslip si collega quando si apre Xonos in auto; mentre la musica suona il servizio resta
 * in primo piano (con la sua notifica) anche a schermo spento.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var app: XonosApp
    private lateinit var engine: AudioEngine
    private lateinit var sendspin: SendspinClient
    private lateinit var player: MaPlayer
    private lateinit var tree: BrowseTree
    private lateinit var session: MediaLibrarySession
    private lateinit var focus: AudioFocus
    private lateinit var connectivity: ConnectivityManager

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private val searches = mutableMapOf<String, List<MediaItem>>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var noisyRegistered = false
    private var resumeAfterInterruption = false
    private var networkChanged = false
    private var lastNetwork: Network? = null
    private var announcedName: String? = null
    private val abandonFocus = Runnable { focus.abandon() }

    override fun onCreate() {
        super.onCreate()
        app = application as XonosApp
        connectivity = getSystemService(ConnectivityManager::class.java)
        val settings = app.settings
        val artwork = Artwork(this)

        engine = AudioEngine(AndroidAudio::output, AndroidAudio::decoder, TimeFilter()) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        }
        engine.setVolume(settings.volume, settings.muted)
        sendspin = SendspinClient(app.repo.http, engine, ::identity) { volume, muted ->
            settings.volume = volume
            settings.muted = muted
        }
        player = MaPlayer(
            mainLooper,
            app.repo,
            settings.playerId,
            app.playerReady,
            engine.outputting,
            artwork::uriFor,
            { engine.lateMs },
            { onPhoneServer },
            // Un play col Music Assistant sul telefono fermo (nessuno lo usava): lo si risveglia. Vale la pena aspettarlo
            // solo se può ripartire: non col pacchetto da aggiornare, né quando Xonos ha smesso di provarci.
            wake = {
                app.keeper.touch(Need.PLAYING)
                app.phoneServer?.installed == true && !app.phoneServerGivenUp
            },
            phoneServerFailed = { app.phoneServer?.state == PhoneServerControl.State.FAILED },
        ) { connection ->
            val context = settings.language.wrap(this)
            phoneServerProblem(context, connection) ?: describe(context, connection)
        }
        tree = BrowseTree(Menu(app.library, ResourceLabels(this) { settings.language }) { settings.playerId }, artwork)
        player.onPlayRequest = { item -> tree.play(item) }
        // Il perché di un play o di un comando non riuscito arriva anche all'auto (lo stato della sessione).
        player.onFailure = { e ->
            if (::session.isInitialized) {
                val context = settings.language.wrap(this)
                // Col server sul telefono che non risponde, il perché (parte, va aggiornato, non è partito) invece di
                // "Non collegato a Music Assistant".
                val text = phoneServerProblem(context, app.repo.connection.value) ?: describe(context, e)
                session.sendError(SessionError(SessionError.ERROR_UNKNOWN, text))
            }
        }
        // Pausa non arrivata a Music Assistant (niente rete): l'audio già ricevuto si zittisce.
        player.onPauseLost = { engine.silence() }

        // Toccando la notifica si apre il lettore sul telefono.
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openPlayer)
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_notification) },
        )
        focus = AudioFocus(this, ::onFocusChange)

        app.repo.acquire()
        scope.launch { app.repo.connection.collect(::onConnection) }
        scope.launch {
            combine(app.repo.connection, sendspin.phase) { connection, phase ->
                connection is Connection.Connected && phase == SendspinClient.Phase.READY
            }.collectLatest { ready ->
                // Music Assistant registra il lettore un attimo dopo il saluto di Sendspin: fino ad allora la
                // coda risponde "The player is not available". Con il server sul telefono si arrivava prima.
                if (ready) awaitQueue()
                app.playerReady.value = ready
            }
        }
        scope.launch { engine.outputting.collect(::onOutputting) }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Un play chiesto (dalla persona, dall'auto, a fine telefonata): il silenzio dopo lo stacco dell'uscita o
                // una pausa non arrivata finisce. Non quando lo stato torna "in riproduzione" da solo (una pausa fallita).
                if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) engine.unsilence()
                updateLocks()
                updateEngaged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) = updateEngaged()
        })
        connectivity.registerDefaultNetworkCallback(networkCallback)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(outputs, main)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        // Il servizio se ne va (fermo e senza nessuno legato): da qui l'app può essere congelata in pochi secondi. Musica e
        // auto non tengono più il server sul telefono; lo schermo e myCar sì, con le loro attese.
        destroyed = true
        outside.clear()
        engaged = false
        app.keeper.release(Need.PLAYING, 0)
        app.keeper.release(Need.CAR, 0)
        app.playerReady.value = false
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(outputs) }
        main.removeCallbacksAndMessages(null)
        setNoisyReceiver(false)
        releaseLocks()
        focus.abandon()
        session.release()
        player.release()
        sendspin.stop()
        engine.release()
        app.repo.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Xonos tolto dai recenti: se la musica non suona Media3 ferma il servizio (e poi [onDestroy]). Lo schermo non serve
     * più: senza musica, auto né myCar il server sul telefono si ferma subito, prima che Android congeli o chiuda l'app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        app.keeper.release(Need.SCREEN, 0)
    }

    /**
     * I browser di prima (MediaBrowserCompat, come Gearslip) non dicono a Media3 quando se ne vanno: lo si sa qui,
     * quando l'ultimo si stacca. Vero, così il prossimo che si lega passa da onRebind e, staccandosi, di nuovo da qui.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action == LEGACY_BROWSER) {
            outsideChanged { outside.removeAll { it.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION } }
        }
        super.onUnbind(intent)
        return true
    }

    /**
     * Media3 rifà la notifica: qui si guarda, con la sua stessa prova, se la musica suona o sta per suonare. Quando smette
     * di tenere il servizio in primo piano (10 minuti dopo la pausa) l'app può essere congelata: la musica non tiene più
     * il server (di solito è già fermo: [Need.PLAYING] aspetta 8 minuti).
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Prima Media3: è il metodo di base che disegna la notifica.
        super.onUpdateNotification(session, startInForegroundRequired)
        if (!::player.isInitialized || destroyed) return
        updateEngaged()
        if (foreground && !startInForegroundRequired && !engaged) app.keeper.release(Need.PLAYING, 0)
        foreground = startInForegroundRequired
    }

    /** Media3 tiene il servizio in primo piano (l'ultima cosa che ha detto a [onUpdateNotification]). */
    private var foreground = false

    /** La coda suona o sta per suonare: la stessa prova di Media3 (play voluto, pronto o in caricamento; non IDLE). */
    private var engaged = false

    /** In [onDestroy]: sessione e lettore che si chiudono non devono tenere di nuovo il server. */
    private var destroyed = false

    /** Tiene o lascia il server sul telefono per la musica, quando cambia [engaged]. */
    private fun updateEngaged() {
        if (destroyed) return
        val state = player.playbackState
        val now = player.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        if (now == engaged) return
        engaged = now
        if (now) app.keeper.hold(Need.PLAYING) else app.keeper.release(Need.PLAYING)
    }

    /**
     * Chi comanda il lettore da fuori (Gearslip, l'autoradio col Bluetooth): finché c'è, il server sul telefono resta
     * acceso. Non Xonos stesso (lo schermo, la notifica) né la ripresa dei media del sistema, che si lega anche lei.
     */
    private val outside = mutableSetOf<MediaSession.ControllerInfo>()

    private fun outsideChanged(change: () -> Unit) {
        if (destroyed) return
        val before = outside.isNotEmpty()
        change()
        val after = outside.isNotEmpty()
        if (before == after) return
        if (after) app.keeper.hold(Need.CAR) else app.keeper.release(Need.CAR)
    }

    /** Come ci presentiamo a Music Assistant, deciso a ogni connessione (la rete può essere cambiata). */
    private fun identity(): SendspinClient.Identity {
        val settings = app.settings
        // Con il Music Assistant sul telefono stesso la rete non c'entra: il margine è quello del Wi-Fi.
        val metered = connectivity.isActiveNetworkMetered && !onPhoneServer
        val formats = buildList {
            // Opus pesa un decimo del PCM: in rete mobile fa la differenza. Il PCM resta come riserva.
            if (AndroidAudio.canDecode(MediaFormat.MIMETYPE_AUDIO_OPUS)) add(StreamFormat("opus", RATE, 2, 16))
            add(StreamFormat("pcm", RATE, 2, 16))
        }
        return SendspinClient.Identity(
            clientId = settings.playerId,
            name = settings.playerName,
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() },
            softwareVersion = "Xonos ${BuildConfig.VERSION_NAME}",
            formats = formats,
            // Il margine si paga a ogni play e a ogni salto (misurato: con 800 ms la musica partiva 0,65 s
            // dopo, con 1500 ms 1,3 s dopo). Le gallerie non le copre questo ma l'audio che il server manda
            // in anticipo durante il brano: il margine resta piccolo, un po' più largo in rete mobile.
            leadTimeMs = if (metered) 600 else 300,
            minBufferMs = if (metered) 400 else 150,
            volume = settings.volume,
            muted = settings.muted,
            // Senza i campi che la specifica di Sendspin ha tolto: Music Assistant non segna più il telefono come non
            // conforme. Solo dove si sa che li legge altrove: dalla 2.10.8, e il server sul telefono (2.10.8).
            modernWire = onPhoneServer || atLeast((app.repo.connection.value as? Connection.Connected)?.server?.version, 2, 10, 8),
        )
    }

    /** Aspetta che Music Assistant abbia la coda del telefono; dopo qualche secondo si va avanti lo stesso. */
    private suspend fun awaitQueue() {
        withTimeoutOrNull(QUEUE_WAIT_MS) {
            while (true) {
                val queue = try {
                    app.repo.command("player_queues/get") { put("queue_id", app.settings.playerId) } as? JSONObject
                } catch (e: MaException) {
                    null
                }
                if (queue?.optBoolean("available") == true) break
                delay(QUEUE_POLL_MS)
            }
        }
    }

    private fun onConnection(connection: Connection) {
        when (connection) {
            is Connection.Connected -> {
                // L'auto ha avuto il menu senza Music Assistant (aspettato invano): lo richieda, ora c'è tutto.
                if (browsedOffline) {
                    browsedOffline = false
                    session.notifyChildrenChanged(Menu.ROOT, Int.MAX_VALUE, null)
                }
                // Prima l'API, poi l'audio: così Music Assistant lega il lettore a questa sessione.
                // Si riapre anche l'audio se è cambiata la rete o il nome del lettore.
                val name = app.settings.playerName
                val force = networkChanged || connection.server.limitedPlayers || name != announcedName
                networkChanged = false
                announcedName = name
                sendspin.start(connection.url, app.settings.load().token, force)
            }
            Connection.NotConfigured -> sendspin.stop()
            // Mentre si ricollega, l'audio già ricevuto continua a suonare.
            else -> Unit
        }
    }

    /** L'audio esce adesso dal telefono. */
    private var outputting = false

    /** L'auto ha sfogliato il menu mentre Music Assistant non era collegato (vedi [awaitConnected]). */
    @Volatile private var browsedOffline = false

    /**
     * Gearslip e myCar chiedono il menu appena si collegano, spesso prima che Xonos sia collegato a Music Assistant
     * (all'avvio, o col server sul telefono che parte): si aspetta il collegamento, al massimo [BROWSE_WAIT_MS],
     * invece di rispondere con cartelle in errore e voci vuote che l'auto poi non richiede.
     */
    private suspend fun awaitConnected() {
        val now = app.repo.connection.value
        // Niente da aspettare: senza un Music Assistant scelto, con un problema che non passa da solo (token, versione,
        // indirizzo), col server sul telefono da aggiornare o che Xonos non prova più ad avviare, o dopo un'attesa già
        // andata a vuoto (ogni cartella aspetterebbe di nuovo; quando arriva, browsedOffline torna falso).
        if (now == Connection.NotConfigured || now is Connection.Connected || browsedOffline) return
        if (now is Connection.Failed && now.problem in LASTING) return
        val server = app.phoneServer?.takeIf { onPhoneServer }
        val ok = if (server != null) {
            if (!server.installed) return
            // Fermo perché nessuno lo usava (o chiuso da Android): l'auto lo risveglia, e il menu aspetta che riparta.
            app.keeper.touch(Need.CAR)
            withTimeoutOrNull(PHONE_BROWSE_WAIT_MS) {
                while (app.repo.connection.value !is Connection.Connected) {
                    if (server.state == PhoneServerControl.State.FAILED && app.phoneServerGivenUp) return@withTimeoutOrNull false
                    delay(PHONE_POLL_MS)
                }
                true
            } == true
        } else {
            withTimeoutOrNull(BROWSE_WAIT_MS) { app.repo.connection.first { it is Connection.Connected } } != null
        }
        if (!ok) browsedOffline = true
    }

    /**
     * Col Music Assistant sul telefono, perché non risponde detto per quello che è (parte, va aggiornato, non è
     * partito) invece di "Errore di rete: Failed to connect to /127.0.0.1:8095". null: non è quello, o risponde.
     */
    private fun phoneServerProblem(context: Context, connection: Connection): String? {
        if (connection is Connection.Connected) return null
        val server = app.phoneServer ?: return null
        if (app.settings.load().homeUrl != server.url) return null
        return when {
            !server.installed -> context.getString(R.string.phone_server_outdated)
            // Fermo perché non serviva: il prossimo bisogno (il play, il menu dell'auto) lo fa ripartire.
            server.state == PhoneServerControl.State.STARTING || server.state == PhoneServerControl.State.STOPPED ->
                context.getString(R.string.phone_server_starting)
            server.state == PhoneServerControl.State.FAILED ->
                context.getString(R.string.phone_server_failed, server.error.ifBlank { server.state.name })
            else -> null
        }
    }

    /**
     * Sveglio (processore e Wi-Fi) mentre esce l'audio, e per al massimo [PREPARE_LOCK_MS] (solo il processore) anche
     * quando Music Assistant sta per suonare ma l'audio non esce ancora: col server sul telefono, in auto a schermo
     * spento, la preparazione del brano (Soloist: circa 7 s) o la riconnessione dopo un calo di rete si fermerebbero.
     */
    private fun updateLocks() {
        when {
            outputting -> acquireLocks()
            player.playWhenReady -> {
                // Solo il processore, al massimo 2 minuti: il Wi-Fi a piena potenza serve mentre l'audio esce (prima
                // restava tenuto senza limite anche con Music Assistant sparito e la coda vecchia "in riproduzione").
                wifiLock?.takeIf { it.isHeld }?.release()
                acquireCpu(PREPARE_LOCK_MS)
            }
            else -> releaseLocks()
        }
    }

    private fun onOutputting(on: Boolean) {
        outputting = on
        if (on) {
            // Dopo un salto: quanto ci ha messo la musica a uscire di nuovo (gli altri passi li scrivono SkipSender,
            // SendspinClient e AudioEngine).
            SkipTrace.musicOut()?.let { Log.i(TAG, "Salto: musica fuori $it ms dopo il tocco") }
            main.removeCallbacks(abandonFocus)
            updateLocks()
            setNoisyReceiver(true)
            if (!focus.request()) {
                Log.i(TAG, "Un'altra app ha l'audio (telefonata?): metto in pausa")
                player.pauseFromSystem()
            }
        } else {
            updateLocks()
            setNoisyReceiver(false)
            // Ferma per un po': si lascia l'audio alle altre app, ma non durante una telefonata.
            if (!resumeAfterInterruption) main.postDelayed(abandonFocus, FOCUS_GRACE_MS)
        }
    }

    private fun onFocusChange(change: AudioFocus.Change) {
        when (change) {
            AudioFocus.Change.PAUSED -> if (player.isPlayingOnServer) {
                // Subito zitto, poi la pausa vera; riprende da sola alla fine della telefonata.
                engine.setDuck(0f)
                resumeAfterInterruption = true
                player.pauseFromSystem()
            }
            AudioFocus.Change.RESUMED -> {
                engine.setDuck(1f)
                if (resumeAfterInterruption) player.resumeFromSystem()
                resumeAfterInterruption = false
            }
            AudioFocus.Change.LOST -> {
                engine.setDuck(1f)
                resumeAfterInterruption = false
                player.pauseFromSystem()
            }
        }
    }

    /**
     * Il Bluetooth dell'auto (o le cuffie) si stacca anche quando la musica è già ferma, per esempio perché la rete
     * è caduta in galleria: allora, al ritorno della rete, non si riprende da soli (uscirebbe dall'altoparlante).
     * Mentre suona ci pensa [noisy].
     */
    private val outputs = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.none { it.isSink && it.type !in BUILT_IN_OUTPUTS }) return
            player.forgetDrop()
            // La musica sta partendo (Soloist, 7 s) ma non esce ancora: Android non manda "noisy", e appena arriva
            // uscirebbe dall'altoparlante. Come una pausa della persona.
            if (player.playWhenReady && !outputting) {
                engine.silence()
                player.pauseFromSystem()
            }
        }
    }

    /** Il Bluetooth dell'auto si stacca: la musica si ferma invece di passare all'altoparlante del telefono. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Si è staccata l'uscita in uso: zitti subito, anche se la pausa non arriva a Music Assistant.
                engine.silence()
                player.pauseFromSystem()
            }
        }
    }

    private fun setNoisyReceiver(on: Boolean) {
        if (on == noisyRegistered) return
        if (on) {
            ContextCompat.registerReceiver(
                this,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            runCatching { unregisterReceiver(noisy) }
        }
        noisyRegistered = on
    }

    /** A schermo spento il telefono non deve addormentarsi né spegnere il Wi-Fi mentre suona. */
    @Suppress("DEPRECATION")
    private fun acquireCpu(timeoutMs: Long) {
        val wake = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Xonos:audio")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        wake.acquire(timeoutMs)
    }

    private fun acquireLocks() {
        acquireCpu(LOCK_TIMEOUT_MS)
        val wifi = wifiLock ?: applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Xonos:audio")
            ?.apply { setReferenceCounted(false) }
            ?.also { wifiLock = it }
        wifi?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    /** Xonos usa il Music Assistant sul telefono stesso (127.0.0.1). */
    private val onPhoneServer: Boolean
        get() = app.phoneServer?.url?.let { it == app.settings.load().homeUrl } == true

    /** Uscendo di casa il Wi-Fi lascia il posto alla rete mobile: le connessioni vecchie sono morte, si riapre tutto. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                val previous = lastNetwork
                lastNetwork = network
                if (previous != null && previous != network) {
                    // Il Music Assistant sul telefono è a 127.0.0.1: il cambio di rete non tocca la connessione, e
                    // ricollegarsi farebbe fermare la coda a Music Assistant (con Soloist il brano ripartirebbe).
                    if (onPhoneServer) {
                        Log.i(TAG, "Rete cambiata: Music Assistant è sul telefono, resto collegato")
                        return@post
                    }
                    Log.i(TAG, "Rete cambiata: mi ricollego")
                    networkChanged = true
                    app.repo.reconnect()
                }
            }
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {

        // Gearslip non è un'app di sistema: senza questo Media3 gli lascerebbe solo guardare, non comandare.
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val pkg = controller.packageName
            if (pkg != this@PlaybackService.packageName && pkg != SYSTEM_UI) {
                outsideChanged {
                    if (outside.add(controller)) Log.i(TAG, "Comanda da fuori: $pkg")
                }
            }
            return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session).build())
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            outsideChanged { outside.remove(controller) }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = tree.item(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            awaitConnected()
            try {
                LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params)
            } catch (e: Exception) {
                Log.w(TAG, "Elenco ${Logged.id(parentId)} non disponibile: ${Logged.error(e)}")
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            awaitConnected()
            val results = try {
                tree.search(query)
            } catch (e: Exception) {
                Log.w(TAG, "Ricerca non riuscita: ${Logged.error(e)}")
                emptyList()
            }
            searches[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            // onSearch ha già aspettato e messo i risultati.
            if (searches[query] == null) awaitConnected()
            try {
                val results = searches[query] ?: tree.search(query)
                LibraryResult.ofItemList(results.page(page, pageSize), params)
            } catch (e: Exception) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        // Titolo e copertina della voce scelta, così l'auto li mostra subito mentre Music Assistant prepara la coda.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(mediaItems.map(tree::resolve), startIndex, startPositionMs),
        )

        // L'auto preme play e il telefono non ha ancora la coda: si riprende quella di Music Assistant.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                listOf(MediaItem.Builder().setMediaId(Menu.RESUME).build()),
                0,
                C.TIME_UNSET,
            ),
        )

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating,
        ): ListenableFuture<SessionResult> = scope.future { setFavorite(rating) }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            rating: Rating,
        ): ListenableFuture<SessionResult> = scope.future { setFavorite(rating) }
    }

    /** Il cuore di Gearslip: aggiunge o toglie il brano dai preferiti di Music Assistant. */
    private suspend fun setFavorite(rating: Rating): SessionResult {
        val heart = (rating as? HeartRating)?.takeIf { it.isRated }?.isHeart
            ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val item = player.currentItem ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        val uri = item.uri ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
        player.showFavorite(item, heart)
        return try {
            app.library.setFavorite(uri, item.mediaType ?: "track", item.provider, item.itemId, heart)
            SessionResult(SessionResult.RESULT_SUCCESS)
        } catch (e: Exception) {
            Log.w(TAG, "Preferito non salvato: ${Logged.error(e)}")
            player.showFavorite(item, !heart)
            SessionResult(SessionError.ERROR_UNKNOWN)
        }
    }

    private fun List<MediaItem>.page(page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
        val from = page.toLong() * pageSize
        if (from >= size) return emptyList()
        return subList(from.toInt(), min(size.toLong(), from + pageSize).toInt())
    }

    private companion object {
        const val TAG = "Xonos"
        const val RATE = 48_000
        const val FOCUS_GRACE_MS = 30_000L
        const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val PREPARE_LOCK_MS = 2 * 60 * 1000L

        /** Quanto aspetta il menu dell'auto che Xonos si colleghi a Music Assistant (vedi [awaitConnected]). */
        const val BROWSE_WAIT_MS = 20_000L

        /** Lo stesso col Music Assistant sul telefono, che può dover ripartire (sull'emulatore circa 10 s). */
        const val PHONE_BROWSE_WAIT_MS = 45_000L
        const val PHONE_POLL_MS = 250L

        /** I browser di prima, come Gearslip (MediaBrowserServiceCompat.SERVICE_INTERFACE). */
        const val LEGACY_BROWSER = "android.media.browse.MediaBrowserService"

        /** La ripresa dei media del sistema: si lega anche lei, ma non è qualcuno che ascolta. */
        const val SYSTEM_UI = "com.android.systemui"

        /** I problemi di connessione che non passano da soli: il menu dell'auto non li aspetta. */
        val LASTING = setOf(
            Problem.BAD_TOKEN,
            Problem.TOO_OLD,
            Problem.DENIED,
            Problem.NOT_MUSIC_ASSISTANT,
            Problem.SETUP_REQUIRED,
            Problem.BAD_ADDRESS,
        )
        const val QUEUE_WAIT_MS = 10_000L
        const val QUEUE_POLL_MS = 200L

        /**
         * Music Assistant è almeno [major].[minor].[patch]. Severo: una versione che non si legge ("?", una build di
         * sviluppo "0.0.0") non lo è, e nemmeno una di prova della stessa ("2.10.8b1"); "2.11.0b7" sì.
         */
        fun atLeast(version: String?, major: Int, minor: Int, patch: Int): Boolean {
            val match = VERSION.find(version.orEmpty()) ?: return false
            val (a, b, c, rest) = match.destructured
            val found = listOf(a.toInt(), b.toInt(), c.toInt())
            val wanted = listOf(major, minor, patch)
            for (i in 0..2) {
                if (found[i] != wanted[i]) return found[i] > wanted[i]
            }
            // Uguale: solo se non è una versione di prova (niente dopo il terzo numero).
            return rest.isBlank()
        }

        private val VERSION = Regex("^\\s*(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,6})(.*)$")

        /** Le uscite del telefono stesso: staccarle non c'entra con il Bluetooth o le cuffie. */
        val BUILT_IN_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        )
    }
}
