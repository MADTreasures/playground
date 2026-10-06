package ch.madtreasures.fluency.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import ch.madtreasures.fluency.FluencyBottomBar
import ch.madtreasures.fluency.Tab
import ch.madtreasures.fluency.bench.AsrBench
import ch.madtreasures.fluency.bench.BenchReport
import ch.madtreasures.fluency.bench.MtBench
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Latency
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelState
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.settings.AUTO
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.ui.bench.BenchmarkScreen
import ch.madtreasures.fluency.ui.conversation.ConversationActions
import ch.madtreasures.fluency.ui.conversation.ConversationScreen
import ch.madtreasures.fluency.ui.conversation.ConversationUiState
import ch.madtreasures.fluency.ui.live.LiveActions
import ch.madtreasures.fluency.ui.live.LiveScreen
import ch.madtreasures.fluency.ui.live.LiveUiState
import ch.madtreasures.fluency.ui.models.ModelsActions
import ch.madtreasures.fluency.ui.models.ModelsScreen
import ch.madtreasures.fluency.ui.models.ModelsUiState
import ch.madtreasures.fluency.ui.settings.SettingsScreen
import ch.madtreasures.fluency.ui.settings.SettingsUiState
import ch.madtreasures.fluency.ui.text.ModelOption
import ch.madtreasures.fluency.ui.text.TextActions
import ch.madtreasures.fluency.ui.text.TextScreen
import ch.madtreasures.fluency.ui.text.TextUiState
import ch.madtreasures.fluency.ui.theme.FluencyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen with representative state (Robolectric native graphics, Galaxy-S-Ultra-like
 * window) and writes PNGs to docs/screenshots. Texts and numbers are taken from the integration
 * tests on the x86-64 build container (EndToEndTest, TranslationEngineIntegrationTest,
 * SpeechIntegrationTest) - they are not phone measurements.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("fluency.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { FluencyTheme(darkTheme = dark, dynamicColor = false) { content() } }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(outDir, "$name.png").length() > 10_000)
    }

    @Composable
    private fun Framed(tab: Tab, content: @Composable () -> Unit) {
        Scaffold(bottomBar = { FluencyBottomBar(tab) {} }) { p -> Box(Modifier.fillMaxSize().padding(p)) { content() } }
    }

    private val de = Languages.require("de")
    private val en = Languages.require("en")

    private val utterances = listOf(
        Utterance(1, 0, de, en, "Alles hat ein Ende, nur die Wurst hat zwei.", "Everything has an end, only the sausage has two.", true,
            Latency(asrMs = 816, mtMs = 799, totalMs = 1611, tokensPerSecond = 17.2, reusedAsr = true)),
        Utterance(2, 0, de, en, "Ich denke, wir sollten uns morgen am Bahnhof treffen.", "I think we should meet at the train station tomorrow.", true,
            Latency(asrMs = 294, mtMs = 0, totalMs = 702, reusedPartial = true)),
        Utterance(3, 0, de, en, "Kannst du mir sagen, wann der nächste Zug", "Can you tell me when the next train", false),
    )

    @Test fun live() = shoot("01_live") {
        Framed(Tab.LIVE) {
            LiveScreen(LiveUiState(source = "de", target = "en", running = true, speechActive = true, level = 0.7f, utterances = utterances), LiveActions())
        }
    }

    @Test fun liveDark() = shoot("02_live_dark", dark = true) {
        Framed(Tab.LIVE) {
            LiveScreen(LiveUiState(source = "de-CH", target = "fr", running = false, utterances = utterances.take(2), speak = true), LiveActions())
        }
    }

    @Test fun firstStart() = shoot("03_first_start") {
        Framed(Tab.LIVE) { LiveScreen(LiveUiState(needsSetup = true, setupBytes = 1_806_000_000L), LiveActions()) }
    }

    @Test fun conversation() = shoot("04_conversation") {
        val ch = Languages.require("de-CH")
        ConversationScreen(
            ConversationUiState(
                langA = "de-CH", langB = "en", running = true, activeSpeaker = 1, speechActive = true, level = 0.5f,
                utterances = listOf(
                    Utterance(1, 0, ch, en, "Grüezi! Wie kann ich Ihnen helfen?", "Hello! How can I help you?", true, Latency(110, 280, 690)),
                    Utterance(2, 1, en, ch, "I'm looking for the street to the old town.", "Ich suche die Strasse zur Altstadt.", true, Latency(95, 0, 470, reusedPartial = true)),
                    Utterance(3, 1, en, ch, "Is it far", "Ist es weit", false),
                ),
            ),
            ConversationActions(),
        )
    }

    @Test fun text() = shoot("05_text") {
        Framed(Tab.TEXT) {
            TextScreen(
                TextUiState(
                    source = AUTO, target = "de-CH",
                    input = "The street is very large. Greetings from the big city!",
                    output = "Die Strasse ist sehr gross. Grüsse aus der grossen Stadt!",
                    latency = Latency(null, 1210, 1214, tokensPerSecond = 16.4), modelName = "Hy-MT2 1.8B", detectedSource = "Englisch",
                    models = listOf(ModelOption(ModelCatalog.HY_MT2, "Hy-MT2 1.8B"), ModelOption(ModelCatalog.MILMMT_4B, "MiLMMT-46 4B")),
                ),
                TextActions(),
            )
        }
    }

    @Test fun models() = shoot("06_models") {
        val states = mapOf(
            ModelCatalog.HY_MT2 to ModelState.Installed,
            ModelCatalog.MILMMT_4B to ModelState.Downloading(1_120_000_000, 2_489_893_152, 38_500_000, false),
            ModelCatalog.MILMMT_1B to ModelState.Paused(300_000_000, 806_057_408),
            ModelCatalog.PARAKEET to ModelState.Installed,
            ModelCatalog.SILERO_VAD to ModelState.Installed,
        )
        Framed(Tab.MODELS) {
            ModelsScreen(ModelsUiState(ModelCatalog.builtIn, states, usedBytes = 1_806_000_000, freeBytes = 812_000_000_000), ModelsActions())
        }
    }

    @Test fun benchmark() = shoot("07_benchmark") {
        BenchmarkScreen(
            BenchReport(
                system = "Beispiel: x86-64-Build-Container, 4 vCPU (keine Handy-Messung)\nThreads: Übersetzung 4, Erkennung 4 · nur CPU",
                mt = listOf(
                    MtBench(ModelCatalog.HY_MT2, "Hy-MT2 1.8B", 761, 4, 1497.0, 153.0, 17.2, 965.0, "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme? → …"),
                    MtBench(ModelCatalog.MILMMT_4B, "MiLMMT-46 4B", 1848, 4, 2928.0, 66.0, 7.2, 2113.0, "Good morning, how are you? → God morgon, hur mår du?"),
                    MtBench(ModelCatalog.MILMMT_1B, "MiLMMT-46 1B", 564, 4, 901.0, 145.0, 24.6, 647.0, "Das Wetter ist heute wunderschön … → The weather is beautiful today; we are going swimming this afternoon."),
                ),
                asr = listOf(
                    AsrBench(ModelCatalog.PARAKEET, "Parakeet-TDT 0.6B v3", 2419, 6.6, 733, "[de] Alles hat ein Ende, nur die Wurst hat zwei.\n[en] Ask not what your country can do for you. Ask what you can do for your country."),
                    AsrBench(ModelCatalog.WHISPER_TURBO, "Whisper large-v3-turbo", 6293, 6.6, 2906, "[de] Alles hat ein Ende, nur die Wurst hat zwei.\n[en] Ask not what your country can do for you. Ask what you can do for your country."),
                ),
            ),
            onRun = {}, onCopy = {}, onBack = {},
        )
    }

    @Test fun settings() = shoot("08_settings") {
        Framed(Tab.SETTINGS) {
            SettingsScreen(
                SettingsUiState(
                    settings = AppSettings(),
                    translationModels = listOf(ModelOption(ModelCatalog.HY_MT2, "Hy-MT2 1.8B"), ModelOption(ModelCatalog.MILMMT_4B, "MiLMMT-46 4B")),
                    asrModels = listOf(ModelOption(ModelCatalog.PARAKEET, "Parakeet-TDT 0.6B v3")),
                    versionInfo = "Fluency 1.0.0",
                ),
                onChange = {}, onBenchmark = {},
            )
        }
    }
}
