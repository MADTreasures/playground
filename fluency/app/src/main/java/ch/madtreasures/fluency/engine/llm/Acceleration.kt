package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.models.ModelInfo
import java.io.File
import java.util.Properties

/** Where a translation model runs. */
enum class Processor { CPU, GPU }

/** Setting: how the processor of a translation model is chosen. */
enum class AccelMode {
    /** measure CPU and GPU once per model and device, keep the faster one */
    AUTO,
    GPU,
    CPU;

    companion object {
        fun parse(value: String?): AccelMode? = entries.firstOrNull { it.name == value }
    }
}

/** Result of loading the GPU backend: the device name, or null with the reason in [report]. */
data class GpuInfo(val name: String?, val report: String) {
    companion object {
        /** Loads ggml's OpenCL backend (Adreno) from [libDir]; kernels are cached in [kernelCacheDir]. */
        fun openCl(libDir: String?, kernelCacheDir: String?): GpuInfo {
            val report = LlamaNative.nativeEnableGpu(libDir, kernelCacheDir)
            return GpuInfo(LlamaNative.nativeGpuName(), report)
        }
    }
}

/**
 * Persistent result of the CPU/GPU comparison per translation model, and the GPU block after a
 * crash. Everything belongs to [fingerprint] (app build + Android build): after an update of
 * either, the GPU is measured again. [file] null: in memory only (tests).
 */
class AccelStore(private val file: File?, private val fingerprint: String) {

    data class Decision(
        val processor: Processor,
        val cpuMs: Long,
        val gpuMs: Long,
        /** why the GPU is not used although it was considered (empty if it simply was slower) */
        val note: String = "",
        val measuredAt: Long = System.currentTimeMillis(),
    ) {
        internal fun encode() = "${processor.name};$cpuMs;$gpuMs;$measuredAt;${note.replace('\n', ' ')}"

        companion object {
            internal fun decode(s: String): Decision? {
                val f = s.split(';', limit = 5)
                if (f.size < 5) return null
                val p = Processor.entries.firstOrNull { it.name == f[0] } ?: return null
                return Decision(p, f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[4], f[3].toLongOrNull() ?: 0)
            }
        }
    }

    private val props = Properties()

    init {
        val f = file
        if (f != null && f.exists()) {
            runCatching { f.inputStream().use { props.load(it) } }
        }
        if (props.getProperty(KEY_FINGERPRINT) != fingerprint) {
            props.clear()
            props.setProperty(KEY_FINGERPRINT, fingerprint)
            save()
        }
    }

    @Synchronized
    fun decision(modelId: String): Decision? = props.getProperty(PREFIX + modelId)?.let(Decision::decode)

    @Synchronized
    fun decisions(): Map<String, Decision> = props.stringPropertyNames()
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { k -> Decision.decode(props.getProperty(k))?.let { k.removePrefix(PREFIX) to it } }
        .toMap()

    @Synchronized
    fun put(modelId: String, decision: Decision) {
        props.setProperty(PREFIX + modelId, decision.encode())
        save()
    }

    @Synchronized
    fun clearDecisions() {
        props.stringPropertyNames().filter { it.startsWith(PREFIX) }.forEach { props.remove(it) }
        save()
    }

    /** Why the GPU is blocked (it crashed the app), or null. */
    @Synchronized
    fun blockedReason(): String? = props.getProperty(KEY_BLOCKED)

    @Synchronized
    fun block(reason: String) {
        props.setProperty(KEY_BLOCKED, reason)
        save()
    }

