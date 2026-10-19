package ch.madtreasures.fluency.engine.asr

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.models.AsrType
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.models.ModelManager
import ch.madtreasures.fluency.settings.AppSettings
import java.io.File
import java.io.IOException

/** Chooses and keeps the speech recognisers warm. */
class AsrManager(
    private val models: ModelManager,
    private val settings: () -> AppSettings,
) {
    private val engines = LinkedHashMap<String, AsrEngine>()

    /**
     * Routing:
     * - "Deutsch (Schweiz)": the Swiss German whisper.cpp model if installed, else as German
     * - an explicitly chosen model if it supports the language
     * - automatic: Parakeet (detects 25 languages itself), else Whisper
     * - otherwise the fastest installed model supporting the language
     */
    fun pickModel(source: Language?): ModelInfo? {
        val installed = models.installed(ModelKind.ASR)
        val s = settings()
        if (source?.code == "de-CH") {
            installed.firstOrNull { it.asrType == AsrType.WHISPER_CPP && it.supports("de-CH") }?.let { return it }
            installed.firstOrNull { it.custom && it.asrType == AsrType.WHISPER_CPP }?.let { return it }
        }
        if (s.asrModelId.isNotEmpty()) {
            installed.firstOrNull { it.id == s.asrModelId && (source == null || supports(it, source)) }?.let { return it }
        }
        if (source == null) {
            return installed.firstOrNull { it.asrType == AsrType.PARAKEET }
                ?: installed.firstOrNull { it.asrType == AsrType.WHISPER_SHERPA }
                ?: installed.firstOrNull()
        }
        return installed.filter { supports(it, source) }.maxByOrNull { it.speedRank }
    }

    private fun supports(m: ModelInfo, l: Language): Boolean = when {
        m.custom -> true
        m.asrType == AsrType.WHISPER_CPP -> m.supports(l.code)
        // "de-CH" spoken as standard German: Parakeet/Whisper handle it as "de"
        else -> m.supports(l.code) || (l.code == "de-CH" && m.supports("de"))
    }

    /** Blocking: loads the engine on first use. */
    @Synchronized
    fun engine(info: ModelInfo): AsrEngine {
        engines[info.id]?.let { return it }
        val threads = settings().asrThreads
        val dir = models.store.dir(info.id)
        val engine: AsrEngine = when (info.asrType) {
            AsrType.PARAKEET -> SherpaAsr.parakeet(info.id, info.name, dir, threads)
            AsrType.WHISPER_SHERPA -> SherpaAsr.whisper(info.id, info.name, dir, threads)
            AsrType.WHISPER_CPP -> {
                val file = File(dir, info.files.first().path)
                WhisperCppAsr.load(info.id, info.name, file.path, maxOf(threads, 4), fixedLanguage = "de".takeIf { info.supports("de-CH") && !info.custom })
                    ?: throw IOException("${info.name} konnte nicht geladen werden")
            }
            null -> throw IOException("${info.name} ist kein Spracherkennungsmodell")
        }
        engines[info.id] = engine
        return engine
    }

    fun vadModelPath(): String? {
        val vad = models.model(ModelCatalog.SILERO_VAD) ?: return null
        if (!models.isInstalled(vad.id)) return null
        return models.store.file(vad.id, vad.files.first().path).path
    }

    @Synchronized
    fun releaseAll() {
        engines.values.forEach { runCatching { it.close() } }
        engines.clear()
    }
}
