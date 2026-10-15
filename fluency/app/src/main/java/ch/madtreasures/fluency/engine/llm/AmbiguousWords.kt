package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import java.text.Normalizer

/**
 * One entry of the user's module "Mehrdeutige Wörter": a word of [source] with its possible
 * translations into [target]. The first meaning is the standard.
 */
data class AmbiguousWord(
    val word: String,
    val source: String,
    val target: String,
    val meanings: List<Meaning>,
) {
    /** a translation, and the words that select it when they occur in the sentence (or the one before) */
    data class Meaning(val translation: String, val hints: List<String> = emptyList())

    /** identifies the entry for remembered choices */
    val key: String get() = "$source>$target:${word.lowercase()}"
}

/**
 * How one listed word is translated in one sentence: [from] (as it is written in the sentence) is
 * forced to become [to].
 * [alternatives] are the other translations of the entry (never wanted in this sentence).
 */
data class WordUse(
    val key: String,
    /** the entry's word as listed */
    val word: String,
    val meaningIndex: Int,
    val from: String,
    val to: String,
    val reason: Reason,
    /** the hint word that decided ([Reason.HINT], [Reason.HINT_BEFORE]) */
    val hint: String? = null,
    val alternatives: List<String> = emptyList(),
    /** all translations of the entry, for choosing another one (forward direction only) */
    val meanings: List<String> = emptyList(),
    /** how often the word is in the sentence ([to] more often in the translation: it replaced another word) */
    val occurrences: Int = 1,
    val status: Status = Status.PLANNED,
) {
    enum class Reason {
        /** chosen by tapping for this sentence */
        FORCED,
        /** a hint word of the meaning is in the sentence */
        HINT,
        /** a hint word is in the sentence before */
        HINT_BEFORE,
        /** chosen earlier in this conversation */
        CHOICE,
        /** no hint: the first meaning */
        STANDARD,
        /** the entry has only one meaning */
        ONLY,
        /** the other direction: one of the translations becomes the entry's word */
        REVERSE,
    }

    enum class Status {
        PLANNED,
        /** the translation contains [to] */
        CONFIRMED,
        /** contained after a second run with an explicit hint */
        CONFIRMED_AFTER_HINT,
        /** another listed meaning was replaced by [to] */
        CORRECTED,
        /** [to] is not in the translation, but no other listed meaning either (synonym) */
        SYNONYM,
        /** [to] is in the translation more often than the word in the sentence, in every run */
        REPEATED,
    }

    /** can the user switch to another meaning? */
    val switchable: Boolean get() = reason != Reason.REVERSE && meanings.size > 1

    /** why this meaning (shown in the app) */
    val reasonLabel: String get() = when (reason) {
        Reason.FORCED -> "von dir gewählt"
        Reason.HINT -> "Hinweis „$hint“"
        Reason.HINT_BEFORE -> "Hinweis davor „$hint“"
        Reason.CHOICE -> "deine Wahl"
        Reason.STANDARD -> "Standard"
        Reason.ONLY -> "fest"
        Reason.REVERSE -> "Gegenrichtung"
    }

    /** what the check of the translation found (shown in the app) */
    val statusLabel: String get() = when (status) {
        Status.PLANNED -> "nicht geprüft"
        Status.CONFIRMED -> "geprüft"
        Status.CONFIRMED_AFTER_HINT -> "geprüft im 2. Durchlauf"
        Status.CORRECTED -> "nachkorrigiert"
        Status.SYNONYM -> "anders formuliert"
        Status.REPEATED -> "mehrfach – bitte prüfen"
    }
}

/**
 * The module "Mehrdeutige Wörter" (pure, unit tested): the user lists words with several meanings,
 * the app picks the meaning by fixed rules and forces it into the translation (TranslationEngine).
 * Sentences without a listed word are translated exactly as without the module.
 *
 * Which meaning, in this order:
 * 1. chosen by tapping for this sentence,
 * 2. a hint word of exactly one meaning in the sentence,
 * 3. a hint word of exactly one meaning in the sentence before (live: the previous utterance),
 * 4. the meaning chosen earlier in this conversation,
 * 5. the first meaning (standard).
 * The other direction (the target language spoken, e.g. "messages" in an English sentence) needs no
 * choice: it becomes the entry's word.
 */
object AmbiguousWords {
    const val MAX_ENTRIES = 300

