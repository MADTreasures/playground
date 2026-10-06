package ch.madtreasures.fluency.core

/**
 * Latency of one finished utterance/translation, in milliseconds.
 *
 * @param asrMs   speech recognition of the final segment (null in text mode); with [reusedAsr]
 *                it ran during the speech pause and did not delay the result
 * @param mtMs    translation; 0 if the partial translation could be reused
 * @param totalMs end of speech (or "translate" tap) until the final translation was complete
 * @param tokensPerSecond decoding speed of the translation model
 */
data class Latency(
    val asrMs: Long?,
    val mtMs: Long,
    val totalMs: Long,
    val tokensPerSecond: Double? = null,
    val reusedPartial: Boolean = false,
    val model: String? = null,
    /** the recognition already ran during the speech pause (before the VAD closed the segment) */
    val reusedAsr: Boolean = false,
) {
    fun format(): String = buildString {
        if (asrMs != null) append(if (reusedAsr) "Erkennung ${asrMs} ms (vorab) · " else "Erkennung ${asrMs} ms · ")
        append(if (reusedPartial) "Übersetzung 0 ms (vorab)" else "Übersetzung ${mtMs} ms")
        append(" · Gesamt ${totalMs} ms")
    }
}
