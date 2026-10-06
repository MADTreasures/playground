package ch.madtreasures.fluency.engine.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.TextNormalizer
import ch.madtreasures.fluency.pipeline.Speaker
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * System TTS, restricted to voices that work without network (Samsung/Google offline voices).
 */
class AndroidTtsSpeaker(context: Context, private val rate: () -> Float) : Speaker {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    private fun offlineVoice(language: Language): Voice? {
        val wanted = language.locale
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter {
            !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features &&
                it.locale.language == wanted.language
        }
        return voices.sortedWith(
            compareByDescending<Voice> { it.locale.country == wanted.country }
                .thenByDescending { it.quality }
                .thenBy { it.latency },
        ).firstOrNull()
    }

    override suspend fun speak(text: String, language: Language) {
        if (!ready.await()) return
        val voice = offlineVoice(language) ?: return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.voice = voice
        tts.setSpeechRate(rate())
        // the voice was chosen among those that do not need a network connection
        tts.speak(TextNormalizer.forLanguage(text, language), TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(120_000) { done.await() }
        pending.remove(id)
    }

    override fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    override fun canSpeak(language: Language): Boolean = ready.isCompleted && offlineVoice(language) != null

    fun shutdown() = tts.shutdown()
}

/** Piper (VITS) voices through sherpa-onnx; one cached engine per language. */
class PiperSpeaker(
    /** language code ("de", "en", ...) -> voice directory; espeak-ng-data dir */
    private val voices: () -> Map<String, File>,
    private val espeakDataDir: () -> File?,
    private val rate: () -> Float,
    private val threads: Int = 2,
) : Speaker {
    private val engines = ConcurrentHashMap<String, OfflineTts>()
    @Volatile private var track: AudioTrack? = null

    private fun voiceDir(language: Language): File? {
        val all = voices()
        return all[language.code] ?: all[language.code.substringBefore('-')]
    }

    override fun canSpeak(language: Language): Boolean = voiceDir(language) != null && espeakDataDir() != null

    private fun engine(language: Language): OfflineTts? {
        val dir = voiceDir(language) ?: return null
        val data = espeakDataDir() ?: return null
        return engines.getOrPut(dir.path) {
            val model = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull() ?: return null
            OfflineTts(
                null,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.path,
                            tokens = File(dir, "tokens.txt").path,
                            dataDir = data.path,
                        ),
                        numThreads = threads,
                        provider = "cpu",
                    ),
                ),
            )
        }
    }

    /** Synthesises without playing (benchmark). */
    fun synthesize(text: String, language: Language): Pair<FloatArray, Int>? {
        val tts = engine(language) ?: return null
        val audio = tts.generate(TextNormalizer.forLanguage(text, language), sid = 0, speed = rate())
        return audio.samples to audio.sampleRate
    }

    override suspend fun speak(text: String, language: Language) {
        val (samples, sampleRate) = withContext(Dispatchers.Default) { synthesize(text, language) } ?: return
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
            track = t
            try {
                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                t.play()
                val durationMs = samples.size * 1000L / sampleRate
                val endAt = System.currentTimeMillis() + durationMs + 100
                while (System.currentTimeMillis() < endAt && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    coroutineContext.ensureActive()
                    delay(50)
                }
            } finally {
                runCatching { t.stop() }
                t.release()
                track = null
            }
        }
    }

    override fun stop() {
        runCatching { track?.stop() }
    }

    fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }
}

/** Uses Piper when a voice for the language is installed and selected, else the system TTS. */
class RoutingSpeaker(
    private val android: AndroidTtsSpeaker,
    private val piper: PiperSpeaker,
    private val preferPiper: () -> Boolean,
) : Speaker {
    private fun pick(language: Language): Speaker =
        if (preferPiper() && piper.canSpeak(language)) piper
        else if (android.canSpeak(language)) android
        else if (piper.canSpeak(language)) piper
        else android

    override suspend fun speak(text: String, language: Language) = pick(language).speak(text, language)

    override fun stop() {
        android.stop()
        piper.stop()
    }

    override fun canSpeak(language: Language): Boolean = android.canSpeak(language) || piper.canSpeak(language)
}
