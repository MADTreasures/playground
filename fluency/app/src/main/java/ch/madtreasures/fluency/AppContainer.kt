package ch.madtreasures.fluency

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.pm.PackageManager
import android.os.Build
import ch.madtreasures.fluency.engine.asr.AsrManager
import ch.madtreasures.fluency.engine.llm.AccelStore
import ch.madtreasures.fluency.engine.llm.Acceleration
import ch.madtreasures.fluency.engine.llm.GpuGuard
import ch.madtreasures.fluency.engine.llm.GpuInfo
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.engine.tts.AndroidTtsSpeaker
import ch.madtreasures.fluency.engine.tts.PiperSpeaker
import ch.madtreasures.fluency.engine.tts.RoutingSpeaker
import ch.madtreasures.fluency.models.DownloadService
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelDownloader
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.models.ModelManager
import ch.madtreasures.fluency.models.ModelStore
import ch.madtreasures.fluency.pipeline.EngineTranslator
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.settings.SettingsRepository
import ch.madtreasures.fluency.settings.SettingsRepository.Companion.fluencyDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency wiring; one instance per process (see [FluencyApp]). */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(app.fluencyDataStore, appScope)
    val settings: AppSettings get() = settingsRepository.settings.value

    val modelStore = ModelStore(File(app.filesDir, "models"))

    val modelManager = ModelManager(modelStore, ModelDownloader(modelStore), appScope) { active ->
        DownloadService.update(app, active)
    }

    /** CPU/GPU decisions per model; measured again after an app or Android update */
    private val accelStore = AccelStore(File(app.filesDir, "acceleration.properties"), buildFingerprint())
    private val gpuGuard = GpuGuard(File(app.filesDir, "gpu-step"))

    init {
        checkGpuCrash()
    }

    val translationEngine = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = modelManager.installed(ModelKind.TRANSLATION)
            override fun modelPath(info: ModelInfo): String = modelStore.file(info.id, info.files.first().path).path
        },
        settings = { settings },
        nativeLibDir = app.applicationInfo.nativeLibraryDir,
        acceleration = Acceleration(accelStore, gpuGuard) {
            // compiled Adreno kernels: the system clears this directory when the app is updated
            GpuInfo.openCl(app.applicationInfo.nativeLibraryDir, File(app.codeCacheDir, "opencl").path)
        },
        scope = appScope,
    )

    val translator = EngineTranslator(translationEngine)

    init {
        // a different CPU/GPU setting applies to the loaded models right away
        appScope.launch {
            settingsRepository.settings.map { it.accel }.distinctUntilChanged().drop(1).collect { translationEngine.refresh() }
        }
    }

    val asrManager = AsrManager(modelManager) { settings }

    private val androidTts by lazy { AndroidTtsSpeaker(app) { settings.speechRate } }

    val piperSpeaker by lazy {
        PiperSpeaker(
            voices = {
                modelManager.installed(ModelKind.TTS).mapNotNull { m -> m.ttsLanguage?.let { it to modelStore.dir(m.id) } }.toMap()
            },
            espeakDataDir = {
                if (modelManager.isInstalled(ModelCatalog.ESPEAK_DATA)) File(modelStore.dir(ModelCatalog.ESPEAK_DATA), "espeak-ng-data") else null
            },
            rate = { settings.speechRate },
        )
    }

    val speaker by lazy { RoutingSpeaker(androidTts, piperSpeaker) { settings.ttsEngine == "piper" } }

    private fun buildFingerprint(): String {
        val pm = app.packageManager
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, 0)
        }
        return "${pi.longVersionCode}/${pi.lastUpdateTime}|${Build.FINGERPRINT}"
    }

    /**
     * If the previous process ended inside a GPU step (see [GpuGuard]) because it crashed, the
     * GPU is blocked until the user allows it again in the settings: no crash loop. Ends that are
     * not the GPU's fault (swiped away, out of memory, update) only repeat the step next time.
     */
    private fun checkGpuCrash() {
        val step = gpuGuard.takeLeftover() ?: return
        val exits = runCatching {
            app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 5)
        }.getOrDefault(emptyList())
        val exit = exits.firstOrNull { it.timestamp >= step.startedAt }
        val why = when (exit?.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativer Absturz"
            ApplicationExitInfo.REASON_CRASH -> "Absturz"
            ApplicationExitInfo.REASON_EXIT_SELF -> "beendet (Kernel-Übersetzung fehlgeschlagen?)"
            ApplicationExitInfo.REASON_ANR -> "reagierte nicht mehr"
            ApplicationExitInfo.REASON_UNKNOWN, null -> "unbekannter Grund"
            else -> return
        }
        accelStore.block("„${step.name}“ – App $why")
    }
}
