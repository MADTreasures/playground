package ch.madtreasures.fluency.glasses

import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.glasses.G2Protocol.Source
import ch.madtreasures.fluency.pipeline.LivePipeline
import ch.madtreasures.fluency.pipeline.MissingModelException
import ch.madtreasures.fluency.pipeline.PipelineState
import ch.madtreasures.fluency.pipeline.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge with a simulated pipeline and virtual time: what the glasses get, and when. */
class GlassesBridgeTest {

    /** A pipeline the test speaks through: what it "hears" goes into its state like the real one's. */
    private class FakePipeline(
        val config: LivePipeline.Config,
        val state: MutableStateFlow<PipelineState>,
        val speak: () -> Boolean,
    ) : BridgePipeline {
        var started = false
        var stopped = false
        var cancelled = false

        /** set: stop() waits for it, like the real pipeline finishing the sentence being said */
        var finishing: CompletableDeferred<Unit>? = null

        override fun start() {
            started = true
            state.update { it.copy(running = true) }
        }

        override suspend fun stop() {
            finishing?.await()
            state.update { s -> s.copy(running = false, utterances = s.utterances.filter { it.final }) }
            stopped = true
        }

        override fun cancel() {
            cancelled = true
            state.update { it.copy(running = false) }
        }

        fun say(id: Long, src: String, text: String, final: Boolean = false) = state.update { s ->
            val u = Utterance(id, 0, config.source, config.target, src, text, final)
            s.copy(utterances = if (s.utterances.any { it.id == id }) s.utterances.map { if (it.id == id) u else it } else s.utterances + u)
        }

        fun drop(id: Long) = state.update { s -> s.copy(utterances = s.utterances.filterNot { it.id == id }) }

        /** the microphone failed: the real pipeline notes why and stops */
        fun fail(message: String) = state.update { it.copy(error = message, running = false) }
    }

    private class FakePipelines : Pipelines {
        val built = mutableListOf<FakePipeline>()
        var missing: MissingModelException? = null

        /** like the real factory, which finishes loading a model even when nobody waits any more */
        var finishesLoadingAnyway = false

        override suspend fun build(
            config: LivePipeline.Config,
            audio: AudioSource,
            state: MutableStateFlow<PipelineState>,
            speak: () -> Boolean,
        ): BridgePipeline {
            try {
                delay(LOAD_MS)
            } catch (e: CancellationException) {
                if (!finishesLoadingAnyway) throw e
            }
            missing?.let { throw it }
            return FakePipeline(config, state, speak).also { built += it }
        }
    }

