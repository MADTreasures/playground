package ch.madtreasures.fluency

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import ch.madtreasures.fluency.ui.settings.SettingsScreen
import ch.madtreasures.fluency.ui.settings.SettingsUiState
import ch.madtreasures.fluency.ui.text.ModelOption
import ch.madtreasures.fluency.ui.text.TextActions
import ch.madtreasures.fluency.ui.text.TextScreen
import ch.madtreasures.fluency.ui.text.TextViewModel
import ch.madtreasures.fluency.ui.theme.FluencyTheme
import kotlinx.coroutines.launch

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
            tab == Tab.CONVERSATION -> tab = previousTab
            else -> tab = Tab.LIVE
        }
    }
    Scaffold(
        bottomBar = {
            if (!fullScreen) {
                FluencyBottomBar(tab) { t ->
                    if (t == Tab.CONVERSATION) previousTab = tab
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
                tab == Tab.SETTINGS -> SettingsRoute(c, onBenchmark = { benchmark = true })
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
private fun SettingsRoute(c: AppContainer, onBenchmark: () -> Unit) {
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
    )
}
