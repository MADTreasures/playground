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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.graphics.Color
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

/** One language in a list of the word lists; [code] null = "every language of the original". */
data class WordListEntry(val code: String?, val name: String, val count: Int, val changed: Boolean = false)

private fun groupsLabel(n: Int) = if (n == 1) "1 Doppeldeutung" else "$n Doppeldeutungen"

private val Gold = Color(0xFFF2B600)

/** favourites first, then the languages with entries, then the rest (each in the given order) */
private fun LazyListScope.languageSections(
    entries: List<WordListEntry>,
    favorites: Set<String>,
    row: @Composable (WordListEntry) -> Unit,
) {
    val fav = entries.filter { it.code in favorites }
    val (filled, empty) = (entries - fav.toSet()).partition { it.count > 0 }
    listOf("Favoriten" to fav, "Mit Einträgen" to filled, "Weitere Sprachen" to empty).forEach { (title, list) ->
        if (list.isEmpty()) return@forEach
        item(key = "title-$title") { SectionTitle(title, Modifier.padding(horizontal = 16.dp)) }
        items(list, key = { "lang-${it.code}" }) { row(it) }
    }
}

@Composable
private fun LanguageRow(e: WordListEntry, favorite: Boolean, onOpen: () -> Unit, onFavorite: (() -> Unit)?, detail: String) {
    ListItem(
        headlineContent = { Text(e.name) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onFavorite != null) {
                    IconButton(onClick = onFavorite) {
                        if (favorite) {
                            Icon(Icons.Filled.Star, contentDescription = "${e.name}: Favorit entfernen", tint = Gold)
                        } else {
                            Icon(Icons.Outlined.StarBorder, contentDescription = "${e.name} als Favorit")
                        }
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

// ------------------------------------------------------------------ 1: the target languages

/** The word lists: one per target language (a file each); a star puts a language on top. */
@Composable
fun WordListsScreen(
    entries: List<WordListEntry>,
    favorites: Set<String>,
    onOpen: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Doppeldeutige Wörter",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Steht in einer Übersetzung ein Wort aus diesen Listen, erscheint darunter ein Knopf, z. B. " +
                        "„news → messages“. Finger auf den Knopf: das Wort leuchtet in der Übersetzung auf. " +
                        "Loslassen: es wird ersetzt. Wähle die Sprache der Übersetzung, dann die Sprache des Originals. " +
                        "Mit dem Stern kommt eine Sprache nach oben.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            languageSections(entries, favorites) { e ->
                val code = e.code ?: return@languageSections
                LanguageRow(
                    e.copy(name = if (code == "de") "${e.name} (auch Schweiz)" else e.name), code in favorites,
                    onOpen = { onOpen(code) }, onFavorite = { onFavorite(code) },
                    detail = when {
                        e.count == 0 -> "leer"
                        e.changed -> "Übersetzungen nach ${e.name} · ${groupsLabel(e.count)} · von dir bearbeitet"
                        else -> "Übersetzungen nach ${e.name} · ${groupsLabel(e.count)}"
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------- 2: the languages of the original

data class SourceLanguagesUiState(
    val code: String,
    val name: String,
    /** the languages of the original; the entry with code null: groups for every language */
    val entries: List<WordListEntry> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** the user changed the list (else it is the start list of the app) */
    val changed: Boolean = false,
    val hasDefaults: Boolean = false,
)

class SourceLanguagesActions(
    val onBack: () -> Unit = {},
    /** a language of the original; null = every language */
    val onOpen: (String?) -> Unit = {},
    val onFavorite: (String) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** The list of one target language (its file): one page per language of the original. */
@Composable
fun SourceLanguagesScreen(state: SourceLanguagesUiState, actions: SourceLanguagesActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "reset" or "clear"
    val total = state.entries.sumOf { it.count }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = state.name,
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Datei exportieren …") }, onClick = { menu = false; actions.onExport() })
                        DropdownMenuItem(text = { Text("Datei importieren …") }, onClick = { menu = false; actions.onImport() })
                        if (state.hasDefaults) {
                            DropdownMenuItem(
                                text = { Text("Startliste wiederherstellen") },
                                enabled = state.changed,
                                onClick = { menu = false; confirm = "reset" },
                            )
                        }
                        DropdownMenuItem(text = { Text("Alle löschen") }, enabled = total > 0, onClick = { menu = false; confirm = "clear" })
                    }
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("Übersetzungen nach ${state.name}: ${groupsLabel(total)}.") }
                        append(" Wähle die Sprache des Originals, z. B. Deutsch: dort stehen die deutschen Wörter mit ihren ")
                        append("Übersetzungen nach ${state.name}, die verwechselt werden können.")
                        if (state.code == "de") append(" Gilt auch für Deutsch (Schweiz), dort mit „ss“.")
                        if (!state.changed && total > 0) append(" Das ist die Startliste der App.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.entries.firstOrNull { it.code == null }?.let { all ->
                item(key = "all") {
                    LanguageRow(
                        all, favorite = false, onOpen = { actions.onOpen(null) }, onFavorite = null,
                        detail = if (all.count == 0) "Für Wörter, die bei jeder Sprache des Originals gelten" else "${groupsLabel(all.count)}, bei jeder Sprache des Originals",
                    )
                }
            }
            languageSections(state.entries.filter { it.code != null }, state.favorites) { e ->
                val code = e.code ?: return@languageSections
                LanguageRow(
                    e, code in state.favorites,
                    onOpen = { actions.onOpen(code) }, onFavorite = { actions.onFavorite(code) },
                    detail = if (e.count == 0) "leer" else groupsLabel(e.count),
                )
            }
        }
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
            text = "Alle ${groupsLabel(total)} für Übersetzungen nach ${state.name} werden gelöscht." +
                if (state.hasDefaults) " Die Startliste lässt sich später wiederherstellen." else "",
            confirm = "Löschen",
            onConfirm = { actions.onClear(); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

// ------------------------------------------------------------- 3: one pair of languages

data class WordListUiState(
    /** the target language (its file) */
    val code: String,
    val name: String,
    /** the language of the original; null = every language */
    val source: String?,
    val sourceName: String,
    /** the groups of this pair; null while loading */
    val groups: List<WordGroup>? = null,
)

class WordListActions(
    val onBack: () -> Unit = {},
    /** a change of the whole list of the target language, applied to the latest stored list */
    val onChange: ((List<WordGroup>) -> List<WordGroup>) -> Unit = {},
)

/** All ambiguous words of one pair, e.g. Deutsch → Englisch: "Bank: bench ↔ bank". */
@Composable
fun WordListScreen(state: WordListUiState, actions: WordListActions, modifier: Modifier = Modifier) {
    val groups = state.groups.orEmpty()
    val key = "${state.code}/${state.source}"
    var query by rememberSaveable(key) { mutableStateOf("") }
    // the group in the editor; NEW = add a group
    var editing by remember(key) { mutableStateOf<WordGroup?>(null) }
    var textMode by rememberSaveable(key) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val title = if (state.source == null) "Alle Sprachen → ${state.name}" else "${state.sourceName} → ${state.name}"
    val ofThisPair: (WordGroup) -> Boolean = { it.language == state.source }

    if (textMode) {
        WordListTextEditor(
            title = title, groups = groups,
            onSave = { text ->
                val parsed = WordGroups.parse(text).map { it.copy(language = state.source) }
                actions.onChange { list -> list.filterNot(ofThisPair) + parsed }
                textMode = false
            },
            onClose = { textMode = false },
            modifier = modifier,
        )
        return
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FluencyTopBar(
                title = title,
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Als Text bearbeiten") }, onClick = { menu = false; textMode = true })
                            DropdownMenuItem(
                                text = { Text("Alle auf dieser Seite löschen") },
                                enabled = groups.isNotEmpty(),
                                onClick = { menu = false; confirmClear = true },
                            )
                        }
                    }
                },
            )
            val shown = remember(groups, query) {
                val q = WordGroups.normalize(query.trim())
                groups.filter { g -> q.isEmpty() || WordGroups.normalize(WordGroups.formatLine(g)).contains(q) }
                    .sortedWith(compareBy(COLLATOR) { g -> g.sources.firstOrNull() ?: g.words.first().text })
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(if (groups.isEmpty()) "Noch keine Doppeldeutungen." else groupsLabel(groups.size) + ".")
                    }
                    append(
                        if (state.source == null) {
                            " Diese Wörter gelten für Übersetzungen nach ${state.name} aus jeder Sprache."
                        } else {
                            " Steht das Wort im Original (${state.sourceName}) und eine seiner Übersetzungen in der Übersetzung " +
                                "(${state.name}), lässt sie sich dort durch die anderen ersetzen."
                        },
                    )
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
                    if (state.source == null) {
                        "Tippe auf „Doppeldeutung“, um Wörter auf ${state.name} einzutragen, die verwechselt werden können."
                    } else {
                        "Tippe auf „Doppeldeutung“, um ein Wort auf ${state.sourceName} mit seinen Übersetzungen nach " +
                            "${state.name} einzutragen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown) { g ->
                    GroupRow(g, onClick = { editing = g })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = NEW },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Doppeldeutung") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { old ->
        val existing = old.takeIf { it !== NEW }
        GroupEditor(
            initial = existing,
            sourceName = state.sourceName.takeIf { state.source != null },
            targetName = state.name,
            onSave = { g ->
                val group = g.copy(language = state.source)
                actions.onChange { list -> if (existing == null) list + group else list.map { if (it == existing) group else it } }
                editing = null
            },
            onDelete = if (existing != null) ({ actions.onChange { list -> list.filter { it != existing } }; editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Alle löschen?",
            text = "Alle ${groupsLabel(groups.size)} der Seite „$title“ werden gelöscht.",
            confirm = "Löschen",
            onConfirm = { actions.onChange { list -> list.filterNot(ofThisPair) }; confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

/** alphabetical order that ignores case and accents ("Läufer" before "Leiter") */
private val COLLATOR = java.text.Collator.getInstance(java.util.Locale.GERMAN).apply { strength = java.text.Collator.PRIMARY }

/** marks "add a group" in the editor state */
private val NEW = WordGroup(emptyList())

@Composable
private fun GroupRow(g: WordGroup, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            if (g.sources.isNotEmpty()) g.sources.joinToString(", ") else "bei jedem Satz",
            style = MaterialTheme.typography.titleMedium,
            color = if (g.sources.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildAnnotatedString {
                g.words.forEachIndexed { i, w ->
                    if (i > 0) append("  ↔  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)) { append(w.text) }
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
    }
}

/** adding or changing one group: the word of the original and its translations that can be mixed up */
@Composable
private fun GroupEditor(
    initial: WordGroup?,
    /** null: the page for every language of the original */
    sourceName: String?,
    targetName: String,
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
        title = { Text(if (initial == null) "Neue Doppeldeutung" else "Doppeldeutung bearbeiten") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = sources,
                    onValueChange = { sources = it },
                    label = { Text(if (sourceName != null) "Wort auf $sourceName (im Original)" else "Wort im Original (optional)") },
                    supportingText = {
                        Text(
                            "Mehrere mit Komma, z. B. „Bank“ – Endungen werden mitgefunden. Leer: bei jedem Satz" +
                                if (sourceName != null) " aus dieser Sprache." else ".",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Übersetzungen nach $targetName, die verwechselt werden können. Mehrere Formen mit Komma, " +
                        "in jeder Übersetzung gleich geordnet, z. B. „bank, banks“ und „bench, benches“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEachIndexed { i, (forms, note) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = forms,
                                onValueChange = { rows[i] = it to note },
                                label = { Text("Übersetzung ${i + 1} (Formen mit Komma)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = note,
                                onValueChange = { rows[i] = forms to it },
                                label = { Text("Bedeutung (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (rows.size > 2) {
                            IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Default.Close, "Übersetzung ${i + 1} entfernen") }
                        }
                    }
                }
                TextButton(onClick = { rows.add("" to "") }) {
                    Icon(Icons.Default.Add, null)
                    Text("Übersetzung")
                }
                if (!valid) {
                    Text("Mindestens zwei Übersetzungen eintragen.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

/** the groups of one page as lines of the file */
@Composable
private fun WordListTextEditor(
    title: String,
    groups: List<WordGroup>,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(title) {
        mutableStateOf(
            "# $title: eine Zeile pro Doppeldeutung, z. B.\n# bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank\n" +
                groups.joinToString("") { WordGroups.formatLine(it) + "\n" },
        )
    }
    val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
    val parsed = lines.count { WordGroups.parseLine(it) != null }
    Column(modifier.fillMaxSize()) {
        FluencyTopBar(
            title = "Als Text",
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Abbrechen") } },
            actions = { TextButton(onClick = { onSave(text) }) { Text("Speichern") } },
        )
        Text(
            buildAnnotatedString {
                append("$title: ${groupsLabel(parsed)} erkannt.")
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

/** asks whether an imported file replaces the list or is added to it */
@Composable
fun ImportDialog(languageName: String, count: Int, onReplace: () -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datei übernehmen") },
        text = {
            Text(
                "Die Datei enthält ${groupsLabel(count)}. Ersetzen: die Liste für $languageName besteht danach nur aus " +
                    "der Datei. Ergänzen: neue Doppeldeutungen kommen dazu, vorhandene bleiben.",
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
