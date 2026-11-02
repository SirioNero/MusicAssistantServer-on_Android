package it.xonos.ma.sendspin

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Le uscite e i decoder veri, quelli del telefono. */
object AndroidAudio {

    private const val TAG = "Xonos"

    fun output(sampleRate: Int, channels: Int): PcmOutput? = try {
        AudioTrackOutput(sampleRate, channels)
    } catch (e: Exception) {
        Log.e(TAG, "Impossibile aprire l'uscita audio", e)
        null
    }

    fun decoder(format: StreamFormat): AudioDecoder? = try {
        when (format.codec) {
            "pcm" -> PcmDecoder(format.channels, format.bitDepth)
            "opus" -> MediaCodecDecoder(format, MediaFormat.MIMETYPE_AUDIO_OPUS)
            "flac" -> MediaCodecDecoder(format, MediaFormat.MIMETYPE_AUDIO_FLAC)
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Decoder ${format.codec} non disponibile", e)
        null
    }

    /** Se il telefono sa decodificare un formato, da dichiarare al server. */
    fun canDecode(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (e: Exception) {
        false
    }
}

/** L'uscita audio del telefono: con il Bluetooth collegato, le casse dell'auto. */
private class AudioTrackOutput(override val sampleRate: Int, override val channels: Int) : PcmOutput {

    private val track: AudioTrack
    private val timestamp = AudioTimestamp()
    private val bufferFrames: Int
    private val threshold: Int

    init {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Almeno un quarto di secondo: a schermo spento il telefono non deve mai restare senza audio.
        val bytes = maxOf(minBuffer * 2, sampleRate * channels * 2 / 4)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        bufferFrames = track.bufferSizeInFrames
        // Di suo l'AudioTrack comincia a suonare solo con il buffer pieno: un quarto di secondo di
        // silenzio prima della musica a ogni avvio. Da Android 12 gli bastano pochi millesimi.
        threshold = if (Build.VERSION.SDK_INT >= 31) {
            try {
                track.setStartThresholdInFrames(minOf(bufferFrames, sampleRate * START_MS / 1000))
            } catch (e: Exception) {
                bufferFrames
            }
        } else {
            bufferFrames
        }
    }

    override val startFrames: Int
        get() = threshold

    // Dove Android manda il suono adesso: vale solo mentre l'AudioTrack suona (prima dice null).
    override val route: String?
        get() = try {
            track.routedDevice?.let { describe(it.type) }
        } catch (e: Exception) {
            null
        }

    override val latencyUs: Long
        get() = bufferFrames * 1_000_000L / sampleRate + EXTRA_LATENCY_US

    override fun play() {
        track.play()
    }

    override fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int {
        val written = track.write(data, offsetFrames * channels, frames * channels, AudioTrack.WRITE_BLOCKING)
        return if (written < 0) written else written / channels
    }

    override fun playedFrames(): Long? = try {
        // La posizione è un intero senza segno a 32 bit.
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    } catch (e: IllegalStateException) {
        null
    }

    override fun presentationTimeUs(frame: Long): Long? {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
        return try {
            // Finché l'uscita non dà i suoi tempi (i primi decimi di secondo) si aspetta, invece di indovinare.
            if (track.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                timestamp.nanoTime / 1000 + (frame - timestamp.framePosition) * 1_000_000L / sampleRate
            } else {
                null
            }
        } catch (e: IllegalStateException) {
            null
        }
    }

    override fun setGain(gain: Float) {
        try {
            track.setVolume(gain)
        } catch (e: IllegalStateException) {
            // Già rilasciato.
        }
    }

    override fun interrupt() {
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            // Già rilasciato.
        }
    }

    override fun release() {
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (e: IllegalStateException) {
            // Mai partito.
        }
        track.release()
    }

    private companion object {
        /** Quello che c'è dopo il buffer (mixer, Bluetooth), finché l'uscita non dà i suoi tempi veri. */
        const val EXTRA_LATENCY_US = 50_000L

        /** Quanto audio vuole l'uscita prima di partire, da Android 12 (prima: il buffer intero). */
        const val START_MS = 40

        /** Il tipo di uscita per il registro. */
        fun describe(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "altoparlante"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "capsula del telefono"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "cuffie con il filo"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
            AudioDeviceInfo.TYPE_BUS -> "bus dell'auto"
            else -> "uscita di tipo $type"
        }
    }
}

/**
 * I decoder del telefono. Opus arriva senza intestazione (un pacchetto per messaggio):
 * l'intestazione OpusHead che Android vuole si ricostruisce da frequenza e canali.
 */
private class MediaCodecDecoder(format: StreamFormat, mime: String) : AudioDecoder {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(mime)
    private val info = MediaCodec.BufferInfo()
    private var channels = format.channels

    init {
        val mediaFormat = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channels)
        when (mime) {
            MediaFormat.MIMETYPE_AUDIO_OPUS -> {
                mediaFormat.setByteBuffer("csd-0", opusHead(format.channels, format.sampleRate))
                // Nessun ritardo dell'encoder né preroll dopo un salto: non si butta via niente.
                mediaFormat.setByteBuffer("csd-1", longBuffer(0))
                mediaFormat.setByteBuffer("csd-2", longBuffer(0))
            }
            MediaFormat.MIMETYPE_AUDIO_FLAC -> format.headerBytes()?.let { mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        }
        codec.configure(mediaFormat, null, null, 0)
        codec.start()
    }

    override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
        // Con gli ingressi tutti occupati il decoder di solito aspetta che si prendano le sue uscite:
        // le si prende e si riprova. Prima il pacchetto si buttava, e nella musica restava un buco.
        repeat(INPUT_TRIES) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(data, offset, length)
                codec.queueInputBuffer(index, 0, length, timestampUs, 0)
                drain(out)
                return
            }
            drain(out)
        }
        Log.w("Xonos", "Decoder fermo: pacchetto audio saltato")
    }

    private fun drain(out: (ShortArray, Int, Long) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcm = ShortArray(info.size / 2)
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                            out(pcm, pcm.size / channels, info.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit
            }
        }
    }

    override fun release() {
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // Già fermo.
        }
        codec.release()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_TRIES = 20

        /** L'intestazione di identificazione di Opus (RFC 7845), con pre-skip a zero. */
        fun opusHead(channels: Int, sampleRate: Int): ByteBuffer =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1.toByte()) // versione
                put(channels.toByte())
                putShort(0.toShort()) // pre-skip
                putInt(sampleRate)
                putShort(0.toShort()) // guadagno
                put(0.toByte()) // mappatura dei canali: mono o stereo
                flip()
            }

        fun longBuffer(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}