    /** Storage: one entry per line, "de>en|Nachrichten|messages:Chat,App|news:Fernsehen,Radio". */
    fun parse(storage: String): List<AmbiguousWord> = storage.lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 3) return@mapNotNull null
        val langs = f[0].split('>')
        if (langs.size != 2) return@mapNotNull null
        val word = f[1].trim()
        val meanings = f.drop(2).mapNotNull { m ->
            val translation = m.substringBefore(':').trim()
            val hints = m.substringAfter(':', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            translation.takeIf { it.isNotEmpty() }?.let { AmbiguousWord.Meaning(it, hints) }
        }
        if (word.isEmpty() || meanings.isEmpty()) null else AmbiguousWord(word, langs[0].trim(), langs[1].trim(), meanings)
    }.take(MAX_ENTRIES).toList()

    fun format(entries: List<AmbiguousWord>): String = entries.joinToString("\n") { e ->
        (listOf("${clean(e.source)}>${clean(e.target)}", clean(e.word)) + e.meanings.map { m ->
            clean(m.translation) + (if (m.hints.isEmpty()) "" else ":" + m.hints.joinToString(",") { clean(it) })
        }).joinToString("|")
    }

    /**
     * The fixed translations ("Wort = Übersetzung") of a context document from version 1.4.0 as
     * entries with one meaning; its other sentences are dropped.
     */
    fun fromContextDocument(doc: String, source: String, target: String): List<AmbiguousWord> = doc.lines().mapNotNull { line ->
        val parts = line.split(Regex("\\s*(?:=|→|->)\\s*"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val a = clean(parts[0].trim('"', '„', '“', '\'', ' '))
        val b = clean(parts[1].trim('"', '„', '“', '\'', ' '))
        if (a.isEmpty() || b.isEmpty() || a.length > 40 || b.length > 60) null
        else AmbiguousWord(a, source, target, listOf(AmbiguousWord.Meaning(b)))
    }.distinctBy { it.word.lowercase() }.take(MAX_ENTRIES)

    /** removes the characters of the storage format from user input */
    fun clean(s: String): String = s.replace(Regex("[|:,>\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * The listed words in [text] (translated [source] → [target]) and the meaning each gets.
     * [forced]: chosen by tapping for this sentence; [choices]: chosen earlier in the conversation.
     */
    fun resolve(
        entries: List<AmbiguousWord>,
        text: String,
        previous: String?,
        source: Language?,
        target: Language,
        choices: Map<String, Int> = emptyMap(),
        forced: Map<String, Int> = emptyMap(),
    ): List<WordUse> {
        val uses = mutableListOf<WordUse>()
        for (e in entries) {
            val es = Languages.byCode(e.source)
            val et = Languages.byCode(e.target)
            val forward = sameLanguage(et, target) && (source == null || sameLanguage(es, source))
            val backward = sameLanguage(es, target) && (source == null || sameLanguage(et, source))
            val found = if (forward) find(e.word, text) else null
            if (found != null) {
                uses += forwardUse(e, found, text, previous, choices, forced)
            } else if (backward) {
                for ((i, m) in e.meanings.withIndex()) {
                    val form = find(m.translation, text) ?: continue
                    uses += WordUse(e.key, e.word, i, form, e.word, WordUse.Reason.REVERSE, occurrences = count(m.translation, text))
                    break
                }
            }
        }
        return uses.distinctBy { it.from.lowercase() }
    }

    /** [found]: the form of the entry's word in the sentence (it goes into the prompt) */
    private fun forwardUse(
        e: AmbiguousWord,
        found: String,
        text: String,
        previous: String?,
        choices: Map<String, Int>,
        forced: Map<String, Int>,
    ): WordUse {
        val translations = e.meanings.map { it.translation }
        val occurrences = count(e.word, text)
        fun use(i: Int, reason: WordUse.Reason, hint: String? = null) = WordUse(
            e.key, e.word, i, found, translations[i], reason, hint,
            alternatives = translations.filterIndexed { j, t -> j != i && !t.equals(translations[i], ignoreCase = true) },
            meanings = translations,
            occurrences = occurrences,
        )
        forced[e.key]?.takeIf { it in translations.indices }?.let { return use(it, WordUse.Reason.FORCED) }
        if (e.meanings.size == 1) return use(0, WordUse.Reason.ONLY)
        hintIn(e, text)?.let { (i, h) -> return use(i, WordUse.Reason.HINT, h) }
        if (previous != null) hintIn(e, previous)?.let { (i, h) -> return use(i, WordUse.Reason.HINT_BEFORE, h) }
        choices[e.key]?.takeIf { it in translations.indices }?.let { return use(it, WordUse.Reason.CHOICE) }
        return use(0, WordUse.Reason.STANDARD)
    }

    /** the only meaning with a hint word in [text] (none or several: null) */
    private fun hintIn(e: AmbiguousWord, text: String): Pair<Int, String>? {
        val hits = e.meanings.mapIndexedNotNull { i, m -> m.hints.firstOrNull { hintOccurs(it, text) }?.let { i to it } }
        return hits.singleOrNull()
    }

    private fun sameLanguage(a: Language?, b: Language?) = a != null && b != null && a.promptName == b.promptName

    /**
     * [word] occurs in [text] as a word of its own, also with a usual ending or without it
     * ("Nachricht" / "Nachrichten", "Bank" / "Bänke" / "Banken", "message" / "messages"), ignoring
     * case, accents and ß/ss - but not inside other words ("Bank" is not in "Bankraub" or "Banker").
     * Chinese/Japanese/Korean have no spaces: anywhere.
     */
    fun occurs(word: String, text: String): Boolean = find(word, text) != null

    /** the form of [word] in [text] (see [occurs]), e.g. "Nachrichten" for "Nachricht" */
    fun find(word: String, text: String): String? = forms(word)?.find(text)?.value

    /** how often [word] is in [text] (see [occurs]) */
    fun count(word: String, text: String): Int = forms(word)?.findAll(text)?.count() ?: 0

    /**
     * A hint word occurs in [text] like the word itself ([occurs]); with a "*" at the end at the
     * start of any word ("Fernseh*" in "Fernsehen" and "Fernseher").
     */
    fun hintOccurs(hint: String, text: String): Boolean {
        val h = hint.trim()
        if (!h.endsWith("*")) return occurs(h, text)
        val prefix = h.trimEnd('*').trim()
        if (prefix.isEmpty()) return false
        if (prefix.any(::isCjk)) return text.contains(prefix)
        return Regex("(?<![\\p{L}\\p{N}])" + loose(prefix), RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** True if the translation [output] contains [term] in one of its forms (see [occurs]). */
    fun contains(output: String, term: String): Boolean = occurs(term, output)

    /**
     * Replaces the first form of another listed meaning ([WordUse.alternatives]) in [output] by the
     * wanted one. Null if there is none.
     */
    fun correct(output: String, use: WordUse): String? {
        for (alt in use.alternatives.sortedByDescending { it.length }) {
            val m = forms(alt)?.find(output) ?: continue
            val replacement = if (m.value.first().isUpperCase()) use.to.replaceFirstChar { it.uppercaseChar() } else use.to
            return output.replaceRange(m.range, replacement)
        }
        return null
    }

    /** endings a word may have or lose (longest first); a word keeps at least [MIN_BASE] letters */
    private val ENDINGS = listOf("innen", "en", "es", "in", "e", "n", "s", "x", "a", "i", "o")
    private const val MIN_BASE = 5
    private val formsCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun forms(term: String): Regex? {
        val t = term.trim()
        if (t.isEmpty()) return null
        formsCache[t]?.let { return it }
        val regex = if (t.any(::isCjk)) {
            Regex(Regex.escape(t))
        } else {
            val words = t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            val last = words.last()
            val ending = ENDINGS.firstOrNull {
                last.length - it.length >= MIN_BASE && last.endsWith(it, ignoreCase = true) && !last.endsWith("ss", ignoreCase = true)
            }
            val base = if (ending == null) last else last.dropLast(ending.length)
            var lastForms = loose(base) + "(?:" + ENDINGS.joinToString("|") + ")?"
            // English "battery" / "batteries"
            if (base.length >= 4 && base.endsWith("y", ignoreCase = true)) lastForms = "(?:$lastForms|${loose(base.dropLast(1))}ies)"
            val parts = words.dropLast(1).map(::loose) + lastForms
            Regex("(?<![\\p{L}\\p{N}])" + parts.joinToString("[^\\p{L}\\p{N}]+") + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }
        if (formsCache.size > 4_000) formsCache.clear()
        formsCache[t] = regex
        return regex
    }

    private val VARIANTS = mapOf(
        'a' to "aàáâãäå", 'e' to "eèéêë", 'i' to "iìíîï", 'o' to "oòóôõöø", 'u' to "uùúûü",
        'c' to "cç", 'n' to "nñ", 'y' to "yýÿ",
    )

    /** [word] as a pattern that ignores accents and ß/ss (case: with IGNORE_CASE) */
    private fun loose(word: String): String {
        val base = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace("ß", "ss")
        val sb = StringBuilder()
        var i = 0
        while (i < base.length) {
            if (base.startsWith("ss", i)) {
                sb.append("(?:ss|ß)")
                i += 2
                continue
            }
            val c = base[i]
            val v = VARIANTS[c]
            sb.append(
                when {
                    v != null -> "[$v]"
                    c.isLetterOrDigit() -> c.toString()
                    else -> Regex.escape(c.toString())
                },
            )
            i++
        }
        return sb.toString()
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
