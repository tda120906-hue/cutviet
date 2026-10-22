package com.cutviet.editor.model

import kotlin.math.max
import kotlin.math.min

/**
 * Making a Vietnamese line fit the time of the sentence it replaces: speed the dub up a little, and
 * when that is not enough, play that stretch of the video a little slower. Small changes are hard
 * to hear or see ([SOFT_DUB], [SOFT_VIDEO]), so both are used gently first and pushed to the
 * user's limits only for lines that still do not fit.
 */
object DubTime {
    /** Dub speed-up that sounds natural for Vietnamese speech. */
    const val SOFT_DUB = 1.2f
    /** Video slow-down that hardly shows in normal movement. */
    const val SOFT_VIDEO = 0.9f
    const val DEFAULT_MAX_DUB = 1.25f
    const val DEFAULT_MIN_VIDEO = 0.85f
    /** Video pieces shorter than this are not worth cutting out: neighbouring slow stretches merge. */
    const val MIN_PIECE_MS = 400L
    /** A line this much longer than its time still counts as fitting. */
    const val TOLERANCE_MS = 120L

    /** Source time [startMs]..[endMs] of a clip plays at [factor] (below 1 = slower). */
    data class Slowdown(val startMs: Long, val endMs: Long, val factor: Float)

    /** [dubSpeed]: how much faster the line is read; [videoFactor]: speed of the video under it. */
    data class Plan(val dubSpeed: Float, val videoFactor: Float)

    /**
     * How to fit a line that needs [ratio] times its room at normal speed (1.3 = 30 % too long):
     * dub up to [SOFT_DUB], then video down to [SOFT_VIDEO], then dub up to [maxDub], then video
     * down to [minVideo]. A line that fits (ratio ≤ 1) is left alone.
     */
    fun plan(ratio: Double, maxDub: Float, minVideo: Float): Plan {
        val r = ratio.toFloat()
        if (r <= 1f) return Plan(1f, 1f)
        val softDub = min(SOFT_DUB, maxDub).coerceAtLeast(1f)
        val softVideo = max(SOFT_VIDEO, minVideo).coerceAtMost(1f)
        return when {
            r <= softDub -> Plan(r, 1f)
            r <= softDub / softVideo -> Plan(softDub, softDub / r)
            r <= maxDub / softVideo -> Plan(r * softVideo, softVideo)
            else -> Plan(max(maxDub, softDub), max(minVideo, max(maxDub, softDub) / r).coerceAtMost(1f))
        }
    }

    /**
     * Cleans up the slow stretches of a clip's [trimStart]..[trimEnd]: clipped to it, in order, and
     * with no video piece shorter than [MIN_PIECE_MS] between, before or after them (such gaps join
     * the slow stretch at the slower of the two speeds).
     */
    fun normalise(list: List<Slowdown>, trimStart: Long, trimEnd: Long): List<Slowdown> {
        val sorted = list.map { it.copy(startMs = it.startMs.coerceIn(trimStart, trimEnd), endMs = it.endMs.coerceIn(trimStart, trimEnd)) }
            .filter { it.endMs > it.startMs && it.factor < 0.999f }
            .sortedBy { it.startMs }
        val out = ArrayList<Slowdown>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startMs - last.endMs < MIN_PIECE_MS) {
                out[out.lastIndex] = Slowdown(last.startMs, max(last.endMs, s.endMs), min(last.factor, s.factor))
            } else {
                out += s
            }
        }
        if (out.isNotEmpty() && out.first().startMs - trimStart < MIN_PIECE_MS) out[0] = out[0].copy(startMs = trimStart)
        if (out.isNotEmpty() && trimEnd - out.last().endMs < MIN_PIECE_MS) out[out.lastIndex] = out.last().copy(endMs = trimEnd)
        return out.filter { it.endMs - it.startMs >= MIN_PIECE_MS }
    }

    /** Source time [t] moved by the extra length of the slow stretches before it. */
    fun stretched(t: Long, slowdowns: List<Slowdown>): Long {
        var extra = 0.0
        for (s in slowdowns) {
            val end = min(t, s.endMs)
            if (end > s.startMs) extra += (end - s.startMs) * (1.0 / s.factor - 1.0)
        }
        return t + extra.toLong()
    }

    /** The clip's trim cut into pieces: (start, end, factor), normal stretches at factor 1. */
    fun pieces(trimStart: Long, trimEnd: Long, slowdowns: List<Slowdown>): List<Slowdown> {
        val out = ArrayList<Slowdown>()
        var at = trimStart
        for (s in slowdowns) {
            if (s.startMs > at) out += Slowdown(at, s.startMs, 1f)
            out += s
            at = s.endMs
        }
        if (trimEnd > at) out += Slowdown(at, trimEnd, 1f)
        return out
    }
}
