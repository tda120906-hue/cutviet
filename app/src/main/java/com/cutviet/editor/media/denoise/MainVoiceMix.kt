package com.cutviet.editor.media.denoise

import com.cutviet.editor.media.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Puts the full-band "main voice only" track together. VoxLock works at 16 kHz, which would leave
 * the voice dull (nothing above 8 kHz). So:
 *  - below about 7 kHz the voice is VoxLock's own output, upsampled;
 *  - above it, the separated 44.1 kHz voice is kept, turned down by how much VoxLock took away in
 *    2–7.8 kHz during each 10 ms ([bandGains]): silent where only other people speak, untouched
 *    where the main voice speaks.
 * The two are joined in the frequency domain (2048-point STFT, linear crossfade 6.5–7.5 kHz) so the
 * join adds no phase shift. Tuned in Python on mixtures with a second talker, music, street and cafe
 * noise: same score as VoxLock alone at 16 kHz, with the high band kept.
 */
object MainVoiceMix {
    const val GAIN_RATE = 100 // gains per second
    private const val GN = 512
    private const val GHOP = 160 // 10 ms at 16 kHz

    /**
     * Amplitude gain per 10 ms of [isolated] against [voice] (both 16 kHz mono, [total] samples) over
     * 2–7.8 kHz, at most 1. [read] fills samples [from, from+count) of one of the two signals.
     */
    fun bandGains(total: Long, rate: Int, readVoice: (Long, FloatArray, Int) -> Unit, readIsolated: (Long, FloatArray, Int) -> Unit): FloatArray {
        val frames = (1 + total / GHOP).toInt()
        val out = FloatArray(frames)
        val fft = Fft(GN)
        val w = FloatArray(GN) { i -> (0.5 - 0.5 * cos(2 * PI * i / GN)).toFloat() }
        val lo = (2000.0 * GN / rate).let { kotlin.math.ceil(it).toInt() }
        val hi = (7800.0 * GN / rate).toInt().coerceAtMost(GN / 2)
        val va = FloatArray(GN); val ya = FloatArray(GN)
        val re = FloatArray(GN); val im = FloatArray(GN)
        // Read in blocks of many frames to keep file access cheap.
        val blockFrames = 256
        val blockLen = (blockFrames - 1) * GHOP + GN
        val vb = FloatArray(blockLen); val yb = FloatArray(blockLen)
        var k0 = 0
        while (k0 < frames) {
            val start = k0.toLong() * GHOP - GN / 2
            readZeroPadded(start, blockLen, total, readVoice, vb)
            readZeroPadded(start, blockLen, total, readIsolated, yb)
            val k1 = min(frames, k0 + blockFrames)
            for (k in k0 until k1) {
                val o = (k - k0) * GHOP
                for (i in 0 until GN) { va[i] = vb[o + i] * w[i]; ya[i] = yb[o + i] * w[i] }
                val ev = bandEnergy(fft, va, re, im, lo, hi)
                val ey = bandEnergy(fft, ya, re, im, lo, hi)
                out[k] = sqrt(min(1.0, ey / (ev + 1e-9))).toFloat()
            }
            k0 = k1
        }
        return out
    }

    private fun bandEnergy(fft: Fft, x: FloatArray, re: FloatArray, im: FloatArray, lo: Int, hi: Int): Double {
        System.arraycopy(x, 0, re, 0, x.size)
        java.util.Arrays.fill(im, 0f)
        fft.forward(re, im)
        var e = 0.0
        for (b in lo..hi) e += re[b].toDouble() * re[b] + im[b].toDouble() * im[b]
        return e
    }

    private fun readZeroPadded(from: Long, count: Int, total: Long, read: (Long, FloatArray, Int) -> Unit, dst: FloatArray) {
        java.util.Arrays.fill(dst, 0, count, 0f)
        val a = maxOf(0L, from)
        val b = minOf(total, from + count)
        if (b <= a) return
        val tmp = FloatArray((b - a).toInt())
        read(a, tmp, tmp.size)
        System.arraycopy(tmp, 0, dst, (a - from).toInt(), tmp.size)
    }

