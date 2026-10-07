package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.models.ModelInfo
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Where a translation model runs. The ordinal is passed to the native code (fluency_jni.cpp). */
enum class Processor {
    CPU,

    /** Adreno, through llama.cpp's OpenCL backend */
    GPU,

    /** Hexagon, through llama.cpp's Hexagon (HTP) backend */
    NPU;

    companion object {
        val accelerators = listOf(GPU, NPU)
    }
}

/** Setting: how the processor of a translation model is chosen. */
enum class AccelMode {
    /** measure CPU, GPU and NPU once per model and device, keep the fastest */
    AUTO,
    NPU,
    GPU,
    CPU;

    /** the processor this mode forces, null for [AUTO] */
    val forced: Processor?
        get() = when (this) {
            AUTO -> null
            NPU -> Processor.NPU
            GPU -> Processor.GPU
            CPU -> Processor.CPU
        }

    companion object {
        fun parse(value: String?): AccelMode? = entries.firstOrNull { it.name == value }
    }
}

/** Result of loading an accelerator backend: the device name, or null with the reason in [report]. */
data class DeviceInfo(val name: String?, val report: String) {
    companion object {
        /** Loads ggml's OpenCL backend (Adreno) from [libDir]; kernels are cached in [kernelCacheDir]. */
        fun openCl(libDir: String?, kernelCacheDir: String?): DeviceInfo {
            val report = LlamaNative.nativeEnableGpu(libDir, kernelCacheDir)
            return DeviceInfo(LlamaNative.nativeDeviceName(Processor.GPU.ordinal), report)
        }

        /** Loads ggml's Hexagon backend (NPU) from [libDir]. */
        fun hexagon(libDir: String?): DeviceInfo {
            val report = LlamaNative.nativeEnableNpu(libDir)
            return DeviceInfo(LlamaNative.nativeDeviceName(Processor.NPU.ordinal), report)
        }
    }
}

/**
 * Persistent result of the CPU/GPU/NPU comparison per translation model, and the processors that
 * are blocked after a crash. Everything belongs to [fingerprint] (app build + Android build): after
 * an update of either, everything is measured again. [file] null: in memory only (tests).
 */
class AccelStore(private val file: File?, private val fingerprint: String) {

    data class Decision(
        val processor: Processor,
        /** time for the probe sentences per processor (only the measured ones) */
        val millis: Map<Processor, Long> = emptyMap(),
        /** why an accelerator is not used although it was considered (empty if it was just slower) */
        val note: String = "",
        val measuredAt: Long = System.currentTimeMillis(),
    ) {
        internal fun encode(): String {
            val times = millis.entries.joinToString(",") { "${it.key.name}=${it.value}" }
            return "${processor.name};$times;$measuredAt;${note.replace('\n', ' ')}"
        }

        companion object {
            internal fun decode(s: String): Decision? {
                val f = s.split(';', limit = 4)
                if (f.size < 4) return null
                val p = Processor.entries.firstOrNull { it.name == f[0] } ?: return null
                val times = f[1].split(',').filter { '=' in it }.mapNotNull { kv ->
                    val (k, v) = kv.split('=', limit = 2)
                    val key = Processor.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                    v.toLongOrNull()?.let { key to it }
                }.toMap()
                return Decision(p, times, f[3], f[2].toLongOrNull() ?: 0)
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

    /** Why [p] is blocked (it crashed the app), or null. */
    @Synchronized
    fun blockedReason(p: Processor): String? = props.getProperty(blockKey(p))

    @Synchronized
    fun block(p: Processor, reason: String) {
        props.setProperty(blockKey(p), reason)
        save()
    }

    @Synchronized
    fun unblock(p: Processor) {
        props.remove(blockKey(p))
        save()
    }

    private fun blockKey(p: Processor) = "${p.name.lowercase()}.blocked"

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { props.store(it, "Fluency: CPU/GPU/NPU per translation model") }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private companion object {
        const val KEY_FINGERPRINT = "fingerprint"
        const val PREFIX = "model."
    }
}

/**
 * Crash guard for the accelerators: a marker file exists while a risky native step runs on the GPU
 * or NPU (loading the driver, loading a model onto it - the GPU compiles its kernels there, the NPU
 * starts its program -, the first runs). If the process dies inside such a step (driver crash, or
 * ggml's exit(1) after a kernel compile error), the marker survives and the next start uses
 * another processor instead of crashing again (AppContainer).
 */
class AccelGuard(private val marker: File) {
    data class Step(val processor: Processor, val name: String, val startedAt: Long)

    private val active = LinkedHashMap<Long, Step>()
    private var nextId = 0L

    fun <T> step(processor: Processor, name: String, block: () -> T): T {
        val id = enter(processor, name)
        try {
            return block()
        } finally {
            leave(id)
        }
    }

    /** The steps the previous process died in (the marker is consumed). */
    @Synchronized
    fun takeLeftover(): List<Step> {
        if (!marker.exists()) return emptyList()
        val lines = runCatching { marker.readLines() }.getOrDefault(emptyList())
        marker.delete()
        val startedAt = lines.firstOrNull()?.toLongOrNull() ?: 0L
        val steps = lines.drop(1).mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            Processor.entries.firstOrNull { it.name == parts[0] }?.let { Step(it, parts[1], startedAt) }
        }
        // an unreadable marker still means: the app died in an accelerator step
        return steps.ifEmpty { Processor.accelerators.map { Step(it, "Beschleuniger", startedAt) } }
    }

    @Synchronized
    private fun enter(processor: Processor, name: String): Long {
        val id = nextId++
        active[id] = Step(processor, name, System.currentTimeMillis())
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
                marker.writeText("$first\n" + active.values.joinToString("\n") { "${it.processor.name}|${it.name}" })
            }
        }
    }
}

