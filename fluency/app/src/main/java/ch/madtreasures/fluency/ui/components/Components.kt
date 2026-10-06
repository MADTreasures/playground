package ch.madtreasures.fluency.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Latency
import ch.madtreasures.fluency.settings.AUTO
import ch.madtreasures.fluency.ui.theme.TranslationTextStyle

/** Top app bar for screens inside the app scaffold (the scaffold already handles system insets). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FluencyTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    androidx.compose.material3.TopAppBar(
        title = { Text(title) },
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    )
}

/** Source/target picker row with swap button. */
@Composable
fun LanguageBar(
    source: String,
    target: String,
    onSource: (String) -> Unit,
    onTarget: (String) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    allowAutoSource: Boolean = true,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LanguagePicker(
            selected = source, onSelect = onSource, allowAuto = allowAutoSource,
            label = "Von", modifier = Modifier.weight(1f), enabled = enabled,
        )
        IconButton(onClick = onSwap, enabled = enabled && source != AUTO) {
            Icon(Icons.Default.SwapHoriz, contentDescription = "Sprachen tauschen")
        }
        LanguagePicker(
            selected = target, onSelect = onTarget, allowAuto = false,
            label = "Nach", modifier = Modifier.weight(1f), enabled = enabled,
        )
    }
}

fun languageLabel(code: String): String =
    if (code == AUTO) "Automatisch" else Languages.byCode(code)?.nameDe ?: code

@Composable
fun LanguagePicker(
    selected: String,
    onSelect: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowAuto: Boolean = false,
    enabled: Boolean = true,
    languages: List<Language> = Languages.sortedForPicker,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: ${languageLabel(selected)}" },
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(languageLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            if (allowAuto) {
                DropdownMenuItem(text = { Text("Automatisch erkennen") }, onClick = { open = false; onSelect(AUTO) })
                HorizontalDivider()
            }
            languages.forEachIndexed { i, l ->
                if (i == Languages.core.size) HorizontalDivider()
                DropdownMenuItem(text = { Text(l.nameDe) }, onClick = { open = false; onSelect(l.code) })
            }
        }
    }
}

/** "⏱ 780 ms gesamt · Erkennung 120 ms · Übersetzung 340 ms · 46 Tok/s" */
@Composable
fun LatencyRow(latency: Latency, modifier: Modifier = Modifier) {
    val parts = buildList {
        latency.asrMs?.let { add(if (latency.reusedAsr) "Erkennung $it ms (vorab)" else "Erkennung $it ms") }
        add(if (latency.reusedPartial) "Übersetzung vorab fertig" else "Übersetzung ${latency.mtMs} ms")
        latency.tokensPerSecond?.takeIf { it > 0 }?.let { add("%.0f Tok/s".format(it)) }
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Timer, contentDescription = "Latenz",
            modifier = Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.primary,
        )
        HSpace(4)
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                append("${latency.totalMs} ms gesamt")
                pop()
                parts.forEach { append(" · $it") }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One recognised + translated utterance. */
@Composable
fun UtteranceCard(
    sourceText: String,
    translation: String,
    final: Boolean,
    latency: Latency?,
    showLatency: Boolean,
    error: String?,
    onSpeak: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (final) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                sourceText.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = if (final) FontStyle.Normal else FontStyle.Italic,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                translation.ifBlank { if (final) "" else "…" },
                style = TranslationTextStyle,
                modifier = Modifier.alpha(if (final) 1f else 0.6f),
            )
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (final && (showLatency && latency != null || onSpeak != null || onCopy != null)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    if (showLatency && latency != null) LatencyRow(latency, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    if (onSpeak != null) IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Vorlesen") }
                    if (onCopy != null) IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Kopieren") }
                }
            }
        }
    }
}

/** Big round microphone button with a level ring. */
@Composable
fun MicButton(
    recording: Boolean,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = if (recording) "Aufnahme beenden" else "Sprechen",
) {
    val ring by animateFloatAsState(if (recording) 1f + level * 0.35f else 1f, label = "level")
    Box(modifier.size(112.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier.size(96.dp).scale(ring).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            )
        }
        LargeFloatingActionButton(
            onClick = { if (enabled) onClick() },
            shape = CircleShape,
            containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        ) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = label, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000L -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}
