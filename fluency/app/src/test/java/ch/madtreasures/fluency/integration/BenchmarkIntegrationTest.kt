package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.bench.BenchReport
import ch.madtreasures.fluency.bench.Benchmark
import ch.madtreasures.fluency.core.Wav
import ch.madtreasures.fluency.engine.asr.AsrEngine
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.asr.SherpaAsr
import ch.madtreasures.fluency.models.AsrType
import ch.madtreasures.fluency.models.ModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the app's benchmark (same code as the Benchmark screen) on the build host. */
class BenchmarkIntegrationTest {
    @Test fun benchmarkAllLocalModels() = runBlocking {
        TestEnv.assumeLlama()
        TestEnv.assumeSherpa()
        TestEnv.assumeFile(File(TestEnv.parakeetDir, "encoder.int8.onnx"))
        val engine = TestEnv.engine()
        var current: AsrEngine? = null
        val asrModels = listOfNotNull(
            ModelCatalog.byId(ModelCatalog.PARAKEET),
            ModelCatalog.byId(ModelCatalog.WHISPER_TURBO)?.takeIf { File(TestEnv.whisperDir, "turbo-encoder.int8.onnx").exists() },
        )
        val bench = Benchmark(
            translation = engine,
            translationModels = { ModelCatalog.builtIn.filter { TestEnv.files[it.id]?.exists() == true } },
            asrModels = { asrModels },
            loadAsr = { m ->
                when (m.asrType) {
                    AsrType.WHISPER_SHERPA -> SherpaAsr.whisper(m.id, m.name, TestEnv.whisperDir, 4)
                    else -> SherpaAsr.parakeet(m.id, m.name, TestEnv.parakeetDir, 4)
                }.also { current = it }
            },
            releaseAsr = { current?.close(); current = null },
            testAudio = {
                listOf("de", "en").map { lang ->
                    lang to File(TestEnv.testWavs, "$lang.wav").inputStream().use { Wav.resample(Wav.read(it), SAMPLE_RATE) }
                }
            },
            tts = emptyList(),
        )
        var report = BenchReport()
        bench.run("x86-64-Build-Container (4 vCPU), Threads 4") { report = it }
        current?.close()
        engine.unloadAll()
        println(report.asText())
        assertFalse(report.running)
        assertEquals(TestEnv.files.values.count { it.exists() }, report.mt.size)
        assertTrue(report.mt.all { it.avgMs > 0 && it.decodeTps > 0 && it.prefillTps > 0 })
        // the KV cache makes a repeated (live partial) prompt cheaper than a cold one
        assertTrue(report.mt.all { it.cachedMs < it.avgMs })
        assertEquals(asrModels.size, report.asr.size)
        assertTrue(report.asr.all { it.rtf in 0.0..2.0 && it.text.contains("Wurst") })
    }
}
