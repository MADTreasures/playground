package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.TextNormalizer

/** An earlier sentence and its translation, oriented like the current request (source → target). */
data class ContextPair(val source: String, val translation: String)

/**
 * Context for ambiguous words ("Nachrichten" = messages or news): the earlier sentences go in front
 * of the text to translate, and their translation is written as the beginning of the model's
 * answer, so the model simply continues translating. Measured on 45 ambiguous sentences (x86, the
 * real models): Hy-MT2 29 → 40 correct, MiLMMT-46 1B 32 → 44. Hy-MT2's own context template was
 * worse (35) and sometimes translated the context instead of the text. A separate small context
 * model (Qwen3.5-0.8B/2B, LFM2.5-1.2B: topic + word hints via Hy-MT2's terminology template) was
 * worse as well (28-39) and cost about twice the translation time.
 */
object TranslationContext {
    /** at most this many earlier sentences ... */
    const val MAX_PAIRS = 2

    /** ... with at most this many characters of source text together (prompt length = latency) */
    const val MAX_CHARS = 300

    /** The most recent pairs of [pairs] (oldest first) that fit the limits, oldest first. */
    fun select(pairs: List<ContextPair>): List<ContextPair> {
        val out = ArrayDeque<ContextPair>()
        var chars = 0
        for (p in pairs.asReversed()) {
            val s = oneLine(p.source)
            val t = oneLine(p.translation)
            if (s.isEmpty() || t.isEmpty()) continue
            if (out.size == MAX_PAIRS || chars + s.length > MAX_CHARS) break
            out.addFirst(ContextPair(s, t))
            chars += s.length
        }
        return out.toList()
    }

    /** [pairs] as one source text and one translation, or null if there are none. */
    fun join(pairs: List<ContextPair>): PromptFormat.Context? {
        if (pairs.isEmpty()) return null
        return PromptFormat.Context(
            source = pairs.map { oneLine(it.source) }.reduce(::joinSentences),
            translation = pairs.map { oneLine(it.translation) }.reduce(::joinSentences),
        )
    }

    /** Two sentences in a row: a space between them, none between Chinese or Japanese sentences. */
    fun joinSentences(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (isCjk(a.last()) || isCjk(b.first())) a + b else "$a $b"
    }

    /**
     * True if a translation made with [context] cannot be used: empty, or the model repeated the
     * earlier translation instead of continuing. The sentence is then translated without context.
     */
    fun failed(output: String, context: PromptFormat.Context): Boolean {
        val out = TextNormalizer.comparable(output)
        if (out.isEmpty()) return true
        val previous = TextNormalizer.comparable(context.translation)
        if (previous.isEmpty()) return false
        // a short previous sentence ("Yes.") may legitimately start the new one ("Yes, ...")
        return out == previous || (previous.length >= 12 && out.startsWith("$previous "))
    }

    /** Whether earlier sentences in [a] can serve as context for a request in [b] (de and de-CH: yes). */
    fun sameLanguage(a: Language?, b: Language?): Boolean = a != null && b != null && a.promptName == b.promptName

    private fun oneLine(s: String) = s.replace('\n', ' ').trim()

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }
}
