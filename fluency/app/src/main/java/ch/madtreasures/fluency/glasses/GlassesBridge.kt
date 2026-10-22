package ch.madtreasures.fluency.glasses

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.glasses.G2Protocol.Code
import ch.madtreasures.fluency.glasses.G2Protocol.Incoming
import ch.madtreasures.fluency.glasses.G2Protocol.Source
import ch.madtreasures.fluency.pipeline.LivePipeline
import ch.madtreasures.fluency.pipeline.MissingModelException
import ch.madtreasures.fluency.pipeline.PipelineState
import ch.madtreasures.fluency.pipeline.Utterance
import ch.madtreasures.fluency.settings.AUTO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** How the bridge gets its pipelines: in the app from [ch.madtreasures.fluency.pipeline.PipelineFactory], in tests fakes. */
fun interface Pipelines {
    /**
     * Loads the models for [config] and builds a pipeline that listens to [audio] and reports into [state],
     * not started yet; [speak] says, per finished sentence, whether it is read aloud. Throws
     * [MissingModelException] when a model is missing.
     */
    suspend fun build(
        config: LivePipeline.Config,
        audio: AudioSource,
        state: MutableStateFlow<PipelineState>,
        speak: () -> Boolean,
    ): BridgePipeline
}

/** A pipeline as the bridge drives it ([LivePipeline] in the app). */
interface BridgePipeline {
    fun start()

    /** Stops listening; what is still being said is finished first. */
    suspend fun stop()

    /** Stops at once, also before [start]. */
    fun cancel()
}

/**
 * Fluency's side of its app on the G2 glasses: does what the glasses ask ([G2Protocol]: start, config,
 * stop) with a [LivePipeline], like the live screen, and sends back what it hears and translates. How the
 * messages travel is not its business ([GlassesService] in the app, a list in the tests).
 *
 * - Unfinished sentences go out at most every [PARTIAL_MS] (the pipeline changes with every token);
 *   finished ones, dropped ones ("gone"), states and errors at once.
 * - A "config" that changes the sound source or a language starts a new pipeline; one that only changes
 *   reading aloud or unfinished sentences does not.
 * - "stop" finishes what is still being said. A "start" right after does not wait for that.
 * - Without sound from the watch for [NO_SOUND_MS], the glasses hear about it once.
 *
 * Everything runs in [scope], which must use one thread (the main thread in the app).
 */
