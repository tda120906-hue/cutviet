package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.AudioFitter
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioFitterTest {
    /** A fake voice: [atOne] ms long at rate 1.0, inversely proportional to the rate, plus [bias]. */
    private fun voice(atOne: Long, bias: Long = 0): suspend (Float) -> Long? = { r -> (atOne / r).toLong() + bias }

    @Test
    fun fitsWithinToleranceWhenTheRateCanReach() = runBlocking {
        val fit = AudioFitter.fit(estimateAtOneMs = 2600, slotMs = 2400, synthesize = voice(2700))!!
        assertTrue(fit.withinTolerance)
        assertTrue(abs(fit.durationMs - 2400) <= AudioFitter.TOLERANCE_MS)
        assertTrue(fit.rate in AudioFitter.MIN_RATE..AudioFitter.MAX_RATE)
    }

    @Test
    fun correctsAWrongEstimateWithTheMeasuredLength() = runBlocking {
        // The estimate says it fits at 1.0, but the real voice is 10% slower.
        val fit = AudioFitter.fit(estimateAtOneMs = 3000, slotMs = 3000, synthesize = voice(3300))!!
        assertTrue(fit.attempts >= 2)
        assertTrue(fit.withinTolerance)
    }

    @Test
    fun neverLeavesTheSafeRateRange() = runBlocking {
        val fast = AudioFitter.fit(estimateAtOneMs = 6000, slotMs = 2000, synthesize = voice(6000))!!
        assertEquals(AudioFitter.MAX_RATE, fast.rate, 1e-4f)
        assertTrue(!fast.withinTolerance)
        val slow = AudioFitter.fit(estimateAtOneMs = 1000, slotMs = 4000, synthesize = voice(1000))!!
        assertEquals(AudioFitter.MIN_RATE, slow.rate, 1e-4f)
    }

    @Test
    fun stopsAfterTheAttemptLimitAndOnFailure() = runBlocking {
        var calls = 0
        val fit = AudioFitter.fit(2000, 2000) { r -> calls++; (2000 / r).toLong() + 900 }
        assertTrue(calls <= AudioFitter.MAX_ATTEMPTS)
        assertTrue(fit != null)
        assertNull(AudioFitter.fit(2000, 2000) { null })
    }

    @Test
    fun dubLinesLandOnTheTimelineAndMuteTheClip() {
        val a = Clip(uri = "a", mime = "video/mp4", isImage = false, sourceDurationMs = 4000, width = 1920, height = 1080)
        val b = Clip(uri = "b", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 1920, height = 1080, trimStartMs = 2000, speed = 2f)
        val p = Project(name = "p", clips = listOf(a, b))
        val lines = listOf(
            ProjectOps.DubLine(1000, "x", 800, "trước đoạn cắt"),
            ProjectOps.DubLine(4000, "y", 900, "câu một"),
        )
        val out = ProjectOps.applyDub(p, 1, lines, originalVolume = 0f)
        val dubs = out.audios.filter { it.kind == AudioKind.DUB }
        assertEquals(1, dubs.size)
        // Clip b starts at 4000 on the timeline; source 4000 is 2000 ms into the trim at 2x speed.
        assertEquals(4000L + 1000L, dubs[0].startMs)
        assertEquals(0f, out.clips[1].volume, 0f)
        assertEquals(1f, out.clips[0].volume, 0f)
        // Dubbing again replaces the earlier dub of that clip.
        assertEquals(1, ProjectOps.applyDub(out, 1, lines, 0f).audios.count { it.kind == AudioKind.DUB })
    }
}
