package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AccelChoice
import ch.madtreasures.fluency.engine.llm.AccelGuard
import ch.madtreasures.fluency.engine.llm.AccelMode
import ch.madtreasures.fluency.engine.llm.AccelStore
import ch.madtreasures.fluency.engine.llm.Acceleration
import ch.madtreasures.fluency.engine.llm.DeviceInfo
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.LlmSession
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.Processor.CPU
import ch.madtreasures.fluency.engine.llm.Processor.GPU
import ch.madtreasures.fluency.engine.llm.Processor.NPU
import ch.madtreasures.fluency.engine.llm.SessionLoader
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AccelChoiceTest {
    private val t = listOf("Hello there.", "The meeting was moved.")
    private fun run(ms: Long, texts: List<String> = t, error: String? = null) = AccelChoice.Run(ms, texts, error)

    @Test fun similarityOfTranslations() {
        val a = "Could you please tell me the fastest way to the main station?"
        assertEquals(1.0, AccelChoice.similarity(a, a), 1e-9)
        assertTrue(AccelChoice.similarity(a, "Could you tell me the quickest way to the main station?") > 0.7)
        assertTrue(AccelChoice.similarity(a, "@@@@ #### ,,,,") < 0.1)
        assertTrue(AccelChoice.similarity(a, "") == 0.0)
    }

    @Test fun theFastestAcceleratorWins() {
        val d = AccelChoice.decide(mapOf(CPU to run(1000), GPU to run(800), NPU to run(600)))
        assertEquals(NPU, d.processor)
        assertEquals(mapOf(CPU to 1000L, GPU to 800L, NPU to 600L), d.millis)
        assertEquals("", d.note)
    }

    @Test fun anAcceleratorWithinToleranceStillWins() {
        // 8 % slower than the CPU: still the accelerator (the CPU stays free for speech recognition)
        assertEquals(GPU, AccelChoice.decide(mapOf(CPU to run(1000), GPU to run(1080))).processor)
        // 20 % slower: the CPU
        val slow = AccelChoice.decide(mapOf(CPU to run(1000), GPU to run(1200), NPU to run(1300)))
        assertEquals(CPU, slow.processor)
        assertEquals("", slow.note)
    }

    @Test fun wrongOrFailedAcceleratorsAreSkipped() {
        val d = AccelChoice.decide(
            mapOf(CPU to run(1000), NPU to run(300, listOf("Hello there.", "!!!!!!!!")), GPU to run(900)),
        )
        assertEquals(GPU, d.processor)
        assertTrue(d.note, d.note.contains("NPU-Übersetzung weicht"))
        val e = AccelChoice.decide(mapOf(CPU to run(1000), NPU to run(0, emptyList(), "HTP error")))
        assertEquals(CPU, e.processor)
        assertTrue(e.note, e.note.contains("NPU-Test fehlgeschlagen: HTP error"))
        assertEquals(setOf(CPU), e.millis.keys)
    }

    @Test fun probesNeedTheLanguages() {
        assertEquals(2, AccelChoice.probesFor(ModelCatalog.byId(ModelCatalog.HY_MT2)!!).size)
        val onlyFrench = ModelCatalog.byId(ModelCatalog.HY_MT2)!!.copy(languages = setOf("fr", "it"))
        assertTrue(AccelChoice.probesFor(onlyFrench).isEmpty())
    }
}

class AccelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun decisionsAndBlocksSurviveARestart() {
        val f = File(tmp.root, "accel.properties")
        AccelStore(f, "v1").apply {
            put("m1", AccelStore.Decision(NPU, mapOf(CPU to 900, GPU to 700, NPU to 500)))
            put("m2", AccelStore.Decision(CPU, mapOf(CPU to 900), "note; with; semicolons"))
            block(GPU, "Absturz")
        }
        val again = AccelStore(f, "v1")
        assertEquals(NPU, again.decision("m1")?.processor)
        assertEquals(mapOf(CPU to 900L, GPU to 700L, NPU to 500L), again.decision("m1")?.millis)
        assertEquals("note; with; semicolons", again.decision("m2")?.note)
        assertEquals("Absturz", again.blockedReason(GPU))
        assertNull(again.blockedReason(NPU))
        assertEquals(setOf("m1", "m2"), again.decisions().keys)
        again.unblock(GPU)
        assertNull(AccelStore(f, "v1").blockedReason(GPU))
    }

    @Test fun anUpdateMeasuresAgain() {
        val f = File(tmp.root, "accel.properties")
        AccelStore(f, "app 1 / android A").apply {
            put("m1", AccelStore.Decision(GPU, mapOf(CPU to 900, GPU to 600)))
            block(NPU, "Absturz")
        }
        val updated = AccelStore(f, "app 2 / android A")
        assertNull(updated.decision("m1"))
        assertNull(updated.blockedReason(NPU))
    }
}

class AccelGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun markerExistsOnlyWhileAStepRuns() {
        val marker = File(tmp.root, "accel-step")
        val guard = AccelGuard(marker)
        var inside = ""
        val r = guard.step(NPU, "Modell auf die NPU laden") {
            inside = marker.readText()
            guard.step(GPU, "inner") { assertTrue(marker.readText().contains("GPU|inner")) }
            // the outer step is still guarded after the inner one ended
            assertTrue(marker.exists())
            42
        }
        assertEquals(42, r)
        assertFalse(marker.exists())
        assertTrue(inside.contains("NPU|Modell auf die NPU laden"))
        assertTrue(guard.takeLeftover().isEmpty())

        // what the next start sees if the process died inside the step
        marker.writeText(inside)
        val left = AccelGuard(marker).takeLeftover()
        assertEquals(listOf(NPU), left.map { it.processor })
        assertEquals("Modell auf die NPU laden", left.single().name)
        assertTrue(left.single().startedAt > 0)
        assertFalse(marker.exists())
    }

    @Test fun markerIsRemovedWhenTheStepFails() {
        val marker = File(tmp.root, "accel-step")
        runCatching { AccelGuard(marker).step(GPU, "x") { error("driver error") } }
        assertFalse(marker.exists())
    }

    @Test fun anUnreadableMarkerBlamesBothAccelerators() {
        val marker = File(tmp.root, "accel-step").apply { writeText("garbage") }
        assertEquals(Processor.accelerators, AccelGuard(marker).takeLeftover().map { it.processor })
    }
}

/**
 * The CPU/GPU/NPU logic of the translation engine with simulated models: the build host has
 * neither an Adreno GPU nor a Hexagon NPU, so this is where those paths are tested (the phone runs
 * the same code).
 */
class EngineAccelerationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val hy = ModelCatalog.byId(ModelCatalog.HY_MT2)!!

    @After fun tearDown() = scope.cancel()

    /** A model instance on [processor]: [msPerCall] per translation. */
    class FakeSession(
        override val processor: Processor,
        private val msPerCall: Long,
        private val garbage: Boolean = false,
        private val failing: Boolean = false,
    ) : LlmSession {
        override val description = "fake $processor"
        override val loadMillis = 1L
        @Volatile override var isClosed = false
            private set
        @Volatile private var cancelled = false

        override fun generate(
            prompt: String,
            addSpecial: Boolean,
            maxTokens: Int,
            repeatPenalty: Float,
            stopAtNewline: Boolean,
            onText: ((String) -> Boolean)?,
        ): LlamaModel.Result {
            check(!isClosed) { "closed" }
            cancelled = false
            if (failing) return LlamaModel.Result("", 10, 0, 0, 1.0, 1.0, LlamaModel.StopReason.ERROR)
            Thread.sleep(msPerCall)
            val source = prompt.substringAfterLast("\n\n").substringBefore("<")
            val text = if (garbage) "#### ${"!".repeat(20)}" else "EN(${source.length}) ${source.take(12)}"
            onText?.invoke(text)
            val stop = if (cancelled) LlamaModel.StopReason.CANCELLED else LlamaModel.StopReason.EOG
            return LlamaModel.Result(text, 20, 0, 8, 1.0, msPerCall.toDouble(), stop)
        }

        override fun cancel() {
            cancelled = true
        }

        override fun resetCache() {}
        override fun applyChatTemplate(user: String): String? = null
        override fun close() {
            isClosed = true
        }
    }

    /** A simulated accelerator; [name] null: the backend loads but finds no device. */
    data class Dev(
        val ms: Long,
        val name: String? = "fake",
        val garbage: Boolean = false,
        val failing: Boolean = false,
        val loadFails: Boolean = false,
    )

    /** [gpu]/[npu] null: this build has no backend for it. */
    private class Env(val cpuMs: Long = 40, val gpu: Dev? = Dev(20), val npu: Dev? = Dev(15)) {
        val loads = CopyOnWriteArrayList<Processor>()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val probes = AtomicInteger()
        val markerDuringLoad = CopyOnWriteArrayList<String>()

        fun dev(p: Processor) = if (p == GPU) gpu else npu
    }

    private fun engine(
        env: Env,
        settings: () -> AppSettings,
        store: AccelStore = AccelStore(null, "test"),
        guardFile: File = File(tmp.root, "accel-step"),
        idleMillis: Long = 20,
    ) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = listOf(hy)
            override fun modelPath(info: ModelInfo) = "/fake/${info.id}.gguf"
        },
        settings = settings,
        nativeLibDir = null,
        acceleration = Acceleration(
            store, AccelGuard(guardFile),
            Processor.accelerators.filter { env.dev(it) != null }.associateWith { p ->
                {
                    env.probes.incrementAndGet()
                    val name = env.dev(p)!!.name
                    DeviceInfo(name, if (name == null) "no $p" else "$p: $name")
                }
            },
        ),
        loader = SessionLoader { _, params ->
            val p = params.processor
            if (p != CPU) env.markerDuringLoad += guardFile.takeIf { it.exists() }?.readText().orEmpty()
            env.loads += p
            val dev = if (p == CPU) null else env.dev(p)
            if (dev?.loadFails == true) return@SessionLoader null
            val s = if (dev == null) FakeSession(CPU, env.cpuMs) else FakeSession(p, dev.ms, dev.garbage, dev.failing)
            s.also { env.sessions += it }
        },
        scope = scope,
        initBackends = { "fake backends" },
        idleMillis = idleMillis,
    )

    private fun req(text: String = "Guten Morgen, wie geht es Ihnen heute?") = TranslationEngine.Request(text, de, en, Role.FINAL)

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    @Test fun autoModeMovesToTheFastestProcessor() = runBlocking {
        val env = Env(cpuMs = 40, gpu = Dev(25), npu = Dev(10))
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        // first translation: on the CPU right away, the measurement follows in the background
        assertEquals(CPU, e.translate(req()).processor)
        until("decision") { store.decision(hy.id) != null }
        val d = store.decision(hy.id)!!
        assertEquals(NPU, d.processor)
        assertEquals(setOf(CPU, GPU, NPU), d.millis.keys)
        assertTrue("$d", d.millis.getValue(NPU) < d.millis.getValue(GPU) && d.millis.getValue(GPU) < d.millis.getValue(CPU))
        until("switch") { e.processorOf(hy.id) == NPU }
        assertEquals(NPU, e.translate(req()).processor)
        // serving CPU instance, one temporary instance per accelerator, then the winner
        assertEquals(listOf(CPU, GPU, NPU, NPU), env.loads.toList())
        assertTrue(env.sessions.first { it.processor == CPU }.isClosed)
        assertTrue(env.sessions.first { it.processor == GPU }.isClosed)
        // the accelerator steps were crash-guarded, and the marker is gone afterwards
        assertTrue(env.markerDuringLoad.any { it.contains("GPU|") })
        assertTrue(env.markerDuringLoad.any { it.contains("NPU|") })
        assertFalse(File(tmp.root, "accel-step").exists())
        assertEquals(NPU, e.accelStatus.value.active[hy.id])
    }

    @Test fun autoModeKeepsTheCpuIfTheAcceleratorsAreSlower() = runBlocking {
        val env = Env(cpuMs = 20, gpu = Dev(60), npu = Dev(50))
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        e.translate(req())
        until("decision") { store.decision(hy.id) != null }
        assertEquals(CPU, store.decision(hy.id)!!.processor)
        until("measurement finished") { e.accelStatus.value.measuring.isEmpty() }
        assertEquals(CPU, e.translate(req()).processor)
        // the temporary instances are gone again
        assertTrue(env.sessions.filter { it.processor != CPU }.all { it.isClosed })
        assertEquals(listOf(CPU, GPU, NPU), env.loads.toList())
    }

    @Test fun aWrongNpuDoesNotBeatACorrectGpu() = runBlocking {
        val env = Env(cpuMs = 40, gpu = Dev(20), npu = Dev(5, garbage = true))
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        e.translate(req())
        until("decision") { store.decision(hy.id) != null }
        val d = store.decision(hy.id)!!
        assertEquals(GPU, d.processor)
        assertTrue(d.note, d.note.contains("NPU-Übersetzung weicht"))
        until("switch") { e.processorOf(hy.id) == GPU }
    }

    @Test fun theMeasurementWaitsWhileTheUserTranslates() = runBlocking {
        val env = Env(cpuMs = 30, gpu = null, npu = Dev(10))
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store, idleMillis = 300)
        val until = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < until) {
            assertEquals(CPU, e.translate(req()).processor)
            assertNull("measured while busy", store.decision(hy.id))
        }
        until("decision after the user stopped") { store.decision(hy.id) != null }
        assertEquals(NPU, store.decision(hy.id)!!.processor)
    }

    @Test fun aStoredDecisionLoadsDirectlyOnThatProcessor() = runBlocking {
        val env = Env()
        val store = AccelStore(null, "test").apply { put(hy.id, AccelStore.Decision(NPU, mapOf(CPU to 900, NPU to 500))) }
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        assertEquals(NPU, e.translate(req()).processor)
        assertEquals(listOf(NPU), env.loads.toList())
    }

    @Test fun cpuModeNeverTouchesTheAccelerators() = runBlocking {
        val env = Env()
        val e = engine(env, { AppSettings(accel = AccelMode.CPU) })
        repeat(3) { assertEquals(CPU, e.translate(req()).processor) }
        delay(100)
        assertEquals(0, env.probes.get())
        assertEquals(listOf(CPU), env.loads.toList())
    }

    @Test fun anErrorOnTheNpuIsAnsweredByTheCpu() = runBlocking {
        val env = Env(npu = Dev(10, failing = true))
        val e = engine(env, { AppSettings(accel = AccelMode.NPU) })
        // the NPU fails while translating: the same request is answered by the CPU
        val r = e.translate(req())
        assertEquals(CPU, r.processor)
        assertTrue(r.text.startsWith("EN("))
        assertEquals(listOf(NPU, CPU), env.loads.toList())
        assertNotNull(e.accelStatus.value.failed[hy.id]?.get(NPU))
        // and it stays there in this process
        assertEquals(CPU, e.translate(req()).processor)
        assertEquals(2, env.loads.size)
    }

    @Test fun aFailedNpuLoadIsTriedOnceAndTheCpuServes() = runBlocking {
        val env = Env(npu = Dev(10, loadFails = true))
        val e = engine(env, { AppSettings(accel = AccelMode.NPU) })
        assertEquals(CPU, e.translate(req()).processor)
        assertEquals(CPU, e.translate(req()).processor)
        assertEquals(listOf(NPU, CPU), env.loads.toList())
        assertEquals("Laden auf der NPU fehlgeschlagen", e.accelStatus.value.failed[hy.id]?.get(NPU))
        assertFalse(File(tmp.root, "accel-step").exists())
    }

    @Test fun withoutAcceleratorsAutoModeRemembersTheCpu() = runBlocking {
        val env = Env(gpu = Dev(10, name = null), npu = null)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        assertEquals(CPU, e.translate(req()).processor)
        until("decision") { store.decision(hy.id) != null }
        val d = store.decision(hy.id)!!
        assertEquals(CPU, d.processor)
        assertTrue(d.note, d.note.contains("no GPU") && d.note.contains("kein NPU-Backend"))
        assertEquals(listOf(CPU), env.loads.toList())
        assertEquals(setOf(GPU, NPU), e.accelStatus.value.problems.keys)
    }

    @Test fun aBlockedNpuIsNotUsedUntilAllowedAgain() = runBlocking {
        val env = Env()
        val store = AccelStore(null, "test").apply { block(NPU, "„Hy-MT2 auf die NPU laden“ – App nativer Absturz") }
        val e = engine(env, { AppSettings(accel = AccelMode.NPU) }, store)
        assertEquals(CPU, e.translate(req()).processor)
        assertEquals(setOf(NPU), e.accelStatus.value.blocked)
        // the GPU is not affected
        assertEquals(GPU, e.translate(req().copy(processor = GPU)).processor)
        e.unblock(NPU)
        e.refresh()
        until("switch to the NPU") { e.processorOf(hy.id) == NPU }
        assertEquals(NPU, e.translate(req()).processor)
    }

    @Test fun switchingTheSettingAppliesWithoutRestart() = runBlocking {
        val env = Env()
        var settings = AppSettings(accel = AccelMode.CPU)
        val e = engine(env, { settings })
        assertEquals(CPU, e.translate(req()).processor)
        settings = settings.copy(accel = AccelMode.NPU)
        e.refresh()
        until("NPU") { e.processorOf(hy.id) == NPU }
        settings = settings.copy(accel = AccelMode.GPU)
        e.refresh()
        until("GPU") { e.processorOf(hy.id) == GPU }
        settings = settings.copy(accel = AccelMode.CPU)
        // back to the CPU: synchronous, the next translation already runs there
        assertEquals(CPU, e.translate(req()).processor)
    }

    @Test fun theBenchmarkCanForceEveryProcessor() = runBlocking {
        val env = Env()
        val e = engine(env, { AppSettings(accel = AccelMode.CPU) })
        val results = Processor.entries.associateWith { p -> e.translate(req().copy(processor = p)) }
        results.forEach { (p, r) -> assertEquals(p, r.processor) }
        val d = e.rememberComparison(
            hy.id, results.mapValues { (_, r) -> AccelChoice.Run(r.wallMs, listOf(r.text)) },
        )
        assertEquals(NPU, d.processor)
    }
}
