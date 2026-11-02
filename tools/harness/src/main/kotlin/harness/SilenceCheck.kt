package harness

import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.PcmOutput
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter

/**
 * Lo stacco dell'uscita in uso ([AudioEngine.silence]): l'audio già ricevuto resta zitto, anche se arriva altro dello
 * stesso stream; torna su con un play ([AudioEngine.unsilence]) o con uno stream nuovo.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "OK   " else "MALE ") + name + if (detail.isNotEmpty()) ": $detail" else "")
        if (!ok) failures++
    }
    class GainOutput : PcmOutput {
        override val sampleRate = 48_000
        override val channels = 1
        override val latencyUs = 50_000L
        @Volatile var level = -1f
        private val start = System.nanoTime()
        private var written = 0L
        override fun play() = Unit
        override fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int {
            written += frames
            return frames
        }
        override fun presentationTimeUs(frame: Long): Long = start / 1000 + 50_000 + frame * 1_000_000L / sampleRate
        override fun setGain(gain: Float) {
            level = gain
        }
        override fun interrupt() = Unit
        override fun release() = Unit
    }
    val outputs = mutableListOf<GainOutput>()
    val engine = AudioEngine({ _, _ -> GainOutput().also { synchronized(outputs) { outputs += it } } }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val format = StreamFormat("pcm", 48_000, 1, 16)
    fun feed(n: Int) {
        val now = System.nanoTime() / 1000
        repeat(n) { i -> engine.audio(now + 100_000 + i * 20_000L, ByteArray(960 * 2) { 1 }) }
        Thread.sleep(400)
    }
    fun last() = synchronized(outputs) { outputs.lastOrNull()?.level }

    engine.startStream(format)
    feed(5)
    check("Suona a volume pieno", last() == 1f, "${last()}")
    engine.silence()
    check("Uscita staccata: zitto", last() == 0f, "${last()}")
    feed(5)
    check("Altro audio dello stesso stream: resta zitto", last() == 0f, "${last()}")
    engine.setVolume(50, false)
    check("Il volume cambiato non lo riaccende", last() == 0f, "${last()}")
    engine.unsilence()
    check("Play: di nuovo su (col volume nuovo)", last() != null && last()!! > 0.3f && last()!! < 0.4f, "${last()}")
    engine.silence()
    engine.end()
    Thread.sleep(200)
    engine.startStream(format)
    feed(5)
    check("Stream nuovo: di nuovo su", last() != null && last()!! > 0.3f, "${last()} su ${outputs.size} uscite")
    engine.release()
    println(if (failures == 0) "Tutto bene" else "$failures prove non passate")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
