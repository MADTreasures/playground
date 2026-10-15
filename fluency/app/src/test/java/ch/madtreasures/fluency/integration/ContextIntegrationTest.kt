package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The user's context document on the real Hy-MT2 (x86 host, same JNI code as the app): the
 * ambiguous word from the field test is translated as meant once the document says what the
 * conversation is about, fixed translations are followed, and unrelated sentences stay as they were.
 */
class ContextIntegrationTest {
    companion object {
        /** what a user might write: two sentences about the topic, one fixed translation */
        private const val DOCUMENT = "Ich teste meine Übersetzungs-App auf dem Handy.\n" +
            "Wir sprechen oft über Chat-Nachrichten und das Modell.\nBank = bench"

        private val withDocument = TestEnv.engine(AppSettings(llmThreads = 4, contextDocument = DOCUMENT))
        private val plain = TestEnv.engine(AppSettings(llmThreads = 4))

        @BeforeClass @JvmStatic fun check() {
            TestEnv.assumeLlama()
            TestEnv.assumeFile(TestEnv.files.getValue(ModelCatalog.HY_MT2))
        }

        @AfterClass @JvmStatic fun close() = runBlocking {
            withDocument.unloadAll()
            plain.unloadAll()
        }
    }

    private fun t(e: TranslationEngine, text: String, src: String = "de", tgt: String = "en", role: Role = Role.FINAL) = runBlocking {
        val r = e.translate(TranslationEngine.Request(text, Languages.require(src), Languages.require(tgt), role, ModelCatalog.HY_MT2))
        val label = if (e === withDocument) "mit Dokument" else "ohne Dokument"
        println("$label: $text  ⇒  ${r.text}   (${r.promptTokens} Prompt-Tokens, ${r.reusedTokens} aus dem Cache, ${r.wallMs} ms)")
        r
    }

    /** The sentence from the field test (Live, de → en): "Nachrichten" meant messages, not news. */
    @Test fun nachrichtenAreMessagesWithTheDocument() {
        val sentence = "Aber das wenn ich eine kurze Pause mache, dass die Nachrichten getrennt werden."
        val r = t(withDocument, sentence)
        assertTrue(r.text, r.text.contains("message", ignoreCase = true))
        assertFalse(r.text, r.text.contains("news", ignoreCase = true))
        t(plain, sentence) // for comparison (printed)
    }

    @Test fun fixedTranslationsAreFollowed() {
        val r = t(withDocument, "Wir haben uns auf eine Bank gesetzt.")
        assertTrue(r.text, Regex("\\bbench", RegexOption.IGNORE_CASE).containsMatchIn(r.text))
        t(plain, "Wir haben uns auf eine Bank gesetzt.")
        // the other direction: "bench" in an English sentence becomes "Bank"
        val back = t(withDocument, "We sat down on a bench in the park.", "en", "de")
        assertTrue(back.text, back.text.contains("Bank"))
    }

    @Test fun theDocumentIsNotTranslatedIntoUnrelatedSentences() {
        for ((text, expected) in listOf("Ich war mit meinen Kindern im Zoo." to "zoo", "Wo ist der nächste Bahnhof?" to "station")) {
            val r = t(withDocument, text)
            assertTrue(r.text, r.text.contains(expected, ignoreCase = true))
            assertFalse(r.text, r.text.contains("app", ignoreCase = true) || r.text.contains("model", ignoreCase = true))
        }
    }

    /** The sentences are the same in every prompt: after loading they come from the KV cache. */
    @Test fun theSentencesComeFromTheCache() = runBlocking {
        withDocument.preload(setOf(ModelCatalog.HY_MT2))
        val r = t(withDocument, "Ich gehe jetzt einkaufen.", role = Role.LIVE)
        val p = t(plain, "Ich gehe jetzt einkaufen.", role = Role.LIVE)
        println("Prompt mit Dokument ${r.promptTokens} Tokens, davon ${r.reusedTokens} aus dem Cache; ohne Dokument ${p.promptTokens}")
        // everything that the document adds was evaluated before (warm-up after loading)
        assertTrue("${r.reusedTokens} von ${r.promptTokens}", r.promptTokens - r.reusedTokens <= p.promptTokens)
        assertTrue(r.text.contains("shop", ignoreCase = true))
    }
}
