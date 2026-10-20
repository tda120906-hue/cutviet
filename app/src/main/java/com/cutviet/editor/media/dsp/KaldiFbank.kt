package com.cutviet.editor.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Kaldi-compatible log mel filterbank, as kaldi-native-fbank computes it for the speaker
 * embedding model: 25 ms Povey windows every 10 ms (frames centred, edges reflected), DC removal,
 * 0.97 pre-emphasis, 512-point power spectrum, [bins] mel bands from 20 Hz to Nyquist - 400 Hz.
 * Not thread-safe: use one instance per thread.
 */
class KaldiFbank(private val rate: Int = 16_000, val bins: Int = 80) {
    private val length = rate * 25 / 1000
    private val shift = rate / 100
    private val nFft = Integer.highestOneBit(length - 1) shl 1
    private val fft = Fft(nFft)
    private val window = DoubleArray(length) { (0.5 - 0.5 * cos(2 * PI * it / (length - 1))).pow(0.85) }

    /** Each band's first FFT bin and its weights from there. */
    private val bandStart = IntArray(bins)
    private val bandWeights = arrayOfNulls<FloatArray>(bins)

    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)
    private val frame = DoubleArray(length)

    init {
        fun mel(f: Double) = 1127.0 * ln(1 + f / 700.0)
        val lowMel = mel(20.0)
        val highMel = mel(rate / 2.0 - 400.0)
        val delta = (highMel - lowMel) / (bins + 1)
        val binHz = rate.toDouble() / nFft
        for (b in 0 until bins) {
            val left = lowMel + b * delta
            val center = left + delta
            val right = center + delta
            val w = DoubleArray(nFft / 2)
            var first = -1
            var last = -1
            for (i in 0 until nFft / 2) {
                val m = mel(binHz * i)
                if (m > left && m < right) {
                    w[i] = if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) {
                first = 0
                last = -1
            }
            bandStart[b] = first
            bandWeights[b] = FloatArray(last - first + 1) { w[first + it].toFloat() }
        }
    }

    fun frames(samples: Int) = (samples + shift / 2) / shift

    /** Features of [x] (samples -1..1), frames × [bins] in rows. */
    fun compute(x: FloatArray, count: Int = x.size): FloatArray {
        val n = count
        val frames = frames(n)
        val out = FloatArray(frames * bins)
        if (n == 0) return out
        for (f in 0 until frames) {
            val start = f * shift + shift / 2 - length / 2
            var mean = 0.0
            for (i in 0 until length) {
                var s = start + i
                while (s < 0 || s >= n) s = if (s < 0) -s - 1 else 2 * n - 1 - s
                frame[i] = x[s].toDouble()
                mean += frame[i]
            }
            mean /= length
            for (i in 0 until length) frame[i] -= mean
            for (i in length - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            frame[0] -= 0.97 * frame[0]
            for (i in 0 until nFft) {
                re[i] = if (i < length) (frame[i] * window[i]).toFloat() else 0f
                im[i] = 0f
            }
            fft.forward(re, im)
            for (b in 0 until bins) {
                val w = bandWeights[b]!!
                val from = bandStart[b]
                var e = 0.0
                for (k in w.indices) {
                    val r = re[from + k].toDouble()
                    val m = im[from + k].toDouble()
                    e += w[k] * (r * r + m * m)
                }
                out[f * bins + b] = ln(max(e, EPSILON)).toFloat()
            }
        }
        return out
    }

    companion object {
        /** FLT_EPSILON, Kaldi's floor before the log. */
        private const val EPSILON = 1.1920928955078125e-7
    }
}