    private object Silence : AudioSource {
        override fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit) {}
        override fun stop() {}
    }

    private class Sent(val at: Long, val m: JSONObject) {
        val t: String get() = m.getString("t")
    }

    /** The bridge, with what it sends and what it asks for. */
    private class Glasses(test: TestScope) {
        private val scheduler = test.testScheduler
        val pipelines = FakePipelines()
        val sent = mutableListOf<Sent>()
        val sources = mutableListOf<Source>()
        var micProblem: String? = null
        var watchSoundAt = 0L
        var activity: String? = null
        val bridge = GlassesBridge(
            scope = test.backgroundScope,
            pipelines = pipelines,
            audio = { source ->
                sources += source
                Silence
            },
            send = { sent += Sent(scheduler.currentTime, JSONObject(it)) },
            version = "1.9.0",
            micProblem = { micProblem },
            watchSoundAt = { watchSoundAt },
            clock = { scheduler.currentTime },
            onActivity = { activity = it },
        )
        val pipeline: FakePipeline get() = pipelines.built.last()

        fun say(message: String) {
            bridge.receive(message)
            now()
        }

        fun now() = scheduler.runCurrent()

        fun pass(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }

        fun take(): List<Sent> = sent.toList().also { sent.clear() }

        /** "start" (n = 1) until the pipeline listens; what was sent until then is taken */
        fun listening(source: String = "watch", from: String = "auto", to: String = "de-CH", speak: Boolean = false, partials: Boolean = true): FakePipeline {
            say(start(1, source, from, to, speak, partials))
            pass(LOAD_MS)
            take()
            return pipeline
        }
    }

    @Test
    fun helloIsAnsweredAndUnknownMessagesAreIgnored() = runTest {
        val g = Glasses(this)
        g.say("""{"t":"ping","n":1}""")
        g.say("kein JSON")
        assertTrue(g.take().isEmpty())
        g.say("""{"t":"hello","proto":"fluency-g2@1","app":"1.0.0"}""")
        val welcome = g.take().single()
        assertEquals("welcome", welcome.t)
        assertEquals("fluency-g2@1", welcome.m.getString("proto"))
        assertEquals("1.9.0", welcome.m.getString("app"))
    }

    @Test
    fun startLoadsTheModelsAndListensToTheWatch() = runTest {
        val g = Glasses(this)
        g.say(start(1, source = "watch", from = "auto", to = "de-CH"))
        assertState(g.take().single(), running = true, note = GlassesBridge.LOADING, re = 1)
        assertEquals("Uhr-Mikrofon, Automatisch → Deutsch (Schweiz) (lädt die Modelle)", g.activity)
        // the same again while the models load: they keep loading, for the newer request
        g.say(start(2, source = "watch", from = "auto", to = "de-CH"))
        assertState(g.take().single(), running = true, note = GlassesBridge.LOADING, re = 2)
        g.pass(LOAD_MS)
        val p = g.pipelines.built.single()
        assertTrue(p.started)
        assertNull(p.config.source) // automatic
        assertEquals("de-CH", p.config.target.code)
        assertEquals(listOf(Source.WATCH), g.sources)
        assertState(g.take().single(), running = true, re = 2)
        assertEquals("Uhr-Mikrofon, Automatisch → Deutsch (Schweiz)", g.activity)
    }

    @Test
    fun unfinishedSentencesGoOutAtMostEvery250ms() = runTest {
        val g = Glasses(this)
        val p = g.listening()
        val t0 = testScheduler.currentTime
        // the pipeline changes with every token: here every 50 ms, for a second
        for (i in 1..20) {
            p.say(7, "Where is the station number $i", "Wo ist der Bahnhof Nummer $i")
            g.pass(50)
        }
        g.pass(GlassesBridge.PARTIAL_MS)
        val utts = g.take()
        assertTrue(utts.all { it.t == "utt" && !it.m.getBoolean("final") })
        assertTrue("${utts.size} messages", utts.size in 4..6)
        utts.zipWithNext().forEach { (a, b) -> assertTrue("${b.at - a.at} ms apart", b.at - a.at >= GlassesBridge.PARTIAL_MS) }
        assertEquals(t0, utts.first().at) // the first at once
        assertEquals("Wo ist der Bahnhof Nummer 20", utts.last().m.getString("text")) // the newest in the end
    }

    @Test
    fun finishedSentencesGoOutAtOnceAndOnlyOnce() = runTest {
        val g = Glasses(this)
        val p = g.listening(from = "en")
        p.say(7, "Where is", "Wo ist")
        g.pass(10)
        p.say(7, "Where is the station?", "Wo ist der Bahnhof?", final = true)
        g.now()
        val (partial, final) = g.take()
        assertUtt(partial, 7, "Where is", "Wo ist", final = false)
        assertUtt(final, 7, "Where is the station?", "Wo ist der Bahnhof?", final = true)
        assertEquals("en", final.m.getString("from"))
        assertEquals(10L, final.at - partial.at) // not held back for the 250 ms
        p.say(8, "Thanks", "Danke") // the state changes again: 7 is not sent again
        g.pass(GlassesBridge.PARTIAL_MS)
        assertEquals(listOf(8L), g.take().map { it.m.getLong("id") })
    }

    @Test
    fun withoutUnfinishedSentencesOnlyFinishedOnesGoOut() = runTest {
        val g = Glasses(this)
        val p = g.listening(partials = false)
        p.say(7, "Where is", "Wo ist")
        g.pass(300)
        assertTrue(g.take().isEmpty())
        p.say(7, "Where is it?", "Wo ist es?", final = true)
        g.now()
        assertUtt(g.take().single(), 7, "Where is it?", "Wo ist es?", final = true)
    }

    @Test
    fun aSentenceThePipelineDropsIsGone() = runTest {
        val g = Glasses(this)
        val p = g.listening()
        p.say(7, "Hm", "Hm")
        g.now()
        p.say(8, "Hello", "Hallo", final = true)
        g.now()
        g.take()
        p.drop(7) // it was a noise
        p.drop(8) // finished sentences leave the pipeline's list later: nothing to tell
        p.say(9, "", "")
        p.drop(9) // never had any text: the glasses never saw it
        g.now()
        assertGone(g.take().single(), 7)
        // recognised as nothing in the end
        p.say(10, "Äh", "Äh")
        g.pass(GlassesBridge.PARTIAL_MS)
        g.take()
        p.say(10, "", "", final = true)
        g.now()
        assertGone(g.take().single(), 10)
    }

    @Test
    fun configWithOnlyReadingAloudKeepsThePipeline() = runTest {
        val g = Glasses(this)
        val p = g.listening(speak = false)
        assertFalse(p.speak())
        g.say(config(2, speak = true))
        assertState(g.take().single(), running = true, re = 2)
        assertTrue(p.speak())
        assertEquals(1, g.pipelines.built.size)
        assertFalse(p.cancelled)
    }

    @Test
    fun configWithAnotherLanguageStartsAnotherPipeline() = runTest {
        val g = Glasses(this)
        val first = g.listening(to = "de-CH")
        first.say(7, "Hello", "Hallo")
        g.now()
        g.take()
        g.say(config(2, to = "fr"))
        val (gone, loading) = g.take()
        assertGone(gone, 7) // that sentence will not be finished in German any more
        assertState(loading, running = true, note = GlassesBridge.LOADING, re = 2)
        assertTrue(first.cancelled)
        g.pass(LOAD_MS)
        val second = g.pipeline
        assertEquals("fr", second.config.target.code)
        assertTrue(second.started)
        assertState(g.take().single(), running = true, re = 2)
        first.say(8, "late", "spät", final = true) // the old one is not heard any more
        g.now()
        assertTrue(g.take().isEmpty())
    }

    @Test
    fun configWhileNotListeningChangesNothing() = runTest {
        val g = Glasses(this)
        g.say(config(1, to = "fr"))
        assertState(g.take().single(), running = false, re = 1)
        g.say(stop(2))
        assertState(g.take().single(), running = false, re = 2)
        g.pass(LOAD_MS)
        assertTrue(g.pipelines.built.isEmpty())
    }

    @Test
    fun stopFinishesTheSentenceBeingSpoken() = runTest {
        val g = Glasses(this)
        val p = g.listening()
        val finishing = CompletableDeferred<Unit>()
        p.finishing = finishing
        p.say(7, "Where is", "Wo ist")
        g.now()
        g.take()
        g.say(stop(2))
        assertTrue(g.take().isEmpty()) // not yet: the sentence is still being finished
        assertNull(g.activity)
        g.pass(600)
        p.say(7, "Where is the station?", "Wo ist der Bahnhof?", final = true)
        g.now()
        assertUtt(g.take().single(), 7, "Where is the station?", "Wo ist der Bahnhof?", final = true)
        finishing.complete(Unit)
        g.now()
        assertTrue(p.stopped)
        assertState(g.take().single(), running = false, re = 2)
    }

    @Test
    fun stopWhileTheModelsLoadGivesUp() = runTest {
        val g = Glasses(this)
        g.say(start(1))
        g.pass(LOAD_MS / 2)
        g.say(stop(2))
        val (loading, stopped) = g.take()
        assertState(loading, running = true, note = GlassesBridge.LOADING, re = 1)
        assertState(stopped, running = false, re = 2)
        g.pass(LOAD_MS)
        assertTrue(g.pipelines.built.isEmpty())
        assertTrue(g.take().isEmpty())
    }

    @Test
    fun aPipelineThatFinishesLoadingAfterAStopNeverListens() = runTest {
        val g = Glasses(this)
        g.pipelines.finishesLoadingAnyway = true
        g.say(start(1))
        g.pass(LOAD_MS / 2)
        g.say(stop(2))
        g.pass(LOAD_MS)
        val p = g.pipeline
        assertFalse(p.started)
        assertTrue(p.cancelled)
        assertEquals(2, g.take().size) // "loading" and "stopped", nothing after
    }

    @Test
    fun aNewStartDoesNotWaitForTheOldOneToFinish() = runTest {
        val g = Glasses(this)
        val old = g.listening()
        val finishing = CompletableDeferred<Unit>()
        old.finishing = finishing
        old.say(7, "Where", "Wo")
        g.now()
        g.take()
        g.say(stop(2))
        g.say(start(3))
        assertState(g.take().single(), running = true, note = GlassesBridge.LOADING, re = 3)
        g.pass(LOAD_MS)
        val new = g.pipeline
        assertTrue(new !== old && new.started)
        assertState(g.take().single(), running = true, re = 3)
        old.say(7, "Where is it?", "Wo ist es?", final = true) // the old one still finishes its sentence
        g.now()
        assertUtt(g.take().single(), 7, "Where is it?", "Wo ist es?", final = true)
        finishing.complete(Unit)
        g.now()
        assertTrue(old.stopped)
        assertTrue(g.take().isEmpty()) // no "running: false" for request 2: request 3 listens
    }

    @Test
    fun aMissingModelIsReported() = runTest {
        val g = Glasses(this)
        g.pipelines.missing = MissingModelException("Kein Übersetzungsmodell für Japanisch installiert", "Kein Modell für Englisch → Japanisch")
        g.say(start(1, from = "en", to = "ja"))
        g.pass(LOAD_MS)
        val (loading, error, state) = g.take()
        assertState(loading, running = true, note = GlassesBridge.LOADING, re = 1)
        assertError(error, "no_model", "Kein Modell für Englisch → Japanisch")
        assertState(state, running = false, re = 1)
        assertNull(g.activity)
    }

    @Test
    fun anUnknownLanguageIsABadRequest() = runTest {
        val g = Glasses(this)
        g.say(start(1, to = "xx"))
        val (error, state) = g.take()
        assertError(error, "bad_request", "Unbekannte Sprache „xx“")
        assertState(state, running = false, re = 1)
        g.pass(LOAD_MS)
        assertTrue(g.pipelines.built.isEmpty())
    }

    @Test
    fun thePhoneMicrophoneOnlyWhenFluencyMayUseIt() = runTest {
        val g = Glasses(this)
        g.micProblem = "Fluency darf das Handy-Mikrofon nicht nutzen"
        g.say(start(1, source = "phone"))
        val (error, state) = g.take()
        assertError(error, "mic", "Fluency darf das Handy-Mikrofon nicht nutzen")
        assertState(state, running = false, re = 1)
        g.micProblem = null
        g.say(start(2, source = "phone"))
        g.pass(LOAD_MS)
        assertTrue(g.pipeline.started)
        assertEquals(listOf(Source.PHONE), g.sources)
        assertEquals("Handy-Mikrofon, Automatisch → Deutsch (Schweiz)", g.activity)
    }

    @Test
    fun aPipelineThatStopsByItselfIsReported() = runTest {
        val g = Glasses(this)
        val p = g.listening(source = "phone")
        p.fail("Mikrofonfehler")
        g.now()
        val (error, state) = g.take()
        assertError(error, "mic", "Mikrofonfehler")
        assertState(state, running = false) // without "re": Fluency changed by itself
        assertNull(g.activity)
    }

    @Test
    fun noSoundFromTheWatchIsReportedOnce() = runTest {
        val g = Glasses(this)
        g.listening(source = "watch")
        g.pass(GlassesBridge.NO_SOUND_MS - 1_000)
        assertTrue(g.take().isEmpty())
        g.pass(1_000)
        assertError(g.take().single(), "audio", "Kein Ton von der Uhr")
        g.pass(20_000)
        assertTrue(g.take().isEmpty()) // once
        g.watchSoundAt = testScheduler.currentTime // the sound comes back …
        g.pass(1_000)
        assertTrue(g.take().isEmpty())
        g.pass(GlassesBridge.NO_SOUND_MS) // … and stops again
        assertError(g.take().single(), "audio", "Kein Ton von der Uhr")
    }

    @Test
    fun afterCloseNothingIsSent() = runTest {
        val g = Glasses(this)
        val p = g.listening()
        p.say(7, "Hello", "Hallo")
        g.now()
        g.take()
        g.bridge.close()
        assertTrue(p.cancelled)
        p.say(7, "Hello you", "Hallo du", final = true)
        g.say("""{"t":"hello","proto":"fluency-g2@1","app":"1.0.0"}""")
        g.pass(10_000)
        assertTrue(g.take().isEmpty())
        assertNull(g.activity)
    }

    private fun assertState(s: Sent, running: Boolean, note: String? = null, re: Int? = null) {
        assertEquals("state", s.t)
        assertEquals(running, s.m.getBoolean("running"))
        assertEquals(note, s.m.optString("note", null))
        assertEquals(re, if (s.m.has("re")) s.m.getInt("re") else null)
    }

    private fun assertUtt(s: Sent, id: Long, src: String, text: String, final: Boolean) {
        assertEquals("utt", s.t)
        assertEquals(id, s.m.getLong("id"))
        assertEquals(src, s.m.getString("src"))
        assertEquals(text, s.m.getString("text"))
        assertEquals(final, s.m.getBoolean("final"))
    }

    private fun assertGone(s: Sent, id: Long) {
        assertEquals("utt", s.t)
        assertEquals(id, s.m.getLong("id"))
        assertTrue(s.m.getBoolean("gone"))
    }

    private fun assertError(s: Sent, code: String, message: String) {
        assertEquals("error", s.t)
        assertEquals(code, s.m.getString("code"))
        assertEquals(message, s.m.getString("message"))
    }

    companion object {
        private const val LOAD_MS = 1_000L

        private fun start(n: Int, source: String = "watch", from: String = "auto", to: String = "de-CH", speak: Boolean = false, partials: Boolean = true) =
            """{"t":"start","n":$n,"source":"$source","from":"$from","to":"$to","speak":$speak,"partials":$partials}"""

        private fun config(n: Int, source: String = "watch", from: String = "auto", to: String = "de-CH", speak: Boolean = false, partials: Boolean = true) =
            """{"t":"config","n":$n,"source":"$source","from":"$from","to":"$to","speak":$speak,"partials":$partials}"""

        private fun stop(n: Int) = """{"t":"stop","n":$n}"""
    }
}
