package ch.madtreasures.fluency.engine.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import androidx.annotation.RequiresPermission
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Source of 16 kHz mono float audio. Abstracted for tests (feeding WAV files). */
interface AudioSource : Closeable {
    /** Starts delivering chunks to [onChunk] on a background thread. */
    fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
    override fun close() = stop()
}

/**
 * Microphone via AudioRecord. VOICE_RECOGNITION delivers unprocessed audio (no AGC, no noise
 * suppression), which is what the ASR models were trained on.
 */
class MicrophoneSource(private val chunkSamples: Int = 512) : AudioSource {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "fluency-mic") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var record: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(minBuf, chunkSamples * 4 * 8),
                )
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon nicht verfügbar" }
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                record.startRecording()
                while (running.get()) {
                    val buf = FloatArray(chunkSamples)
                    var off = 0
                    while (off < chunkSamples && running.get()) {
                        val n = record.read(buf, off, chunkSamples - off, AudioRecord.READ_BLOCKING)
                        if (n < 0) error("AudioRecord.read: $n")
                        off += n
                    }
                    if (off == chunkSamples) onChunk(buf)
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            } finally {
                runCatching { record?.stop() }
                record?.release()
                aec?.release()
                running.set(false)
            }
        }
    }

    override fun stop() {
        running.set(false)
        worker?.join(500)
        worker = null
    }
}

object AudioLevel {
    /** RMS level mapped to 0..1 for a level meter. */
    fun of(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size)
        // -60 dB .. 0 dB -> 0 .. 1
        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}
