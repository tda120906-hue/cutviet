package com.cutviet.editor.media.dubbing

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cuts one synthesized recording of several lines (read with long pauses between them) back into
 * the lines, at its longest pauses. This lets the natural online voice read a speaker's lines in
 * one request, which saves the free daily quota.
 */
object SilenceSplit {
    /** A pause shorter than this is never taken as the break between two lines. */
    const val MIN_PAUSE_MS = 300

    /**
     * Sample ranges of the [weights].size lines in [x], or null when the pauses are not clear
     * enough to trust (then each line is synthesized on its own). [weights] are the lines'
     * expected lengths (syllables), used to check the cut.
     */
    fun split(x: FloatArray, rate: Int, weights: List<Int>): List<IntRange>? {
        val pieces = weights.size
        if (pieces <= 1) return listOf(x.indices)
        val frame = max(1, rate / 100)
        val n = x.size / frame
        if (n < pieces * 10) return null
        val db = DoubleArray(n) { i ->
            var s = 0.0
            for (k in i * frame until (i + 1) * frame) s += x[k].toDouble() * x[k]
            20 * log10(max(sqrt(s / frame), 1e-6))
        }
        val loud = db.sorted()[(n * 0.95).toInt().coerceAtMost(n - 1)]
        val threshold = max(-60.0, loud - 35.0)
        // Silent runs strictly inside the recording.
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (db[i] >= threshold) { i++; continue }
            var j = i
            while (j < n && db[j] < threshold) j++
            if (i > 0 && j < n) runs += i until j
            i = j
        }
        val chosen = runs.sortedByDescending { it.last - it.first }.take(pieces - 1)
        if (chosen.size < pieces - 1) return null
        if (chosen.any { (it.last - it.first + 1) * 10 < MIN_PAUSE_MS }) return null
        val cuts = chosen.sortedBy { it.first }.map { ((it.first + it.last + 1) / 2) * frame }
        val bounds = listOf(0) + cuts + listOf(x.size)
        val out = (0 until pieces).map { bounds[it] until bounds[it + 1] }
        // Each piece should be roughly as long as its text: a wrong cut gives one line two texts.
        val total = weights.sum().coerceAtLeast(1)
        val perWeight = x.size.toDouble() / total
        val ok = out.indices.all { k ->
            val expected = max(1, weights[k]) * perWeight
            val got = (out[k].last - out[k].first + 1).toDouble()
            got in expected * 0.35..expected * 2.8
        }
        return if (ok) out else null
    }
}

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