class GlassesBridge(
    private val scope: CoroutineScope,
    private val pipelines: Pipelines,
    /** the sound for a pipeline */
    private val audio: (Source) -> AudioSource,
    private val send: (String) -> Unit,
    /** Fluency's version, for "welcome" */
    private val version: String,
    /** why the phone's microphone cannot be used now (German, short), or null */
    private val micProblem: () -> String? = { null },
    /** when sound from the watch last arrived (clock ms), 0: never */
    private val watchSoundAt: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
    /** what the bridge does, for the settings ("Uhr-Mikrofon, Englisch → Deutsch"); null: nothing */
    private val onActivity: (String?) -> Unit = {},
) {
    /** One pipeline for the glasses, from loading its models until it is finished or given up. */
    private class Run(@Volatile var request: G2Protocol.Request, var n: Int) {
        val state = MutableStateFlow(PipelineState())
        var pipeline: BridgePipeline? = null
        var job: Job? = null
        var flush: Job? = null

        /** utterance id → what the glasses have of it */
        val sent = HashMap<Long, Sent>()

        /** unfinished sentences waiting for their turn */
        val waiting = LinkedHashMap<Long, Utterance>()
        var lastPartialAt = -PARTIAL_MS
        var startedAt = 0L
        var listening = false
        var warnedNoSound = false
    }

    private data class Sent(val src: String, val text: String, val final: Boolean)

    /** the pipeline for what the glasses want now; null: not listening */
    private var active: Run? = null

    /** pipelines that finish what is still being said after a "stop" */
    private val finishing = HashSet<Run>()
    private var closed = false

    /** A message from the glasses. */
    fun receive(text: String) {
        if (closed) return
        when (val m = G2Protocol.parse(text)) {
            null -> Unit // not for this version: ignored, so the glasses can grow
            is Incoming.Hello -> out(G2Protocol.welcome(version))
            is Incoming.Start -> listen(m.n, m.request, config = false)
            is Incoming.Config -> listen(m.n, m.request, config = true)
            is Incoming.Stop -> stop(m.n)
            is Incoming.Bad -> refuse(m.n, Code.BAD_REQUEST, m.why)
        }
    }

    /** The connection ended: everything stops at once, nothing more is sent. */
    fun close() {
        if (closed) return
        closed = true
        active?.let(::abandon)
        active = null
        finishing.toList().forEach(::abandon)
        activity()
    }

    private fun listen(n: Int, request: G2Protocol.Request, config: Boolean) {
        val target = Languages.byCode(request.to)
        val source = if (request.from == AUTO) null else Languages.byCode(request.from)
        if (target == null || (request.from != AUTO && source == null)) {
            refuse(n, Code.BAD_REQUEST, "Unbekannte Sprache „${if (target == null) request.to else request.from}“")
            return
        }
        val run = active
        if (run == null && config) {
            out(G2Protocol.state(running = false, re = n)) // nothing to change: Fluency does not listen
            return
        }
        if (run != null && run.request.source == request.source && run.request.from == request.from && run.request.to == request.to) {
            // same sound, same languages: the pipeline stays, reading aloud and unfinished sentences change on the fly
            run.request = request
            run.n = n
            if (!request.partials) run.waiting.clear()
            out(G2Protocol.state(running = true, note = if (run.pipeline == null) LOADING else null, re = n))
            activity()
            return
        }
        if (run != null) abandon(run)
        active = null
        if (request.source == Source.PHONE) {
            micProblem()?.let { why ->
                refuse(n, Code.MIC, why)
                return
            }
        }
        launch(Run(request, n), LivePipeline.Config(source = source, target = target, speak = request.speak))
    }

    private fun launch(run: Run, config: LivePipeline.Config) {
        active = run
        out(G2Protocol.state(running = true, note = LOADING, re = run.n))
        activity()
        run.job = scope.launch {
            val p = try {
                pipelines.build(config, audio(run.request.source), run.state) { run.request.speak }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingModelException) {
                lost(run, Code.NO_MODEL, e.brief)
                return@launch
            } catch (e: Exception) {
                lost(run, Code.NO_MODEL, "Modelle laden nicht: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            if (active !== run) {
                p.cancel() // given up while the models were loading
                return@launch
            }
            run.pipeline = p
            run.startedAt = clock()
            p.start()
            out(G2Protocol.state(running = true, re = run.n))
            activity()
            if (run.request.source == Source.WATCH) launch { watchSound(run) }
            run.state.collect { update(run, it) }
        }
    }

    private fun stop(n: Int) {
        val run = active
        active = null
        val p = run?.pipeline
        if (run == null || p == null) {
            run?.let(::abandon) // still loading: nothing to finish
            out(G2Protocol.state(running = false, re = n))
        } else {
            finishing += run
            scope.launch {
                p.stop() // the sentence still being said is finished and sent
                update(run, run.state.value)
                finish(run)
                // after a newer "start", its pipeline answers for Fluency
                if (active == null) out(G2Protocol.state(running = false, re = n))
            }
        }
        activity()
    }

    /** A request Fluency cannot do; [n]: the request, if it had a number. */
    private fun refuse(n: Int?, code: String, why: String) {
        out(G2Protocol.error(code, why))
        if (n != null) out(G2Protocol.state(running = active != null, re = n))
    }

    /** [run] failed before it listened. */
    private fun lost(run: Run, code: String, why: String) {
        if (active !== run) return
        active = null
        out(G2Protocol.error(code, why))
        out(G2Protocol.state(running = false, re = run.n))
        activity()
    }

    /** Gives [run] up at once; the glasses drop what it has not finished. */
    private fun abandon(run: Run) {
        run.job?.cancel()
        run.flush?.cancel()
        run.pipeline?.cancel()
        finishing -= run
        for ((id, sent) in run.sent) if (!sent.final) out(G2Protocol.gone(id))
        run.sent.clear()
        run.waiting.clear()
    }

    /** [run] has finished everything after a stop. */
    private fun finish(run: Run) {
        run.job?.cancel()
        run.flush?.cancel()
        finishing -= run
        for ((id, sent) in run.sent) if (!sent.final) out(G2Protocol.gone(id))
    }

    /** The pipeline changed: what the glasses do not have yet goes out. */
    private fun update(run: Run, s: PipelineState) {
        if (s.running) run.listening = true
        val present = HashSet<Long>()
        for (u in s.utterances) {
            present += u.id
            val had = run.sent[u.id]
            if (had?.final == true) continue
            if (u.final) {
                run.waiting.remove(u.id)
                if (u.sourceText.isBlank() && u.translation.isBlank()) {
                    if (had != null) out(G2Protocol.gone(u.id))
                    run.sent[u.id] = Sent("", "", final = true)
                } else {
                    sendUtterance(run, u, final = true)
                }
            } else if (run.request.partials && (u.sourceText.isNotBlank() || u.translation.isNotBlank()) &&
                (had == null || had.src != u.sourceText || had.text != u.translation)
            ) {
                run.waiting[u.id] = u
            }
        }
        // dropped by the pipeline: a noise, or a sentence that never came to an end
        for (id in run.sent.keys.filter { it !in present }) {
            if (run.sent.getValue(id).final.not()) out(G2Protocol.gone(id))
            run.sent.remove(id)
        }
        run.waiting.keys.retainAll(present)
        flushPartials(run)
        if (run.listening && !s.running && active === run) {
            // stopped by itself: the microphone failed
            active = null
            run.job?.cancel()
            out(G2Protocol.error(if (run.request.source == Source.PHONE) Code.MIC else Code.AUDIO, s.error ?: "Fluency hat aufgehört"))
            out(G2Protocol.state(running = false))
            activity()
        }
    }

    private fun flushPartials(run: Run) {
        if (run.waiting.isEmpty() || run.flush?.isActive == true) return
        val wait = run.lastPartialAt + PARTIAL_MS - clock()
        if (wait > 0) {
            run.flush = scope.launch {
                delay(wait)
                run.flush = null
                flushPartials(run)
            }
            return
        }
        for (u in run.waiting.values) sendUtterance(run, u, final = false)
        run.waiting.clear()
        run.lastPartialAt = clock()
    }

    private fun sendUtterance(run: Run, u: Utterance, final: Boolean) {
        out(G2Protocol.utterance(u.id, u.sourceText, u.translation, final, u.source?.code))
        run.sent[u.id] = Sent(u.sourceText, u.translation, final)
    }

    /** Says once when the watch sends no sound, and again after sound came back and stopped. */
    private suspend fun watchSound(run: Run) {
        while (true) {
            delay(SOUND_CHECK_MS)
            val silent = clock() - maxOf(watchSoundAt(), run.startedAt) >= NO_SOUND_MS
            if (silent && !run.warnedNoSound && active === run) out(G2Protocol.error(Code.AUDIO, "Kein Ton von der Uhr"))
            run.warnedNoSound = silent
        }
    }

    private fun activity() {
        val r = active?.request
        onActivity(r?.let { "${it.source.label}, ${name(it.from)} → ${name(it.to)}" + if (active?.pipeline == null) " (lädt die Modelle)" else "" })
    }

    private fun name(code: String): String = if (code == AUTO) "Automatisch" else Languages.byCode(code)?.nameDe ?: code

    private fun out(message: String) {
        if (!closed) send(message)
    }

    companion object {
        /** at most one message with unfinished sentences per this many ms */
        const val PARTIAL_MS = 250L

        /** how long the watch may send no sound before the glasses hear about it */
        const val NO_SOUND_MS = 5_000L
        private const val SOUND_CHECK_MS = 1_000L
        const val LOADING = "Lädt die Modelle …"
    }
}
