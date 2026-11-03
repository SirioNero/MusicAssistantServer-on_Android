package it.xonos.ma.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * "Spotify è già occupato" e nessuno lo sta usando. Con il motore Soloist Spotify suona una cosa
 * sola alla volta: se un ascolto resta appeso (per esempio il telefono si è scollegato a metà di un
 * brano e nessuno ritira più l'audio), Music Assistant rifiuta tutto il resto con
 * `soloist_session_busy` finché il servizio non si ricarica. Qui, se nessun altro lettore sta
 * suonando Spotify, si ricarica il servizio e si riprova una volta. Se un altro lettore lo sta
 * usando davvero non si tocca niente: resta il messaggio di Music Assistant ("ferma l'altro lettore").
 * Ricaricare un servizio è solo per gli amministratori: per gli altri resta l'errore.
 */
class SoloistUnlock(private val repo: MaRepository, private val domain: String = "spotify") {

    /** Fa [action] sulla coda [queueId]; se Spotify è occupato da un ascolto appeso, lo sblocca e riprova. */
    suspend fun <T> retrying(queueId: String, action: suspend () -> T): T {
        try {
            return action()
        } catch (e: MaException) {
            if (!busy(e) || !unlock(queueId, e.args.firstOrNull())) throw e
        }
        return action()
    }

    /**
     * Libera Spotify se nessun lettore oltre a [queueId] lo sta suonando: ricarica il servizio
     * (quello di nome [name], se c'è, altrimenti tutti quelli di Spotify). Vero se l'ha fatto.
     */
    internal suspend fun unlock(queueId: String, name: String?): Boolean = lock.withLock {
        // Più comandi insieme ricevono lo stesso errore: il servizio si ricarica una volta sola.
        if (System.currentTimeMillis() - lastUnlock < AGAIN_MS) return@withLock true
        val user = playingElsewhere(queueId)
        if (user != null) {
            Log.i(TAG, "Spotify è occupato da un altro lettore${Logged.text(" ($user)", "")}: non si tocca")
            return@withLock false
        }
        val services = try {
            (repo.command("config/providers") as? JSONArray).objects()
        } catch (e: MaException) {
            Log.w(TAG, "Spotify occupato, ma i servizi non si possono vedere: ${Logged.error(e)}")
            return@withLock false
        }
        val ours = services.filter { it.str("domain") == domain }
        val targets = ours.filter { name != null && it.str("name") == name }.ifEmpty { ours }
        if (targets.isEmpty()) return@withLock false
        for (service in targets) {
            val id = service.str("instance_id") ?: continue
            // Il nome del servizio lo sceglie la persona: nelle build vere solo l'id.
            Log.i(TAG, "Spotify occupato da un ascolto appeso: ricarico ${Logged.text(service.str("name") ?: id, id)}")
            try {
                repo.command("config/providers/reload", RELOAD_TIMEOUT_MS) { put("instance_id", id) }
            } catch (e: MaException) {
                Log.w(TAG, "Ricarica di $id non riuscita: ${Logged.error(e)}")
                return@withLock false
            }
        }
        lastUnlock = System.currentTimeMillis()
        true
    }

    /** Il nome di un altro lettore che sta suonando Spotify adesso, se c'è. */
    private suspend fun playingElsewhere(queueId: String): String? =
        (repo.command("player_queues/all") as? JSONArray).objects().firstOrNull { q ->
            q.str("queue_id") != queueId &&
                q.str("state") == "playing" &&
                q.optBoolean("available", true) &&
                QueueItem.from(q.optJSONObject("current_item"))?.sources?.contains(domain) == true
        }?.let { it.str("display_name") ?: it.str("queue_id") }

    companion object {
        private const val TAG = "Xonos"
        private const val AGAIN_MS = 15_000L
        private const val RELOAD_TIMEOUT_MS = 60_000L
        private val lock = Mutex()
        @Volatile private var lastUnlock = 0L

        /**
         * Il rifiuto di Soloist occupato in ogni lingua in cui Music Assistant lo traduce. Music Assistant 2.10 manda gli
         * errori già tradotti nella lingua della connessione e senza `translation_key`: si riconosce dal testo. Le frasi
         * vengono dalle sue traduzioni (de, es, nl, ru_RU, sr_Latn; le altre lingue restano in inglese) e da quella
         * italiana del server sul telefono (`server/ma/ma-strings.json`): se cambiano lì, vanno cambiate qui.
         */
        private val BUSY = listOf(
            "plays one thing at a time",
            "una cosa alla volta",
            "jeweils nur einen",
            "una cosa a la vez",
            "één stream tegelijk",
            "только один трек за раз",
            "samo jednu stvar istovremeno",
        )

        /** L'errore di Music Assistant per Spotify Soloist occupato (anche quando lo dice in un'altra lingua). */
        fun busy(e: Throwable): Boolean {
            if (e !is MaException) return false
            if (e.key == "soloist_session_busy") return true
            val text = e.message.orEmpty()
            return text.contains("Soloist") && BUSY.any { text.contains(it, ignoreCase = true) }
        }
    }
}
