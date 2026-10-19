package com.cutviet.editor.media.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Short-time Fourier transform of a stereo chunk, matching torch.stft / torch.istft with
 * center=True, reflect padding and a periodic Hann window — what the UVR MDX-Net models were
 * trained with. Both channels go through one complex FFT (left as real part, right as imaginary).
 *
 * Spectra are stored as [channel][re/im][bin][frame] flattened, for bins below [bins].
 */
class StereoStft(val nFft: Int, val hop: Int, val bins: Int) {
    private val fft = Fft(nFft)
    private val window = FloatArray(nFft) { i -> (0.5 - 0.5 * cos(2 * PI * i / nFft)).toFloat() }
    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)

    fun frames(length: Int) = 1 + length / hop

    /** Index of (channel c, part 0=re/1=im, bin f, frame t) in a spectrum of [frames] frames. */
    fun index(c: Int, part: Int, f: Int, t: Int, frames: Int) = (((c * 2 + part) * bins + f) * frames) + t

    /** [left]/[right] hold [length] samples; returns 4 * bins * frames floats. */
    fun forward(left: FloatArray, right: FloatArray, length: Int, out: FloatArray = FloatArray(4 * bins * frames(length))): FloatArray {
        val frames = frames(length)
        val pad = nFft / 2
        for (t in 0 until frames) {
            val start = t * hop - pad
            for (i in 0 until nFft) {
                val s = reflect(start + i, length)
                re[i] = left[s] * window[i]
                im[i] = right[s] * window[i]
            }
            fft.forward(re, im)
            for (f in 0 until bins) {
                // Split the two real spectra: L = (Z[f] + conj Z[-f]) / 2, R = (Z[f] - conj Z[-f]) / 2i.
                val g = if (f == 0) 0 else nFft - f
                val zr = re[f]; val zi = im[f]; val wr = re[g]; val wi = -im[g]
                out[index(0, 0, f, t, frames)] = (zr + wr) * 0.5f
                out[index(0, 1, f, t, frames)] = (zi + wi) * 0.5f
                out[index(1, 0, f, t, frames)] = (zi - wi) * 0.5f
                out[index(1, 1, f, t, frames)] = -(zr - wr) * 0.5f
            }
        }
        return out
    }

    /**
     * Inverse of [forward] for a [length]-sample chunk; bins at or above [bins] are taken as zero.
     * Overlap-add normalised by the summed squared window, like torch.istft.
     */
    fun inverse(spec: FloatArray, length: Int, left: FloatArray, right: FloatArray) {
        val frames = frames(length)
        val pad = nFft / 2
        val total = length + 2 * pad
        val accL = FloatArray(total)
        val accR = FloatArray(total)
        val norm = FloatArray(total)
        for (t in 0 until frames) {
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (f in 0 until bins) {
                val lr = spec[index(0, 0, f, t, frames)]; val li = spec[index(0, 1, f, t, frames)]
                val rr = spec[index(1, 0, f, t, frames)]; val ri = spec[index(1, 1, f, t, frames)]
                // Z = L + iR at +f; at -f use the conjugates: Z[-f] = conj(L) + i conj(R).
                re[f] = lr - ri; im[f] = li + rr
                if (f != 0 && f * 2 != nFft) {
                    val g = nFft - f
                    re[g] = lr + ri; im[g] = -li + rr
                }
            }
            fft.inverse(re, im)
            val start = t * hop
            for (i in 0 until nFft) {
                val w = window[i]
                accL[start + i] += re[i] * w
                accR[start + i] += im[i] * w
                norm[start + i] += w * w
            }
        }
        for (i in 0 until length) {
            val n = norm[i + pad]
            val s = if (n > 1e-8f) 1f / n else 0f
            left[i] = accL[i + pad] * s
            right[i] = accR[i + pad] * s
        }
    }

    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var x = i
        while (x < 0 || x >= n) {
            if (x < 0) x = -x
            if (x >= n) x = 2 * (n - 1) - x
        }
        return x
    }
}
