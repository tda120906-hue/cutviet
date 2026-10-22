package com.cutviet.editor.media.dubbing

import com.cutviet.editor.model.DubTime

/**
 * Last step of a dub: lines still longer than their time are played faster (up to [maxDub]) and,
 * if that is not enough, the video under them slower (down to [minVideo]), as [DubTime.plan] lays
 * out. Lines that fit keep their speed; a line is never slowed down. Pure, so it can be unit tested.
 */
object DubFitting {
    class Result(val segments: List<DubbedSegment>, val slowdowns: List<DubTime.Slowdown>)

    fun fit(segments: List<DubbedSegment>, trimStartMs: Long, trimEndMs: Long, maxDub: Float, minVideo: Float): Result {
        val sorted = segments.sortedBy { it.startMs }
        val room = DubbingPipeline.roomFor(sorted.map { it.startMs to it.slotMs }, trimEndMs)
        val slow = ArrayList<DubTime.Slowdown>()
        val out = sorted.mapIndexed { i, s ->
            val r = room[i]
            if (r <= 0 || s.durationMs <= 0) return@mapIndexed s
            // Length at normal speed: offline lines were already read at their TTS rate.
            val natural = s.durationMs * s.rate.toDouble()
            val plan = DubTime.plan(natural / r, maxDub, minVideo)
            // Fits already (offline lines may have been read a little slower to fill their time).
            if (plan.dubSpeed <= 1f && s.durationMs <= r + DubTime.TOLERANCE_MS) return@mapIndexed s
            val playback = maxOf(1f, plan.dubSpeed / s.rate)
            val played = s.durationMs / playback
            val factor = if (played <= r + DubTime.TOLERANCE_MS) 1f else (r / played).toFloat().coerceIn(minVideo, 1f)
            if (factor < 0.999f) slow += DubTime.Slowdown(s.startMs, s.startMs + r, factor)
            s.copy(playbackSpeed = playback)
        }
        return Result(out, DubTime.normalise(slow, trimStartMs, trimEndMs))
    }
}
