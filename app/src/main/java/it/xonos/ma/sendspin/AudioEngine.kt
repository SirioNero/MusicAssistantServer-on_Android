package it.xonos.ma.sendspin

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Il formato di uno stream, come lo annuncia il server in stream/start. */
data class StreamFormat(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    /** L'intestazione del codec in Base64 (per FLAC), se c'è. */
    val header: String? = null,
) {
    fun headerBytes(): ByteArray? = header?.let { Base64.getMimeDecoder().decode(it) }

    fun toJson(): JSONObject = JSONObject()
        .put("codec", codec)
        .put("channels", channels)
        .put("sample_rate", sampleRate)
        .put("bit_depth", bitDepth)

    companion object {
        fun from(o: JSONObject): StreamFormat? {
            val codec = o.optString("codec").ifEmpty { return null }
            val rate = o.optInt("sample_rate")
            val channels = o.optInt("channels")
            if (rate <= 0 || channels <= 0) return null
            return StreamFormat(
                codec = codec,
                sampleRate = rate,
                channels = channels,
                bitDepth = o.optInt("bit_depth", 16),
                header = if (o.isNull("codec_header")) null else o.optString("codec_header").ifEmpty { null },
            )
        }
    }
}

/** Dove finisce il PCM a 16 bit interleaved: sul telefono un AudioTrack. */
interface PcmOutput {
    val sampleRate: Int
    val channels: Int

    /** Ritardo stimato tra la scrittura e l'ascolto, quando l'uscita non sa ancora dirlo meglio. */
    val latencyUs: Long

    fun play()

    /** Scrive [frames] fotogrammi partendo da [offsetFrames]; blocca finché c'è posto. Restituisce quanti ne ha scritti. */
    fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int

    /** Quando si sentirà il fotogramma numero [frame] (contando dall'inizio), sul nostro orologio; null se non lo sa. */
    fun presentationTimeUs(frame: Long): Long?

    /** Quanti fotogrammi ha già suonato (o almeno preso per suonarli); null se non lo sa. */
    fun playedFrames(): Long? = null

    /** Quanti fotogrammi vuole prima di cominciare a suonare. */
    val startFrames: Int get() = 0

    fun setGain(gain: Float)

    /** Smette subito di suonare e butta ciò che ha in coda; si può chiamare da qualsiasi thread. */
    fun interrupt()

    fun release()

    companion object {
        /** Ciò che [write] risponde da un'uscita morta per sempre (AudioTrack.ERROR_DEAD_OBJECT). */
        const val DEAD = -6
    }
}

/** Da pacchetti compressi a PCM a 16 bit interleaved. */
interface AudioDecoder {
    /** Decodifica un pacchetto; chiama [out] per ogni blocco di PCM prodotto, con il suo tempo del server. */
    fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit)

    fun release()
}

/** PCM così com'è: interi little-endian; quelli a 24 o 32 bit si riducono a 16. */
class PcmDecoder(private val channels: Int, private val bitDepth: Int) : AudioDecoder {
    override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
        val bytes = bitDepth / 8
        if (bytes !in 2..4) return
        val samples = length / bytes
        val frames = samples / channels
        if (frames == 0) return
        val pcm = ShortArray(frames * channels)
        if (bytes == 2) {
            ByteBuffer.wrap(data, offset, frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        } else {
            for (i in pcm.indices) {
                // Il byte più significativo e quello sotto: gli ultimi due del campione.
                val p = offset + i * bytes + bytes - 2
                pcm[i] = ((data[p + 1].toInt() shl 8) or (data[p].toInt() and 0xFF)).toShort()
            }
        }
        out(pcm, frames, timestampUs)
    }

    override fun release() = Unit
}

/**
 * Suona l'audio di Sendspin al momento giusto.
 *
 * I pacchetti arrivano dalla rete con il tempo del server in cui vanno ascoltati, anche mezzo minuto
 * prima. Un thread li decodifica e li scrive sull'uscita: se è in ritardo salta l'audio già passato,
 * se è in anticipo aspetta con un po' di silenzio, e i piccoli scarti (gli orologi non vanno mai
 * proprio alla stessa velocità) li recupera togliendo o ripetendo pochi campioni, senza che si senta.
 *
 * All'avvio invece non si salta niente: se l'uscita ci ha messo più del margine chiesto al server,
 * l'inizio del brano si suona lo stesso, un po' dopo ([lateMs]), e il ritardo si recupera piano.
 *
 * I metodi pubblici si chiamano dal thread della rete, in ordine di arrivo dei messaggi.
 */
