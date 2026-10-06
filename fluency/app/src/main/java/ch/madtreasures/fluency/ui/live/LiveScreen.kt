package ch.madtreasures.fluency.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.ui.components.InfoBanner
import ch.madtreasures.fluency.ui.components.LanguageBar
import ch.madtreasures.fluency.ui.components.MicButton
import ch.madtreasures.fluency.ui.components.UtteranceCard
import ch.madtreasures.fluency.ui.components.formatBytes

class LiveActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSource: (String) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onToggleSpeak: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onSpeak: (Utterance) -> Unit = {},
    val onCopy: (Utterance) -> Unit = {},
    val onDownloadRecommended: () -> Unit = {},
    val onDismissSetup: () -> Unit = {},
    val onOpenModels: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(state: LiveUiState, actions: LiveActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.utterances.size, state.utterances.lastOrNull()?.translation) {
        if (state.utterances.isNotEmpty()) listState.animateScrollToItem(state.utterances.lastIndex)
    }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Live-Übersetzung",
            actions = {
                IconButton(onClick = actions.onToggleSpeak) {
                    Icon(
                        if (state.speak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (state.speak) "Vorlesen aus" else "Vorlesen ein",
                    )
                }
                IconButton(onClick = actions.onClear, enabled = state.utterances.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Verlauf leeren")
                }
            },
        )
        LanguageBar(
            source = state.source, target = state.target,
            onSource = actions.onSource, onTarget = actions.onTarget, onSwap = actions.onSwap,
            enabled = !state.running && !state.preparing,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.utterances.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.needsSetup) {
                        InfoBanner(
                            "Willkommen bei Fluency! Alles läuft offline auf dem Handy – ohne Cloud und ohne Konto. " +
                                "Lade einmalig die empfohlenen Modelle (Hy-MT2, Parakeet, Silero VAD, " +
                                "${formatBytes(state.setupBytes)}).",
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = actions.onDownloadRecommended) { Text("Empfohlene laden") }
                                TextButton(onClick = actions.onOpenModels) { Text("Auswahl") }
                            }
                        }
                    } else {
                        Text(
                            "Tippe auf das Mikrofon und sprich. Untertitel und Übersetzung erscheinen, während du sprichst.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.utterances, key = { it.id }) { u ->
                        UtteranceCard(
                            sourceText = u.sourceText,
                            translation = u.translation,
                            final = u.final,
                            latency = u.latency,
                            showLatency = state.showLatency,
                            error = u.error,
                            onSpeak = { actions.onSpeak(u) },
                            onCopy = { actions.onCopy(u) },
                        )
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val msg = state.error ?: state.status ?: when {
                state.running && state.speechActive -> "Höre zu …"
                state.running -> "Bereit – sprich jetzt"
                else -> null
            }
            if (msg != null) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.padding(10.dp))
            }
            MicButton(
                recording = state.running,
                level = if (state.speechActive) state.level else state.level * 0.3f,
                enabled = !state.preparing,
                onClick = { if (state.running) actions.onStop() else actions.onStart() },
            )
        }
    }
}
