package com.cutviet.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitExportTest {
    private val H = 3_600_000L
    private fun video(ms: Long, speed: Float = 1f) =
        Clip(uri = "content://v/$ms", mime = "video/mp4", isImage = false, sourceDurationMs = ms, width = 1920, height = 1080, speed = speed)

    @Test
    fun evenSplitsSixHoursIntoSixHours() {
        val parts = SplitExport.parts(6 * H, SplitPlan(mode = SplitMode.EVEN, count = 6))
        assertEquals(6, parts.size)
        parts.forEachIndexed { i, p ->
            assertEquals(i * H, p.startMs)
            assertEquals(H, p.durationMs)
        }
    }

    @Test
    fun fixedLengthLeavesRemainderLast() {
        val parts = SplitExport.parts(6 * H + H / 2, SplitPlan(mode = SplitMode.FIXED, fixedMs = H))
        assertEquals(7, parts.size)
        assertEquals(H, parts[5].durationMs)
        assertEquals(30 * 60_000L, parts[6].durationMs)
    }

    @Test
    fun fixedLengthCrumbJoinsPreviousVideo() {
        val parts = SplitExport.parts(2 * H + 400, SplitPlan(mode = SplitMode.FIXED, fixedMs = H))
        assertEquals(2, parts.size)
        assertEquals(2 * H + 400, parts.last().endMs)
    }

    @Test
    fun customUsesGivenLengthsAndCapsAtEnd() {
        val plan = SplitPlan(mode = SplitMode.CUSTOM, customMs = listOf(H, 2 * H, 10 * H))
        val parts = SplitExport.parts(4 * H, plan)
        assertEquals(listOf(H, 2 * H, H), parts.map { it.durationMs })
        assertEquals(-9 * H, SplitExport.customLeftoverMs(4 * H, plan.customMs))
    }

    @Test
    fun movieModeStartsWithTailOfPrevious() {
        val parts = SplitExport.parts(3 * H, SplitPlan(mode = SplitMode.EVEN, count = 3, overlapMs = 5_000))
        assertEquals(0, parts[0].startMs)
        assertEquals(H - 5_000, parts[1].startMs)
        assertEquals(2 * H, parts[1].endMs)
        assertEquals(2 * H - 5_000, parts[2].startMs)
    }

    @Test
    fun sliceCutsClipsAcrossBoundaries() {
        val p = Project(name = "t", clips = listOf(video(10_000), video(20_000, speed = 2f), video(5_000)))
        // timeline: 0-10s clip A, 10-20s clip B (20s at x2), 20-25s clip C
        val s = SplitExport.slice(p, 8_000, 22_000)
        assertEquals(14_000, s.durationMs)
        assertEquals(3, s.clips.size)
        assertEquals(8_000, s.clips[0].trimStartMs)
        assertEquals(0, s.clips[1].trimStartMs)
        assertEquals(20_000, s.clips[1].trimEndMs)
        assertEquals(2_000, s.clips[2].trimEndMs)
    }

    @Test
    fun sliceSumsToWholeProject() {
        val p = Project(name = "t", clips = listOf(video(7_777), video(12_345, 1.5f), video(9_999)))
        val parts = SplitExport.parts(p.durationMs, SplitPlan(mode = SplitMode.EVEN, count = 4))
        val total = parts.sumOf { SplitExport.slice(p, it.startMs, it.endMs).durationMs }
        assertTrue(kotlin.math.abs(total - p.durationMs) <= 8)
    }

    @Test
    fun sliceMovesOverlaysSoundsAndSubtitles() {
        val ov = Overlay(uri = "o", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 100, height = 100, startMs = 5_000)
        val a = AudioTrack(kind = AudioKind.MUSIC, uri = "m", name = "m", sourceDurationMs = 30_000, startMs = 2_000)
        val sub = Subtitle(startMs = 9_000, endMs = 12_000, text = "x")
        val p = Project(name = "t", clips = listOf(video(60_000)), overlays = listOf(ov), audios = listOf(a), subtitles = listOf(sub))
        val s = SplitExport.slice(p, 10_000, 20_000)
        assertEquals(0, s.overlays[0].startMs)
        assertEquals(5_000, s.overlays[0].trimStartMs)
        assertEquals(0, s.audios[0].startMs)
        assertEquals(8_000, s.audios[0].sourceStartMs)
        assertEquals(18_000, s.audios[0].sourceEndMs)
        assertEquals(0, s.subtitles[0].startMs)
        assertEquals(2_000, s.subtitles[0].endMs)
    }

    @Test
    fun sliceKeepsLoopingMusicInPhase() {
        val a = AudioTrack(kind = AudioKind.MUSIC, uri = "m", name = "m", sourceDurationMs = 4_000, loop = true)
        val p = Project(name = "t", clips = listOf(video(60_000)), audios = listOf(a))
        val s = SplitExport.slice(p, 10_000, 20_000) // 10s = 2.5 passes in
        assertEquals(2, s.audios.size)
        val first = s.audios[0]
        assertFalse(first.loop)
        assertEquals(2_000, first.sourceStartMs)
        assertEquals(0, first.startMs)
        assertTrue(s.audios[1].loop)
        assertEquals(2_000, s.audios[1].startMs)
    }

    @Test
    fun sliceCutsTextLayers() {
        val t = TextItem(text = "a", startMs = 8_000, durationMs = 5_000)
        val p = Project(name = "t", clips = listOf(video(60_000)), texts = listOf(t))
        val s = SplitExport.slice(p, 10_000, 20_000)
        assertEquals(0, s.texts[0].startMs)
        assertEquals(3_000, s.texts[0].durationMs)
        assertTrue(SplitExport.slice(p, 20_000, 30_000).texts.isEmpty())
    }

    @Test
    fun stretchTextKeepsMinimumLength() {
        val t = TextItem(text = "a", startMs = 1_000, durationMs = 2_000)
        assertEquals(ProjectOps.MIN_TEXT_MS, ProjectOps.stretchTextEnd(t, -5_000).durationMs)
        val s = ProjectOps.stretchTextStart(t, 5_000)
        assertEquals(3_000, s.endMs)
        assertEquals(ProjectOps.MIN_TEXT_MS, s.durationMs)
    }
}