    /**
     * Combines, at [rate] Hz, the separated voice ([channels] interleaved, [total] frames) with the
     * upsampled VoxLock output (mono, same length and rate). [emit] gets the result in order:
     * (interleaved samples, frame count, index of the first frame).
     */
    fun combine(
        total: Long,
        rate: Int,
        channels: Int,
        gains: FloatArray,
        readVoice: (from: Long, out: FloatArray, frames: Int) -> Unit,
        readIsolated: (from: Long, out: FloatArray, frames: Int) -> Unit,
        emit: (FloatArray, Int, Long) -> Unit,
        progress: (Float) -> Unit = {},
        n: Int = 2048,
        hop: Int = 512,
        crossLow: Float = 6500f,
        crossHigh: Float = 7500f,
    ) {
        if (total <= 0) return
        val fft = Fft(n)
        val w = FloatArray(n) { i -> (0.5 - 0.5 * cos(2 * PI * i / n)).toFloat() }
        // Weight of the separated voice per bin (both halves of the spectrum).
        val high = FloatArray(n) { k ->
            val bin = if (k <= n / 2) k else n - k
            val f = bin.toFloat() * rate / n
            ((f - crossLow) / (crossHigh - crossLow)).coerceIn(0f, 1f)
        }
        val frames = (1 + total / hop).toInt()
        val half = n / 2
        val acc = Array(channels) { FloatArray(n) }
        val den = FloatArray(n)
        val vbuf = FloatArray(n * channels)
        val ubuf = FloatArray(n)
        val ure = FloatArray(n); val uim = FloatArray(n)
        val re = FloatArray(n); val im = FloatArray(n)
        val out = FloatArray(hop * channels)
        for (t in 0 until frames) {
            val start = t.toLong() * hop - half
            readFrames(start, n, total, channels, readVoice, vbuf)
            readFrames(start, n, total, 1, readIsolated, ubuf)
            // Gain at this frame's centre.
            val pos = t.toDouble() * hop / rate * GAIN_RATE
            val g0 = floor(pos).toInt()
            val fr = (pos - g0).toFloat()
            val g = if (gains.isEmpty()) 1f else {
                val a = gains[g0.coerceIn(0, gains.lastIndex)]
                val b = gains[(g0 + 1).coerceIn(0, gains.lastIndex)]
                a + (b - a) * fr
            }
            for (i in 0 until n) { ure[i] = ubuf[i] * w[i]; uim[i] = 0f }
            fft.forward(ure, uim)
            for (c in 0 until channels) {
                for (i in 0 until n) { re[i] = vbuf[i * channels + c] * w[i]; im[i] = 0f }
                fft.forward(re, im)
                for (k in 0 until n) {
                    val hw = high[k] * g
                    val lw = 1f - high[k]
                    re[k] = lw * ure[k] + hw * re[k]
                    im[k] = lw * uim[k] + hw * im[k]
                }
                fft.inverse(re, im)
                val a = acc[c]
                for (i in 0 until n) a[i] += re[i] * w[i]
            }
            for (i in 0 until n) den[i] += w[i] * w[i]
            // Padded positions [t*hop, t*hop+hop) are final: no later frame reaches them.
            emitFinal(t.toLong() * hop, hop, half, total, channels, acc, den, out, emit)
            for (c in 0 until channels) shift(acc[c], hop)
            shift(den, hop)
            if (t % 64 == 0) progress(t.toFloat() / frames)
        }
        // Whatever the last frames left.
        var p = frames.toLong() * hop
        var left = n - hop
        val tail = FloatArray(hop * channels)
        while (left > 0) {
            val k = min(hop, left)
            emitFinal(p, k, half, total, channels, acc, den, tail, emit)
            for (c in 0 until channels) shift(acc[c], k)
            shift(den, k)
            p += k
            left -= k
        }
        progress(1f)
    }

    private fun emitFinal(
        paddedStart: Long, count: Int, half: Int, total: Long, channels: Int,
        acc: Array<FloatArray>, den: FloatArray, out: FloatArray, emit: (FloatArray, Int, Long) -> Unit,
    ) {
        var first = -1L
        var m = 0
        for (i in 0 until count) {
            val s = paddedStart + i - half
            if (s < 0 || s >= total) continue
            if (first < 0) first = s
            val d = if (den[i] > 1e-8f) 1f / den[i] else 0f
            for (c in 0 until channels) out[m * channels + c] = acc[c][i] * d
            m++
        }
        if (m > 0) emit(out, m, first)
    }

    private fun readFrames(from: Long, count: Int, total: Long, channels: Int, read: (Long, FloatArray, Int) -> Unit, dst: FloatArray) {
        java.util.Arrays.fill(dst, 0, count * channels, 0f)
        val a = maxOf(0L, from)
        val b = minOf(total, from + count)
        if (b <= a) return
        val tmp = FloatArray(((b - a) * channels).toInt())
        read(a, tmp, (b - a).toInt())
        System.arraycopy(tmp, 0, dst, ((a - from) * channels).toInt(), tmp.size)
    }

    private fun shift(a: FloatArray, by: Int) {
        System.arraycopy(a, by, a, 0, a.size - by)
        java.util.Arrays.fill(a, a.size - by, a.size, 0f)
    }
}
