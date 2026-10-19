package ch.madtreasures.fluency

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelKind
import ch.madtreasures.fluency.ui.bench.BenchmarkScreen
import ch.madtreasures.fluency.ui.bench.BenchmarkViewModel
import ch.madtreasures.fluency.ui.conversation.ConversationActions
import ch.madtreasures.fluency.ui.conversation.ConversationScreen
import ch.madtreasures.fluency.ui.conversation.ConversationViewModel
import ch.madtreasures.fluency.ui.live.LiveActions
import ch.madtreasures.fluency.ui.live.LiveScreen
import ch.madtreasures.fluency.ui.live.LiveViewModel
import ch.madtreasures.fluency.ui.models.ModelsActions
import ch.madtreasures.fluency.ui.models.ModelsScreen
import ch.madtreasures.fluency.ui.models.ModelsViewModel
import ch.madtreasures.fluency.ui.settings.AccelUi
import ch.madtreasures.fluency.ui.settings.ImportDialog
import ch.madtreasures.fluency.ui.settings.WordListActions
import ch.madtreasures.fluency.ui.settings.WordListEntry
import ch.madtreasures.fluency.ui.settings.WordListScreen
import ch.madtreasures.fluency.ui.settings.WordListUiState
import ch.madtreasures.fluency.ui.settings.WordListsScreen
import ch.madtreasures.fluency.ui.settings.SettingsScreen
import ch.madtreasures.fluency.ui.settings.SettingsUiState
import ch.madtreasures.fluency.ui.text.ModelOption
import ch.madtreasures.fluency.ui.text.TextActions
import ch.madtreasures.fluency.ui.text.TextScreen
import ch.madtreasures.fluency.ui.text.TextViewModel
import ch.madtreasures.fluency.ui.theme.FluencyTheme
import ch.madtreasures.fluency.words.WordGroup
import ch.madtreasures.fluency.words.WordGroupStore
import ch.madtreasures.fluency.words.WordGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FluencyApp).container
        setContent { FluencyTheme { FluencyRoot(container) } }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Default.Mic),
    CONVERSATION("Gespräch", Icons.Default.Forum),
    TEXT("Text", Icons.Default.Translate),
    MODELS("Modelle", Icons.Default.Inventory2),
    SETTINGS("Optionen", Icons.Default.Settings),
}

@Composable
fun FluencyRoot(c: AppContainer) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var previousTab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var benchmark by rememberSaveable { mutableStateOf(false) }
    // word lists in the settings: null = closed, "" = all languages, else the language code
    var words by rememberSaveable { mutableStateOf<String?>(null) }
    val factory = remember(c) {
        viewModelFactory {
            initializer { LiveViewModel(c) }
            initializer { ConversationViewModel(c) }
            initializer { TextViewModel(c) }
            initializer { ModelsViewModel(c) }
            initializer { BenchmarkViewModel(c) }
        }
    }
    val fullScreen = tab == Tab.CONVERSATION || benchmark
    BackHandler(enabled = benchmark || tab != Tab.LIVE) {
        when {
            benchmark -> benchmark = false
            tab == Tab.SETTINGS && words != null -> words = if (words == "") null else ""
            tab == Tab.CONVERSATION -> tab = previousTab
            else -> tab = Tab.LIVE
        }
    }
    Scaffold(
        bottomBar = {
            if (!fullScreen) {
                FluencyBottomBar(tab) { t ->
                    if (t == Tab.CONVERSATION) previousTab = tab
                    if (t == Tab.SETTINGS && tab == Tab.SETTINGS) words = null
                    tab = t
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                benchmark -> BenchmarkRoute(viewModel(factory = factory), onBack = { benchmark = false })
                tab == Tab.LIVE -> LiveRoute(viewModel(factory = factory), onOpenModels = { tab = Tab.MODELS })
                tab == Tab.CONVERSATION -> ConversationRoute(viewModel(factory = factory), onClose = { tab = previousTab })
                tab == Tab.TEXT -> TextRoute(viewModel(factory = factory))
                tab == Tab.MODELS -> ModelsRoute(viewModel(factory = factory), onBenchmark = { benchmark = true })
                tab == Tab.SETTINGS && words != null -> WordsRoute(
                    c, words.orEmpty(),
                    onOpen = { words = it },
                    onBack = { words = if (words == "") null else "" },
                )
                tab == Tab.SETTINGS -> SettingsRoute(c, onBenchmark = { benchmark = true }, onOpenWords = { words = "" })
            }
        }
    }
}

