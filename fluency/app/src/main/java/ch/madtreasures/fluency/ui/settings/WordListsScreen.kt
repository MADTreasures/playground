package ch.madtreasures.fluency.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.ui.components.FluencyTopBar
import ch.madtreasures.fluency.ui.components.SectionTitle
import ch.madtreasures.fluency.words.GroupWord
import ch.madtreasures.fluency.words.WordGroup
import ch.madtreasures.fluency.words.WordGroups

/** One language in the overview of the word lists. */
data class WordListEntry(val code: String, val name: String, val count: Int, val changed: Boolean)

/** The word lists: one per language, each opens its own window. */
@Composable
fun WordListsScreen(entries: List<WordListEntry>, onOpen: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val (filled, empty) = entries.partition { it.count > 0 }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Doppeldeutige Wörter",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Steht in einer Übersetzung ein Wort aus der Liste der Zielsprache, erscheint darunter ein Knopf, " +
                        "z. B. „news → messages“. Finger auf den Knopf: das Wort leuchtet in der Übersetzung auf. " +
                        "Loslassen: es wird ersetzt. Jede Sprache hat ihre eigene Liste (eine Textdatei).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (filled.isNotEmpty()) item { SectionTitle("Mit Einträgen", Modifier.padding(horizontal = 16.dp)) }
            items(filled, key = { it.code }) { e -> LanguageRow(e, onOpen) }
            if (empty.isNotEmpty()) item { SectionTitle("Weitere Sprachen", Modifier.padding(horizontal = 16.dp)) }
            items(empty, key = { it.code }) { e -> LanguageRow(e, onOpen) }
        }
    }
}

@Composable
private fun LanguageRow(e: WordListEntry, onOpen: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(if (e.code == "de") "${e.name} (auch Schweiz)" else e.name) },
        supportingContent = {
            Text(
                when {
                    e.count == 0 -> "leer"
                    e.changed -> "${groupsLabel(e.count)} · von dir bearbeitet"
                    else -> "${groupsLabel(e.count)} · Startliste"
                },
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable { onOpen(e.code) },
    )
}

private fun groupsLabel(n: Int) = if (n == 1) "1 Gruppe" else "$n Gruppen"

/** marks "add a group" in the editor state */
private val NEW = WordGroup(emptyList())

data class WordListUiState(
    val code: String,
    val name: String,
    /** null while loading */
    val groups: List<WordGroup>? = null,
    /** the user changed the list (else it is the start list of the app) */
    val changed: Boolean = false,
    val hasDefaults: Boolean = false,
)

class WordListActions(
    val onBack: () -> Unit = {},
    /** a change of the list, applied to the latest stored list */
    val onChange: ((List<WordGroup>) -> List<WordGroup>) -> Unit = {},
    val onSaveText: (String) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onReset: () -> Unit = {},
)

