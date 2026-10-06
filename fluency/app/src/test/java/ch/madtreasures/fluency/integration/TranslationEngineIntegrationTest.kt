package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.settings.AUTO
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** The complete translation path (routing, prompts, streaming, cleanup, Swiss spelling) on real models. */
class TranslationEngineIntegrationTest {
    companion object {
        private val engine = TestEnv.engine()

        @BeforeClass @JvmStatic fun check() {
            TestEnv.assumeLlama()
            TestEnv.assumeFile(TestEnv.files.getValue(ModelCatalog.HY_MT2))
        }

        @AfterClass @JvmStatic fun close() = runBlocking { engine.unloadAll() }
    }

    private fun t(text: String, src: String, tgt: String, role: Role = Role.FINAL, model: String? = null): TranslationEngine.Result = runBlocking {
        val r = engine.translate(
            TranslationEngine.Request(text, if (src == AUTO) null else Languages.require(src), Languages.require(tgt), role, model),
        )
        println("[${r.modelName}] $src→$tgt: $text  ⇒  ${r.text}   (${r.wallMs} ms, ${"%.1f".format(r.tokensPerSecond)} tok/s)")
        r
    }

    @Test fun swissGermanTargetNeverContainsEszett() {
        val r = t("The street is very large. Greetings from the big city!", "en", "de-CH")
        assertFalse(r.text, r.text.contains('ß'))
        assertTrue(r.text, r.text.contains("Strasse") || r.text.contains("gross") || r.text.contains("Grüsse"))
        // for comparison: standard German keeps the model's spelling
        t("The street is very large. Greetings from the big city!", "en", "de")
    }

    @Test fun coreLanguagePairs() {
        val sentence = mapOf(
            "de" to "Wo ist der nächste Bahnhof?",
            "en" to "Where is the nearest train station?",
            "fr" to "Où est la gare la plus proche ?",
            "it" to "Dov'è la stazione più vicina?",
            "es" to "¿Dónde está la estación de tren más cercana?",
            "pt" to "Onde fica a estação de comboios mais próxima?",
        )
        for ((src, text) in sentence) {
            val tgt = if (src == "en") "de" else "en"
            val r = t(text, src, tgt)
            assertTrue(r.text.isNotBlank())
            assertNotEquals(text, r.text)
            if (tgt == "en") assertTrue(r.text, r.text.contains("station", ignoreCase = true))
            if (tgt == "de") assertTrue(r.text, r.text.contains("Bahnhof"))
        }
    }

    @Test fun autoSourceWorks() {
        val r = t("Buongiorno, vorrei un caffè per favore.", AUTO, "de", Role.LIVE)
        assertTrue(r.text, r.text.contains("Kaffee"))
    }

    @Test fun textModeKeepsParagraphs() {
        val r = t("Hello Anna!\n\nThanks for your message. I will call you tomorrow.\nBest regards", "en", "de", Role.TEXT, ModelCatalog.HY_MT2)
        assertEquals(4, r.text.lines().size)
        assertTrue(r.text.lines()[1].isBlank())
    }

    @Test fun streamingDeliversGrowingText() = runBlocking {
        val seen = mutableListOf<String>()
        val r = engine.translate(
            TranslationEngine.Request("Ich habe heute leider keine Zeit, aber morgen gerne.", Languages.require("de"), Languages.require("en"), Role.LIVE),
        ) { seen += it }
        assertTrue(seen.size >= 3)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.startsWith(a.trimEnd()) || b.length >= a.length })
        assertEquals(r.text, seen.last().trim())
    }

    @Test fun milmmtModels() {
        for (id in listOf(ModelCatalog.MILMMT_1B, ModelCatalog.MILMMT_4B)) {
            if (TestEnv.files[id]?.exists() != true) continue
            val r = t("Das Wetter ist heute wunderschön, wir gehen am Nachmittag schwimmen.", "de", "en", Role.TEXT, id)
            assertTrue(r.text, r.text.contains("weather", ignoreCase = true))
            assertTrue(r.text, r.text.contains("swim", ignoreCase = true))
            // a language Hy-MT2 does not have
            val sv = t("Good morning, how are you?", "en", "sv", Role.TEXT, id)
            assertTrue(sv.text, sv.text.contains("God morgon", ignoreCase = true) || sv.text.contains("hur", ignoreCase = true))
            val ch = t("The street is large.", "en", "de-CH", Role.TEXT, id)
            assertFalse(ch.text.contains('ß'))
        }
    }
}
