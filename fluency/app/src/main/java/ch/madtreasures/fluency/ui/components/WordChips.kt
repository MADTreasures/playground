package ch.madtreasures.fluency.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.engine.llm.WordUse

/**
 * The listed ambiguous words of a translation, e.g. "Nachrichten → messages · Standard". Tapping one
 * says why this meaning was taken and what the check found; choosing another meaning translates the
 * sentence again ([onChoose], null: no choice possible here).
 */
@Composable
fun WordChips(words: List<WordUse>, onChoose: ((WordUse, Int) -> Unit)?, modifier: Modifier = Modifier) {
    if (words.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        words.forEach { WordChip(it, onChoose) }
    }
}

@Composable
private fun WordChip(w: WordUse, onChoose: ((WordUse, Int) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    buildAnnotatedString {
                        append("${w.from} → ${w.to}")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ${w.reasonLabel}") }
                    },
                )
            },
            leadingIcon = { Icon(statusIcon(w.status), contentDescription = w.statusLabel, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                explain(w),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 300.dp),
            )
            if (w.switchable && onChoose != null) {
                HorizontalDivider()
                w.meanings.forEachIndexed { i, meaning ->
                    DropdownMenuItem(
                        text = { Text(if (i == 0) "$meaning (Standard)" else meaning) },
                        leadingIcon = { if (i == w.meaningIndex) Icon(Icons.Default.Check, contentDescription = "gewählt") },
                        enabled = i != w.meaningIndex,
                        onClick = {
                            open = false
                            onChoose(w, i)
                        },
                    )
                }
            }
        }
    }
}

private fun statusIcon(s: WordUse.Status): ImageVector = when (s) {
    WordUse.Status.CONFIRMED, WordUse.Status.CONFIRMED_AFTER_HINT -> Icons.Default.Check
    WordUse.Status.CORRECTED -> Icons.Default.AutoFixHigh
    WordUse.Status.PLANNED, WordUse.Status.SYNONYM -> Icons.Outlined.Info
    WordUse.Status.REPEATED -> Icons.Outlined.WarningAmber
}

/** why this meaning and what the check found, in full sentences */
fun explain(w: WordUse): String {
    val why = when (w.reason) {
        WordUse.Reason.FORCED -> "Von dir für diesen Satz gewählt."
        WordUse.Reason.HINT -> "„${w.hint}“ steht im Satz."
        WordUse.Reason.HINT_BEFORE -> "„${w.hint}“ stand im Satz davor."
        WordUse.Reason.CHOICE -> "Früher in diesem Gespräch von dir gewählt."
        WordUse.Reason.STANDARD -> "Kein Hinweiswort gefunden: die erste Bedeutung (Standard)."
        WordUse.Reason.ONLY -> "Nur eine Übersetzung eingetragen."
        WordUse.Reason.REVERSE -> "Gegenrichtung: „${w.from}“ wird immer zu „${w.to}“."
    }
    val check = when (w.status) {
        WordUse.Status.CONFIRMED -> "Geprüft: „${w.to}“ steht in der Übersetzung."
        WordUse.Status.CONFIRMED_AFTER_HINT -> "Geprüft: „${w.to}“ steht in der Übersetzung (im 2. Durchlauf)."
        WordUse.Status.CORRECTED -> "Nachkorrigiert: das Modell hatte eine andere eingetragene Bedeutung, sie wurde durch „${w.to}“ ersetzt."
        WordUse.Status.SYNONYM -> "Anders formuliert: „${w.to}“ steht nicht wörtlich in der Übersetzung, aber auch keine andere eingetragene Bedeutung."
        WordUse.Status.REPEATED -> "Bitte prüfen: „${w.to}“ steht öfter in der Übersetzung als „${w.from}“ im Satz – " +
            "vielleicht hat das Modell damit noch ein anderes Wort übersetzt."
        WordUse.Status.PLANNED -> "Nicht geprüft."
    }
    return "$why $check"
}
