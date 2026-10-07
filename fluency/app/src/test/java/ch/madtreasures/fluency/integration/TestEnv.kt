package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.engine.llm.AccelStore
import ch.madtreasures.fluency.engine.llm.Acceleration
import ch.madtreasures.fluency.engine.llm.DeviceInfo
import ch.madtreasures.fluency.engine.llm.LlamaNative
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.settings.AppSettings
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Real-model tests run on the x86-64 build host with the same JNI code as the app
 * (native/build-host-jni.sh, native/build-sherpa-onnx.sh host). They are skipped when the
 * native libraries or the model files are missing.
 */
object TestEnv {
    /** Layout: llm/ (GGUF files), parakeet-v3/, whisper-turbo/, swiss-whisper/, vad/ (see README "Tests"). */
    val modelDir = File(System.getProperty("fluency.modelDir") ?: "test-models")

    val files: Map<String, File> = mapOf(
        ModelCatalog.HY_MT2 to File(modelDir, "llm/Hy-MT2-1.8B-Q4_K_M.gguf"),
        ModelCatalog.MILMMT_1B to File(modelDir, "llm/MiLMMT-46-1B-v1.0.Q4_K_M.gguf"),
        ModelCatalog.MILMMT_4B to File(modelDir, "llm/MiLMMT-46-4B-v1.0.Q4_K_M.gguf"),
    )
    val parakeetDir = File(modelDir, "parakeet-v3")
    val whisperDir = File(modelDir, "whisper-turbo")
    val swissWhisper = File(modelDir, "swiss-whisper/ggml-model-q5_0.bin")
    val vadV5 = File(modelDir, "vad/silero_vad_v5.onnx")
    val testWavs = File(parakeetDir, "test_wavs")
    val downloads = File(modelDir, "downloaded")

    private val llamaOk: Boolean by lazy {
        try {
            LlamaNative.load()
            LlamaNative.nativeInitBackends(null)
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("libfluency_jni not available: ${e.message}")
            false
        }
    }

    private val sherpaOk: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("libsherpa-onnx-jni not available: ${e.message}")
            false
        }
    }

    fun assumeLlama() = assumeTrue("host JNI library missing (native/build-host-jni.sh)", llamaOk)

    fun assumeSherpa() = assumeTrue("host sherpa-onnx JNI missing (native/build-sherpa-onnx.sh host)", sherpaOk)

    fun assumeFile(f: File) = assumeTrue("missing ${f.path}", f.exists())

    fun engine(settings: AppSettings = AppSettings(llmThreads = 4)) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> =
                ModelCatalog.builtIn.filter { files[it.id]?.exists() == true }
            override fun modelPath(info: ModelInfo): String = files.getValue(info.id).path
        },
        settings = { settings },
        nativeLibDir = null,
        // the real GPU/NPU probes: the host build has neither backend, so everything runs on the CPU
        acceleration = Acceleration(
            AccelStore(null, "host"),
            probes = mapOf(
                Processor.GPU to { DeviceInfo.openCl(null, null) },
                Processor.NPU to { DeviceInfo.hexagon(null) },
            ),
        ),
    )
}
