package it.xonos.ma.sendspin

import android.util.Log
import it.xonos.ma.data.Logged
import it.xonos.ma.data.MaClient
import it.xonos.ma.data.SkipTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Il telefono come lettore di Music Assistant, con il protocollo Sendspin.
 *
 * Si collega a /sendspin sullo stesso indirizzo dell'API e si autentica con lo stesso token, così
 * il server sa che il lettore appartiene a questa app. Si presenta come applicazione mobile: per
 * Music Assistant è un lettore privato, che comanda solo l'app stessa. Poi tiene l'orologio
 * allineato con quello del server e passa l'audio ad [AudioEngine].
 *
 * Parla la variante in chiaro del protocollo, che Music Assistant accetta dai lettori: la connessione
 * è comunque autenticata dal token, e cifrata quando l'indirizzo è https.
 *
 * Col Music Assistant sul telefono stesso si collega invece alla porta di Sendspin, senza il proxy (vedi
 * [Identity.direct]).
 */
class SendspinClient(
    private val http: OkHttpClient,
    private val engine: AudioEngine,
    private val identity: () -> Identity,
    /** La porta del server Sendspin di Music Assistant (aiosendspin), per la connessione diretta. Le prove ne usano altre. */
    private val directPort: Int = DIRECT_PORT,
    private val onVolumeChanged: (Int, Boolean) -> Unit,
) {

    /** Chi siamo e cosa sappiamo fare, riletto a ogni connessione. */
    data class Identity(
        val clientId: String,
        val name: String,
        val manufacturer: String,
        val softwareVersion: String,
        /** In ordine di preferenza: il server sceglie il primo che sa produrre. */
        val formats: List<StreamFormat>,
        val leadTimeMs: Int,
        val minBufferMs: Int,
        val volume: Int,
        val muted: Boolean,
        /** "Mobile Application" per il telefono; nel banco di prova un altro nome fa una cassa finta, visibile a tutti. */
        val productName: String = PRODUCT_NAME,
        /**
         * Il server legge i comandi del lettore (volume, muto) da client/state: allora l'hello non li ripete e
         * client/state non ha il vecchio "state". Con quei campi, che la specifica ha tolto, Music Assistant segna il
         * telefono come lettore non conforme. Resta spento per i server più vecchi: prima della 2.10 li volevano.
         */
        val modernWire: Boolean = false,
        /**
         * Music Assistant è su questo telefono: l'audio arriva dalla porta di Sendspin ([DIRECT_PORT], solo su
         * 127.0.0.1) invece che dal proxy /sendspin, che in Python ripete ogni pacchetto da 25 ms in una seconda
         * WebSocket. Il proxy in più chiede il token e lega il lettore all'account: conta solo per gli account con un
         * elenco di lettori permessi, e quello di Xonos sul telefono è amministratore. Se la porta diretta non va si
         * torna al proxy. Vale solo per un indirizzo di questo telefono (127.0.0.1, localhost).
         */
        val direct: Boolean = false,
    )

    enum class Phase { STOPPED, CONNECTING, READY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var target: Pair<String, String>? = null
    @Volatile private var session: Session? = null

    /** Il ciclo di connessione sta solo aspettando il prossimo tentativo (dopo molti errori fino a 30 s). */
    @Volatile private var backingOff = false

    /**
     * Il Music Assistant per cui la porta diretta non va (vedi [Identity.direct]): fino alla fine del servizio si usa
     * il proxy. null: nessuno.
     */
    @Volatile private var proxyOnly: String? = null

    private val _phase = MutableStateFlow(Phase.STOPPED)

    /** READY quando il server ci conosce come lettore e l'orologio è allineato. */
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Perché l'ultima connessione audio non è riuscita, se non è riuscita. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Si collega (o resta collegato) a [baseUrl]; [force] riapre comunque la connessione. */
    @Synchronized
    fun start(baseUrl: String, token: String, force: Boolean = false) {
        // Se il ciclo sta solo aspettando il prossimo tentativo, si riprova subito: Music Assistant è appena tornato
        // (l'API si è ricollegata) e l'audio non deve restare indietro fino a 30 s.
        if (!force && !backingOff && job?.isActive == true && target == (baseUrl to token)) return
        job?.cancel()
        session?.close(goodbye = false)
        target = baseUrl to token
        job = scope.launch { connectLoop(baseUrl, token) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        target = null
        session?.close(goodbye = true)
        session = null
        _phase.value = Phase.STOPPED
        engine.end()
    }

    private suspend fun connectLoop(baseUrl: String, token: String) {
        var failures = 0
        // Music Assistant 2.7.0: /sendspin non c'è ancora sulla porta dell'interfaccia, solo la porta di Sendspin.
        var oldServer = false
        // Da quando la porta diretta non arriva al server (System.nanoTime(); 0: arriva). Vedi [Identity.direct].
        var missedSince = 0L
        // Questa volta il proxy al posto della porta diretta.
        var tryProxy = false
        while (true) {
            _phase.value = Phase.CONNECTING
            val id = identity()
            val wantDirect = id.direct && proxyOnly != baseUrl && local(baseUrl)
            val direct = oldServer || (wantDirect && !tryProxy)
            // Il proxy al posto della porta diretta: se lui arriva a suonare, si guarda se lei è ancora chiusa.
            val fallback = wantDirect && tryProxy
            val url = if (direct) directUrl(baseUrl, directPort) else MaClient.websocketUrl(baseUrl, "/sendspin")
            val current = Session(id, token, direct)
            // Col lock di start() e stop(), e solo se questo ciclo vale ancora: uno vecchio, fermato mentre non era in
            // attesa, si metterebbe al posto della sessione di quello nuovo, che non riceverebbe più niente.
            val context = currentCoroutineContext()
            synchronized(this) {
                context.ensureActive()
                session = current
            }
            val probe = if (fallback) {
                scope.launch {
                    current.awaitReady()
                    checkDirectPort(baseUrl)
                }
            } else {
                null
            }
            val outcome = try {
                current.run(url)
            } catch (e: CancellationException) {
                current.close(goodbye = false)
                throw e
            } finally {
                probe?.cancel()
            }
            // Finita senza che la WebSocket si chiudesse (il server non ha risposto all'hello, o ha detto di no): la si
            // chiude qui, o a ogni giro ne restava aperta un'altra verso Music Assistant.
            current.abandon()
            if (session === current) session = null
            _phase.value = Phase.CONNECTING
            engine.connectionLost()
            // La porta diretta non è arrivata a suonare, e non perché il server rifiuta questo lettore (lo farebbe anche
            // attraverso il proxy, che porta allo stesso server Sendspin).
            val directMiss = direct && wantDirect && !outcome.wasReady && !outcome.refused
            // Appena collegati, o dopo una connessione andata bene: Music Assistant che parte apre la porta di Sendspin
            // qualche secondo dopo l'API (sul computer 6 s; il proxy aspetta fino a 5 s la sua). Si riprova presto.
            var quick = false
            when {
                outcome.httpCode == 404 && !direct && !wantDirect && url.startsWith("ws://") -> {
                    // Music Assistant 2.7.0 non aveva ancora /sendspin sulla porta dell'interfaccia.
                    Log.i(TAG, "Nessun /sendspin${Logged.text(" su $baseUrl", "")}: provo la porta diretta di Sendspin")
                    oldServer = true
                    continue
                }
                directMiss && outcome.httpCode != null -> {
                    // Sulla porta risponde un server web che non è Sendspin: non cambierà.
                    proxyOnly = baseUrl
                    missedSince = 0L
                    Log.w(TAG, "Sendspin: la porta diretta risponde HTTP ${outcome.httpCode}, uso /sendspin finché il servizio è acceso")
                    continue
                }
                directMiss && failures == 0 -> {
                    val now = System.nanoTime()
                    if (missedSince == 0L) {
                        missedSince = now
                        Log.i(TAG, "Sendspin: la porta diretta non risponde ancora (${outcome.kind}), riprovo")
                    }
                    if (now - missedSince >= DIRECT_PATIENCE_MS * 1_000_000L) {
                        Log.w(TAG, "Sendspin: la porta diretta non risponde da ${(now - missedSince) / 1_000_000_000L} s (${outcome.kind}), provo /sendspin")
                        missedSince = 0L
                        tryProxy = true
                        continue
                    }
                    quick = true
                }
                outcome.wasReady -> {
                    failures = 0
                    missedSince = 0L
                    tryProxy = false
                }
                else -> {
                    // Non va nemmeno il proxy (Music Assistant fermo?): da qui solo la porta diretta, con le attese di
                    // sempre, finché l'API non si ricollega e fa ripartire questo ciclo (vedi [start]). Non il proxy:
                    // mentre Music Assistant riparte lui aspetta fino a 5 s la porta di Sendspin, e arrivato a suonare
                    // terrebbe sul proxy tutta la connessione.
                    failures++
                    tryProxy = false
                }
            }
            if (!quick) {
                _error.value = outcome.reason
                // Nelle build vere solo il tipo: il testo può avere l'indirizzo del server (errori di rete di OkHttp).
                Log.w(TAG, "Audio scollegato: ${Logged.text(outcome.reason, outcome.kind)}")
            }
            backingOff = true
            try {
                delay(
                    when {
                        // Riprovare subito non cambia niente: lo deve cambiare chi gestisce Music Assistant. Si riprova
                        // comunque ogni tanto, e subito quando l'API si ricollega (vedi [start]).
                        outcome.refused -> REFUSED_RETRY_MS
                        quick -> DIRECT_RETRY_MS
                        failures == 0 -> 1_000L
                        else -> (2_000L * failures).coerceAtMost(30_000L)
                    },
                )
            } finally {
                backingOff = false
            }
        }
    }

    /**
     * Il proxy è arrivato a suonare dopo che la porta diretta non rispondeva. Se lei è chiusa anche adesso, con Music
     * Assistant acceso (il proxy passa dal suo server Sendspin), non era il server che partiva: fino alla fine del
     * servizio si usa il proxy. Se risponde, la prossima connessione la riprova.
     */
    private suspend fun checkDirectPort(baseUrl: String) {
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.removeSurrounding("[", "]") ?: return
        val open = try {
            Socket().use { it.connect(InetSocketAddress(host, directPort), PROBE_TIMEOUT_MS) }
            true
        } catch (e: IOException) {
            false
        }
        // La sessione è finita mentre si guardava: forse si sta fermando Music Assistant, e la porta chiusa non dice niente.
        currentCoroutineContext().ensureActive()
        if (open) {
            Log.i(TAG, "Sendspin: la porta diretta ora risponde, la uso dalla prossima connessione")
        } else {
            proxyOnly = baseUrl
            Log.w(TAG, "Sendspin: la porta diretta resta chiusa, uso /sendspin finché il servizio è acceso")
        }
    }

    /**
     * Com'è finita una connessione: [reason] per chi usa l'app, [kind] per il registro (senza indirizzi né testi del
     * server), [refused] se il server non vuole lettori come questo.
     */
    private class Outcome(val reason: String, val kind: String, val wasReady: Boolean, val httpCode: Int?, val refused: Boolean)

    /** Una connessione, dall'apertura alla chiusura; [direct]: alla porta di Sendspin, senza il proxy e il suo token. */
    private inner class Session(private val id: Identity, private val token: String, private val direct: Boolean) : WebSocketListener() {

        private val done = CompletableDeferred<Outcome>()
        private val replies = Channel<LongArray>(capacity = 16)
        private val helloAnswered = CompletableDeferred<Unit>()
        private val becameReady = CompletableDeferred<Unit>()
        @Volatile private var socket: WebSocket? = null
        @Volatile private var ready = false
        private var volume = id.volume
        private var muted = id.muted

        // Dopo stream/start o stream/clear: il primo pacchetto audio va nel registro (vedi [SkipTrace]). Lo toccano
        // solo i messaggi di questa connessione, che OkHttp consegna uno alla volta dallo stesso thread.
        private var firstAudioAfter: String? = null

        suspend fun run(url: String): Outcome {
            engine.setVolume(volume, muted)
            val request = try {
                Request.Builder().url(url).build()
            } catch (e: IllegalArgumentException) {
                return Outcome("Indirizzo non valido: $url", "indirizzo non valido", false, null, false)
            }
            socket = http.newWebSocket(request, this)
            return coroutineScope {
                val sync = launch {
                    withTimeoutOrNull(HELLO_TIMEOUT_MS) { helloAnswered.await() }
                        ?: return@launch finish("Il server non risponde come lettore Sendspin", null)
                    syncLoop()
                }
                try {
                    done.await()
                } finally {
                    sync.cancel()
                }
            }
        }

        fun close(goodbye: Boolean) {
            val ws = socket ?: return
            if (goodbye && ready) {
                send(message("client/goodbye", JSONObject().put("reason", "shutdown")))
            }
            ws.close(1000, null)
            finish("Connessione chiusa", null)
        }

        /** Aspetta che il server conosca il lettore e l'orologio sia allineato ([Phase.READY]). */
        suspend fun awaitReady() = becameReady.await()

        /** Lascia andare la WebSocket senza saluti; non fa niente se è già chiusa. */
        fun abandon() {
            socket?.cancel()
        }

        /** Una raffica di misure subito, poi una ogni pochi secondi: la specifica ne vuole almeno una ogni dieci. */
        private suspend fun syncLoop() {
            while (true) {
                if (burst() && !ready) {
                    sendState()
                    ready = true
                    _error.value = null
                    _phase.value = Phase.READY
                    becameReady.complete(Unit)
                    Log.i(TAG, "Lettore pronto in Music Assistant")
                }
                delay(if (ready) SYNC_INTERVAL_MS else RETRY_SYNC_MS)
            }
        }

        /** Otto scambi uno dopo l'altro; al filtro va solo quello con l'errore più piccolo. */
        private suspend fun burst(): Boolean {
            var best: LongArray? = null
            var bestError = Long.MAX_VALUE
            repeat(BURST) {
                val t1 = now()
                if (!send(message("client/time", JSONObject().put("client_transmitted", t1)))) return false
                val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                    var r = replies.receive()
                    while (r[0] != t1) r = replies.receive()
                    r
                } ?: return@repeat
                val maxError = ((reply[3] - reply[0]) - (reply[2] - reply[1])) / 2
                if (maxError in 1 until bestError) {
                    bestError = maxError
                    best = reply
                }
            }
            val m = best ?: return false
            val filter = engine.filter
            // Dopo una riconnessione l'orologio del server di solito è lo stesso, e l'audio rimasto in coda
            // continua a usarlo; se invece il server è ripartito, la stima vecchia non vale più.
            val offset = ((m[1] - m[0]) + (m[2] - m[3])) / 2
            if (filter.count > 0 && abs(filter.offsetUs - offset) > CLOCK_JUMP_US) filter.reset()
            filter.update(m[0], m[1], m[2], m[3])
            return true
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!direct) {
                // Il proxy di Music Assistant vuole prima il token, poi parla Sendspin.
                send(JSONObject().put("type", "auth").put("token", token).put("client_id", id.clientId))
            } else {
                sendHello()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Una connessione chiusa per aprirne un'altra consegna ancora i suoi messaggi per un po' (finché la chiusura
            // non è finita): uno stream/end di quella vecchia fermerebbe lo stream nuovo.
            if (session !== this) return
            val received = now()
            val msg = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return
            }
            val payload = msg.optJSONObject("payload") ?: JSONObject()
            when (msg.optString("type")) {
                "auth_ok" -> sendHello()
                "server/hello" -> {
                    val name = Logged.text(" " + payload.optString("name"), "")
                    val way = if (direct) ", porta diretta" else ""
                    Log.i(TAG, "Sendspin: server pronto$name, ruoli ${payload.optJSONArray("active_roles")}$way")
                    helloAnswered.complete(Unit)
                }
                // Il server non accetta questa connessione: per esempio un Music Assistant che vuole solo lettori
                // cifrati (senza "allow_legacy_clients" risponde "malformed" al client/hello in chiaro), poi chiude.
                "server/error" -> {
                    val why = payload.optString("reason").take(REASON_CHARS).ifBlank { "?" }
                    finish(
                        "Questo Music Assistant accetta solo lettori Sendspin cifrati, che Xonos non sa ancora usare ($why)",
                        null,
                        kind = "server/error $why",
                        refused = true,
                    )
                }
                "server/time" -> replies.trySend(
                    longArrayOf(
                        payload.optLong("client_transmitted"),
                        payload.optLong("server_received"),
                        payload.optLong("server_transmitted"),
                        received,
                    ),
                )
                "stream/start" -> payload.optJSONObject("player")?.let(StreamFormat::from)?.let { format ->
                    Log.i(TAG, "Sendspin: stream/start (${format.codec})${SkipTrace.suffix()}")
                    firstAudioAfter = "stream/start"
                    engine.startStream(format)
                }
                "stream/clear" -> if (forPlayer(payload)) {
                    Log.i(TAG, "Sendspin: stream/clear${SkipTrace.suffix()}")
                    firstAudioAfter = "stream/clear"
                    engine.clear()
                }
                "stream/end" -> if (forPlayer(payload)) {
                    Log.i(TAG, "Sendspin: stream/end${SkipTrace.suffix()}")
                    engine.end()
                }
                "server/command" -> payload.optJSONObject("player")?.let(::command)
                "group/update" -> Log.d(TAG, "Gruppo: ${payload.optString("playback_state")}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (session !== this) return
            // Tipo 4: audio, preceduto dal tempo del server (8 byte). Il margine di invio (send_ahead)
            // che la specifica mette dopo il tempo il server lo manda solo ai client cifrati, non a questo.
            if (bytes.size < AUDIO_HEADER || bytes[0].toInt() != AUDIO_CHUNK) return
            val data = bytes.toByteArray()
            val timestamp = ByteBuffer.wrap(data, 1, 8).long
            firstAudioAfter?.let { after ->
                firstAudioAfter = null
                // L'anticipo: quanto prima del momento in cui va suonato è arrivato (negativo: è arrivato tardi).
                val aheadMs = (engine.filter.toClient(timestamp) - now()) / 1000
                val arrival = if (aheadMs >= 0) "con $aheadMs ms di anticipo" else "in ritardo di ${-aheadMs} ms"
                Log.i(TAG, "Sendspin: primo audio dopo $after, $arrival${SkipTrace.suffix()}")
            }
            engine.audio(timestamp, data, AUDIO_HEADER, data.size - AUDIO_HEADER)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(
                when {
                    code == 4001 -> "Music Assistant ha rifiutato il token ($reason)"
                    reason.isNotBlank() -> reason
                    else -> "Connessione chiusa dal server"
                },
                null,
                kind = if (code == 4001) "token rifiutato (4001)" else "chiusa dal server ($code)",
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when {
                response?.code == 401 || response?.code == 403 -> "Accesso all'audio negato"
                response?.code == 404 -> "Questo Music Assistant non offre l'audio per i telefoni"
                response != null -> "Errore HTTP ${response.code}"
                else -> t.message ?: "Errore di rete"
            }
            finish(reason, response?.code, kind = response?.let { "HTTP ${it.code}" } ?: t.javaClass.simpleName)
        }

        private fun finish(reason: String, httpCode: Int?, kind: String = reason, refused: Boolean = false) {
            done.complete(Outcome(reason, kind, ready, httpCode, refused))
        }

        private fun sendHello() {
            val player = JSONObject()
                .put("supported_formats", JSONArray(id.formats.map { it.toJson() }))
                .put("buffer_capacity", BUFFER_CAPACITY)
            // Le versioni prima della 2.10 li volevano qui; le nuove li leggono da client/state.
            if (!id.modernWire) player.put("supported_commands", JSONArray(COMMANDS))
            val payload = JSONObject()
                .put("client_id", id.clientId)
                .put("version", 1)
                .put("name", utf8Limit(id.name, 64))
                .put(
                    "device_info",
                    JSONObject()
                        // Con questo nome Music Assistant lo tratta come l'app ufficiale: un lettore privato dell'app.
                        .put("product_name", id.productName)
                        .put("manufacturer", utf8Limit(id.manufacturer, 64))
                        .put("software_version", utf8Limit(id.softwareVersion, 64)),
                )
                .put("supported_roles", JSONArray(listOf("player@v1")))
                .put("player@v1_support", player)
                // L'accesso da ospite iscriverebbe il telefono tra i dispositivi fidati, e da lì in poi
                // il server rifiuterebbe le sue riconnessioni in chiaro.
                .put("unpaired_access", JSONObject().put("enabled", false))
            send(message("client/hello", payload))
        }

        private fun sendState() {
            val player = JSONObject()
                .put("volume", volume)
                .put("muted", muted)
                .put("output_delay_ms", 0)
                .put("required_lead_time_ms", id.leadTimeMs)
                .put("min_buffer_ms", id.minBufferMs)
                .put("supported_commands", JSONArray(COMMANDS))
            val payload = JSONObject()
                .put("available", true)
                .put("player", player)
            // Il nome di "available" nelle versioni prima della 2.10.
            if (!id.modernWire) payload.put("state", "synchronized")
            send(message("client/state", payload))
        }

        private fun command(player: JSONObject) {
            when (player.optString("command")) {
                "volume" -> volume = player.optInt("volume", volume).coerceIn(0, 100)
                "mute" -> muted = player.optBoolean("mute", muted)
                else -> return
            }
            engine.setVolume(volume, muted)
            onVolumeChanged(volume, muted)
            sendState()
        }

        private fun send(json: JSONObject): Boolean = socket?.send(json.toString()) ?: false
    }

    private companion object {
        const val TAG = "Xonos"
        const val PRODUCT_NAME = "Mobile Application"
        val COMMANDS = listOf("volume", "mute")
        const val AUDIO_CHUNK = 4
        const val AUDIO_HEADER = 9

        /** Fino a qualche minuto di Opus, una decina di secondi di PCM: il server ne manda al massimo mezzo minuto. */
        const val BUFFER_CAPACITY = 2_000_000

        const val BURST = 8
        const val REPLY_TIMEOUT_MS = 1_500L
        const val SYNC_INTERVAL_MS = 5_000L
        const val RETRY_SYNC_MS = 1_000L
        const val HELLO_TIMEOUT_MS = 15_000L

        /** Dopo un "no" del server ([Outcome.refused]) si riprova solo ogni tanto. */
        const val REFUSED_RETRY_MS = 5 * 60_000L

        /** Il motivo di server/error nel registro è una parola ("malformed"): non di più. */
        const val REASON_CHARS = 40

        /** La porta del server Sendspin di Music Assistant (aiosendspin). */
        const val DIRECT_PORT = 8927

        /**
         * La porta diretta che non risponde si riprova presto, per al massimo [DIRECT_PATIENCE_MS]; poi il proxy. Largo:
         * passare al proxy mentre Music Assistant parte lascerebbe tutta la connessione sul proxy.
         */
        const val DIRECT_RETRY_MS = 500L
        const val DIRECT_PATIENCE_MS = 20_000L

        /** Quanto aspetta la prova della porta diretta (vedi [checkDirectPort]): è su questo telefono. */
        const val PROBE_TIMEOUT_MS = 1_000
        const val CLOCK_JUMP_US = 1_000_000L

        fun now(): Long = System.nanoTime() / 1000

        fun message(type: String, payload: JSONObject): JSONObject = JSONObject().put("type", type).put("payload", payload)

        fun forPlayer(payload: JSONObject): Boolean {
            val roles = payload.optJSONArray("roles") ?: return true
            return (0 until roles.length()).any { roles.optString(it).substringBefore('@') == "player" }
        }

        /** La porta del server Sendspin: per le versioni senza /sendspin sulla porta dell'interfaccia, e sul telefono. */
        fun directUrl(baseUrl: String, port: Int): String {
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull() ?: baseUrl
            return "ws://$host:$port/sendspin"
        }

        /** L'indirizzo è questo telefono: 127.0.0.1 (o un altro 127.x), localhost, ::1. */
        fun local(baseUrl: String): Boolean {
            val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase() ?: return false
            return host == "localhost" || host.startsWith("127.") || host == "[::1]" || host == "::1"
        }

        fun utf8Limit(text: String, bytes: Int): String {
            var result = text
            while (result.toByteArray(Charsets.UTF_8).size > bytes) result = result.dropLast(1)
            return result
        }
    }
}
