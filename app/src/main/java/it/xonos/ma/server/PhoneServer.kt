package it.xonos.ma.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import it.xonos.ma.BuildConfig
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Music Assistant sul telefono: il pacchetto scaricato ([ServerPackage]) avviato come processo a sé,
 * `python3 -m xonos_server` (server/src/main/python/xonos_server.py). Da Android 10 un'app non può avviare i
 * programmi che ha scaricato: li fa partire il linker di sistema (`/system/bin/linker64 <programma>`), anche
 * ffmpeg, ffprobe e librespot quando li chiama Music Assistant. Il processo vive quanto Xonos; i dati di
 * Music Assistant (account, servizi, libreria) restano nella cartella dell'app anche quando il pacchetto cambia.
 *
 * Soloist (il motore ufficiale di Spotify) e PulseAudio sono programmi per Linux: girano dentro proot, che sta tra
 * le librerie dell'app (app/native/proot.sh). Xonos dice dov'è (XONOS_PROOT) e tiene aggiornato il file del DNS
 * che quei programmi usano (Android non ha resolv.conf). Mentre Soloist si fa trovare dall'app Spotify per
 * l'abbinamento, il server lo dice (XONOS_MULTICAST on/off) e Xonos lascia passare il multicast del Wi-Fi.
 */
class PhoneServer private constructor(context: Context, private val pkg: ServerPackage) : PhoneServerControl {

    private val app = context.applicationContext
    private val data = File(context.filesDir, "music-assistant")
    private val cache = File(context.cacheDir, "music-assistant")
    private val dnsFile = File(data, "resolv.conf")
    private var dnsWatch: ConnectivityManager.NetworkCallback? = null
    private val multicastLock: WifiManager.MulticastLock? by lazy {
        app.getSystemService(WifiManager::class.java)?.createMulticastLock("xonos-soloist")?.apply {
            setReferenceCounted(false)
        }
    }
    private val lock = Any()
    private var process: Process? = null
    private var stopping = false

    override val url = URL

    @Volatile
    override var state = State.STOPPED
        private set

    @Volatile
    override var error = ""
        private set

    override val installed: Boolean get() = pkg.installed

    override val outdated: Boolean get() = pkg.outdated

    override suspend fun downloadSize(): Long? = pkg.size()