@Composable
fun FluencyBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = selected == t,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(t.label, maxLines = 1) },
            )
        }
    }
}

/** Runs [action] once RECORD_AUDIO is granted (asks if needed). */
@Composable
private fun rememberWithMicPermission(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else onDenied()
        pending = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Asks for the notification permission (download progress) the first time a download starts. */
@Composable
private fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Fluency", text))) } }
}

@Composable
private fun LiveRoute(vm: LiveViewModel, onOpenModels: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val withMic = rememberWithMicPermission { permissionError = "Ohne Mikrofon-Berechtigung keine Spracherkennung" }
    val askNotifications = rememberNotificationPermission()
    val copy = rememberCopy()
    KeepScreenOn(state.running && state.keepScreenOn)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }
    LiveScreen(
        state = state.copy(error = state.error ?: permissionError),
        actions = LiveActions(
            onStart = { permissionError = null; withMic { vm.start() } },
            onStop = vm::stop,
            onSource = vm::setSource,
            onTarget = vm::setTarget,
            onSwap = vm::swap,
            onToggleSpeak = vm::toggleSpeak,
            onClear = vm::clear,
            onSpeak = vm::speak,
            onCopy = { copy(it.translation) },
            onReplaceWord = vm::replaceWord,
            onDownloadRecommended = { askNotifications(); vm.downloadRecommended(); onOpenModels() },
            onDismissSetup = vm::dismissSetup,
            onOpenModels = onOpenModels,
        ),
    )
}

@Composable
private fun ConversationRoute(vm: ConversationViewModel, onClose: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val withMic = rememberWithMicPermission { permissionError = "Ohne Mikrofon-Berechtigung keine Spracherkennung" }
    KeepScreenOn(state.keepScreenOn)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }
    ConversationScreen(
        state = state.copy(error = state.error ?: permissionError),
        actions = ConversationActions(
            onMic = { speaker -> permissionError = null; withMic { vm.onMic(speaker) } },
            onLangA = vm::setLangA,
            onLangB = vm::setLangB,
            onSwap = vm::swap,
            onToggleSpeak = vm::toggleSpeak,
            onClear = vm::clear,
            onClose = { vm.stop(); onClose() },
            onReplaceWord = vm::replaceWord,
        ),
    )
}

@Composable
private fun TextRoute(vm: TextViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val copy = rememberCopy()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextScreen(
        state = state,
        actions = TextActions(
            onInput = vm::onInput,
            onTranslate = { vm.translate() },
            onCancel = vm::cancel,
            onClear = vm::clear,
            onPaste = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrEmpty()) vm.onInput(if (state.input.isBlank()) text else state.input + "\n" + text)
                }
            },
            onCopy = { copy(state.output) },
            onSpeak = vm::speakOutput,
            onSource = vm::setSource,
            onTarget = vm::setTarget,
            onSwap = vm::swap,
            onModel = vm::setModel,
            onTypingMode = vm::setTranslateWhileTyping,
            onReplaceWord = vm::replaceWord,
        ),
    )
}

@Composable
private fun ModelsRoute(vm: ModelsViewModel, onBenchmark: () -> Unit) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberNotificationPermission()
    var importTarget by remember { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importTarget?.let { vm.importFiles(it, uris, context.contentResolver) }
        importTarget = null
    }
    val pickGguf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.importCustom(uri, context.contentResolver, whisper = false)
    }
    val pickWhisper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.importCustom(uri, context.contentResolver, whisper = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }
    ModelsScreen(
        state = state,
        actions = ModelsActions(
            onDownload = { askNotifications(); vm.download(it) },
            onPause = vm::pause,
            onDelete = vm::delete,
            onImportFiles = { id -> importTarget = id; pickFiles.launch(arrayOf("*/*")) },
            onImportGguf = { pickGguf.launch(arrayOf("*/*")) },
            onImportWhisper = { pickWhisper.launch(arrayOf("*/*")) },
            onDownloadRecommended = { askNotifications(); vm.downloadRecommended() },
            onBenchmark = onBenchmark,
            onDismissMessage = vm::dismissMessage,
        ),
    )
}

