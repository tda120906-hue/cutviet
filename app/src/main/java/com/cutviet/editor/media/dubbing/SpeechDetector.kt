package com.cutviet.editor.media.dubbing

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds where someone speaks in a separated voice track (16 kHz mono), by loudness against the
 * track's own noise floor. Used by the online branch, which needs sentence-sized pieces of audio
 * but has no Whisper timestamps.
 */
object SpeechDetector {
    const val FRAME_MS = 30
    /** Silence shorter than this stays inside one segment. */
    const val JOIN_GAP_MS = 500L
    const val MIN_SPEECH_MS = 300L
    /** Longer speech is cut at its quietest frame near the middle. */
    const val MAX_SEGMENT_MS = 12_000L

    data class Range(val startMs: Long, val endMs: Long)

    fun detect(samples: FloatArray, rate: Int): List<Range> {
        val frame = rate * FRAME_MS / 1000
        if (frame == 0 || samples.size < frame) return emptyList()
        val n = samples.size / frame
        val db = DoubleArray(n) { i ->
            var s = 0.0
            for (k in i * frame until (i + 1) * frame) s += samples[k].toDouble() * samples[k]
            20 * log10(max(sqrt(s / frame), 1e-6))
        }
        // Noise floor: the 10th percentile of frame levels. When someone talks through almost the
        // whole clip that percentile is speech too, so the threshold also stays 20 dB under the
        // loud frames (95th percentile).
        val sorted = db.sorted()
        val floor = sorted[n / 10]
        val loud = sorted[n * 95 / 100]
        val threshold = max(-50.0, min(floor + 12.0, loud - 20.0))
        val voiced = BooleanArray(n) { db[it] > threshold }

        val raw = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (!voiced[i]) { i++; continue }
            var j = i
            while (j < n && voiced[j]) j++
            raw += i until j
            i = j
        }
        // Join close pieces, drop blips.
        val joinFrames = (JOIN_GAP_MS / FRAME_MS).toInt()
        val joined = ArrayList<IntRange>()
        for (r in raw) {
            val last = joined.lastOrNull()
            if (last != null && r.first - last.last - 1 <= joinFrames) joined[joined.lastIndex] = last.first..r.last
            else joined += r
        }
        val minFrames = (MIN_SPEECH_MS / FRAME_MS).toInt()
        val maxFrames = (MAX_SEGMENT_MS / FRAME_MS).toInt()
        val out = ArrayList<Range>()
        fun emit(r: IntRange) {
            if (r.last - r.first + 1 < minFrames) return
            if (r.last - r.first + 1 <= maxFrames) {
                out += Range(r.first.toLong() * FRAME_MS, (r.last + 1).toLong() * FRAME_MS)
                return
            }
            // Cut at the quietest frame in the middle half.
            val len = r.last - r.first + 1
            val from = r.first + len / 4
            val to = r.first + 3 * len / 4
            val cut = (from..to).minByOrNull { db[it] } ?: (r.first + len / 2)
            emit(r.first until cut)
            emit(cut..r.last)
        }
        joined.forEach(::emit)
        return out
    }
}
