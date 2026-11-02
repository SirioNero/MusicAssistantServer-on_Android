package harness

import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.PcmOutput
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * L'inizio di un brano, senza server: il motore audio riceve pacchetti che vanno ascoltati fra
 * poco (il margine chiesto al server) e un'uscita che, come un AudioTrack vero, parte solo con
 * abbastanza audio (il buffer intero fino ad Android 11, 40 ms da Android 12 con Xonos), dice i
 * suoi tempi solo dopo un po' e ha il suo ritardo (altoparlante, Bluetooth). Ogni campione porta
 * il suo numero: così si sa se l'inizio del brano si è sentito o è stato saltato, quando, e se la
 * posizione che il telefono mostra (quella del server meno [AudioEngine.lateMs]) è quella giusta.
 */
fun main() {
    var failures = 0
    class Case(val label: String, val lead: Long, val ready: Long, val latency: Long, val hold: Long = 0)
    val cases = listOf(
        Case("Wi-Fi, altoparlante", 300, 100, 60),
        Case("Wi-Fi, Bluetooth in auto", 300, 200, 250),
        Case("rete mobile, Bluetooth", 600, 200, 250),
        Case("telefono lento ad aprire l'uscita", 300, 500, 300),
        Case("margine largo: si aspetta", 1_500, 100, 50),
        // Dopo una pausa l'autoradio ha sospeso il collegamento: l'uscita resta ferma 1,5 s, senza tempi.
        Case("Bluetooth dell'auto lento a ripartire", 300, 200, 250, hold = 1_500),
    )
    for (android12 in listOf(false, true)) {
        println(if (android12) "Da Android 12 (l'uscita parte con 40 ms)" else "Fino ad Android 11 (l'uscita parte con il buffer pieno)")
        for (c in cases) {
            val r = run(c.lead * 1000, c.ready * 1000, c.latency * 1000, android12, c.hold * 1000)
            // Niente inizio saltato né buchi dopo, e la posizione mostrata (server meno ritardo) è quella che si sente.
            val ok = r.skippedMs == 0L && r.gapMs == 0L && abs(r.behindMs - r.lateMs) <= 50
            if (!ok) failures++
            println(
                "${if (ok) "OK " else "NO "} ${c.label} (margine ${c.lead} ms): primo suono ${r.firstSoundMs} ms dopo il momento " +
                    "del server, saltati ${r.skippedMs} ms d'inizio e ${r.gapMs} ms dopo; dopo ${r.afterS} s indietro di ${r.behindMs} ms, " +
                    "il telefono lo sa (${r.lateMs} ms)",
            )
        }
    }
    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(0)
}

private class Result(val skippedMs: Long, val gapMs: Long, val firstSoundMs: Long, val afterS: Long, val behindMs: Long, val lateMs: Long)

private const val RATE = 48_000
private const val CHUNK_FRAMES = 960 // 20 ms
private const val RUN_MS = 20_000L

