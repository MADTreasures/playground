package ch.madtreasures.fluency.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav16(samples: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(samples.size * 2).put(data.array())
        return b.array()
    }

    @Test fun reads16BitMono() {
        val a = Wav.read(ByteArrayInputStream(wav16(shortArrayOf(0, 16384, -32768, 32767), 16000)))
        assertEquals(16000, a.sampleRate)
        assertEquals(4, a.samples.size)
        assertEquals(0.5f, a.samples[1], 1e-4f)
        assertEquals(-1f, a.samples[2], 1e-4f)
    }

    @Test fun downmixesStereo() {
        val a = Wav.read(ByteArrayInputStream(wav16(shortArrayOf(16384, 0, 16384, 16384), 8000, channels = 2)))
        assertEquals(2, a.samples.size)
        assertEquals(0.25f, a.samples[0], 1e-4f)
        assertEquals(0.5f, a.samples[1], 1e-4f)
    }

    @Test fun resamplesTo16k() {
        val a = Wav.Audio(FloatArray(8000) { 0.1f }, 8000)
        val r = Wav.resample(a, 16000)
        assertEquals(16000, r.samples.size)
        assertEquals(1.0, r.seconds, 1e-6)
    }
}
