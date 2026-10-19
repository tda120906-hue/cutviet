package com.cutviet.editor.media.dsp

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Writes interleaved float frames as a 16-bit PCM WAV file (TPDF dithered), header fixed on close. */
class WavWriter(private val file: File, private val rate: Int, private val channels: Int) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val buf = ByteBuffer.allocate(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)
    private val rnd = Random(1)
    var frames = 0L
        private set

    init {
        out.write(ByteArray(44))
    }

    fun write(samples: FloatArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            if (buf.remaining() < 2) flushBuf()
            val dither = (rnd.nextFloat() - rnd.nextFloat()) / 32768f
            val v = ((samples[i] + dither).coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        frames += count / channels
    }

    private fun flushBuf() {
        out.write(buf.array(), 0, buf.position())
        buf.clear()
    }

    override fun close() {
        flushBuf()
        out.close()
        val dataBytes = frames * channels * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        h.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(h.array()) }
    }
}
