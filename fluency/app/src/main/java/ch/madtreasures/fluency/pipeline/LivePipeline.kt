package ch.madtreasures.fluency.pipeline

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Latency
import ch.madtreasures.fluency.core.TextNormalizer
import ch.madtreasures.fluency.engine.asr.AsrEngine
import ch.madtreasures.fluency.engine.asr.AsrResult
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.audio.AudioLevel
import ch.madtreasures.fluency.engine.audio.AudioSource
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class Utterance(
    val id: Long,
    /** conversation mode: 0 = person A (bottom), 1 = person B (top) */
    val speaker: Int,
    val source: Language?,
    val target: Language,
    val sourceText: String,
    val translation: String,
    val final: Boolean,
    val latency: Latency? = null,
    val error: String? = null,
)

data class PipelineState(
    val running: Boolean = false,
    /** which side is recording (conversation mode) */
    val activeSpeaker: Int = 0,
    val speechActive: Boolean = false,
    val level: Float = 0f,
    val utterances: List<Utterance> = emptyList(),
    val status: String? = null,
    val error: String? = null,
)

/**
 * Live speech translation:
 *
 *   mic → Silero VAD ──speech──▶ growing utterance buffer ──every ~400 ms──▶ ASR (partial)
 *                                                         └──▶ latest-wins partial translation (fast model)
 *            └──segment end (VAD silence)──▶ final ASR ──▶ final translation (or reuse of the
 *                                              identical, already finished partial) ──▶ TTS
 *
 * Threads: audio thread → processing thread (VAD, buffers) → ASR thread; translation runs on the
 * model threads of [TranslationEngine]. Finals are processed strictly in order.
 */
