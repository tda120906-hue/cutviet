package com.cutviet.editor.media.dubbing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds where someone speaks in a separated voice track (16 kHz mono), by loudness against the
 * track's own noise floor. The subtitles use segments of a few sentences ([detect] with its
 * defaults); the dub uses one sentence per piece ([sentences]), each dubbed on its own and placed
 * exactly where its speech starts.
 */
object SpeechDetector {
    const val FRAME_MS = 30
    /** Silence shorter than this stays inside one segment. */
    const val JOIN_GAP_MS = 500L
    const val MIN_SPEECH_MS = 300L
    /** Longer speech is cut at its quietest frame near the middle. */
    const val MAX_SEGMENT_MS = 12_000L

    /** For the dub: a pause this long ends a sentence. */
    const val SENTENCE_GAP_MS = 300L
    /** For the dub: longest sentence before it is cut at its quietest point. */
    const val SENTENCE_MAX_MS = 8_000L
    /** For the dub: a piece shorter than this joins its nearer neighbour when the pause between is short. */
    const val SENTENCE_MIN_MS = 800L
    /** The pause a short piece may bridge to join a neighbour. */
    const val SENTENCE_BRIDGE_MS = 700L

    data class Range(val startMs: Long, val endMs: Long) {
        val lengthMs: Long get() = endMs - startMs
    }

    fun detect(samples: FloatArray, rate: Int, joinGapMs: Long = JOIN_GAP_MS, maxSegmentMs: Long = MAX_SEGMENT_MS): List<Range> {
        val frame = rate * FRAME_MS / 1000
        if (frame == 0 || samples.size < frame) return emptyList()
        val n = samples.size / frame
        val db = DoubleArray(n) { i ->
            var s = 0.0
            for (k in i * frame until (i + 1) * frame) s += samples[k].toDouble() * samples[k]
            level(s, frame)
        }
        return fromLevels(db, joinGapMs, maxSegmentMs)
    }

    /** The dub's pieces: one sentence each, short pieces joined to a neighbour. */
    fun sentences(samples: FloatArray, rate: Int): List<Range> =
        joinShort(detect(samples, rate, SENTENCE_GAP_MS, SENTENCE_MAX_MS))

    /**
     * [detect] on a little-endian float file at [rate], read a little at a time so a long video's
     * voice never has to fit in memory at once.
     */
    fun detectFile(file: File, rate: Int, joinGapMs: Long = SENTENCE_GAP_MS, maxSegmentMs: Long = SENTENCE_MAX_MS): List<Range> {
        val frame = rate * FRAME_MS / 1000
        val n = (file.length() / 4 / frame).toInt()
        if (frame == 0 || n == 0) return emptyList()
        val db = DoubleArray(n)
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(frame * 4 * 200)
            var i = 0
            while (i < n) {
                val frames = min(200, n - i)
                raf.readFully(bytes, 0, frames * frame * 4)
                val fb = ByteBuffer.wrap(bytes, 0, frames * frame * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (f in 0 until frames) {
                    var s = 0.0
                    repeat(frame) { val x = fb.get().toDouble(); s += x * x }
                    db[i + f] = level(s, frame)
                }
                i += frames
            }
        }
        return fromLevels(db, joinGapMs, maxSegmentMs)
    }

    private fun level(sumSquares: Double, frame: Int) = 20 * log10(max(sqrt(sumSquares / frame), 1e-6))

    /** Speech ranges from per-frame levels in dB. */
    fun fromLevels(db: DoubleArray, joinGapMs: Long, maxSegmentMs: Long): List<Range> {
        val n = db.size
        if (n == 0) return emptyList()
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
        val joinFrames = (joinGapMs / FRAME_MS).toInt()
        val joined = ArrayList<IntRange>()
        for (r in raw) {
            val last = joined.lastOrNull()
            if (last != null && r.first - last.last - 1 <= joinFrames) joined[joined.lastIndex] = last.first..r.last
            else joined += r
        }
        val minFrames = (MIN_SPEECH_MS / FRAME_MS).toInt()
        val maxFrames = (maxSegmentMs / FRAME_MS).toInt()
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

    /**
     * Whisper's sentences trimmed to the speech heard in them: a sentence that starts in silence
     * (Whisper often starts one where the last ended) starts where the voice really starts, and one
     * that ends in silence ends with the voice. Times are only ever moved inwards, so a dub line
     * never starts before the mouth moves; a sentence with no speech found, or that would be left
     * shorter than [MIN_SPEECH_MS], stays as it was. [units] are speech ranges on the same timeline.
     */
    fun tighten(segments: List<SpeechSegment>, units: List<Range>): List<SpeechSegment> {
        if (units.isEmpty()) return segments
        fun covered(t: Long) = units.any { t >= it.startMs && t < it.endMs }
        return segments.map { seg ->
            val inside = units.filter { it.endMs > seg.startMs && it.startMs < seg.endMs }
            if (inside.isEmpty()) return@map seg
            val start = if (covered(seg.startMs)) seg.startMs else maxOf(seg.startMs, inside.first().startMs)
            val end = if (covered(seg.endMs - 1)) seg.endMs else minOf(seg.endMs, inside.last().endMs)
            if (end - start < MIN_SPEECH_MS || (start == seg.startMs && end == seg.endMs)) seg
            else seg.copy(startMs = start, endMs = end)
        }
    }

    /**
     * A piece shorter than [minMs] (a word or two) joins the neighbour it is closer to, when the
     * pause to it is at most [bridgeMs] and the joined piece stays within [maxMs]; otherwise it
     * stays a line of its own.
     */
    fun joinShort(
        ranges: List<Range>,
        minMs: Long = SENTENCE_MIN_MS,
        bridgeMs: Long = SENTENCE_BRIDGE_MS,
        maxMs: Long = SENTENCE_MAX_MS,
    ): List<Range> {
        val out = ranges.toMutableList()
        var i = 0
        while (i < out.size) {
            val r = out[i]
            if (r.lengthMs >= minMs) { i++; continue }
            val before = out.getOrNull(i - 1)?.takeIf { r.startMs - it.endMs <= bridgeMs && r.endMs - it.startMs <= maxMs }
            val after = out.getOrNull(i + 1)?.takeIf { it.startMs - r.endMs <= bridgeMs && it.endMs - r.startMs <= maxMs }
            val joinBefore = when {
                before == null -> false
                after == null -> true
                else -> r.startMs - before.endMs <= after.startMs - r.endMs
            }
            // A joined piece is looked at again, as it may still be short.
            when {
                joinBefore -> { out[i - 1] = Range(before!!.startMs, r.endMs); out.removeAt(i); i = max(0, i - 1) }
                after != null -> { out[i] = Range(r.startMs, after.endMs); out.removeAt(i + 1) }
                else -> i++
            }
        }
        return out
    }
}
