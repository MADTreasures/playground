package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.LanguageGuesser
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Wav
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.asr.SherpaAsr
import ch.madtreasures.fluency.engine.asr.SherpaVad
import ch.madtreasures.fluency.engine.asr.WhisperCppAsr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** sherpa-onnx (Silero VAD, Parakeet, Whisper) and whisper.cpp on the x86-64 host, real models. */
class SpeechIntegrationTest {

    private fun wav(name: String): Wav.Audio = File(TestEnv.testWavs, name).inputStream().use { Wav.resample(Wav.read(it), SAMPLE_RATE) }

    private val clips = listOf("de", "en", "fr", "es")

    @Test fun parakeetRecognisesFourLanguages() {
        TestEnv.assumeSherpa()
        TestEnv.assumeFile(File(TestEnv.parakeetDir, "encoder.int8.onnx"))
        SherpaAsr.parakeet("p", "Parakeet", TestEnv.parakeetDir, threads = 4).use { asr ->
            for (lang in clips) {
                val audio = wav("$lang.wav")
                val r = asr.transcribe(audio.samples, null)
                println("Parakeet [$lang] ${"%.1f".format(audio.seconds)} s → ${r.millis} ms (RTF ${"%.3f".format(r.millis / 1000.0 / audio.seconds)}): ${r.text}")
                assertTrue(r.text.length > 10)
                // the transcript is in the spoken language (automatic language detection)
                assertEquals(lang, LanguageGuesser.guess(r.text, clips))
            }
        }
    }

    @Test fun sileroVadFindsUtterancesWithAbsolutePositions() {
        TestEnv.assumeSherpa()
        TestEnv.assumeFile(TestEnv.vadV5)
        val de = wav("de.wav").samples
        val en = wav("en.wav").samples
        val silence = FloatArray(SAMPLE_RATE)
        val stream = silence + de + silence + silence + en + silence
        SherpaVad(TestEnv.vadV5.path, minSilenceSeconds = 0.4f).use { vad ->
            val segs = mutableListOf<ch.madtreasures.fluency.engine.asr.SpeechChunk>()
            var i = 0
            while (i < stream.size) {
                val n = minOf(512, stream.size - i)
                vad.accept(stream.copyOfRange(i, i + n))
                segs += vad.drain()
                i += n
            }
            vad.flush()
            segs += vad.drain()
            segs.forEach { println("VAD segment: start ${"%.2f".format(it.start / 16000.0)} s, ${"%.2f".format(it.samples.size / 16000.0)} s") }
            assertTrue("found ${segs.size}", segs.size >= 2)
            // first segment starts near 1 s, the last one ends before the stream ends
            assertTrue(segs.first().start in 8_000L..40_000L)
            val enStart = (SAMPLE_RATE + de.size + 2 * SAMPLE_RATE).toLong()
            assertTrue(segs.any { it.start in (enStart - 16_000)..(enStart + 24_000) })
            assertTrue(segs.last().end <= stream.size)
        }
    }

    @Test fun whisperTurboThroughSherpa() {
        TestEnv.assumeSherpa()
        TestEnv.assumeFile(File(TestEnv.whisperDir, "turbo-encoder.int8.onnx"))
        TestEnv.assumeFile(File(TestEnv.whisperDir, "turbo-decoder.int8.onnx"))
        SherpaAsr.whisper("w", "Whisper", TestEnv.whisperDir, threads = 4).use { asr ->
            for (lang in listOf("de", "en")) {
                val audio = wav("$lang.wav")
                val r = asr.transcribe(audio.samples, Languages.require(lang))
                println("Whisper-turbo [$lang] → ${r.millis} ms: ${r.text}")
                assertEquals(lang, LanguageGuesser.guess(r.text, clips))
            }
            // automatic language detection
            val auto = asr.transcribe(wav("fr.wav").samples, null)
            println("Whisper-turbo [auto/fr] → ${auto.text}")
            assertEquals("fr", LanguageGuesser.guess(auto.text, clips))
        }
    }

    @Test fun swissGermanWhisperCpp() {
        TestEnv.assumeLlama()
        TestEnv.assumeFile(TestEnv.swissWhisper)
        val asr = WhisperCppAsr.load("sw", "Swiss", TestEnv.swissWhisper.path, threads = 4, fixedLanguage = "de")!!
        asr.use {
            val audio = wav("de.wav")
            val r = it.transcribe(audio.samples, Languages.require("de-CH"))
            println("Swiss Whisper (whisper.cpp) [de] ${"%.1f".format(audio.seconds)} s → ${r.millis} ms: ${r.text}")
            assertEquals("de", LanguageGuesser.guess(r.text, clips))
        }
    }
}
