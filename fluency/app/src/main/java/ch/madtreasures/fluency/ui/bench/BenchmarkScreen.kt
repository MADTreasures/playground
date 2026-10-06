package ch.madtreasures.fluency.ui.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.madtreasures.fluency.AppContainer
import ch.madtreasures.fluency.bench.AsrBench
import ch.madtreasures.fluency.bench.BenchReport
import ch.madtreasures.fluency.bench.Benchmark
import ch.madtreasures.fluency.bench.MtBench
import ch.madtreasures.fluency.core.Wav
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkViewModel(private val c: AppContainer) : ViewModel() {
    private val _report = MutableStateFlow(BenchReport())
    val report: StateFlow<BenchReport> = _report
    private var job: Job? = null

    fun run() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val system = withContext(Dispatchers.IO) { systemSummary() }
                val bench = Benchmark(
                    translation = c.translationEngine,
                    translationModels = { c.modelManager.installed(ModelKind.TRANSLATION) },
                    asrModels = { c.modelManager.installed(ModelKind.ASR) },
                    loadAsr = { c.asrManager.engine(it) },
                    releaseAsr = { c.asrManager.releaseAll() },
                    testAudio = {
                        listOf("de", "en").map { lang ->
                            lang to c.app.assets.open("bench/$lang.wav").use { Wav.resample(Wav.read(it), 16_000) }
                        }
                    },
                    tts = buildList {
                        if (c.modelManager.installed(ModelKind.TTS).isNotEmpty()) {
                            add("Piper (Deutsch)" to { text, lang -> c.piperSpeaker.synthesize(text, lang) })
                        }
                    },
                )
                bench.run(system) { _report.value = it }
            } catch (e: Exception) {
                _report.value = _report.value.copy(running = false, step = null, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun systemSummary(): String {
        val am = c.app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val backends = runCatching { c.translationEngine.ensureBackends() }.getOrElse { "Backends: ${it.message}" }
        val accel = c.translationEngine.accelStatus.value
        return buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} · SoC ${Build.SOC_MODEL} · RAM ${mem.totalMem / 1_000_000_000} GB · Android ${Build.VERSION.RELEASE}\n")
            append("Threads: Übersetzung ${c.settings.llmThreads}, Erkennung ${c.settings.asrThreads} · Rechenwerk: ${c.settings.accel}\n")
            append(backends)
            accel.problem?.let { append("\n$it") }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(report: BenchReport, onRun: () -> Unit, onCopy: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Benchmark",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "Misst alle installierten Modelle auf diesem Gerät: Ladezeit, Übersetzungszeit pro Satz auf CPU und GPU, " +
                        "Prompt- und Ausgabe-Tempo (Tokens/s), Spracherkennung (Echtzeitfaktor) und Sprachausgabe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onRun, enabled = !report.running) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (report.mt.isEmpty() && report.asr.isEmpty()) "Benchmark starten" else "Erneut messen")
                    }
                    OutlinedButton(onClick = onCopy, enabled = !report.running && (report.mt.isNotEmpty() || report.asr.isNotEmpty())) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Kopieren")
                    }
                }
                if (report.running) {
                    Text(report.step ?: "…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (report.system.isNotEmpty()) {
                    Text(report.system, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (report.mt.isNotEmpty()) item { SectionTitle("Übersetzung") }
            items(report.mt, key = { "mt-" + it.modelId + "-" + it.processor }) { MtCard(it) }
            if (report.choices.isNotEmpty()) {
                item {
                    Column {
                        report.choices.forEach { Text("→ $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "Die GPU wird genommen, solange sie höchstens 10 % langsamer ist (die CPU bleibt dann für die Spracherkennung frei).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (report.asr.isNotEmpty()) item { SectionTitle("Spracherkennung") }
            items(report.asr, key = { "asr-" + it.modelId }) { AsrCard(it) }
            if (report.tts.isNotEmpty()) item { SectionTitle("Sprachausgabe") }
            items(report.tts, key = { "tts-" + it.name }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text("${t.synthMs} ms für ${"%.1f".format(t.audioSeconds)} s Audio · Echtzeitfaktor ${"%.3f".format(t.rtf)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun MtCard(m: MtBench) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium)
            if (m.error != null) {
                Text(m.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Metric("Ø pro Satz", "%.0f ms".format(m.avgMs))
            Metric("Mit KV-Cache (Live-Teilübersetzung)", "%.0f ms".format(m.cachedMs))
            Metric("Prompt-Verarbeitung", "%.0f Tokens/s".format(m.prefillTps))
            Metric("Textausgabe", "%.1f Tokens/s".format(m.decodeTps))
            Metric("Laden", "${m.loadMs} ms")
            Text(m.sample, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AsrCard(a: AsrBench) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium)
            Metric("Erkennung", "${a.decodeMs} ms für ${"%.1f".format(a.audioSeconds)} s Audio")
            Metric("Echtzeitfaktor", "%.3f".format(a.rtf))
            Metric("Laden", "${a.loadMs} ms")
            Text(a.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
