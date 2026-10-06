package ch.madtreasures.fluency.engine.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable

/** A finished speech segment: [start] is the sample index in the stream since [SherpaVad.reset]. */
class SpeechChunk(val start: Long, val samples: FloatArray) {
    val end: Long get() = start + samples.size
}

/** Silero VAD (v4/v5) through sherpa-onnx. Not thread-safe; used from the audio thread only. */
class SherpaVad(
    modelPath: String,
    minSilenceSeconds: Float,
    minSpeechSeconds: Float = 0.25f,
    maxSpeechSeconds: Float = 15f,
    threshold: Float = 0.5f,
) : Closeable {

    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = threshold,
                minSilenceDuration = minSilenceSeconds,
                minSpeechDuration = minSpeechSeconds,
                windowSize = 512,
                maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    fun isSpeech(): Boolean = vad.isSpeechDetected()

    /** Removes and returns all completed segments. */
    fun drain(): List<SpeechChunk> {
        val out = mutableListOf<SpeechChunk>()
        while (!vad.empty()) {
            val s = vad.front()
            out += SpeechChunk(s.start.toLong(), s.samples)
            vad.pop()
        }
        return out
    }

    /** Ends the stream: an ongoing speech segment becomes available via [drain]. */
    fun flush() = vad.flush()

    fun reset() = vad.reset()

    override fun close() = vad.release()
}
