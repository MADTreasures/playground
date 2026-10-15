package ch.madtreasures.fluency.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.engine.llm.ContextDocument
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import ch.madtreasures.fluency.ui.components.SectionTitle

/**
 * The user's context document: own sentences about what the conversations are about, and fixed
 * translations "Wort = Übersetzung". Saved with "Speichern" or when leaving the screen.
 */
@Composable
fun ContextDocumentScreen(text: String, onSave: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var value by rememberSaveable(text) { mutableStateOf(text) }
    val doc = remember(value) { ContextDocument.parse(value) }
    val leave = {
        if (value != text) onSave(value)
        onBack()
    }
    BackHandler(onBack = leave)
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Kontext-Dokument",
            navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            actions = { TextButton(onClick = { onSave(value) }, enabled = value != text) { Text("Speichern") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "Schreib hier, worum es in deinen Gesprächen geht, ein Satz pro Zeile, z. B. „Ich teste meine " +
                    "Übersetzungs-App auf dem Handy.“ Feste Übersetzungen schreibst du als „Wort = Übersetzung“, " +
                    "z. B. „Nachrichten = messages“.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(ContextDocument.MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                placeholder = { Text("Ich teste meine Übersetzungs-App auf dem Handy.\nNachrichten = messages") },
                supportingText = { Text("${value.length} / ${ContextDocument.MAX_CHARS} Zeichen") },
            )
            SectionTitle("So verwendet die App das Dokument")
            Text(
                when {
                    doc.isEmpty -> "Noch leer: übersetzt wird ohne Kontext."
                    else -> "${doc.sentenceCount} ${if (doc.sentenceCount == 1) "Satz" else "Sätze"} · " +
                        "${doc.terms.size} ${if (doc.terms.size == 1) "fester Begriff" else "feste Begriffe"}"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            doc.terms.forEach { t ->
                Text("${t.source} → ${t.target}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "• Die Sätze gehen bei jeder Übersetzung mit. Sie werden einmal berechnet und danach aus dem " +
                    "Zwischenspeicher genommen; pro Satz kosten sie fast keine Zeit.\n" +
                    "• Ein fester Begriff gilt nur, wenn das Wort im Satz vorkommt, mit beliebiger Endung " +
                    "(„Nachricht“ passt auch zu „Nachrichten“), und auch in der Gegenrichtung (messages → Nachrichten).\n" +
                    "• Die App nimmt nichts automatisch dazu, nur was hier steht.\n" +
                    "• Gilt für Hy-MT2 (Standardmodell), nicht für MiLMMT-46.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(32.dp))
        }
    }
}
