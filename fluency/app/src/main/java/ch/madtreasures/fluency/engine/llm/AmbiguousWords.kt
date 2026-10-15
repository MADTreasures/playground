package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.LanguageGuesser
import ch.madtreasures.fluency.core.Languages
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry of the module "Mehrdeutige Wörter": a word of [language] and its meanings. Every
 * meaning has a short description, hint words (in [language]) and its translations into any number
 * of languages, so one entry serves every target language that is filled in.
 */
data class AmbiguousWord(
    val word: String,
    val language: String,
    val meanings: List<Meaning>,
    /** further forms that the usual endings do not cover, e.g. "Schlösser" */
    val forms: List<String> = emptyList(),
    /**
     * Without a hint word in the sentence: true = a hint word in the sentence before decides, else the
     * first meaning; false = the model decides (the translation stays as it would be without the module).
     */
    val standard: Boolean = false,
    /** only [word] and [forms] exactly, no endings ("glasses" must not match "glass") */
    val exact: Boolean = false,
) {
    data class Meaning(
        /** a few words that say which meaning, e.g. "Sitzbank" */
        val label: String,
        /** words that select this meaning when they are in the sentence (in the entry's language) */
        val hints: List<String> = emptyList(),
        /** language code → translations; the first is given to the model, the others count as right too */
        val translations: Map<String, List<String>> = emptyMap(),
    ) {
        /** the translations into [target]; Swiss German falls back to German */
        fun into(target: String): List<String> {
            translations[target]?.let { return it }
            val base = target.substringBefore('-')
            if (base != target && Languages.byCode(base)?.promptName == Languages.byCode(target)?.promptName) {
                return translations[base].orEmpty()
            }
            return emptyList()
        }
    }

    /** identifies the entry for remembered choices (the same for every target language) */
    val key: String get() = "${language.substringBefore('-')}:${word.lowercase()}"
}

/**
 * How one listed word comes out in one sentence translated into [target]. [meaningIndex] is the
 * meaning given to the model, or the one found in its translation ([Reason.MODEL]); -1 = none.
 */
