package ch.madtreasures.fluency.engine.asr

import ch.madtreasures.fluency.core.Language
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/** Parakeet-TDT (NeMo transducer) or Whisper, both through sherpa-onnx / ONNX Runtime. */
class SherpaAsr private constructor(
    override val id: String,
    override val name: String,
    private var config: OfflineRecognizerConfig,
    private val isWhisper: Boolean,
) : AsrEngine {

    private val recognizer = OfflineRecognizer(null, config)
    private var whisperLanguage: String? = if (isWhisper) config.modelConfig.whisper.language else null

    override val supportsFastPartials: Boolean = !isWhisper

    @Synchronized
    override fun transcribe(samples: FloatArray, language: Language?): AsrResult {
        val t0 = System.nanoTime()
        if (isWhisper) {
            val lang = language?.whisper ?: ""
            if (lang != whisperLanguage) {
                config = config.copy(modelConfig = config.modelConfig.copy(whisper = config.modelConfig.whisper.copy(language = lang)))
                recognizer.setConfig(config)
                whisperLanguage = lang
            }
        }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return AsrResult(r.text.trim(), (System.nanoTime() - t0) / 1_000_000, r.lang.ifBlank { null })
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        fun parakeet(id: String, name: String, dir: File, threads: Int): SherpaAsr {
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    modelType = "nemo_transducer",
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            return SherpaAsr(id, name, cfg, isWhisper = false)
        }

        fun whisper(id: String, name: String, dir: File, threads: Int, prefix: String = "turbo"): SherpaAsr {
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "$prefix-encoder.int8.onnx").path,
                        decoder = File(dir, "$prefix-decoder.int8.onnx").path,
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = File(dir, "$prefix-tokens.txt").path,
                    modelType = "whisper",
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            return SherpaAsr(id, name, cfg, isWhisper = true)
        }
    }
}
