package ch.madtreasures.fluency.words

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The word lists, one text file per language in [dir] ("en.txt"; Swiss German uses the German
 * "de.txt"). Until the user changes a language, its start list from the app ([defaults]) is used,
 * so a later app version can improve it. Thread-safe; [changes] counts every change.
 */
class WordGroupStore(private val dir: File, private val defaults: (String) -> String?) {
    private val cache = ConcurrentHashMap<String, List<WordGroup>>()
    private val defaultTexts = ConcurrentHashMap<String, String>()
    private val changesFlow = MutableStateFlow(0)

    /** increases with every change of a list */
    val changes: StateFlow<Int> = changesFlow

    fun file(language: String): File = File(dir, "${fileCode(language)}.txt")

    fun groups(language: String): List<WordGroup> = cache.getOrPut(fileCode(language)) { WordGroups.parse(text(language)) }

    /** the list of [language] as a file: the user's file, else the start list */
    fun text(language: String): String {
        val f = file(language)
        if (f.exists()) return runCatching { f.readText() }.getOrDefault("")
        return defaultText(fileCode(language)).orEmpty()
    }

    /** the user changed the list (a file of their own exists) */
    fun isChanged(language: String): Boolean = file(language).exists()

    fun hasDefaults(language: String): Boolean = defaultText(fileCode(language)) != null

    private fun defaultText(code: String): String? =
        defaultTexts[code] ?: defaults(code)?.also { defaultTexts[code] = it }

    @Synchronized
    fun save(language: String, groups: List<WordGroup>) {
        val code = fileCode(language)
        val list = groups.take(WordGroups.MAX_GROUPS)
        dir.mkdirs()
        val f = file(code)
        val tmp = File(dir, "$code.txt.tmp")
        tmp.writeText(WordGroups.fileText(nameOf(code), list))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
        cache[code] = list
        changesFlow.update { it + 1 }
    }

    /** changes the list of [language] in one step (no change of another thread in between is lost) */
    @Synchronized
    fun update(language: String, transform: (List<WordGroup>) -> List<WordGroup>) = save(language, transform(groups(language)))

    /** a whole file typed or imported by the user; returns its groups */
    fun saveText(language: String, text: String): List<WordGroup> = WordGroups.parse(text).also { save(language, it) }

    /** [groups] whose words are not in the list yet are added; returns how many */
    @Synchronized
    fun add(language: String, groups: List<WordGroup>): Int {
        val before = groups(language)
        val after = WordGroups.merge(before, groups)
        if (after.size > before.size) save(language, after)
        return after.size - before.size
    }

    /** back to the start list of the app */
    @Synchronized
    fun reset(language: String) {
        file(language).delete()
        cache.remove(fileCode(language))
        changesFlow.update { it + 1 }
    }

    /** the replacements for a translation into [target] ([source]: the original text) */
    fun swaps(translation: String, source: String, target: Language): List<WordSwap> =
        WordGroups.swaps(translation, source, groups(target.code), swissSpelling = target.usesSwissSpelling)

    companion object {
        /** Swiss Standard German is German with "ss": one list */
        fun fileCode(language: String): String = if (language == "de-CH") "de" else language

        /** the languages with a list of their own, in the order of the language pickers */
        val languages: List<Language> get() = Languages.sortedForPicker.filter { it.code == fileCode(it.code) }

        private fun nameOf(code: String): String = Languages.byCode(code)?.let { "${it.nameDe} ($code)" } ?: code
    }
}
