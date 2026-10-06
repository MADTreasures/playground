package ch.madtreasures.fluency.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.models.ModelState
import ch.madtreasures.fluency.ui.components.SectionTitle
import ch.madtreasures.fluency.ui.components.formatBytes

data class ModelsUiState(
    val models: List<ModelInfo> = emptyList(),
    val states: Map<String, ModelState> = emptyMap(),
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val message: String? = null,
)

class ModelsActions(
    val onDownload: (String) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onImportFiles: (String) -> Unit = {},
    val onImportGguf: () -> Unit = {},
    val onImportWhisper: () -> Unit = {},
    val onDownloadRecommended: () -> Unit = {},
    val onBenchmark: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf<ModelInfo?>(null) }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Modelle",
            actions = {
                IconButton(onClick = actions.onBenchmark) { Icon(Icons.Default.Speed, "Benchmark") }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                StorageCard(state, actions)
            }
            if (state.message != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.message, Modifier.weight(1f))
                            TextButton(onClick = actions.onDismissMessage) { Text("OK") }
                        }
                    }
                }
            }
            ModelKind.entries.forEach { kind ->
                val list = state.models.filter { it.kind == kind }
                if (list.isNotEmpty()) {
                    item(key = "title-$kind") { SectionTitle(kind.title) }
                    items(list, key = { it.id }) { m ->
                        ModelCard(
                            model = m,
                            state = state.states[m.id] ?: ModelState.NotInstalled,
                            actions = actions,
                            onDelete = { confirmDelete = m },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${m.name} löschen?") },
            text = { Text("${formatBytes(m.totalBytes)} werden freigegeben. Das Modell kann jederzeit wieder geladen werden.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(m.id); confirmDelete = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StorageCard(state: ModelsUiState, actions: ModelsActions) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Speicher", style = MaterialTheme.typography.titleMedium)
            Text("Modelle belegen ${formatBytes(state.usedBytes)} · frei: ${formatBytes(state.freeBytes)}")
            Text(
                "Downloads nur von Hugging Face (URL auf Commit fixiert, SHA-256 geprüft). Danach läuft alles offline, auch im Flugmodus.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = actions.onDownloadRecommended) {
                    Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Empfohlene laden")
                }
                OutlinedButton(onClick = actions.onImportGguf) { Text("GGUF importieren") }
                OutlinedButton(onClick = actions.onImportWhisper) { Text("Whisper-Modell importieren") }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo, state: ModelState, actions: ModelsActions, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        if (model.recommended) {
                            Spacer(Modifier.size(6.dp))
                            Text("empfohlen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (state == ModelState.Installed) {
                            Spacer(Modifier.size(6.dp))
                            Icon(Icons.Default.CheckCircle, "installiert", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "${formatBytes(model.totalBytes)} · Lizenz: ${model.license}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!model.custom) {
                            DropdownMenuItem(
                                text = { Text("Datei manuell importieren …") },
                                leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                                onClick = { menu = false; actions.onImportFiles(model.id) },
                            )
                        }
                        DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(model.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.size(8.dp))
            when (state) {
                ModelState.Installed -> {}
                ModelState.NotInstalled -> if (!model.custom) {
                    FilledTonalButton(onClick = { actions.onDownload(model.id) }) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Herunterladen")
                    }
                }
                ModelState.Queued -> {
                    Text("In Warteschlange …", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                is ModelState.Downloading -> {
                    val p = if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else 0f
                    Text(
                        if (state.verifying) "Prüfe vorhandene Daten (SHA-256) …"
                        else "${formatBytes(state.bytesDone)} von ${formatBytes(state.bytesTotal)} · ${formatBytes(state.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
                        IconButton(onClick = { actions.onPause(model.id) }) { Icon(Icons.Default.Pause, "Pausieren") }
                    }
                }
                is ModelState.Paused -> {
                    val p = if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else 0f
                    Text("Pausiert: ${formatBytes(state.bytesDone)} von ${formatBytes(state.bytesTotal)}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    FilledTonalButton(onClick = { actions.onDownload(model.id) }) { Text("Fortsetzen") }
                }
                is ModelState.Importing -> {
                    val p = if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else 0f
                    Text("Importiere … ${formatBytes(state.bytesDone)}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                is ModelState.Failed -> {
                    Text("Fehler: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!model.custom) {
                        FilledTonalButton(onClick = { actions.onDownload(model.id) }, modifier = Modifier.padding(top = 4.dp)) {
                            Text(if (state.bytesDone > 0) "Fortsetzen" else "Erneut versuchen")
                        }
                    }
                }
            }
        }
    }
}
