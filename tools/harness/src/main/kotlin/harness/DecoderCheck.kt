package harness

import it.xonos.ma.sendspin.AudioDecoder
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.PcmOutput
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Un decoder che si guasta due volte di fila (il secondo guasto entro un secondo dal primo) a metà di un flusso
 * continuo, come quello di Sendspin: l'audio deve tornare da solo, senza uno stream nuovo.
 */
fun main() {
    val made = AtomicInteger()
    val failuresLeft = AtomicInteger(2)
    var decodedAfter = 0L
    class Flaky(private val inner: AudioDecoder) : AudioDecoder {
        override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
            // Si guasta al 50° pacchetto suonato, e il decoder nuovo subito al primo.
            if (decodedAfter >= 50 && failuresLeft.get() > 0) {
                failuresLeft.decrementAndGet()
                throw IllegalStateException("MediaCodec in errore (finto)")
            }
            decodedAfter++
            inner.decode(data, offset, length, timestampUs, out)
        }
        override fun release() = Unit
    }
    class CountOutput : PcmOutput {
        override val sampleRate = 48_000
        override val channels = 1
        override val latencyUs = 50_000L
        @Volatile var music = 0L
        private val start = System.nanoTime() / 1000
        override fun play() = Unit
        override fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int {
            if (data[offsetFrames].toInt() != 0) music += frames
            Thread.sleep(frames * 1000L / sampleRate)
            return frames
        }
        override fun presentationTimeUs(frame: Long): Long = start + 50_000 + frame * 1_000_000L / sampleRate
        override fun setGain(gain: Float) = Unit
        override fun interrupt() = Unit
        override fun release() = Unit
    }
    val output = CountOutput()
    val engine = AudioEngine({ _, _ -> output }, { f -> made.incrementAndGet(); Flaky(PcmDecoder(f.channels, f.bitDepth)) }, TimeFilter())
    engine.startStream(StreamFormat("pcm", 48_000, 1, 16))
    val t0 = System.nanoTime() / 1000 + 200_000
    // 6 s di flusso continuo, a tempo reale.
    for (i in 0 until 300) {
        val ts = t0 + i * 20_000L
        while (ts - System.nanoTime() / 1000 > 1_000_000) Thread.sleep(5)
        engine.audio(ts, ByteArray(960 * 2) { if (it % 2 == 0) 7 else 0 })
    }
    Thread.sleep(1_500)
    val seconds = output.music / 48_000.0
    engine.release()
    // 300 pacchetti da 20 ms = 6 s; due guasti fanno perdere il secondo d'attesa (e quello che arriva intanto). Senza il
    // nuovo tentativo la musica finiva al secondo guasto, dopo circa 1 s.
    val ok = seconds > 3.5 && made.get() >= 3
    println((if (ok) "OK   " else "MALE ") + "Decoder guasto due volte: %.1f s di musica su 6, %d decoder".format(seconds, made.get()))
    kotlin.system.exitProcess(if (ok) 0 else 1)
}
