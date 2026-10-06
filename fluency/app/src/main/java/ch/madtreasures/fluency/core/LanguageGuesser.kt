package ch.madtreasures.fluency.core

/**
 * Tiny offline language guesser for short transcripts. Only needed when the source language is
 * "Automatisch" and the chosen translation model (MiLMMT) must be told the source language.
 * Script detection for non-Latin scripts, stop-word voting for Latin-script languages.
 */
object LanguageGuesser {

    private val stopWords: Map<String, Set<String>> = mapOf(
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ich", "du", "wir", "sie", "ein", "eine", "zu", "mit", "auf", "für", "es", "den", "dem", "von", "auch", "wie", "noch", "aber", "bitte", "danke", "heute", "morgen"),
        "en" to setOf("the", "and", "is", "are", "you", "i", "we", "to", "of", "in", "it", "that", "this", "for", "with", "not", "have", "do", "what", "please", "thanks", "my", "your", "be", "was"),
        "fr" to setOf("le", "la", "les", "et", "est", "je", "tu", "nous", "vous", "il", "elle", "pas", "ne", "un", "une", "des", "du", "de", "que", "qui", "pour", "avec", "merci", "oui", "ce", "c'est"),
        "it" to setOf("il", "lo", "la", "gli", "le", "e", "è", "non", "io", "tu", "noi", "voi", "un", "una", "che", "di", "per", "con", "grazie", "sono", "sì", "questo", "molto", "ciao", "come"),
        "es" to setOf("el", "la", "los", "las", "y", "es", "yo", "tú", "nosotros", "no", "un", "una", "que", "de", "para", "con", "gracias", "sí", "esto", "muy", "pero", "como", "está", "hola", "por"),
        "pt" to setOf("o", "a", "os", "as", "e", "é", "eu", "você", "nós", "não", "um", "uma", "que", "de", "para", "com", "obrigado", "obrigada", "sim", "isso", "muito", "mas", "como", "está", "olá"),
        "nl" to setOf("de", "het", "een", "en", "is", "ik", "jij", "wij", "niet", "dat", "van", "voor", "met", "dank", "ja", "dit", "heel", "maar", "hoe", "zijn"),
        "pl" to setOf("i", "w", "nie", "to", "jest", "się", "na", "że", "z", "co", "jak", "ale", "dziękuję", "tak", "ja", "ty", "my", "jestem"),
        "cs" to setOf("a", "je", "to", "v", "se", "na", "že", "ne", "jsem", "jak", "ale", "děkuji", "ano", "já", "ty", "my", "co"),
        "sv" to setOf("och", "är", "jag", "du", "vi", "inte", "en", "ett", "det", "att", "som", "för", "med", "tack", "ja", "på"),
        "da" to setOf("og", "er", "jeg", "du", "vi", "ikke", "en", "et", "det", "at", "som", "for", "med", "tak", "ja", "på"),
        "no" to setOf("og", "er", "jeg", "du", "vi", "ikke", "en", "et", "det", "å", "som", "for", "med", "takk", "ja", "på"),
        "fi" to setOf("ja", "on", "minä", "sinä", "me", "ei", "se", "että", "kiitos", "kyllä", "mutta", "olen"),
        "tr" to setOf("ve", "bir", "bu", "ben", "sen", "biz", "değil", "için", "ile", "teşekkürler", "evet", "çok", "ama", "ne"),
        "ro" to setOf("și", "este", "eu", "tu", "noi", "nu", "un", "o", "că", "de", "pentru", "cu", "mulțumesc", "da"),
        "hu" to setOf("és", "a", "az", "egy", "nem", "én", "te", "mi", "hogy", "köszönöm", "igen", "van"),
    )

    /** Returns a language code or null if unsure. [candidates] restricts the answer if not empty. */
    fun guess(text: String, candidates: Collection<String> = emptyList()): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        scriptGuess(t)?.let { return it.takeIf { c -> candidates.isEmpty() || c in candidates } }
        val words = t.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val scores = stopWords
            .filterKeys { candidates.isEmpty() || it in candidates }
            .mapValues { (_, sw) -> words.count { it in sw } }
        val best = scores.maxByOrNull { it.value } ?: return null
        if (best.value == 0) return null
        val second = scores.values.sortedDescending().getOrElse(1) { 0 }
        return if (best.value > second) best.key else null
    }

    private fun scriptGuess(t: String): String? {
        var hiraganaKatakana = 0; var han = 0; var hangul = 0; var cyrillic = 0; var greek = 0
        var arabic = 0; var hebrew = 0; var thai = 0; var devanagari = 0; var letters = 0
        var ukrainianLetters = 0; var persianLetters = 0
        for (ch in t) {
            if (!ch.isLetter()) continue
            letters++
            val block = Character.UnicodeBlock.of(ch) ?: continue
            when (block) {
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> hiraganaKatakana++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO -> hangul++
                Character.UnicodeBlock.CYRILLIC -> {
                    cyrillic++
                    if (ch in "іїєґІЇЄҐ") ukrainianLetters++
                }
                Character.UnicodeBlock.GREEK -> greek++
                Character.UnicodeBlock.ARABIC -> {
                    arabic++
                    if (ch in "پچژگ") persianLetters++
                }
                Character.UnicodeBlock.HEBREW -> hebrew++
                Character.UnicodeBlock.THAI -> thai++
                Character.UnicodeBlock.DEVANAGARI -> devanagari++
                else -> {}
            }
        }
        if (letters == 0) return null
        val half = letters / 2
        return when {
            hiraganaKatakana > 0 && hiraganaKatakana + han > half -> "ja"
            hangul > half -> "ko"
            han > half -> "zh"
            cyrillic > half -> if (ukrainianLetters > 0) "uk" else "ru"
            greek > half -> "el"
            arabic > half -> if (persianLetters > 0) "fa" else "ar"
            hebrew > half -> "he"
            thai > half -> "th"
            devanagari > half -> "hi"
            else -> null
        }
    }
}
