package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AccelChoice
import ch.madtreasures.fluency.engine.llm.AccelMode
import ch.madtreasures.fluency.engine.llm.AccelStore
import ch.madtreasures.fluency.engine.llm.Acceleration
import ch.madtreasures.fluency.engine.llm.GpuGuard
import ch.madtreasures.fluency.engine.llm.GpuInfo
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.LlmSession
import ch.madtreasures.fluency.engine.llm.Processor
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
    @Test fun similarityOfTranslations() {
        val a = "Could you please tell me the fastest way to the main station?"
        assertEquals(1.0, AccelChoice.similarity(a, a), 1e-9)
        assertTrue(AccelChoice.similarity(a, "Could you tell me the quickest way to the main station?") > 0.7)
        assertTrue(AccelChoice.similarity(a, "@@@@ #### ,,,,") < 0.1)
        assertTrue(AccelChoice.similarity(a, "") == 0.0)
    }

    @Test fun gpuWinsWithinTolerance() {
        val t = listOf("Hello there.", "The meeting was moved.")
        assertEquals(Processor.GPU, AccelChoice.decide(1000, 700, t, t).processor)
        // 8 % slower: still the GPU (the CPU stays free for speech recognition)
        assertEquals(Processor.GPU, AccelChoice.decide(1000, 1080, t, t).processor)
        // 20 % slower: the CPU
        val slow = AccelChoice.decide(1000, 1200, t, t)
        assertEquals(Processor.CPU, slow.processor)
        assertEquals("", slow.note)
    }

    @Test fun wrongGpuOutputMeansCpu() {
        val d = AccelChoice.decide(1000, 500, listOf("Hello there.", "The meeting was moved."), listOf("Hello there.", "!!!!!!!!"))
        assertEquals(Processor.CPU, d.processor)
        assertTrue(d.note.isNotEmpty())
    }

    @Test fun probesNeedTheLanguages() {
        assertEquals(2, AccelChoice.probesFor(ModelCatalog.byId(ModelCatalog.HY_MT2)!!).size)
        val onlyFrench = ModelCatalog.byId(ModelCatalog.HY_MT2)!!.copy(languages = setOf("fr", "it"))
        assertTrue(AccelChoice.probesFor(onlyFrench).isEmpty())
    }
}

class AccelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun decisionsAndBlockSurviveARestart() {
        val f = File(tmp.root, "accel.properties")
        AccelStore(f, "v1").apply {
            put("m1", AccelStore.Decision(Processor.GPU, 900, 600))
            put("m2", AccelStore.Decision(Processor.CPU, 900, 0, "note; with; semicolons"))
            block("Absturz")
        }
        val again = AccelStore(f, "v1")
        assertEquals(Processor.GPU, again.decision("m1")?.processor)
        assertEquals(600L, again.decision("m1")?.gpuMs)
        assertEquals("note; with; semicolons", again.decision("m2")?.note)
        assertEquals("Absturz", again.blockedReason())
        assertEquals(setOf("m1", "m2"), again.decisions().keys)
        again.unblock()
        assertNull(AccelStore(f, "v1").blockedReason())
    }

    @Test fun anUpdateMeasuresAgain() {
        val f = File(tmp.root, "accel.properties")
        AccelStore(f, "app 1 / android A").apply {
            put("m1", AccelStore.Decision(Processor.GPU, 900, 600))
            block("Absturz")
        }
        val updated = AccelStore(f, "app 2 / android A")
        assertNull(updated.decision("m1"))
        assertNull(updated.blockedReason())
    }
}

class GpuGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun markerExistsOnlyWhileAStepRuns() {
        val marker = File(tmp.root, "gpu-step")
        val guard = GpuGuard(marker)
        var inside = ""
        val r = guard.step("Modell auf die GPU laden") {
            inside = marker.readText()
            guard.step("inner") { assertTrue(marker.readText().contains("inner")) }
            // the outer step is still guarded after the inner one ended
            assertTrue(marker.exists())
            42
        }
        assertEquals(42, r)
        assertFalse(marker.exists())
        assertTrue(inside.contains("Modell auf die GPU laden"))
        assertNull(guard.takeLeftover())

        // what the next start sees if the process died inside the step
        marker.writeText(inside)
        val left = GpuGuard(marker).takeLeftover()
        assertEquals("Modell auf die GPU laden", left?.name)
        assertTrue(left!!.startedAt > 0)
        assertFalse(marker.exists())
    }

    @Test fun markerIsRemovedWhenTheStepFails() {
        val marker = File(tmp.root, "gpu-step")
        runCatching { GpuGuard(marker).step("x") { error("driver error") } }
        assertFalse(marker.exists())
    }
}

/**
 * The CPU/GPU logic of the translation engine with simulated models: the build host has no
 * Adreno GPU, so this is where the GPU paths are tested (the phone runs the same code).
 */
class EngineAccelerationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val hy = ModelCatalog.byId(ModelCatalog.HY_MT2)!!

    @After fun tearDown() = scope.cancel()

    /** A model instance: [msPerCall] per translation, [gpu] where it runs. */
    class FakeSession(
        override val usesGpu: Boolean,
        private val msPerCall: Long,
        private val garbage: Boolean = false,
        private val failing: Boolean = false,
    ) : LlmSession {
        override val description = if (usesGpu) "fake GPU" else "fake CPU"
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

    private class Env(
        val cpuMs: Long = 40,
        val gpuMs: Long = 20,
        val gpuName: String? = "Adreno (fake)",
        val gpuGarbage: Boolean = false,
        val gpuFailing: Boolean = false,
        /** loading onto the GPU fails (e.g. not enough GPU memory) */
        val gpuLoadFails: Boolean = false,
    ) {
        val loads = CopyOnWriteArrayList<Processor>()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val probes = AtomicInteger()
        var guardSeenDuringGpuLoad = false
    }

    private fun engine(
        env: Env,
        settings: () -> AppSettings,
        store: AccelStore = AccelStore(null, "test"),
        guardFile: File = File(tmp.root, "gpu-step"),
        idleMillis: Long = 20,
    ) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = listOf(hy)
            override fun modelPath(info: ModelInfo) = "/fake/${info.id}.gguf"
        },
        settings = settings,
        nativeLibDir = null,
        acceleration = Acceleration(store, GpuGuard(guardFile)) {
            env.probes.incrementAndGet()
            GpuInfo(env.gpuName, if (env.gpuName == null) "no GPU" else "GPU: ${env.gpuName}")
        },
        loader = SessionLoader { _, params ->
            val p = if (params.useGpu) Processor.GPU else Processor.CPU
            if (p == Processor.GPU) env.guardSeenDuringGpuLoad = guardFile.exists()
            env.loads += p
            if (p == Processor.GPU && env.gpuLoadFails) return@SessionLoader null
            val s = if (p == Processor.GPU) FakeSession(true, env.gpuMs, env.gpuGarbage, env.gpuFailing) else FakeSession(false, env.cpuMs)
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

    @Test fun autoModeMovesToTheFasterGpu() = runBlocking {
        val env = Env(cpuMs = 40, gpuMs = 15)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        // first translation: on the CPU right away, the measurement follows in the background
        assertEquals(Processor.CPU, e.translate(req()).processor)
        until("decision") { store.decision(hy.id) != null }
        val d = store.decision(hy.id)!!
        assertEquals(Processor.GPU, d.processor)
        assertTrue("gpu ${d.gpuMs} < cpu ${d.cpuMs}", d.gpuMs < d.cpuMs)
        until("switch") { e.processorOf(hy.id) == Processor.GPU }
        assertEquals(Processor.GPU, e.translate(req()).processor)
        // the CPU instance was released, the measuring GPU instance took over (no second GPU load)
        assertTrue(env.sessions.first { !it.usesGpu }.isClosed)
        assertEquals(listOf(Processor.CPU, Processor.GPU), env.loads.toList())
        assertTrue(env.guardSeenDuringGpuLoad)
        assertFalse(File(tmp.root, "gpu-step").exists())
        assertEquals(Processor.GPU, e.accelStatus.value.active[hy.id])
    }

    @Test fun autoModeKeepsTheCpuIfTheGpuIsSlower() = runBlocking {
        val env = Env(cpuMs = 20, gpuMs = 60)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        e.translate(req())
        until("decision") { store.decision(hy.id) != null }
        assertEquals(Processor.CPU, store.decision(hy.id)!!.processor)
        until("measurement finished") { e.accelStatus.value.measuring.isEmpty() }
        assertEquals(Processor.CPU, e.translate(req()).processor)
        // the measuring GPU instance is gone again
        assertTrue(env.sessions.single { it.usesGpu }.isClosed)
    }

    @Test fun autoModeDistrustsAGpuThatTranslatesWrongly() = runBlocking {
        val env = Env(cpuMs = 40, gpuMs = 10, gpuGarbage = true)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        e.translate(req())
        until("decision") { store.decision(hy.id) != null }
        val d = store.decision(hy.id)!!
        assertEquals(Processor.CPU, d.processor)
        assertTrue(d.note, d.note.contains("weicht"))
    }

    @Test fun theMeasurementWaitsWhileTheUserTranslates() = runBlocking {
        val env = Env(cpuMs = 30, gpuMs = 10)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store, idleMillis = 300)
        val until = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < until) {
            assertEquals(Processor.CPU, e.translate(req()).processor)
            assertNull("measured while busy", store.decision(hy.id))
        }
        until("decision after the user stopped") { store.decision(hy.id) != null }
        assertEquals(Processor.GPU, store.decision(hy.id)!!.processor)
    }

    @Test fun aStoredDecisionLoadsDirectlyOnTheGpu() = runBlocking {
        val env = Env()
        val store = AccelStore(null, "test").apply { put(hy.id, AccelStore.Decision(Processor.GPU, 900, 500)) }
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        assertEquals(Processor.GPU, e.translate(req()).processor)
        assertEquals(listOf(Processor.GPU), env.loads.toList())
    }

    @Test fun cpuModeNeverTouchesTheGpu() = runBlocking {
        val env = Env()
        val e = engine(env, { AppSettings(accel = AccelMode.CPU) })
        repeat(3) { assertEquals(Processor.CPU, e.translate(req()).processor) }
        delay(100)
        assertEquals(0, env.probes.get())
        assertEquals(listOf(Processor.CPU), env.loads.toList())
    }

    @Test fun gpuModeAndAnErrorOnTheGpu() = runBlocking {
        val env = Env(gpuFailing = true)
        val e = engine(env, { AppSettings(accel = AccelMode.GPU) })
        // the GPU fails while translating: the same request is answered by the CPU
        val r = e.translate(req())
        assertEquals(Processor.CPU, r.processor)
        assertTrue(r.text.startsWith("EN("))
        assertEquals(listOf(Processor.GPU, Processor.CPU), env.loads.toList())
        assertNotNull(e.accelStatus.value.failed[hy.id])
        // and it stays there in this process
        assertEquals(Processor.CPU, e.translate(req()).processor)
        assertEquals(2, env.loads.size)
    }

    @Test fun aFailedGpuLoadIsTriedOnceAndTheCpuServes() = runBlocking {
        val env = Env(gpuLoadFails = true)
        val e = engine(env, { AppSettings(accel = AccelMode.GPU) })
        assertEquals(Processor.CPU, e.translate(req()).processor)
        assertEquals(Processor.CPU, e.translate(req()).processor)
        assertEquals(listOf(Processor.GPU, Processor.CPU), env.loads.toList())
        assertEquals("Laden auf der GPU fehlgeschlagen", e.accelStatus.value.failed[hy.id])
        assertFalse(File(tmp.root, "gpu-step").exists())
    }

    @Test fun withoutAGpuAutoModeRemembersTheCpu() = runBlocking {
        val env = Env(gpuName = null)
        val store = AccelStore(null, "test")
        val e = engine(env, { AppSettings(accel = AccelMode.AUTO) }, store)
        assertEquals(Processor.CPU, e.translate(req()).processor)
        until("decision") { store.decision(hy.id) != null }
        assertEquals(Processor.CPU, store.decision(hy.id)!!.processor)
        assertTrue(e.accelStatus.value.problem!!.contains("no GPU"))
        assertEquals(listOf(Processor.CPU), env.loads.toList())
    }

    @Test fun aBlockedGpuIsNotUsedUntilAllowedAgain() = runBlocking {
        val env = Env()
        val store = AccelStore(null, "test").apply { block("„Hy-MT2 auf die GPU laden“ – App nativer Absturz") }
        val e = engine(env, { AppSettings(accel = AccelMode.GPU) }, store)
        assertEquals(Processor.CPU, e.translate(req()).processor)
        assertEquals(0, env.probes.get())
        assertTrue(e.accelStatus.value.blocked)
        e.unblockGpu()
        e.refresh()
        until("switch to GPU") { e.processorOf(hy.id) == Processor.GPU }
        assertEquals(Processor.GPU, e.translate(req()).processor)
    }

    @Test fun switchingTheSettingAppliesWithoutRestart() = runBlocking {
        val env = Env()
        var settings = AppSettings(accel = AccelMode.CPU)
        val e = engine(env, { settings })
        assertEquals(Processor.CPU, e.translate(req()).processor)
        settings = settings.copy(accel = AccelMode.GPU)
        e.refresh()
        until("GPU") { e.processorOf(hy.id) == Processor.GPU }
        settings = settings.copy(accel = AccelMode.CPU)
        // back to the CPU: synchronous, the next translation already runs there
        assertEquals(Processor.CPU, e.translate(req()).processor)
    }

    @Test fun benchmarkCanForceEitherProcessor() = runBlocking {
        val env = Env()
        val e = engine(env, { AppSettings(accel = AccelMode.CPU) })
        val onGpu = e.translate(req().copy(processor = Processor.GPU))
        assertEquals(Processor.GPU, onGpu.processor)
        val onCpu = e.translate(req().copy(processor = Processor.CPU))
        assertEquals(Processor.CPU, onCpu.processor)
        val d = e.rememberComparison(hy.id, 1000, 600, listOf(onCpu.text), listOf(onGpu.text))
        assertEquals(Processor.GPU, d.processor)
    }
}
