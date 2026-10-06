package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.LanguageGuesser
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.core.TextNormalizer
import ch.madtreasures.fluency.core.TextSegmenter
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/** Where the installed models and their files are (implemented by ModelManager in the app). */
interface TranslationModelSource {
    fun installedTranslationModels(): List<ModelInfo>
    fun modelPath(info: ModelInfo): String
}

class NoModelException(message: String) : IOException(message)

/**
 * Picks a translation model for a request, keeps loaded models warm (one dedicated thread per
 * model) and runs the translation with streaming output.
 */
class TranslationEngine(
    private val source: TranslationModelSource,
    private val settings: () -> AppSettings,
    private val nativeLibDir: String?,
    private val maxLoaded: Int = 3,
) {
    enum class Role { LIVE, FINAL, TEXT }

    data class Request(
        val text: String,
        val source: Language?,
        val target: Language,
        val role: Role,
        /** force a model (benchmark) */
        val modelId: String? = null,
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
    ) {
        val tokensPerSecond: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
    }

    private class Loaded(val info: ModelInfo, val model: LlamaModel, val dispatcher: ExecutorCoroutineDispatcher) {
        @Volatile var lastUsed = System.nanoTime()
        @Volatile var runningRole: Role? = null
    }

    private val loaded = LinkedHashMap<String, Loaded>()
    private val loadMutex = Mutex()

    @Volatile
    var backendInfo: String = ""
        private set

    @Volatile
    private var backendsReady = false

    /** Loads the native libraries and the ggml backends (once per process). */
    @Synchronized
    fun ensureBackends(): String {
        if (!backendsReady) {
            LlamaNative.load()
            backendInfo = LlamaNative.nativeInitBackends(nativeLibDir, settings().useGpu)
            backendsReady = true
        }
        return backendInfo
    }

    fun systemInfo(): String {
        ensureBackends()
        return LlamaNative.nativeSystemInfo()
    }

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

    private suspend fun get(info: ModelInfo): Loaded = loadMutex.withLock {
        loaded[info.id]?.let { it.lastUsed = System.nanoTime(); return it }
        withContext(Dispatchers.IO) { ensureBackends() }
        while (loaded.size >= maxLoaded) {
            val victim = loaded.values.filter { it.runningRole == null }.minByOrNull { it.lastUsed } ?: break
            loaded.remove(victim.info.id)
            withContext(victim.dispatcher) { victim.model.close() }
            victim.dispatcher.close()
        }
        val s = settings()
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-${info.id}") }.asCoroutineDispatcher()
        val model = withContext(dispatcher) {
            LlamaModel.load(
                source.modelPath(info),
                LlamaModel.LoadParams(
                    contextSize = 2048, batchSize = 512,
                    threads = s.llmThreads, threadsBatch = s.llmThreads, useGpu = s.useGpu,
                ),
            )
        }
        if (model == null) {
            dispatcher.close()
            throw IOException("${info.name} konnte nicht geladen werden")
        }
        Loaded(info, model, dispatcher).also { loaded[info.id] = it }
    }

    fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    fun loadedDescription(id: String): String? = loaded[id]?.model?.description

    fun loadMillis(id: String): Long? = loaded[id]?.model?.loadMillis

    /** Stops the generation that is running for [role] (all roles if null). */
    fun cancel(role: Role? = null) {
        loaded.values.toList().forEach { l -> if (role == null || l.runningRole == role) l.model.cancel() }
    }

    /** Forgets the KV caches (benchmark: measure full prompt evaluation). */
    suspend fun resetCaches() = loadMutex.withLock {
        loaded.values.forEach { l -> withContext(l.dispatcher) { l.model.resetCache() } }
    }

    suspend fun unloadAll() = loadMutex.withLock {
        loaded.values.forEach { l ->
            l.model.cancel()
            withContext(l.dispatcher) { l.model.close() }
            l.dispatcher.close()
        }
        loaded.clear()
    }

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
        val l = get(info)
        // a final translation must not wait for a stale live partial on the same model
        if (req.role != Role.LIVE && l.runningRole == Role.LIVE) l.model.cancel()

        return withContext(l.dispatcher) {
            l.runningRole = req.role
            try {
                runPieces(l, style, src, req, text, onPartial)
            } finally {
                l.runningRole = null
                l.lastUsed = System.nanoTime()
            }
        }
    }

    private fun runPieces(
        l: Loaded,
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
            val prompt = PromptFormat.build(style, src, req.target, piece.text) { user -> l.model.applyChatTemplate(user) }
            val prefix = out.toString()
            val r = l.model.generate(
                prompt = prompt.text,
                addSpecial = prompt.addSpecial,
                maxTokens = PromptFormat.maxTokens(piece.text),
                repeatPenalty = if (style == PromptStyle.HY_MT) 1.05f else 1.0f,
                stopAtNewline = prompt.stopAtNewline,
            ) { partial ->
                onPartial?.invoke(TextNormalizer.forLanguage(prefix + TextNormalizer.cleanTranslation(partial, piece.text, echo), req.target))
                true
            }
            if (r.stop == LlamaModel.StopReason.ERROR) throw IOException("Übersetzung fehlgeschlagen (${l.info.name})")
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
            text = final, modelId = l.info.id, modelName = l.info.name, sourceUsed = src,
            wallMs = (System.nanoTime() - t0) / 1_000_000, promptTokens = promptTokens, reusedTokens = reused,
            generatedTokens = generated, prefillMs = prefill, decodeMs = decode, cancelled = cancelled,
        )
    }

    private fun noModelMessage(req: Request): String {
        val any = source.installedTranslationModels()
        return if (any.isEmpty()) "Kein Übersetzungsmodell installiert – bitte unter „Modelle“ laden"
        else "Kein installiertes Modell kann ${req.source?.nameDe?.let { "$it → " } ?: ""}${req.target.nameDe}"
    }
}