class AudioEngine(
    private val outputs: (sampleRate: Int, channels: Int) -> PcmOutput?,
    private val decoders: (StreamFormat) -> AudioDecoder?,
    val filter: TimeFilter,
    private val clock: () -> Long = { System.nanoTime() / 1000 },
    /** Chiamato sul thread audio appena parte: sul telefono gli dà la priorità dell'audio. */
    private val onAudioThread: () -> Unit = {},
) {
    private sealed interface Event
    private class Start(val format: StreamFormat, val fresh: Boolean) : Event
    private class Chunk(val generation: Int, val timestampUs: Long, val data: ByteArray, val offset: Int, val length: Int) : Event
    private object Reset : Event
    private object Finish : Event

    private val lock = Object()
    private val events = ArrayDeque<Event>()
    private val outputLock = Any()

    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var gain = 1f
    @Volatile private var duck = 1f
    private var thread: Thread? = null

    // Lo stato del protocollo, visto dal thread della rete.
    private var streamOpen = false
    private var freshNeeded = true

    // Lo stato dell'uscita, visto dal thread audio.
    private var format: StreamFormat? = null
    private var decoder: AudioDecoder? = null
    @Volatile private var output: PcmOutput? = null
    private var framesWritten = 0L
    private var smoothedErrorUs = 0.0
    private var lastAudioAt = 0L

    // Quanto si suona dopo il momento del server, dall'ultimo avvio: si recupera piano.
    @Volatile private var lateUs = 0L

    // L'uscita ha già detto quando suona davvero, da quando è aperta (prima i tempi sono stime), e quando la si è aperta.
    private var timed = false
    private var openedAt = 0L
    private var silence = ShortArray(0)
    private var statSince = 0L
    private var statBlocks = 0
    private var statSum = 0L
    private var statMax = 0L

    private val _outputting = MutableStateFlow(false)

    /** Vero mentre esce davvero musica: falso in attesa del primo audio, in pausa o se la rete si è fermata. */
    val outputting: StateFlow<Boolean> = _outputting.asStateFlow()

    /**
     * Di quanto la musica che si sente è indietro rispetto al momento del server (e alla posizione
     * che dice Music Assistant): succede dopo un avvio lento, e torna a zero piano piano.
     */
    val lateMs: Long get() = lateUs / 1000

    /** Inizia uno stream, o ne cambia il formato senza interromperlo. */
    fun startStream(format: StreamFormat) {
        val fresh = !streamOpen || freshNeeded
        if (fresh) interrupt()
        streamOpen = true
        freshNeeded = false
        post(Start(format, fresh))
    }

    /** Un pacchetto audio: [timestampUs] è il tempo del server in cui va ascoltato il primo campione. */
    fun audio(timestampUs: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (!streamOpen || freshNeeded || length <= 0) return
        post(Chunk(generation, timestampUs, data, offset, length))
    }

    /** Il server ha saltato altrove (ricerca, brano diverso): si butta l'audio in coda e si continua. */
    fun clear() {
        if (!streamOpen) return
        interrupt()
        post(Reset)
    }

    /** Il server ha finito: si smette subito di suonare. */
    fun end() {
        streamOpen = false
        interrupt()
        post(Finish)
    }

    /**
     * La connessione è caduta: l'audio già ricevuto continua a suonare finché basta,
     * ma il prossimo stream ripartirà da capo.
     */
    fun connectionLost() {
        freshNeeded = true
    }

    /** Volume (0-100) e muto come li chiede Music Assistant; il volume percepito vuole la potenza 1,5. */
    fun setVolume(volume: Int, muted: Boolean) {
        gain = if (muted) 0f else (volume.coerceIn(0, 100) / 100f).pow(1.5f)
        applyGain()
    }

    /** Abbassa (o azzera) il volume per un momento, per esempio durante una telefonata. */
    fun setDuck(factor: Float) {
        duck = factor.coerceIn(0f, 1f)
        applyGain()
    }

    fun release() {
        released = true
        interrupt()
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1_000)
        thread = null
        closeStream()
    }

    private fun applyGain() {
        synchronized(outputLock) { output?.setGain(gain * duck) }
    }

    private fun interrupt() {
        generation++
        synchronized(outputLock) { output?.interrupt() }
        synchronized(lock) { events.removeAll { it is Chunk } }
    }

    private fun post(event: Event) {
        synchronized(lock) {
            if (released) return
            // Più di un minuto di audio in coda vuol dire che il thread audio è fermo: si butta il più vecchio.
            if (event is Chunk && events.size > MAX_QUEUED) events.removeFirstOrNull()
            events.addLast(event)
            lock.notifyAll()
        }
        if (thread == null) {
            thread = Thread(::run, "xonos-audio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private fun run() {
        onAudioThread()
        while (!released) {
            val event = synchronized(lock) {
                if (events.isEmpty()) lock.wait(IDLE_WAIT_MS)
                events.removeFirstOrNull()
            }
            try {
                when (event) {
                    null -> checkStarved()
                    is Start -> begin(event)
                    is Chunk -> if (event.generation == generation) decode(event)
                    Reset -> restartOutput()
                    Finish -> closeStream()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Errore nell'uscita audio", e)
            }
        }
    }

    private fun begin(start: Start) {
        if (start.fresh) closeStream()
        val previous = format
        format = start.format
        if (start.fresh || previous == null || previous.codec != start.format.codec || previous.header != start.format.header) {
            decoder?.release()
            decoder = decoders(start.format)
            if (decoder == null) Log.w(TAG, "Formato non supportato: ${start.format}")
        }
        if (previous != null && (previous.sampleRate != start.format.sampleRate || previous.channels != start.format.channels)) {
            // Nuova frequenza: serve un'uscita nuova (succede solo se il server ignora il formato chiesto).
            releaseOutput()
        }
        Log.i(TAG, "Stream ${start.format.codec} ${start.format.sampleRate} Hz, ${start.format.channels} canali")
    }

    private fun restartOutput() {
        releaseOutput()
        format?.let {
            decoder?.release()
            decoder = decoders(it)
        }
        _outputting.value = false
    }

    private fun closeStream() {
        releaseOutput()
        decoder?.release()
        decoder = null
        format = null
        _outputting.value = false
    }

    private fun releaseOutput() {
        synchronized(outputLock) {
            output?.release()
            output = null
        }
        framesWritten = 0
        smoothedErrorUs = 0.0
        lateUs = 0
        timed = false
    }

    private fun checkStarved() {
        if (_outputting.value && clock() - lastAudioAt > STARVED_US) _outputting.value = false
    }

    /** Quando si è preso l'ultimo decoder nuovo dopo un guasto (System.nanoTime). */
    private var decoderResetNs = 0L

    private fun decode(chunk: Chunk) {
        val decoder = decoder ?: return
        var rendering = false
        try {
            decoder.decode(chunk.data, chunk.offset, chunk.length, chunk.timestampUs) { pcm, frames, timestampUs ->
                rendering = true
                render(pcm, frames, timestampUs, chunk.generation)
                rendering = false
            }
        } catch (e: Exception) {
            if (rendering) throw e
            // Un decoder guasto (MediaCodec in errore) non torna da solo: senza questo, silenzio fino al brano dopo e un
            // errore per ogni pacchetto. Se ne prende uno nuovo, al massimo una volta al secondo; si perde solo il pezzo.
            runCatching { decoder.release() }
            val now = System.nanoTime()
            val again = now - decoderResetNs > DECODER_RESET_NS
            this.decoder = if (again) format?.let(decoders) else null
            decoderResetNs = now
            Log.w(TAG, if (again) "Decoder guasto, ne prendo uno nuovo: ${e.message}" else "Decoder guasto di nuovo: aspetto il prossimo brano")
        }
    }

    private fun ensureOutput(format: StreamFormat): PcmOutput? {
        output?.let { return it }
        val created = outputs(format.sampleRate, format.channels) ?: return null
        synchronized(outputLock) {
            output = created
            created.setGain(gain * duck)
        }
        framesWritten = 0
        smoothedErrorUs = 0.0
        lateUs = 0
        timed = false
        openedAt = clock()
        created.play()
        return created
    }

    private fun render(pcm: ShortArray, frames: Int, timestampUs: Long, gen: Int) {
        if (gen != generation || frames <= 0) return
        val format = format ?: return
        val out = ensureOutput(format) ?: return
        val fresh = framesWritten == 0L
        if (fresh && !prime(out, gen)) return

        // Il ritardo dell'avvio si recupera piano, meno della correzione dolce più sotto.
        if (!fresh && lateUs > 0) lateUs = max(0L, lateUs - (frames * 1_000_000L / out.sampleRate * CATCH_UP).toLong())
        var target = filter.toClient(timestampUs) + lateUs
        // Il primo tempo vero dell'uscita da quando è aperta: fin lì quando si sente era una stima. Un Bluetooth
        // sospeso (l'autoradio dopo una pausa) può restare fermo più di quanto [prime] aspetta, e il ritardo che si
        // scopre adesso è ancora quello dell'avvio, non un buco della rete: non si salta.
        val firstStamp = !timed && out.presentationTimeUs(framesWritten) != null
        if (firstStamp) timed = true
        var error = heardAt(out) - target // positivo: siamo in ritardo
        report(error, fresh)
        var start = 0
        var count = frames
        // Prima del primo tempo vero (per [UNTIMED_GRACE_US] al massimo) "in ritardo" è solo una stima: un'uscita
        // ferma che riparte sembra in ritardo. Non si salta né si accelera: il primo tempo vero dirà quanto.
        val guessed = !fresh && !timed && error > 0 && clock() - openedAt < UNTIMED_GRACE_US
        if (guessed) {
            if (writeFrames(out, pcm, 0, frames, gen)) markAudio()
            return
        }
        if (fresh || abs(error) > HARD_US || (firstStamp && error > 0)) {
            // All'avvio, o dopo un buco della rete: ci si allinea in un colpo solo.
            smoothedErrorUs = 0.0
            if ((fresh || firstStamp) && error > 0) {
                // L'uscita ci ha messo più del margine chiesto al server: l'inizio del brano non si
                // salta, lo si suona un po' dopo (fino a [MAX_LATE_US]) e si recupera piano.
                val late = min(error, max(0L, MAX_LATE_US - lateUs))
                Log.d(TAG, "Inizio ${(lateUs + late) / 1000} ms dopo il momento giusto: non si salta, si recupera piano")
                lateUs += late
                target += late
                error -= late
            }
            if (error > 0) {
                // Audio già passato: lo si salta.
                Log.d(TAG, "Audio in ritardo di ${error / 1000} ms: lo si salta")
                val drop = framesFor(error, out.sampleRate).coerceAtMost(count.toLong()).toInt()
                start = drop
                count -= drop
                if (count == 0) return
            } else if (error < 0 && !waitFor(out, target, gen)) {
                return
            }
        } else {
            smoothedErrorUs = smoothedErrorUs * 0.9 + error * 0.1
            if (abs(smoothedErrorUs) > SOFT_US && count > 200) {
                // Mezzo per cento al massimo, un campione alla volta sparso nel blocco: non si sente.
                val step = max(1, count / 200)
                val delta = if (smoothedErrorUs > 0) -step else step
                if (writeFrames(out, adjust(pcm, count, format.channels, delta), 0, count + delta, gen)) markAudio()
                return
            }
        }
        if (writeFrames(out, pcm, start, count, gen)) markAudio()
    }

    /** Toglie ([delta] negativo) o ripete singoli campioni, a distanza regolare l'uno dall'altro. */
    private fun adjust(pcm: ShortArray, frames: Int, channels: Int, delta: Int): ShortArray {
        val result = ShortArray((frames + delta) * channels)
        val spacing = frames / (abs(delta) + 1)
        var from = 0
        var to = 0
        for (k in 1..abs(delta)) {
            val n = k * spacing - from
            System.arraycopy(pcm, from * channels, result, to * channels, n * channels)
            from += n
            to += n
            if (delta > 0) {
                System.arraycopy(pcm, (from - 1) * channels, result, to * channels, channels)
                to++
            } else {
                from++
            }
        }
        System.arraycopy(pcm, from * channels, result, to * channels, (frames - from) * channels)
        return result
    }

    /** Ogni tanto nei log: quanto è lontano l'ascolto dal momento voluto dal server. */
    private fun report(error: Long, fresh: Boolean) {
        val now = clock()
        if (fresh) {
            // L'attesa iniziale non è uno scarto: le statistiche partono da dopo l'allineamento.
            Log.d(TAG, "Avvio a ${error / 1000} ms dal momento giusto")
            statSince = now
            statBlocks = 0
            statSum = 0
            statMax = 0
            return
        }
        statBlocks++
        statSum += abs(error)
        statMax = max(statMax, abs(error))
        if (now - statSince < STATS_US) return
        if (statBlocks > 0) {
            Log.i(TAG, "Audio: scarto medio ${statSum / statBlocks / 1000} ms, massimo ${statMax / 1000} ms su $statBlocks blocchi")
        }
        statSince = now
        statBlocks = 0
        statSum = 0
        statMax = 0
    }

    /**
     * Apre l'uscita con un po' di silenzio, finché non sa dire quando suona davvero. Il silenzio in
     * coda resta poco ([PRIME_AHEAD_US]) se l'uscita dice quanto ha già suonato: tutto quello che c'è
     * in coda quando arriva la musica, la musica lo deve aspettare.
     */
    private fun prime(out: PcmOutput, gen: Int): Boolean {
        val step = framesFor(PRIME_STEP_US, out.sampleRate).toInt()
        val ahead = framesFor(PRIME_AHEAD_US, out.sampleRate)
        val until = clock() + PRIME_MAX_US
        while (out.presentationTimeUs(framesWritten) == null && clock() < until) {
            val played = out.playedFrames()
            if (played != null && framesWritten >= out.startFrames && framesWritten - played >= ahead) {
                Thread.sleep(PRIME_SLEEP_MS)
                if (gen != generation || released) return false
                continue
            }
            if (!writeSilence(out, step, gen)) return false
        }
        return true
    }

    /** Quando si sentirà il prossimo campione scritto; mai prima di adesso, anche se l'uscita è rimasta a secco. */
    private fun heardAt(out: PcmOutput): Long {
        val now = clock()
        val estimate = out.presentationTimeUs(framesWritten) ?: (now + out.latencyUs)
        return max(estimate, now + MIN_LATENCY_US)
    }

    /** Silenzio fino a [target]: se manca molto si aspetta prima, per non riempirsi di silenzio. */
    private fun waitFor(out: PcmOutput, target: Long, gen: Int): Boolean {
        var wait = target - heardAt(out)
        while (wait > MAX_SILENCE_US) {
            Thread.sleep(SLEEP_STEP_MS)
            if (gen != generation || released) return false
            wait = target - heardAt(out)
        }
        return wait <= 0 || writeSilence(out, framesFor(wait, out.sampleRate).toInt(), gen)
    }

    private fun markAudio() {
        lastAudioAt = clock()
        if (!_outputting.value) _outputting.value = true
    }

    private fun writeSilence(out: PcmOutput, frames: Int, gen: Int): Boolean {
        val needed = min(frames, framesFor(MAX_SILENCE_US, out.sampleRate).toInt()) * out.channels
        if (silence.size < needed) silence = ShortArray(needed)
        return writeFrames(out, silence, 0, needed / out.channels, gen)
    }

    /** Scrive a pezzi, così uno stop arriva in fretta anche mentre l'uscita è piena. */
    private fun writeFrames(out: PcmOutput, data: ShortArray, offset: Int, frames: Int, gen: Int): Boolean {
        var done = 0
        val piece = max(1, out.sampleRate / 50)
        while (done < frames) {
            if (gen != generation || released) return false
            val n = out.write(data, offset + done, min(piece, frames - done))
            if (n == PcmOutput.DEAD) {
                // Morta per sempre (l'audio server è ripartito): ogni scrittura dopo fallirebbe e la
                // musica resterebbe ferma. Si lascia, e il pezzo dopo ne apre una nuova.
                Log.w(TAG, "Uscita audio morta: se ne apre una nuova")
                releaseOutput()
                return false
            }
            if (n <= 0) return false
            done += n
            framesWritten += n
        }
        return true
    }

    private fun framesFor(us: Long, rate: Int): Long = us * rate / 1_000_000

    private companion object {
        const val TAG = "Xonos"

        /** Oltre questo scarto si salta o si aspetta; sotto si corregge piano. */
        const val HARD_US = 400_000L
        const val SOFT_US = 30_000.0
        const val MIN_LATENCY_US = 20_000L
        const val STATS_US = 30_000_000L
        const val PRIME_STEP_US = 10_000L
        const val PRIME_AHEAD_US = 50_000L
        const val PRIME_SLEEP_MS = 5L
        const val PRIME_MAX_US = 600_000L

        /** Il ritardo più grande che si tiene all'avvio invece di saltare l'audio. */
        const val MAX_LATE_US = 2_000_000L

        /** Quanto si aspetta il primo tempo vero dell'uscita prima di fidarsi delle stime (vedi [render]). */
        const val UNTIMED_GRACE_US = 5_000_000L

        /** Quanto in fretta si recupera il ritardo dell'avvio: 0,4% (la correzione dolce arriva allo 0,5%). */
        const val CATCH_UP = 0.004
        const val MAX_SILENCE_US = 3_000_000L
        const val STARVED_US = 1_500_000L
        const val SLEEP_STEP_MS = 50L
        const val IDLE_WAIT_MS = 250L
        const val MAX_QUEUED = 4_000
        const val DECODER_RESET_NS = 1_000_000_000L
    }
}
