package ch.madtreasures.fluency.ui.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Latency
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.settings.AUTO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelOption(val id: String, val name: String)

data class TextUiState(
    val source: String = AUTO,
    val target: String = "en",
    val input: String = "",
    val output: String = "",
    val translating: Boolean = false,
    val latency: Latency? = null,
    val modelName: String? = null,
    val detectedSource: String? = null,
    val error: String? = null,
    val models: List<ModelOption> = emptyList(),
    /** "" = automatic */
    val selectedModel: String = "",
    val translateWhileTyping: Boolean = true,
    val showLatency: Boolean = true,
)

class TextViewModel(private val c: AppContainer) : ViewModel() {
    private data class Local(
        val input: String = "",
        val output: String = "",
        val translating: Boolean = false,
        val latency: Latency? = null,
        val modelName: String? = null,
        val detectedSource: String? = null,
        val error: String? = null,
        val translateWhileTyping: Boolean = true,
    )

    private val local = MutableStateFlow(Local())
    private var job: Job? = null

    val ui: StateFlow<TextUiState> = combine(local, c.settingsRepository.settings, c.modelManager.states) { l, s, _ ->
        TextUiState(
            source = s.textSource, target = s.textTarget, input = l.input, output = l.output,
            translating = l.translating, latency = l.latency, modelName = l.modelName,
            detectedSource = l.detectedSource, error = l.error,
            models = c.modelManager.installed(ModelKind.TRANSLATION).map { ModelOption(it.id, it.name) },
            selectedModel = s.textModelId, translateWhileTyping = l.translateWhileTyping, showLatency = s.showLatency,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TextUiState())

    fun onInput(text: String) {
        local.update { it.copy(input = text) }
        if (local.value.translateWhileTyping) translate(debounceMs = 600, role = Role.LIVE)
    }

    fun translate(debounceMs: Long = 0, role: Role = Role.TEXT) {
        job?.cancel()
        c.translationEngine.cancel(Role.LIVE)
        job = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val s = c.settings
            val text = local.value.input
            if (text.isBlank()) {
                local.update { it.copy(output = "", latency = null, error = null, translating = false) }
                return@launch
            }
            val target = Languages.byCode(s.textTarget) ?: return@launch
            val source = if (s.textSource == AUTO) null else Languages.byCode(s.textSource)
            local.update { it.copy(translating = true, error = null) }
            val t0 = System.nanoTime()
            try {
                val forced = s.textModelId.ifEmpty { null }?.takeIf { role == Role.TEXT }
                val res = c.translationEngine.translate(TranslationEngine.Request(text, source, target, role, forced)) { partial ->
                    local.update { it.copy(output = partial) }
                }
                if (!res.cancelled) {
                    local.update {
                        it.copy(
                            output = res.text,
                            latency = Latency(
                                asrMs = null, mtMs = res.wallMs, totalMs = (System.nanoTime() - t0) / 1_000_000,
                                tokensPerSecond = res.tokensPerSecond, model = res.modelName, processor = res.processor.name,
                            ),
                            modelName = res.modelName,
                            detectedSource = if (source == null) res.sourceUsed?.nameDe else null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = e.message ?: "Fehler") }
            } finally {
                local.update { it.copy(translating = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        c.translationEngine.cancel(null)
        local.update { it.copy(translating = false) }
    }

    fun clear() {
        cancel()
        local.update { it.copy(input = "", output = "", latency = null, error = null) }
    }

    fun setSource(code: String) {
        c.settingsRepository.update { it.copy(textSource = code) }
        retranslate()
    }

    fun setTarget(code: String) {
        c.settingsRepository.update { it.copy(textTarget = code) }
        retranslate()
    }

    fun swap() {
        val st = ui.value
        if (st.source == AUTO) return
        c.settingsRepository.update { it.copy(textSource = it.textTarget, textTarget = it.textSource) }
        local.update { it.copy(input = st.output.ifBlank { it.input }, output = "") }
        retranslate()
    }

    fun setModel(id: String) = c.settingsRepository.update { it.copy(textModelId = id) }

    fun setTranslateWhileTyping(on: Boolean) = local.update { it.copy(translateWhileTyping = on) }

    fun speakOutput() {
        val st = ui.value
        val lang = Languages.byCode(st.target) ?: return
        viewModelScope.launch { runCatching { c.speaker.speak(st.output, lang) } }
    }

    private fun retranslate() {
        if (local.value.input.isNotBlank()) translate(debounceMs = 150, role = Role.TEXT)
    }
}