    @Synchronized
    fun unblock() {
        props.remove(KEY_BLOCKED)
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { props.store(it, "Fluency: CPU/GPU per translation model") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private companion object {
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_BLOCKED = "gpu.blocked"
        const val PREFIX = "model."
    }
}

/**
 * Crash guard for the GPU: a marker file exists while a risky native GPU step runs (loading the
 * OpenCL driver, compiling the kernels while a model is loaded, the first GPU runs). If the
 * process dies inside such a step (driver crash, or ggml's exit(1) after a kernel compile error),
 * the marker survives and the next start uses the CPU instead of crashing again (AppContainer).
 */
class GpuGuard(private val marker: File) {
    data class Step(val name: String, val startedAt: Long)

    private val active = LinkedHashMap<Long, Step>()
    private var nextId = 0L

    fun <T> step(name: String, block: () -> T): T {
        val id = enter(name)
        try {
            return block()
        } finally {
            leave(id)
        }
    }

    /** The step the previous process died in (the marker is consumed). */
    @Synchronized
    fun takeLeftover(): Step? {
        if (!marker.exists()) return null
        val lines = runCatching { marker.readLines() }.getOrDefault(emptyList())
        marker.delete()
        val startedAt = lines.firstOrNull()?.toLongOrNull() ?: 0L
        return Step(lines.drop(1).joinToString(", ").ifBlank { "GPU" }, startedAt)
    }

    @Synchronized
    private fun enter(name: String): Long {
        val id = nextId++
        active[id] = Step(name, System.currentTimeMillis())
        write()
        return id
    }

    @Synchronized
    private fun leave(id: Long) {
        active.remove(id)
        write()
    }

    private fun write() {
        runCatching {
            if (active.isEmpty()) {
                marker.delete()
            } else {
                marker.parentFile?.mkdirs()
                val first = active.values.minOf { it.startedAt }
                marker.writeText("$first\n" + active.values.joinToString("\n") { it.name })
            }
        }
    }
}

/**
 * GPU state of the process: the persistent decisions ([store]), the crash guard and the GPU
 * backend, which is loaded on first use ([probe] null: no GPU backend in this build).
 */
class Acceleration(
    val store: AccelStore = AccelStore(null, ""),
    private val guard: GpuGuard? = null,
    private val probe: (() -> GpuInfo)? = null,
) {
    @Volatile
    private var info: GpuInfo? = null
    private var tried = false

    val hasBackend: Boolean get() = probe != null

    /** Name of the usable GPU, or null. Loads the backend on the first call (blocking). */
    @Synchronized
    fun gpu(): String? {
        val p = probe
        if (store.blockedReason() != null || p == null) return null
        if (!tried) {
            tried = true
            info = runCatching { guarded("GPU-Treiber (OpenCL) laden") { p() } }
                .getOrElse { GpuInfo(null, it.message ?: it.javaClass.simpleName) }
        }
        return info?.name
    }

    /** The GPU name if the backend was loaded already (never blocks). */
    val knownGpu: String? get() = info?.name

    /** Why the GPU is not used; null if it is usable or was not tried yet. */
    fun problem(): String? = when {
        store.blockedReason() != null -> "GPU nach einem Absturz gesperrt: ${store.blockedReason()}"
        probe == null -> "Dieser Build hat kein GPU-Backend"
        info != null && info?.name == null -> "Keine nutzbare GPU: ${info?.report}"
        else -> null
    }

    fun <T> guarded(step: String, block: () -> T): T {
        val g = guard ?: return block()
        return g.step(step, block)
    }
}

/** How the processor is chosen in automatic mode (pure, unit tested). */
object AccelChoice {
    /**
     * The GPU is taken while it is at most 10 % slower than the CPU: in live mode it then leaves
     * the CPU cores to speech recognition, which runs at the same time.
     */
    const val GPU_TOLERANCE = 1.10

    /** below this the GPU output counts as wrong (miscompiled kernel, broken driver) */
    const val MIN_SIMILARITY = 0.5

    data class Probe(val source: String, val target: String, val text: String)

    /** The same two sentences are translated on both processors (after one warm-up run). */
    val probes = listOf(
        Probe("de", "en", "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme?"),
        Probe("en", "de", "The meeting has been moved to Thursday afternoon because two colleagues are still on vacation."),
    )

    fun probesFor(info: ModelInfo): List<Probe> = probes.filter { info.supports(it.source) && info.supports(it.target) }

    fun decide(cpuMs: Long, gpuMs: Long, cpuTexts: List<String>, gpuTexts: List<String>): AccelStore.Decision {
        val same = cpuTexts.size == gpuTexts.size && cpuTexts.zip(gpuTexts).all { (a, b) -> similarity(a, b) >= MIN_SIMILARITY }
        return when {
            !same -> AccelStore.Decision(Processor.CPU, cpuMs, gpuMs, "GPU-Übersetzung weicht von der CPU ab")
            gpuMs <= cpuMs * GPU_TOLERANCE -> AccelStore.Decision(Processor.GPU, cpuMs, gpuMs)
            else -> AccelStore.Decision(Processor.CPU, cpuMs, gpuMs)
        }
    }

    /** Dice coefficient of the character bigrams (case-insensitive): 1 = same text, 0 = nothing in common. */
    fun similarity(a: String, b: String): Double {
        fun bigrams(s: String): Map<String, Int> {
            val t = s.lowercase().replace(Regex("\\s+"), " ").trim()
            return t.windowed(2).groupingBy { it }.eachCount()
        }
        if (a.isBlank() || b.isBlank()) return if (a.isBlank() && b.isBlank()) 1.0 else 0.0
        val x = bigrams(a)
        val y = bigrams(b)
        val total = x.values.sum() + y.values.sum()
        if (total == 0) return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        val common = x.entries.sumOf { (k, n) -> minOf(n, y[k] ?: 0) }
        return 2.0 * common / total
    }
}
