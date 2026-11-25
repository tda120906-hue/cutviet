package com.cutviet.editor.media.denoise

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random access to a 16-bit PCM WAV with a 44-byte header, as [com.cutviet.editor.media.dsp.WavWriter] writes. */
class WavReader(file: File, val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val frames: Long = (raf.length() - 44) / (2L * channels)
    private var bytes = ByteArray(0)

    /** [count] frames from [from] into [out] (interleaved floats); frames past the end are zeros. */
    fun read(from: Long, out: FloatArray, count: Int) {
        val n = (minOf(frames, from + count) - from).coerceAtLeast(0).toInt()
        if (n > 0) {
            val need = n * channels * 2
            if (bytes.size < need) bytes = ByteArray(need)
            raf.seek(44 + from * channels * 2)
            raf.readFully(bytes, 0, need)
            val sb = ByteBuffer.wrap(bytes, 0, need).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until n * channels) out[i] = sb.get(i) / 32768f
        }
        java.util.Arrays.fill(out, n * channels, count * channels, 0f)
    }

    override fun close() = raf.close()
}

/** Little-endian float32 mono samples, written in order and read back at random. */
class FloatFile(val file: File) : Closeable {
    private var out: BufferedOutputStream? = null
    private var raf: RandomAccessFile? = null
    private val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    var count = 0L
        private set

    fun write(x: FloatArray, n: Int) {
        val o = out ?: BufferedOutputStream(FileOutputStream(file), 1 shl 16).also { out = it }
        for (i in 0 until n) {
            if (buf.remaining() < 4) { o.write(buf.array(), 0, buf.position()); buf.clear() }
            buf.putFloat(x[i])
        }
        count += n
    }

    fun finish() {
        out?.let { it.write(buf.array(), 0, buf.position()); buf.clear(); it.close() }
        out = null
    }

    val length: Long get() = file.length() / 4
    private var bytes = ByteArray(0)

    fun read(from: Long, dst: FloatArray, n: Int) {
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        val total = length
        val k = (minOf(total, from + n) - from).coerceAtLeast(0).toInt()
        if (k > 0) {
            if (bytes.size < k * 4) bytes = ByteArray(k * 4)
            r.seek(from * 4)
            r.readFully(bytes, 0, k * 4)
            ByteBuffer.wrap(bytes, 0, k * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dst, 0, k)
        }
        java.util.Arrays.fill(dst, k, n, 0f)
    }

    override fun close() {
        finish()
        raf?.close()
        raf = null
    }
}
