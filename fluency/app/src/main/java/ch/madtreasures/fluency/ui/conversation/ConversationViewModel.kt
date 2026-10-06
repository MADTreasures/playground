package ch.madtreasures.fluency.ui.conversation

import androidx.lifecycle.viewModelScope
import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.pipeline.LivePipeline
import ch.madtreasures.fluency.ui.live.PipelineHost
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationViewModel(c: AppContainer) : PipelineHost(c) {

    val ui: StateFlow<ConversationUiState> = combine(
        pipelineState, c.settingsRepository.settings, preparing, localError,
    ) { p, s, prep, err ->
        ConversationUiState(
            langA = s.convLangA,
            langB = s.convLangB,
            running = p.running,
            preparing = prep,
            activeSpeaker = p.activeSpeaker,
            speechActive = p.speechActive,
            level = p.level,
            utterances = p.utterances,
            speak = s.speakConversation,
            showLatency = s.showLatency,
            keepScreenOn = s.keepScreenOn,
            error = err ?: p.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    override fun speakEnabled(): Boolean = c.settings.speakConversation

    init {
        viewModelScope.launch {
            val s = c.settingsRepository.loaded()
            warmUp(Languages.byCode(s.convLangA), Languages.byCode(s.convLangB))
        }
    }

    /** Tap on a microphone: start that side; tapping the active side again stops it. */
    fun onMic(speaker: Int) {
        val st = ui.value
        viewModelScope.launch {
            if (st.running) {
                stopAndWait()
                if (st.activeSpeaker == speaker) return@launch
            }
            val s = c.settings
            val a = Languages.byCode(s.convLangA) ?: return@launch
            val b = Languages.byCode(s.convLangB) ?: return@launch
            val (src, tgt) = if (speaker == 0) a to b else b to a
            startPipeline(
                LivePipeline.Config(
                    source = src, target = tgt, speaker = speaker, speak = s.speakConversation,
                    // hand over automatically after a pause, so the other person can answer
                    autoStopSilenceMs = 1_800,
                ),
            )
        }
    }

    fun stop() = stopPipeline()

    fun setLangA(code: String) = c.settingsRepository.update { it.copy(convLangA = code) }
    fun setLangB(code: String) = c.settingsRepository.update { it.copy(convLangB = code) }
    fun swap() = c.settingsRepository.update { it.copy(convLangA = it.convLangB, convLangB = it.convLangA) }
    fun toggleSpeak() = c.settingsRepository.update { it.copy(speakConversation = !it.speakConversation) }
}
