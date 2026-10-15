package ch.madtreasures.fluency.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AmbiguousWord
import ch.madtreasures.fluency.engine.llm.AmbiguousWords
import ch.madtreasures.fluency.engine.llm.WordCollection
import ch.madtreasures.fluency.settings.AUTO
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import ch.madtreasures.fluency.ui.components.LanguagePicker
import ch.madtreasures.fluency.ui.components.SectionTitle
import ch.madtreasures.fluency.ui.components.languageLabel

/** the languages whose translations the editor always offers */
private val CORE = listOf("de", "en", "fr", "it", "es", "pt")

/**
 * The module "Mehrdeutige Wörter": the user's list, filled with the collection on first use. Every
 * change is saved at once ([onSave] gets the whole list in the storage format).
 * [initialEditing]: the entry being edited (-1 = a new one, null = the list).
 */
@Composable
fun AmbiguousWordsScreen(
    storage: String,
    defaultLanguage: String,
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
        val language = defaultLanguage.takeIf { it != AUTO }?.substringBefore('-') ?: "de"
        EntryEditor(
            entry = entry ?: AmbiguousWord("", language, emptyList()),
            isNew = entry == null,
            others = entries.filterIndexed { i, _ -> i != current },
            onSave = { e ->
                save(if (entry == null) listOf(e) + entries else entries.mapIndexed { i, old -> if (i == current) e else old })
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
    WordList(entries, save, onEdit = { editing = it }, onBack = onBack, modifier = modifier)
}

@Composable
private fun WordList(
    entries: List<AmbiguousWord>,
    save: (List<AmbiguousWord>) -> Unit,
    onEdit: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val missing = remember(entries) { AmbiguousWords.merge(entries, WordCollection.entries).size - entries.size }
    val languages = remember(entries) { entries.groupingBy { it.language.substringBefore('-') }.eachCount().entries.sortedByDescending { it.value } }
    val shown = remember(entries, query, language) {
        entries.withIndex().filter { (_, e) ->
            (language == null || e.language.substringBefore('-') == language) && (query.isBlank() || matches(e, query.trim()))
        }
    }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Mehrdeutige Wörter",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            actions = {
                IconButton(onClick = { onEdit(-1) }, enabled = entries.size < AmbiguousWords.MAX_ENTRIES) { Icon(Icons.Default.Add, "Wort hinzufügen") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Weitere Aktionen") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (missing > 0) "Sammlung ergänzen ($missing Wörter)" else "Sammlung vollständig") },
                            enabled = missing > 0,
                            onClick = {
                                menu = false
                                save(AmbiguousWords.merge(entries, WordCollection.entries))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Alle Wörter löschen") },
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                menu = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Wörter mit mehreren Bedeutungen, in allen Sprachen. Steht ein Hinweiswort im Satz, gibt die App dem " +
                        "Modell die Bedeutung vor und prüft das Ergebnis; sonst entscheidet das Modell und die App zeigt nur an, " +
                        "welche Bedeutung es genommen hat. Antippen unter der Übersetzung wechselt sie.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Suchen: Wort, Bedeutung, Übersetzung") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (languages.size > 1) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = language == null, onClick = { language = null }, label = { Text("Alle (${entries.size})") })
                        languages.forEach { (code, n) ->
                            FilterChip(selected = language == code, onClick = { language = if (language == code) null else code }, label = { Text("${languageLabel(code)} ($n)") })
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Die Liste ist leer.", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { save(WordCollection.entries) }) { Text("Sammlung übernehmen") }
                                OutlinedButton(onClick = { onEdit(-1) }) { Text("Eigenes Wort") }
                            }
                        }
                    }
                }
            }
            items(shown, key = { it.value.key }) { (i, e) -> EntryCard(e) { onEdit(i) } }
            if (entries.isNotEmpty() && shown.isEmpty()) {
                item { Text("Nichts gefunden.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                OutlinedButton(onClick = { onEdit(-1) }, enabled = entries.size < AmbiguousWords.MAX_ENTRIES, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Wort hinzufügen (${entries.size} / ${AmbiguousWords.MAX_ENTRIES})")
                }
            }
            item { Rules() }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Alle Wörter löschen?") },
            text = { Text("Die Liste wird leer. Die Sammlung kannst du danach mit „Sammlung übernehmen“ wieder holen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    save(emptyList())
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

private fun matches(e: AmbiguousWord, query: String): Boolean {
    val texts = listOf(e.word) + e.forms + e.meanings.flatMap { m -> listOf(m.label) + m.hints + m.translations.values.flatten() }
    return texts.any { it.contains(query, ignoreCase = true) }
}

@Composable
private fun Rules() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("So wählt die App die Bedeutung")
        Text(
            "Nur wenn ein eingetragenes Wort im Satz vorkommt – auch mit üblicher Endung („Bank“, „Bänke“, „Banken“; " +
                "„banca“, „banche“), aber nicht in zusammengesetzten Wörtern („Bankraub“). Deutsche Nomen nur gross " +
                "geschrieben („Steuer“, nicht „steuern“). Die erste Regel, die passt, gilt:\n" +
                "1. Du hast für diesen Satz eine Bedeutung angetippt.\n" +
                "2. Ein Hinweiswort genau einer Bedeutung steht im Satz.\n" +
                "3. Du hast in diesem Gespräch schon eine Bedeutung angetippt.\n" +
                "4. Wörter mit Standard: ein Hinweiswort im Satz davor, sonst die erste Bedeutung.\n" +
                "5. Sonst gibt die App nichts vor: das Modell entscheidet, die Übersetzung bleibt genau wie ohne Modul. " +
                "Unter der Übersetzung siehst du, welche Bedeutung es genommen hat – und eine Warnung, wenn ein " +
                "Hinweiswort im Satz davor dagegen spricht.\n" +
                "Im Gespräch zählt als „Satz davor“ die vorherige Äusserung samt ihrer Übersetzung.",
            style = MaterialTheme.typography.bodySmall,
        )
        SectionTitle("Was garantiert ist – und was nicht")
        Text(
            "• Eine vorgegebene Bedeutung wird geprüft. Fehlt sie, übersetzt die App ein zweites Mal mit deutlicherem " +
                "Hinweis; steht dann noch eine andere eingetragene Bedeutung im Text, wird sie ersetzt. Eine falsche " +
                "eingetragene Bedeutung bleibt so nie stehen.\n" +
                "• Steht die Vorgabe öfter in der Übersetzung als das Wort im Satz, hat sie noch ein anderes Wort ersetzt " +
                "(gemessen: „suitcases“ wurde auch zu „Kofferraum“). Dann nimmt die App die Übersetzung ohne Vorgabe.\n" +
                "• Nicht garantiert: ob ein Hinweiswort im Einzelfall passt, und der Rest des Satzes – den übersetzt " +
                "weiterhin das Modell.\n" +
                "• Wo eine Sprache für alle Bedeutungen dasselbe Wort hat (spanisch „banco“), lässt die App das Wort in Ruhe.\n" +
                "• MiLMMT-46 kennt keine Vorgaben: dort wird nur geprüft und nachkorrigiert.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryCard(e: AmbiguousWord, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.word, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    languageLabel(e.language) + if (e.standard) " · mit Standard" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            e.meanings.forEachIndexed { i, m ->
                val translations = m.translations.values.mapNotNull { it.firstOrNull() }.distinct().take(5).joinToString(", ")
                Text(
                    "${i + 1}. ${m.label}" + if (translations.isNotEmpty()) " – $translations" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == 0 && e.standard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (m.hints.isNotEmpty()) {
                    Text(
                        "    Hinweise: " + m.hints.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** why an entry cannot be saved, null if it can */
fun entryProblem(e: AmbiguousWord, others: List<AmbiguousWord>): String? {
    if (e.word.isBlank()) return "Das Wort fehlt."
    if (e.meanings.isEmpty()) return "Mindestens eine Bedeutung mit Übersetzung eintragen."
    e.meanings.firstOrNull { it.translations.isEmpty() }?.let { return "Bei „${it.label}“ fehlt eine Übersetzung." }
    if (others.any { it.key == e.key }) return "„${e.word}“ ist für diese Sprache schon eingetragen."
    val labels = e.meanings.map { it.label.lowercase() }
    labels.firstOrNull { l -> labels.count { it == l } > 1 }?.let { return "Die Bedeutung „$it“ steht zweimal da." }
    // a hint word decides only if it fits exactly one meaning ("Fern*" would also fit "Fernsehen")
    e.meanings.forEachIndexed { i, m ->
        for (h in m.hints) {
            if (!h.endsWith("*") && AmbiguousWords.isFormOf(e, h)) return "„$h“ ist eine Form des Worts selbst – als Hinweiswort entscheidet es nie."
            e.meanings.forEachIndexed { j, other ->
                val clash = if (i == j) null else other.hints.firstOrNull { AmbiguousWords.hintOccurs(h, it, e.language) }
                if (clash != null) {
                    return if (clash.equals(h, ignoreCase = true)) "Das Hinweiswort „$h“ steht bei mehreren Bedeutungen – so entscheidet es nichts."
                    else "Das Hinweiswort „$h“ passt auch zu „$clash“ (andere Bedeutung) – so entscheidet es nichts."
                }
            }
        }
    }
    return null
}

/** one meaning while it is edited: translations by language code, synonyms separated by "/" */
private data class MeaningDraft(val label: String = "", val hints: String = "", val translations: Map<String, String> = emptyMap())

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
    var language by remember { mutableStateOf(entry.language) }
    var word by remember { mutableStateOf(entry.word) }
    var forms by remember { mutableStateOf(entry.forms.joinToString(", ")) }
    var standard by remember { mutableStateOf(entry.standard) }
    var exact by remember { mutableStateOf(entry.exact) }
    var drafts by remember {
        mutableStateOf(
            entry.meanings.map { m -> MeaningDraft(m.label, m.hints.joinToString(", "), m.translations.mapValues { (_, v) -> v.joinToString(" / ") }) }
                .ifEmpty { listOf(MeaningDraft(), MeaningDraft()) },
        )
    }
    var extra by remember { mutableStateOf(entry.meanings.flatMap { it.translations.keys }.distinct().filter { it !in CORE }) }
    val shownLanguages = (CORE.filter { !AmbiguousWords.sameLanguage(it, language) } + extra).distinct()
    val result = AmbiguousWord(
        AmbiguousWords.cleanWord(word), language,
        drafts.mapNotNull { d ->
            val translations = shownLanguages.mapNotNull { code ->
                val texts = d.translations[code].orEmpty().split('/').map { AmbiguousWords.cleanWord(it) }.filter { it.isNotEmpty() }
                if (texts.isEmpty()) null else code to texts
            }.toMap()
            val label = AmbiguousWords.clean(d.label)
            if (label.isEmpty() && translations.isEmpty()) null
            else AmbiguousWord.Meaning(
                label.ifEmpty { translations.values.firstOrNull()?.first().orEmpty() },
                d.hints.split(',').map { AmbiguousWords.clean(it) }.filter { it.isNotEmpty() },
                translations,
            )
        },
        forms = forms.split(',').map { AmbiguousWords.cleanWord(it) }.filter { it.isNotEmpty() },
        standard = standard,
        exact = exact,
    )
    val problem = entryProblem(result, others)
    fun update(i: Int, f: (MeaningDraft) -> MeaningDraft) {
        drafts = drafts.mapIndexed { j, d -> if (j == i) f(d) else d }
    }
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
            LanguagePicker(language, { language = it }, label = "Sprache des Worts", modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = word,
                onValueChange = { word = it.filterNot { c -> c in FORBIDDEN || c == ',' || c == '/' || c == '*' } },
                label = { Text("Wort, z. B. Bank") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = forms,
                onValueChange = { forms = it.filterNot { c -> c in FORBIDDEN || c == '/' || c == '*' } },
                label = { Text("Weitere Formen, mit Komma (falls nötig)") },
                placeholder = { Text("z. B. Schlösser") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Setting(
                "Ohne Hinweis im Satz eine Bedeutung vorgeben",
                if (standard) "Ein Hinweiswort im Satz davor entscheidet, sonst die 1. Bedeutung." else "Aus: dann entscheidet das Modell, die App zeigt nur an.",
                standard,
            ) { standard = it }
            Setting("Nur genau diese Formen erkennen", "Keine Endungen (z. B. „glasses“, das nicht „glass“ treffen soll).", exact) { exact = it }
            SectionTitle("Bedeutungen")
            Text(
                "Hinweiswörter in der Sprache des Worts, mit Komma getrennt. Sie passen auch mit üblicher Endung; ein * am Ende " +
                    "passt zu jedem Wort, das so anfängt („Fernseh*“ → Fernsehen, Fernseher). Mehrere Übersetzungen mit / trennen: " +
                    "die erste wird vorgegeben, alle gelten als richtig.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            drafts.forEachIndexed { i, d ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}. Bedeutung" + if (i == 0 && standard) " · Standard" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (i == 0 && standard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (i > 0) TextButton(onClick = { drafts = listOf(d) + drafts.filterIndexed { j, _ -> j != i } }) { Text("Als erste") }
                            IconButton(onClick = { drafts = drafts.filterIndexed { j, _ -> j != i } }, enabled = drafts.size > 1) {
                                Icon(Icons.Default.Delete, "Bedeutung entfernen")
                            }
                        }
                        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = d.label,
                                onValueChange = { v -> update(i) { it.copy(label = v.filterNot { c -> c in FORBIDDEN }) } },
                                label = { Text("Bedeutung kurz, z. B. Sitzbank") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = d.hints,
                                onValueChange = { v -> update(i) { it.copy(hints = v.filterNot { c -> c in FORBIDDEN || c == '/' }) } },
                                label = { Text("Hinweiswörter") },
                                placeholder = { Text("z. B. im Park, sitzen, Garten") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            shownLanguages.forEach { code ->
                                OutlinedTextField(
                                    value = d.translations[code].orEmpty(),
                                    onValueChange = { v -> update(i) { it.copy(translations = it.translations + (code to v.filterNot { c -> c in FORBIDDEN || c == ',' || c == '*' })) } },
                                    label = { Text(languageLabel(code)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { drafts = drafts + MeaningDraft() }, enabled = drafts.size < MAX_MEANINGS, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Bedeutung")
                }
                LanguagePicker(
                    selected = "",
                    onSelect = { code -> if (code !in shownLanguages && !AmbiguousWords.sameLanguage(code, language) || code == "de-CH") extra = (extra + code).distinct() },
                    label = "Sprache hinzufügen",
                    modifier = Modifier.weight(1f),
                    languages = Languages.sortedForPicker.filter { it.code !in shownLanguages },
                )
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

@Composable
private fun Setting(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** characters of the storage format, not allowed in user input */
private const val FORBIDDEN = "|~;=\n\r"
private const val MAX_MEANINGS = 8
