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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
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
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.WordUse

/**
 * The listed ambiguous words of a translation, e.g. "Bank → bench · Hinweis „im Park“". Words the
 * model decided on are shown quietly ("· Modell"). Tapping one says why this meaning and what the
 * check found; choosing another meaning translates the sentence again ([onChoose], null: no choice).
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
    val quiet = !w.chosen && !w.doubtful
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    buildAnnotatedString {
                        append("${w.from} → ${w.to ?: "?"}")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" · " + if (w.doubtful) "Hinweis davor spricht dagegen" else w.reasonLabel)
                        }
                    },
                    style = if (quiet) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                )
            },
            leadingIcon = {
                Icon(
                    statusIcon(w),
                    contentDescription = w.statusLabel,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    tint = if (w.doubtful || w.status == WordUse.Status.REPEATED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            },
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
                w.labels.forEachIndexed { i, label ->
                    val translation = w.options.getOrNull(i)?.firstOrNull()
                    DropdownMenuItem(
                        text = { Text(if (translation != null) "$label – $translation" else "$label (keine Übersetzung)") },
                        leadingIcon = { if (i == w.meaningIndex) Icon(Icons.Default.Check, contentDescription = "gewählt") },
                        enabled = !(w.chosen && i == w.meaningIndex),
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

private fun statusIcon(w: WordUse): ImageVector = when {
    w.doubtful -> Icons.Outlined.WarningAmber
    else -> when (w.status) {
        WordUse.Status.CONFIRMED, WordUse.Status.CONFIRMED_AFTER_HINT -> Icons.Default.Check
        // the model decided, the app only looked
        WordUse.Status.DETECTED -> Icons.Outlined.Visibility
        WordUse.Status.CORRECTED -> Icons.Default.AutoFixHigh
        WordUse.Status.REPEATED -> Icons.Outlined.WarningAmber
        WordUse.Status.UNCLEAR -> Icons.AutoMirrored.Outlined.HelpOutline
        WordUse.Status.PLANNED, WordUse.Status.SYNONYM, WordUse.Status.NO_TRANSLATION -> Icons.Outlined.Info
    }
}

/** why this meaning and what the check found, in full sentences */
fun explain(w: WordUse): String {
    val label = w.labels.getOrNull(w.meaningIndex)
    val why = when (w.reason) {
        WordUse.Reason.FORCED -> "„$label“: von dir für diesen Satz gewählt."
        WordUse.Reason.HINT -> "„$label“, weil „${w.hint}“ im Satz steht."
        WordUse.Reason.HINT_BEFORE -> "„$label“, weil „${w.hint}“ im Satz davor stand."
        WordUse.Reason.CHOICE -> "„$label“: früher in diesem Gespräch von dir gewählt."
        WordUse.Reason.STANDARD -> "Kein Hinweiswort: „$label“ ist der Standard."
        WordUse.Reason.ONLY -> "Feste Übersetzung."
        WordUse.Reason.MODEL -> "Kein Hinweiswort im Satz: das Modell hat entschieden, die App hat nichts vorgegeben."
    }
    val language = Languages.byCode(w.target)?.nameDe ?: w.target
    val check = when (w.status) {
        WordUse.Status.CONFIRMED -> "Geprüft: „${w.to}“ steht in der Übersetzung."
        WordUse.Status.CONFIRMED_AFTER_HINT -> "Geprüft: „${w.to}“ steht in der Übersetzung (im 2. Durchlauf)."
        WordUse.Status.CORRECTED -> "Nachkorrigiert: das Modell hatte eine andere eingetragene Bedeutung, sie wurde durch „${w.to}“ ersetzt."
        WordUse.Status.SYNONYM -> "Anders formuliert: „${w.to}“ steht nicht wörtlich in der Übersetzung, aber auch keine andere eingetragene Bedeutung."
        WordUse.Status.REPEATED -> "Bitte prüfen: „${w.to}“ steht öfter in der Übersetzung als „${w.from}“ im Satz – " +
            "vielleicht hat das Modell damit noch ein anderes Wort übersetzt."
        WordUse.Status.NO_TRANSLATION -> "Für $language ist bei dieser Bedeutung keine Übersetzung eingetragen; das Modell hat frei übersetzt."
        WordUse.Status.DETECTED -> "Es hat „${w.to}“ genommen, also „$label“."
        WordUse.Status.UNCLEAR -> "Keine (oder mehrere) der eingetragenen Bedeutungen stehen in der Übersetzung."
        WordUse.Status.PLANNED -> "Nicht geprüft."
    }
    val doubt = if (w.doubtful) {
        " Aber „${w.suggestedHint}“ im Satz davor spricht für „${w.labels.getOrNull(w.suggested)}“."
    } else {
        ""
    }
    return "$why $check$doubt"
}
