package ch.madtreasures.fluency.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AmbiguousWord
import ch.madtreasures.fluency.engine.llm.AmbiguousWords
import ch.madtreasures.fluency.settings.AUTO
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import ch.madtreasures.fluency.ui.components.InfoBanner
import ch.madtreasures.fluency.ui.components.LanguagePicker
import ch.madtreasures.fluency.ui.components.SectionTitle
import ch.madtreasures.fluency.ui.components.languageLabel

/** the example of the field test, offered while the list is empty */
val EXAMPLE_WORD = AmbiguousWord(
    "Nachrichten", "de", "en",
    listOf(
        AmbiguousWord.Meaning("messages", listOf("Chat", "Handy", "App", "WhatsApp", "SMS", "schreib*", "geschrieben")),
        AmbiguousWord.Meaning("news", listOf("Fernseh*", "Radio", "Zeitung", "Tagesschau", "Politik")),
    ),
)

/**
 * The module "Mehrdeutige Wörter": the user's own list of words with several meanings. Every
 * change is saved at once ([onSave] gets the whole list in the storage format).
 * [editing]: the entry being edited (-1 = a new one, null = the list).
 */
@Composable
fun AmbiguousWordsScreen(
    storage: String,
    defaultSource: String,
    defaultTarget: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialEditing: Int? = null,
) {
    val entries = remember(storage) { AmbiguousWords.parse(storage) }
    var editing by rememberSaveable { mutableStateOf(initialEditing) }
    val save = { list: List<AmbiguousWord> -> onSave(AmbiguousWords.format(list)) }
    val current = editing
    if (current != null) {
        val entry = entries.getOrNull(current)
        val source = defaultSource.takeIf { it != AUTO } ?: "de"
        EntryEditor(
            entry = entry ?: AmbiguousWord("", source, defaultTarget, emptyList()),
            isNew = entry == null,
            others = entries.filterIndexed { i, _ -> i != current },
            onSave = { e ->
                save(if (entry == null) entries + e else entries.mapIndexed { i, old -> if (i == current) e else old })
                editing = null
            },
            onDelete = {
                save(entries.filterIndexed { i, _ -> i != current })
                editing = null
            },
            onCancel = { editing = null },
            modifier = modifier,
        )
        return
    }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Mehrdeutige Wörter",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            actions = {
                IconButton(onClick = { editing = -1 }, enabled = entries.size < AmbiguousWords.MAX_ENTRIES) {
                    Icon(Icons.Default.Add, "Wort hinzufügen")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Trag Wörter ein, die mehrere Bedeutungen haben, mit ihren Übersetzungen und zu jeder Übersetzung " +
                    "Hinweiswörter. Die App wählt die Bedeutung nach festen Regeln, schreibt sie der Übersetzung vor " +
                    "und prüft das Ergebnis.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entries.isEmpty()) {
                InfoBanner(
                    "Noch keine Wörter. Beispiel: „Nachrichten“ → messages (Hinweise: Chat, Handy, App, WhatsApp, SMS, " +
                        "schreib*, geschrieben) oder news (Hinweise: Fernseh*, Radio, Zeitung, Tagesschau, Politik).",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { save(listOf(EXAMPLE_WORD)) }) { Text("Beispiel übernehmen") }
                        OutlinedButton(onClick = { editing = -1 }) { Text("Eigenes Wort") }
                    }
                }
            }
            entries.forEachIndexed { i, e -> EntryCard(e) { editing = i } }
            if (entries.isNotEmpty()) {
                OutlinedButton(
                    onClick = { editing = -1 },
                    enabled = entries.size < AmbiguousWords.MAX_ENTRIES,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Wort hinzufügen (${entries.size} / ${AmbiguousWords.MAX_ENTRIES})")
                }
            }
            SectionTitle("So wählt die App die Bedeutung")
            Text(
                "Nur wenn ein eingetragenes Wort im Satz vorkommt – auch mit üblicher Endung („Nachricht“ und " +
                    "„Nachrichten“, „Bank“ und „Bänke“), aber nicht in zusammengesetzten Wörtern („Bankraub“, " +
                    "„Sprachnachricht“: bei Bedarf eigens eintragen). Feste Reihenfolge, die erste Regel, die passt, gilt:\n" +
                    "1. Du hast für diesen Satz eine Bedeutung angetippt.\n" +
                    "2. Ein Hinweiswort genau einer Bedeutung steht im Satz.\n" +
                    "3. Ein Hinweiswort genau einer Bedeutung stand im Satz davor.\n" +
                    "4. Du hast in diesem Gespräch schon eine Bedeutung angetippt.\n" +
                    "5. Sonst die erste Bedeutung (Standard).\n" +
                    "In der Gegenrichtung (z. B. „messages“ in einem englischen Satz) wird immer das eingetragene Wort genommen.",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle("Was garantiert ist – und was nicht")
            Text(
                "• Die gewählte Bedeutung wird dem Modell vorgeschrieben (Hy-MT2) und danach geprüft. Fehlt sie, " +
                    "übersetzt die App ein zweites Mal mit deutlicherem Hinweis; steht dann noch eine andere " +
                    "eingetragene Bedeutung im Text, wird sie ersetzt. Eine falsche eingetragene Bedeutung bleibt so nie stehen.\n" +
                    "• Steht die Vorgabe öfter in der Übersetzung als das Wort im Satz, hat sie noch ein anderes Wort " +
                    "ersetzt (gemessen: „suitcases“ wurde auch zu „Kofferraum“). Dann nimmt die App die Übersetzung ohne Vorgabe.\n" +
                    "• Unter jeder Übersetzung siehst du das Wort, die Bedeutung und warum („Standard“, „Hinweis „Chat““ …). " +
                    "Antippen wechselt die Bedeutung, der Satz wird neu übersetzt.\n" +
                    "• Nicht garantiert: ob die Regel trifft, was du meinst (dafür gibt es Hinweiswörter und das Antippen), " +
                    "und der Rest des Satzes – den übersetzt weiterhin das Modell.\n" +
                    "• Sätze ohne eingetragenes Wort werden genau gleich übersetzt wie ohne dieses Modul.\n" +
                    "• MiLMMT-46 kennt keine Vorgaben: dort wird nur geprüft und nachkorrigiert.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun EntryCard(e: AmbiguousWord, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.word, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${languageLabel(e.source)} → ${languageLabel(e.target)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            e.meanings.forEachIndexed { i, m ->
                val hints = if (m.hints.isEmpty()) "" else " · Hinweise: " + m.hints.joinToString(", ")
                Text(
                    "${i + 1}. ${m.translation}" + (if (i == 0 && e.meanings.size > 1) " · Standard" else "") + hints,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** why an entry cannot be saved, null if it can */
fun entryProblem(e: AmbiguousWord, others: List<AmbiguousWord>): String? {
    if (e.word.isBlank()) return "Das Wort fehlt."
    if (e.meanings.isEmpty()) return "Mindestens eine Übersetzung eintragen."
    val src = Languages.byCode(e.source)?.promptName
    val tgt = Languages.byCode(e.target)?.promptName
    if (src == tgt) return "Ausgangs- und Zielsprache sind gleich."
    if (others.any { it.word.equals(e.word, ignoreCase = true) && Languages.byCode(it.source)?.promptName == src && Languages.byCode(it.target)?.promptName == tgt }) {
        return "„${e.word}“ ist für diese Sprachen schon eingetragen."
    }
    val translations = e.meanings.map { it.translation.lowercase() }
    translations.firstOrNull { t -> translations.count { it == t } > 1 }?.let { return "Die Übersetzung „$it“ steht zweimal da." }
    // a hint word decides only if it fits exactly one meaning ("Fern" would also fit "Fernsehen")
    e.meanings.forEachIndexed { i, m ->
        for (h in m.hints) {
            if (AmbiguousWords.hintOccurs(h, e.word)) return "Das Hinweiswort „$h“ passt zum Wort selbst und würde immer entscheiden."
            e.meanings.forEachIndexed { j, other ->
                val clash = if (i == j) null else other.hints.firstOrNull { AmbiguousWords.hintOccurs(h, it) }
                if (clash != null) {
                    return if (clash.equals(h, ignoreCase = true)) "Das Hinweiswort „$h“ steht bei mehreren Bedeutungen – so entscheidet es nichts."
                    else "Das Hinweiswort „$h“ passt auch zu „$clash“ (andere Bedeutung) – so entscheidet es nichts."
                }
            }
        }
    }
    return null
}

@Composable
private fun EntryEditor(
    entry: AmbiguousWord,
    isNew: Boolean,
    others: List<AmbiguousWord>,
    onSave: (AmbiguousWord) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var word by rememberSaveable { mutableStateOf(entry.word) }
    var source by rememberSaveable { mutableStateOf(entry.source) }
    var target by rememberSaveable { mutableStateOf(entry.target) }
    // one translation and one hint line per meaning; two empty rows for a new word
    var translations by rememberSaveable { mutableStateOf(entry.meanings.map { it.translation }.ifEmpty { listOf("", "") }) }
    var hints by rememberSaveable { mutableStateOf(entry.meanings.map { it.hints.joinToString(", ") }.ifEmpty { listOf("", "") }) }
    val result = AmbiguousWord(
        AmbiguousWords.clean(word), source, target,
        translations.indices.mapNotNull { i ->
            val t = AmbiguousWords.clean(translations[i])
            val h = hints[i].split(',').map { AmbiguousWords.clean(it) }.filter { it.isNotEmpty() }
            if (t.isEmpty()) null else AmbiguousWord.Meaning(t, h)
        },
    )
    val problem = entryProblem(result, others)
    BackHandler(onBack = onCancel)
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = if (isNew) "Neues Wort" else "Wort bearbeiten",
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Abbrechen") } },
            actions = { TextButton(onClick = { onSave(result) }, enabled = problem == null) { Text("Speichern") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguagePicker(source, { source = it }, label = "Wort in", modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 6.dp))
                LanguagePicker(target, { target = it }, label = "Übersetzt nach", modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = word,
                onValueChange = { word = it.filterNot { c -> c in FORBIDDEN || c == ',' || c == '*' } },
                label = { Text("Wort, z. B. Nachrichten") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("Bedeutungen")
            Text(
                "Die erste ist der Standard: sie gilt, wenn kein Hinweiswort im Satz oder im Satz davor steht. " +
                    "Hinweiswörter passen wie das Wort mit üblicher Endung; ein * am Ende passt zu jedem Wort, das so " +
                    "anfängt („Fernseh*“ passt zu Fernsehen, Fernseher und Fernsehsendung).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            translations.indices.forEach { i ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (i == 0) "1. Bedeutung · Standard" else "${i + 1}. Bedeutung",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (i > 0) {
                                TextButton(onClick = {
                                    translations = listOf(translations[i]) + translations.filterIndexed { j, _ -> j != i }
                                    hints = listOf(hints[i]) + hints.filterIndexed { j, _ -> j != i }
                                }) { Text("Als Standard") }
                            }
                            IconButton(
                                onClick = {
                                    translations = translations.filterIndexed { j, _ -> j != i }
                                    hints = hints.filterIndexed { j, _ -> j != i }
                                },
                                enabled = translations.size > 1,
                            ) { Icon(Icons.Default.Delete, "Bedeutung entfernen") }
                        }
                        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = translations[i],
                                onValueChange = { v -> translations = translations.mapIndexed { j, t -> if (j == i) v.filterNot { c -> c in FORBIDDEN || c == ',' || c == '*' } else t } },
                                label = { Text(if (i == 0) "Übersetzung, z. B. messages" else "Übersetzung, z. B. news") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = hints[i],
                                onValueChange = { v -> hints = hints.mapIndexed { j, h -> if (j == i) v.filterNot { c -> c in FORBIDDEN } else h } },
                                label = { Text("Hinweiswörter, mit Komma getrennt") },
                                placeholder = { Text(if (i == 0) "Chat, Handy, schreib*" else "Fernseh*, Radio, Zeitung") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    translations = translations + ""
                    hints = hints + ""
                },
                enabled = translations.size < MAX_MEANINGS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(8.dp))
                Text("Bedeutung hinzufügen")
            }
            if (problem != null && (word.isNotBlank() || !isNew)) {
                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { onSave(result) }, enabled = problem == null, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            if (!isNew) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Wort löschen")
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

/** characters of the storage format, not allowed in words, translations and hints */
private const val FORBIDDEN = "|:>\n\r"
private const val MAX_MEANINGS = 8
