package ch.madtreasures.fluency.bench

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.Wav
import ch.madtreasures.fluency.engine.asr.AsrEngine
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.models.ModelInfo
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class MtBench(
    val modelId: String,
    val name: String,
    val loadMs: Long,
    val sentences: Int,
    val avgMs: Double,
    val prefillTps: Double,
    val decodeTps: Double,
    /** same sentence again: prompt prefix comes from the KV cache (live partials) */
    val cachedMs: Double,
    val sample: String,
)

data class AsrBench(
    val modelId: String,
    val name: String,
    val loadMs: Long,
    val audioSeconds: Double,
    val decodeMs: Long,
    val text: String,
) {
    val rtf: Double get() = decodeMs / 1000.0 / audioSeconds
}

data class TtsBench(val name: String, val synthMs: Long, val audioSeconds: Double) {
    val rtf: Double get() = synthMs / 1000.0 / audioSeconds
}

data class BenchReport(
    val system: String = "",
    val mt: List<MtBench> = emptyList(),
    val asr: List<AsrBench> = emptyList(),
    val tts: List<TtsBench> = emptyList(),
    val running: Boolean = false,
    val step: String? = null,
    val error: String? = null,
) {
    fun asText(): String = buildString {
        appendLine("Fluency Benchmark")
        appendLine(system)
        if (mt.isNotEmpty()) appendLine("\nÜbersetzung:")
        mt.forEach {
            appendLine(
                "- ${it.name}: Laden ${it.loadMs} ms, Ø ${"%.0f".format(it.avgMs)} ms/Satz, Prompt ${"%.0f".format(it.prefillTps)} Tok/s, " +
                    "Ausgabe ${"%.1f".format(it.decodeTps)} Tok/s, mit KV-Cache ${"%.0f".format(it.cachedMs)} ms",
            )
        }
        if (asr.isNotEmpty()) appendLine("\nSpracherkennung:")
        asr.forEach {
            appendLine("- ${it.name}: Laden ${it.loadMs} ms, ${it.decodeMs} ms für ${"%.1f".format(it.audioSeconds)} s Audio (RTF ${"%.3f".format(it.rtf)})")
        }
        if (tts.isNotEmpty()) appendLine("\nSprachausgabe:")
        tts.forEach { appendLine("- ${it.name}: ${it.synthMs} ms für ${"%.1f".format(it.audioSeconds)} s (RTF ${"%.3f".format(it.rtf)})") }
    }
}

/** Fixed workload so numbers are comparable between models and devices. */
object BenchData {
    data class Sentence(val source: String, val target: String, val text: String)

    val sentences = listOf(
        Sentence("de", "en", "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme?"),
        Sentence("en", "de", "The meeting has been moved to Thursday afternoon because two colleagues are still on vacation."),
        Sentence("fr", "de", "Je voudrais réserver une table pour quatre personnes ce soir à vingt heures."),
        Sentence("it", "en", "Il treno per Milano è in ritardo di circa venti minuti a causa di un guasto tecnico."),
    )

    const val TTS_SENTENCE = "Guten Tag! Das ist ein kurzer Test der Sprachausgabe."
}

/** Runs the benchmark against everything that is installed. */
class Benchmark(
    private val translation: TranslationEngine,
    private val translationModels: () -> List<ModelInfo>,
    private val asrModels: () -> List<ModelInfo>,
    private val loadAsr: (ModelInfo) -> AsrEngine,
    private val releaseAsr: () -> Unit,
    private val testAudio: () -> List<Pair<String, Wav.Audio>>,
    private val tts: List<Pair<String, (String, Language) -> Pair<FloatArray, Int>?>>,
) {
    suspend fun run(system: String, update: (BenchReport) -> Unit) {
        var report = BenchReport(system = system, running = true)
        update(report)

        // ------------------------------------------------------------------ translation
        for (m in translationModels()) {
            coroutineContext.ensureActive()
            report = report.copy(step = "Übersetzung: ${m.name} …")
            update(report)
            translation.unloadAll()
            val pairs = BenchData.sentences.filter { m.supports(it.source) && m.supports(it.target) }
            if (pairs.isEmpty()) continue
            val first = pairs.first()
            val t0 = System.nanoTime()
            // load + warm-up (not part of the sentence numbers)
            translation.translate(req(first, m.id, "Hallo."))
            val loadMs = translation.loadMillis(m.id) ?: ((System.nanoTime() - t0) / 1_000_000)
            var ms = 0.0
            var prefillTok = 0
            var prefillMs = 0.0
            var genTok = 0
            var decodeMs = 0.0
            var sample = ""
            for (s in pairs) {
                coroutineContext.ensureActive()
                translation.resetCaches()
                val r = translation.translate(req(s, m.id))
                ms += r.wallMs
                prefillTok += r.promptTokens - r.reusedTokens
                prefillMs += r.prefillMs
                genTok += r.generatedTokens
                decodeMs += r.decodeMs
                if (sample.isEmpty()) sample = "${s.text} → ${r.text}"
            }
            // repeat the last sentence: the whole prompt prefix is cached
            val cached = translation.translate(req(pairs.last(), m.id))
            report = report.copy(
                mt = report.mt + MtBench(
                    m.id, m.name, loadMs, pairs.size, ms / pairs.size,
                    if (prefillMs > 0) prefillTok * 1000 / prefillMs else 0.0,
                    if (decodeMs > 0) genTok * 1000 / decodeMs else 0.0,
                    cached.wallMs.toDouble(), sample,
                ),
            )
            update(report)
        }

        // ------------------------------------------------------------------ speech recognition
        val clips = runCatching { testAudio() }.getOrDefault(emptyList())
        if (clips.isNotEmpty()) {
            for (m in asrModels()) {
                coroutineContext.ensureActive()
                report = report.copy(step = "Spracherkennung: ${m.name} …")
                update(report)
                releaseAsr()
                val t0 = System.nanoTime()
                val engine = loadAsr(m)
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                // warm-up
                engine.transcribe(clips.first().second.samples.copyOf(SAMPLE_RATE), null)
                var total = 0L
                var secs = 0.0
                val texts = mutableListOf<String>()
                for ((lang, clip) in clips) {
                    val l = Languages.byCode(lang)
                    if (!m.supports(lang) && !m.custom && m.languages.isNotEmpty()) continue
                    val r = engine.transcribe(clip.samples, l)
                    total += r.millis
                    secs += clip.seconds
                    texts += "[$lang] ${r.text}"
                }
                if (secs > 0) {
                    report = report.copy(asr = report.asr + AsrBench(m.id, m.name, loadMs, secs, total, texts.joinToString("\n")))
                    update(report)
                }
            }
        }

        // ------------------------------------------------------------------ speech output
        for ((name, synth) in tts) {
            coroutineContext.ensureActive()
            val de = Languages.require("de")
            val t0 = System.nanoTime()
            val audio = runCatching { synth(BenchData.TTS_SENTENCE, de) }.getOrNull() ?: continue
            val ms = (System.nanoTime() - t0) / 1_000_000
            report = report.copy(tts = report.tts + TtsBench(name, ms, audio.first.size.toDouble() / audio.second))
            update(report)
        }

        update(report.copy(running = false, step = null))
    }

    private fun req(s: BenchData.Sentence, modelId: String, text: String = s.text) = TranslationEngine.Request(
        text = text,
        source = Languages.require(s.source),
        target = Languages.require(s.target),
        role = TranslationEngine.Role.TEXT,
        modelId = modelId,
    )
}
