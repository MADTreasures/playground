package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Wav
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.asr.SherpaAsr
import ch.madtreasures.fluency.engine.asr.SherpaVad
import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.engine.tts.PiperSpeaker
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelDownloader
import ch.madtreasures.fluency.models.ModelStore
import ch.madtreasures.fluency.pipeline.EngineTranslator
import ch.madtreasures.fluency.pipeline.LivePipeline
import ch.madtreasures.fluency.pipeline.PipelineState
import ch.madtreasures.fluency.pipeline.SileroDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/**
 * The whole chain on the build host with real models:
 * real download (HF, pinned URL, SHA-256) → Piper TTS → microphone stand-in → Silero VAD →
 * Parakeet → Hy-MT2 → final translation with latency.
 */
class EndToEndTest {

    /** Plays audio in real time like a microphone. */
    private class PlaybackSource(private val audio: FloatArray, private val realTime: Boolean = true) : AudioSource {
        @Volatile private var running = false
        override fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit) {
            running = true
            thread(name = "playback") {
                var i = 0
                val t0 = System.nanoTime()
                while (running && i < audio.size) {
                    val n = minOf(512, audio.size - i)
                    onChunk(audio.copyOfRange(i, i + n).let { if (it.size < 512) it + FloatArray(512 - it.size) else it })
                    i += n
                    if (realTime) {
                        val due = t0 + i * 1_000_000_000L / SAMPLE_RATE
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
            }
        }

        override fun stop() {
            running = false
        }
    }

    @Test fun realDownloadOfSmallModelsWithChecksum() = runBlocking {
        val store = ModelStore(TestEnv.downloads)
        val downloader = ModelDownloader(store)
        for (id in listOf(ModelCatalog.SILERO_VAD, ModelCatalog.ESPEAK_DATA, "piper-de-thorsten")) {
            val m = ModelCatalog.byId(id)!!
            if (store.isInstalled(m)) continue
            val t0 = System.nanoTime()
            downloader.download(m) {}
            println("downloaded ${m.name}: ${m.files.size} files, ${m.totalBytes / 1_000_000} MB in ${(System.nanoTime() - t0) / 1_000_000} ms (SHA-256 ok)")
        }
        assertTrue(store.isInstalled(ModelCatalog.byId(ModelCatalog.SILERO_VAD)!!))
        assertTrue(store.isInstalled(ModelCatalog.byId("piper-de-thorsten")!!))
    }

    @Test fun piperSpeaksGermanAndParakeetUnderstandsIt() {
        TestEnv.assumeSherpa()
        val store = ModelStore(TestEnv.downloads)
        val voice = ModelCatalog.byId("piper-de-thorsten")!!
        org.junit.Assume.assumeTrue("run realDownloadOfSmallModelsWithChecksum first", store.isInstalled(voice))
        TestEnv.assumeFile(File(TestEnv.parakeetDir, "encoder.int8.onnx"))
        val piper = PiperSpeaker(
            voices = { mapOf("de" to store.dir(voice.id)) },
            espeakDataDir = { File(store.dir(ModelCatalog.ESPEAK_DATA), "espeak-ng-data") },
            rate = { 1.0f },
        )
        val text = "Guten Morgen! Wie komme ich zum Bahnhof?"
        val t0 = System.nanoTime()
        val (samples, rate) = piper.synthesize(text, Languages.require("de"))!!
        val synthMs = (System.nanoTime() - t0) / 1_000_000
        val audio = Wav.resample(Wav.Audio(samples, rate), SAMPLE_RATE)
        println("Piper: ${"%.1f".format(audio.seconds)} s Audio in $synthMs ms (${rate} Hz)")
        assertTrue(audio.seconds > 1.0)
        SherpaAsr.parakeet("p", "Parakeet", TestEnv.parakeetDir, 4).use { asr ->
            val r = asr.transcribe(audio.samples, null)
            println("Parakeet hears: ${r.text}")
            assertTrue(r.text, r.text.contains("Morgen", ignoreCase = true))
            assertTrue(r.text, r.text.contains("Bahnhof", ignoreCase = true))
        }
        piper.release()
    }

    @Test fun livePipelineFromAudioToEnglish() = runBlocking {
        TestEnv.assumeSherpa()
        TestEnv.assumeLlama()
        TestEnv.assumeFile(File(TestEnv.parakeetDir, "encoder.int8.onnx"))
        TestEnv.assumeFile(TestEnv.vadV5)
        TestEnv.assumeFile(TestEnv.files.getValue(ModelCatalog.HY_MT2))

        val de = File(TestEnv.testWavs, "de.wav").inputStream().use { Wav.resample(Wav.read(it), SAMPLE_RATE) }
        val audio = FloatArray(SAMPLE_RATE / 2) + de.samples + FloatArray(SAMPLE_RATE * 2)
        val engine = TestEnv.engine()
        engine.preload(listOf(ModelCatalog.HY_MT2))
        val asr = SherpaAsr.parakeet("p", "Parakeet", TestEnv.parakeetDir, 2)
        val state = MutableStateFlow(PipelineState())
        val pipeline = LivePipeline(
            config = LivePipeline.Config(source = Languages.require("de"), target = Languages.require("en"), partialIntervalMs = 400),
            audio = PlaybackSource(audio),
            vad = SileroDetector(SherpaVad(TestEnv.vadV5.path, minSilenceSeconds = 0.4f)),
            asr = asr,
            translator = EngineTranslator(engine),
            speaker = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            state = state,
        )
        val partials = mutableListOf<String>()
        val watcher = thread {
            var last = ""
            while (!Thread.currentThread().isInterrupted) {
                val u = state.value.utterances.lastOrNull()
                if (u != null && !u.final && u.translation != last) { last = u.translation; synchronized(partials) { partials += "${u.sourceText} ⇒ ${u.translation}" } }
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }
        pipeline.start()
        withTimeout(60_000) {
            while (state.value.utterances.none { it.final }) delay(50)
        }
        pipeline.stop()
        watcher.interrupt()
        watcher.join()
        synchronized(partials) { partials.forEach { println("partial: $it") } }
        val u = state.value.utterances.first { it.final }
        println("FINAL: ${u.sourceText} ⇒ ${u.translation}")
        println("latency: ${u.latency?.format()}")
        assertEquals(1, state.value.utterances.size)
        assertTrue(u.sourceText.length > 10)
        assertTrue(u.translation.isNotBlank())
        assertTrue(u.latency != null && u.latency!!.totalMs > 0)
        asr.close()
        engine.unloadAll()
    }
}
