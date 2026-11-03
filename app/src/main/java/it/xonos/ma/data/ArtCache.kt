package it.xonos.ma.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Le copertine scaricate per l'auto, la notifica e il lettore (vedi playback/Artwork): un file per
 * immagine e misura, al massimo [maxFiles]. Al cambio di brano la stessa copertina la chiedono
 * insieme la notifica, il mini-lettore (copertina e colore di fondo) e l'auto: si scarica una volta
 * sola, e chi arriva mentre si scarica aspetta quel file.
 */
class ArtCache(private val dir: File, http: OkHttpClient, private val maxFiles: Int = MAX_FILES) {

    private val client = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    // Le copertine che si stanno scaricando, ciascuna con il suo lucchetto.
    private val downloads = ConcurrentHashMap<String, Any>()

    /** Il file [name], scaricato da [url] (chiesto solo se serve) se non c'è ancora. Null se non si è potuto. */
    fun get(name: String, url: () -> String?): File? {
        val file = File(dir, name)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val lock = downloads.computeIfAbsent(name) { Any() }
        try {
            synchronized(lock) {
                // Chi aspettava trova il file scaricato da chi è arrivato prima.
                if (file.length() > 0) return file
                return download(url() ?: return null, file)
            }
        } finally {
            downloads.remove(name, lock)
        }
    }

    private fun download(url: String, file: File): File? {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        dir.mkdirs()
        // Un file temporaneo tutto suo: due download non scrivono mai nello stesso file.
        val temp = File.createTempFile(file.name, PART, dir)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (!temp.renameTo(file)) return file.takeIf { it.length() > 0 }
            prune()
            return file
        } catch (e: Exception) {
            Log.w(TAG, "Copertina non scaricata: ${Logged.error(e)}")
            return null
        } finally {
            temp.delete()
        }
    }

    /** Toglie le copertine usate meno di recente, e i pezzi di download rimasti a metà. */
    private fun prune() {
        val old = System.currentTimeMillis() - STALE_PART_MS
        dir.listFiles { f -> f.name.endsWith(PART) && f.lastModified() < old }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.name.endsWith(".img") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "Xonos"
        const val MAX_FILES = 400
        private const val PART = ".part"
        private const val STALE_PART_MS = 60 * 60_000L
    }
}
