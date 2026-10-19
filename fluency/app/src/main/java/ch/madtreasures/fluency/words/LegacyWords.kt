package ch.madtreasures.fluency.words

/**
 * The list "Mehrdeutige Wörter" of versions 1.5.0/1.6.0 (one entry per word of the original text,
 * with meanings and their translations into several languages) as word groups per target language,
 * so that words the user added there are kept. Entries of the collection built into 1.6.0 are
 * skipped: the start lists contain them already.
 */
object LegacyWords {
    private class Meaning(val label: String, val translations: Map<String, List<String>>)
    private class Entry(val language: String, val word: String, val forms: List<String>, val meanings: List<Meaning>)

    /** target language (file code, see [WordGroupStore.fileCode]) → groups */
    fun groups(storage: String): Map<String, List<WordGroup>> {
        val out = LinkedHashMap<String, MutableList<WordGroup>>()
        for (line in storage.lineSequence()) {
            val e = (if (line.startsWith("2|")) parseV2(line) else parseV1(line)) ?: continue
            val base = e.language.substringBefore('-')
            if ("$base:${e.word.lowercase()}" in BUILT_IN) continue
            val targets = e.meanings.flatMap { it.translations.keys }.toSet()
            for (target in targets) {
                // Swiss Standard German shares the German list; standard German translations go first
                if (target == "de-CH" && "de" in targets) continue
                val code = WordGroupStore.fileCode(target)
                if (code.substringBefore('-') == base) continue
                val words = e.meanings.mapNotNull { m ->
                    val t = m.translations[target]?.firstOrNull()?.let(WordGroups::cleanTerm)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    GroupWord(listOf(t), WordGroups.cleanNote(m.label).takeUnless { it.equals(t, ignoreCase = true) }.orEmpty())
                }.distinctBy { it.text.lowercase() }
                if (words.size < 2) continue
                val sources = (listOf(e.word) + e.forms).map(WordGroups::cleanTerm).filter { it.isNotEmpty() }.distinct()
                out.getOrPut(code) { mutableListOf() } += WordGroup(words, sources)
            }
        }
        return out
    }

    /** 1.6.0: `2|de|Bank|Bänke|M|Sitzbank~im Park,sitz*~en=bench;fr=banc|Geldinstitut~Geld~en=bank` */
    private fun parseV2(line: String): Entry? {
        val f = line.split('|')
        if (f.size < 6) return null
        val language = f[1].trim()
        val word = f[2].trim()
        if (language.isEmpty() || word.isEmpty()) return null
        val meanings = f.drop(5).mapNotNull { m ->
            val p = m.split('~')
            val translations = p.getOrNull(2)?.split(';')?.mapNotNull { t ->
                val code = t.substringBefore('=', "").trim()
                val texts = t.substringAfter('=', "").split('/').map { it.trim() }.filter { it.isNotEmpty() }
                if (code.isEmpty() || texts.isEmpty()) null else code to texts
            }?.toMap().orEmpty()
            if (translations.isEmpty()) null else Meaning(p[0].trim(), translations)
        }
        return Entry(language, word, f[3].split('/').map { it.trim() }.filter { it.isNotEmpty() }, meanings)
    }

    /** 1.5.0: `de>en|Nachrichten|messages:Chat,SMS|news:Radio` (the translations were the meanings) */
    private fun parseV1(line: String): Entry? {
        val f = line.split('|')
        if (f.size < 3) return null
        val langs = f[0].split('>')
        if (langs.size != 2) return null
        val target = langs[1].trim()
        val meanings = f.drop(2).mapNotNull { m ->
            m.substringBefore(':').trim().takeIf { it.isNotEmpty() }?.let { Meaning("", mapOf(target to listOf(it))) }
        }
        val word = f[1].trim()
        if (word.isEmpty() || target.isEmpty()) return null
        return Entry(langs[0].trim(), word, emptyList(), meanings)
    }

    /** the entries of the collection built into 1.6.0 (language:word) */
    private val BUILT_IN = setOf(
        "de:absatz", "de:ausdruck", "de:ball", "de:bank", "de:bauer", "de:birne", "de:blatt", "de:bremse", "de:dame",
        "de:decke", "de:estrich", "de:feder", "de:flügel", "de:gang", "de:gericht", "de:hahn", "de:kapelle", "de:kasten",
        "de:kater", "de:kiefer", "de:kurs", "de:lager", "de:leiter", "de:linse", "de:läufer", "de:maus", "de:messe",
        "de:mutter", "de:nachrichten", "de:note", "de:pass", "de:pony", "de:rock", "de:schalter", "de:schimmel",
        "de:schlange", "de:schloss", "de:steuer", "de:stift", "de:stock", "de:strauss", "de:strom", "de:ton", "de:tor",
        "de:umzug", "de:zug", "de:zügeln",
        "en:bank", "en:bark", "en:bat", "en:bill", "en:cell", "en:change", "en:chest", "en:coach", "en:crane", "en:date",
        "en:fair", "en:fan", "en:fine", "en:flat", "en:glasses", "en:jam", "en:key", "en:letter", "en:light", "en:match",
        "en:mouse", "en:nail", "en:organ", "en:palm", "en:plant", "en:pupil", "en:rock", "en:scale", "en:seal",
        "en:sentence", "en:spring", "en:stamp", "en:star", "en:table", "en:ticket", "en:tip", "en:trunk",
        "es:banco", "es:bolsa", "es:carta", "es:cola", "es:cura", "es:gato", "es:hoja", "es:mono", "es:muñeca", "es:papa",
        "es:planta", "es:sierra", "es:tiempo", "es:vela",
        "fr:avocat", "fr:baguette", "fr:bureau", "fr:carte", "fr:feuille", "fr:glace", "fr:livre", "fr:mousse", "fr:pièce",
        "fr:poste", "fr:pêche", "fr:souris", "fr:tableau", "fr:temps", "fr:vol",
        "it:borsa", "it:calcio", "it:camera", "it:collo", "it:lettera", "it:penna", "it:pesca", "it:pianta", "it:riso",
        "it:tasso", "it:vite",
        "pt:banco", "pt:caixa", "pt:conta", "pt:folha", "pt:manga", "pt:pasta", "pt:rede", "pt:vela",
    )
}
