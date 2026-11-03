package it.xonos.ma.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import it.xonos.ma.BuildConfig
import it.xonos.ma.Language
import it.xonos.ma.XonosApp
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Settings
import it.xonos.ma.describe as tell
import it.xonos.ma.phone.model.ServerKeeper
import it.xonos.ma.phone.ui.Host
import it.xonos.ma.phone.ui.XonosScreen
import it.xonos.ma.playback.PlaybackService
import it.xonos.ma.wrap

/**
 * L'app sul telefono: libreria, ricerca e lettore, disegnati con Compose (vedi phone/ui). Lo stato
 * sta in [XonosApp.phone], così resta uguale se lo schermo ruota o cambia la lingua; il lettore è
 * quello del servizio, raggiunto con un MediaController finché l'app è in primo piano.
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: XonosApp
    private val link = ControllerLink()
    private var controller: ListenableFuture<MediaController>? = null
    private var batteryRestricted by mutableStateOf(false)

    // Le scritte nella lingua scelta in Xonos, non in quella del telefono.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings(newBase).language.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sempre scura: le icone della barra di stato restano chiare.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        app = application as XonosApp
        // Solo alla prima apertura: dopo una rotazione o un cambio di lingua l'Intent è lo stesso, e il lettore si
        // riaprirebbe ogni volta anche se lo si era chiuso.
        if (savedInstanceState == null) openPlayerIfAsked(intent)
        val host = object : Host {
            override fun describe(connection: Connection) = tell(this@MainActivity, connection)

            override fun describe(error: Throwable) = tell(this@MainActivity, error)

            override val language: Language get() = app.settings.language

            override fun changeLanguage(language: Language) {
                app.settings.language = language
                app.repo.languageChanged()
                recreate()
            }

            override val batteryRestricted: Boolean get() = this@MainActivity.batteryRestricted

            override fun askBatteryExemption() = this@MainActivity.askBatteryExemption()

            override fun askMusicFiles() = this@MainActivity.askMusicFiles()

            override fun shareText(subject: String, text: String) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { startActivity(Intent.createChooser(send, subject)) }
            }

            override val version: String = BuildConfig.VERSION_NAME
        }
        setContent { XonosScreen(app.phone, link, host) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPlayerIfAsked(intent)
    }

    /** Dalla notifica si arriva direttamente al lettore. */
    private fun openPlayerIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PLAYER, false) == true) app.phone.playerOpen = true
    }

    override fun onStart() {
        super.onStart()
        // Finché l'app è in vista, il telefono è collegato a Music Assistant e il servizio (il lettore) è acceso. Il
        // Music Assistant sul telefono, se Xonos usa quello, parte adesso (vedi ServerKeeper).
        app.keeper.hold(ServerKeeper.Need.SCREEN)
        app.repo.acquire()
        app.phone.wake()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controller = future
        future.addListener(
            { runCatching { future.get() }.getOrNull()?.let(link::attach) },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onResume() {
        super.onResume()
        batteryRestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) != true
    }

    override fun onStop() {
        link.detach()
        controller?.let(MediaController::releaseFuture)
        controller = null
        app.repo.release()
        // Il server sul telefono resta acceso ancora qualche minuto (tornando subito non si aspetta l'avvio); di più durante
        // l'accesso a un servizio nel browser o l'abbinamento di Soloist nell'app Spotify: il flusso vive in Music Assistant.
        app.keeper.release(
            ServerKeeper.Need.SCREEN,
            if (app.phone.inSetupFlow) ServerKeeper.SETUP_SCREEN_MS else ServerKeeper.Need.SCREEN.graceMs,
        )
        super.onStop()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        // Con l'ottimizzazione della batteria Android può fermare la musica a schermo spento.
        val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    /** La versione con il server lo dichiara nel manifest; da Android 13 il permesso è solo per l'audio. */
    private fun askMusicFiles() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_MUSIC_FILES)
        }
    }

    companion object {
        /** Nell'intent: apri il lettore a schermo intero. */
        const val EXTRA_PLAYER = "player"

        private const val REQUEST_MUSIC_FILES = 1
    }
}
