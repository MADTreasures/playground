package ch.madtreasures.fluency.engine.asr

import ch.madtreasures.fluency.core.Language
import java.io.Closeable

data class AsrResult(val text: String, val millis: Long, val detectedLanguage: String? = null)

/** Offline speech recogniser working on 16 kHz mono float PCM. Calls are blocking. */
interface AsrEngine : Closeable {
    val id: String
    val name: String

    /** Fast enough to re-decode the growing utterance every few hundred milliseconds. */
    val supportsFastPartials: Boolean

    fun transcribe(samples: FloatArray, language: Language?): AsrResult

    /** Best effort: stops a running decode. */
    fun cancel() {}
}

const val SAMPLE_RATE = 16_000
