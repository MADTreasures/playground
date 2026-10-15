package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AccelMode
import ch.madtreasures.fluency.engine.llm.ContextDocument
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.LlmSession
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.PromptFormat
import ch.madtreasures.fluency.engine.llm.SessionLoader
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ContextDocumentTest {
    private val doc = ContextDocument.parse(
        """
        Ich teste meine Übersetzungs-App auf dem Handy.

          Wir sprechen oft über Chat-Nachrichten.
        Nachrichten = messages
        Bank -> bench
        „Schloss“ → lock
        Die Formel ist einfach und kurz und passt in keine Begriffszeile, weil sie viel zu lang ist = nein
        """.trimIndent(),
    )

    @Test fun sentencesAndFixedTranslationsAreSeparated() {
        assertEquals(
            "Ich teste meine Übersetzungs-App auf dem Handy.\nWir sprechen oft über Chat-Nachrichten.\n" +
                "Die Formel ist einfach und kurz und passt in keine Begriffszeile, weil sie viel zu lang ist = nein",
            doc.sentences,
        )
        assertEquals(3, doc.sentenceCount)
        assertEquals(
            listOf(
                ContextDocument.Term("Nachrichten", "messages"),
                ContextDocument.Term("Bank", "bench"),
                ContextDocument.Term("Schloss", "lock"),
            ),
            doc.terms,
        )
    }

    @Test fun aFixedTranslationOnlyAppliesWhenItsWordOccurs() {
        val a = doc.forText("Die Nachrichten kommen nicht an.")
        assertEquals(listOf(ContextDocument.Term("Nachrichten", "messages")), a.terms)
        assertEquals(doc.sentences, a.sentences)
        assertTrue(doc.forText("Ich gehe einkaufen.").terms.isEmpty())
        // start of a word, any ending, any case
        assertEquals(1, ContextDocument.parse("Nachricht = message").forText("Hast du meine NACHRICHTEN gelesen?").terms.size)
        // not in the middle of a word
        assertTrue(doc.forText("Die Sitzbank ist nass.").terms.isEmpty())
    }

    @Test fun aFixedTranslationAlsoWorksTheOtherWayRound() {
        assertEquals(listOf(ContextDocument.Term("messages", "Nachrichten")), doc.forText("Did you read my messages?").terms)
    }

    @Test fun chineseWordsAreFoundWithoutSpaces() {
        val zh = ContextDocument.parse("消息 = Nachrichten")
        assertEquals(1, zh.forText("你看到我的消息了吗？").terms.size)
    }

    @Test fun emptyAndOverlongDocuments() {
        assertTrue(ContextDocument.parse("  \n\n ").isEmpty)
        assertTrue(ContextDocument.parse("").forText("Hallo").isEmpty)
        val long = ContextDocument.parse("x".repeat(ContextDocument.MAX_CHARS + 500))
        assertEquals(ContextDocument.MAX_CHARS, long.sentences.length)
        // only fixed translations: no system message
        val onlyTerms = ContextDocument.parse("Bank = bench")
        assertEquals(null, onlyTerms.forText("Die Bank").sentences)
    }
}

class PromptDocumentTest {
    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val zh = Languages.require("zh")
    private val applied = ContextDocument.Applied(
        "Ich teste meine Übersetzungs-App.",
        listOf(ContextDocument.Term("Nachrichten", "messages")),
    )

    @Test fun sentencesBecomeTheSystemMessageAndTermsTheTerminologyTemplate() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, en, "Die Nachrichten kommen nicht an.", applied)
        assertEquals(
            "<｜hy_begin▁of▁sentence｜>Ich teste meine Übersetzungs-App.<｜hy_place▁holder▁no▁3｜><｜hy_User｜>" +
                "Reference the following translations:\nNachrichten translates to messages\n\n" +
                "Translate the following text into English. Note that you must ONLY output the translated result " +
                "without any additional explanation:\n\nDie Nachrichten kommen nicht an.<｜hy_Assistant｜>",
            p.text,
        )
        assertTrue(p.stopAtNewline)
    }

    @Test fun onlySentencesKeepTheDefaultInstruction() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo", ContextDocument.Applied("Thema: App.", emptyList()))
        assertEquals(
            "<｜hy_begin▁of▁sentence｜>Thema: App.<｜hy_place▁holder▁no▁3｜><｜hy_User｜>Translate the following text into " +
                "English. Note that you should only output the translated result without any additional explanation:" +
                "\n\nHallo<｜hy_Assistant｜>",
            p.text,
        )
    }

    @Test fun chineseTerminologyTemplateAsInTheModelCard() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, zh, "Die Nachrichten", ContextDocument.Applied(null, applied.terms))
        assertTrue(p.text, p.text.contains("<｜hy_User｜>参考下面的翻译：\nNachrichten 翻译成 messages\n将以下文本翻译为中文，"))
    }

    @Test fun withoutDocumentThePromptIsUnchanged() {
        assertEquals(PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo"), PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo", null))
    }

    @Test fun theWarmUpPrefixStartsEveryPromptWithTheSameSentences() {
        val warm = PromptFormat.hyMtDocumentPrefix("Ich teste meine Übersetzungs-App.")
        for (text in listOf("Die", "Die Nachrichten kommen nicht an.")) {
            assertTrue(PromptFormat.build(PromptStyle.HY_MT, de, en, text, applied).text.startsWith(warm.text))
            assertTrue(PromptFormat.build(PromptStyle.HY_MT, de, en, text, applied.copy(terms = emptyList())).text.startsWith(warm.text))
        }
    }

    @Test fun onlyHyMtUsesTheDocument() {
        assertTrue(PromptFormat.supportsDocument(PromptStyle.HY_MT))
        assertFalse(PromptFormat.supportsDocument(PromptStyle.MILMMT))
        assertFalse(PromptFormat.supportsDocument(PromptStyle.CHAT_TEMPLATE))
    }
}

