package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.ContextPair
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.LlmSession
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.PromptFormat
import ch.madtreasures.fluency.engine.llm.SessionLoader
import ch.madtreasures.fluency.engine.llm.TranslationContext
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.pipeline.ConversationContext
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TranslationContextTest {
    private fun pair(i: Int) = ContextPair("Satz $i.", "Sentence $i.")

    @Test fun keepsTheTwoMostRecentPairsOldestFirst() {
        assertEquals(listOf(pair(2), pair(3)), TranslationContext.select(listOf(pair(1), pair(2), pair(3))))
        assertEquals(listOf(pair(1)), TranslationContext.select(listOf(pair(1))))
        assertTrue(TranslationContext.select(emptyList()).isEmpty())
    }

    @Test fun staysWithinTheCharacterLimit() {
        val long = ContextPair("x".repeat(TranslationContext.MAX_CHARS - 5), "y")
        // the most recent sentence fits, the older long one would exceed the limit
        assertEquals(listOf(pair(2)), TranslationContext.select(listOf(long, pair(2))))
        // a single sentence above the limit is left out
        assertTrue(TranslationContext.select(listOf(ContextPair("x".repeat(TranslationContext.MAX_CHARS + 1), "y"))).isEmpty())
    }

    @Test fun skipsEmptyPairsAndFlattensLines() {
        val sel = TranslationContext.select(listOf(ContextPair("a\nb", "c\nd"), ContextPair(" ", "x"), ContextPair("e", "")))
        assertEquals(listOf(ContextPair("a b", "c d")), sel)
    }

    @Test fun joinsSentencesWithSpacesExceptChineseAndJapanese() {
        val c = TranslationContext.join(listOf(pair(1), pair(2)))!!
        assertEquals("Satz 1. Satz 2.", c.source)
        assertEquals("Sentence 1. Sentence 2.", c.translation)
        assertEquals("你好。谢谢。", TranslationContext.joinSentences("你好。", "谢谢。"))
        assertEquals("こんにちは。ありがとう。", TranslationContext.joinSentences("こんにちは。", "ありがとう。"))
        assertNull(TranslationContext.join(emptyList()))
    }

    @Test fun detectsAnOutputThatDidNotContinue() {
        val c = PromptFormat.Context("Ich habe dir geschrieben.", "I wrote to you yesterday evening.")
        assertTrue(TranslationContext.failed("", c))
        assertTrue(TranslationContext.failed("  ", c))
        assertTrue(TranslationContext.failed("I wrote to you yesterday evening.", c))
        assertTrue(TranslationContext.failed("I wrote to you yesterday evening. Did you read my messages?", c))
        assertFalse(TranslationContext.failed("Did you read my messages?", c))
        // a short earlier sentence may start the next one
        assertFalse(TranslationContext.failed("Yes, I will come.", PromptFormat.Context("Ja.", "Yes.")))
    }

    @Test fun swissGermanAndGermanAreTheSameLanguageForContext() {
        val de = Languages.require("de")
        val ch = Languages.require("de-CH")
        val en = Languages.require("en")
        assertTrue(TranslationContext.sameLanguage(de, ch))
        assertFalse(TranslationContext.sameLanguage(de, en))
        assertFalse(TranslationContext.sameLanguage(null, en))
    }
}

class PromptContextTest {
    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val zh = Languages.require("zh")
    private val ctx = PromptFormat.Context("Ich schreibe meinem Bruder über die App.", "I'm writing to my brother through the app.")

    @Test fun hyMtPutsTheEarlierSentenceFirstAndItsTranslationIntoTheAnswer() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, en, "Die Nachrichten kommen nicht an.", ctx)
        assertEquals(
            "<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following text into English. Note that you should only " +
                "output the translated result without any additional explanation:\n\n" +
                "Ich schreibe meinem Bruder über die App. Die Nachrichten kommen nicht an.<｜hy_Assistant｜>" +
                "I'm writing to my brother through the app.",
            p.text,
        )
        assertTrue(p.stopAtNewline)
    }

    @Test fun milmmtContinuesAfterTheEarlierTranslation() {
        val p = PromptFormat.build(PromptStyle.MILMMT, de, en, "Die Nachrichten kommen nicht an.", ctx)
        assertEquals(
            "Translate this from German to English:\nGerman: Ich schreibe meinem Bruder über die App. Die Nachrichten kommen nicht an.\n" +
                "English: I'm writing to my brother through the app.",
            p.text,
        )
    }

    @Test fun withoutContextThePromptIsUnchanged() {
        assertEquals(
            PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo"),
            PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo", null),
        )
    }

    @Test fun warmUpPromptIsThePrefixOfEveryNextPrompt() {
        for (style in listOf(PromptStyle.HY_MT, PromptStyle.MILMMT)) {
            for ((src, tgt) in listOf(de to en, de to zh)) {
                val warm = PromptFormat.warmUpPrompt(style, src, tgt, ctx)!!
                assertTrue(warm.text.endsWith(ctx.source))
                for (text in listOf("Die", "Die Nachrichten kommen nicht an.")) {
                    val full = PromptFormat.build(style, src, tgt, text, ctx)
                    assertTrue("$style $tgt: ${warm.text}", full.text.startsWith(warm.text))
                    assertEquals(full.addSpecial, warm.addSpecial)
                }
            }
        }
    }

    @Test fun onlyMeasuredModelFamiliesTakeContext() {
        assertTrue(PromptFormat.supportsContext(PromptStyle.HY_MT))
        assertTrue(PromptFormat.supportsContext(PromptStyle.MILMMT))
        assertFalse(PromptFormat.supportsContext(PromptStyle.CHAT_TEMPLATE))
        assertNull(PromptFormat.warmUpPrompt(PromptStyle.CHAT_TEMPLATE, de, en, ctx))
        // MiLMMT needs the source language
        assertNull(PromptFormat.warmUpPrompt(PromptStyle.MILMMT, null, en, ctx))
    }
}