    override suspend fun download(progress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) { stop() }
        pkg.download(progress)
    }

    // Fermarlo aspetta Music Assistant, e il pacchetto estratto sono migliaia di file: niente sul thread dello schermo.
    override suspend fun remove() = withContext(Dispatchers.IO) {
        stop()
        pkg.remove()
        data.deleteRecursively()
        cache.deleteRecursively()
        Unit
    }

    override suspend fun log(): String = withContext(Dispatchers.IO) {
        val head = "Xonos ${BuildConfig.VERSION_NAME}, Music Assistant ${BuildConfig.SERVER_PACKAGE} (${pkg.abi}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${state.name.lowercase()} $error".trim()
        val file = File(data, "musicassistant.log")
        "$head\n\n" + if (file.isFile) tail(file) else "(no log yet)"
    }

    /** Le ultime [LOG_BYTES] del registro, da una riga intera. */
    private fun tail(file: File): String = RandomAccessFile(file, "r").use { reader ->
        val length = reader.length()
        val start = maxOf(0L, length - LOG_BYTES)
        val bytes = ByteArray((length - start).toInt())
        reader.seek(start)
        reader.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        if (start > 0) text.substringAfter('\n') else text
    }

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        launch()
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (state) {
                State.RUNNING -> return@withContext true
                State.FAILED, State.STOPPED -> return@withContext false
                State.STARTING -> delay(POLL_MS)
            }
        }
        error = "it didn't start in ${START_TIMEOUT_MS / 1000} s"
        false
    }

    override fun stop() {
        val running = synchronized(lock) {
            stopping = true
            process
        } ?: return
        // SIGTERM: Music Assistant chiude il database e i servizi; se non basta, SIGKILL. Su Android
        // destroyForcibly() manda un altro SIGTERM, che Python ignora (sta già chiudendo): il SIGKILL va mandato a mano.
        running.destroy()
        if (!running.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
            val pid = PID.find(running.toString())?.groupValues?.get(1)?.toIntOrNull()
            if (pid != null) runCatching { Os.kill(pid, OsConstants.SIGKILL) } else running.destroyForcibly()
            running.waitFor(KILL_WAIT_S, TimeUnit.SECONDS)
        }
    }

    private fun launch() {
        synchronized(lock) { launchLocked() }
    }

    private fun launchLocked() {
        if (process?.isAlive == true) return
        if (!pkg.installed) {
            state = State.FAILED
            error = "Music Assistant isn't on this phone"
            return
        }
        stopping = false
        state = State.STARTING
        error = ""
        val dir = pkg.dir
        val tmp = File(cache, "tmp").apply { mkdirs() }
        data.mkdirs()
        val command = buildList {
            if (Build.VERSION.SDK_INT >= W_X_SDK) add(LINKER)
            add(File(dir, "bin/python3").path)
            addAll(listOf("-m", "xonos_server", "--data", data.path, "--cache", cache.path))
        }
        val builder = ProcessBuilder(command).redirectErrorStream(true).directory(data)
        val libs = File(app.applicationInfo.nativeLibraryDir)
        val proot = File(libs, "libproot.so")
        val prootLoader = File(libs, "libproot-loader.so")
        watchDns()
        builder.environment().apply {
            if (proot.isFile && prootLoader.isFile) {
                put("XONOS_PROOT", proot.path)
                put("XONOS_PROOT_LOADER", prootLoader.path)
            }
            put("XONOS_DNS_FILE", dnsFile.path)
            put("PYTHONHOME", dir.path)
            put("PYTHONPATH", "$dir/app:$dir/site-packages")
            put("LD_LIBRARY_PATH", "$dir/lib:$dir/site-packages/chaquopy/lib")
            put("PATH", "$dir/bin:" + (get("PATH") ?: "/system/bin"))
            put("XONOS_BIN", "$dir/bin")
            if (Build.VERSION.SDK_INT >= W_X_SDK) put("XONOS_LINKER", LINKER)
            put("SSL_CERT_FILE", "$dir/lib/cacert.pem")
            put("HOME", data.path)
            put("TMPDIR", tmp.path)
            put("LANG", "C.UTF-8")
            put("PYTHONUTF8", "1")
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONDONTWRITEBYTECODE", "1")
        }
        val started = try {
            builder.start()
        } catch (e: Exception) {
            state = State.FAILED
            error = e.message ?: e.toString()
            Log.w(TAG, "Music Assistant non parte", e)
            return
        }
        process = started
        Log.i(TAG, "Avvio di Music Assistant sul telefono")
        val reader = thread(name = "music-assistant-log", isDaemon = true) { follow(started) }
        thread(name = "music-assistant-exit", isDaemon = true) {
            runCatching { started.waitFor() }
            // Python è uscito ma il tubo è ancora aperto: lo tiene un programma rimasto acceso (pacchetti fino al
            // 2.10.8-5, PulseAudio). Lo si chiude da qui: il registro finisce e lo stato dice che il server non c'è.
            reader.join(EXIT_GRACE_MS)
            if (reader.isAlive) runCatching { started.inputStream.close() }
        }
    }

    /** Il registro del processo nel logcat; le righe XONOS_STATE dicono a che punto è. */
    private fun follow(running: Process) {
        val last = ArrayDeque<String>()
        runCatching {
            running.inputStream.bufferedReader().forEachLine { line ->
                // Con i startsWith anche una riga a cui si è attaccata una riga del registro (pacchetti fino al 2.10.8-5,
                // che scrivevano testo e a capo in due volte): "XONOS_STATE running2026-11-01 ...".
                if (line.startsWith(MULTICAST_PREFIX)) {
                    multicast(line.removePrefix(MULTICAST_PREFIX).trim().startsWith("on"))
                } else if (line.startsWith(STATE_PREFIX)) {
                    val said = line.removePrefix(STATE_PREFIX).trim()
                    when {
                        said.startsWith("running") -> state = State.RUNNING
                        said.startsWith("starting") -> state = State.STARTING
                        said.startsWith("stopped") -> state = State.STOPPED
                        said.startsWith("failed") -> {
                            error = said.removePrefix("failed").trim()
                            state = State.FAILED
                        }
                    }
                } else {
                    Log.i(TAG, line)
                    last.addLast(line)
                    if (last.size > LAST_LINES) last.removeFirst()
                }
            }
        }
        val code = running.waitFor()
        multicast(false)
        synchronized(lock) {
            // Un processo di prima (il suo tubo è rimasto aperto più di lui) non tocca lo stato di quello nuovo.
            if (process !== running) return@synchronized
            process = null
            unwatchDns()
            if (stopping) {
                state = State.STOPPED
            } else if (state != State.FAILED) {
                state = State.FAILED
                error = "it stopped (exit $code): ${last.lastOrNull().orEmpty()}".trim()
            }
        }
        Log.i(TAG, "Music Assistant sul telefono si è fermato ($code)")
    }

    /** Il multicast del Wi-Fi mentre Soloist si fa trovare dall'app Spotify (mDNS); spento il resto del tempo. */
    private fun multicast(on: Boolean) {
        val lock = multicastLock ?: return
        runCatching { if (on) lock.acquire() else if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "Multicast del Wi-Fi", it) }
    }

    /**
     * Il file del DNS per i programmi Linux dentro proot (il loro /etc/resolv.conf): i server DNS della rete in uso,
     * più due pubblici di riserva, riscritto quando la rete cambia finché il server gira.
     */
    private fun watchDns() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return
        writeDns(connectivity.getLinkProperties(connectivity.activeNetwork))
        if (dnsWatch != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = writeDns(properties)
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { dnsWatch = callback }
            .onFailure { Log.w(TAG, "DNS per i programmi Linux", it) }
    }

    private fun unwatchDns() {
        val callback = dnsWatch ?: return
        dnsWatch = null
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun writeDns(properties: LinkProperties?) {
        val servers = properties?.dnsServers.orEmpty().mapNotNull { it.hostAddress } + FALLBACK_DNS
        // glibc ne legge al massimo tre.
        val text = servers.distinct().take(3).joinToString("") { "nameserver $it\n" }
        runCatching {
            data.mkdirs()
            val next = File(data, "resolv.conf.new")
            next.writeText(text)
            next.renameTo(dnsFile)
        }.onFailure { Log.w(TAG, "DNS per i programmi Linux", it) }
    }

    companion object {
        const val URL = "http://127.0.0.1:8095"
        private const val TAG = "XonosServer"
        private const val LINKER = "/system/bin/linker64"
        /** Android 10: da qui le app non avviano più i programmi che scaricano (W^X). */
        private const val W_X_SDK = 29
        private const val STATE_PREFIX = "XONOS_STATE"
        private const val MULTICAST_PREFIX = "XONOS_MULTICAST"
        /** Se la rete non dice i suoi server DNS (o non bastano). */
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
        /** Il primo avvio dopo il download prepara il database: su un telefono lento ci vuole un po'. */
        private const val START_TIMEOUT_MS = 180_000L
        private const val POLL_MS = 250L
        private const val STOP_TIMEOUT_S = 15L
        private const val KILL_WAIT_S = 2L

        /** Quanto si lascia al registro per finire da solo dopo l'uscita di Python. */
        private const val EXIT_GRACE_MS = 2_000L

        /** Il numero del processo, da Process.toString() di Android: "Process[pid=1234, hasExited=false]". */
        private val PID = Regex("pid=(\\d+)")
        private const val LAST_LINES = 20
        /** Quanto registro si condivide: le ultime righe bastano, e stanno in una condivisione di Android. */
        private const val LOG_BYTES = 96 * 1024

        /** Music Assistant sul telefono, dove può girare (un telefono a 64 bit); altrimenti null. */
        fun create(context: Context, http: OkHttpClient): PhoneServerControl? {
            val pkg = ServerPackage(context.applicationContext, http)
            return if (pkg.abi != null) PhoneServer(context.applicationContext, pkg) else null
        }
    }
}
