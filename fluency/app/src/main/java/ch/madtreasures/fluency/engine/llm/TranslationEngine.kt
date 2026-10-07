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
 * CPU, GPU or NPU ([AppSettings.accel]): in automatic mode every model is measured once per device
 * and app version on every processor in the background ([AccelChoice]) while it keeps translating
 * on the CPU; if an accelerator wins, it takes over without a pause. Risky accelerator steps are
 * crash-guarded ([AccelGuard]); an error on an accelerator moves the model back to the CPU.
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
        /** force CPU, GPU or NPU (benchmark) */
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

    /** CPU/GPU/NPU state for the settings screen. */
    data class AccelStatus(
        /** accelerators whose backend is loaded, with their device name */
        val devices: Map<Processor, String> = emptyMap(),
        /** why an accelerator is not used (none in this build, none found, blocked after a crash) */
        val problems: Map<Processor, String> = emptyMap(),
        val blocked: Set<Processor> = emptySet(),
        val decisions: Map<String, AccelStore.Decision> = emptyMap(),
        /** models being measured right now */
        val measuring: Set<String> = emptySet(),
        /** loaded models and where they run */
        val active: Map<String, Processor> = emptyMap(),
        /** errors in this process: model id → processor → reason; the model avoids that processor */
        val failed: Map<String, Map<Processor, String>> = emptyMap(),
    )

    private class Loaded(val info: ModelInfo, @Volatile var model: LlmSession) {
        /** every native call of this model runs on this one thread, in order */
        val dispatcher: CoroutineDispatcher = serialDispatcher("llm-${info.id}")
        @Volatile var lastUsed = System.nanoTime()
        @Volatile var runningRole: Role? = null

        /** the first run of an instance on an accelerator is crash-guarded */
        @Volatile var checked = false
        val processor: Processor get() = model.processor
    }

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val loadMutex = Mutex()

    /** background measurements and processor switches: one at a time, never during the benchmark */
    private val jobMutex = Mutex()
    private val pendingJobs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val measuring: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val measureAttempts = ConcurrentHashMap<String, Int>()

    /** "modelId/PROCESSOR" → reason: accelerators that failed for a model in this process */
    private val failures = ConcurrentHashMap<String, String>()

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

    /** Loads the backend of [p] if necessary. The device name, or null if there is none to use. */
    suspend fun device(p: Processor): String? = withContext(Dispatchers.IO) {
        ensureBackends()
        acceleration.device(p)
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

    private fun failureKey(info: ModelInfo, p: Processor) = "${info.id}/${p.name}"

    private fun failed(info: ModelInfo, p: Processor): Boolean = failures.containsKey(failureKey(info, p))

    private fun markFailed(info: ModelInfo, p: Processor, reason: String) {
        failures[failureKey(info, p)] = reason
        publishAccel()
    }

    /** Where [info] should run now (blocking: may load an accelerator backend on the first call). */
    private fun plannedProcessor(info: ModelInfo): Processor {
        val want = settings().accel.forced ?: acceleration.store.decision(info.id)?.processor ?: Processor.CPU
        return if (want == Processor.CPU || failed(info, want) || acceleration.device(want) == null) Processor.CPU else want
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
            if (want != Processor.CPU && forced == null) {
                // loading onto an accelerator takes seconds (the GPU compiles its kernels, the NPU
                // starts its program): the current instance keeps translating until it is ready
                scheduleJob(info.id) { switchTo(info, want) }
                return current
            }
            replace(current, loadSession(info, want) ?: throw IOException(failedOn(info, want)))
            return current
        }
        evictForOneMore()
        val model = loadSession(info, want)
            ?: (if (want != Processor.CPU && forced == null) loadSession(info, Processor.CPU) else null)
            ?: throw IOException(failedOn(info, want))
        return Loaded(info, model).also {
            loaded[info.id] = it
            publishAccel()
        }
    }

    private fun failedOn(info: ModelInfo, p: Processor): String =
        if (p != Processor.CPU) "${info.name} konnte nicht auf der $p geladen werden" +
            (failures[failureKey(info, p)] ?: acceleration.problem(p))?.let { " ($it)" }.orEmpty()
        else "${info.name} konnte nicht geladen werden"

    /** Loads [info] on exactly [on]; null if that fails (an accelerator failure is remembered for this process). */
    private suspend fun loadSession(info: ModelInfo, on: Processor): LlmSession? = withContext(Dispatchers.IO) {
        ensureBackends()
        val s = settings()
        val params = LlamaModel.LoadParams(
            contextSize = 2048, batchSize = 512,
            threads = s.llmThreads, threadsBatch = s.llmThreads, processor = on,
        )
        val path = source.modelPath(info)
        if (on == Processor.CPU) return@withContext loader.load(path, params)
        if (acceleration.device(on) == null) return@withContext null
        val m = acceleration.guarded(on, "${info.name} auf die $on laden") { loader.load(path, params) }
        when {
            m == null -> {
                markFailed(info, on, "Laden auf der $on fehlgeschlagen")
                null
            }
            m.processor != on -> {
                m.close()
                markFailed(info, on, "$on nicht gefunden")
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
            l.checked = verified
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

    // ------------------------------------------------------------------------------ CPU, GPU or NPU

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
        if (settings().accel != AccelMode.AUTO) return
        val candidates = Processor.accelerators.filter {
            acceleration.hasBackend(it) && acceleration.store.blockedReason(it) == null && !failed(info, it)
        }
        if (candidates.isEmpty() || acceleration.store.decision(info.id) != null) return
        if ((measureAttempts[info.id] ?: 0) >= 2 || AccelChoice.probesFor(info).isEmpty()) return
        scheduleJob(info.id) {
            measureAttempts.merge(info.id, 1, Int::plus)
            measure(info)
        }
    }

    private suspend fun switchTo(info: ModelInfo, to: Processor) {
        val fresh = loadSession(info, to) ?: return
        var used = false
        try {
            loadMutex.withLock {
                val l = loaded[info.id]
                if (l != null && l.processor != to && withContext(Dispatchers.IO) { plannedProcessor(info) } == to) {
                    replace(l, fresh)
                    used = true
                }
            }
        } finally {
            if (!used) fresh.close()
        }
    }

    /**
     * Translates the same sentences with the loaded instance of [info] and with a temporary
     * instance on every other usable processor (one at a time, so at most two copies are in
     * memory), stores the decision ([AccelChoice.decide]) and lets the winner take over.
     * Measured only while no translation runs.
     */
    private suspend fun measure(info: ModelInfo): AccelStore.Decision? {
        if (acceleration.store.decision(info.id) != null) return null
        val probes = AccelChoice.probesFor(info)
        if (probes.isEmpty()) return null
        val usable = withContext(Dispatchers.IO) {
            Processor.accelerators.filter { !failed(info, it) && acceleration.device(it) != null }
        }
        if (usable.isEmpty()) {
            // nothing to compare with; remember that unless a blocked accelerator may come back
            if (Processor.accelerators.any { acceleration.store.blockedReason(it) != null }) return null
            val why = Processor.accelerators.mapNotNull { acceleration.problem(it) }.joinToString("; ")
            return remember(info.id, AccelStore.Decision(Processor.CPU, note = why.ifEmpty { "keine GPU/NPU" }))
        }
        val serving = loaded[info.id] ?: return null
        val servingOn = serving.processor
        measuring += info.id
        publishAccel()
        try {
            val runs = LinkedHashMap<Processor, AccelChoice.Run>()
            runs[servingOn] = whenIdle { interrupted ->
                withContext(serving.dispatcher) {
                    val m = serving.model
                    if (m.isClosed || m.processor != servingOn) return@withContext null
                    // a final translation cancels the measurement (it is repeated when idle)
                    serving.runningRole = Role.LIVE
                    try {
                        probeRun(info, m, probes, interrupted)
                    } finally {
                        serving.runningRole = null
                    }
                }
            } ?: return null
            for (p in (listOf(Processor.CPU) + usable).distinct() - servingOn) {
                val temp = loadSession(info, p)
                if (temp == null) {
                    if (p == Processor.CPU) return null // the reference cannot be measured
                    runs[p] = AccelChoice.Run(0, emptyList(), failures[failureKey(info, p)] ?: "Laden fehlgeschlagen")
                    continue
                }
                try {
                    runs[p] = whenIdle { interrupted -> withContext(Dispatchers.IO) { probeRun(info, temp, probes, interrupted) } }
                        ?: return null
                } finally {
                    temp.close()
                }
            }
            val decision = AccelChoice.decide(runs)
            runs.forEach { (p, run) -> if (run.error != null && p != Processor.CPU) markFailed(info, p, run.error) }
            remember(info.id, decision)
            if (decision.processor != servingOn && settings().accel == AccelMode.AUTO) {
                if (decision.processor == Processor.CPU) {
                    loadSession(info, Processor.CPU)?.let { cpu ->
                        loadMutex.withLock {
                            val l = loaded[info.id]
                            if (l === serving && l.processor == servingOn) replace(l, cpu) else cpu.close()
                        }
                    }
                } else {
                    switchTo(info, decision.processor)
                }
            }
            return decision
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
    private fun probeRun(info: ModelInfo, model: LlmSession, probes: List<AccelChoice.Probe>, interrupted: () -> Boolean): AccelChoice.Run? {
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
                    AccelChoice.Run(ms, texts)
                }
            } catch (e: GenerationException) {
                if (model.processor == Processor.CPU) throw e
                AccelChoice.Run(0, emptyList(), e.message)
            } finally {
                model.resetCache()
            }
        }
        return acceleration.guarded(model.processor, "${model.processor}-Test (${info.name})", block)
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

    /** Applies a changed processor setting to the loaded models (in the background). */
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

    /** Allows [p] again after it was blocked because the app crashed in one of its steps. */
    fun unblock(p: Processor) {
        acceleration.store.unblock(p)
        failures.keys.removeAll { it.endsWith("/${p.name}") }
        measureAttempts.clear()
        loaded.values.forEach { maybeMeasure(it.info) }
        publishAccel()
    }

    /** Stores the benchmark's comparison of [modelId]; automatic mode follows it. */
    fun rememberComparison(modelId: String, runs: Map<Processor, AccelChoice.Run>): AccelStore.Decision =
        remember(modelId, AccelChoice.decide(runs))

    /** Runs [block] while no background measurement or switch runs (benchmark). */
    suspend fun <T> exclusive(block: suspend () -> T): T = jobMutex.withLock { block() }

    @Synchronized
    private fun publishAccel() {
        val failed = HashMap<String, MutableMap<Processor, String>>()
        failures.forEach { (key, reason) ->
            val id = key.substringBeforeLast('/')
            val p = Processor.entries.firstOrNull { it.name == key.substringAfterLast('/') } ?: return@forEach
            failed.getOrPut(id) { LinkedHashMap() }[p] = reason
        }
        accel.value = AccelStatus(
            devices = Processor.accelerators.mapNotNull { p -> acceleration.knownDevice(p)?.let { p to it } }.toMap(),
            problems = Processor.accelerators.mapNotNull { p -> acceleration.problem(p)?.let { p to it } }.toMap(),
            blocked = Processor.accelerators.filter { acceleration.store.blockedReason(it) != null }.toSet(),
            decisions = acceleration.store.decisions(),
            measuring = measuring.toSet(),
            active = loaded.mapValues { it.value.processor },
            failed = failed,
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
                val on = l.processor
                try {
                    val r = withContext(l.dispatcher) { runOn(l, style, src, req, text, onPartial) }
                    if (r != null) return r
                    // the instance was closed meanwhile (evicted): load it again
                } catch (e: GenerationException) {
                    if (on == Processor.CPU || req.processor != null) throw e
                    // an error on an accelerator: this model goes back to the CPU and the request is repeated there
                    markFailed(info, on, "Fehler bei der Übersetzung")
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
            if (model.processor != Processor.CPU && !l.checked) {
                return acceleration.guarded(model.processor, "Erste ${model.processor}-Übersetzung (${l.info.name})") {
                    runPieces(l.info, model, style, src, req, text, onPartial)
                }.also { l.checked = true }
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
            processor = model.processor,
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