class ConversationContextTest {
    private val de = Languages.require("de")
    private val ch = Languages.require("de-CH")
    private val en = Languages.require("en")
    private val fr = Languages.require("fr")

    private fun u(id: Long, src: String, tr: String, source: ch.madtreasures.fluency.core.Language? = de, target: ch.madtreasures.fluency.core.Language = en, final: Boolean = true, error: String? = null, speaker: Int = 0) =
        Utterance(id, speaker, source, target, src, tr, final, error = error)

    @Test fun earlierFinishedUtterancesOfTheSameDirection() {
        val list = listOf(
            u(1, "Eins.", "One."),
            u(2, "Zwei.", "Two."),
            u(3, "Drei.", "Three.", error = "Fehler"),
            u(4, "Vier.", "Four."),
            u(5, "Fünf", "Five", final = false),
            u(6, "Sechs.", "Six."),
        )
        assertEquals(
            listOf(ContextPair("Zwei.", "Two."), ContextPair("Vier.", "Four.")),
            ConversationContext.pairsFor(list, currentId = 5, source = de, target = en),
        )
        assertTrue(ConversationContext.pairsFor(list, currentId = 1, source = de, target = en).isEmpty())
    }

    @Test fun theOtherPersonsUtteranceIsUsedTheOtherWayRound() {
        val list = listOf(
            u(1, "Hast du meine Nachrichten gelesen?", "Did you read my messages?", source = ch, target = en, speaker = 0),
            u(2, "Yes, I read them this morning.", "Ja, ich habe sie heute Morgen gelesen.", source = en, target = ch, speaker = 1),
        )
        // person A (Swiss German → English) next: B's English words are the "translation"
        assertEquals(
            listOf(
                ContextPair("Hast du meine Nachrichten gelesen?", "Did you read my messages?"),
                ContextPair("Ja, ich habe sie heute Morgen gelesen.", "Yes, I read them this morning."),
            ),
            ConversationContext.pairsFor(list, currentId = 3, source = ch, target = en),
        )
        // person B (English → Swiss German) next
        assertEquals(
            listOf(
                ContextPair("Did you read my messages?", "Hast du meine Nachrichten gelesen?"),
                ContextPair("Yes, I read them this morning.", "Ja, ich habe sie heute Morgen gelesen."),
            ),
            ConversationContext.pairsFor(list, currentId = 3, source = en, target = ch),
        )
    }

    @Test fun otherLanguagesAreNoContext() {
        val list = listOf(u(1, "Bonjour.", "Hallo.", source = fr, target = de), u(2, "Eins.", "One."))
        assertEquals(listOf(ContextPair("Eins.", "One.")), ConversationContext.pairsFor(list, 3, de, en))
        // detected source language: every utterance into the same target counts
        assertEquals(listOf(ContextPair("Eins.", "One.")), ConversationContext.pairsFor(list, 3, null, en))
        // French → German the other way round is German → French
        assertEquals(listOf(ContextPair("Hallo.", "Bonjour.")), ConversationContext.pairsFor(list, 3, de, fr))
        assertTrue(ConversationContext.pairsFor(list, 3, de, Languages.require("it")).isEmpty())
    }
}

