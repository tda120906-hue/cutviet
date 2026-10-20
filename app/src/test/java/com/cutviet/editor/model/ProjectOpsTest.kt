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

    private fun overlay(ms: Long, startMs: Long = 0) =
        Overlay(uri = "content://o/$ms", mime = "video/mp4", isImage = false, sourceDurationMs = ms, width = 1280, height = 720, startMs = startMs)

    @Test
    fun extractAudioMutesClipAndKeepsTiming() {
        val p = Project(name = "t", clips = listOf(video(2_000), video(6_000).copy(trimStartMs = 1_000, speed = 2f)))
        val e = ProjectOps.extractAudio(p, 1, "a")
        assertEquals(0f, e.clips[1].volume)
        val a = e.audios.single()
        assertEquals(AudioKind.EXTRACTED, a.kind)
        assertEquals(2_000, a.startMs)
        assertEquals(1_000, a.sourceStartMs)
        assertEquals(e.clips[1].durationMs, a.durationMs)
    }

    @Test
    fun stretchingOverlayStartKeepsContentInPlace() {
        val o = overlay(10_000, startMs = 5_000).copy(trimStartMs = 2_000)
        val s = ProjectOps.stretchOverlayStart(o, 500)
        assertEquals(5_500, s.startMs)
        assertEquals(2_500, s.trimStartMs)
        assertEquals(o.endMs, s.endMs)
        // Cannot reveal more than the source has.
        val back = ProjectOps.stretchOverlayStart(o, -4_000)
        assertEquals(0, back.trimStartMs)
        assertEquals(3_000, back.startMs)
        assertEquals(o.endMs, back.endMs)
        val end = ProjectOps.stretchOverlayEnd(o, 99_000)
        assertEquals(10_000, end.trimEndMs)
    }

    @Test
    fun lanesNeverOverlap() {
        val items = listOf(0L to 5_000L, 1_000L to 2_000L, 5_000L to 6_000L, 2_000L to 3_000L)
        val rows = ProjectOps.lanes(items, { it.first }, { it.second })
        assertEquals(2, rows.size)
        for (row in rows) row.zipWithNext().forEach { (a, b) -> assertTrue(a.second <= b.first) }
        assertEquals(items.size, rows.sumOf { it.size })
    }

    @Test
    fun restackMovesOverlayWithinBounds() {
        val a = overlay(1_000); val b = overlay(2_000); val c = overlay(3_000)
        val p = Project(name = "t", overlays = listOf(a, b, c))
        assertEquals(listOf(b, a, c), ProjectOps.restackOverlay(p, a.id, 1).overlays)
        assertEquals(listOf(a, c, b), ProjectOps.restackOverlay(p, c.id, -1).overlays)
        assertSame(p, ProjectOps.restackOverlay(p, c.id, 1))
        assertSame(p, ProjectOps.restackOverlay(p, a.id, -1))
    }

    @Test
    fun stretchingClipsTrimsVideoAndResizesImages() {
        val v = video(10_000).copy(trimStartMs = 1_000, trimEndMs = 9_000)
        assertEquals(1_500, ProjectOps.stretchClipStart(v, 500).trimStartMs)
        assertEquals(0, ProjectOps.stretchClipStart(v, -5_000).trimStartMs)
        assertEquals(10_000, ProjectOps.stretchClipEnd(v, 99_000).trimEndMs)
        val img = Clip(uri = "content://i", mime = "image/jpeg", isImage = true, sourceDurationMs = 3_000, width = 100, height = 100)
        assertEquals(5_000, ProjectOps.stretchClipEnd(img, 2_000).durationMs)
        assertEquals(2_000, ProjectOps.stretchClipStart(img, 1_000).durationMs)
        assertEquals(ProjectOps.MIN_CLIP_MS, ProjectOps.stretchClipStart(img, 9_000).durationMs)
    }

    @Test
    fun trimmingTheStartStopsAtThePlayhead() {
        // A 10 s clip at 4 s on the timeline, played at 2x: 5 s long there.
        val v = video(10_000).copy(speed = 2f)
        val stop = 6_000L
        // Dragged 1.5 s: trims 3 s of source.
        assertEquals(3_000, ProjectOps.trimClipStart(v, 4_000, 1_500, stop, emptyList(), 100).trimStartMs)
        // Dragged past the playhead: stops on the frame under it, however far the finger goes.
        for (d in listOf(2_000L, 2_500L, 4_000L)) {
            val c = ProjectOps.trimClipStart(v, 4_000, d, stop, emptyList(), 100)
            assertEquals(4_000, c.trimStartMs)
            assertEquals(3_000, c.durationMs)
        }
        // Without a stop it goes on, and it still sticks to nearby points.
        assertEquals(5_000, ProjectOps.trimClipStart(v, 4_000, 2_500, null, emptyList(), 100).trimStartMs)
        assertEquals(4_000, ProjectOps.trimClipStart(v, 4_000, 1_950, null, listOf(stop), 100).trimStartMs)
        // Dragging left brings back what was trimmed.
        assertEquals(0, ProjectOps.trimClipStart(v.copy(trimStartMs = 2_000), 4_000, -3_000, stop, emptyList(), 100).trimStartMs)
    }

    @Test
    fun trimmingTheEndSticksToNearbyPoints() {
        val v = video(10_000)
        assertEquals(7_000, ProjectOps.trimClipEnd(v, 0, -3_000, emptyList(), 100).trimEndMs)
        assertEquals(6_000, ProjectOps.trimClipEnd(v, 0, -3_920, listOf(6_000), 100).trimEndMs)
    }

    @Test
    fun playheadKeepsItsFrameAfterTheStartIsTrimmed() {
        assertEquals(3_000, ProjectOps.playheadAfterStartTrim(5_000, 2_000, 2_000))
        assertEquals(2_000, ProjectOps.playheadAfterStartTrim(3_000, 2_000, 2_000))
        assertEquals(1_000, ProjectOps.playheadAfterStartTrim(1_000, 2_000, 2_000))
        assertEquals(5_000, ProjectOps.playheadAfterStartTrim(5_000, 2_000, -1_000))
    }

    @Test
    fun snapPullsToNearbyEdgesOnly() {
        assertEquals(5_000, ProjectOps.snap(5_080, listOf(0, 5_000, 9_000), 100))
        assertEquals(5_300, ProjectOps.snap(5_300, listOf(0, 5_000, 9_000), 100))
    }

    @Test
    fun overlaySourceTimeFollowsTrimAndSpeed() {
        val o = Overlay(
            uri = "v", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 100, height = 100,
            trimStartMs = 2_000, trimEndMs = 8_000, startMs = 1_000, speed = 2f,
        )
        assertEquals(2_000, ProjectOps.overlaySourceTimeMs(o, 0))
        assertEquals(3_000, ProjectOps.overlaySourceTimeMs(o, 1_500))
        assertEquals(7_999, ProjectOps.overlaySourceTimeMs(o, 10_000))
        assertEquals(0, ProjectOps.overlaySourceTimeMs(o.copy(isImage = true), 1_500))
    }
}