/** The engine with a recording fake model: where the document goes, switch, benchmark flag, warm-up. */
class EngineDocumentTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val de = Languages.require("de")
    private val en = Languages.require("en")

    @After fun tearDown() = scope.cancel()

    data class Call(val prompt: String, val maxTokens: Int)

    /** Answers " T<n>"; with [blankWithDocument] nothing when the prompt carries the document. */
    class RecordingSession(private val blankWithDocument: Boolean = false) : LlmSession {
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
            val text = if (blankWithDocument && "hy_place▁holder▁no▁3" in prompt) "" else " T${calls.size}"
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

    private val document = "Ich teste meine Übersetzungs-App.\nNachrichten = messages"

    private fun engine(session: RecordingSession, on: Boolean = true, model: String = ModelCatalog.HY_MT2) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = listOf(ModelCatalog.byId(model)!!)
            override fun modelPath(info: ModelInfo) = "/fake/${info.id}.gguf"
        },
        settings = { AppSettings(accel = AccelMode.CPU, translationContext = on, contextDocument = document) },
        nativeLibDir = null,
        loader = SessionLoader { _, _ -> session },
        scope = scope,
        initBackends = { "test" },
    )

    @Test fun theDocumentGoesIntoEveryHyMtPrompt() = runBlocking {
        val s = RecordingSession()
        val e = engine(s)
        assertTrue(e.documentActive())
        assertEquals("T1", e.translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.LIVE)).text)
        e.translate(TranslationEngine.Request("Ich gehe einkaufen.", de, en, Role.FINAL))
        val (first, second) = s.calls.map { it.prompt }
        assertTrue(first.startsWith("<｜hy_begin▁of▁sentence｜>Ich teste meine Übersetzungs-App.<｜hy_place▁holder▁no▁3｜>"))
        assertTrue(first.contains("Nachrichten translates to messages"))
        // the fixed translation only where its word occurs; the sentences always
        assertTrue(second.startsWith("<｜hy_begin▁of▁sentence｜>Ich teste meine Übersetzungs-App.<｜hy_place▁holder▁no▁3｜>"))
        assertFalse(second.contains("Reference the following translations"))
    }

    @Test fun switchedOffOrLeftOutNoDocument() = runBlocking {
        val off = RecordingSession()
        val e = engine(off, on = false)
        assertFalse(e.documentActive())
        e.translate(TranslationEngine.Request("Die Nachrichten", de, en, Role.LIVE))
        assertFalse(off.calls.single().prompt.contains("hy_place▁holder▁no▁3"))
        // the benchmark and the CPU/GPU/NPU comparison leave it out
        val s = RecordingSession()
        engine(s).translate(TranslationEngine.Request("Die Nachrichten", de, en, Role.TEXT, useDocument = false))
        assertTrue(s.calls.single().prompt.startsWith("<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate"))
    }

    @Test fun miLmmtTranslatesWithoutTheDocument() = runBlocking {
        val s = RecordingSession()
        engine(s, model = ModelCatalog.MILMMT_1B).translate(TranslationEngine.Request("Die Nachrichten", de, en, Role.TEXT))
        assertEquals("Translate this from German to English:\nGerman: Die Nachrichten\nEnglish:", s.calls.single().prompt)
    }

    @Test fun anEmptyAnswerWithTheDocumentIsRepeatedWithoutIt() = runBlocking {
        val s = RecordingSession(blankWithDocument = true)
        val r = engine(s).translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.FINAL))
        assertEquals(2, s.calls.size)
        assertFalse(s.calls[1].prompt.contains("hy_place▁holder▁no▁3"))
        assertEquals("T2", r.text)
    }

    @Test fun preloadEvaluatesTheSentencesOnce() = runBlocking {
        val s = RecordingSession()
        val e = engine(s)
        e.preload(setOf(ModelCatalog.HY_MT2))
        val warm = s.calls.single()
        assertEquals(0, warm.maxTokens)
        e.translate(TranslationEngine.Request("Die Nachrichten kommen nicht an.", de, en, Role.LIVE))
        assertTrue(s.calls[1].prompt.startsWith(warm.prompt))
    }
}
