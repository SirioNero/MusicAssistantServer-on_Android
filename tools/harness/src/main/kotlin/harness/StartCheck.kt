package harness

import it.xonos.ma.data.SkipTrace
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.PcmOutput
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max

/**
 * L'inizio di un brano, senza server: il motore audio riceve pacchetti che vanno ascoltati fra
 * poco (il margine chiesto al server) e un'uscita che, come un AudioTrack vero, parte solo con
 * abbastanza audio (il buffer intero fino ad Android 11, 40 ms da Android 12 con Xonos), dice i
 * suoi tempi solo dopo un po' e ha il suo ritardo (altoparlante, Bluetooth). Ogni campione porta
 * il suo numero: così si sa se l'inizio del brano si è sentito o è stato saltato, quando, e se la
 * posizione che il telefono mostra (quella del server meno [AudioEngine.lateMs]) è quella giusta.
 *
 * Poi un salto: un secondo stream sullo stesso motore, con un'uscita nuova verso la stessa uscita fisica. Da Android 12
 * parte subito col ritardo misurato sulla prima, senza aspettare i suoi tempi: il primo suono non deve arrivare più
 * tardi di quanto il ritardo dell'uscita supera il margine (più 30 ms), niente saltato e niente silenzio in mezzo alla
 * musica. Anche quando la seconda uscita è più lenta o più veloce della stima. E la riga «musica udibile» del registro
 * dice quando si sente davvero.
 */
fun main() {
    var failures = 0
    // Le righe «Salto: musica udibile» del registro (lo stub di Log scrive su System.out, con println(Any?)).
    val original = System.out
    System.setOut(
        object : PrintStream(original, true, "UTF-8") {
            override fun println(x: Any?) {
                super.println(x)
                (x as? String)?.let { HEARD.find(it) }?.let { heardLines += it.groupValues[1].toLong() }
            }
        },
    )
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
                    "del server, saltati ${r.skippedMs} ms d'inizio e ${r.gapMs} ms dopo, silenzio in mezzo ${r.silenceMs} ms; dopo " +
                    "${r.afterS} s indietro di ${r.behindMs} ms, il telefono lo sa (${r.lateMs} ms)",
            )
        }
    }
    // Il salto: il primo stream misura il ritardo dell'uscita, il secondo (un'uscita nuova) lo usa. [hold2] e [latency2]:
    // la seconda uscita parte più tardi o ha un ritardo diverso da quello misurato (la stima sbaglia). [seconds]: quanto
    // dura il secondo stream (la correzione dolce di un anticipo, mezzo per cento, vuole più tempo).
    class Skip(
        val label: String,
        val lead: Long,
        val ready: Long,
        val latency: Long,
        val hold2: Long = 0,
        val latency2: Long = latency,
        val seconds: Long = 8,
    )
    val skips = listOf(
        Skip("come sul telefono dell'utente: altoparlante", 165, 190, 140),
        Skip("Wi-Fi, altoparlante", 300, 100, 60),
        Skip("Bluetooth più lento del margine", 165, 250, 300),
        Skip("rete mobile, Bluetooth", 600, 200, 250),
        Skip("la seconda uscita parte 100 ms dopo la stima", 165, 190, 140, hold2 = 100),
        Skip("la seconda uscita è 80 ms più veloce della stima", 165, 190, 140, latency2 = 60, seconds = 16),
        Skip("Bluetooth: la seconda uscita più veloce della stima, sopra il margine", 165, 250, 300, latency2 = 150),
    )
    for (android12 in listOf(false, true)) {
        println(if (android12) "Salto, da Android 12: parte subito col ritardo misurato" else "Salto, fino ad Android 11: aspetta i tempi dell'uscita, come prima")
        for (c in skips) {
            val run = runSkip(c.lead * 1000, c.ready * 1000, c.latency * 1000, android12, c.hold2 * 1000, c.latency2 * 1000, c.seconds * 1000)
            val (first, second) = run.first to run.second
            // Niente saltato né silenzio in mezzo alla musica, nei due stream; la posizione mostrata è quella che si sente.
            val clean = first.skippedMs == 0L && first.gapMs == 0L && first.silenceMs == 0L && second.skippedMs == 0L &&
                second.gapMs == 0L && second.silenceMs == 0L && abs(second.behindMs - second.lateMs) <= 50
            // Il primo suono: non più tardi di quanto l'uscita (con la sua partenza lenta) supera il margine, più 30 ms.
            val limit = max(0L, c.latency2 + c.hold2 - c.lead) + 30
            // «Musica udibile» nel registro: quando si sente davvero il primo campione, dal tocco (al ms, più l'arrotondamento).
            val heardOk = run.heardLogged != null && abs(run.heardLogged - run.heardTrue) <= 3
            val ok = clean && heardOk && (!android12 || second.firstSoundMs <= limit)
            if (!ok) failures++
            println(
                "${if (ok) "OK " else "NO "} ${c.label} (margine ${c.lead} ms): primo suono ${second.firstSoundMs} ms dopo il momento del " +
                    "server${if (android12) " (al massimo $limit)" else ""}, prima uscita ${first.firstSoundMs} ms; saltati ${second.skippedMs} ms " +
                    "d'inizio e ${second.gapMs} ms dopo, silenzio in mezzo ${second.silenceMs} ms (prima uscita ${first.skippedMs}, " +
                    "${first.gapMs} e ${first.silenceMs}); dopo ${second.afterS} s indietro di ${second.behindMs} ms, il telefono lo sa " +
                    "(${second.lateMs} ms); musica udibile ${run.heardLogged} ms dopo il tocco nel registro, davvero ${run.heardTrue}",
            )
        }
    }
    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    System.exit(if (failures == 0) 0 else 1)
}

