package ch.madtreasures.fluency.pipeline

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.asr.AsrEngine
import ch.madtreasures.fluency.engine.asr.AsrResult
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.asr.SpeechChunk
import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.math.sqrt

/** Pipeline logic with fakes for audio, VAD, ASR and translation (the real engines are tested in integration/). */
class LivePipelineTest {

    /** Plays a script of (seconds, isSpeech) and drives the fake clock from the number of samples. */
    private class ScriptedAudio(val script: List<Pair<Double, Boolean>>, val samplesOut: AtomicLong) : AudioSource {
        @Volatile var running = false
        override fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit) {
            running = true
            thread {
                var t = 0
                for ((sec, speech) in script) {
                    val n = (sec * SAMPLE_RATE).toInt() / 512
                    repeat(n) {
                        if (!running) return@thread
                        val chunk = FloatArray(512) { i -> if (speech) (0.5 * sin((t + i) * 0.05)).toFloat() else 0f }
                        t += 512
                        samplesOut.addAndGet(512)
                        onChunk(chunk)
                        Thread.sleep(2)
                    }
                }
            }
        }

        override fun stop() {
            running = false
        }
    }

    /** Energy VAD with a minimum silence, emitting segments like Silero does. */
    private class EnergyVad(val minSilence: Int = SAMPLE_RATE * 3 / 10) : VoiceActivityDetector {
        private var pos = 0L
        private var inSpeech = false
        private var segStart = 0L
        private var silence = 0
        private val buf = ArrayList<Float>()
        private val done = ArrayDeque<SpeechChunk>()

        override fun accept(samples: FloatArray) {
            val rms = sqrt(samples.map { it * it }.average())
            if (rms > 0.05) {
                if (!inSpeech) { inSpeech = true; segStart = pos; buf.clear() }
                silence = 0
            } else if (inSpeech) {
                silence += samples.size
            }
            if (inSpeech) samples.forEach { buf += it }
            pos += samples.size
            if (inSpeech && silence >= minSilence) {
                done += SpeechChunk(segStart, buf.toFloatArray())
                inSpeech = false
            }
        }

        override fun isSpeech() = inSpeech && silence == 0
        override fun drain(): List<SpeechChunk> = done.toList().also { done.clear() }
        override fun flush() {
            if (inSpeech) { done += SpeechChunk(segStart, buf.toFloatArray()); inSpeech = false }
        }
        override fun close() {}
    }

    /** One word per started 0.5 s of audio: partial results grow while speaking. */
    private class FakeAsr : AsrEngine {
        override val id = "asr"
        override val name = "Fake ASR"
        override val supportsFastPartials = true
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        override fun transcribe(samples: FloatArray, language: Language?): AsrResult {
            calls += samples.size
            Thread.sleep(5)
            val words = (samples.size + SAMPLE_RATE / 2 - 1) / (SAMPLE_RATE / 2)
            return AsrResult((1..words).joinToString(" ") { "wort$it" }, 5)
        }
        override fun close() {}
    }

    private class FakeTranslator : Translator {
        val requests = Collections.synchronizedList(mutableListOf<TranslationEngine.Request>())
        override fun modelFor(role: TranslationEngine.Role, source: Language?, target: Language) = "m"
        override suspend fun translate(req: TranslationEngine.Request, onPartial: ((String) -> Unit)?): TranslationEngine.Result {
            requests += req
            onPartial?.invoke(req.text.take(3).uppercase())
            delay(15)
            return TranslationEngine.Result(req.text.uppercase(), "m", "M", req.source, 15, 10, 0, 5, 1.0, 10.0, false)
        }
        override fun cancel(role: TranslationEngine.Role?) {}
    }

    private class FakeSpeaker : Speaker {
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun speak(text: String, language: Language) { spoken += text }
        override fun stop() {}
        override fun canSpeak(language: Language) = true
    }

    private fun run(
        script: List<Pair<Double, Boolean>>,
        speak: Boolean = false,
        autoStop: Long? = null,
        waitForAutoStop: Boolean = false,
    ): Triple<PipelineState, FakeTranslator, FakeSpeaker> = runBlocking {
        val samples = AtomicLong(0)
        val state = MutableStateFlow(PipelineState())
        val translator = FakeTranslator()
        val speaker = FakeSpeaker()
        val audio = ScriptedAudio(script, samples)
        val p = LivePipeline(
            config = LivePipeline.Config(
                source = Languages.require("de"), target = Languages.require("en"),
                partialIntervalMs = 300, speak = speak, autoStopSilenceMs = autoStop,
            ),
            audio = audio,
            vad = EnergyVad(),
            asr = FakeAsr(),
            translator = translator,
            speaker = speaker,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            state = state,
            clock = { samples.get() * 1_000_000_000L / SAMPLE_RATE },
        )
        p.start()
        val total = script.sumOf { it.first }
        withTimeout(30_000) {
            if (waitForAutoStop) {
                while (state.value.running) delay(20)
            } else {
                // let the script play (2 ms per 32 ms chunk), then stop
                delay((total * 1000 / 16).toLong() + 300)
                p.stop()
            }
        }
        delay(100)
        Triple(state.value, translator, speaker)
    }

    @Test fun oneUtteranceGetsPartialAndFinalTranslation() {
        val (st, tr, _) = run(listOf(0.5 to false, 2.0 to true, 1.0 to false))
        assertFalse(st.running)
        val u = st.utterances.single()
        assertTrue(u.final)
        assertTrue(u.sourceText.startsWith("wort1 wort2 wort3"))
        assertEquals(u.sourceText.uppercase(), u.translation)
        val latency = u.latency!!
        assertNotNull(latency.asrMs)
        assertTrue("partials translated with the live role", tr.requests.any { it.role == TranslationEngine.Role.LIVE })
        val finals = tr.requests.filter { it.role == TranslationEngine.Role.FINAL }
        // either the finished partial was reused or a final translation ran - never both
        assertEquals(latency.reusedPartial, finals.isEmpty())
    }

    @Test fun utterancesStayInOrder() {
        val (st, _, _) = run(listOf(0.3 to false, 1.2 to true, 0.8 to false, 2.2 to true, 0.8 to false))
        assertEquals(2, st.utterances.size)
        assertTrue(st.utterances.all { it.final })
        assertTrue(st.utterances[0].sourceText.split(' ').size < st.utterances[1].sourceText.split(' ').size)
        assertTrue(st.utterances[0].id < st.utterances[1].id)
    }

    @Test fun speechStillRunningAtStopIsFinalised() {
        val (st, _, _) = run(listOf(0.3 to false, 1.5 to true))
        val u = st.utterances.single()
        assertTrue(u.final)
        assertTrue(u.translation.isNotBlank())
    }

    @Test fun silenceProducesNothing() {
        val (st, tr, _) = run(listOf(1.5 to false))
        assertTrue(st.utterances.isEmpty())
        assertTrue(tr.requests.isEmpty())
    }

    @Test fun finishedTranslationsAreSpoken() {
        val (st, _, sp) = run(listOf(0.3 to false, 1.0 to true, 0.8 to false), speak = true)
        assertEquals(listOf(st.utterances.single().translation), sp.spoken)
    }

    @Test fun conversationModeStopsByItselfAfterSilence() {
        val (st, _, _) = run(listOf(0.3 to false, 1.0 to true, 4.0 to false), autoStop = 1_000, waitForAutoStop = true)
        assertFalse(st.running)
        assertTrue(st.utterances.single().final)
    }
}