@Composable
private fun BenchmarkRoute(vm: BenchmarkViewModel, onBack: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val copy = rememberCopy()
    BenchmarkScreen(report = report, onRun = vm::run, onCopy = { copy(report.asText()) }, onBack = onBack)
}

@Composable
private fun SettingsRoute(c: AppContainer, onBenchmark: () -> Unit, onOpenWords: () -> Unit) {
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val states by c.modelManager.states.collectAsStateWithLifecycle()
    val accel by c.translationEngine.accelStatus.collectAsStateWithLifecycle()
    val translation = remember(states) { c.modelManager.installed(ModelKind.TRANSLATION).map { ModelOption(it.id, it.name) } }
    val asr = remember(states) { c.modelManager.installed(ModelKind.ASR).map { ModelOption(it.id, it.name) } }
    SettingsScreen(
        state = SettingsUiState(
            settings = settings,
            translationModels = translation.ifEmpty { listOf(ModelOption(ModelCatalog.HY_MT2, "Hy-MT2 1.8B (nicht installiert)")) },
            asrModels = asr,
            versionInfo = "Fluency ${BuildConfig.VERSION_NAME} · ${NativeVersions.SUMMARY}",
            accel = AccelUi.from(accel, translation, settings.accel),
        ),
        onChange = c.settingsRepository::update,
        onBenchmark = onBenchmark,
        onRemeasure = c.translationEngine::remeasure,
        onUnblock = c.translationEngine::unblock,
        onOpenWords = onOpenWords,
    )
}

/** the word lists: [code] "" = overview of the languages, else the window of that language */
@Composable
private fun WordsRoute(c: AppContainer, code: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val store = c.wordGroups
    val version by store.changes.collectAsStateWithLifecycle()
    if (code.isEmpty()) {
        val entries by produceState(emptyList<WordListEntry>(), version) {
            value = withContext(Dispatchers.IO) {
                WordGroupStore.languages.map { l -> WordListEntry(l.code, l.nameDe, store.groups(l.code).size, store.isChanged(l.code)) }
            }
        }
        WordListsScreen(entries, onOpen, onBack)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = Languages.byCode(code)?.nameDe ?: code
    val state by produceState(WordListUiState(code, name), code, version) {
        value = withContext(Dispatchers.IO) {
            WordListUiState(code, name, store.groups(code), store.isChanged(code), store.hasDefaults(code))
        }
    }
    fun inBackground(done: String? = null, work: () -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(work).exceptionOrNull() }
            val text = if (error != null) "Fehler: ${error.message ?: error.javaClass.simpleName}" else done
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    var imported by remember { mutableStateOf<List<WordGroup>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            inBackground("Datei gespeichert") {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.text(code).toByteArray()) }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val groups = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)!!.use { WordGroups.parse(it.readBytes().decodeToString()) }
                    }.getOrDefault(emptyList())
                }
                if (groups.isEmpty()) {
                    Toast.makeText(context, "Keine Wortgruppen in der Datei gefunden", Toast.LENGTH_LONG).show()
                } else {
                    imported = groups
                }
            }
        }
    }
    WordListScreen(
        state = state,
        actions = WordListActions(
            onBack = onBack,
            onChange = { change -> inBackground { store.update(code, change) } },
            onSaveText = { text -> inBackground { store.saveText(code, text) } },
            onExport = { export.launch("fluency-woerter-$code.txt") },
            onImport = { import.launch(arrayOf("text/*", "application/octet-stream")) },
            onReset = { inBackground { store.reset(code) } },
        ),
    )
    imported?.let { groups ->
        ImportDialog(
            languageName = name,
            count = groups.size,
            onReplace = { imported = null; inBackground("${groups.size} Gruppen übernommen") { store.save(code, groups) } },
            onAdd = {
                imported = null
                scope.launch {
                    val added = withContext(Dispatchers.IO) { runCatching { store.add(code, groups) }.getOrDefault(0) }
                    Toast.makeText(context, if (added == 1) "1 neue Gruppe" else "$added neue Gruppen", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { imported = null },
        )
    }
}