/** The window of one language: its groups, to view, search, add, change and delete. */
@Composable
fun WordListScreen(state: WordListUiState, actions: WordListActions, modifier: Modifier = Modifier) {
    val groups = state.groups.orEmpty()
    var query by rememberSaveable(state.code) { mutableStateOf("") }
    // the group in the editor; a group without words = a new one
    var editing by remember(state.code) { mutableStateOf<WordGroup?>(null) }
    var textMode by rememberSaveable(state.code) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "reset" or "clear"

    if (textMode) {
        WordListTextEditor(state, onSave = { actions.onSaveText(it); textMode = false }, onClose = { textMode = false }, modifier)
        return
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FluencyTopBar(
                title = state.name,
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Als Text bearbeiten") }, onClick = { menu = false; textMode = true })
                            DropdownMenuItem(text = { Text("Datei exportieren …") }, onClick = { menu = false; actions.onExport() })
                            DropdownMenuItem(text = { Text("Datei importieren …") }, onClick = { menu = false; actions.onImport() })
                            if (state.hasDefaults) {
                                DropdownMenuItem(
                                    text = { Text("Startliste wiederherstellen") },
                                    enabled = state.changed,
                                    onClick = { menu = false; confirm = "reset" },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Alle löschen") },
                                enabled = groups.isNotEmpty(),
                                onClick = { menu = false; confirm = "clear" },
                            )
                        }
                    }
                },
            )
            val shown = remember(groups, query) {
                val q = WordGroups.normalize(query.trim())
                groups.withIndex()
                    .filter { (_, g) -> q.isEmpty() || WordGroups.normalize(WordGroups.formatLine(g)).contains(q) }
                    .sortedBy { (_, g) -> g.words.first().text.lowercase() }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(if (groups.isEmpty()) "Noch keine Wörter." else groupsLabel(groups.size) + ".")
                    }
                    append(
                        " Steht ein Wort einer Gruppe in einer Übersetzung nach ${state.name}, kann man es unter der " +
                            "Übersetzung durch die anderen Wörter der Gruppe ersetzen.",
                    )
                    if (state.code == "de") append(" Gilt auch für Deutsch (Schweiz), dort mit „ss“.")
                    if (!state.changed && groups.isNotEmpty()) append(" Das ist die Startliste der App.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (groups.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Suchen") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Suche leeren") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.groups != null && groups.isEmpty()) {
                Text(
                    "Tippe auf „Gruppe“, um Wörter einzutragen, die beim Übersetzen nach ${state.name} verwechselt werden " +
                        "können – z. B. die Übersetzungen eines doppeldeutigen Wortes.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.index }) { (_, g) ->
                    GroupRow(g, onClick = { editing = g })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = NEW },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Gruppe") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { old ->
        val existing = old.takeIf { it !== NEW }
        GroupEditor(
            initial = existing,
            languageName = state.name,
            onSave = { g ->
                actions.onChange { list -> if (existing == null) list + g else list.map { if (it == existing) g else it } }
                editing = null
            },
            onDelete = if (existing != null) ({ actions.onChange { list -> list.filter { it != existing } }; editing = null }) else null,
            onDismiss = { editing = null },
        )
    }

    when (confirm) {
        "reset" -> ConfirmDialog(
            title = "Startliste wiederherstellen?",
            text = "Die Liste für ${state.name} wird wieder die Startliste der App. Deine Änderungen daran gehen verloren.",
            confirm = "Wiederherstellen",
            onConfirm = { actions.onReset(); confirm = null },
            onDismiss = { confirm = null },
        )
        "clear" -> ConfirmDialog(
            title = "Alle löschen?",
            text = "Alle ${groupsLabel(groups.size)} für ${state.name} werden gelöscht." +
                if (state.hasDefaults) " Die Startliste lässt sich später wiederherstellen." else "",
            confirm = "Löschen",
            onConfirm = { actions.onChange { emptyList() }; confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun GroupRow(g: WordGroup, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            buildAnnotatedString {
                g.words.forEachIndexed { i, w ->
                    if (i > 0) append("  ↔  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(w.text) }
                    if (w.forms.size > 1) append(", " + w.forms.drop(1).joinToString(", "))
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        val notes = g.words.map { it.note }
        if (notes.any { it.isNotEmpty() }) {
            Text(
                notes.joinToString("  ↔  ") { it.ifEmpty { "–" } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (g.sources.isNotEmpty()) {
            Text(
                "nur wenn im Original: " + g.sources.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** adding or changing one group */
@Composable
private fun GroupEditor(
    initial: WordGroup?,
    languageName: String,
    onSave: (WordGroup) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val rows = remember(initial) {
        mutableStateListOf<Pair<String, String>>().apply {
            initial?.words?.forEach { add(it.forms.joinToString(", ") to it.note) }
            while (size < 2) add("" to "")
        }
    }
    var sources by remember(initial) { mutableStateOf(initial?.sources?.joinToString(", ").orEmpty()) }
    val words = rows.map { (forms, note) ->
        GroupWord(WordGroups.splitList(forms).map(WordGroups::cleanTerm).filter { it.isNotEmpty() }, WordGroups.cleanNote(note))
    }.filter { it.forms.isNotEmpty() }
    val valid = words.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Neue Gruppe" else "Gruppe bearbeiten") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Wörter auf $languageName, die beim Übersetzen verwechselt werden können. Mehrere Formen eines Wortes " +
                        "mit Komma, in jedem Wort gleich geordnet, z. B. „bank, banks“ und „bench, benches“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEachIndexed { i, (forms, note) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = forms,
                                onValueChange = { rows[i] = it to note },
                                label = { Text("Wort ${i + 1} (Formen mit Komma)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = note,
                                onValueChange = { rows[i] = forms to it },
                                label = { Text("Erklärung (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (rows.size > 2) {
                            IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Default.Close, "Wort ${i + 1} entfernen") }
                        }
                    }
                }
                TextButton(onClick = { rows.add("" to "") }) {
                    Icon(Icons.Default.Add, null)
                    Text("Wort")
                }
                OutlinedTextField(
                    value = sources,
                    onValueChange = { sources = it },
                    label = { Text("Nur wenn im Original steht (optional)") },
                    supportingText = { Text("Wörter des Originaltexts mit Komma, z. B. „Bank“ – Endungen werden mitgefunden") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) {
                    Text("Mindestens zwei Wörter eintragen.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(WordGroup(words, WordGroups.splitList(sources).map(WordGroups::cleanTerm).filter { it.isNotEmpty() })) },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/** the list of one language as its text file */
@Composable
private fun WordListTextEditor(state: WordListUiState, onSave: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(state.code) {
        mutableStateOf(WordGroups.fileText("${state.name} (${state.code})", state.groups.orEmpty()))
    }
    val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
    val parsed = lines.count { WordGroups.parseLine(it) != null }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "${state.name} als Text",
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Abbrechen") } },
            actions = { TextButton(onClick = { onSave(text) }) { Text("Speichern") } },
        )
        Text(
            buildAnnotatedString {
                append("${groupsLabel(parsed)} erkannt.")
                if (lines.size > parsed) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                        append(" ${lines.size - parsed} Zeilen haben weniger als zwei Wörter und werden nicht übernommen.")
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** a short summary of the words of an imported file, for the question "replace or add?" */
@Composable
fun ImportDialog(languageName: String, count: Int, onReplace: () -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datei übernehmen") },
        text = {
            Text(
                "Die Datei enthält ${groupsLabel(count)}. Ersetzen: die Liste für $languageName besteht danach nur aus " +
                    "der Datei. Ergänzen: neue Gruppen kommen dazu, vorhandene bleiben.",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onReplace) { Text("Ersetzen") }
                TextButton(onClick = onAdd) { Text("Ergänzen") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
