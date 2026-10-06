package ch.madtreasures.fluency.engine.llm

/** Receives generated text as complete UTF-8 chunks. Return false to stop generating. */
fun interface TokenSink {
    fun onText(utf8: ByteArray): Boolean
}

/** JNI surface of libfluency_jni.so (app/src/main/cpp/fluency_jni.cpp). */
object LlamaNative {
    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("fluency_jni")
            loaded = true
        }
    }

    /** Loads the best CPU backend variant (and OpenCL if [loadGpu]) from [libDir]. Idempotent. */
    external fun nativeInitBackends(libDir: String?, loadGpu: Boolean): String
    external fun nativeSystemInfo(): String
    external fun nativeHasGpu(): Boolean

    external fun nativeLoad(path: ByteArray, nCtx: Int, nBatch: Int, nThreads: Int, nThreadsBatch: Int, useGpu: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeSetThreads(handle: Long, nThreads: Int, nThreadsBatch: Int)
    external fun nativeMeta(handle: Long, key: String): ByteArray?
    external fun nativeDescribe(handle: Long): String?
    external fun nativeTokenCount(handle: Long, text: ByteArray, parseSpecial: Boolean): Int
    external fun nativeApplyChatTemplate(handle: Long, user: ByteArray): ByteArray?
    external fun nativeResetCache(handle: Long)

    /** @return [promptTokens, reusedTokens, generatedTokens, prefillMicros, decodeMicros, stopReason] */
    external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        addSpecial: Boolean,
        maxTokens: Int,
        repeatPenalty: Float,
        stopAtNewline: Boolean,
        sink: TokenSink?,
    ): LongArray
}
