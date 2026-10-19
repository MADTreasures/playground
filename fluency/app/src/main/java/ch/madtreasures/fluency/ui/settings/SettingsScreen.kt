package ch.madtreasures.fluency.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.engine.llm.AccelChoice
import ch.madtreasures.fluency.engine.llm.AccelMode
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.ui.components.SectionTitle
import ch.madtreasures.fluency.ui.text.ModelOption
import kotlin.math.roundToInt

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val translationModels: List<ModelOption> = emptyList(),
    val asrModels: List<ModelOption> = emptyList(),
    val versionInfo: String = "",
    val accel: AccelUi = AccelUi(),
)

/** CPU/GPU/NPU state as text lines for the settings screen. */
data class AccelUi(
    /** one line per accelerator: device or why it is not used */
    val devices: List<String> = listOf("GPU: wird beim ersten Bedarf geprüft", "NPU: wird beim ersten Bedarf geprüft"),
    /** accelerators blocked after a crash (shown in the error colour, with a button to allow them again) */
    val blocked: List<Processor> = emptyList(),
    /** model name → where it runs and why */
    val models: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun from(status: TranslationEngine.AccelStatus, models: List<ModelOption>, mode: AccelMode): AccelUi = AccelUi(
            devices = Processor.accelerators.map { p ->
                status.problems[p] ?: status.devices[p]?.let { "$p: $it" } ?: "$p: wird beim ersten Bedarf geprüft"
            },
            blocked = status.blocked.sorted(),
            models = models.map { m -> m.name to modelLine(status, m.id, mode) },
        )

        private fun modelLine(status: TranslationEngine.AccelStatus, id: String, mode: AccelMode): String {
            val d = status.decisions[id]
            val measured = when {
                id in status.measuring -> "wird auf CPU, GPU und NPU gemessen …"
                d == null -> if (mode == AccelMode.AUTO) "noch nicht gemessen (geschieht beim ersten Laden)" else "nicht gemessen"
                d.millis.size > 1 -> {
                    // fastest first, e.g. "NPU am schnellsten: NPU 480 ms, CPU 700 ms, GPU 900 ms (2 Testsätze)"
                    val times = d.millis.entries.sortedBy { it.value }.joinToString(", ") { "${it.key} ${it.value} ms" }
                    "${d.processor} gewählt: $times (2 Testsätze)" + d.note.takeIf { it.isNotEmpty() }?.let { " – $it" }.orEmpty()
                }
                else -> "CPU – ${d.note.ifEmpty { "keine GPU/NPU genutzt" }}"
            }
            val failed = status.failed[id]?.entries?.joinToString("") { " · ${it.key}-Fehler: ${it.value}" }.orEmpty()
            val active = status.active[id]?.let { " · läuft jetzt auf der $it" }.orEmpty()
            return measured + failed + active
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBenchmark: () -> Unit,
    modifier: Modifier = Modifier,
    onRemeasure: () -> Unit = {},
    onUnblock: (Processor) -> Unit = {},
) {
    val s = state.settings
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Einstellungen",
            actions = { IconButton(onClick = onBenchmark) { Icon(Icons.Default.Speed, "Benchmark") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Übersetzung")
            Choice(
                "Live-Modell (Teilübersetzungen)", s.liveModelId,
                state.translationModels, onPick = { id -> onChange { it.copy(liveModelId = id) } },
            )
            Choice(
                "Finale Übersetzung", s.finalModelId,
                listOf(ModelOption("", "Wie Live-Modell (am schnellsten)")) + state.translationModels,
                onPick = { id -> onChange { it.copy(finalModelId = id) } },
            )
            Text(
                "Mit demselben Modell kann eine fertige Teilübersetzung sofort als finale Übersetzung übernommen werden. " +
                    "Ein Qualitätsmodell (z. B. MiLMMT-46 4B) übersetzt am Satzende neu und ist dadurch langsamer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Spracherkennung")
            Choice(
                "Modell", s.asrModelId,
                listOf(ModelOption("", "Automatisch (Parakeet, sonst Whisper)")) + state.asrModels,
                onPick = { id -> onChange { it.copy(asrModelId = id) } },
            )
            Stepper(
                "Satzende nach Pause von", s.endSilenceMs, 200..1200, 50, "ms",
            ) { v -> onChange { it.copy(endSilenceMs = v) } }
            Stepper(
                "Teilergebnisse alle", s.partialIntervalMs, 250..1500, 50, "ms",
            ) { v -> onChange { it.copy(partialIntervalMs = v) } }

            SectionTitle("Sprachausgabe")
            Toggle("Im Live-Modus vorlesen", s.speakLive) { v -> onChange { it.copy(speakLive = v) } }
            Toggle("Im Gesprächsmodus vorlesen", s.speakConversation) { v -> onChange { it.copy(speakConversation = v) } }
            Choice(
                "Stimme", s.ttsEngine,
                listOf(ModelOption("android", "Android-TTS (offline-Stimmen)"), ModelOption("piper", "Piper (falls installiert)")),
                onPick = { id -> onChange { it.copy(ttsEngine = id) } },
            )
            Stepper("Sprechtempo", (s.speechRate * 100).roundToInt(), 60..160, 10, "%") { v ->
                onChange { it.copy(speechRate = v / 100f) }
            }
            Toggle("Mikrofon während des Vorlesens stumm", s.muteMicWhileSpeaking) { v -> onChange { it.copy(muteMicWhileSpeaking = v) } }

            SectionTitle("Leistung")
            Choice(
                "Rechenwerk für die Übersetzung", s.accel.name,
                listOf(
                    ModelOption(AccelMode.AUTO.name, "Automatisch: CPU, GPU und NPU messen (empfohlen)"),
                    ModelOption(AccelMode.NPU.name, "Immer NPU (Hexagon)"),
                    ModelOption(AccelMode.GPU.name, "Immer GPU (Adreno, OpenCL)"),
                    ModelOption(AccelMode.CPU.name, "Nur CPU"),
                ),
                onPick = { id -> AccelMode.parse(id)?.let { m -> onChange { it.copy(accel = m) } } },
            )
            AccelPanel(state.accel, onRemeasure, onUnblock)
            Text(
                "Automatisch: Beim ersten Laden eines Modells übersetzt Fluency zwei Testsätze auf CPU, GPU und NPU. " +
                    "Das schnellste Rechenwerk gewinnt; GPU und NPU werden auch genommen, solange sie höchstens " +
                    "${((AccelChoice.TOLERANCE - 1) * 100).roundToInt()} % langsamer als die CPU sind, weil die CPU dann für die " +
                    "gleichzeitige Spracherkennung frei bleibt. Der erste Start auf der GPU dauert einmalig länger (die GPU-Programme " +
                    "werden übersetzt). Spracherkennung und Sprachausgabe laufen immer auf der CPU.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Stepper("Threads Übersetzung", s.llmThreads, 1..8, 1, "") { v -> onChange { it.copy(llmThreads = v) } }
            Stepper("Threads Spracherkennung", s.asrThreads, 1..6, 1, "") { v -> onChange { it.copy(asrThreads = v) } }
            Text(
                "Threads gelten nach einem Neustart der App, ein Wechsel des Rechenwerks sofort. " +
                    "Der Benchmark misst jedes Modell auf CPU, GPU und NPU.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Anzeige")
            Toggle("Latenz anzeigen", s.showLatency) { v -> onChange { it.copy(showLatency = v) } }
            Toggle("Bildschirm während Aufnahme anlassen", s.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }

            SectionTitle("Über Fluency")
            Text(
                "Fluency übersetzt vollständig offline. Es gibt keine Cloud-Dienste, kein Konto und keine Telemetrie. " +
                    "Das Internet wird nur für das einmalige Herunterladen der Modelle verwendet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(state.versionInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun AccelPanel(accel: AccelUi, onRemeasure: () -> Unit, onUnblock: (Processor) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        accel.devices.forEach { line ->
            val blocked = accel.blocked.any { line.startsWith("$it ") }
            Text(
                line, style = MaterialTheme.typography.bodyMedium,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        accel.models.forEach { (name, line) ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                    append(name)
                    pop()
                    append(": $line")
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row {
            TextButton(onClick = onRemeasure) { Text("Neu messen") }
            accel.blocked.forEach { p -> TextButton(onClick = { onUnblock(p) }) { Text("$p wieder zulassen") } }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: String, options: List<ModelOption>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.id == selected }?.name ?: selected.ifEmpty { "–" }, Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o.name) }, onClick = { open = false; onPick(o.id) }) }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${local.roundToInt()} $unit".trim(), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = (it / step).roundToInt() * step.toFloat() },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
}
