package com.cutviet.editor.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Joins 16-bit PCM WAV files of the same format into one, streaming so that hour-long voice-overs
 * never sit in memory. Used when a long text is synthesized piece by piece.
 */
object WavConcat {
    class Format(val channels: Int, val rate: Int, val dataOffset: Long, val dataSize: Long)

    /** Writes [parts] one after another into [out]; returns the length in ms, or null on failure. */
    fun join(parts: List<File>, out: File): Long? {
        if (parts.isEmpty()) return null
        val formats = parts.map { readFormat(it) ?: return null }
        val first = formats[0]
        if (formats.any { it.channels != first.channels || it.rate != first.rate }) return null
        val frameBytes = 2 * first.channels
        val total = formats.sumOf { it.dataSize }
        if (total > 0xFFFF_FFFFL - 36) return null
        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { w ->
            w.setLength(0)
            w.write(header(first.channels, first.rate, total))
            val buf = ByteArray(64 * 1024)
            parts.forEachIndexed { i, f ->
                RandomAccessFile(f, "r").use { r ->
                    r.seek(formats[i].dataOffset)
                    var left = formats[i].dataSize
                    while (left > 0) {
                        val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        w.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        return total / frameBytes * 1000L / first.rate
    }

    fun header(channels: Int, rate: Int, dataSize: Long): ByteArray {
        val frameBytes = 2 * channels
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt((36 + dataSize).toInt()).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
                .putInt(rate).putInt(rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
            put("data".toByteArray()).putInt(dataSize.toInt())
        }.array()
    }

    /** Reads the header of a 16-bit PCM WAV; the data size is taken from the file when the header's is unset. */
    fun readFormat(f: File): Format? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(minOf(r.length(), 64L * 1024).toInt())
            r.readFully(head)
            if (head.size < 44 || String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var channels = 0
            var rate = 0
            var bits = 0
            var format = 0
            while (pos + 8 <= head.size) {
                val id = String(head, pos, 4)
                val size = b.getInt(pos + 4).toLong() and 0xFFFF_FFFFL
                val body = pos + 8
                if (id == "fmt ") {
                    format = b.getShort(body).toInt()
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                } else if (id == "data") {
                    if ((format != 1 && format != -2) || bits != 16 || channels < 1 || rate <= 0) return null
                    val avail = r.length() - body
                    val size2 = if (size <= 0 || size > avail) avail else size
                    return Format(channels, rate, body.toLong(), size2 - size2 % (2 * channels))
                }
                pos = body + size.toInt() + (size.toInt() and 1)
            }
            null
        }
    }.getOrNull()
}