private fun run(leadUs: Long, readyUs: Long, latencyUs: Long, android12: Boolean, holdUs: Long): Result {
    val output = TimedOutput(RATE, readyUs, latencyUs, startFrames = if (android12) RATE * 40 / 1000 else RATE / 4, holdUs = holdUs)
    val engine = AudioEngine({ _, _ -> output }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    engine.startStream(StreamFormat("pcm", RATE, 1, 16))
    // Il server manda subito qualche secondo in anticipo, poi il resto man mano.
    val start = now() + leadUs
    val total = (RUN_MS * 1000 / 20_000).toInt()
    var sent = 0
    while (sent < total) {
        val ts = start + sent * 20_000L
        if (ts - now() > 5_000_000) {
            Thread.sleep(10)
            continue
        }
        engine.audio(ts, chunk(sent * CHUNK_FRAMES))
        sent++
    }
    Thread.sleep(2_000)
    // Il campione che si sente adesso, e quello che il server vorrebbe che si sentisse.
    val at = now()
    val heard = output.frameHeardAt(at)
    val wanted = (at - start) * RATE / 1_000_000
    val late = engine.lateMs
    engine.release()
    return Result(
        skippedMs = (output.firstSample - 1) * 1000L / RATE,
        gapMs = output.gapFrames() * 1000L / RATE,
        firstSoundMs = (output.firstSoundAt - start) / 1000,
        afterS = (at - start) / 1_000_000,
        behindMs = if (heard < 0) -1 else (wanted - heard) * 1000 / RATE,
        lateMs = late,
    )
}

/** Un pacchetto PCM mono a 16 bit: ogni campione vale il suo numero (da 1, modulo 30000). */
private fun chunk(first: Int): ByteArray {
    val b = ByteBuffer.allocate(CHUNK_FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until CHUNK_FRAMES) b.putShort(((first + i) % 30_000 + 1).toShort())
    return b.array()
}

private fun now() = System.nanoTime() / 1000

/**
 * Un'uscita come un AudioTrack: comincia a suonare solo quando ha [startFrames] fotogrammi (di
 * solito il buffer intero, 250 ms), poi consuma in tempo reale, fa sentire ogni campione
 * [latencyUs] dopo e dice i suoi tempi solo [readyUs] dopo la partenza. Con [holdUs] resta ferma ancora quel
 * tempo prima di partire (un Bluetooth sospeso che si risveglia). Ricorda il primo campione di musica scritto e
 * quale campione della musica si sente a ogni momento.
 */
private class TimedOutput(
    override val sampleRate: Int,
    private val readyUs: Long,
    override val latencyUs: Long,
    private val bufferFrames: Int = sampleRate / 4,
    override val startFrames: Int = bufferFrames,
    private val holdUs: Long = 0,
) : PcmOutput {
    override val channels = 1
    @Volatile private var firstWriteAt = -1L
    @Volatile private var written = 0L
    @Volatile private var interrupted = false
    @Volatile var firstSample = -1
    @Volatile var firstSoundAt = -1L

    // Dove sono finiti i campioni di musica: (fotogramma scritto, numero del campione), per pezzi.
    private val marks = mutableListOf<Pair<Long, Int>>()

    // Quando comincia a suonare davvero.
    private val startAt: Long get() = firstWriteAt + holdUs

    private fun consumed(): Long = if (firstWriteAt < 0) 0 else ((now() - startAt) * sampleRate / 1_000_000).coerceIn(0, written)

    override fun play() = Unit

    override fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int {
        if (interrupted) return 0
        // Parte (e da lì consuma) quando ha abbastanza fotogrammi.
        if (firstWriteAt < 0 && written + frames >= startFrames) firstWriteAt = now()
        while (written + frames - consumed() > bufferFrames) {
            Thread.sleep(2)
            if (interrupted) return 0
        }
        val value = data[offsetFrames].toInt()
        if (value != 0) {
            synchronized(marks) { marks += written to value }
            if (firstSample < 0) {
                firstSample = value
                firstSoundAt = presentation(written)
            }
        }
        written += frames
        return frames
    }

    private fun presentation(frame: Long) = startAt + latencyUs + frame * 1_000_000L / sampleRate

    override fun playedFrames(): Long = consumed()

    override fun presentationTimeUs(frame: Long): Long? =
        if (firstWriteAt < 0 || now() - startAt < readyUs) null else presentation(frame)

    /**
     * I fotogrammi di musica saltati dopo il primo: dove un pezzo non riprende dal campione dopo l'ultimo scritto.
     * Le correzioni dolci (un campione ogni tanto) non contano.
     */
    fun gapFrames(): Long = synchronized(marks) {
        marks.zipWithNext().sumOf { (a, b) ->
            val expected = (a.second - 1 + (b.first - a.first)) % 30_000 + 1
            val jump = ((b.second - expected) % 30_000 + 30_000) % 30_000
            if (jump in 100..15_000) jump.toLong() else 0L
        }
    }

    /** Il numero (da 0) del campione di musica che si sente al momento [at], o -1. */
    fun frameHeardAt(at: Long): Long {
        val frame = (at - startAt - latencyUs) * sampleRate / 1_000_000
        val mark = synchronized(marks) { marks.lastOrNull { it.first <= frame } } ?: return -1
        // Il numero va da 1 e riparte dopo 30000: si conta quante volte è ripartito.
        val wrapped = synchronized(marks) { marks.zipWithNext().count { (a, b) -> b.second < a.second && b.first <= mark.first } }
        return wrapped * 30_000L + mark.second - 1 + (frame - mark.first)
    }

    override fun setGain(gain: Float) = Unit
    override fun interrupt() { interrupted = true }
    override fun release() = Unit
}