private class Result(
    val skippedMs: Long,
    val gapMs: Long,
    val silenceMs: Long,
    val firstSoundMs: Long,
    val afterS: Long,
    val behindMs: Long,
    val lateMs: Long,
)

/** Un salto: i due stream, e quando si sente il primo campione del secondo, in ms dal tocco: nel registro e davvero. */
private class SkipRun(val first: Result, val second: Result, val heardLogged: Long?, val heardTrue: Long)

private val HEARD = Regex("Salto: musica udibile (-?\\d+) ms")
private val heardLines: MutableList<Long> = Collections.synchronizedList(mutableListOf())

private const val RATE = 48_000
private const val CHUNK_FRAMES = 960 // 20 ms
private const val RUN_MS = 20_000L

private fun run(leadUs: Long, readyUs: Long, latencyUs: Long, android12: Boolean, holdUs: Long): Result {
    val output = TimedOutput(RATE, readyUs, latencyUs, startFrames = if (android12) RATE * 40 / 1000 else RATE / 4, holdUs = holdUs)
    val engine = AudioEngine({ _, _ -> output }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val result = stream(engine, { output }, leadUs, RUN_MS, 2_000)
    engine.release()
    return result
}

/**
 * Un salto: un primo stream di qualche secondo, la fine, e un secondo stream su un'uscita nuova verso la stessa uscita
 * fisica (da Android 12 con un id: si può usare il ritardo misurato sulla prima). La seconda uscita parte [hold2Us] più
 * tardi e ha il ritardo [latency2Us]: diversi da quelli della prima, la stima sbaglia.
 */
private fun runSkip(
    leadUs: Long,
    readyUs: Long,
    latencyUs: Long,
    android12: Boolean,
    hold2Us: Long,
    latency2Us: Long,
    secondMs: Long,
): SkipRun {
    val outputs = mutableListOf<TimedOutput>()
    val engine = AudioEngine(
        { _, _ ->
            val second = outputs.isNotEmpty()
            TimedOutput(
                RATE, readyUs, if (second) latency2Us else latencyUs,
                startFrames = if (android12) RATE * 40 / 1000 else RATE / 4,
                holdUs = if (second) hold2Us else 0,
                device = if (android12) 7 else null,
            ).also { synchronized(outputs) { outputs += it } }
        },
        { f -> PcmDecoder(f.channels, f.bitDepth) },
        TimeFilter(),
    )
    val last = { synchronized(outputs) { outputs.last() } }
    val first = stream(engine, last, leadUs, 4_000, 1_500)
    // Il tocco, poi stream/end e subito stream/start del brano dopo (qui senza l'attesa di Music Assistant).
    heardLines.clear()
    SkipTrace.touch()
    val touchedAt = now()
    engine.end()
    val second = stream(engine, last, leadUs, secondMs, 2_000)
    val heardTrue = (last().firstSoundAt - touchedAt) / 1000
    engine.release()
    return SkipRun(first, second, heardLines.lastOrNull(), heardTrue)
}

/**
 * Uno stream di [durationMs] che va ascoltato da [leadUs] dopo adesso; [settleMs] dopo l'ultimo pacchetto guarda cosa si
 * sente sull'uscita del momento ([output]).
 */
private fun stream(engine: AudioEngine, output: () -> TimedOutput, leadUs: Long, durationMs: Long, settleMs: Long): Result {
    engine.startStream(StreamFormat("pcm", RATE, 1, 16))
    // Il server manda subito qualche secondo in anticipo, poi il resto man mano.
    val start = now() + leadUs
    val total = (durationMs * 1000 / 20_000).toInt()
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
    Thread.sleep(settleMs)
    // Il campione che si sente adesso, e quello che il server vorrebbe che si sentisse.
    val out = output()
    val at = now()
    val heard = out.frameHeardAt(at)
    val wanted = (at - start) * RATE / 1_000_000
    return Result(
        skippedMs = (out.firstSample - 1) * 1000L / RATE,
        gapMs = out.gapFrames() * 1000L / RATE,
        silenceMs = out.silenceAfterMusic * 1000L / RATE,
        firstSoundMs = (out.firstSoundAt - start) / 1000,
        afterS = (at - start) / 1_000_000,
        behindMs = if (heard < 0) -1 else (wanted - heard) * 1000 / RATE,
        lateMs = engine.lateMs,
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
 * tempo prima di partire (un Bluetooth sospeso che si risveglia). Ricorda il primo campione di musica scritto, il
 * silenzio scritto dopo (un buco nella musica) e quale campione della musica si sente a ogni momento.
 */
private class TimedOutput(
    override val sampleRate: Int,
    private val readyUs: Long,
    override val latencyUs: Long,
    private val bufferFrames: Int = sampleRate / 4,
    override val startFrames: Int = bufferFrames,
    private val holdUs: Long = 0,
    override val device: Int? = null,
) : PcmOutput {
    override val channels = 1
    @Volatile private var firstWriteAt = -1L
    @Volatile private var written = 0L
    @Volatile private var interrupted = false
    @Volatile var firstSample = -1
    @Volatile private var firstMusicFrame = -1L
    @Volatile var silenceAfterMusic = 0L

    /** Quando si sente il primo campione di musica (-1: non ancora scritto, o l'uscita non è ancora partita). */
    val firstSoundAt: Long get() = if (firstMusicFrame < 0 || firstWriteAt < 0) -1 else presentation(firstMusicFrame)

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
                firstMusicFrame = written
            }
        } else if (firstSample >= 0) {
            silenceAfterMusic += frames
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
