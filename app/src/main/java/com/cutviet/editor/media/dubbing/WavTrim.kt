package com.cutviet.editor.media.dubbing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Cuts the silence a TTS engine (or Gemini) puts before and after the speech in a 16-bit PCM WAV,
 * so the dubbed line starts exactly where the original sentence starts and its measured length is
 * the speech itself. Google's Vietnamese voice pads about a quarter of a second on each side.
 */
object WavTrim {
    /** Level below which a 10 ms window counts as silence (about -40 dBFS). */
    private const val THRESHOLD = 0.01f
    const val LEAD_MS = 30L
    const val TAIL_MS = 60L

    /** Trims [f] in place; returns the new length in ms, or null if it is not a 16-bit PCM WAV. */
    fun trim(f: File): Long? {
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        val wav = parse(bytes) ?: return null
        val frameBytes = 2 * wav.channels
        val frames = wav.dataSize / frameBytes
        if (frames == 0) return 0
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val window = maxOf(1, wav.rate / 100)
        fun loud(w: Int): Boolean {
            val from = w * window
            val to = minOf(frames, from + window)
            for (i in from until to) for (c in 0 until wav.channels) {
                if (abs(b.getShort(wav.dataOffset + i * frameBytes + 2 * c) / 32768f) > THRESHOLD) return true
            }
            return false
        }
        val windows = (frames + window - 1) / window
        val first = (0 until windows).firstOrNull(::loud) ?: return frames * 1000L / wav.rate // all quiet: keep as is
        val last = (windows - 1 downTo 0).first(::loud)
        val start = maxOf(0, first * window - (LEAD_MS * wav.rate / 1000).toInt())
        val end = minOf(frames, (last + 1) * window + (TAIL_MS * wav.rate / 1000).toInt())
        if (start == 0 && end == frames) return frames * 1000L / wav.rate
        val keep = (end - start) * frameBytes
        val out = ByteBuffer.allocate(44 + keep).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + keep).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(wav.channels.toShort())
            .putInt(wav.rate).putInt(wav.rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(keep)
        out.put(bytes, wav.dataOffset + start * frameBytes, keep)
        f.writeBytes(out.array())
        return (end - start) * 1000L / wav.rate
    }

    private class Wav(val channels: Int, val rate: Int, val dataOffset: Int, val dataSize: Int)

    private fun parse(bytes: ByteArray): Wav? {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                format = b.getShort(body).toInt()
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                if ((format != 1 && format != -2) || bits != 16 || channels < 1 || rate <= 0) return null
                // Some engines leave the size at 0 or too large while streaming; use what is there.
                val size2 = if (size <= 0 || size > bytes.size - body) bytes.size - body else size
                return Wav(channels, rate, body, size2 - size2 % (2 * channels))
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }
}
