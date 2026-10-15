package ch.madtreasures.fluency.engine.llm

/**
 * The user's own context document (Einstellungen → Übersetzung → Kontext-Dokument). Nothing in it is
 * chosen by the app:
 * - normal lines are sentences that describe what the conversations are about,
 * - lines "Wort = Übersetzung" are fixed translations.
 *
 * How Hy-MT2 gets it (45 ambiguous sentences, real model on x86):
 * - The sentences go into the system message of the Hunyuan chat template: 29 → 37 correct. The
 *   system message is the same for every translation, so it is evaluated once and then comes from
 *   the KV cache. Hy-MT2's "[Background Information]" template reached 35, as an earlier chat
 *   turn 36.
 * - A fixed translation goes in through Hy-MT2's terminology template, only when its word occurs
 *   in the sentence: sentences + fixed translations 45 of 45.
 */
class ContextDocument private constructor(
    /** the sentences, one per line; empty if there are none */
    val sentences: String,
    val terms: List<Term>,
) {
    data class Term(val source: String, val target: String)

    /** What applies to one text: the sentences, and the fixed translations of the words in it. */
    data class Applied(val sentences: String?, val terms: List<Term>) {
        val isEmpty: Boolean get() = sentences == null && terms.isEmpty()
    }

    val isEmpty: Boolean get() = sentences.isEmpty() && terms.isEmpty()

    val sentenceCount: Int get() = if (sentences.isEmpty()) 0 else sentences.lines().size

    /**
     * The sentences, and every fixed translation whose word occurs in [text]. A term also works the
     * other way round (in a conversation the other person speaks the target language): if [text]
     * contains its translation, the pair is turned around.
     */
    fun forText(text: String): Applied {
        val matching = terms.mapNotNull { t ->
            when {
                occurs(t.source, text) -> t
                occurs(t.target, text) -> Term(t.target, t.source)
                else -> null
            }
        }.distinct()
        return Applied(sentences.ifEmpty { null }, matching)
    }

    companion object {
        /** longer documents are cut: everything goes into every prompt (context of 2048 tokens) */
        const val MAX_CHARS = 2000

        val EMPTY = ContextDocument("", emptyList())

        fun parse(text: String): ContextDocument {
            val sentences = mutableListOf<String>()
            val terms = mutableListOf<Term>()
            for (raw in text.take(MAX_CHARS).lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val term = termOf(line)
                if (term != null) terms += term else sentences += line
            }
            return ContextDocument(sentences.joinToString("\n"), terms.distinct())
        }

        private val separator = Regex("\\s*(?:=|→|->)\\s*")

        /** "Nachrichten = messages" (also with → or ->): a word or short phrase on both sides. */
        private fun termOf(line: String): Term? {
            val parts = line.split(separator, limit = 2)
            if (parts.size != 2) return null
            val a = parts[0].trim().trim('"', '„', '“', '\'')
            val b = parts[1].trim().trim('"', '„', '“', '\'')
            if (a.isEmpty() || b.isEmpty() || a.length > 40 || b.length > 60) return null
            return Term(a, b)
        }

        /**
         * [word] occurs in [text] at the start of a word, with any ending ("Nachricht" also matches
         * "Nachrichten"), ignoring case. Chinese and Japanese have no spaces: there it may be anywhere.
         */
        fun occurs(word: String, text: String): Boolean {
            val w = word.trim()
            if (w.isEmpty()) return false
            if (w.any(::isCjk)) return text.contains(w)
            return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w), RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        private fun isCjk(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c)
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES
        }
    }
}