data class WordUse(
    val key: String,
    /** the entry's word as listed */
    val word: String,
    /** the word as it is written in the sentence */
    val from: String,
    val meaningIndex: Int,
    val reason: Reason,
    /** the hint word that decided ([Reason.HINT], [Reason.HINT_BEFORE]) */
    val hint: String? = null,
    /** the short descriptions of all meanings */
    val labels: List<String> = emptyList(),
    /** the translations of every meaning into [target] (empty: none listed) */
    val options: List<List<String>> = emptyList(),
    /** how often the word is in the sentence */
    val occurrences: Int = 1,
    val target: String = "",
    /** [Reason.MODEL]: the meaning a hint word in the sentence before speaks for (-1: none) */
    val suggested: Int = -1,
    val suggestedHint: String? = null,
    val status: Status = Status.PLANNED,
) {
    enum class Reason {
        /** chosen by tapping for this sentence */
        FORCED,
        /** a hint word of the meaning is in the sentence */
        HINT,
        /** a hint word is in the sentence before (entries with a standard) */
        HINT_BEFORE,
        /** chosen earlier in this conversation */
        CHOICE,
        /** no hint: the first meaning */
        STANDARD,
        /** the entry has only one meaning */
        ONLY,
        /** no hint, no choice: the model decided, the app only looked which meaning it took */
        MODEL,
    }

    enum class Status {
        PLANNED,
        /** the translation contains the meaning */
        CONFIRMED,
        /** contained after a second run with an explicit hint */
        CONFIRMED_AFTER_HINT,
        /** another listed meaning was replaced */
        CORRECTED,
        /** the meaning is not in the translation, but no other listed meaning either (synonym) */
        SYNONYM,
        /** the meaning is in the translation more often than the word in the sentence, in every run */
        REPEATED,
        /** the chosen meaning has no translation into the target language */
        NO_TRANSLATION,
        /** [Reason.MODEL]: the model's translation contains exactly one of the meanings */
        DETECTED,
        /** [Reason.MODEL]: none or several meanings in the translation */
        UNCLEAR,
    }

    /** what the model is told (or what it chose), null if unknown */
    val to: String? get() = options.getOrNull(meaningIndex)?.firstOrNull()

    /** the translations that count as the meaning */
    val accepted: List<String> get() = options.getOrNull(meaningIndex).orEmpty()

    /** translations of the other meanings (never wanted when a meaning is given) */
    val alternatives: List<String>
        get() = options.filterIndexed { i, _ -> i != meaningIndex }.flatten()
            .filter { a -> accepted.none { it.equals(a, ignoreCase = true) } }.distinct()

    /** the app chose the meaning (and gives it to the model) */
    val chosen: Boolean get() = reason != Reason.MODEL

    /** can the user switch to another meaning? */
    val switchable: Boolean get() = labels.size > 1

    /** the model took another meaning than a hint word in the sentence before speaks for */
    val doubtful: Boolean get() = reason == Reason.MODEL && suggested >= 0 && suggested != meaningIndex

    /** why this meaning (shown in the app) */
    val reasonLabel: String get() = when (reason) {
        Reason.FORCED -> "von dir gewählt"
        Reason.HINT -> "Hinweis „$hint“"
        Reason.HINT_BEFORE -> "Hinweis davor „$hint“"
        Reason.CHOICE -> "deine Wahl"
        Reason.STANDARD -> "Standard"
        Reason.ONLY -> "fest"
        Reason.MODEL -> "Modell"
    }

    /** what the check of the translation found (shown in the app) */
    val statusLabel: String get() = when (status) {
        Status.PLANNED -> "nicht geprüft"
        Status.CONFIRMED -> "geprüft"
        Status.CONFIRMED_AFTER_HINT -> "geprüft im 2. Durchlauf"
        Status.CORRECTED -> "nachkorrigiert"
        Status.SYNONYM -> "anders formuliert"
        Status.REPEATED -> "mehrfach – bitte prüfen"
        Status.NO_TRANSLATION -> "keine Übersetzung eingetragen"
        Status.DETECTED -> "vom Modell gewählt"
        Status.UNCLEAR -> "nicht erkannt"
    }
}

/**
 * The module "Mehrdeutige Wörter" (pure, unit tested): words with several meanings, the rules that
 * pick a meaning, and the word forms of six languages that find a word in a sentence and a
 * meaning in a translation. TranslationEngine gives the chosen meaning to the model and checks it.
 *
 * Which meaning, in this order:
 * 1. chosen by tapping for this sentence,
 * 2. a hint word of exactly one meaning in the sentence,
 * 3. the meaning chosen earlier in this conversation,
 * 4. entries with a standard: a hint word in the sentence before, else the first meaning,
 * 5. otherwise nothing is given: the model decides, and the app shows which meaning it took
 *    (and whether a hint word in the sentence before speaks against it).
 * Sentences without a listed word, and listed words that no rule decides, get exactly the prompt
 * they would get without the module.
 */
object AmbiguousWords {
    const val MAX_ENTRIES = 500

    // ------------------------------------------------------------------------------- storage

    /**
     * One entry per line:
     * `2|de|Bank|Bänke|M|Sitzbank~im Park,sitz*~en=bench;fr=banc|Geldinstitut~Geld,Konto~en=bank;fr=banque`
     * (language, word, further forms separated by "/", flags: S = standard, X = exact, M = neither;
     * then the meanings: description ~ hint words ~ translations, synonyms separated by "/").
     * Lines of version 1.5.0 ("de>en|Nachrichten|messages:Chat|news:Radio") are read as well.
     */
    fun parse(storage: String): List<AmbiguousWord> = storage.lineSequence()
        .mapNotNull { line -> if (line.startsWith("2|")) parseV2(line) else parseV1(line) }
        .distinctBy { it.key }
        .take(MAX_ENTRIES)
        .toList()

