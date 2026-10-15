package ch.madtreasures.fluency.engine.asr

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.engine.llm.LlamaNative

/** JNI surface of whisper.cpp in libfluency_jni.so. */
object WhisperCppNative {
    external fun nativeInit(path: ByteArray, useGpu: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, threads: Int, audioCtx: Int): ByteArray?
    external fun nativeWeightType(handle: Long): String?
}

/**
 * whisper.cpp model (GGML/GGUF), used for the Swiss German fine-tunes which only exist in the
 * whisper.cpp format. Too slow for live partials: final results only.
 *
 * Q5_0 files (the Swiss German model) are loaded as Q8_0 - exactly the same weights, but ggml has
 * fast ARM matrix kernels (i8mm, KleidiAI) only for Q8_0 (fluency_jni.cpp, whisper_q8.h).
 */
class WhisperCppAsr private constructor(
    override val id: String,
    override val name: String,
    private var handle: Long,
    val threads: Int,
    private val fixedLanguage: String?,
    /** weight type the model runs with, e.g. "q8_0" */
    val weightType: String,
) : AsrEngine {

    override val supportsFastPartials: Boolean = false

    @Synchronized
    override fun transcribe(samples: FloatArray, language: Language?): AsrResult {
        check(handle != 0L) { "closed" }
        val t0 = System.nanoTime()
        val lang = fixedLanguage ?: language?.whisper ?: "auto"
        val bytes = WhisperCppNative.nativeTranscribe(handle, samples, lang, threads, audioContext(samples.size))
        val text = bytes?.toString(Charsets.UTF_8)?.trim().orEmpty()
        return AsrResult(text, (System.nanoTime() - t0) / 1_000_000, lang.takeIf { it != "auto" })
    }

    override fun cancel() {
        if (handle != 0L) WhisperCppNative.nativeCancel(handle)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) WhisperCppNative.nativeFree(h)
    }

    companion object {
        /** Encoder frames for [samples] (50 per second) plus headroom; 0 = full 30 s window. */
        fun audioContext(samples: Int, enabled: Boolean = true): Int {
            if (!enabled) return 0
            val frames = samples / (SAMPLE_RATE / 50)
            return if (frames >= 1200) 0 else (frames + 128).coerceAtLeast(384)
        }

        fun load(id: String, name: String, path: String, threads: Int, fixedLanguage: String?): WhisperCppAsr? {
            LlamaNative.load()
            val h = WhisperCppNative.nativeInit(path.toByteArray(Charsets.UTF_8), false)
            if (h == 0L) return null
            return WhisperCppAsr(id, name, h, threads, fixedLanguage, WhisperCppNative.nativeWeightType(h) ?: "?")
        }
    }
}
