package it.xonos.ma.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Un errore di Music Assistant: [message] per i log, [problem] e [detail] per dirlo a chi usa l'app. */
class MaException(
    message: String,
    val code: Int? = null,
    val problem: Problem = Problem.REFUSED,
    val detail: String? = null,
    /** Il nome dell'errore per Music Assistant (`translation_key`) e i suoi argomenti, se li ha mandati. */
    val key: String? = null,
    val args: List<String> = emptyList(),
) : Exception(message)

/**
 * Una singola connessione WebSocket all'API di Music Assistant (/ws).
 * Gestisce l'accesso con il token, i comandi con la loro risposta e gli eventi del server.
 */
class MaClient(
    private val http: OkHttpClient,
    /** La lingua dei testi che Music Assistant traduce da sé (per esempio "it" o "pt_PT"): c'è nella 2.10.8, chi non la conosce la ignora. */
    private val locale: String? = null,
) {

    private class Box(val value: Any?)

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Box>>()
    private val partials = ConcurrentHashMap<String, JSONArray>()

    // Per gli elenchi chiesti con [items]: come trasformare ogni elemento, e quelli già trasformati.
    private val transforms = ConcurrentHashMap<String, (JSONObject) -> Any?>()
    private val converted = ConcurrentHashMap<String, ArrayList<Any?>>()

    // Chi vuole vedere un elenco lungo mentre arriva: riceve gli elementi fin lì a ogni pezzo.
    private val progress = ConcurrentHashMap<String, (List<Any?>) -> Unit>()

    // Quando è arrivato l'ultimo pezzo di un elenco (System.nanoTime): il tempo per rispondere conta da lì.
    private val lastPart = ConcurrentHashMap<String, Long>()
    private val serverInfo = CompletableDeferred<JSONObject>()

    /** Si completa (con il motivo) quando la connessione si chiude. */
    val closed = CompletableDeferred<String>()

    /** Perché la connessione si è chiusa: lo si dice anche a chi prova a mandare un comando dopo. */
    @Volatile private var closeError: MaException? = null

    /** Gli eventi del server (nome, object_id, dati). Arrivano sul thread di OkHttp. */
    @Volatile var onEvent: ((String, String?, Any?) -> Unit)? = null

    @Volatile private var socket: WebSocket? = null

    /** Si collega e accede con il token. */
    suspend fun connect(baseUrl: String, token: String, timeoutMs: Long = 10_000): ServerInfo {
        val server = open(baseUrl, timeoutMs)
        try {
            return authenticate(server, token, timeoutMs)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Si collega senza accedere e dice chi è il server: serve per accedere con nome utente e
     * password, o per creare il primo account (vedi [Account]).
     */
    suspend fun open(baseUrl: String, timeoutMs: Long = 10_000): ServerInfo {
        val request = try {
            Request.Builder().url(websocketUrl(baseUrl)).build()
        } catch (e: IllegalArgumentException) {
            throw MaException("Indirizzo non valido: $baseUrl", problem = Problem.BAD_ADDRESS, detail = baseUrl)
        }
        socket = http.newWebSocket(request, listener)
        try {
            val info = withTimeoutOrNull(timeoutMs) { serverInfo.await() }
                ?: throw MaException("Nessuna risposta da $baseUrl", problem = Problem.NO_ANSWER, detail = baseUrl)
            return ServerInfo.from(info)
                ?: throw MaException("A questo indirizzo non risponde Music Assistant", problem = Problem.NOT_MUSIC_ASSISTANT)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** L'accesso con il token, su una connessione aperta con [open]; dice anche chi ha fatto l'accesso. */
    suspend fun authenticate(server: ServerInfo, token: String, timeoutMs: Long = 10_000): ServerInfo {
        val auth = try {
            command("auth", timeoutMs) {
                put("token", token)
                locale?.let { put("locale", it) }
            } as? JSONObject
        } catch (e: MaException) {
            when {
                // Prima della 2.7 Music Assistant non aveva accessi né token, e nemmeno l'audio per i telefoni.
                e.code == INVALID_COMMAND || e.message.orEmpty().contains("Invalid command") -> throw MaException(
                    "Music Assistant ${server.version} è troppo vecchio: serve la 2.7 o successiva",
                    e.code,
                    Problem.TOO_OLD,
                    server.version,
                )
                e.code in TOKEN_ERRORS -> throw MaException(e.message ?: "Token non valido", e.code, Problem.BAD_TOKEN)
                e.code == NOT_ALLOWED -> throw MaException(e.message ?: "Accesso negato", e.code, Problem.DENIED)
                else -> throw e
            }
        }
        val user = auth?.optJSONObject("user")
        val filter = user?.optJSONArray("player_filter")
        return server.copy(
            limitedPlayers = filter != null && filter.length() > 0,
            user = user?.str("display_name") ?: user?.str("username"),
            role = user?.str("role"),
        )
    }

    /**
     * Esegue un comando e ne restituisce il risultato (JSONObject, JSONArray, numero, stringa o null).
     * Gli elenchi lunghi arrivano a pezzi: qui si ricompongono.
     */
    suspend fun command(name: String, timeoutMs: Long = 30_000, fill: JSONObject.() -> Unit = {}): Any? =
        send(name, timeoutMs, null, fill)

    /**
     * Un comando che risponde con un elenco: ogni elemento diventa subito un [T] con [transform],
     * pezzo per pezzo man mano che arriva, sul thread della rete. Così un elenco di migliaia di
     * elementi non resta mai tutto in memoria come JSON, e il thread principale non lo tocca.
     * Music Assistant manda gli elenchi lunghi a pezzi di 500 man mano che li riceve dai servizi:
     * [onPart], se c'è, riceve a ogni pezzo gli elementi arrivati fin lì (sul thread della rete).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> items(
        name: String,
        timeoutMs: Long = 30_000,
        transform: (JSONObject) -> T?,
        fill: JSONObject.() -> Unit = {},
        onPart: ((List<T>) -> Unit)? = null,
    ): List<T> = send(name, timeoutMs, transform, fill, onPart as ((List<Any?>) -> Unit)?) as? List<T> ?: emptyList()

    private suspend fun send(
        name: String,
        timeoutMs: Long,
        transform: ((JSONObject) -> Any?)?,
        fill: JSONObject.() -> Unit,
        onPart: ((List<Any?>) -> Unit)? = null,
    ): Any? {
        val ws = socket
        if (ws == null || closed.isCompleted) throw closeError ?: MaException("Non collegato a Music Assistant", problem = Problem.NOT_CONNECTED)
        val id = ids.getAndIncrement().toString()
        val message = JSONObject()
            .put("message_id", id)
            .put("command", name)
            .put("args", JSONObject().apply(fill))
        val deferred = CompletableDeferred<Box>()
        if (transform != null) transforms[id] = transform
        if (onPart != null) progress[id] = onPart
        pending[id] = deferred
        if (!ws.send(message.toString())) {
            forget(id)
            // Il server ha appena chiuso (per esempio "Setup required"): il motivo arriva un attimo dopo.
            withTimeoutOrNull(2_000) { closed.await() }
            throw closeError ?: MaException("Invio a Music Assistant non riuscito", problem = Problem.LOST)
        }
        var box = withTimeoutOrNull(timeoutMs) { deferred.await() }
        // Un elenco lungo arriva a pezzi, e con i servizi lenti (Spotify dà 50 brani ogni 1-2 s) una playlist di
        // migliaia di brani supera il tempo concesso mentre i pezzi arrivano ancora: si aspetta finché ne arrivano.
        while (box == null) {
            val last = lastPart[id] ?: break
            val quiet = (System.nanoTime() - last) / 1_000_000
            if (quiet >= timeoutMs) break
            box = withTimeoutOrNull(timeoutMs - quiet) { deferred.await() }
        }
        if (box == null) {
            forget(id)
            throw MaException("Music Assistant non risponde", problem = Problem.NO_ANSWER)
        }
        return box.value
    }

    private fun forget(id: String) {
        pending.remove(id)
        partials.remove(id)
        transforms.remove(id)
        converted.remove(id)
        progress.remove(id)
        lastPart.remove(id)
    }

    fun close() {
        socket?.close(1000, null)
        shutdown("Connessione chiusa", Problem.LOST)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(traced("Xonos.json") { JSONObject(text) })
            } catch (e: Exception) {
                Log.w(TAG, "Messaggio non valido da Music Assistant", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            shutdown(reason.ifBlank { "Connessione chiusa" }, Problem.LOST, reason.ifBlank { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            when {
                response?.code == 401 || response?.code == 403 -> shutdown("Accesso negato", Problem.DENIED)
                response?.code == 404 -> shutdown("A questo indirizzo non risponde Music Assistant", Problem.NOT_MUSIC_ASSISTANT)
                response != null -> shutdown("Errore HTTP ${response.code}", Problem.HTTP, response.code.toString())
                else -> shutdown(t.message ?: "Errore di rete", Problem.NETWORK, t.message)
            }
        }
    }

    private fun handle(msg: JSONObject) {
        if (msg.has("event")) {
            val data = msg.opt("data")?.takeIf { it != JSONObject.NULL }
            try {
                onEvent?.invoke(msg.optString("event"), msg.str("object_id"), data)
            } catch (e: Exception) {
                Log.w(TAG, "Errore nella gestione di un evento", e)
            }
            return
        }
        val id = msg.str("message_id")
        if (id == null) {
            // Il primo messaggio: chi è il server.
            if (msg.has("server_id")) serverInfo.complete(msg)
            return
        }
        if (msg.has("error_code")) {
            val details = msg.str("details")
            val args = msg.optJSONArray("translation_args")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val error = MaException(details ?: "Comando rifiutato", msg.optInt("error_code"), Problem.REFUSED, details, msg.str("translation_key"), args)
            if (id == "connection") {
                // Per esempio "Setup required" (un Music Assistant appena installato, senza account): il server chiude subito dopo.
                val setup = msg.optInt("error_code") == SETUP_REQUIRED || msg.str("translation_key") == "setup_required"
                shutdown(details ?: "Connessione rifiutata", if (setup) Problem.SETUP_REQUIRED else Problem.REFUSED, details)
                return
            }
            val deferred = pending[id]
            forget(id)
            deferred?.completeExceptionally(error)
            return
        }
        val result = msg.opt("result")?.takeIf { it != JSONObject.NULL }
        val transform = transforms[id]
        if (transform != null) {
            // Un elenco da trasformare: questo pezzo diventa subito elementi, il JSON si butta.
            val list = converted.getOrPut(id) { ArrayList() }
            (result as? JSONArray)?.let { chunk -> traced("Xonos.items") { chunk.objects().mapNotNullTo(list, transform) } }
            if (msg.optBoolean("partial")) {
                lastPart[id] = System.nanoTime()
                progress[id]?.let { show ->
                    try {
                        show(ArrayList(list))
                    } catch (e: Exception) {
                        Log.w(TAG, "Errore mostrando un pezzo di elenco", e)
                    }
                }
                return
            }
            val deferred = pending[id]
            forget(id)
            deferred?.complete(Box(list))
            return
        }
        if (msg.optBoolean("partial")) {
            // I pezzi di una richiesta già lasciata (tempo scaduto) si buttano: resterebbero in memoria per sempre.
            if (!pending.containsKey(id)) return
            lastPart[id] = System.nanoTime()
            val chunk = result as? JSONArray ?: return
            val all = partials.getOrPut(id) { JSONArray() }
            for (i in 0 until chunk.length()) all.put(chunk.get(i))
            return
        }
        val deferred = pending.remove(id) ?: return
        val earlier = partials.remove(id)
        if (earlier != null && result is JSONArray) {
            for (i in 0 until result.length()) earlier.put(result.get(i))
            deferred.complete(Box(earlier))
        } else {
            deferred.complete(Box(earlier ?: result))
        }
    }

    private fun shutdown(reason: String, problem: Problem, detail: String? = null) {
        val error = MaException(reason, problem = problem, detail = detail)
        // Il primo motivo è quello vero: dopo "Setup required" arriva anche la chiusura del socket.
        if (closeError == null) closeError = error
        serverInfo.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        partials.clear()
        transforms.clear()
        converted.clear()
        lastPart.clear()
        closed.complete(reason)
    }

    companion object {
        private const val TAG = "Xonos"

        // I codici d'errore di Music Assistant che contano all'accesso.
        private const val INVALID_COMMAND = 12
        private const val NOT_ALLOWED = 22
        private val TOKEN_ERRORS = setOf(20, 21, 23)
        private const val SETUP_REQUIRED = 503

        /** L'indirizzo WebSocket di un percorso del server: http diventa ws, https diventa wss. */
        fun websocketUrl(base: String, path: String = "/ws"): String {
            val trimmed = base.trim().trimEnd('/')
            val ws = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "ws://$trimmed"
            }
            return ws + path
        }
    }
}
