package ch.madtreasures.fluency.core

/**
 * Splits text into pieces that are translated one by one. Small translation models are faster
 * and more accurate on sentence-sized inputs, and the prompt always stays far below the context.
 */
object TextSegmenter {
    data class Piece(val text: String, val separatorAfter: String)

    private val sentenceEnd = Regex("(?<=[.!?…。！？])\\s+(?=\\S)")

    fun split(text: String, maxChars: Int = 400): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val paragraphs = text.split('\n')
        paragraphs.forEachIndexed { pi, paragraph ->
            val lastParagraph = pi == paragraphs.lastIndex
            if (paragraph.isBlank()) {
                if (!lastParagraph) pieces += Piece("", "\n")
                return@forEachIndexed
            }
            val sentences = groupSentences(paragraph.trim().split(sentenceEnd), maxChars)
            sentences.forEachIndexed { si, s ->
                val sep = when {
                    si < sentences.lastIndex -> " "
                    lastParagraph -> ""
                    else -> "\n"
                }
                pieces += Piece(s, sep)
            }
        }
        return pieces
    }

    /** Joins short sentences (up to [maxChars]) so that context is preserved; hard-splits giants. */
    private fun groupSentences(sentences: List<String>, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (raw in sentences) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.length > maxChars) {
                if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                out += hardSplit(s, maxChars)
                continue
            }
            if (current.isNotEmpty() && current.length + 1 + s.length > maxChars) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun hardSplit(s: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > maxChars) {
            val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