/**
 * Accelerator state of the process: the persistent decisions ([store]), the crash guard and the
 * GPU/NPU backends, which are loaded on first use ([probes]: the processors this build has a
 * backend for).
 */
class Acceleration(
    val store: AccelStore = AccelStore(null, ""),
    private val guard: AccelGuard? = null,
    private val probes: Map<Processor, () -> DeviceInfo> = emptyMap(),
) {
    private val info = ConcurrentHashMap<Processor, DeviceInfo>()

    fun hasBackend(p: Processor): Boolean = p == Processor.CPU || p in probes

    /** Name of the usable device for [p], or null. Loads its backend on the first call (blocking). */
    @Synchronized
    fun device(p: Processor): String? {
        if (p == Processor.CPU) return "CPU"
        val probe = probes[p] ?: return null
        if (store.blockedReason(p) != null) return null
        val known = info[p] ?: runCatching { guarded(p, "$p-Treiber laden") { probe() } }
            .getOrElse { DeviceInfo(null, it.message ?: it.javaClass.simpleName) }
            .also { info[p] = it }
        return known.name
    }

    /** The device name for [p] if its backend was loaded already (never blocks). */
    fun knownDevice(p: Processor): String? = info[p]?.name

    /** Why [p] is not used; null if it is usable or was not tried yet. */
    fun problem(p: Processor): String? {
        val blocked = store.blockedReason(p)
        val tried = info[p]
        return when {
            p == Processor.CPU -> null
            blocked != null -> "$p nach einem Absturz gesperrt: $blocked"
            p !in probes -> "Dieser Build hat kein $p-Backend"
            tried != null && tried.name == null -> "Keine nutzbare $p: ${tried.report}"
            else -> null
        }
    }

    fun <T> guarded(p: Processor, step: String, block: () -> T): T {
        val g = guard
        if (g == null || p == Processor.CPU) return block()
        return g.step(p, step, block)
    }
}

/** How the processor is chosen in automatic mode (pure, unit tested). */
object AccelChoice {
    /**
     * An accelerator is taken while it is at most 10 % slower than the CPU: in live mode it then
     * leaves the CPU cores to speech recognition, which runs at the same time.
     */
    const val TOLERANCE = 1.10

    /** below this an accelerator's output counts as wrong (miscompiled kernel, broken driver) */
    const val MIN_SIMILARITY = 0.5

    data class Probe(val source: String, val target: String, val text: String)

    /** The same two sentences are translated on every processor (after one warm-up run). */
    val probes = listOf(
        Probe("de", "en", "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme?"),
        Probe("en", "de", "The meeting has been moved to Thursday afternoon because two colleagues are still on vacation."),
    )

    fun probesFor(info: ModelInfo): List<Probe> = probes.filter { info.supports(it.source) && info.supports(it.target) }

    /** What one processor produced for the probes: total time and texts, or an error. */
    data class Run(val millis: Long, val texts: List<String>, val error: String? = null)

    /**
     * Picks the processor from the runs (the CPU run is the reference and must be present): the
     * fastest accelerator whose translations match the CPU's, if it is within [TOLERANCE] of the
     * CPU; otherwise the CPU.
     */
    fun decide(runs: Map<Processor, Run>): AccelStore.Decision {
        val cpu = requireNotNull(runs[Processor.CPU]) { "CPU run missing" }
        val notes = mutableListOf<String>()
        val good = runs.filter { (p, run) ->
            when {
                p == Processor.CPU -> false
                run.error != null -> {
                    notes += "$p-Test fehlgeschlagen: ${run.error}"
                    false
                }
                !sameTexts(cpu.texts, run.texts) -> {
                    notes += "$p-Übersetzung weicht von der CPU ab"
                    false
                }
                else -> true
            }
        }
        val times = runs.filterValues { it.error == null }.mapValues { it.value.millis }
        val best = good.minByOrNull { it.value.millis }
        val winner = if (best != null && best.value.millis <= cpu.millis * TOLERANCE) best.key else Processor.CPU
        return AccelStore.Decision(winner, times, notes.joinToString("; "))
    }

    private fun sameTexts(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> similarity(x, y) >= MIN_SIMILARITY }

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
