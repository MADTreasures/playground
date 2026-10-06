package ch.madtreasures.fluency

import android.app.Application
import ch.madtreasures.fluency.engine.asr.AsrManager
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

    val translationEngine = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = modelManager.installed(ModelKind.TRANSLATION)
            override fun modelPath(info: ModelInfo): String = modelStore.file(info.id, info.files.first().path).path
        },
        settings = { settings },
        nativeLibDir = app.applicationInfo.nativeLibraryDir,
    )

    val translator = EngineTranslator(translationEngine)

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
}
