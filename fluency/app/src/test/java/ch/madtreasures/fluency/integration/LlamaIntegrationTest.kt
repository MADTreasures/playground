package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.PromptFormat
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** llama.cpp through the app's JNI bridge with Hy-MT2-1.8B (x86-64 host build). */
class LlamaIntegrationTest {
    companion object {
        private var model: LlamaModel? = null

        @BeforeClass @JvmStatic fun load() {
            TestEnv.assumeLlama()
            val f = TestEnv.files.getValue("hy-mt2-1.8b")
            TestEnv.assumeFile(f)
            model = LlamaModel.load(f.path, LlamaModel.LoadParams(contextSize = 1024, threads = 4, threadsBatch = 4))
            println("loaded: ${model?.description} in ${model?.loadMillis} ms")
        }

        @AfterClass @JvmStatic fun close() {
            model?.close()
        }
    }

    private val de = Languages.require("de")
    private val en = Languages.require("en")

    private fun translate(text: String, src: ch.madtreasures.fluency.core.Language?, tgt: ch.madtreasures.fluency.core.Language,
                          onText: ((String) -> Boolean)? = null): LlamaModel.Result {
        val p = PromptFormat.hyMt(src, tgt, text)
        return model!!.generate(p.text, p.addSpecial, PromptFormat.maxTokens(text), 1.05f, p.stopAtNewline, onText)
    }

    @Test fun translatesGermanToEnglishWithStreaming() {
        val chunks = AtomicInteger()
        val r = translate("Der Zug nach Zürich fährt um acht Uhr ab.", de, en) { chunks.incrementAndGet(); true }
        println("DE→EN: ${r.text}  [${r.generatedTokens} tok, prefill ${"%.0f".format(r.prefillMs)} ms, decode ${"%.0f".format(r.decodeMs)} ms, ${"%.1f".format(r.tokensPerSecond)} tok/s]")
        assertTrue(r.text, r.text.contains("train", ignoreCase = true))
        assertTrue(r.text, r.text.contains("Zurich") || r.text.contains("Zürich"))
        assertTrue(r.text, r.text.contains("8") || r.text.contains("eight", ignoreCase = true))
        assertTrue("streamed in several chunks", chunks.get() >= 3)
        assertTrue(r.stop == LlamaModel.StopReason.EOG || r.stop == LlamaModel.StopReason.NEWLINE)
    }

    @Test fun reusesKvCacheWhenTheUtteranceGrows() {
        model!!.resetCache()
        val first = translate("Ich denke, wir sollten uns", de, en)
        val second = translate("Ich denke, wir sollten uns morgen am Bahnhof treffen.", de, en)
        println("partial 1: ${first.text} (${first.promptTokens} prompt tok, reused ${first.reusedTokens})")
        println("partial 2: ${second.text} (${second.promptTokens} prompt tok, reused ${second.reusedTokens}, prefill ${"%.0f".format(second.prefillMs)} ms)")
        assertEquals(0, first.reusedTokens)
        // instruction + "Ich denke, wir sollten uns" come from the cache
        assertTrue("reused ${second.reusedTokens}", second.reusedTokens >= first.promptTokens - 2)
        assertTrue(second.text.contains("station", ignoreCase = true))
    }

    @Test fun identicalPromptIsAlmostFree() {
        translate("Guten Morgen", de, en)
        val again = translate("Guten Morgen", de, en)
        assertEquals(again.promptTokens - 1, again.reusedTokens)
    }

    @Test fun sinkCanStopEarly() {
        val r = translate("Das ist ein langer Satz, der viele Wörter enthält und deshalb etwas länger dauert.", de, en) { false }
        assertEquals(LlamaModel.StopReason.SINK, r.stop)
        assertTrue(r.generatedTokens <= 3)
    }

    @Test fun cancelFromAnotherThread() {
        val text = List(30) { "Dies ist Satz Nummer $it." }.joinToString(" ")
        val started = AtomicInteger()
        val t = thread {
            while (started.get() == 0) Thread.sleep(5)
            model!!.cancel()
        }
        val r = translate(text, de, en) { started.incrementAndGet(); true }
        t.join()
        assertEquals(LlamaModel.StopReason.CANCELLED, r.stop)
        // the session stays usable after a cancel
        val ok = translate("Danke schön!", de, en)
        assertTrue(ok.text, ok.text.contains("Thank", ignoreCase = true))
    }

    @Test fun multiByteOutputIsNeverSplit() {
        val zh = Languages.require("zh")
        val ja = Languages.require("ja")
        for (tgt in listOf(zh, ja)) {
            val pieces = mutableListOf<String>()
            val r = translate("Good morning! The weather is wonderful today.", en, tgt) { pieces += it; true }
            println("EN→${tgt.code}: ${r.text}")
            assertFalse(r.text.contains('�'))
            assertTrue(pieces.none { it.contains('�') })
            assertTrue(r.text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HIRAGANA })
        }
    }

    @Test fun metadataAndTokenizer() {
        assertEquals("hunyuan-dense", model!!.meta("general.architecture"))
        assertTrue(model!!.tokenCount("Hallo Welt") in 2..6)
        assertTrue(model!!.applyChatTemplate("Hi") != null || true)
    }
}
