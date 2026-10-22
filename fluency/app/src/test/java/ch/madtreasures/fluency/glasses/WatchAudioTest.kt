package ch.madtreasures.fluency.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The watch's sound: PCM from a pipe becomes float chunks for the pipeline, across stream ends and pipelines. */
class WatchAudioTest {

    /** A pipe as the test feeds it: each [feed] arrives as it is (or in pieces of at most the reader's buffer). */
    private class Pipe : InputStream() {
        private val pieces = LinkedBlockingQueue<ByteArray>()
        private var rest: ByteArray? = null
        private var at = 0

        fun feed(bytes: ByteArray) = pieces.put(bytes)

        /** the writer's end closes: the reader sees the end of the stream */
        fun end() = pieces.put(END)

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (rest == null) {
                val next = pieces.take()
                if (next === END) {
                    pieces.put(END)
                    return -1
                }
                rest = next
                at = 0
            }
            val r = rest!!
            val n = minOf(len, r.size - at)
            System.arraycopy(r, at, b, off, n)
            at += n
            if (at == r.size) rest = null
            return n
        }

        override fun close() = end()

        companion object {
            private val END = ByteArray(0)
        }
    }

    private val chunks = LinkedBlockingQueue<FloatArray>()

    private fun pcm(samples: List<Int>): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun next(): FloatArray? = chunks.poll(2, TimeUnit.SECONDS)

    @Test
    fun pcmBecomesFloatChunksWhateverPiecesThePipeDelivers() {
        val audio = WatchAudio(chunkSamples = 4)
        audio.source().start(onChunk = { chunks.put(it) }, onError = {})
        val pipe = Pipe()
        audio.attach(pipe)
        // 8 samples in pieces that split samples between two reads
        val bytes = pcm(listOf(0, 16384, -16384, 32767, -32768, 1, -1, 8192))
        pipe.feed(bytes.copyOfRange(0, 3))
        pipe.feed(bytes.copyOfRange(3, 4))
        pipe.feed(bytes.copyOfRange(4, 11))
        pipe.feed(bytes.copyOfRange(11, 16))
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f), next(), 0f)
        assertArrayEquals(floatArrayOf(-1f, 1 / 32768f, -1 / 32768f, 0.25f), next(), 0f)
        assertNull(chunks.poll(100, TimeUnit.MILLISECONDS))
        audio.detach()
    }

    @Test
    fun aNewStreamFeedsTheSamePipeline() {
        val audio = WatchAudio(chunkSamples = 2)
        audio.source().start(onChunk = { chunks.put(it) }, onError = {})
        val first = Pipe()
        audio.attach(first)
        first.feed(pcm(listOf(100, 200)))
        assertArrayEquals(floatArrayOf(100 / 32768f, 200 / 32768f), next(), 0f)
        first.end() // the watch turned its microphone off
        val second = Pipe()
        audio.attach(second)
        second.feed(pcm(listOf(300, 400)))
        assertArrayEquals(floatArrayOf(300 / 32768f, 400 / 32768f), next(), 0f)
        audio.detach()
    }

    @Test
    fun soundWithoutAListenerIsDroppedAndOnlyTheNewestPipelineHearsIt() {
        val audio = WatchAudio(chunkSamples = 1)
        val pipe = Pipe()
        audio.attach(pipe)
        pipe.feed(pcm(listOf(1))) // nobody listens yet: dropped, the pipe keeps flowing
        val until = System.currentTimeMillis() + 2_000
        while (audio.lastSoundAt == 0L && System.currentTimeMillis() < until) Thread.sleep(5)
        Thread.sleep(50)

        val old = LinkedBlockingQueue<FloatArray>()
        val first = audio.source()
        first.start(onChunk = { old.put(it) }, onError = {})
        val second = audio.source()
        second.start(onChunk = { chunks.put(it) }, onError = {})
        first.stop() // the old pipeline finishing must not take the sound from the new one
        pipe.feed(pcm(listOf(2)))
        assertArrayEquals(floatArrayOf(2 / 32768f), next(), 0f)
        assertTrue(old.isEmpty())

        second.stop()
        pipe.feed(pcm(listOf(3)))
        assertNull(chunks.poll(200, TimeUnit.MILLISECONDS))
        audio.detach()
    }

    @Test
    fun remembersWhenSoundLastArrived() {
        val now = AtomicLong(1_000)
        val audio = WatchAudio(chunkSamples = 1, clock = now::get)
        assertEquals(0L, audio.lastSoundAt)
        audio.source().start(onChunk = { chunks.put(it) }, onError = {})
        val pipe = Pipe()
        audio.attach(pipe)
        pipe.feed(pcm(listOf(5)))
        next()
        assertEquals(1_000L, audio.lastSoundAt)
        now.set(7_000)
        pipe.feed(pcm(listOf(6)))
        next()
        assertEquals(7_000L, audio.lastSoundAt)
        audio.detach()
    }

    @Test
    fun detachEndsTheStream() {
        val audio = WatchAudio(chunkSamples = 1)
        audio.source().start(onChunk = { chunks.put(it) }, onError = {})
        val pipe = Pipe()
        audio.attach(pipe)
        audio.detach()
        pipe.feed(pcm(listOf(9)))
        assertNull(chunks.poll(200, TimeUnit.MILLISECONDS))
    }
}
