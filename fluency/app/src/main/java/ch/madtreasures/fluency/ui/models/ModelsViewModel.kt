package ch.madtreasures.fluency.ui.models

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.models.AsrType
import ch.madtreasures.fluency.models.ModelKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsViewModel(private val c: AppContainer) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val storage = MutableStateFlow(0L to 0L)

    val ui: StateFlow<ModelsUiState> = combine(c.modelManager.models, c.modelManager.states, storage, message) { models, states, st, msg ->
        ModelsUiState(models, states, st.first, st.second, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    init {
        refreshStorage()
        // storage numbers follow installs/deletes
        viewModelScope.launch {
            c.modelManager.states.map { s -> s.values.count { it == ch.madtreasures.fluency.models.ModelState.Installed } }
                .collect { refreshStorage() }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            storage.value = withContext(Dispatchers.IO) { c.modelStore.usedBytes() to c.modelStore.freeBytes() }
        }
    }

    fun download(id: String) = c.modelManager.download(id)
    fun pause(id: String) = c.modelManager.pause(id)
    fun delete(id: String) {
        viewModelScope.launch {
            // a loaded model must be released before its files disappear
            c.translationEngine.unloadAll()
            c.asrManager.releaseAll()
            c.modelManager.delete(id)
        }
    }

    fun downloadRecommended() = c.modelManager.downloadRecommended()

    fun importFiles(id: String, uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val r = c.modelManager.importFiles(id, uris, resolver)
            message.value = r.exceptionOrNull()?.message ?: "Import abgeschlossen"
            refreshStorage()
        }
    }

    fun importCustom(uri: Uri?, resolver: ContentResolver, whisper: Boolean) {
        uri ?: return
        viewModelScope.launch {
            val r = if (whisper) c.modelManager.importCustom(uri, resolver, ModelKind.ASR, AsrType.WHISPER_CPP)
            else c.modelManager.importCustom(uri, resolver, ModelKind.TRANSLATION)
            message.value = r.fold(
                { "${it.name} importiert" + if (whisper) " – wird für „Deutsch (Schweiz)“ verwendet" else "" },
                { "Import fehlgeschlagen: ${it.message}" },
            )
            refreshStorage()
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}
