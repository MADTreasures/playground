package ch.madtreasures.fluency.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.asr.SherpaVad
import ch.madtreasures.fluency.engine.audio.MicrophoneSource
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.pipeline.LivePipeline
import ch.madtreasures.fluency.pipeline.PipelineState
import ch.madtreasures.fluency.pipeline.SileroDetector
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.settings.AUTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveUiState(
    val source: String = "de",
    val target: String = "en",
    val running: Boolean = false,
    val preparing: Boolean = false,
    val speechActive: Boolean = false,
    val level: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val speak: Boolean = false,
    val showLatency: Boolean = true,
    val keepScreenOn: Boolean = true,
    val status: String? = null,
    val error: String? = null,
    /** first start: nothing installed yet */
    val needsSetup: Boolean = false,
    val setupBytes: Long = 0,
    val asrName: String? = null,
    val translationName: String? = null,
)

/** Shared logic of live and conversation mode: starting a pipeline with warm models. */
abstract class PipelineHost(protected val c: AppContainer) : ViewModel() {
    protected val pipelineState = MutableStateFlow(PipelineState())
    protected val preparing = MutableStateFlow(false)
    protected val localError = MutableStateFlow<String?>(null)
    private var pipeline: LivePipeline? = null
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected fun lang(code: String): Language? = if (code == AUTO) null else Languages.byCode(code)

    /**
     * Loads the models for [source] → [target] in the background, so that the first tap on the
     * microphone starts instantly (llama.cpp mmap + weight repacking, ONNX session creation).
     */
    protected fun warmUp(source: Language?, target: Language?) {
        if (target == null) return
        pipelineScope.launch {
            runCatching {
                c.translationEngine.ensureBackends()
                c.asrManager.pickModel(source)?.let { c.asrManager.engine(it) }
                val ids = listOfNotNull(
                    c.translationEngine.pickModel(Role.LIVE, source, target)?.id,
                    c.translationEngine.pickModel(Role.FINAL, source, target)?.id,
                )
                c.translationEngine.preload(ids)
            }
        }
    }

    /** whether finished translations are spoken (checked per utterance) */
    protected abstract fun speakEnabled(): Boolean

    protected fun startPipeline(config: LivePipeline.Config) {
        if (pipeline != null || preparing.value) return
        localError.value = null
        preparing.value = true
        viewModelScope.launch {
            try {
                val s = c.settings
                val asrInfo = c.asrManager.pickModel(config.source)
                    ?: error("Kein Spracherkennungsmodell für ${config.source?.nameDe ?: "die Sprache"} installiert – siehe „Modelle“")
                val vadPath = c.asrManager.vadModelPath() ?: error("Silero VAD fehlt – bitte unter „Modelle“ laden")
                val live = c.translationEngine.pickModel(Role.LIVE, config.source, config.target)
                    ?: error("Kein Übersetzungsmodell für ${config.target.nameDe} installiert – siehe „Modelle“")
                val final = c.translationEngine.pickModel(Role.FINAL, config.source, config.target)
                val (asr, vad) = withContext(Dispatchers.IO) {
                    c.translationEngine.ensureBackends()
                    c.asrManager.engine(asrInfo) to SherpaVad(vadPath, minSilenceSeconds = s.endSilenceMs / 1000f)
                }
                withContext(Dispatchers.IO) { c.translationEngine.preload(setOfNotNull(live.id, final?.id)) }
                val p = LivePipeline(
                    config = config.copy(
                        partialIntervalMs = s.partialIntervalMs.toLong(),
                        muteWhileSpeaking = s.muteMicWhileSpeaking,
                    ),
                    audio = MicrophoneSource(),
                    vad = SileroDetector(vad),
                    asr = asr,
                    translator = c.translator,
                    speaker = c.speaker,
                    scope = pipelineScope,
                    state = pipelineState,
                    speakEnabled = { speakEnabled() },
                )
                pipeline = p
                p.start()
                pipelineState.update { it.copy(status = "${asr.name} · ${live.name}") }
                launch {
                    // forget the pipeline once it stopped by itself (auto-stop, error)
                    pipelineState.first { !it.running }
                    if (pipeline === p) pipeline = null
                }
            } catch (e: Exception) {
                localError.value = e.message ?: e.javaClass.simpleName
            } finally {
                preparing.value = false
            }
        }
    }

    protected fun stopPipeline() {
        val p = pipeline ?: return
        pipeline = null
        viewModelScope.launch { p.stop() }
    }

    /** Stops the running pipeline and waits until its last utterance is finished. */
    protected suspend fun stopAndWait() {
        val p = pipeline ?: return
        pipeline = null
        p.stop()
    }

    protected val isRunning: Boolean get() = pipeline != null || preparing.value

    fun speak(u: Utterance) {
        viewModelScope.launch { runCatching { c.speaker.speak(u.translation, u.target) } }
    }

    fun clear() {
        pipelineState.update { s -> s.copy(utterances = s.utterances.filter { !it.final }, error = null) }
        localError.value = null
    }

    override fun onCleared() {
        pipeline?.cancel()
        pipeline = null
        pipelineScope.cancel()
    }
}

class LiveViewModel(c: AppContainer) : PipelineHost(c) {

    val ui: StateFlow<LiveUiState> = combine(
        pipelineState, c.settingsRepository.settings, c.modelManager.states, preparing, localError,
    ) { p, s, _, prep, err ->
        val hasMt = c.modelManager.installed(ModelKind.TRANSLATION).isNotEmpty()
        val hasAsr = c.modelManager.installed(ModelKind.ASR).isNotEmpty()
        LiveUiState(
            source = s.liveSource,
            target = s.liveTarget,
            running = p.running,
            preparing = prep,
            speechActive = p.speechActive,
            level = p.level,
            utterances = p.utterances,
            speak = s.speakLive,
            showLatency = s.showLatency,
            keepScreenOn = s.keepScreenOn,
            status = if (prep) "Modelle werden geladen …" else p.status,
            error = err ?: p.error,
            needsSetup = !(hasMt && hasAsr) && !s.setupDismissed,
            setupBytes = ModelCatalog.recommendedIds.sumOf { c.modelManager.model(it)?.totalBytes ?: 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    override fun speakEnabled(): Boolean = c.settings.speakLive

    init {
        viewModelScope.launch {
            val s = c.settingsRepository.loaded()
            warmUp(lang(s.liveSource), Languages.byCode(s.liveTarget))
        }
    }

    fun start() {
        val s = c.settings
        val target = Languages.byCode(s.liveTarget) ?: return
        startPipeline(LivePipeline.Config(source = lang(s.liveSource), target = target, speak = s.speakLive))
    }

    fun stop() = stopPipeline()

    fun setSource(code: String) = c.settingsRepository.update { it.copy(liveSource = code) }
    fun setTarget(code: String) = c.settingsRepository.update { it.copy(liveTarget = code) }
    fun swap() = c.settingsRepository.update {
        if (it.liveSource == AUTO) it else it.copy(liveSource = it.liveTarget, liveTarget = it.liveSource)
    }
    fun toggleSpeak() = c.settingsRepository.update { it.copy(speakLive = !it.speakLive) }

    fun downloadRecommended() = c.modelManager.downloadRecommended()
    fun dismissSetup() = c.settingsRepository.update { it.copy(setupDismissed = true) }
}
