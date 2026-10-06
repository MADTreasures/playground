package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.LanguageGuesser
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.TextNormalizer
import ch.madtreasures.fluency.core.TextSegmenter
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Where the installed models and their files are (implemented by ModelManager in the app). */
interface TranslationModelSource {
    fun installedTranslationModels(): List<ModelInfo>
    fun modelPath(info: ModelInfo): String
}

class NoModelException(message: String) : IOException(message)

/** llama.cpp reported an error while generating (not a cancellation, not a too long prompt). */
class GenerationException(message: String) : IOException(message)

/** Loads a model file ([LlamaModel] in the app, fakes in tests). */
fun interface SessionLoader {
    fun load(path: String, params: LlamaModel.LoadParams): LlmSession?
}

/**
 * Picks a translation model for a request, keeps loaded models warm (one thread per model) and
 * runs the translation with streaming output.
 *
 * CPU or GPU ([AppSettings.accel]): in automatic mode every model is measured once per device
 * and app version on both processors in the background ([AccelChoice]) while it keeps
 * translating on the CPU; if the GPU wins, it takes over without a pause. Risky GPU steps are
 * crash-guarded ([GpuGuard]); a GPU error moves the model back to the CPU.
 */
class TranslationEngine(
    private val source: TranslationModelSource,
    private val settings: () -> AppSettings,
    private val nativeLibDir: String?,
    private val maxLoaded: Int = 3,
    val acceleration: Acceleration = Acceleration(),
    private val loader: SessionLoader = SessionLoader { path, params -> LlamaModel.load(path, params) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val initBackends: () -> String = {
        LlamaNative.load()
        LlamaNative.nativeInitBackends(nativeLibDir)
    },
    /** a background measurement starts after this much time without a translation */
    idleMillis: Long = 1_500,
) {
    enum class Role { LIVE, FINAL, TEXT }

    data class Request(
        val text: String,
        val source: Language?,
        val target: Language,
        val role: Role,
        /** force a model (benchmark) */
        val modelId: String? = null,
        /** force CPU or GPU (benchmark) */
        val processor: Processor? = null,
    )

    data class Result(
        val text: String,
        val modelId: String,
        val modelName: String,
        val sourceUsed: Language?,
        val wallMs: Long,
        val promptTokens: Int,
        val reusedTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val cancelled: Boolean,
        val processor: Processor = Processor.CPU,
    ) {
        val tokensPerSecond: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
    }

    /** CPU/GPU state for the settings screen. */
    data class AccelStatus(
        val gpuName: String? = null,
        /** why the GPU is not used (no GPU, blocked after a crash) */
        val problem: String? = null,
        val blocked: Boolean = false,
        val decisions: Map<String, AccelStore.Decision> = emptyMap(),
        /** models being measured on CPU and GPU right now */
        val measuring: Set<String> = emptySet(),
        /** loaded models and where they run */
        val active: Map<String, Processor> = emptyMap(),
        /** GPU failures in this process (model id → reason); these models stay on the CPU */
        val failed: Map<String, String> = emptyMap(),
    )

    private class Loaded(val info: ModelInfo, @Volatile var model: LlmSession) {
        /** every native call of this model runs on this one thread, in order */
        val dispatcher: CoroutineDispatcher = serialDispatcher("llm-${info.id}")
        @Volatile var lastUsed = System.nanoTime()
        @Volatile var runningRole: Role? = null

        /** the first GPU run of an instance is crash-guarded */
        @Volatile var gpuChecked = false
        val processor: Processor get() = if (model.usesGpu) Processor.GPU else Processor.CPU
    }

    private class Measurement(val ms: Long, val texts: List<String>, val error: String? = null)

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val loadMutex = Mutex()

    /** background measurements and processor switches: one at a time, never during the benchmark */
    private val jobMutex = Mutex()
    private val pendingJobs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val measuring: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val measureAttempts = ConcurrentHashMap<String, Int>()
    private val gpuFailed = ConcurrentHashMap<String, String>()

    private val idleNanos = idleMillis * 1_000_000
    private val activity = AtomicLong()
    @Volatile private var lastActivity = System.nanoTime() - idleNanos

    private val accel = MutableStateFlow(AccelStatus())
    val accelStatus: StateFlow<AccelStatus> = accel

    @Volatile
    var backendInfo: String = ""
        private set

    @Volatile
    private var backendsReady = false

    init {
        publishAccel()
    }

    /** Loads the native libraries and the CPU backend (once per process). */
    @Synchronized
    fun ensureBackends(): String {
        if (!backendsReady) {
            backendInfo = initBackends()
            backendsReady = true
        }
        return backendInfo
    }

    fun systemInfo(): String {
        ensureBackends()
        return LlamaNative.nativeSystemInfo()
    }

    /** Loads the GPU backend if necessary. The GPU name, or null if there is none to use. */
    suspend fun gpu(): String? = withContext(Dispatchers.IO) {
        ensureBackends()
        acceleration.gpu()
    }.also { publishAccel() }

    /** Chooses the model for [role]; null if none installed supports the language pair. */
    fun pickModel(role: Role, src: Language?, tgt: Language, forcedId: String? = null): ModelInfo? {
        val installed = source.installedTranslationModels()
        fun ok(m: ModelInfo): Boolean =
            m.supports(tgt.code) && (src == null || m.supports(src.code)) &&
                !(src == null && m.promptStyle?.let(PromptFormat::needsSource) == true &&
                    installed.any { it.promptStyle?.let(PromptFormat::needsSource) == false && it.supports(tgt.code) })
        if (forcedId != null) return installed.firstOrNull { it.id == forcedId }
        val s = settings()
        val preferred = when (role) {
            Role.LIVE -> s.liveModelId
            Role.FINAL -> s.finalModelId.ifEmpty { s.liveModelId }
            Role.TEXT -> s.textModelId
        }
        installed.firstOrNull { it.id == preferred && ok(it) }?.let { return it }
        val candidates = installed.filter(::ok)
        return when (role) {
            Role.LIVE -> candidates.maxWithOrNull(compareBy<ModelInfo> { it.speedRank }.thenBy { it.qualityRank })
            Role.FINAL, Role.TEXT -> candidates.maxWithOrNull(compareBy<ModelInfo> { it.qualityRank }.thenBy { it.speedRank })
        }
    }

    /** Loads [ids] in the background so the first translation is fast. */
    suspend fun preload(ids: Collection<String>) {
        val infos = source.installedTranslationModels().filter { it.id in ids }
        for (info in infos) runCatching { get(info) }
    }

    // ------------------------------------------------------------------------------ loading

    /** Where [info] should run now (blocking: may load the GPU backend on the first call). */
    private fun plannedProcessor(info: ModelInfo): Processor {
        val wantGpu = when (settings().accel) {
            AccelMode.CPU -> false
            AccelMode.GPU -> true
            AccelMode.AUTO -> acceleration.store.decision(info.id)?.processor == Processor.GPU
        }
        return if (wantGpu && !gpuFailed.containsKey(info.id) && acceleration.gpu() != null) Processor.GPU else Processor.CPU
    }

    private suspend fun get(info: ModelInfo, forced: Processor? = null): Loaded {
        val l = loadMutex.withLock { getLocked(info, forced) }
        l.lastUsed = System.nanoTime()
        if (forced == null) maybeMeasure(info)
        return l
    }

    private suspend fun getLocked(info: ModelInfo, forced: Processor?): Loaded {
        val want = forced ?: withContext(Dispatchers.IO) {
            ensureBackends()
            plannedProcessor(info)
        }
        val current = loaded[info.id]
        if (current != null) {
            if (current.processor == want) return current
            if (want == Processor.GPU && forced == null) {
                // the first GPU load compiles the kernels (seconds): the CPU instance keeps
                // translating until the GPU instance is ready
                scheduleJob(info.id) { switchToGpu(info) }
                return current
            }
            replace(current, loadSession(info, want) ?: throw IOException(failedOn(info, want)))
            return current
        }
        evictForOneMore()
        val model = loadSession(info, want)
            ?: (if (want == Processor.GPU && forced == null) loadSession(info, Processor.CPU) else null)
            ?: throw IOException(failedOn(info, want))
        return Loaded(info, model).also {
            loaded[info.id] = it
            publishAccel()
        }
    }

    private fun failedOn(info: ModelInfo, p: Processor): String =
        if (p == Processor.GPU) "${info.name} konnte nicht auf der GPU geladen werden" +
            (gpuFailed[info.id] ?: acceleration.problem())?.let { " ($it)" }.orEmpty()
        else "${info.name} konnte nicht geladen werden"

    /** Loads [info] on exactly [on]; null if that fails (a GPU failure is remembered for this process). */
    private suspend fun loadSession(info: ModelInfo, on: Processor): LlmSession? = withContext(Dispatchers.IO) {
        ensureBackends()
        val s = settings()
        val params = LlamaModel.LoadParams(
            contextSize = 2048, batchSize = 512,
            threads = s.llmThreads, threadsBatch = s.llmThreads, useGpu = on == Processor.GPU,
        )
        val path = source.modelPath(info)
        if (on == Processor.CPU) return@withContext loader.load(path, params)
        if (acceleration.gpu() == null) return@withContext null
        val m = acceleration.guarded("${info.name} auf die GPU laden") { loader.load(path, params) }
        when {
            m == null -> {
                gpuFailed[info.id] = "Laden auf der GPU fehlgeschlagen"
                null
            }
            !m.usesGpu -> {
                m.close()
                gpuFailed[info.id] = "GPU nicht gefunden"
                null
            }
            else -> m
        }.also { publishAccel() }
    }

    /** Swaps the model of [l] on its own thread: earlier calls finish on the old instance, later ones use the new one. */
    private suspend fun replace(l: Loaded, fresh: LlmSession, verified: Boolean = false) {
        withContext(l.dispatcher) {
            val old = l.model
            l.model = fresh
            l.gpuChecked = verified
            old.close()
        }
        publishAccel()
    }

    private suspend fun evictForOneMore() {
        while (loaded.size >= maxLoaded) {
            val victim = loaded.values.filter { it.runningRole == null }.minByOrNull { it.lastUsed } ?: break
            loaded.remove(victim.info.id)
            withContext(victim.dispatcher) { victim.model.close() }
        }
    }

    fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    fun loadedDescription(id: String): String? = loaded[id]?.model?.description

    fun loadMillis(id: String): Long? = loaded[id]?.model?.loadMillis

    /** Where model [id] runs, if it is loaded. */
    fun processorOf(id: String): Processor? = loaded[id]?.processor

    /** Stops the generation that is running for [role] (all roles if null). */
    fun cancel(role: Role? = null) {
        loaded.values.toList().forEach { l -> if (role == null || l.runningRole == role) l.model.cancel() }
    }

    /** Forgets the KV caches (benchmark: measure full prompt evaluation). */
    suspend fun resetCaches() = loadMutex.withLock {
        loaded.values.forEach { l -> withContext(l.dispatcher) { if (!l.model.isClosed) l.model.resetCache() } }
    }

    suspend fun unloadAll() = loadMutex.withLock {
        loaded.values.forEach { l ->
            l.model.cancel()
            withContext(l.dispatcher) { l.model.close() }
        }
        loaded.clear()
        publishAccel()
    }

    // ------------------------------------------------------------------------------ CPU or GPU

    private fun scheduleJob(modelId: String, job: suspend () -> Unit) {
        if (!pendingJobs.add(modelId)) return
        scope.launch {
            try {
                jobMutex.withLock { job() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the model keeps running where it is
            } finally {
                pendingJobs.remove(modelId)
                publishAccel()
            }
        }
    }

    /** Automatic mode: a loaded model without a decision is measured in the background (twice per process at most). */
    private fun maybeMeasure(info: ModelInfo) {
        if (settings().accel != AccelMode.AUTO || !acceleration.hasBackend) return
        if (acceleration.store.decision(info.id) != null || acceleration.store.blockedReason() != null) return
        if (gpuFailed.containsKey(info.id) || (measureAttempts[info.id] ?: 0) >= 2 || AccelChoice.probesFor(info).isEmpty()) return
        scheduleJob(info.id) {
            measureAttempts.merge(info.id, 1, Int::plus)
            measure(info)
        }
    }

    private suspend fun switchToGpu(info: ModelInfo) {
        val fresh = loadSession(info, Processor.GPU) ?: return
        var used = false
        try {
            loadMutex.withLock {
                val l = loaded[info.id]
                if (l != null && l.processor == Processor.CPU && withContext(Dispatchers.IO) { plannedProcessor(info) } == Processor.GPU) {
                    replace(l, fresh)
                    used = true
                }
            }
        } finally {
            if (!used) fresh.close()
        }
    }

    /**
     * Translates the same sentences with the loaded instance of [info] and with a second one on
     * the other processor, stores the decision ([AccelChoice.decide]) and, if the other processor
     * wins, lets that instance take over. Measured only while no translation runs.
     */
    private suspend fun measure(info: ModelInfo): AccelStore.Decision? {
        if (acceleration.store.decision(info.id) != null) return null
        val probes = AccelChoice.probesFor(info)
        if (probes.isEmpty()) return null
        if (withContext(Dispatchers.IO) { acceleration.gpu() } == null) {
            if (acceleration.store.blockedReason() != null) return null
            return remember(info.id, AccelStore.Decision(Processor.CPU, 0, 0, acceleration.problem() ?: "keine GPU"))
        }
        val serving = loaded[info.id] ?: return null
        val servingOn = serving.processor
        val otherOn = if (servingOn == Processor.GPU) Processor.CPU else Processor.GPU
        measuring += info.id
        publishAccel()
        try {
            val other = loadSession(info, otherOn)
                ?: return if (otherOn == Processor.GPU) remember(info.id, AccelStore.Decision(Processor.CPU, 0, 0, "Laden auf der GPU fehlgeschlagen")) else null
            var kept = false
            try {
                val otherRun = whenIdle { interrupted ->
                    withContext(Dispatchers.IO) { probeRun(info, other, probes, interrupted) }
                } ?: return null
                val servingRun = whenIdle { interrupted ->
                    withContext(serving.dispatcher) {
                        val m = serving.model
                        if (m.isClosed || m.usesGpu != (servingOn == Processor.GPU)) return@withContext null
                        // a final translation cancels the measurement (it is repeated when idle)
                        serving.runningRole = Role.LIVE
                        try {
                            probeRun(info, m, probes, interrupted)
                        } finally {
                            serving.runningRole = null
                        }
                    }
                } ?: return null
                val (cpu, gpu) = if (servingOn == Processor.CPU) servingRun to otherRun else otherRun to servingRun
                val decision = gpu.error?.let { AccelStore.Decision(Processor.CPU, cpu.ms, 0, "GPU-Test fehlgeschlagen: $it") }
                    ?: AccelChoice.decide(cpu.ms, gpu.ms, cpu.texts, gpu.texts)
                loadMutex.withLock {
                    acceleration.store.put(info.id, decision)
                    if (decision.processor == otherOn && loaded[info.id] === serving && serving.processor == servingOn &&
                        settings().accel == AccelMode.AUTO
                    ) {
                        replace(serving, other, verified = true)
                        kept = true
                    }
                }
                return decision
            } finally {
                if (!kept) other.close()
            }
        } finally {
            measuring -= info.id
            publishAccel()
        }
    }

    private fun remember(modelId: String, d: AccelStore.Decision): AccelStore.Decision {
        acceleration.store.put(modelId, d)
        publishAccel()
        return d
    }

    /** One warm-up run, then every probe from an empty KV cache. Null if a translation interrupted it. */
    private fun probeRun(info: ModelInfo, model: LlmSession, probes: List<AccelChoice.Probe>, interrupted: () -> Boolean): Measurement? {
        val style = info.promptStyle ?: PromptStyle.CHAT_TEMPLATE
        fun run(p: AccelChoice.Probe): Result? {
            if (interrupted()) return null
            val src = Languages.require(p.source)
            val tgt = Languages.require(p.target)
            model.resetCache()
            return runPieces(info, model, style, src, Request(p.text, src, tgt, Role.FINAL), p.text, null).takeIf { !it.cancelled }
        }
        val block = {
            try {
                run(probes.first())?.let {
                    var ms = 0L
                    val texts = ArrayList<String>()
                    for (p in probes) {
                        val r = run(p) ?: return@let null
                        ms += r.wallMs
                        texts += r.text
                    }
                    Measurement(ms, texts)
                }
            } catch (e: GenerationException) {
                if (!model.usesGpu) throw e
                Measurement(0, emptyList(), e.message)
            } finally {
                model.resetCache()
            }
        }
        return if (model.usesGpu) acceleration.guarded("GPU-Test (${info.name})", block) else block()
    }

    /**
     * Runs [block] once no translation has run for a moment; repeats it if a translation was
     * requested meanwhile ([block] can check that with its argument and stop early).
     */
    private suspend fun <T> whenIdle(block: suspend (interrupted: () -> Boolean) -> T?): T? {
        repeat(5) {
            while (loaded.values.any { it.runningRole != null } || System.nanoTime() - lastActivity < idleNanos) {
                delay((idleNanos / 4_000_000).coerceIn(10, 250))
            }
            val before = activity.get()
            val interrupted = { activity.get() != before }
            val r = block(interrupted)
            if (r != null && !interrupted()) return r
        }
        return null
    }

    /** Applies a changed CPU/GPU setting to the loaded models (in the background). */
    fun refresh() {
        scope.launch {
            for (l in loaded.values.toList()) runCatching { get(l.info) }
        }
    }

    /** Forgets all measurements; loaded models are measured again (automatic mode). */
    fun remeasure() {
        acceleration.store.clearDecisions()
        measureAttempts.clear()
        loaded.values.forEach { maybeMeasure(it.info) }
        publishAccel()
    }

    /** Allows the GPU again after it was blocked because the app crashed in a GPU step. */
    fun unblockGpu() {
        acceleration.store.unblock()
        gpuFailed.clear()
        measureAttempts.clear()
        loaded.values.forEach { maybeMeasure(it.info) }
        publishAccel()
    }

    /** Stores the benchmark's CPU/GPU comparison of [modelId]; automatic mode follows it. */
    fun rememberComparison(modelId: String, cpuMs: Long, gpuMs: Long, cpuTexts: List<String>, gpuTexts: List<String>): AccelStore.Decision =
        remember(modelId, AccelChoice.decide(cpuMs, gpuMs, cpuTexts, gpuTexts))

    /** Runs [block] while no background measurement or switch runs (benchmark). */
    suspend fun <T> exclusive(block: suspend () -> T): T = jobMutex.withLock { block() }

    @Synchronized
    private fun publishAccel() {
        accel.value = AccelStatus(
            gpuName = acceleration.knownGpu,
            problem = acceleration.problem(),
            blocked = acceleration.store.blockedReason() != null,
            decisions = acceleration.store.decisions(),
            measuring = measuring.toSet(),
            active = loaded.mapValues { it.value.processor },
            failed = gpuFailed.toMap(),
        )
    }

    // ------------------------------------------------------------------------------ translation

    suspend fun translate(req: Request, onPartial: ((String) -> Unit)? = null): Result {
        val text = req.text.trim()
        val info = pickModel(req.role, req.source, req.target, req.modelId)
            ?: throw NoModelException(noModelMessage(req))
        if (text.isEmpty()) return Result("", info.id, info.name, req.source, 0, 0, 0, 0, 0.0, 0.0, false)

        val style = info.promptStyle ?: PromptStyle.CHAT_TEMPLATE
        var src = req.source
        if (src == null && PromptFormat.needsSource(style)) {
            val guess = LanguageGuesser.guess(text, info.languages.filter { it != req.target.code })
            src = guess?.let { Languages.byCode(it) }
                ?: throw NoModelException("Ausgangssprache nicht erkannt – bitte wählen")
        }
        activity.incrementAndGet()
        lastActivity = System.nanoTime()
        try {
            while (true) {
                val l = get(info, req.processor)
                // a final translation must not wait for a stale live partial on the same model
                if (req.role != Role.LIVE && l.runningRole == Role.LIVE) l.model.cancel()
                try {
                    val r = withContext(l.dispatcher) { runOn(l, style, src, req, text, onPartial) }
                    if (r != null) return r
                    // the instance was closed meanwhile (evicted): load it again
                } catch (e: GenerationException) {
                    if (l.processor != Processor.GPU || req.processor != null) throw e
                    // an error on the GPU: this model goes back to the CPU and the request is repeated there
                    gpuFailed[info.id] = "Fehler bei der Übersetzung"
                    publishAccel()
                }
            }
        } finally {
            lastActivity = System.nanoTime()
        }
    }

    /** Runs on the model's thread; null if the instance was closed. */
    private fun runOn(
        l: Loaded,
        style: PromptStyle,
        src: Language?,
        req: Request,
        text: String,
        onPartial: ((String) -> Unit)?,
    ): Result? {
        val model = l.model
        if (model.isClosed) return null
        l.runningRole = req.role
        try {
            if (model.usesGpu && !l.gpuChecked) {
                return acceleration.guarded("Erste GPU-Übersetzung (${l.info.name})") {
                    runPieces(l.info, model, style, src, req, text, onPartial)
                }.also { l.gpuChecked = true }
            }
            return runPieces(l.info, model, style, src, req, text, onPartial)
        } finally {
            l.runningRole = null
            l.lastUsed = System.nanoTime()
        }
    }

    private fun runPieces(
        info: ModelInfo,
        model: LlmSession,
        style: PromptStyle,
        src: Language?,
        req: Request,
        text: String,
        onPartial: ((String) -> Unit)?,
    ): Result {
        val t0 = System.nanoTime()
        // live/final utterances are short: one piece. Texts are split into sentences/paragraphs.
        val pieces = if (req.role == Role.TEXT) TextSegmenter.split(text) else listOf(TextSegmenter.Piece(text, ""))
        val out = StringBuilder()
        var promptTokens = 0
        var reused = 0
        var generated = 0
        var prefill = 0.0
        var decode = 0.0
        var cancelled = false
        val echo = PromptFormat.echoNames(req.target)
        for (piece in pieces) {
            if (piece.text.isBlank()) {
                out.append(piece.separatorAfter)
                continue
            }
            val prompt = PromptFormat.build(style, src, req.target, piece.text) { user -> model.applyChatTemplate(user) }
            val prefix = out.toString()
            val r = model.generate(
                prompt = prompt.text,
                addSpecial = prompt.addSpecial,
                maxTokens = PromptFormat.maxTokens(piece.text),
                repeatPenalty = if (style == PromptStyle.HY_MT) 1.05f else 1.0f,
                stopAtNewline = prompt.stopAtNewline,
            ) { partial ->
                onPartial?.invoke(TextNormalizer.forLanguage(prefix + TextNormalizer.cleanTranslation(partial, piece.text, echo), req.target))
                true
            }
            if (r.stop == LlamaModel.StopReason.ERROR) throw GenerationException("Übersetzung fehlgeschlagen (${info.name})")
            if (r.stop == LlamaModel.StopReason.PROMPT_TOO_LONG) throw IOException("Text zu lang für das Modell")
            out.append(TextNormalizer.cleanTranslation(r.text, piece.text, echo)).append(piece.separatorAfter)
            promptTokens += r.promptTokens
            reused += r.reusedTokens
            generated += r.generatedTokens
            prefill += r.prefillMs
            decode += r.decodeMs
            if (r.stop == LlamaModel.StopReason.CANCELLED) {
                cancelled = true
                break
            }
        }
        val final = TextNormalizer.forLanguage(out.toString().trim(), req.target)
        return Result(
            text = final, modelId = info.id, modelName = info.name, sourceUsed = src,
            wallMs = (System.nanoTime() - t0) / 1_000_000, promptTokens = promptTokens, reusedTokens = reused,
            generatedTokens = generated, prefillMs = prefill, decodeMs = decode, cancelled = cancelled,
            processor = if (model.usesGpu) Processor.GPU else Processor.CPU,
        )
    }

    private fun noModelMessage(req: Request): String {
        val any = source.installedTranslationModels()
        return if (any.isEmpty()) "Kein Übersetzungsmodell installiert – bitte unter „Modelle“ laden"
        else "Kein installiertes Modell kann ${req.source?.nameDe?.let { "$it → " } ?: ""}${req.target.nameDe}"
    }

    private companion object {
        /** one thread at most, created on demand, ended after 30 s without work: no closing needed */
        fun serialDispatcher(name: String): CoroutineDispatcher =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, name) }
                .asCoroutineDispatcher()
    }
}
