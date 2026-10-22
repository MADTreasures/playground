package ch.madtreasures.fluency.pipeline

import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.engine.asr.SherpaVad
import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * A model the pipeline needs is not installed. [message] is meant for the screen ("… – siehe „Modelle“"),
 * [brief] for the glasses, where a note has room for about 60 characters.
 */
class MissingModelException(message: String, val brief: String) : IllegalStateException(message)

/**
 * Loads the models of a [LivePipeline] (speech recognition, VAD, translation) and builds it, with the
 * settings of the app. Shared by the live and conversation screens and the glasses bridge, so all of them
 * use the same models.
 */
class PipelineFactory(private val c: AppContainer) {
    /** A pipeline ready to start, and the line that names its models ("Parakeet · Hy-MT2 1.8B (NPU)"). */
    class Ready(val pipeline: LivePipeline, val models: String)

    /** Throws [MissingModelException] when a model is missing. */
    suspend fun build(
        config: LivePipeline.Config,
        audio: AudioSource,
        scope: CoroutineScope,
        state: MutableStateFlow<PipelineState>,
        speakEnabled: () -> Boolean,
    ): Ready {
        val s = c.settings
        val asrInfo = c.asrManager.pickModel(config.source) ?: throw MissingModelException(
            "Kein Spracherkennungsmodell für ${config.source?.nameDe ?: "die Sprache"} installiert – siehe „Modelle“",
            "Keine Spracherkennung für ${config.source?.nameDe ?: "die Sprache"}",
        )
        val vadPath = c.asrManager.vadModelPath()
            ?: throw MissingModelException("Silero VAD fehlt – bitte unter „Modelle“ laden", "Silero VAD fehlt (Fluency → Modelle)")
        val live = c.translationEngine.pickModel(Role.LIVE, config.source, config.target) ?: throw MissingModelException(
            "Kein Übersetzungsmodell für ${config.target.nameDe} installiert – siehe „Modelle“",
            "Kein Modell für " + (config.source?.let { "${it.nameDe} → " } ?: "") + config.target.nameDe,
        )
        val final = c.translationEngine.pickModel(Role.FINAL, config.source, config.target)
        val (asr, vad) = withContext(Dispatchers.IO) {
            c.translationEngine.ensureBackends()
            c.asrManager.engine(asrInfo) to SherpaVad(vadPath, minSilenceSeconds = s.endSilenceMs / 1000f)
        }
        try {
            withContext(Dispatchers.IO) { c.translationEngine.preload(setOfNotNull(live.id, final?.id)) }
        } catch (e: Throwable) {
            runCatching { vad.close() }
            throw e
        }
        val pipeline = LivePipeline(
            config = config.copy(
                partialIntervalMs = s.partialIntervalMs.toLong(),
                muteWhileSpeaking = s.muteMicWhileSpeaking,
            ),
            audio = audio,
            vad = SileroDetector(vad),
            asr = asr,
            translator = c.translator,
            speaker = c.speaker,
            scope = scope,
            state = state,
            speakEnabled = speakEnabled,
        )
        val on = c.translationEngine.processorOf(live.id)?.let { " ($it)" }.orEmpty()
        return Ready(pipeline, "${asr.name} · ${live.name}$on")
    }
}
