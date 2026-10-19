package ch.madtreasures.fluency.words

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * One word of a [WordGroup]: its [forms] (e.g. singular and plural, in the same order in every word
 * of the group, so that "banks" becomes "benches") and a short explanation ([note], e.g. "Sitzbank").
 */
data class GroupWord(val forms: List<String>, val note: String = "") {
    val text: String get() = forms.first()
}

/**
 * Words of one language that a translation can mix up, e.g. English "bench" and "bank" for the
 * German "Bank". Under a translation, each of them can be replaced by the others.
 *
 * @param sources the group is only offered when the original text contains one of these words
 *   (endings included: "Bank" also finds "Banken" and "Bänke"); empty = always
 */
data class WordGroup(val words: List<GroupWord>, val sources: List<String> = emptyList())

/** A possible replacement in a translation: [found] at [range] becomes [replacement]. */
data class WordSwap(
    val range: IntRange,
    val found: String,
    val replacement: String,
    /** what the replacement means, e.g. "Sitzbank" */
    val note: String = "",
)

/**
 * The word lists: file format, finding their words in a translation and replacing them. Pure text
 * work, the translation model is not involved.
 *
 * File format, one group per line:
 * `bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank`
 * words separated by "|", forms of a word by ",", an explanation in brackets, after "<" the words
 * of the original text the group belongs to. Lines starting with "#" are comments.
 */
object WordGroups {
    /** per language (every translation is searched for all of them) */
    const val MAX_GROUPS = 3000

    // ------------------------------------------------------------------------------- file format

    fun parse(text: String): List<WordGroup> = text.lineSequence().mapNotNull(::parseLine).take(MAX_GROUPS).toList()

