package ch.madtreasures.fluency.core

object TextNormalizer {
    /** Swiss Standard German never uses "ß". */
    fun swiss(text: String): String =
        if (text.indexOf('ß') < 0 && text.indexOf('ẞ') < 0) text
        else text.replace("ß", "ss").replace("ẞ", "SS")

    /** Applies the spelling rules of [language] to text shown or spoken in that language. */
    fun forLanguage(text: String, language: Language?): String =
        if (language?.usesSwissSpelling == true) swiss(text) else text

    /** Collapses whitespace runs and trims; keeps paragraph breaks. */
    fun cleanup(text: String): String =
        text.lines().joinToString("\n") { it.trim().replace(Regex("[ \\t\\u00A0]+"), " ") }.trim()

    /**
     * Cleans raw model output: strips a leading "Target language:" echo that some models produce
     * and surrounding quotes added by the model when the source had none.
     */
    fun cleanTranslation(output: String, source: String, targetNames: Collection<String>): String {
        var t = output.trim()
        for (name in targetNames) {
            if (t.startsWith("$name:", ignoreCase = true)) {
                t = t.substring(name.length + 1).trim()
                break
            }
        }
        val quoted = t.length >= 2 && t.first() == '"' && t.last() == '"'
        if (quoted && !(source.trim().startsWith('"') && source.trim().endsWith('"'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    /** Normalised form for comparing ASR hypotheses (case, punctuation and spacing ignored). */
    fun comparable(text: String): String =
        text.lowercase().replace(Regex("[\\p{P}\\p{S}]"), "").replace(Regex("\\s+"), " ").trim()
}
