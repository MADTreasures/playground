package ch.madtreasures.fluency.words

import ch.madtreasures.fluency.core.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The start lists in assets/words: readable, without duplicates, every word findable. */
class StartListsTest {
    private val dir = File("src/main/assets/words")
    private val files = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }

    @Test fun `there is a list for the six core languages`() {
        assertEquals(listOf("de", "en", "es", "fr", "it", "pt"), files.map { it.nameWithoutExtension })
        files.forEach { f -> assertTrue(f.name, WordGroupStore.languages.any { it.code == f.nameWithoutExtension }) }
    }

    @Test fun `every file is written as the app writes it, every group has its language and words of the original`() {
        for (f in files) {
            val code = f.nameWithoutExtension
            val text = f.readText()
            val groups = WordGroups.parse(text)
            assertEquals(f.name, WordGroups.fileText("${Languages.require(code).nameDe} ($code)", groups), text)
            assertTrue(f.name, groups.size in 50..WordGroups.MAX_GROUPS)
            groups.forEach { g ->
                val line = "${f.name}: ${WordGroups.formatLine(g)}"
                assertTrue(line, g.sources.isNotEmpty())
                assertTrue(line, g.language != null && Languages.byCode(g.language!!) != null)
                assertNotEquals(line, code, g.language)
                assertTrue(line, g.words.all { it.forms.size <= 2 })
                assertEquals(line, g.words.size, g.words.map { it.text.lowercase() }.toSet().size)
            }
            assertEquals(f.name, groups.size, WordGroups.merge(emptyList(), groups).size)
            assertTrue(f.name, "ß" !in text.substringAfter("allen Sprachen.\n"))
        }
    }

    @Test fun `every form of every word is found and offers the other words`() {
        for (f in files) {
            for (g in WordGroups.parse(f.readText())) {
                g.words.forEachIndexed { wi, w ->
                    w.forms.forEachIndexed { fi, form ->
                        val swaps = WordGroups.swaps("x $form x", g.sources.first(), listOf(g), sourceLanguage = g.language)
                        val expected = g.words.filterIndexed { i, _ -> i != wi }.map { o -> o.forms.getOrElse(fi) { o.forms.first() } }
                        assertEquals("${f.name}: $form", expected, swaps.map { it.replacement })
                        assertTrue("${f.name}: $form", swaps.all { it.found == form })
                    }
                }
            }
        }
    }
}
