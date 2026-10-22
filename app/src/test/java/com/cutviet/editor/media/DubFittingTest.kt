package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.DubFitting
import com.cutviet.editor.media.dubbing.DubbedSegment
import com.cutviet.editor.model.DubTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DubFittingTest {
    private fun line(start: Long, slot: Long, duration: Long, rate: Float = 1f) =
        DubbedSegment(start, slot, "src", "vi", File("x.wav"), duration, rate)

    @Test
    fun speedsUpLongLinesAndSlowsTheVideoOnlyWhenNeeded() {
        val r = DubFitting.fit(
            listOf(
                line(3000, 2000, 3000),          // 50 % too long: x1.25 and the video at x0.85
                line(1000, 2000, 2200),          // 10 % too long: x1.1 is enough
            ),
            trimStartMs = 0, trimEndMs = 5000, maxDub = 1.25f, minVideo = 0.85f,
        )
        assertEquals(listOf(1000L, 3000L), r.segments.map { it.startMs })
        assertEquals(1.1f, r.segments[0].playbackSpeed, 1e-3f)
        assertEquals(1.25f, r.segments[1].playbackSpeed, 1e-3f)
        assertEquals(listOf(DubTime.Slowdown(3000, 5000, 0.85f)), r.slowdowns)
    }

    @Test
    fun linesThatFitAreLeftAlone() {
        // Read slower by the offline voice to fill its time: not sped back up.
        val r = DubFitting.fit(listOf(line(1000, 2000, 1900, rate = 0.9f)), 0, 5000, 1.4f, 0.8f)
        assertEquals(1f, r.segments.single().playbackSpeed, 0f)
        assertEquals(emptyList<DubTime.Slowdown>(), r.slowdowns)
    }

    @Test
    fun alreadyFastOfflineLinesOnlyGetTheRest() {
        // Read at x1.15 and still too long: needs x1.38 overall, so x1.242 for the voice (the dub
        // plays x1.08 more) and the video at x0.9.
        val r = DubFitting.fit(listOf(line(1000, 2000, 2400, rate = 1.15f)), 0, 3000, 1.4f, 0.8f)
        val s = r.segments.single()
        assertEquals(1.38f * 0.9f / 1.15f, s.playbackSpeed, 1e-3f)
        assertEquals(0.9f, r.slowdowns.single().factor, 2e-3f)
    }
}