class LivePipeline(
    private val config: Config,
    private val audio: AudioSource,
    private val vad: VoiceActivityDetector,
    private val asr: AsrEngine,
    private val translator: Translator,
    private val speaker: Speaker?,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PipelineState>,
    private val clock: () -> Long = System::nanoTime,
    /** read for every finished utterance, so "Vorlesen" can be toggled while running */
    private val speakEnabled: () -> Boolean = { config.speak },
) {
    data class Config(
        /** null = automatic language detection */
        val source: Language?,
        val target: Language,
        val speaker: Int = 0,
        val partialIntervalMs: Long = 400,
        val speak: Boolean = false,
        val muteWhileSpeaking: Boolean = true,
        /** conversation mode: stop after this much silence following an utterance */
        val autoStopSilenceMs: Long? = null,
        val minPartialSamples: Int = SAMPLE_RATE * 4 / 10,
        val preRollSamples: Int = SAMPLE_RATE * 3 / 10,
        val maxUtterances: Int = 200,
    )

    private data class FinalJob(val id: Long, val samples: FloatArray, val segmentEnd: Long, val speechEndNanos: Long)
    private data class PartialRequest(val id: Long, val text: String)
    private data class PartialResult(val source: String, val translation: String, val modelId: String)
    private class Inflight(val req: PartialRequest, val result: CompletableDeferred<TranslationEngine.Result?>)

    /** A partial recognition: [endSample] is the absolute sample index where its audio ended. */
    private class PartialAsr(val id: Long, val endSample: Long, val text: String, val millis: Long)

    private val proc: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "fluency-pipeline") }.asCoroutineDispatcher()
    private val asrThread: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "fluency-asr") }.asCoroutineDispatcher()

    private val chunks = Channel<FloatArray>(Channel.UNLIMITED)
    private val finals = Channel<FinalJob>(Channel.UNLIMITED)
    private val partialRequests = Channel<PartialRequest>(Channel.CONFLATED) // latest wins
    private val speakQueue = Channel<Pair<String, Language>>(Channel.UNLIMITED)

    @Volatile private var muted = false
    @Volatile private var inflight: Inflight? = null
    @Volatile private var lastPartialAsr: PartialAsr? = null
    @Volatile private var partialAsrJob: Job? = null
    @Volatile private var partialAsrFor: Long = -1
    private val partialAsrBusy = AtomicBoolean(false)
    private val partialCache = ConcurrentHashMap<Long, PartialResult>()
    private val finalizing: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val stopping = AtomicBoolean(false)

    // ------------------------------------------------ processing-thread state
    private var startNanos = 0L
    private var currentId: Long? = null
    private var current = FloatArray(SAMPLE_RATE * 4)
    private var currentLen = 0
    private val preRoll = FloatArray(config.preRollSamples)
    private var preRollLen = 0
    private var lastPartialAt = 0L
    private var lastSpeechAt = 0L
    private var hadSpeech = false
    private var lastLevelUpdate = 0L
    private var samplesSeen = 0L
    // pause detection inside an utterance (before the VAD declares the end)
    private var peakRms = 0.0
    private var quietChunks = 0
    private var earlyPartialDone = false
    private var earlyPartialPending = false

    private var processJob: Job? = null
    private var finalJob: Job? = null
    private var partialJob: Job? = null
    private var speakJob: Job? = null

    fun start() {
        startNanos = clock()
        state.update { it.copy(running = true, activeSpeaker = config.speaker, error = null, status = null) }
        processJob = scope.launch(proc) { processLoop() }
        finalJob = scope.launch { for (job in finals) processFinal(job) }
        partialJob = scope.launch { for (req in partialRequests) runPartial(req) }
        if (speaker != null) speakJob = scope.launch { speakLoop() }
        audio.start(
            onChunk = { chunks.trySend(it) },
            onError = { e -> fail(e.message ?: "Mikrofonfehler") },
        )
    }

    /** Stops recording; utterances that are still being spoken or processed are finished first. */
    suspend fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        audio.stop()
        chunks.close()
        processJob?.join()
        finals.close()
        finalJob?.join()
        partialRequests.close()
        translator.cancel(Role.LIVE)
        partialJob?.join()
        speakQueue.close() // queued sentences are still spoken
        release()
        state.update { it.copy(running = false, speechActive = false, level = 0f) }
    }

    /** Immediate stop without finishing pending work. */
    fun cancel() {
        if (!stopping.compareAndSet(false, true)) return
        audio.stop()
        chunks.close()
        finals.close()
        partialRequests.close()
        speakQueue.close()
        translator.cancel(null)
        speaker?.stop()
        listOfNotNull(processJob, finalJob, partialJob, speakJob).forEach { it.cancel() }
        scope.launch {
            processJob?.join()
            release()
        }
        state.update { s ->
            s.copy(running = false, speechActive = false, level = 0f, utterances = s.utterances.filter { it.final || it.sourceText.isNotBlank() })
                .let { st -> st.copy(utterances = st.utterances.map { if (!it.final) it.copy(final = true) else it }) }
        }
    }

    private fun release() {
        runCatching { vad.close() }
        proc.close()
        asrThread.close()
    }

    private fun fail(message: String) {
        state.update { it.copy(error = message) }
        scope.launch { stop() }
    }

    // ------------------------------------------------------------------------------- processing

    private suspend fun processLoop() {
        for (chunk in chunks) {
            handle(if (muted) FloatArray(chunk.size) else chunk)
        }
        // end of stream: whatever is still being said becomes a final segment
        vad.flush()
        handleSegments()
        currentId?.let { id -> removeUtterance(id) }
        currentId = null
    }

    private fun handle(chunk: FloatArray) {
        val now = clock()
        vad.accept(chunk)
        samplesSeen += chunk.size
        val speech = vad.isSpeech()
        if (speech) {
            lastSpeechAt = now
            hadSpeech = true
            if (currentId == null) {
                currentId = newUtterance()
                currentLen = 0
                append(preRoll, preRollLen)
                lastPartialAt = now
                peakRms = 0.0
                quietChunks = 0
                earlyPartialDone = false
            }
        }
        if (currentId != null) detectPause(chunk)
        if (currentId != null) append(chunk, chunk.size)
        pushPreRoll(chunk)
        handleSegments()

        // a speech start without a segment (very short noise): drop it after a while
        if (!speech && currentId != null && now - lastSpeechAt > 3_000_000_000L) {
            removeUtterance(currentId!!)
            currentId = null
        }

        if (speech != state.value.speechActive || now - lastLevelUpdate > 60_000_000L) {
            lastLevelUpdate = now
            val level = AudioLevel.of(chunk)
            state.update { it.copy(speechActive = speech, level = level) }
        }
        maybePartial(now)
        maybeAutoStop(now)
    }

    private fun handleSegments() {
        for (seg in vad.drain()) {
            val id = currentId ?: newUtterance()
            currentId = null
            currentLen = 0
            finalizing += id
            earlyPartialPending = false
            val endNanos = startNanos + seg.end * 1_000_000_000L / SAMPLE_RATE
            finals.trySend(FinalJob(id, seg.samples, seg.end, endNanos))
        }
    }

    private fun append(src: FloatArray, n: Int) {
        if (n <= 0) return
        if (currentLen + n > current.size) current = current.copyOf(maxOf(current.size * 2, currentLen + n))
        System.arraycopy(src, 0, current, currentLen, n)
        currentLen += n
    }

    private fun pushPreRoll(chunk: FloatArray) {
        val cap = preRoll.size
        if (cap == 0) return
        if (chunk.size >= cap) {
            System.arraycopy(chunk, chunk.size - cap, preRoll, 0, cap)
            preRollLen = cap
            return
        }
        val keep = minOf(preRollLen, cap - chunk.size)
        System.arraycopy(preRoll, preRollLen - keep, preRoll, 0, keep)
        System.arraycopy(chunk, 0, preRoll, keep, chunk.size)
        preRollLen = keep + chunk.size
    }

    /**
     * Speech pause inside an utterance: ~160 ms clearly below the utterance's peak level. The VAD
     * needs [VoiceActivityDetector] min-silence more before it closes the segment; recognising and
     * translating right now gives the final result a head start (it is reused at segment end).
     */
    private fun detectPause(chunk: FloatArray) {
        var sum = 0.0
        for (x in chunk) sum += x * x
        val rms = kotlin.math.sqrt(sum / chunk.size)
        if (rms > peakRms) peakRms = rms
        if (rms < maxOf(0.003, peakRms * 0.12)) {
            quietChunks++
            if (quietChunks == 5 && !earlyPartialDone) {
                earlyPartialDone = true
                earlyPartialPending = true
            }
        } else {
            quietChunks = 0
            earlyPartialDone = false
        }
    }

    private fun maybePartial(now: Long) {
        val id = currentId ?: return
        val forced = earlyPartialPending
        // slow recognisers (Whisper) only decode once per speech pause, fast ones also periodically
        if (!asr.supportsFastPartials && !forced) return
        if (!forced && now - lastPartialAt < config.partialIntervalMs * 1_000_000L) return
        if (currentLen < config.minPartialSamples) return
        if (!partialAsrBusy.compareAndSet(false, true)) return
        earlyPartialPending = false
        lastPartialAt = now
        val audio = current.copyOf(currentLen)
        val endSample = samplesSeen
        partialAsrFor = id
        partialAsrJob = scope.launch(asrThread) {
            try {
                if (id in finalizing) return@launch
                val r = asr.transcribe(audio, config.source)
                val text = normalizeSource(r.text)
                lastPartialAsr = PartialAsr(id, endSample, text, r.millis)
                if (text.isNotBlank() && id !in finalizing) {
                    updateUtterance(id) { if (it.final) it else it.copy(sourceText = text) }
                    partialRequests.trySend(PartialRequest(id, text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // a failed partial is not fatal: the final decode follows
            } finally {
                partialAsrBusy.set(false)
            }
        }
    }

    private fun maybeAutoStop(now: Long) {
        val ms = config.autoStopSilenceMs ?: return
        if (hadSpeech && currentId == null && now - lastSpeechAt > ms * 1_000_000L && !stopping.get()) {
            scope.launch { stop() }
        }
    }

    // --------------------------------------------------------------------------- translation

    private suspend fun runPartial(req: PartialRequest) {
        if (req.id in finalizing) return
        val deferred = CompletableDeferred<TranslationEngine.Result?>()
        inflight = Inflight(req, deferred)
        try {
            val res = translator.translate(TranslationEngine.Request(req.text, config.source, config.target, Role.LIVE)) { partial ->
                if (req.id !in finalizing) updateUtterance(req.id) { if (it.final) it else it.copy(translation = partial) }
            }
            if (!res.cancelled) {
                partialCache[req.id] = PartialResult(req.text, res.text, res.modelId)
                if (req.id !in finalizing) updateUtterance(req.id) { if (it.final) it else it.copy(translation = res.text) }
            }
            deferred.complete(res)
        } catch (e: CancellationException) {
            deferred.complete(null)
            throw e
        } catch (e: Exception) {
            deferred.complete(null)
            if (req.id !in finalizing) state.update { it.copy(status = e.message) }
        } finally {
            if (inflight?.req == req) inflight = null
        }
    }

    private suspend fun processFinal(job: FinalJob) {
        try {
            // a partial recognition of this utterance may still be running: it might cover it all
            if (partialAsrFor == job.id) partialAsrJob?.join()
            val early = lastPartialAsr?.takeIf {
                it.id == job.id && it.text.isNotBlank() && it.endSample >= job.segmentEnd - SAMPLE_RATE / 10
            }
            val asrRes = if (early != null) {
                AsrResult(early.text, early.millis)
            } else {
                withContext(asrThread) { asr.transcribe(job.samples, config.source) }
            }
            val text = if (early != null) early.text else normalizeSource(asrRes.text)
            if (text.isBlank()) {
                removeUtterance(job.id)
                return
            }
            updateUtterance(job.id) { it.copy(sourceText = text) }

            val finalModel = translator.modelFor(Role.FINAL, config.source, config.target)
            val liveModel = translator.modelFor(Role.LIVE, config.source, config.target)
            var reused: String? = partialCache[job.id]?.takeIf { it.source == text && it.modelId == finalModel }?.translation
            if (reused == null) {
                val inf = inflight
                if (inf != null && inf.req.id == job.id && inf.req.text == text && liveModel == finalModel) {
                    reused = inf.result.await()?.takeIf { !it.cancelled }?.text
                }
            }
            val mtStart = clock()
            var result: TranslationEngine.Result? = null
            val translation = reused ?: run {
                translator.cancel(Role.LIVE)
                translator.translate(TranslationEngine.Request(text, config.source, config.target, Role.FINAL)) { partial ->
                    updateUtterance(job.id) { it.copy(translation = partial) }
                }.also { result = it }.text
            }
            val now = clock()
            val latency = Latency(
                asrMs = asrRes.millis,
                reusedAsr = early != null,
                mtMs = if (reused != null) 0 else (now - mtStart) / 1_000_000,
                totalMs = ((now - job.speechEndNanos) / 1_000_000).coerceAtLeast(0),
                tokensPerSecond = result?.tokensPerSecond,
                reusedPartial = reused != null,
                model = result?.modelName,
            )
            updateUtterance(job.id) { it.copy(translation = translation, final = true, latency = latency) }
            partialCache.remove(job.id)
            if (speaker != null && speakEnabled() && translation.isNotBlank()) speakQueue.trySend(translation to config.target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateUtterance(job.id) { it.copy(final = true, error = e.message ?: "Fehler") }
        }
    }

    private suspend fun speakLoop() {
        val sp = speaker ?: return
        for ((text, lang) in speakQueue) {
            if (config.muteWhileSpeaking) muted = true
            try {
                sp.speak(text, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                delay(200) // room echo
                muted = false
            }
        }
    }

    // ------------------------------------------------------------------------------- state

    private fun normalizeSource(text: String): String = TextNormalizer.forLanguage(text.trim(), config.source)

    private fun newUtterance(): Long {
        val id = nextId.incrementAndGet()
        val u = Utterance(id, config.speaker, config.source, config.target, "", "", final = false)
        state.update { s -> s.copy(utterances = (s.utterances + u).takeLast(config.maxUtterances)) }
        return id
    }

    private fun updateUtterance(id: Long, f: (Utterance) -> Utterance) {
        state.update { s -> s.copy(utterances = s.utterances.map { if (it.id == id) f(it) else it }) }
    }

    private fun removeUtterance(id: Long) {
        state.update { s -> s.copy(utterances = s.utterances.filterNot { it.id == id }) }
    }

    companion object {
        private val nextId = AtomicLong(0)
    }
}
