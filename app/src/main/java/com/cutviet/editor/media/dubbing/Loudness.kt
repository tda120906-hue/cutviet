package com.cutviet.editor.media.dubbing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness (ITU-R BS.1770-4 / EBU R128: K-weighting, 400 ms blocks with 75% overlap,
 * absolute and relative gates) of the dubbed voice, and the gain that brings it to a target.
 * All sentences are measured together so the whole dub gets one gain and keeps its dynamics.
 */
object Loudness {
    class Mono(val samples: FloatArray, val rate: Int)

    /** Linear gain that brings [files] (16-bit PCM WAV) to [targetLufs], kept below clipping. */
    fun gainTo(targetLufs: Double, files: List<File>): Float {
        val clips = files.mapNotNull { readWav(it) }
        val lufs = integrated(clips) ?: return 1f
        val peak = clips.maxOfOrNull { c -> c.samples.maxOfOrNull { abs(it) } ?: 0f } ?: 0f
        var gain = 10.0.pow((targetLufs - lufs) / 20.0)
        if (peak > 0f) gain = min(gain, 0.98 / peak) // stay under -0.2 dBFS sample peak
        return gain.coerceIn(0.1, 4.0).toFloat()
    }

    /** Integrated loudness in LUFS, or null when there is nothing above the absolute gate. */
    fun integrated(clips: List<Mono>): Double? {
        val blocks = ArrayList<Double>()
        for (c in clips) blocks += blockPowers(c)
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return null
        val relGate = lufs(abs.average()) - 10.0
        val gated = abs.filter { lufs(it) > relGate }
        if (gated.isEmpty()) return null
        return lufs(gated.average())
    }

    private fun lufs(meanSquare: Double) = -0.691 + 10 * log10(max(meanSquare, 1e-12))

    /** Mean square of the K-weighted signal in each 400 ms block, 100 ms apart. */
    fun blockPowers(c: Mono): List<Double> {
        val y = kWeight(c.samples, c.rate)
        val block = (0.4 * c.rate).toInt()
        val step = (0.1 * c.rate).toInt().coerceAtLeast(1)
        if (y.size < block || block == 0) return emptyList()
        // Prefix sums of squares make every block O(1).
        val prefix = DoubleArray(y.size + 1)
        for (i in y.indices) prefix[i + 1] = prefix[i] + y[i] * y[i]
        val out = ArrayList<Double>()
        var s = 0
        while (s + block <= y.size) {
            out += (prefix[s + block] - prefix[s]) / block
            s += step
        }
        return out
    }

    /** Pre-filter (high shelf) and RLB high-pass of BS.1770, designed for any sample rate. */
    fun kWeight(x: FloatArray, rate: Int): DoubleArray {
        val fs = rate.toDouble()
        // Stage 1: high shelf.
        var k = tan(PI * 1681.974450955533 / fs)
        val vh = 10.0.pow(3.999843853973347 / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val q1 = 0.7071752369554196
        var a0 = 1 + k / q1 + k * k
        val b10 = (vh + vb * k / q1 + k * k) / a0
        val b11 = 2 * (k * k - vh) / a0
        val b12 = (vh - vb * k / q1 + k * k) / a0
        val a11 = 2 * (k * k - 1) / a0
        val a12 = (1 - k / q1 + k * k) / a0
        // Stage 2: high-pass.
        k = tan(PI * 38.13547087602444 / fs)
        val q2 = 0.5003270373238773
        a0 = 1 + k / q2 + k * k
        val a21 = 2 * (k * k - 1) / a0
        val a22 = (1 - k / q2 + k * k) / a0

        val out = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var u1 = 0.0; var u2 = 0.0; var v1 = 0.0; var v2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val yi = b10 * xi + b11 * x1 + b12 * x2 - a11 * y1 - a12 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            val vi = yi - 2 * u1 + u2 - a21 * v1 - a22 * v2
            u2 = u1; u1 = yi; v2 = v1; v1 = vi
            out[i] = vi
        }
        return out
    }

    /** Reads a 16-bit PCM WAV as mono floats (channels averaged); null if it is another format. */
    fun readWav(f: File): Mono? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return@use null
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
                    if ((format != 1 && format != -2) || bits != 16 || channels < 1 || rate <= 0) return@use null
                    val n = (min(size, bytes.size - body) / 2) / channels
                    val out = FloatArray(n)
                    for (i in 0 until n) {
                        var sum = 0f
                        for (c in 0 until channels) sum += b.getShort(body + 2 * (i * channels + c)) / 32768f
                        out[i] = sum / channels
                    }
                    return@use Mono(out, rate)
                }
                pos = body + size + (size and 1)
            }
            null
        }
    }.getOrNull()
}