    /** one line of the file; null for comments, empty lines and lines with fewer than two words */
    fun parseLine(line: String): WordGroup? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val cut = l.indexOfFirst { it == '<' || it == '←' }
        val words = (if (cut < 0) l else l.substring(0, cut)).split('|').mapNotNull(::parseWord)
        if (words.size < 2) return null
        return WordGroup(words, if (cut < 0) emptyList() else splitList(l.substring(cut + 1)))
    }

    private fun parseWord(s: String): GroupWord? {
        val open = s.indexOf('(')
        val close = s.lastIndexOf(')')
        val note = if (open >= 0 && close > open) s.substring(open + 1, close) else ""
        val forms = splitList(if (open >= 0) s.substring(0, open) else s)
        return if (forms.isEmpty()) null else GroupWord(forms, note.replace(SPACES, " ").trim())
    }

    /** "a, b , c" → [a, b, c] */
    fun splitList(s: String): List<String> = s.split(',').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }

    fun formatLine(g: WordGroup): String = g.words.joinToString(" | ") { w ->
        w.forms.joinToString(", ") + if (w.note.isNotEmpty()) " (${w.note})" else ""
    } + if (g.sources.isNotEmpty()) " < " + g.sources.joinToString(", ") else ""

    fun format(groups: List<WordGroup>): String = groups.joinToString("") { formatLine(it) + "\n" }

    /** the whole file of one language, with an explanation of the format on top */
    fun fileText(languageName: String, groups: List<WordGroup>): String = header(languageName) + format(groups)

    fun header(languageName: String): String = """
        |# Fluency – doppeldeutige Wörter: $languageName
        |# Eine Zeile pro Gruppe: Wörter, die beim Übersetzen verwechselt werden können, getrennt mit „|“.
        |# Formen eines Wortes mit Komma, in jedem Wort gleich geordnet (z. B. Einzahl, Mehrzahl).
        |# In Klammern eine Erklärung. Nach „<“ optional Wörter des Originaltexts: dann erscheint die
        |# Gruppe nur, wenn eines davon im Original steht (Endungen werden mitgefunden, „Bank“ findet „Bänke“).
        |
    """.trimMargin()

    /** a form or a word of the original text as typed by the user, without the separators of the format */
    fun cleanTerm(s: String): String = s.replace(Regex("[|<←,()#\\n\\r]"), " ").replace(SPACES, " ").trim()

    /** an explanation as typed by the user */
    fun cleanNote(s: String): String = s.replace(Regex("[|<←\\n\\r]"), " ").replace(SPACES, " ").trim()

    /** same words (first forms, ignoring case) - such groups are one entry */
    fun sameWords(a: WordGroup, b: WordGroup): Boolean = key(a) == key(b)

    private fun key(g: WordGroup): Set<String> = g.words.map { it.text.lowercase() }.toSet()

    /** [list] plus the groups of [more] whose words are not in it yet */
    fun merge(list: List<WordGroup>, more: List<WordGroup>): List<WordGroup> {
        val keys = list.map(::key).toMutableSet()
        return list + more.filter { keys.add(key(it)) }
    }

    // -------------------------------------------------------------------------------- finding

    private class Hit(val range: IntRange, val group: WordGroup, val word: Int, val form: Int)

    /**
     * The replacements that [groups] offer for [translation] ([source]: the original text, for
     * [WordGroup.sources]), in reading order. Every place gets one entry per other word of its group;
     * places do not overlap (the longer one wins: "post office" before "office").
     *
     * @param swissSpelling the translation is Swiss Standard German: "ss" instead of "ß"
     */
    fun swaps(translation: String, source: String, groups: List<WordGroup>, swissSpelling: Boolean = false): List<WordSwap> {
        if (translation.isBlank() || groups.isEmpty()) return emptyList()
        val original by lazy { Original(source) }
        val hits = ArrayList<Hit>()
        for (g in groups) {
            if (g.sources.isNotEmpty() && !original.mentions(g.sources)) continue
            g.words.forEachIndexed { wi, w ->
                w.forms.forEachIndexed { fi, f ->
                    pattern(f)?.findAll(translation)?.forEach { m -> hits += Hit(m.range, g, wi, fi) }
                }
            }
        }
        val places = hits.groupBy { it.range }.entries
            .sortedWith(compareBy({ it.key.first }, { -it.key.last }))
        val out = ArrayList<WordSwap>()
        var end = -1
        for ((range, at) in places) {
            if (range.first <= end) continue
            end = range.last
            val found = translation.substring(range)
            val start = atSentenceStart(translation, range.first)
            val seen = HashSet<String>()
            for (h in at) {
                h.group.words.forEachIndexed { i, other ->
                    if (i == h.word) return@forEachIndexed
                    val text = adapt(found, other.forms.getOrElse(h.form) { other.forms.first() }, start, swissSpelling)
                    if (text != found && seen.add(text)) out += WordSwap(range, found, text, other.note)
                }
            }
        }
        return out
    }

    /** [text] with [swap] done; null if [text] no longer has [WordSwap.found] at its place */
    fun apply(text: String, swap: WordSwap): String? {
        if (swap.range.last >= text.length || text.substring(swap.range) != swap.found) return null
        return text.replaceRange(swap.range, swap.replacement)
    }

    /** the place of the new word after [swap] */
    fun replacedRange(swap: WordSwap): IntRange = swap.range.first until swap.range.first + swap.replacement.length

    /**
     * [form] written like [found]: in capitals if [found] is, with a capital at the start of a
     * sentence; otherwise as in the list (German nouns keep theirs, "leicht" stays small).
     */
    internal fun adapt(found: String, form: String, sentenceStart: Boolean, swissSpelling: Boolean): String {
        val t = if (swissSpelling) form.replace("ß", "ss") else form
        val letters = found.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> t.uppercase()
            sentenceStart && found.first().isUpperCase() -> t.replaceFirstChar { it.titlecase() }
            else -> t
        }
    }

    private const val OPENERS = "\"'«»„“”‚‘’‹›([{¿¡–—-*"
    private const val ENDERS = ".!?…。！？"

    internal fun atSentenceStart(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n') return true
            if (c.isWhitespace() || c in OPENERS) {
                i--
                continue
            }
            return c in ENDERS
        }
        return true
    }

    private val patterns = ConcurrentHashMap<String, Regex>()

    /** whole words only (not inside "Bankkonto"), ignoring case and ß/ss; null for an empty form */
    private fun pattern(form: String): Regex? {
        val f = form.trim()
        if (f.isEmpty()) return null
        return patterns.getOrPut(f) {
            val body = f.split(SPACES).joinToString("\\s+", transform = ::loose)
            val pattern = when {
                // Chinese, Japanese, Thai … have no spaces between words
                f.any(::withoutSpaces) -> body
                // Korean particles are written onto the word ("은행에서")
                f.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "(?<![\\p{L}\\p{M}\\p{N}])$body"
                else -> "(?<![\\p{L}\\p{M}\\p{N}])$body(?![\\p{L}\\p{M}\\p{N}])"
            }
            Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }

    private fun loose(token: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < token.length) {
            val c = token[i]
            when {
                c == 'ß' -> sb.append("(?:ss|ß)")
                c.lowercaseChar() == 's' && i + 1 < token.length && token[i + 1].lowercaseChar() == 's' -> {
                    sb.append("(?:ss|ß)")
                    i++
                }
                c == '\'' || c == '’' -> sb.append("['’]")
                // the special characters of regular expressions are all ASCII
                c.code < 128 && !c.isLetterOrDigit() -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun withoutSpaces(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
        -> true
        else -> false
    }

    // ---------------------------------------------------------------------- the original text

    /** The original text, for [WordGroup.sources]: words compared without case and accents. */
    private class Original(text: String) {
        private val flat = normalize(text)
        private val tokens = flat.split(NON_WORD).filter { it.isNotEmpty() }

        fun mentions(words: List<String>): Boolean = words.any { w ->
            val parts = normalize(w).split(NON_WORD).filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> false
                w.any(::withoutSpaces) -> flat.contains(normalize(w).trim())
                else -> (0..tokens.size - parts.size).any { i -> parts.indices.all { j -> sameWord(tokens[i + j], parts[j]) } }
            }
        }
    }

    /** [word] or a form of it: "bank" finds "banken", "banke" (Bänke); "nachrichten" finds "nachricht" */
    internal fun sameWord(token: String, word: String): Boolean = when {
        token == word -> true
        word.length >= 3 && token.startsWith(word) -> token.length - word.length <= 3
        token.length >= 4 && word.startsWith(token) -> word.length - token.length <= 2
        else -> false
    }

    /** lower case, without accents and umlaut dots, "ß" as "ss" */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace("ß", "ss")

    /** whether [text] contains one of [words] in the sense of [WordGroup.sources] */
    fun mentions(text: String, words: List<String>): Boolean = Original(text).mentions(words)

    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
