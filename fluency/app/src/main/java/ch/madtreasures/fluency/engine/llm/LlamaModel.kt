package ch.madtreasures.fluency.engine.llm

import java.io.Closeable

/** A loaded translation model as the engine uses it ([LlamaModel]; fakes in tests). */
interface LlmSession : Closeable {
    val description: String
    val loadMillis: Long

    /** all layers run on the GPU */
    val usesGpu: Boolean
    val isClosed: Boolean

    /**
     * Generates a completion for [prompt]. [onText] receives the accumulated text after every
     * chunk; returning false stops the generation early.
     */
    fun generate(
        prompt: String,
        addSpecial: Boolean,
        maxTokens: Int,
        repeatPenalty: Float = 1.0f,
        stopAtNewline: Boolean = false,
        onText: ((String) -> Boolean)? = null,
    ): LlamaModel.Result

    fun cancel()

    fun resetCache()

    /** Formats one user message with the model's chat template (null if unsupported). */
    fun applyChatTemplate(user: String): String?
}

/**
 * A GGUF model loaded with llama.cpp and kept warm. Not thread-safe for concurrent [generate]
 * calls (the native side serialises them); [cancel] may be called from any thread.
 */
class LlamaModel private constructor(
    private var handle: Long,
    val path: String,
    override val description: String,
    override val loadMillis: Long,
    override val usesGpu: Boolean,
) : LlmSession {

    data class LoadParams(
        val contextSize: Int = 2048,
        val batchSize: Int = 512,
        val threads: Int = 6,
        val threadsBatch: Int = 6,
        val useGpu: Boolean = false,
    )

    enum class StopReason { EOG, MAX_TOKENS, NEWLINE, CANCELLED, SINK, ERROR, PROMPT_TOO_LONG }

    data class Result(
        val text: String,
        val promptTokens: Int,
        val reusedTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val stop: StopReason,
    ) {
        val totalMs: Double get() = prefillMs + decodeMs
        val tokensPerSecond: Double get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0
        val prefillTokensPerSecond: Double
            get() = if (prefillMs > 0) (promptTokens - reusedTokens) * 1000.0 / prefillMs else 0.0
    }

    override val isClosed: Boolean get() = handle == 0L

    override fun generate(
        prompt: String,
        addSpecial: Boolean,
        maxTokens: Int,
        repeatPenalty: Float,
        stopAtNewline: Boolean,
        onText: ((String) -> Boolean)?,
    ): Result {
        check(handle != 0L) { "model closed" }
        val sb = StringBuilder()
        val sink = TokenSink { bytes ->
            sb.append(String(bytes, Charsets.UTF_8))
            onText?.invoke(sb.toString()) ?: true
        }
        val s = LlamaNative.nativeGenerate(
            handle, prompt.toByteArray(Charsets.UTF_8), addSpecial, maxTokens, repeatPenalty, stopAtNewline, sink,
        )
        val stop = when (s[5].toInt()) {
            0 -> StopReason.EOG
            1 -> StopReason.MAX_TOKENS
            2 -> StopReason.NEWLINE
            3 -> StopReason.CANCELLED
            4 -> StopReason.SINK
            -2 -> StopReason.PROMPT_TOO_LONG
            else -> StopReason.ERROR
        }
        return Result(sb.toString(), s[0].toInt(), s[1].toInt(), s[2].toInt(), s[3] / 1000.0, s[4] / 1000.0, stop)
    }

    override fun cancel() {
        if (handle != 0L) LlamaNative.nativeCancel(handle)
    }

    override fun resetCache() {
        if (handle != 0L) LlamaNative.nativeResetCache(handle)
    }

    fun setThreads(threads: Int, threadsBatch: Int) {
        if (handle != 0L) LlamaNative.nativeSetThreads(handle, threads, threadsBatch)
    }

    fun meta(key: String): String? = LlamaNative.nativeMeta(handle, key)?.toString(Charsets.UTF_8)

    fun tokenCount(text: String): Int = LlamaNative.nativeTokenCount(handle, text.toByteArray(Charsets.UTF_8), true)

    override fun applyChatTemplate(user: String): String? =
        LlamaNative.nativeApplyChatTemplate(handle, user.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8)

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.nativeFree(h)
    }

    companion object {
        /** Blocking. Returns null if the file cannot be loaded. */
        fun load(path: String, params: LoadParams): LlamaModel? {
            LlamaNative.load()
            val t0 = System.nanoTime()
            val h = LlamaNative.nativeLoad(
                path.toByteArray(Charsets.UTF_8), params.contextSize, params.batchSize,
                params.threads, params.threadsBatch, params.useGpu,
            )
            if (h == 0L) return null
            val ms = (System.nanoTime() - t0) / 1_000_000
            return LlamaModel(h, path, LlamaNative.nativeDescribe(h) ?: path, ms, LlamaNative.nativeUsesGpu(h))
        }
    }
}
