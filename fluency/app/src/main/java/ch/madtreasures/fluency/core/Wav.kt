package ch.madtreasures.fluency.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader (PCM 16 bit or float 32, any channel count → mono, any rate). */
object Wav {
    class Audio(val samples: FloatArray, val sampleRate: Int) {
        val seconds: Double get() = samples.size.toDouble() / sampleRate
    }

    fun read(input: InputStream): Audio {
        val bytes = input.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "keine WAV-Datei" }
        var pos = 12
        var format = 0
        var channels = 1
        var rate = 16000
        var bits = 16
        var data: Pair<Int, Int>? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xffff
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> data = body to minOf(len, bytes.size - body)
            }
            pos = body + len + (len and 1)
        }
        val (start, len) = requireNotNull(data) { "WAV ohne Daten" }
        val bytesPerSample = bits / 8
        val frames = len / (bytesPerSample * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val o = start + (f * channels + ch) * bytesPerSample
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat(o)
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    bits == 8 -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                    else -> error("WAV-Format $format/$bits bit nicht unterstützt")
                }
            }
            out[f] = acc / channels
        }
        return Audio(out, rate)
    }

    /** Linear resampling (good enough for test clips). */
    fun resample(audio: Audio, rate: Int): Audio {
        if (audio.sampleRate == rate) return audio
        val n = (audio.samples.size.toLong() * rate / audio.sampleRate).toInt()
        val out = FloatArray(n)
        val step = audio.sampleRate.toDouble() / rate
        for (i in 0 until n) {
            val x = i * step
            val i0 = x.toInt().coerceAtMost(audio.samples.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(audio.samples.lastIndex)
            val t = (x - i0).toFloat()
            out[i] = audio.samples[i0] * (1 - t) + audio.samples[i1] * t
        }
        return Audio(out, rate)
    }
}
