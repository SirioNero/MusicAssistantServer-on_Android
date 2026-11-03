package it.xonos.ma.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.system.Os
import android.system.OsConstants
import android.util.Log
import it.xonos.ma.BuildConfig
import it.xonos.ma.XonosApp
import it.xonos.ma.phone.model.ServerKeeper
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Il ponte per myCar, che ha Xonos dentro di sé. Per collegarsi all'auto senza fili, myCar va
 * lasciata fuori da Tailscale (dentro una VPN Android non le dà il Wi-Fi dell'auto), e allora non
 * raggiunge più Music Assistant su un indirizzo Tailscale; Xonos, dentro Tailscale, sì. Così myCar
 * si lega a questo servizio durante il viaggio e gli chiede le sue connessioni: Xonos apre quella
 * verso Music Assistant e la passa a myCar come socket locale, copiando i dati nei due versi.
 * Il legame tiene sveglio Xonos quanto myCar, che in auto è in primo piano.
 *
 * Solo a myCar firmata con il suo certificato, come [AccountProvider], e solo verso gli indirizzi
 * di Music Assistant scritti qui: non è una porta verso il resto della rete. La build di prova di
 * Xonos accetta anche la build di prova di myCar, che ha un'altra firma: serve alle prove in CI.
 *
 * myCar fa: bindService(Intent("io.github.sirionero.mycar.BRIDGE").setPackage("it.xonos.ma"), ...),
 * poi transact(OPEN) con l'interfaccia [DESCRIPTOR], host e porta; risposta [OK] e il socket, o il codice
 * del rifiuto ([NOT_MYCAR], [NOT_OURS], [NO_ANSWER], [OTHER]) e il motivo.
 */
class MyCarBridge : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != OPEN) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val host = data.readString().orEmpty()
            val port = data.readInt()
            val result = runCatching { open(Binder.getCallingUid(), host, port) }
            if (reply == null) {
                result.getOrNull()?.close()
                return true
            }
            result.onSuccess { socket ->
                reply.writeInt(OK)
                socket.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            }.onFailure { e ->
                // Indirizzo e testo dell'errore solo nella build di prova (vedi Logged).
                val code = (e as? Refusal)?.code ?: OTHER
                Log.w(TAG, "Ponte per myCar non aperto: $code" + Logged.text(" verso $host:$port: ${e.message}", ""))
                reply.writeInt((e as? Refusal)?.code ?: OTHER)
                reply.writeString(e.message ?: e.javaClass.simpleName)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val app: XonosApp get() = applicationContext as XonosApp

    // myCar si lega per il viaggio e si stacca qualche minuto dopo l'ultima connessione: intanto il Music Assistant sul
    // telefono, se è quello di Xonos, resta acceso (vedi ServerKeeper).
    override fun onCreate() {
        super.onCreate()
        app.keeper.hold(ServerKeeper.Need.MYCAR)
    }

    override fun onDestroy() {
        app.keeper.release(ServerKeeper.Need.MYCAR)
        super.onDestroy()
    }

    /** La connessione verso [host]:[port], per myCar: l'altro capo di un socket locale. */
    private fun open(uid: Int, host: String, port: Int): ParcelFileDescriptor {
        val caller = packageManager.getPackagesForUid(uid).orEmpty().firstOrNull { isMyCar(this, it) }
            ?: throw Refusal(NOT_MYCAR, "il ponte è solo per myCar")
        val urls = app.settings.load().urls
        if (!allowed(urls, host, port)) throw Refusal(NOT_OURS, "$host:$port non è il Music Assistant di Xonos")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            // Il Music Assistant sul telefono non gira (fermo perché nessuno lo usava, o chiuso da Android): parte adesso.
            // myCar intanto dice che il server sta partendo e riprova da sé.
            val server = app.phoneServer
            if (server != null && allowed(listOf(server.url), host, port)) app.keeper.touch(ServerKeeper.Need.MYCAR)
            throw Refusal(NO_ANSWER, "Music Assistant a $host:$port non risponde a Xonos (${e.message})")
        }
        val (mine, theirs) = ParcelFileDescriptor.createSocketPair()
        relay(socket, mine)
        Log.i(TAG, "Ponte per $caller verso Music Assistant (porta $port)")
        return theirs
    }

    /** Copia i dati tra il socket di Music Assistant e quello locale finché uno dei due si chiude. */
    private fun relay(socket: Socket, local: ParcelFileDescriptor) {
        val closed = AtomicBoolean(false)
        val ends = java.util.concurrent.atomic.AtomicInteger(0)
        fun closeAll() {
            if (closed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                runCatching { local.close() }
            }
        }
        fun ended() {
            if (ends.incrementAndGet() == 2) closeAll()
        }
        thread(name = "xonos-ponte-su", isDaemon = true) {
            // Da myCar a Music Assistant; finito quello che myCar manda, lo si dice al server.
            try {
                copy(FileInputStream(local.fileDescriptor), socket.getOutputStream())
                socket.shutdownOutput()
            } catch (e: IOException) {
                closeAll()
            } finally {
                ended()
            }
        }
        thread(name = "xonos-ponte-giu", isDaemon = true) {
            // Da Music Assistant a myCar; chiuso il server, myCar legge la fine.
            try {
                copy(socket.getInputStream(), FileOutputStream(local.fileDescriptor))
                Os.shutdown(local.fileDescriptor, OsConstants.SHUT_WR)
            } catch (e: Exception) {
                closeAll()
            } finally {
                ended()
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            output.write(buffer, 0, n)
        }
    }

    /** Un rifiuto, col codice che myCar trasforma nelle sue parole. */
    private class Refusal(val code: Int, message: String) : Exception(message)

    companion object {
        private const val TAG = "Xonos"

        /** L'interfaccia del ponte, la stessa in Plancia e in myCar. */
        const val DESCRIPTOR = "io.github.sirionero.mycar.Bridge"

        /** Apri una connessione: host (String), porta (Int). */
        const val OPEN = IBinder.FIRST_CALL_TRANSACTION

        /** Le risposte: il socket, o il codice del rifiuto e il motivo. */
        const val OK = 1
        const val OTHER = 0
        const val NOT_MYCAR = 2
        const val NOT_OURS = 3
        const val NO_ANSWER = 4

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val BUFFER = 16 * 1024

        /** myCar e la sua build di prova. */
        private val MYCAR = setOf("io.github.sirionero.mycar", "io.github.sirionero.mycar.dev")
        private const val MYCAR_DEV = "io.github.sirionero.mycar.dev"

        /** Il certificato di firma di myCar (SHA-256), come in [AccountProvider]. */
        private const val MYCAR_CERT_SHA256 = "e477a4794b984927360e9ae4a31e87cb08a26c54e0e27e4334116d1c7cdccc31"

        /** Il pacchetto è di myCar ed è firmato con il suo certificato; la prova di Xonos accetta anche quella di myCar. */
        fun isMyCar(context: Context, pkg: String): Boolean {
            if (pkg !in MYCAR) return false
            if (BuildConfig.DEBUG && pkg == MYCAR_DEV) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSigningCertificate(pkg, hex(MYCAR_CERT_SHA256), PackageManager.CERT_INPUT_SHA256)
        }

        private fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        /** Se [host]:[port] è uno degli indirizzi di Music Assistant scritti in Xonos (la porta di default dallo schema). */
        fun allowed(urls: List<String>, host: String, port: Int): Boolean = urls.any { url ->
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return@any false
            val name = uri.host?.removeSurrounding("[", "]")?.lowercase() ?: return@any false
            val own = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)) 443 else 80
            name == host.removeSurrounding("[", "]").lowercase() && own == port
        }
    }
}
