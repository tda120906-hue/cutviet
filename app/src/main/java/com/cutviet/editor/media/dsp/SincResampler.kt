package com.cutviet.editor.media.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming windowed-sinc (Kaiser) sample-rate converter for interleaved float audio. Transparent
 * for listening (passband to about 94% of the lower Nyquist, ~80 dB stop band), which matters
 * because the separated background track is what ends up in the exported video.
 */
class SincResampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val ratio = inRate.toDouble() / outRate
    /** Cutoff as a fraction of the input Nyquist. */
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.94
    private val half = ceil(16 / cutoff).toInt()
    private val taps = 2 * half
    private val phases = 256
    private val table = Array(phases + 1) { p -> FloatArray(taps) { j -> kernel(j - half + 1 - p.toDouble() / phases) } }

    private var hist = Array(channels) { FloatArray(1 shl 14) }
    private var histLen = 0
    /** Absolute input index of hist[c][0]. */
    private var base = -half.toLong() + 1
    private var outIndex = 0L

    init {
        // Leading zeros so the first output sample is centred on input sample 0.
        histLen = half - 1
    }

    private fun kernel(x: Double): Float {
        val w = x / half
        if (w <= -1.0 || w >= 1.0) return 0f
        val s = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
        return (s * besselI0(BETA * sqrt(1 - w * w)) / besselI0(BETA)).toFloat()
    }

    /** Feeds [frames] interleaved frames from [input]; [emit] receives each output frame. */
    fun push(input: FloatArray, frames: Int, emit: (FloatArray) -> Unit) {
        ensure(histLen + frames)
        for (f in 0 until frames) for (c in 0 until channels) hist[c][histLen + f] = input[f * channels + c]
        histLen += frames
        drain(emit)
    }

    /** Pads the end with silence and emits the remaining output. */
    fun finish(totalInputFrames: Long, emit: (FloatArray) -> Unit) {
        ensure(histLen + half)
        for (c in 0 until channels) java.util.Arrays.fill(hist[c], histLen, histLen + half, 0f)
        histLen += half
        val totalOut = floor(totalInputFrames * outRate.toDouble() / inRate + 1e-9).toLong()
        drain(emit, totalOut)
    }

    private val frame = FloatArray(channels)

    private fun drain(emit: (FloatArray) -> Unit, limit: Long = Long.MAX_VALUE) {
        while (outIndex < limit) {
            val t = outIndex * ratio
            val i0 = floor(t).toLong()
            val first = i0 - half + 1 - base
            if (first + taps > histLen) break
            val phase = (t - i0) * phases
            val p0 = phase.toInt().coerceIn(0, phases - 1)
            val w = (phase - p0).toFloat()
            val h0 = table[p0]
            val h1 = table[p0 + 1]
            for (c in 0 until channels) {
                val x = hist[c]
                var acc = 0f
                val start = first.toInt()
                for (j in 0 until taps) acc += x[start + j] * (h0[j] + (h1[j] - h0[j]) * w)
                frame[c] = acc
            }
            emit(frame)
            outIndex++
        }
        // Drop input no longer needed.
        val keepFrom = (floor(outIndex * ratio).toLong() - half + 1 - base).coerceIn(0, histLen.toLong()).toInt()
        if (keepFrom > 0) {
            for (c in 0 until channels) System.arraycopy(hist[c], keepFrom, hist[c], 0, histLen - keepFrom)
            histLen -= keepFrom
            base += keepFrom
        }
    }

    private fun ensure(size: Int) {
        if (size <= hist[0].size) return
        var cap = hist[0].size
        while (cap < size) cap *= 2
        hist = Array(channels) { c -> hist[c].copyOf(cap) }
    }

    companion object {
        private const val BETA = 8.6

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-12 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
