package it.xonos.ma.data

import android.content.Context
import android.util.Base64
import it.xonos.ma.Language
import java.net.URI
import java.security.SecureRandom

/** Indirizzi, token e preferenze del lettore, salvati nelle preferenze private dell'app. */
class Settings(context: Context) {

    data class Config(val homeUrl: String, val remoteUrl: String, val token: String) {
        /** Prima l'indirizzo di casa, poi quello remoto. */
        val urls: List<String> get() = listOf(homeUrl, remoteUrl).filter { it.isNotBlank() }.distinct()
        val isComplete: Boolean get() = urls.isNotEmpty() && token.isNotBlank()
    }

    private val prefs = context.getSharedPreferences("xonos", Context.MODE_PRIVATE)

    fun load() = Config(
        homeUrl = prefs.getString(KEY_HOME, "").orEmpty(),
        remoteUrl = prefs.getString(KEY_REMOTE, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
    )

    fun save(config: Config) {
        prefs.edit()
            .putString(KEY_HOME, config.homeUrl)
            .putString(KEY_REMOTE, config.remoteUrl)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    /** La lingua scelta sul telefono, per lo schermo e per il menu in auto; l'inglese finché non se ne sceglie una. */
    var language: Language
        get() = Language.of(prefs.getString(KEY_LANGUAGE, null))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()
        }

    /** Chi ha fatto l'accesso con nome utente e password (null se il token è stato copiato a mano). */
    var username: String?
        get() = prefs.getString(KEY_USER, null)?.ifBlank { null }
        set(value) {
            prefs.edit().putString(KEY_USER, value).apply()
        }

    /** Il nome con cui il telefono compare in Music Assistant. */
    var playerName: String
        get() = prefs.getString(KEY_NAME, null)?.trim()?.ifEmpty { null } ?: DEFAULT_NAME
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim()).apply()
        }

    /**
     * L'identità del telefono come lettore: nasce una volta sola, così Music Assistant
     * lo riconosce a ogni viaggio e gli ritrova la coda dove l'aveva lasciata.
     */
    val playerId: String
        @Synchronized get() {
            prefs.getString(KEY_PLAYER_ID, null)?.let { return it }
            val id = "xonos-" + randomBytes(6).joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_PLAYER_ID, id).apply()
            return id
        }

    /** Volume (0-100) e muto decisi da Music Assistant: vanno ricordati tra una connessione e l'altra. */
    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 100)
        set(value) {
            prefs.edit().putInt(KEY_VOLUME, value.coerceIn(0, 100)).apply()
        }

    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTED, value).apply()
        }

    /**
     * La password dell'account che Xonos si è creato nel Music Assistant sul telefono (Xonos-server.apk):
     * la sceglie Xonos, e la tiene per rifarsi dare il token se lo perde. Quel server risponde solo al telefono.
     */
    var phoneServerPassword: String?
        get() = prefs.getString(KEY_PHONE_SERVER_PASSWORD, null)
        set(value) {
            prefs.edit().putString(KEY_PHONE_SERVER_PASSWORD, value).apply()
        }

    /**
     * La cronologia degli ascolti (Impostazioni › Per te, vedi data/taste/Journal.kt): accesa di base, come ha deciso
     * l'utente. Resta sul telefono, per un anno; spenta, Xonos non scrive nuovi ascolti.
     */
    var journalEnabled: Boolean
        get() = prefs.getBoolean(KEY_JOURNAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JOURNAL, value).apply()
        }

    /**
     * L'ultimo ascolto che la persona ha tolto dalla Home trascinando "Riprendi" (vedi LastListen.key): resta nascosto
     * fino al prossimo ascolto.
     */
    var hiddenResume: String?
        get() = prefs.getString(KEY_HIDDEN_RESUME, null)
        set(value) {
            prefs.edit().putString(KEY_HIDDEN_RESUME, value).apply()
        }

    /**
     * Il Music Assistant (server_id) dell'ultima Home collegata; null dopo un cambio di server, finché il nuovo non
     * risponde. Senza la Home salvata (è nella cache, che Android può svuotare) dice se la copia di "Riprendi" è di questo
     * server anche col server fermo.
     */
    var lastServerId: String?
        get() = prefs.getString(KEY_LAST_SERVER, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_SERVER, value).apply()
        }

    /**
     * I numeri della diagnosi di "Per te" (quanti artisti, con generi, con MBID) e per quale Music Assistant e versione
     * di Xonos li si è contati (vedi playback/Listening.kt, savedDiagnosis): si contano una volta per versione.
     */
    var perTeDiagnosis: String?
        get() = prefs.getString(KEY_PER_TE_DIAGNOSIS, null)
        set(value) {
            prefs.edit().putString(KEY_PER_TE_DIAGNOSIS, value).apply()
        }

    /** La sezione "Altro" della Home aperta: si ricorda tra un'apertura e l'altra. */
    var homeMoreOpen: Boolean
        get() = prefs.getBoolean(KEY_HOME_MORE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HOME_MORE, value).apply()
        }

    /** Chiave con cui l'app firma gli indirizzi delle copertine: le serve solo per sé (vedi ArtworkProvider). */
    val artKey: ByteArray
        @Synchronized get() {
            prefs.getString(KEY_ART, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val key = randomBytes(32)
            prefs.edit().putString(KEY_ART, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            return key
        }

    companion object {
        private const val KEY_HOME = "url_home"
        private const val KEY_REMOTE = "url_remote"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "player_name"
        private const val KEY_PLAYER_ID = "player_id"
        private const val KEY_VOLUME = "volume"
        private const val KEY_MUTED = "muted"
        private const val KEY_ART = "art_key"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_USER = "username"
        private const val KEY_PHONE_SERVER_PASSWORD = "phone_server_password"
        private const val KEY_JOURNAL = "journal_enabled"
        private const val KEY_HIDDEN_RESUME = "hidden_resume"
        private const val KEY_HOME_MORE = "home_more_open"
        private const val KEY_PER_TE_DIAGNOSIS = "per_te_diagnosis"
        private const val KEY_LAST_SERVER = "last_server_id"

        const val DEFAULT_NAME = "Xonos"

        /** La porta di Music Assistant, quando l'indirizzo non ne indica una. */
        private const val DEFAULT_PORT = 8095

        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
                // "homeassistant.local" senza porta: si intende l'interfaccia di Music Assistant.
                val port = runCatching { URI(url).port }.getOrDefault(0)
                if (port == -1) {
                    val hostEnd = url.indexOf('/', "http://".length).let { if (it < 0) url.length else it }
                    url = url.substring(0, hostEnd) + ":$DEFAULT_PORT" + url.substring(hostEnd)
                }
            }
            return url
        }

        private fun randomBytes(count: Int) = ByteArray(count).also { SecureRandom().nextBytes(it) }
    }
}
