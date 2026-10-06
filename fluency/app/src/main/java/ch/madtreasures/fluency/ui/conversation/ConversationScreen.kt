package ch.madtreasures.fluency.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.ui.components.LanguagePicker
import ch.madtreasures.fluency.ui.components.LatencyRow
import ch.madtreasures.fluency.ui.components.MicButton
import ch.madtreasures.fluency.ui.theme.TranslationTextStyle

data class ConversationUiState(
    val langA: String = "de-CH",
    val langB: String = "en",
    val running: Boolean = false,
    val preparing: Boolean = false,
    val activeSpeaker: Int = 0,
    val speechActive: Boolean = false,
    val level: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val speak: Boolean = true,
    val showLatency: Boolean = true,
    val keepScreenOn: Boolean = true,
    val error: String? = null,
)

class ConversationActions(
    val onMic: (speaker: Int) -> Unit = {},
    val onLangA: (String) -> Unit = {},
    val onLangB: (String) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onToggleSpeak: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * Two people at a table: the lower half belongs to the phone owner (A), the upper half is turned
 * upside down for the person opposite (B). Everyone reads their own language in their half.
 */
@Composable
fun ConversationScreen(state: ConversationUiState, actions: ConversationActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ConversationHalf(
            me = 1, state = state, myLang = state.langB, onLang = actions.onLangB,
            onMic = { actions.onMic(1) },
            modifier = Modifier.weight(1f).rotate(180f).background(MaterialTheme.colorScheme.surfaceContainerLow),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onClose) { Icon(Icons.Default.Close, "Gespräch schliessen") }
                Text(
                    state.error ?: if (state.preparing) "Modelle werden geladen …" else "Gesprächsmodus",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onSwap, enabled = !state.running) { Icon(Icons.Default.SwapVert, "Sprachen tauschen") }
                IconButton(onClick = actions.onToggleSpeak) {
                    Icon(
                        if (state.speak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (state.speak) "Vorlesen aus" else "Vorlesen ein",
                    )
                }
                IconButton(onClick = actions.onClear, enabled = state.utterances.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, "Verlauf leeren")
                }
            }
        }
        HorizontalDivider()
        ConversationHalf(
            me = 0, state = state, myLang = state.langA, onLang = actions.onLangA,
            onMic = { actions.onMic(0) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConversationHalf(
    me: Int,
    state: ConversationUiState,
    myLang: String,
    onLang: (String) -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.utterances.size, state.utterances.lastOrNull()?.translation) {
        if (state.utterances.isNotEmpty()) listState.animateScrollToItem(state.utterances.lastIndex)
    }
    val recording = state.running && state.activeSpeaker == me
    Column(modifier.fillMaxWidth()) {
        LanguagePicker(
            selected = myLang, onSelect = onLang, label = if (me == 0) "Ich spreche" else "Gegenüber spricht",
            enabled = !state.running,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.utterances, key = { it.id }) { u -> Bubble(u, mine = u.speaker == me, showLatency = state.showLatency && me == 0) }
        }
        Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
            MicButton(
                recording = recording,
                level = if (recording && state.speechActive) state.level else 0f,
                enabled = !state.preparing && !(state.running && !recording),
                onClick = onMic,
                label = if (recording) "Aufnahme beenden" else if (me == 0) "Ich spreche" else "Gegenüber spricht",
            )
        }
    }
}

@Composable
private fun Bubble(u: Utterance, mine: Boolean, showLatency: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 340.dp).alpha(if (u.final) 1f else 0.75f),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (mine) {
                    // what I said, plus a small preview of what the other person sees
                    Text(u.sourceText.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge)
                    if (u.translation.isNotBlank()) {
                        Text(u.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // what the other person said, in my language
                    Text(u.translation.ifBlank { "…" }, style = TranslationTextStyle)
                    if (u.sourceText.isNotBlank()) {
                        Text(u.sourceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (u.error != null) Text(u.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (showLatency && u.final && u.latency != null) LatencyRow(u.latency, Modifier.padding(top = 4.dp))
            }
        }
    }
}