    private fun parseV2(line: String): AmbiguousWord? {
        val f = line.split('|')
        if (f.size < 6) return null
        val language = f[1].trim()
        val word = f[2].trim()
        if (language.isEmpty() || word.isEmpty()) return null
        val forms = f[3].split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val meanings = f.drop(5).mapNotNull { m ->
            val p = m.split('~')
            val label = p[0].trim()
            val hints = p.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val translations = p.getOrNull(2)?.split(';')?.mapNotNull { t ->
                val code = t.substringBefore('=', "").trim()
                val texts = t.substringAfter('=', "").split('/').map { it.trim() }.filter { it.isNotEmpty() }
                if (code.isEmpty() || texts.isEmpty()) null else code to texts
            }?.toMap().orEmpty()
            if (label.isEmpty() && translations.isEmpty()) null
            else AmbiguousWord.Meaning(label.ifEmpty { translations.values.first().first() }, hints, translations)
        }
        if (meanings.isEmpty()) return null
        return AmbiguousWord(word, language, meanings, forms, standard = 'S' in f[4], exact = 'X' in f[4])
    }

    /** 1.5.0: one language pair per entry, the translation was the meaning, the first one the standard */
    private fun parseV1(line: String): AmbiguousWord? {
        val f = line.split('|')
        if (f.size < 3) return null
        val langs = f[0].split('>')
        if (langs.size != 2) return null
        val word = f[1].trim()
        val target = langs[1].trim()
        val meanings = f.drop(2).mapNotNull { m ->
            val translation = m.substringBefore(':').trim()
            val hints = m.substringAfter(':', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            translation.takeIf { it.isNotEmpty() }?.let { AmbiguousWord.Meaning(it, hints, mapOf(target to listOf(it))) }
        }
        if (word.isEmpty() || meanings.isEmpty()) return null
        return AmbiguousWord(word, langs[0].trim(), meanings, standard = true)
    }

    fun format(entries: List<AmbiguousWord>): String = entries.joinToString("\n") { e ->
        val flags = (if (e.standard) "S" else "") + (if (e.exact) "X" else "")
        (
            listOf("2", clean(e.language), cleanWord(e.word), e.forms.map(::cleanWord).filter { it.isNotEmpty() }.joinToString("/"), flags.ifEmpty { "M" }) +
                e.meanings.map { m ->
                    clean(m.label) + "~" + m.hints.map { clean(it).replace(",", " ") }.filter { it.isNotEmpty() }.joinToString(",") + "~" +
                        m.translations.entries.joinToString(";") { (code, texts) -> clean(code) + "=" + texts.map { cleanWord(it) }.filter { it.isNotEmpty() }.joinToString("/") }
                }
            ).joinToString("|")
    }

    /** removes the characters of the storage format from user input */
    fun clean(s: String): String = s.replace(Regex("[|~;=\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim()

    /** like [clean], for words and translations (no "/" and ",") */
    fun cleanWord(s: String): String = clean(s.replace('/', ' ').replace(',', ' '))

    /** [entries] plus the ones of [collection] whose word is not listed yet */
    fun merge(entries: List<AmbiguousWord>, collection: List<AmbiguousWord>): List<AmbiguousWord> {
        val keys = entries.map { it.key }.toSet()
        return (entries + collection.filter { it.key !in keys }).take(MAX_ENTRIES)
    }

    /**
     * The fixed translations ("Wort = Übersetzung") of a context document from version 1.4.0 as
     * entries with one meaning; its other sentences are dropped.
     */
    fun fromContextDocument(doc: String, source: String, target: String): List<AmbiguousWord> = doc.lines().mapNotNull { line ->
        val parts = line.split(Regex("\\s*(?:=|→|->)\\s*"), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val a = cleanWord(parts[0].trim('"', '„', '“', '\'', ' '))
        val b = cleanWord(parts[1].trim('"', '„', '“', '\'', ' '))
        if (a.isEmpty() || b.isEmpty() || a.length > 40 || b.length > 60) null
        else AmbiguousWord(a, source, listOf(AmbiguousWord.Meaning(b, translations = mapOf(target to listOf(b)))), standard = true)
    }.distinctBy { it.word.lowercase() }.take(MAX_ENTRIES)

    // --------------------------------------------------------------------------------- rules

    /**
     * The listed words in [text] (translated from [source], null = not known, into [target]) and the
     * meaning each gets. [previous]: the sentence before and its translation (live: the previous
     * utterance); [forced]: chosen by tapping for this sentence; [choices]: earlier in the conversation.
     */
    fun resolve(
        entries: List<AmbiguousWord>,
        text: String,
        previous: List<String>,
        source: Language?,
        target: Language,
        choices: Map<String, Int> = emptyMap(),
        forced: Map<String, Int> = emptyMap(),
    ): List<WordUse> {
        if (entries.isEmpty() || text.isBlank()) return emptyList()
        // an unknown source language is guessed, only to know which words to look for
        val guessed = if (source == null) LanguageGuesser.guess(text, entries.map { it.language.substringBefore('-') }.distinct()) else null
        val before = previous.filter { it.isNotBlank() }.joinToString("\n")
        val uses = mutableListOf<WordUse>()
        for (e in entries) {
            val speaks = if (source != null) sameLanguage(e.language, source.code) else guessed == e.language.substringBefore('-')
            if (!speaks || sameLanguage(e.language, target.code)) continue
            val options = e.meanings.map { m -> m.into(target.code) }
            if (!relevant(e, options)) continue
            val found = find(e, text) ?: continue
            uses += choose(e, found, text, before, options, target.code, choices, forced)
        }
        return uses.distinctBy { it.from.lowercase() }
    }

    /** an entry matters for a target language if its meanings have different translations into it */
    private fun relevant(e: AmbiguousWord, options: List<List<String>>): Boolean =
        if (e.meanings.size == 1) options[0].isNotEmpty()
        else options.filter { it.isNotEmpty() }.map { it.first().lowercase() }.distinct().size >= 2

    private fun choose(
        e: AmbiguousWord,
        found: String,
        text: String,
        before: String,
        options: List<List<String>>,
        target: String,
        choices: Map<String, Int>,
        forced: Map<String, Int>,
    ): WordUse {
        val labels = e.meanings.map { it.label }
        val occurrences = count(e, text).coerceAtLeast(1)
        fun use(i: Int, reason: WordUse.Reason, hint: String? = null) =
            WordUse(e.key, e.word, found, i, reason, hint, labels, options, occurrences, target)
        forced[e.key]?.takeIf { it in e.meanings.indices }?.let { return use(it, WordUse.Reason.FORCED) }
        if (e.meanings.size == 1) return use(0, WordUse.Reason.ONLY)
        hintIn(e, text)?.let { (i, h) -> return use(i, WordUse.Reason.HINT, h) }
        choices[e.key]?.takeIf { it in e.meanings.indices }?.let { return use(it, WordUse.Reason.CHOICE) }
        val previousHint = if (before.isEmpty()) null else hintIn(e, before)
        if (e.standard) {
            previousHint?.let { (i, h) -> return use(i, WordUse.Reason.HINT_BEFORE, h) }
            return use(0, WordUse.Reason.STANDARD)
        }
        return use(-1, WordUse.Reason.MODEL).copy(suggested = previousHint?.first ?: -1, suggestedHint = previousHint?.second)
    }

    /**
     * The only meaning with a hint word in [text] (none or several: null). A hint never counts inside
     * the word itself ("brems*" is not found in "Bremse", "bremsen" not in the plural "Bremsen");
     * phrases with the word count ("ans Steuer", "die Leiter").
     */
    private fun hintIn(e: AmbiguousWord, text: String): Pair<Int, String>? {
        val own = entryPattern(e)?.findAll(text)?.map { it.range }?.toList().orEmpty()
        fun elsewhere(hint: String) = hintRegex(hint, e.language)?.findAll(text)?.any { m -> own.none { it.first <= m.range.first && m.range.last <= it.last } } ?: false
        val hits = e.meanings.mapIndexedNotNull { i, m -> m.hints.firstOrNull { elsewhere(it) }?.let { i to it } }
        return hits.singleOrNull()
    }

    /** de and de-CH are the same language here (the speech recognition writes standard German) */
    fun sameLanguage(a: String, b: String): Boolean {
        if (a.substringBefore('-') == b.substringBefore('-') && a.substringBefore('-') != "zh") return true
        val la = Languages.byCode(a)
        val lb = Languages.byCode(b)
        return la != null && lb != null && la.promptName == lb.promptName
    }

    /**
     * The meaning the model took: exactly one meaning whose translation into [WordUse.target] is in
     * [output]; null if none or several.
     */
    fun detect(output: String, use: WordUse): Int? {
        val hits = use.options.indices.filter { i -> use.options[i].isNotEmpty() && containsAny(output, use.options[i], use.target) }
        return hits.singleOrNull()
    }

    /**
     * Replaces the first form of another listed meaning ([WordUse.alternatives]) in [output] by the
     * wanted one. Null if there is none.
     */
    fun correct(output: String, use: WordUse): String? {
        val wanted = use.to ?: return null
        for (alt in use.alternatives.sortedByDescending { it.length }) {
            val m = pattern(listOf(alt), use.target)?.find(output) ?: continue
            val replacement = if (m.value.first().isUpperCase()) wanted.replaceFirstChar { it.uppercaseChar() } else wanted
            return output.replaceRange(m.range, replacement)
        }
        return null
    }

    // ------------------------------------------------------------------------------ matching

    /** [text] is the entry's word in one of its forms (a hint like that never decides) */
    fun isFormOf(e: AmbiguousWord, text: String): Boolean = entryPattern(e)?.matchEntire(text.trim()) != null

    /** the form of the entry's word in [text] (the first one), null if it is not there */
    fun find(e: AmbiguousWord, text: String): String? = entryPattern(e)?.find(text)?.value

    /** how often the entry's word is in [text] */
    fun count(e: AmbiguousWord, text: String): Int = entryPattern(e)?.findAll(text)?.count() ?: 0

    /**
     * [word] occurs in [text] as a word of its own, also with a usual ending of [language]
     * ("Bank"/"Bänke"/"Banken", "banca"/"banche", "journal"/"journaux", "avião"/"aviões"),
     * ignoring case, accents and ß/ss - but not inside other words ("Bank" is not in "Bankraub").
     * Chinese, Japanese and Korean have no spaces: anywhere.
     */
    fun occurs(word: String, text: String, language: String?): Boolean = find(word, text, language) != null

    fun find(word: String, text: String, language: String?): String? = pattern(listOf(word), language)?.find(text)?.value

    fun count(word: String, text: String, language: String?): Int = pattern(listOf(word), language)?.findAll(text)?.count() ?: 0

    /** one of [terms] (synonyms) is in [text] */
    fun containsAny(text: String, terms: List<String>, language: String?): Boolean = pattern(terms, language)?.containsMatchIn(text) ?: false

    /** how often [terms] (synonyms) are in [text] */
    fun countAny(text: String, terms: List<String>, language: String?): Int = pattern(terms, language)?.findAll(text)?.count() ?: 0

    /**
     * A hint word occurs in [text] like a listed word ([occurs]); with a "*" at the end at the start
     * of any word ("Fernseh*" in "Fernsehen" and "Fernseher").
     */
    fun hintOccurs(hint: String, text: String, language: String?): Boolean = hintRegex(hint, language)?.containsMatchIn(text) ?: false

    private fun hintRegex(hint: String, language: String?): Regex? {
        val h = hint.trim()
        if (!h.endsWith("*")) return pattern(listOf(h), language)
        val prefix = h.trimEnd('*').trim()
        if (prefix.isEmpty()) return null
        if (prefix.any(::isCjk)) return Regex(Regex.escape(prefix))
        val noun = nounCase(prefix, language)
        return cached("*|$noun|$prefix") { Regex("(?<![\\p{L}\\p{N}])" + noun + loose(prefix), RegexOption.IGNORE_CASE) }
    }

    /**
     * German nouns are written with a capital letter (the speech recognition does so too): "Steuer"
     * must not match the verb "steuern", "Lager" not "lagern", "Pass" not "passen".
     */
    private fun nounCase(term: String, language: String?): String =
        if (language?.substringBefore('-') == "de" && term.first().isUpperCase()) "(?=(?-i:\\p{Lu}))" else ""

    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun cached(key: String, build: () -> Regex): Regex {
        regexCache[key]?.let { return it }
        if (regexCache.size > 8_000) regexCache.clear()
        return build().also { regexCache[key] = it }
    }

    private fun entryPattern(e: AmbiguousWord): Regex? = pattern(listOf(e.word) + e.forms, e.language, e.exact)

    /** [terms] and their forms in [language] as one pattern; null if there is nothing to look for */
    fun pattern(terms: List<String>, language: String?, exact: Boolean = false): Regex? {
        val clean = terms.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        val family = language?.substringBefore('-').orEmpty()
        return cached("$family|$exact|" + clean.joinToString("/")) {
            val alternatives = clean.sortedByDescending { it.length }.map { term ->
                if (term.any(::isCjk)) {
                    Regex.escape(term)
                } else {
                    val tokens = term.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
                    val parts = tokens.mapIndexed { i, t ->
                        // the first and the last word are inflected (Romance and Germanic heads)
                        if (exact || (i != 0 && i != tokens.lastIndex)) loose(t)
                        else "(?:" + variants(t, family).sortedByDescending { it.length }.joinToString("|") { loose(it) } + ")"
                    }
                    "(?<![\\p{L}\\p{N}])" + nounCase(term, family) + parts.joinToString("[^\\p{L}\\p{N}]+") + "(?![\\p{L}\\p{N}])"
                }
            }
            Regex(alternatives.joinToString("|"), RegexOption.IGNORE_CASE)
        }
    }

    /** the usual forms of one word in a language family (singular, plural, a few others) */
    internal fun variants(word: String, family: String): Set<String> {
        val w = word.lowercase()
        val out = linkedSetOf(w)
        if (w.length < 3) return out
        when (family) {
            "de" -> {
                if (w.endsWith("ss")) {
                    // Pass/Pässe/Passes, Schloss/Schlosses - "passen" is a verb
                    out += listOf(w + "e", w + "es")
                } else {
                    var base = w
                    for (end in listOf("innen", "en", "es", "in", "e", "n", "s")) {
                        if (w.endsWith(end) && w.length - end.length >= 4) {
                            base = w.dropLast(end.length)
                            break
                        }
                    }
                    for (b in setOf(w, base)) for (end in listOf("", "e", "en", "n", "es", "s", "ns", "in", "innen")) out += b + end
                }
            }
            "en" -> {
                val bases = linkedSetOf(w)
                when {
                    w.endsWith("ies") && w.length > 4 -> bases += w.dropLast(3) + "y"
                    w.endsWith("ves") && w.length > 4 -> {
                        bases += w.dropLast(3) + "f"
                        bases += w.dropLast(3) + "fe"
                    }
                    w.endsWith("es") && w.length > 4 && listOf("s", "x", "z", "ch", "sh").any { w.dropLast(2).endsWith(it) } -> bases += w.dropLast(2)
                    // (not for short words: "news" is not the plural of "new")
                    w.endsWith("s") && !w.endsWith("ss") && w.length > 4 -> bases += w.dropLast(1)
                }
                for (b in bases) {
                    out += listOf(b, b + "s", b + "es", b + "'s", b + "ing", b + "ed")
                    if (b.endsWith("e")) {
                        out += b + "d"
                        out += b.dropLast(1) + "ing"
                    }
                    if (b.endsWith("y") && b.length > 2 && b[b.length - 2] !in "aeiou") {
                        out += b.dropLast(1) + "ies"
                        out += b.dropLast(1) + "ied"
                    }
                    if (b.endsWith("fe")) out += b.dropLast(2) + "ves" else if (b.endsWith("f")) out += b.dropLast(1) + "ves"
                }
            }
            "fr" -> {
                val bases = linkedSetOf(w)
                if ((w.endsWith("s") || w.endsWith("x")) && w.length > 3) bases += w.dropLast(1)
                if (w.endsWith("aux") && w.length > 4) {
                    bases += w.dropLast(3) + "al"
                    bases += w.dropLast(3) + "ail"
                }
                for (b in bases) {
                    out += listOf(b, b + "s", b + "x", b + "e", b + "es")
                    if (b.endsWith("al")) out += b.dropLast(2) + "aux"
                    if (b.endsWith("ail")) out += b.dropLast(3) + "aux"
                }
            }
            "it" -> {
                // nouns keep their gender: foglio/fogli, foglia/foglie (two different words)
                val stem = w.dropLast(1)
                val hard = stem.endsWith("c") || stem.endsWith("g")
                when (w.last()) {
                    'o' -> {
                        out += if (stem.endsWith("i")) stem else stem + "i"
                        if (hard) out += stem + "hi"
                    }
                    // banca/banche, pesca/pesche (not "pesce": that is the fish)
                    'a' -> out += if (hard) stem + "he" else stem + "e"
                    'e' -> out += stem + "i"
                    'i' -> {
                        out += w + "o"
                        out += stem + "o"
                        out += stem + "e"
                    }
                }
            }
            "es" -> {
                val bases = linkedSetOf(w)
                if (w.endsWith("ces") && w.length > 4) bases += w.dropLast(3) + "z"
                if (w.endsWith("es") && w.length > 4) bases += w.dropLast(2)
                if (w.endsWith("s") && w.length > 3) bases += w.dropLast(1)
                for (b in bases) {
                    out += listOf(b, b + "s", b + "es")
                    if (b.endsWith("z")) out += b.dropLast(1) + "ces"
                }
            }
            "pt" -> {
                val bases = linkedSetOf(w)
                if (w.endsWith("ões") || w.endsWith("ães")) bases += w.dropLast(3) + "ão"
                if (w.endsWith("ns")) bases += w.dropLast(2) + "m"
                if (w.endsWith("is") && w.length > 4) bases += w.dropLast(2) + "l"
                if (w.endsWith("s") && w.length > 3) bases += w.dropLast(1)
                for (b in bases) {
                    out += listOf(b, b + "s", b + "es")
                    if (b.endsWith("ão")) out += listOf(b.dropLast(2) + "ões", b.dropLast(2) + "ães", b.dropLast(2) + "ãos")
                    if (b.endsWith("l")) out += b.dropLast(1) + "is"
                    if (b.endsWith("m")) out += b.dropLast(1) + "ns"
                }
            }
            else -> out += listOf(w + "s", w + "e", w + "en", w + "es", w + "n")
        }
        return out
    }

    private val VARIANTS = mapOf(
        'a' to "aàáâãäå", 'e' to "eèéêë", 'i' to "iìíîï", 'o' to "oòóôõöø", 'u' to "uùúûü",
        'c' to "cç", 'y' to "yýÿ",
    )

    /** [word] as a pattern that ignores accents and ß/ss (not ñ: "año" is not "ano"); case: IGNORE_CASE */
    private fun loose(word: String): String {
        val base = Normalizer.normalize(word.lowercase().replace("ñ", "\u0001"), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace("ß", "ss").replace("\u0001", "ñ")
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
