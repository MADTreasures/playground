package ch.madtreasures.fluency.pipeline

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.engine.asr.SherpaVad
import ch.madtreasures.fluency.engine.asr.SpeechChunk
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import java.io.Closeable

/** Translation as seen by the pipeline (implemented by [TranslationEngine]; faked in tests). */
interface Translator {
    /** id of the model that would serve this request, or null if none can */
    fun modelFor(role: TranslationEngine.Role, source: Language?, target: Language): String?

    suspend fun translate(req: TranslationEngine.Request, onPartial: ((String) -> Unit)? = null): TranslationEngine.Result

    fun cancel(role: TranslationEngine.Role? = null)
}

class EngineTranslator(private val engine: TranslationEngine) : Translator {
    override fun modelFor(role: TranslationEngine.Role, source: Language?, target: Language): String? =
        engine.pickModel(role, source, target)?.id

    override suspend fun translate(req: TranslationEngine.Request, onPartial: ((String) -> Unit)?) =
        engine.translate(req, onPartial)

    override fun cancel(role: TranslationEngine.Role?) = engine.cancel(role)
}

/** Voice activity detection (Silero in the app). */
interface VoiceActivityDetector : Closeable {
    fun accept(samples: FloatArray)
    fun isSpeech(): Boolean
    fun drain(): List<SpeechChunk>
    fun flush()
}

class SileroDetector(private val vad: SherpaVad) : VoiceActivityDetector {
    override fun accept(samples: FloatArray) = vad.accept(samples)
    override fun isSpeech() = vad.isSpeech()
    override fun drain() = vad.drain()
    override fun flush() = vad.flush()
    override fun close() = vad.close()
}

/** Text-to-speech output. */
interface Speaker {
    /** Speaks [text]; suspends until finished (or failed). */
    suspend fun speak(text: String, language: Language)

    fun stop()

    /** Whether a voice for [language] is available. */
    fun canSpeak(language: Language): Boolean
}
