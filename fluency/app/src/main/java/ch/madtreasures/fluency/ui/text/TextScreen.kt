package ch.madtreasures.fluency.ui.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.ui.components.LanguageBar
import ch.madtreasures.fluency.ui.components.LatencyRow
import ch.madtreasures.fluency.ui.theme.TranslationTextStyle

class TextActions(
    val onInput: (String) -> Unit = {},
    val onTranslate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onSpeak: () -> Unit = {},
    val onSource: (String) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onTypingMode: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScreen(state: TextUiState, actions: TextActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Text übersetzen")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LanguageBar(
                source = state.source, target = state.target,
                onSource = actions.onSource, onTarget = actions.onTarget, onSwap = actions.onSwap,
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = actions.onInput,
                label = { Text("Text eingeben oder einfügen") },
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Column {
                        IconButton(onClick = actions.onPaste) { Icon(Icons.Default.ContentPaste, "Einfügen") }
                        if (state.input.isNotEmpty()) IconButton(onClick = actions.onClear) { Icon(Icons.Default.Clear, "Leeren") }
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelSelector(state, actions.onModel, Modifier.weight(1f))
                Text("Beim Tippen", style = MaterialTheme.typography.labelMedium)
                Switch(checked = state.translateWhileTyping, onCheckedChange = actions.onTypingMode)
            }
            Button(
                onClick = { if (state.translating) actions.onCancel() else actions.onTranslate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.input.isNotBlank() || state.translating,
            ) {
                if (state.translating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(8.dp))
                    Text("Stopp")
                } else {
                    Icon(Icons.Default.Translate, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Übersetzen")
                }
            }
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (state.output.isNotEmpty() || state.translating) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (state.detectedSource != null) {
                            Text(
                                "Erkannt: ${state.detectedSource}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            Text(state.output.ifEmpty { "…" }, style = TranslationTextStyle, modifier = Modifier.heightIn(min = 40.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (state.showLatency && state.latency != null && !state.translating) {
                                    LatencyRow(state.latency)
                                }
                                state.modelName?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = actions.onSpeak, enabled = state.output.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Vorlesen")
                            }
                            IconButton(onClick = actions.onCopy, enabled = state.output.isNotBlank()) {
                                Icon(Icons.Default.ContentCopy, "Kopieren")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ModelSelector(state: TextUiState, onModel: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = state.models.firstOrNull { it.id == state.selectedModel }?.name ?: "Automatisch (beste Qualität)"
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Modell: $label", maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automatisch (beste Qualität)") }, onClick = { open = false; onModel("") })
            state.models.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { open = false; onModel(m.id) }) }
        }
    }
}
