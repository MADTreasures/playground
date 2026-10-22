package ch.madtreasures.fluency.glasses

import ch.madtreasures.fluency.engine.audio.AudioSource
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The sound of the watch's microphone. The watch app streams it, and "G2 Handy" hands Fluency the read
 * end of a pipe: PCM, 16 bit little-endian, 16 kHz, mono. The stream ends and starts again (the watch
 * turns its microphone off while the glasses pause); a pipeline listens through [source], whichever
 * stream is playing, in chunks of [chunkSamples] like [ch.madtreasures.fluency.engine.audio.MicrophoneSource].
 * Gaps are not filled in.
 */
class WatchAudio(
    private val chunkSamples: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stream = AtomicReference<InputStream?>(null)

    @Volatile private var listener: ((FloatArray) -> Unit)? = null

    /** When sound last arrived (clock ms); 0: never. */
    @Volatile var lastSoundAt = 0L
        private set

    /** Reads [input] from now on, instead of the stream before. */
    fun attach(input: InputStream) {
        stream.getAndSet(input)?.let { old -> runCatching { old.close() } }
        thread(name = "fluency-watch-audio", isDaemon = true) { read(input) }
    }

    /** No sound for now: the watch stopped sending, or the connection ended. */
    fun detach() {
        stream.getAndSet(null)?.let { old -> runCatching { old.close() } } // also ends a read that waits
    }

    /** The sound for a pipeline. One listens at a time: the one started last. */
    fun source(): AudioSource = object : AudioSource {
        private var onChunk: ((FloatArray) -> Unit)? = null

        override fun start(onChunk: (FloatArray) -> Unit, onError: (Throwable) -> Unit) {
            this.onChunk = onChunk
            listener = onChunk
        }

        override fun stop() {
            if (listener === onChunk) listener = null
            onChunk = null
        }
    }

    private fun read(input: InputStream) {
        val bytes = ByteArray(chunkSamples * 2)
        var chunk = FloatArray(chunkSamples)
        var filled = 0
        var low = -1 // the first byte of a sample whose second byte comes with the next read
        try {
            while (true) {
                val n = input.read(bytes)
                if (n < 0 || stream.get() !== input) break
                if (n > 0) lastSoundAt = clock()
                for (i in 0 until n) {
                    val b = bytes[i].toInt() and 0xFF
                    if (low < 0) {
                        low = b
                        continue
                    }
                    chunk[filled++] = ((b shl 8) or low).toShort() / 32768f
                    low = -1
                    if (filled == chunkSamples) {
                        filled = 0
                        val to = listener ?: continue // nobody listens: the sound is dropped
                        to(chunk)
                        chunk = FloatArray(chunkSamples)
                    }
                }
            }
        } catch (_: IOException) {
            // closed: by detach(), or the watch app ended the stream
        } finally {
            stream.compareAndSet(input, null)
            runCatching { input.close() }
        }
    }
}
