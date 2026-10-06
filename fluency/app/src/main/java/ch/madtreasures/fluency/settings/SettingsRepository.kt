package ch.madtreasures.fluency.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.madtreasures.fluency.models.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "auto" as source language means: detect (Parakeet/Whisper detect it, Hy-MT2 does not need it). */
const val AUTO = "auto"

data class AppSettings(
    val liveSource: String = "de",
    val liveTarget: String = "en",
    val convLangA: String = "de-CH",
    val convLangB: String = "en",
    val textSource: String = AUTO,
    val textTarget: String = "en",
    /** translation model for live partials (and finals unless [finalModelId] is set) */
    val liveModelId: String = ModelCatalog.HY_MT2,
    /** "" = same as the live model (fastest: a finished partial is reused instantly) */
    val finalModelId: String = "",
    /** "" = best installed model */
    val textModelId: String = "",
    /** "" = automatic: Parakeet for its 25 languages, Whisper otherwise */
    val asrModelId: String = "",
    val speakLive: Boolean = false,
    val speakConversation: Boolean = true,
    /** "android" or "piper" */
    val ttsEngine: String = "android",
    val speechRate: Float = 1.0f,
    val partialIntervalMs: Int = 400,
    val endSilenceMs: Int = 400,
    val llmThreads: Int = 6,
    val asrThreads: Int = 2,
    val useGpu: Boolean = false,
    val showLatency: Boolean = true,
    val keepScreenOn: Boolean = true,
    val muteMicWhileSpeaking: Boolean = true,
    val setupDismissed: Boolean = false,
)

class SettingsRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {

    private object K {
        val liveSource = stringPreferencesKey("liveSource")
        val liveTarget = stringPreferencesKey("liveTarget")
        val convLangA = stringPreferencesKey("convLangA")
        val convLangB = stringPreferencesKey("convLangB")
        val textSource = stringPreferencesKey("textSource")
        val textTarget = stringPreferencesKey("textTarget")
        val liveModelId = stringPreferencesKey("liveModelId")
        val finalModelId = stringPreferencesKey("finalModelId")
        val textModelId = stringPreferencesKey("textModelId")
        val asrModelId = stringPreferencesKey("asrModelId")
        val speakLive = booleanPreferencesKey("speakLive")
        val speakConversation = booleanPreferencesKey("speakConversation")
        val ttsEngine = stringPreferencesKey("ttsEngine")
        val speechRate = floatPreferencesKey("speechRate")
        val partialIntervalMs = intPreferencesKey("partialIntervalMs")
        val endSilenceMs = intPreferencesKey("endSilenceMs")
        val llmThreads = intPreferencesKey("llmThreads")
        val asrThreads = intPreferencesKey("asrThreads")
        val useGpu = booleanPreferencesKey("useGpu")
        val showLatency = booleanPreferencesKey("showLatency")
        val keepScreenOn = booleanPreferencesKey("keepScreenOn")
        val muteMicWhileSpeaking = booleanPreferencesKey("muteMicWhileSpeaking")
        val setupDismissed = booleanPreferencesKey("setupDismissed")
    }

    private val defaults = AppSettings()

    private fun read(p: Preferences) = AppSettings(
        liveSource = p[K.liveSource] ?: defaults.liveSource,
        liveTarget = p[K.liveTarget] ?: defaults.liveTarget,
        convLangA = p[K.convLangA] ?: defaults.convLangA,
        convLangB = p[K.convLangB] ?: defaults.convLangB,
        textSource = p[K.textSource] ?: defaults.textSource,
        textTarget = p[K.textTarget] ?: defaults.textTarget,
        liveModelId = p[K.liveModelId] ?: defaults.liveModelId,
        finalModelId = p[K.finalModelId] ?: defaults.finalModelId,
        textModelId = p[K.textModelId] ?: defaults.textModelId,
        asrModelId = p[K.asrModelId] ?: defaults.asrModelId,
        speakLive = p[K.speakLive] ?: defaults.speakLive,
        speakConversation = p[K.speakConversation] ?: defaults.speakConversation,
        ttsEngine = p[K.ttsEngine] ?: defaults.ttsEngine,
        speechRate = p[K.speechRate] ?: defaults.speechRate,
        partialIntervalMs = p[K.partialIntervalMs] ?: defaults.partialIntervalMs,
        endSilenceMs = p[K.endSilenceMs] ?: defaults.endSilenceMs,
        llmThreads = p[K.llmThreads] ?: defaults.llmThreads,
        asrThreads = p[K.asrThreads] ?: defaults.asrThreads,
        useGpu = p[K.useGpu] ?: defaults.useGpu,
        showLatency = p[K.showLatency] ?: defaults.showLatency,
        keepScreenOn = p[K.keepScreenOn] ?: defaults.keepScreenOn,
        muteMicWhileSpeaking = p[K.muteMicWhileSpeaking] ?: defaults.muteMicWhileSpeaking,
        setupDismissed = p[K.setupDismissed] ?: defaults.setupDismissed,
    )

    val settings: StateFlow<AppSettings> = store.data.map(::read).stateIn(scope, SharingStarted.Eagerly, defaults)

    /** The stored settings (waits for the first read from disk, unlike [settings].value). */
    suspend fun loaded(): AppSettings = store.data.map(::read).first()

    private val writeScope = scope

    /** Applies [transform] to the latest stored settings (atomic read-modify-write). */
    fun update(transform: (AppSettings) -> AppSettings) {
        writeScope.launch {
            store.edit { p ->
                val s = transform(read(p))
                p[K.liveSource] = s.liveSource
                p[K.liveTarget] = s.liveTarget
                p[K.convLangA] = s.convLangA
                p[K.convLangB] = s.convLangB
                p[K.textSource] = s.textSource
                p[K.textTarget] = s.textTarget
                p[K.liveModelId] = s.liveModelId
                p[K.finalModelId] = s.finalModelId
                p[K.textModelId] = s.textModelId
                p[K.asrModelId] = s.asrModelId
                p[K.speakLive] = s.speakLive
                p[K.speakConversation] = s.speakConversation
                p[K.ttsEngine] = s.ttsEngine
                p[K.speechRate] = s.speechRate
                p[K.partialIntervalMs] = s.partialIntervalMs
                p[K.endSilenceMs] = s.endSilenceMs
                p[K.llmThreads] = s.llmThreads
                p[K.asrThreads] = s.asrThreads
                p[K.useGpu] = s.useGpu
                p[K.showLatency] = s.showLatency
                p[K.keepScreenOn] = s.keepScreenOn
                p[K.muteMicWhileSpeaking] = s.muteMicWhileSpeaking
                p[K.setupDismissed] = s.setupDismissed
            }
        }
    }

    companion object {
        val Context.fluencyDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
    }
}
