package it.xonos.ma.server

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import it.xonos.ma.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Il pacchetto di Music Assistant sul telefono (server/package/make_payload.py): Python, Music Assistant con
 * i suoi servizi, FFmpeg e librespot, per l'ABI del telefono. Xonos lo scarica solo quando la persona lo
 * chiede, dalla release di GitHub di questa versione di Xonos, e lo usa solo se è firmato con la stessa chiave
 * di Xonos: nessun altro può metterne uno al suo posto. Sta fuori dai backup (noBackupFilesDir).
 */
class ServerPackage(private val context: Context, http: OkHttpClient) {

    /** Quello dell'app, ma un download fermo da mezzo minuto si arrende invece di aspettare per sempre. */
    private val http = http.newBuilder().readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS).build()

    /** L'ABI a 64 bit del telefono per cui c'è un pacchetto; null (un telefono a 32 bit): niente server. */
    val abi: String? = Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it in ABIS }

    private val base = File(context.noBackupFilesDir, "music-assistant-server")

    /** La cartella del pacchetto che serve a questa versione di Xonos. */
    val dir = File(base, BuildConfig.SERVER_PACKAGE)

    @Volatile
    var installed: Boolean = check()
        private set

    /** C'è quello di una versione precedente (Xonos è stato aggiornato e il server con lui). */
    val outdated: Boolean
        get() = !installed && base.listFiles()?.any { it.isDirectory && !it.name.endsWith(STAGING) && File(it, PAYLOAD).isFile } == true

    private val zipName: String get() = "xonos-server-$abi.zip"

    /** Un download alla volta: uno annullato può finire il pezzo che sta facendo mentre ne parte un altro. */
    private val busy = Mutex()

    /** Quanto pesa lo zip da scaricare; null se non si sa. */
    suspend fun size(): Long? = withContext(Dispatchers.IO) {
        if (abi == null) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url(zipName)).head().build()).execute().use { response ->
                if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() else null
            }
        }.getOrNull()
    }

    /** Scarica, controlla e mette al suo posto il pacchetto; quello vecchio, se c'era, se ne va. */
    suspend fun download(progress: (Long, Long) -> Unit) = busy.withLock { downloadNow(progress) }

    private suspend fun downloadNow(progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (abi == null) throw IOException("this phone isn't 64-bit")
        base.mkdirs()
        val part = File(base, "$zipName.part")
        // Un'estrazione interrotta (Xonos chiuso a metà) resta qui fino al download dopo: non è un pacchetto.
        val staging = File(base, dir.name + STAGING)
        try {
            val signature = fetch(url("$zipName.sig")).use { it.body?.bytes() } ?: throw IOException("no signature")
            fetch(url(zipName)).use { response ->
                val total = response.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                // Lo zip, e il pacchetto estratto che pesa circa tre volte tanto.
                val needed = if (total > 0) total * SPACE_FACTOR else MIN_SPACE
                val free = StatFs(base.path).availableBytes
                if (free < needed) throw IOException("not enough space: ${free / MB} MB free, ${needed / MB} MB needed")
                part.outputStream().use { out ->
                    val input = response.body?.byteStream() ?: throw IOException("empty answer")
                    val buffer = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
            }
            if (!signedLikeXonos(part, signature)) throw IOException("the package isn't signed like Xonos")
            staging.deleteRecursively()
            unzip(part, staging)
            val info = JSONObject(File(staging, PAYLOAD).readText())
            if (info.optString("version") != BuildConfig.SERVER_PACKAGE || info.optString("abi") != abi) {
                throw IOException("the package is ${info.optString("version")} for ${info.optString("abi")}")
            }
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IOException("couldn't put the package in place")
            // Le versioni precedenti non servono più.
            base.listFiles()?.filter { it != dir && it.isDirectory }?.forEach { it.deleteRecursively() }
        } finally {
            part.delete()
            staging.deleteRecursively()
            installed = check()
        }
    }

    /** Toglie il pacchetto (tutte le versioni). */
    fun remove() {
        base.deleteRecursively()
        installed = false
    }

    private fun check(): Boolean = abi != null && File(dir, PAYLOAD).isFile && File(dir, "bin/python3").isFile

    /**
     * Da dove si scarica: la release di GitHub di questa versione di Xonos. Le prove sull'emulatore mettono
     * un altro indirizzo nel file [OVERRIDE] della cartella privata dell'app: il pacchetto deve comunque
     * essere firmato come Xonos.
     */
    private fun url(name: String): String {
        val override = File(context.filesDir, OVERRIDE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        return (override ?: BuildConfig.SERVER_PACKAGE_URL).trimEnd('/') + "/" + name
    }

    private fun fetch(url: String): Response {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} for ${url.substringAfterLast('/')}")
        }
        return response
    }

    /** Firmato con la chiave che ha firmato Xonos (SHA-256 con RSA, o con ECDSA se la chiave è a curve ellittiche). */
    private fun signedLikeXonos(file: File, signature: ByteArray): Boolean = appKeys().any { key ->
        runCatching {
            val verifier = Signature.getInstance(if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA")
            verifier.initVerify(key)
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    verifier.update(buffer, 0, read)
                }
            }
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun appKeys(): List<PublicKey> {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }.orEmpty()
        val factory = CertificateFactory.getInstance("X.509")
        return signatures.map { factory.generateCertificate(it.toByteArray().inputStream()).publicKey }
    }

    /** Estrae lo zip in [target]; i programmi di bin/ diventano eseguibili. "Annulla" si ferma al file dopo. */
    private suspend fun unzip(zip: File, target: File) {
        val root = target.canonicalFile
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                coroutineContext.ensureActive()
                val file = File(root, entry.name).canonicalFile
                if (!file.path.startsWith(root.path + File.separator)) throw IOException("bad entry ${entry.name}")
                if (entry.isDirectory) {
                    file.mkdirs()
                    continue
                }
                file.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it, BUFFER) } }
                if (entry.name.startsWith("bin/")) file.setExecutable(true, false)
            }
        }
    }

    companion object {
        /** Le ABI per cui si costruisce il pacchetto: Python 3.13 per Android c'è solo a 64 bit. */
        val ABIS = listOf("arm64-v8a", "x86_64")
        const val OVERRIDE = "server-package-url"
        private const val PAYLOAD = "payload.json"
        private const val STAGING = ".new"
        private const val BUFFER = 64 * 1024
        private const val MB = 1024L * 1024
        private const val SPACE_FACTOR = 4L
        private const val MIN_SPACE = 300 * MB
        private const val READ_TIMEOUT_S = 30L
    }
}
