package com.cutviet.editor.media

import com.cutviet.editor.media.dsp.Fft
import com.cutviet.editor.media.dsp.SincResampler
import com.cutviet.editor.media.dsp.StereoStft
import com.cutviet.editor.media.dubbing.Loudness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DspTest {
    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val or = DoubleArray(n)
        val oi = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2 * PI * k * t / n
            or[k] += re[t] * cos(a) - im[t] * sin(a)
            oi[k] += re[t] * sin(a) + im[t] * cos(a)
        }
        return or to oi
    }

    @Test
    fun fftMatchesTheDefinitionForMixedRadixSizes() {
        val rnd = Random(3)
        for (n in listOf(1, 2, 3, 4, 5, 8, 12, 15, 30, 60, 120, 7, 49, 240)) {
            val re = FloatArray(n) { rnd.nextFloat() - 0.5f }
            val im = FloatArray(n) { rnd.nextFloat() - 0.5f }
            val (er, ei) = naiveDft(re, im)
            val r = re.copyOf(); val i = im.copyOf()
            Fft(n).forward(r, i)
            for (k in 0 until n) {
                assertEquals("n=$n re[$k]", er[k], r[k].toDouble(), 1e-3)
                assertEquals("n=$n im[$k]", ei[k], i[k].toDouble(), 1e-3)
            }
            Fft(n).inverse(r, i)
            for (k in 0 until n) {
                assertEquals(re[k], r[k], 1e-4f)
                assertEquals(im[k], i[k], 1e-4f)
            }
        }
    }

    @Test
    fun factorsOfTheSeparationFftSize() {
        assertArrayEquals(intArrayOf(4, 4, 4, 4, 2, 3, 5), Fft.factorize(7680))
    }

    @Test
    fun stftRoundTripIsLossless() {
        // Small sizes with the same structure as the model's (n_fft = 7.5 x hop).
        val stft = StereoStft(nFft = 60, hop = 8, bins = 31)
        val len = 8 * 40
        val rnd = Random(5)
        val l = FloatArray(len) { rnd.nextFloat() - 0.5f }
        val r = FloatArray(len) { rnd.nextFloat() - 0.5f }
        val spec = stft.forward(l, r, len)
        val ol = FloatArray(len); val or = FloatArray(len)
        stft.inverse(spec, len, ol, or)
        for (i in 0 until len) {
            assertEquals("left $i", l[i], ol[i], 1e-4f)
            assertEquals("right $i", r[i], or[i], 1e-4f)
        }
    }

    @Test
    fun stftSeparatesTheChannelsSpectra() {
        // Left: a cosine in bin 3; right: silence. Right's spectrum must stay empty.
        val stft = StereoStft(nFft = 60, hop = 15, bins = 31)
        val len = 15 * 8
        val l = FloatArray(len) { cos(2 * PI * 3 * it / 60).toFloat() }
        val r = FloatArray(len)
        val spec = stft.forward(l, r, len)
        val frames = stft.frames(len)
        var rightEnergy = 0.0
        for (f in 0 until 31) for (t in 0 until frames) {
            rightEnergy += spec[stft.index(1, 0, f, t, frames)].let { it * it } + spec[stft.index(1, 1, f, t, frames)].let { it * it }
        }
        assertTrue(rightEnergy < 1e-6)
        val mid = frames / 2
        val peak = (0 until 31).maxByOrNull { f ->
            val a = spec[stft.index(0, 0, f, mid, frames)]; val b = spec[stft.index(0, 1, f, mid, frames)]
            a * a + b * b
        }
        assertEquals(3, peak)
    }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val rs = SincResampler(inRate, outRate, 1)
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val n = minOf(1000, input.size - i)
            rs.push(input.copyOfRange(i, i + n), n) { out += it[0] }
            i += n
        }
        rs.finish(input.size.toLong()) { out += it[0] }
        return out.toFloatArray()
    }

    @Test
    fun resamplerKeepsAToneAndTheLength() {
        val inRate = 48_000
        val outRate = 44_100
        val hz = 1000.0
        val x = FloatArray(inRate) { (0.5 * sin(2 * PI * hz * it / inRate)).toFloat() }
        val y = resample(x, inRate, outRate)
        assertEquals(outRate, y.size)
        // Away from the edges the output is the same tone at the new rate.
        var err = 0.0
        for (n in 2000 until outRate - 2000) err = maxOf(err, abs(y[n] - 0.5 * sin(2 * PI * hz * n / outRate)))
        assertTrue("max error $err", err < 2e-3)
    }

    @Test
    fun resamplerRemovesWhatTheLowerRateCannotHold() {
        // 12 kHz cannot exist at 16 kHz (Nyquist 8 kHz): it must be filtered, not folded down.
        val x = FloatArray(48_000) { (0.5 * sin(2 * PI * 12_000 * it / 48_000)).toFloat() }
        val y = resample(x, 48_000, 16_000)
        val rms = sqrt(y.copyOfRange(1000, y.size - 1000).map { it.toDouble() * it }.average())
        assertTrue("alias rms $rms", rms < 1e-3)
    }

    @Test
    fun loudnessOfAFullScaleSineIsMinus3Lufs() {
        // BS.1770: a 997 Hz sine at 0 dBFS peak reads -3.01 LUFS.
        val rate = 48_000
        val x = FloatArray(rate * 5) { sin(2 * PI * 997 * it / rate).toFloat() }
        val l = Loudness.integrated(listOf(Loudness.Mono(x, rate)))!!
        assertEquals(-3.01, l, 0.1)
        // Same at the TTS rate.
        val y = FloatArray(24_000 * 5) { (0.1 * sin(2 * PI * 997 * it / 24_000)).toFloat() }
        assertEquals(-23.01, Loudness.integrated(listOf(Loudness.Mono(y, 24_000)))!!, 0.15)
    }
}
