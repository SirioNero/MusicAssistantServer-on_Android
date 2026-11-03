package it.xonos.ma

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.Services
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.ServerKeeper
import it.xonos.ma.playback.PlaybackService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.concurrent.thread

/**
 * Solo nella build di prova: scarica il pacchetto di Music Assistant sul telefono (se non c'è già), lo avvia
 * e mostra se gira. Il workflow "Server MA" mette l'indirizzo del pacchetto di prova nel file
 * files/server-package-url, la apre con `am start`, poi parla col server da fuori (adb forward). Nel registro
 * (tag XonosServer) il download e ogni cambio di stato, con i secondi dall'avvio.
 *
 * Con `--ez xonos true` fa anche quello che farebbe una persona: "Usa Music Assistant su questo
 * telefono" (account e token come dal tasto nelle impostazioni), la cartella files/music come servizio,
 * e il primo brano suonato dal lettore di Xonos stesso (il servizio di riproduzione e il suo motore audio).
 */
class ServerCheckActivity : Activity() {
    @Volatile private var open = true
    private val scope = MainScope()
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 18f
        }
        setContentView(text)
        val app = application as XonosApp
        // Finché la prova è aperta il server sul telefono serve: Xonos non lo deve fermare (vedi ServerKeeper).
        app.keeper.hold(ServerKeeper.Need.SCREEN)
        val server = app.phoneServer
        if (server == null) {
            say("Su questo telefono Music Assistant non può girare (non è a 64 bit).")
            Log.i(TAG, "Server: STOPPED (telefono a 32 bit)")
            return
        }
        app.repo.acquire()
        thread(name = "server-check") {
            val started = SystemClock.elapsedRealtime()
            thread(name = "server-start") {
                val ok = runCatching {
                    runBlocking {
                        if (!server.installed) download(server)
                        server.start()
                    }
                }.onFailure { say("Server: FAILED $it") }.getOrDefault(false)
                // Se non parte prima ancora di dire "starting" (Python che non si avvia), lo stato resta STOPPED.
                if (!ok && server.state == PhoneServerControl.State.STOPPED) say("Server: FAILED ${server.error}".trim())
            }
            // STOPPED è lo stato di prima dell'avvio: non si dice.
            var last: String? = PhoneServerControl.State.STOPPED.name
            while (open) {
                val state = server.state.name
                if (state != last) {
                    val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                    say("Server: $state dopo $seconds s ${server.error}".trim())
                    last = state
                }
                Thread.sleep(500)
            }
        }
        if (intent.getBooleanExtra("xonos", false)) {
            scope.launch {
                try {
                    xonosCheck(app)
                } catch (e: Exception) {
                    say("Xonos: prova interrotta: $e")
                }
            }
        }
    }

    private suspend fun xonosCheck(app: XonosApp) {
        val model = app.phone
        // Il pacchetto lo scarica il thread che avvia il server: si aspetta che sia al suo posto.
        val server = app.phoneServer ?: return
        withTimeout(DOWNLOAD_MS) { while (!server.installed) delay(500) }
        val page = model.settingsPage()
        model.usePhoneServer(page)
        withTimeout(START_MS) {
            while (page.checking || (page.results.isEmpty() && page.phoneServerError == null)) delay(200)
        }
        val connected = page.results.any { it is ConnectionCheck.Result.Ok }
        say("Xonos: collegato al server sul telefono = $connected ${page.results} ${page.phoneServerError.orEmpty()}".trim())
        if (!connected) return

        val services = Services(app.repo)
        var step = services.setup("filesystem_local")
        if (step.type == "form") {
            step = services.submit(step.flowId, mapOf("content_type" to "music", "path" to File(filesDir, "music").path))
        }
        say("Xonos: cartella del telefono come servizio (${step.type})")
        app.repo.command("music/sync")
        var tracks: List<MaItem> = emptyList()
        withTimeout(SYNC_MS) {
            while (tracks.isEmpty()) {
                delay(1_000)
                tracks = app.library.libraryItems("tracks")
            }
        }
        say("Xonos: ${tracks.size} brani")

        // Il lettore di Xonos parte quando un MediaController si collega al servizio, come fa lo schermo.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controller = MediaController.Builder(this, token).buildAsync().await()
        try {
            withTimeout(READY_MS) { app.playerReady.first { it } }
            say("Xonos: lettore pronto")
            app.library.playMedia(app.settings.playerId, listOf(tracks.first().uri))
            delay(LISTEN_MS)
            say("Xonos: suona=${controller.isPlaying} posizione=${controller.currentPosition / 1000.0} s brano=${controller.mediaMetadata.title}")
        } finally {
            controller.release()
        }
    }

    /** Il pacchetto, come dal tasto "Fai diventare Xonos il tuo server Music Assistant": un mega ogni dieci nel registro. */
    private suspend fun download(server: PhoneServerControl) {
        val begun = SystemClock.elapsedRealtime()
        say("Pacchetto: ${server.downloadSize()?.let { "${it / MB} MB" } ?: "dimensione sconosciuta"}")
        var said = 0L
        server.download { done, total ->
            if (done - said >= 10 * MB || done == total) {
                said = done
                say("Pacchetto: ${done / MB} di ${total / MB} MB")
            }
        }
        say("Pacchetto scaricato e controllato in ${(SystemClock.elapsedRealtime() - begun) / 1000.0} s")
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { text.append(line + "\n") }
    }

    override fun onDestroy() {
        open = false
        scope.cancel()
        (application as XonosApp).let {
            if (it.phoneServer != null) it.repo.release()
            it.keeper.release(ServerKeeper.Need.SCREEN)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "XonosServer"
        const val START_MS = 240_000L
        const val SYNC_MS = 120_000L
        const val READY_MS = 60_000L
        const val LISTEN_MS = 12_000L
        const val MB = 1024L * 1024
        const val DOWNLOAD_MS = 600_000L
    }
}