/** The engine with a recording fake model: where the context goes, fallback, texts, warm-up. */
class EngineContextTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val de = Languages.require("de")
    private val en = Languages.require("en")

    @After fun tearDown() = scope.cancel()

    data class Call(val prompt: String, val maxTokens: Int)

    /** Answers "T<n>"; with [blankWithContext] an empty answer whenever the prompt carries a context. */
    class RecordingSession(private val blankWithContext: Boolean = false) : LlmSession {
        val calls = CopyOnWriteArrayList<Call>()
        override val description = "rec"
        override val loadMillis = 1L
        override val processor = Processor.CPU
        override var isClosed = false

        override fun generate(
            prompt: String,
            addSpecial: Boolean,
            maxTokens: Int,
            repeatPenalty: Float,
            stopAtNewline: Boolean,
            onText: ((String) -> Boolean)?,
        ): LlamaModel.Result {
            calls += Call(prompt, maxTokens)
            if (maxTokens == 0) return LlamaModel.Result("", 30, 0, 0, 1.0, 0.0, LlamaModel.StopReason.MAX_TOKENS)
            val answerPart = prompt.substringAfter("<｜hy_Assistant｜>")
            val text = if (blankWithContext && answerPart.isNotEmpty()) "" else " T${calls.size}"
            onText?.invoke(text)
            return LlamaModel.Result(text, 30, 0, 3, 1.0, 1.0, LlamaModel.StopReason.EOG)
        }

        override fun cancel() {}
        override fun resetCache() {}
        override fun applyChatTemplate(user: String): String? = null
        override fun close() {
            isClosed = true
        }
    }

    private fun engine(session: RecordingSession, context: Boolean = true) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = listOf(ModelCatalog.byId(ModelCatalog.HY_MT2)!!)
            override fun modelPath(info: ModelInfo) = "/fake/${info.id}.gguf"
        },
        settings = { AppSettings(accel = ch.madtreasures.fluency.engine.llm.AccelMode.CPU, translationContext = context) },
        nativeLibDir = null,
        loader = SessionLoader { _, _ -> session },
        scope = scope,
        initBackends = { "test" },
    )

    private val earlier = listOf(ContextPair("Ich schreibe meinem Bruder.", "I'm writing to my brother."))

    @Test fun contextGoesInFrontOfTheTextAndIntoTheAnswer() = runBlocking {
        val s = RecordingSession()
        val r = engine(s).translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.LIVE, context = earlier))
        assertEquals("T1", r.text)
        val prompt = s.calls.single().prompt
        assertTrue(prompt.contains("\n\nIch schreibe meinem Bruder. Die Nachrichten kommen nicht an.<｜hy_Assistant｜>"))
        assertTrue(prompt.endsWith("<｜hy_Assistant｜>I'm writing to my brother."))
    }

    @Test fun contextCanBeSwitchedOff() = runBlocking {
        val s = RecordingSession()
        engine(s, context = false).translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.LIVE, context = earlier))
        assertTrue(s.calls.single().prompt.endsWith("\n\nDie Nachrichten kommen nicht an.<｜hy_Assistant｜>"))
    }

    @Test fun anEmptyAnswerWithContextIsRepeatedWithoutIt() = runBlocking {
        val s = RecordingSession(blankWithContext = true)
        val r = engine(s).translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.FINAL, context = earlier))
        assertEquals(2, s.calls.size)
        assertTrue(s.calls[1].prompt.endsWith("<｜hy_Assistant｜>"))
        assertEquals("T2", r.text)
    }

    @Test fun pieceOfATextIsTranslatedWithThePreviousPiece() = runBlocking {
        val s = RecordingSession()
        val r = engine(s).translate(TranslationEngine.Request("Erster Absatz.\nZweiter Absatz.", de, en, Role.TEXT))
        assertEquals("T1\nT2", r.text)
        assertTrue(s.calls[0].prompt.endsWith("\n\nErster Absatz.<｜hy_Assistant｜>"))
        assertTrue(s.calls[1].prompt.endsWith("\n\nErster Absatz. Zweiter Absatz.<｜hy_Assistant｜>T1"))
    }

    @Test fun prepareWarmsUpTheLoadedModelWithThePromptPrefix() = runBlocking {
        val s = RecordingSession()
        val e = engine(s)
        // nothing loaded yet: nothing to warm up
        e.prepare(TranslationEngine.Request("", de, en, Role.LIVE, context = earlier))
        delay(50)
        assertTrue(s.calls.isEmpty())

        e.translate(TranslationEngine.Request("Hallo.", de, en, Role.LIVE))
        e.prepare(TranslationEngine.Request("", de, en, Role.LIVE, context = earlier))
        withTimeout(5_000) { while (s.calls.size < 2) delay(10) }
        val warm = s.calls[1]
        assertEquals(0, warm.maxTokens)
        e.translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.LIVE, context = earlier))
        assertTrue(s.calls[2].prompt.startsWith(warm.prompt))
        assertTrue(warm.prompt.endsWith("Ich schreibe meinem Bruder."))

        // no context, or context switched off: no warm-up
        e.prepare(TranslationEngine.Request("", de, en, Role.LIVE))
        val off = RecordingSession()
        val e2 = engine(off, context = false)
        e2.translate(TranslationEngine.Request("Hallo.", de, en, Role.LIVE))
        e2.prepare(TranslationEngine.Request("", de, en, Role.LIVE, context = earlier))
        delay(100)
        assertEquals(3, s.calls.size)
        assertEquals(1, off.calls.size)
    }
}
