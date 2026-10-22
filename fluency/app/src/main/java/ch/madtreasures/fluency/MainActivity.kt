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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.glasses.GlassesService
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
import ch.madtreasures.fluency.ui.settings.SourceLanguagesActions
import ch.madtreasures.fluency.ui.settings.SourceLanguagesScreen
import ch.madtreasures.fluency.ui.settings.SourceLanguagesUiState
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

    override fun onStart() {
        super.onStart()
        // The connection to the glasses runs in the foreground, started while Fluency is on screen: only so
        // Android lets it use the microphone later from the background.
        val settings = (application as FluencyApp).container.settingsRepository
        lifecycleScope.launch { if (settings.loaded().glasses) GlassesService.start(this@MainActivity) }
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
    // word lists in the settings: null = closed, else the page (see WordsRoute)
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
            tab == Tab.SETTINGS && words != null -> words = wordsBack(words!!)
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
                    onBack = { words = words?.let(::wordsBack) },
                )
                tab == Tab.SETTINGS -> SettingsRoute(c, onBenchmark = { benchmark = true }, onOpenWords = { words = "" })
            }
        }
    }
}

/** one page up in the word lists: "en/de" → "en" → "" → closed */
private fun wordsBack(path: String): String? = when {
    '/' in path -> path.substringBefore('/')
    path.isNotEmpty() -> ""
    else -> null
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
    val glassesStatus by c.glassesStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // the microphone for "Handy-Mikrofon", notifications for the service's notice; without them the watch's sound still works
    val askForGlasses = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        GlassesService.start(context)
    }
    SettingsScreen(
        state = SettingsUiState(
            settings = settings,
            translationModels = translation.ifEmpty { listOf(ModelOption(ModelCatalog.HY_MT2, "Hy-MT2 1.8B (nicht installiert)")) },
            asrModels = asr,
            versionInfo = "Fluency ${BuildConfig.VERSION_NAME} · ${NativeVersions.SUMMARY}",
            accel = AccelUi.from(accel, translation, settings.accel),
            glassesStatus = glassesStatus,
        ),
        onChange = c.settingsRepository::update,
        onBenchmark = onBenchmark,
        onRemeasure = c.translationEngine::remeasure,
        onUnblock = c.translationEngine::unblock,
        onOpenWords = onOpenWords,
        onGlasses = { on ->
            c.settingsRepository.update { it.copy(glasses = on) }
            if (on) {
                val wanted = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)
                val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) GlassesService.start(context) else askForGlasses.launch(missing.toTypedArray())
            }
        },
        onForgetCertificate = { c.settingsRepository.update { it.copy(glassesCert = "") } },
    )
}

/**
 * The word lists. [path]: "" = the target languages, "en" = the languages of the original for
 * translations into English, "en/de" = the page German → English ("*" instead of "de": every language).
 */
@Composable
private fun WordsRoute(c: AppContainer, path: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val store = c.wordGroups
    val version by store.changes.collectAsStateWithLifecycle()
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val favorites = settings.favoriteLanguages
    val toggleFavorite: (String) -> Unit = { code ->
        c.settingsRepository.update { s -> s.copy(favoriteLanguages = if (code in s.favoriteLanguages) s.favoriteLanguages - code else s.favoriteLanguages + code) }
    }
    val code = path.substringBefore('/')
    val name = Languages.byCode(code)?.nameDe ?: code
    when {
        path.isEmpty() -> {
            val entries by produceState(emptyList<WordListEntry>(), version) {
                value = withContext(Dispatchers.IO) {
                    WordGroupStore.languages.map { l -> WordListEntry(l.code, l.nameDe, store.groups(l.code).size, store.isChanged(l.code)) }
                }
            }
            WordListsScreen(entries, favorites, onOpen = onOpen, onFavorite = toggleFavorite, onBack = onBack)
        }
        '/' in path -> {
            val source = WordGroups.languageCode(path.substringAfter('/'))
            val sourceName = source?.let { Languages.byCode(it)?.nameDe ?: it } ?: "Alle Sprachen"
            val state by produceState(WordListUiState(code, name, source, sourceName), path, version) {
                value = withContext(Dispatchers.IO) {
                    WordListUiState(code, name, source, sourceName, store.groups(code).filter { it.language == source })
                }
            }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            WordListScreen(
                state,
                WordListActions(
                    onBack = onBack,
                    onChange = { change ->
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { store.update(code, change) } }.exceptionOrNull()?.let { e ->
                                Toast.makeText(context, "Fehler: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ),
            )
        }
        else -> SourceLanguagesRoute(c, code, name, version, favorites, toggleFavorite, onOpen, onBack)
    }
}

/** the window of one target language: languages of the original, export, import, start list */
@Composable
private fun SourceLanguagesRoute(
    c: AppContainer,
    code: String,
    name: String,
    version: Int,
    favorites: Set<String>,
    onFavorite: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val store = c.wordGroups
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by produceState(SourceLanguagesUiState(code, name), code, version) {
        value = withContext(Dispatchers.IO) {
            val groups = store.groups(code)
            val counts = groups.groupingBy { it.language }.eachCount()
            SourceLanguagesUiState(
                code, name,
                entries = listOf(WordListEntry(null, "Alle Sprachen", counts[null] ?: 0)) +
                    WordGroupStore.sourcesFor(code).map { l -> WordListEntry(l.code, l.nameDe, counts[l.code] ?: 0) },
                changed = store.isChanged(code),
                hasDefaults = store.hasDefaults(code),
            )
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
                    Toast.makeText(context, "Keine Doppeldeutungen in der Datei gefunden", Toast.LENGTH_LONG).show()
                } else {
                    imported = groups
                }
            }
        }
    }
    SourceLanguagesScreen(
        state.copy(favorites = favorites),
        SourceLanguagesActions(
            onBack = onBack,
            onOpen = { source -> onOpen("$code/${source ?: "*"}") },
            onFavorite = onFavorite,
            onExport = { export.launch("fluency-woerter-$code.txt") },
            onImport = { import.launch(arrayOf("text/*", "application/octet-stream")) },
            onReset = { inBackground { store.reset(code) } },
            onClear = { inBackground { store.update(code) { emptyList() } } },
        ),
    )
    imported?.let { groups ->
        ImportDialog(
            languageName = name,
            count = groups.size,
            onReplace = { imported = null; inBackground("${groups.size} Doppeldeutungen übernommen") { store.save(code, groups) } },
            onAdd = {
                imported = null
                scope.launch {
                    val added = withContext(Dispatchers.IO) { runCatching { store.add(code, groups) }.getOrDefault(0) }
                    Toast.makeText(context, if (added == 1) "1 neue Doppeldeutung" else "$added neue Doppeldeutungen", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { imported = null },
        )
    }
}
