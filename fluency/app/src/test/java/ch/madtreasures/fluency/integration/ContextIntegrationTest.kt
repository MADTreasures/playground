package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.ContextPair
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
 * Translation context on the real Hy-MT2 (x86 host, same JNI code as the app): the ambiguous
 * words from the field test are translated correctly once the earlier sentence is known, and the
 * warm-up brings the next prompt's prefix into the KV cache.
 */
class ContextIntegrationTest {
    companion object {
        private val engine = TestEnv.engine()
        private val plain = TestEnv.engine(AppSettings(llmThreads = 4, translationContext = false))

        @BeforeClass @JvmStatic fun check() {
            TestEnv.assumeLlama()
            TestEnv.assumeFile(TestEnv.files.getValue(ModelCatalog.HY_MT2))
        }

        @AfterClass @JvmStatic fun close() = runBlocking {
            engine.unloadAll()
            plain.unloadAll()
        }
    }

    private val de = Languages.require("de")
    private val en = Languages.require("en")

    private fun t(
        e: TranslationEngine,
        text: String,
        src: String,
        tgt: String,
        context: List<ContextPair> = emptyList(),
        role: Role = Role.FINAL,
    ): TranslationEngine.Result = runBlocking {
        val r = e.translate(TranslationEngine.Request(text, Languages.require(src), Languages.require(tgt), role, ModelCatalog.HY_MT2, context = context))
        println("${if (context.isEmpty()) "ohne" else "mit "} Kontext: $text  ⇒  ${r.text}   (${r.promptTokens} Prompt-Tokens, ${r.reusedTokens} aus dem Cache, ${r.wallMs} ms)")
        r
    }

    /** The sentence from the field test (Live, de → en): "Nachrichten" meant messages, not news. */
    @Test fun nachrichtenAreMessagesAfterTheEarlierSentence() {
        val earlier = "Modell ist sehr schnell, also ich merke."
        val sentence = "Aber das wenn ich eine kurze Pause mache, dass die Nachrichten getrennt werden."
        val earlierEn = t(engine, earlier, "de", "en").text
        val with = t(engine, sentence, "de", "en", listOf(ContextPair(earlier, earlierEn)))
        assertTrue(with.text, with.text.contains("message", ignoreCase = true))
        assertFalse(with.text, with.text.contains("news", ignoreCase = true))
        // for comparison (printed): the same sentence without context
        t(plain, sentence, "de", "en", listOf(ContextPair(earlier, earlierEn)))
    }

    @Test fun ambiguousWordsInBothDirections() {
        val cases = listOf(
            Triple("de" to "en", "Wir sind im Park spazieren gegangen und waren müde." to "Wir haben uns auf eine Bank gesetzt.", "\\bbench"),
            Triple("de" to "en", "Ich baue gerade das Regal zusammen." to "Mir fehlt noch eine Mutter für diese Schraube.", "\\bnuts?\\b"),
            Triple("en" to "de", "We had a wonderful dinner at the restaurant." to "Could you ask for the bill, please?", "Rechnung"),
            Triple("en" to "de", "My old piano needs to be repaired." to "One key is stuck.", "\\bTaste\\b"),
        )
        for ((dir, sentences, expected) in cases) {
            val (src, tgt) = dir
            val (earlier, sentence) = sentences
            val earlierTr = t(engine, earlier, src, tgt).text
            val r = t(engine, sentence, src, tgt, listOf(ContextPair(earlier, earlierTr)))
            assertTrue("$sentence ⇒ ${r.text}", Regex(expected, RegexOption.IGNORE_CASE).containsMatchIn(r.text))
            t(plain, sentence, src, tgt)
        }
    }

    @Test fun theContextIsNotTranslatedAgain() {
        val earlier = "Es regnet schon den ganzen Tag."
        val earlierEn = t(engine, earlier, "de", "en").text
        val r = t(engine, "Ich war mit meinen Kindern im Zoo.", "de", "en", listOf(ContextPair(earlier, earlierEn)))
        assertTrue(r.text, r.text.contains("zoo", ignoreCase = true))
        assertFalse(r.text, r.text.contains("rain", ignoreCase = true))
    }

    /** Live mode: after an utterance the next prompt's beginning is evaluated in advance. */
    @Test fun warmUpPutsTheNextPromptPrefixIntoTheCache() = runBlocking {
        val texts = listOf(
            "Ich habe heute die neue Chat-App installiert.",
            "Sie ist ziemlich schnell.",
            "Aber manchmal kommen die Nachrichten doppelt an.",
            "Das muss ich dem Entwickler schreiben.",
        )
        val pairs = mutableListOf<ContextPair>()
        for (s in texts.take(3)) {
            val r = t(engine, s, "de", "en", pairs.takeLast(2), Role.LIVE)
            pairs += ContextPair(s, r.text)
        }
        val ctx = pairs.takeLast(2)
        val next = texts[3]
        // without warm-up only the instruction is still in the cache (the context window moved on)
        val cold = t(engine, next, "de", "en", ctx, Role.LIVE)
        // the same state again, then warm up and translate
        t(engine, texts[2], "de", "en", pairs.take(2), Role.LIVE)
        engine.prepare(TranslationEngine.Request("", de, en, Role.LIVE, context = ctx))
        val warm = t(engine, next, "de", "en", ctx, Role.LIVE)
        println("aus dem Cache: ohne Vorwärmen ${cold.reusedTokens}, mit ${warm.reusedTokens} von ${warm.promptTokens} Prompt-Tokens")
        assertTrue("${warm.reusedTokens} vs ${cold.reusedTokens}", warm.reusedTokens >= cold.reusedTokens + 15)
        assertTrue(warm.text.isNotBlank())
    }
}
