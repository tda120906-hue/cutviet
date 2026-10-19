package com.cutviet.editor.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mixed-radix complex FFT (radices 4, 2, 3, 5 and any other prime, after KISS FFT) for sizes that
 * are not powers of two, such as the 7680-point STFT of the voice separation model.
 * Not thread-safe: use one instance per thread.
 */
class Fft(val n: Int) {
    private val factors: IntArray = factorize(n)
    private val twRe = FloatArray(n)
    private val twIm = FloatArray(n)
    private val outRe = FloatArray(n)
    private val outIm = FloatArray(n)
    private val scratchRe = FloatArray(factors.maxOrNull() ?: 1)
    private val scratchIm = FloatArray(factors.maxOrNull() ?: 1)

    init {
        require(n >= 1)
        for (k in 0 until n) {
            val a = -2.0 * PI * k / n
            twRe[k] = cos(a).toFloat()
            twIm[k] = sin(a).toFloat()
        }
    }

    /** In-place forward transform (no scaling). */
    fun forward(re: FloatArray, im: FloatArray) = run(re, im, inverse = false)

    /** In-place inverse transform, scaled by 1/n so inverse(forward(x)) == x. */
    fun inverse(re: FloatArray, im: FloatArray) {
        run(re, im, inverse = true)
        val s = 1f / n
        for (i in 0 until n) { re[i] *= s; im[i] *= s }
    }

    private var inverseMode = false

    private fun run(re: FloatArray, im: FloatArray, inverse: Boolean) {
        inverseMode = inverse
        work(re, im, 0, 0, 1, 0)
        System.arraycopy(outRe, 0, re, 0, n)
        System.arraycopy(outIm, 0, im, 0, n)
    }

    private fun twIm(k: Int): Float = if (inverseMode) -twIm[k] else twIm[k]

    /** Fills out[outOff until outOff + p*m] with the DFT of in[inOff + j*fstride] (j < p*m). */
    private fun work(inRe: FloatArray, inIm: FloatArray, outOff: Int, inOff: Int, fstride: Int, fi: Int) {
        val p = factors[fi]
        val m = stageLength(fi)
        if (m == 1) {
            for (j in 0 until p) {
                outRe[outOff + j] = inRe[inOff + j * fstride]
                outIm[outOff + j] = inIm[inOff + j * fstride]
            }
        } else {
            for (q in 0 until p) work(inRe, inIm, outOff + q * m, inOff + q * fstride, fstride * p, fi + 1)
        }
        when (p) {
            2 -> bfly2(outOff, fstride, m)
            4 -> bfly4(outOff, fstride, m)
            else -> bflyGeneric(outOff, fstride, m, p)
        }
    }

    private val stageLengths = IntArray(factors.size).also { arr ->
        var m = n
        for (i in factors.indices) { m /= factors[i]; arr[i] = m }
    }

    private fun stageLength(fi: Int) = stageLengths[fi]

    private fun bfly2(off: Int, fstride: Int, m: Int) {
        for (k in 0 until m) {
            val a = off + k
            val b = a + m
            val wr = twRe[k * fstride]
            val wi = twIm(k * fstride)
            val tr = outRe[b] * wr - outIm[b] * wi
            val ti = outRe[b] * wi + outIm[b] * wr
            outRe[b] = outRe[a] - tr; outIm[b] = outIm[a] - ti
            outRe[a] += tr; outIm[a] += ti
        }
    }

    private fun bfly4(off: Int, fstride: Int, m: Int) {
        val sign = if (inverseMode) -1f else 1f
        for (k in 0 until m) {
            val i0 = off + k
            val i1 = i0 + m
            val i2 = i1 + m
            val i3 = i2 + m
            val w1r = twRe[k * fstride]; val w1i = twIm(k * fstride)
            val w2r = twRe[2 * k * fstride]; val w2i = twIm(2 * k * fstride)
            val w3r = twRe[3 * k * fstride]; val w3i = twIm(3 * k * fstride)
            val s0r = outRe[i1] * w1r - outIm[i1] * w1i; val s0i = outRe[i1] * w1i + outIm[i1] * w1r
            val s1r = outRe[i2] * w2r - outIm[i2] * w2i; val s1i = outRe[i2] * w2i + outIm[i2] * w2r
            val s2r = outRe[i3] * w3r - outIm[i3] * w3i; val s2i = outRe[i3] * w3i + outIm[i3] * w3r
            val s5r = outRe[i0] - s1r; val s5i = outIm[i0] - s1i
            val f0r = outRe[i0] + s1r; val f0i = outIm[i0] + s1i
            val s3r = s0r + s2r; val s3i = s0i + s2i
            val s4r = s0r - s2r; val s4i = s0i - s2i
            outRe[i2] = f0r - s3r; outIm[i2] = f0i - s3i
            outRe[i0] = f0r + s3r; outIm[i0] = f0i + s3i
            // Forward: out1 = s5 - i*s4, out3 = s5 + i*s4 (signs swap for the inverse).
            outRe[i1] = s5r + sign * s4i; outIm[i1] = s5i - sign * s4r
            outRe[i3] = s5r - sign * s4i; outIm[i3] = s5i + sign * s4r
        }
    }

    private fun bflyGeneric(off: Int, fstride: Int, m: Int, p: Int) {
        for (u in 0 until m) {
            for (q1 in 0 until p) {
                scratchRe[q1] = outRe[off + u + q1 * m]
                scratchIm[q1] = outIm[off + u + q1 * m]
            }
            var k = u
            for (q1 in 0 until p) {
                var accR = scratchRe[0]
                var accI = scratchIm[0]
                var twidx = 0
                for (q in 1 until p) {
                    twidx += fstride * k
                    if (twidx >= n) twidx -= n
                    val wr = twRe[twidx]; val wi = twIm(twidx)
                    accR += scratchRe[q] * wr - scratchIm[q] * wi
                    accI += scratchRe[q] * wi + scratchIm[q] * wr
                }
                outRe[off + k] = accR
                outIm[off + k] = accI
                k += m
            }
        }
    }

    companion object {
        /** Radix order as in KISS FFT: 4s first, then 2, 3, 5 and larger primes. */
        fun factorize(n: Int): IntArray {
            val out = ArrayList<Int>()
            var x = n
            var p = 4
            while (x > 1) {
                while (x % p != 0) {
                    p = when (p) { 4 -> 2; 2 -> 3; else -> p + 2 }
                    if (p * p > x) p = x
                }
                x /= p
                out += p
            }
            if (out.isEmpty()) out += 1
            return out.toIntArray()
        }
    }
}
