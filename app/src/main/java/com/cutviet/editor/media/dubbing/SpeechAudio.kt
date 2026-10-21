package com.cutviet.editor.media.dubbing

import kotlin.math.sqrt

/**
 * Voice pitch, to tell men, women and children apart when no online model is used: adult male
 * voices sit around 85–155 Hz, female 165–255 Hz, children higher still.
 */
object PitchTracker {
    const val MALE_BELOW_HZ = 160f
    const val CHILD_ABOVE_HZ = 300f

    /** Median fundamental frequency (Hz) of the voiced frames of [x] in [from]..[to], or null. */
    fun medianF0(x: FloatArray, rate: Int, from: Int = 0, to: Int = x.size): Float? {
        val frame = (0.04 * rate).toInt()
        val hop = frame / 2
        val minLag = rate / 400
        val maxLag = rate / 70
        val f0s = ArrayList<Float>()
        var start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(x.size)
        while (start + frame + maxLag <= end) {
            var energy = 0.0
            for (k in start until start + frame) energy += x[k].toDouble() * x[k]
            if (sqrt(energy / frame) > 0.01) {
                // Normalised autocorrelation for every candidate period.
                val r = DoubleArray(maxLag + 1)
                for (lag in minLag..maxLag) {
                    var num = 0.0
                    var e2 = 0.0
                    for (k in start until start + frame) {
                        num += x[k].toDouble() * x[k + lag]
                        e2 += x[k + lag].toDouble() * x[k + lag]
                    }
                    r[lag] = num / sqrt(energy * e2 + 1e-12)
                }
                val best = (minLag..maxLag).maxOf { r[it] }
                if (best > 0.6) {
                    // The shortest period that is nearly as good: a period twice as long always
                    // correlates too, and picking it would halve the pitch.
                    val lag = (minLag..maxLag).first { l ->
                        r[l] >= best * 0.9 && (l == minLag || r[l] >= r[l - 1]) && (l == maxLag || r[l] >= r[l + 1])
                    }
                    f0s += rate.toFloat() / lag
                }
            }
            start += hop
        }
        if (f0s.size < 5) return null
        f0s.sort()
        return f0s[f0s.size / 2]
    }

    fun gender(f0: Float): Gender = when {
        f0 < MALE_BELOW_HZ -> Gender.MALE
        f0 > CHILD_ABOVE_HZ -> Gender.CHILD
        else -> Gender.FEMALE
    }
}
