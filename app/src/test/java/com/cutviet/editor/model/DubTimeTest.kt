package com.cutviet.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubTimeTest {
    private fun DubTime.Plan.fits(ratio: Double) = dubSpeed / videoFactor >= ratio - 1e-4

    @Test
    fun planSpeedsTheDubFirstThenSlowsTheVideo() {
        assertEquals(DubTime.Plan(1f, 1f), DubTime.plan(0.9, 1.25f, 0.85f))
        DubTime.plan(1.1, 1.25f, 0.85f).let { assertEquals(1.1f, it.dubSpeed, 1e-4f); assertEquals(1f, it.videoFactor, 0f) }
        // Past x1.2 the video slows a little before the dub is pushed further.
        DubTime.plan(1.3, 1.25f, 0.85f).let {
            assertEquals(1.2f, it.dubSpeed, 1e-4f); assertEquals(1.2f / 1.3f, it.videoFactor, 1e-4f); assertTrue(it.fits(1.3))
        }
        DubTime.plan(1.36, 1.25f, 0.85f).let {
            assertEquals(1.36f * 0.9f, it.dubSpeed, 1e-4f); assertEquals(0.9f, it.videoFactor, 1e-4f); assertTrue(it.fits(1.36))
        }
        // Too long even at the limits: both at their limits, the rest spills.
        DubTime.plan(1.6, 1.25f, 0.85f).let { assertEquals(1.25f, it.dubSpeed, 1e-4f); assertEquals(0.85f, it.videoFactor, 1e-4f) }
        DubTime.plan(1.7, 1.4f, 0.8f).let {
            assertEquals(1.4f, it.dubSpeed, 1e-4f); assertEquals(1.4f / 1.7f, it.videoFactor, 1e-4f); assertTrue(it.fits(1.7))
        }
    }

    @Test
    fun planKeepsWithinTheUsersLimits() {
        // Dub may not be sped up at all: only the video slows.
        DubTime.plan(1.1, 1f, 0.8f).let { assertEquals(1f, it.dubSpeed, 0f); assertEquals(1f / 1.1f, it.videoFactor, 1e-4f) }
        // Video may not be slowed: only the dub speeds up.
        DubTime.plan(1.5, 1.4f, 1f).let { assertEquals(1.4f, it.dubSpeed, 1e-4f); assertEquals(1f, it.videoFactor, 0f) }
    }

    @Test
    fun normaliseMergesCloseStretchesAndDropsTinyPieces() {
        val out = DubTime.normalise(
            listOf(
                DubTime.Slowdown(2200, 3000, 0.85f),
                DubTime.Slowdown(1000, 2000, 0.9f),
                DubTime.Slowdown(5000, 5300, 0.9f),
                DubTime.Slowdown(6000, 7000, 1f),
                DubTime.Slowdown(9700, 12_000, 0.9f),
            ),
            trimStart = 0, trimEnd = 10_000,
        )
        assertEquals(listOf(DubTime.Slowdown(1000, 3000, 0.85f)), out)
        // A stretch close to the trim's start takes the rest of it.
        assertEquals(listOf(DubTime.Slowdown(0, 1000, 0.9f)), DubTime.normalise(listOf(DubTime.Slowdown(300, 1000, 0.9f)), 0, 10_000))
        assertEquals(listOf(DubTime.Slowdown(9000, 10_000, 0.9f)), DubTime.normalise(listOf(DubTime.Slowdown(9000, 9800, 0.9f)), 0, 10_000))
    }

    @Test
    fun stretchedAndPieces() {
        val slow = listOf(DubTime.Slowdown(1000, 3000, 0.5f))
        assertEquals(500L, DubTime.stretched(500, slow))
        assertEquals(3000L, DubTime.stretched(2000, slow))
        assertEquals(7000L, DubTime.stretched(5000, slow))
        assertEquals(
            listOf(DubTime.Slowdown(0, 1000, 1f), DubTime.Slowdown(1000, 3000, 0.5f), DubTime.Slowdown(3000, 10_000, 1f)),
            DubTime.pieces(0, 10_000, slow),
        )
        assertEquals(listOf(DubTime.Slowdown(0, 10_000, 1f)), DubTime.pieces(0, 10_000, emptyList()))
    }

    @Test
    fun applyDubSlowsThePieceAndMovesWhatFollows() {
        val a = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 4000, width = 1920, height = 1080)
        val b = Clip(uri = "content://b", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 1920, height = 1080)
        val music = AudioTrack(kind = AudioKind.MUSIC, uri = "content://m", name = "m", sourceDurationMs = 3000, startMs = 14_500)
        val sub = Subtitle(startMs = 7000, endMs = 9000, text = "hello")
        val p = Project(name = "p", clips = listOf(a, b), audios = listOf(music), subtitles = listOf(sub))
        // Stems already stretched: 10 s of source with 2 s played at half speed lasts 12 s.
        val stems = ProjectOps.DubStems("file:///v.wav", "file:///bg.wav", sourceStartMs = 0, durationMs = 12_000)
        val lines = listOf(ProjectOps.DubLine(5000, "file:///d.wav", 1500, "xin chào"))
        val out = ProjectOps.applyDub(p, 1, lines, 0f, stems, slowdowns = listOf(DubTime.Slowdown(2000, 4000, 0.5f)))

        assertEquals(4, out.clips.size)
        assertEquals(b.id, out.clips[1].id)
        assertEquals(listOf(2000L, 4000L, 6000L), out.clips.drop(1).map { it.durationMs })
        assertEquals(listOf(1f, 0.5f, 1f), out.clips.drop(1).map { it.speed })
        assertEquals(4000L + 12_000L, out.durationMs)
        assertEquals(4000L + 7000L, out.audios.single { it.kind == AudioKind.DUB }.startMs)
        assertEquals(16_500L, out.audios.single { it.kind == AudioKind.MUSIC }.startMs)
        val bg = out.audios.single { it.kind == AudioKind.BACKGROUND }
        assertEquals(4000L, bg.startMs)
        assertEquals(12_000L, bg.durationMs)
        // The subtitle followed the video: source 3000..5000 is now 4000..7000 into the clip.
        assertEquals(8000L, out.subtitles.single().startMs)
        assertEquals(11_000L, out.subtitles.single().endMs)
    }
}
