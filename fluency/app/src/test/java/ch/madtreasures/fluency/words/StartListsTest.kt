package ch.madtreasures.fluency.words

import ch.madtreasures.fluency.core.Languages
import org.junit.Assert.assertEquals
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

    @Test fun `every line is a group with words of the original and no group is there twice`() {
        for (f in files) {
            val text = f.readText()
            val name = Languages.require(f.nameWithoutExtension).nameDe
            assertTrue(f.name, text.startsWith("# Fluency – doppeldeutige Wörter: $name (${f.nameWithoutExtension})"))
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            val groups = WordGroups.parse(text)
            assertEquals(f.name, lines.size, groups.size)
            assertTrue(f.name, groups.size in 50..WordGroups.MAX_GROUPS)
            groups.forEach { g ->
                assertTrue("${f.name}: ${WordGroups.formatLine(g)}", g.sources.isNotEmpty())
                assertTrue("${f.name}: ${WordGroups.formatLine(g)}", g.words.all { it.forms.size <= 2 })
                assertEquals("${f.name}: ${WordGroups.formatLine(g)}", g.words.size, g.words.map { it.text.lowercase() }.toSet().size)
                // the app writes lines the same way
                assertEquals(lines[groups.indexOf(g)], WordGroups.formatLine(g))
            }
            assertEquals(f.name, groups.size, WordGroups.merge(emptyList(), groups).size)
            assertTrue(f.name, "ß" !in text.substringAfter("Bänke“).\n"))
        }
    }

    @Test fun `every form of every word is found and offers the other words`() {
        for (f in files) {
            for (g in WordGroups.parse(f.readText())) {
                g.words.forEachIndexed { wi, w ->
                    w.forms.forEachIndexed { fi, form ->
                        val swaps = WordGroups.swaps("x $form x", g.sources.first(), listOf(g))
                        val expected = g.words.filterIndexed { i, _ -> i != wi }.map { o -> o.forms.getOrElse(fi) { o.forms.first() } }
                        assertEquals("${f.name}: $form", expected, swaps.map { it.replacement })
                        assertTrue("${f.name}: $form", swaps.all { it.found == form })
                    }
                }
            }
        }
    }
}
