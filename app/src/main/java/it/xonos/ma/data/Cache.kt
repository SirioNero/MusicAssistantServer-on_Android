package it.xonos.ma.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Le ultime schermate caricate (la Home, le prime pagine della libreria), un file JSON per
 * ciascuna nella cartella [dir]: all'apertura dell'app si mostrano subito, mentre arrivano quelle
 * nuove. Senza cartella non si salva niente. Si legge e si scrive fuori dal thread principale.
 */
class ScreenCache(private val dir: File?) {

    fun read(name: String): JSONObject? {
        val file = file(name) ?: return null
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            // Senza l'errore: il testo di org.json avrebbe tutto il file (titoli della Home e della libreria).
            Log.w(TAG, "Cache illeggibile: $name (${e.javaClass.simpleName})")
            file.delete()
            null
        }
    }

    /** Scrive tutto o niente: prima un file a parte, poi lo si mette al posto di quello vecchio. */
    fun write(name: String, value: JSONObject) {
        val file = file(name) ?: return
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(value.toString())
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Cache non salvata: $name", e)
        }
    }

    /** Via tutto: per esempio quando si cambia server. */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    private fun file(name: String): File? = dir?.let { File(it, "$name.json") }

    private companion object {
        const val TAG = "Xonos"
    }
}

/** Un elemento com'è in Xonos, per la cache (non è il formato di Music Assistant). */
fun MaItem.toCache(): JSONObject = JSONObject()
    .put("uri", uri)
    .put("type", mediaType)
    .put("id", itemId)
    .put("provider", provider)
    .put("name", name)
    .putOpt("subtitle", subtitle)
    .putOpt("image", image?.toCache())
    .put("playable", playable)
    .put("favorite", favorite)
    .put("duration", durationSec)
    .putOpt("path", path)
    .put("inline", JSONArray(inline.map { it.toCache() }))
    .putOpt("sort_name", sortName)
    .put("year", year)
    .put("mappings", JSONArray(mappings.map { (provider, id) -> JSONArray().put(provider).put(id) }))
    .put("artists", JSONArray(artists.map { it.toCache() }))
    .putOpt("album", album?.toCache())
    .put("track", trackNumber)
    .put("disc", discNumber)
    .put("resume", resumeMs)
    .put("played", fullyPlayed)
    .put("position", position)
    .put("editable", editable)

/** Il contrario di [toCache]; null se il file è di una versione che non si capisce. */
fun cachedItem(o: JSONObject): MaItem? {
    val uri = o.str("uri") ?: return null
    return MaItem(
        uri = uri,
        mediaType = o.str("type") ?: return null,
        itemId = o.str("id").orEmpty(),
        provider = o.str("provider").orEmpty(),
        name = o.str("name") ?: return null,
        subtitle = o.str("subtitle"),
        image = o.optJSONObject("image")?.let(::cachedImage),
        playable = o.optBoolean("playable"),
        favorite = o.optBoolean("favorite"),
        durationSec = o.optInt("duration"),
        path = o.str("path"),
        inline = cachedItems(o.optJSONArray("inline")),
        sortName = o.str("sort_name"),
        year = o.optInt("year"),
        mappings = (0 until (o.optJSONArray("mappings")?.length() ?: 0)).mapNotNull { i ->
            val pair = o.getJSONArray("mappings").optJSONArray(i) ?: return@mapNotNull null
            pair.optString(0) to pair.optString(1)
        },
        artists = o.optJSONArray("artists").objects().mapNotNull(::cachedRef),
        album = o.optJSONObject("album")?.let(::cachedRef),
        trackNumber = o.optInt("track"),
        discNumber = o.optInt("disc"),
        resumeMs = o.optLong("resume"),
        fullyPlayed = o.optBoolean("played"),
        position = o.optInt("position"),
        editable = o.optBoolean("editable"),
    )
}

fun cachedItems(array: JSONArray?): List<MaItem> = array.objects().mapNotNull(::cachedItem)

fun List<MaItem>.toCache(): JSONArray = JSONArray(map { it.toCache() })

private fun MaImage.toCache(): JSONObject = JSONObject()
    .put("path", path)
    .put("provider", provider)
    .putOpt("proxy_id", proxyId)
    .put("remote", remote)

private fun cachedImage(o: JSONObject): MaImage? {
    val path = o.str("path") ?: return null
    return MaImage(path, o.str("provider").orEmpty(), o.str("proxy_id"), o.optBoolean("remote"))
}

private fun Ref.toCache(): JSONObject = JSONObject().put("uri", uri).put("name", name)

private fun cachedRef(o: JSONObject): Ref? {
    val uri = o.str("uri") ?: return null
    return Ref(uri, o.str("name") ?: return null)
}
