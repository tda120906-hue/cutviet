package com.cutviet.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOpsTest {
    private fun video(ms: Long, w: Int = 1920, h: Int = 1080, bitrate: Int = 16_000_000) =
        Clip(uri = "content://v/$ms", mime = "video/mp4", isImage = false, sourceDurationMs = ms, width = w, height = h, bitrate = bitrate)

    @Test
    fun splitKeepsTotalDuration() {
        val p = Project(name = "t", clips = listOf(video(10_000), video(5_000)))
        val s = ProjectOps.splitAt(p, 4_000)
        assertEquals(3, s.clips.size)
        assertEquals(p.durationMs, s.durationMs)
        assertEquals(4_000, s.clips[0].trimEndMs)
        assertEquals(4_000, s.clips[1].trimStartMs)
    }

    @Test
    fun splitRespectsSpeed() {
        val p = Project(name = "t", clips = listOf(video(10_000).copy(speed = 2f)))
        assertEquals(5_000, p.durationMs)
        val s = ProjectOps.splitAt(p, 1_000)
        assertEquals(2_000, s.clips[0].trimEndMs)
        assertEquals(5_000, s.durationMs)
    }

    @Test
    fun splitAtEdgeIsNoOp() {
        val p = Project(name = "t", clips = listOf(video(10_000)))
        assertSame(p, ProjectOps.splitAt(p, 20))
    }

    @Test
    fun clipAtFindsSecondClip() {
        val p = Project(name = "t", clips = listOf(video(3_000), video(3_000)))
        assertEquals(1 to 500L, p.clipAt(3_500))
    }

    @Test
    fun moveAndDelete() {
        val a = video(1_000); val b = video(2_000)
        val p = Project(name = "t", clips = listOf(a, b))
        assertEquals(listOf(b, a), ProjectOps.move(p, 0, 1).clips)
        assertEquals(listOf(b), ProjectOps.delete(p, 0).clips)
    }

    @Test
    fun canvasKeepsOriginalSizeAndLongSide() {
        val p = Project(name = "t", clips = listOf(video(1_000, 3840, 2160)))
        assertEquals(3840 to 2160, ProjectOps.canvasSize(p))
        assertEquals(2160 to 3840, ProjectOps.canvasSize(p.copy(aspect = AspectRatio.R9_16)))
        assertEquals(3840 to 3840, ProjectOps.canvasSize(p.copy(aspect = AspectRatio.R1_1)))
        assertEquals(1920 to 1080, ProjectOps.scaleToShortSide(3840 to 2160, 1080))
        assertEquals(1280 to 720, ProjectOps.scaleToShortSide(1280 to 720, 1080))
    }

    @Test
    fun bitrateNotBelowSource() {
        val p = Project(name = "t", clips = listOf(video(1_000, bitrate = 20_000_000)))
        val b = ProjectOps.targetBitrate(p, 1920 to 1080, ExportQuality.ORIGINAL)
        assertTrue(b >= 20_000_000)
    }

    @Test
    fun trimClamps() {
        val c = ProjectOps.trim(video(5_000), -100, 99_999)
        assertEquals(0, c.trimStartMs)
        assertEquals(5_000, c.trimEndMs)
    }
}
