package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.SpeechDetector
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class DubbingTest {
    private val rate = 16_000

    /** Quiet noise with loud "speech" bursts at the given (startMs, endMs). */
    private fun signal(totalMs: Int, bursts: List<Pair<Int, Int>>): FloatArray {
        val rnd = Random(7)
        return FloatArray(totalMs * rate / 1000) { i ->
            val ms = i * 1000 / rate
            val noise = (rnd.nextFloat() - 0.5f) * 0.002f
            if (bursts.any { ms in it.first until it.second }) noise + 0.3f * sin(2 * PI * 220 * i / rate).toFloat() else noise
        }
    }

    @Test
    fun detectsSpeechJoinsShortGapsAndDropsBlips() {
        val x = signal(10_000, listOf(1000 to 2000, 2200 to 3000, 6000 to 6100, 7000 to 8500))
        val r = SpeechDetector.detect(x, rate)
        assertEquals(2, r.size)
        assertTrue(r[0].startMs in 960..1050 && r[0].endMs in 2970..3060)
        assertTrue(r[1].startMs in 6960..7050 && r[1].endMs in 8470..8560)
    }

    @Test
    fun longSpeechIsCutIntoPieces() {
        val x = signal(30_000, listOf(1000 to 28_000))
        val r = SpeechDetector.detect(x, rate)
        assertTrue(r.size >= 3)
        r.forEach { assertTrue(it.endMs - it.startMs <= SpeechDetector.MAX_SEGMENT_MS) }
    }

    @Test
    fun stemsMuteTheClipAndKeepTheBackgroundInSync() {
        val a = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 4000, width = 1920, height = 1080)
        val b = Clip(
            uri = "content://b", mime = "video/mp4", isImage = false, sourceDurationMs = 20_000, width = 3840, height = 2160,
            trimStartMs = 2000, trimEndMs = 12_000, speed = 2f,
        )
        val p = Project(name = "p", clips = listOf(a, b))
        val stems = ProjectOps.DubStems("file:///v.wav", "file:///bg.wav", sourceStartMs = 2000, durationMs = 10_000)
        val lines = listOf(ProjectOps.DubLine(4000, "file:///d.wav", 1500, "xin chào", speed = 1.1f))
        val out = ProjectOps.applyDub(p, 1, lines, originalVolume = 0.2f, stems = stems, dubVolume = 1.3f)

        assertEquals(0f, out.clips[1].volume, 0f)
        val bg = out.audios.single { it.kind == AudioKind.BACKGROUND }
        val voice = out.audios.single { it.kind == AudioKind.SOURCE_VOICE }
        val dub = out.audios.single { it.kind == AudioKind.DUB }
        // Clip b starts at 4000 on the timeline and lasts 10 s / 2x = 5 s; the stems match it.
        assertEquals(4000L, bg.startMs)
        assertEquals(2f, bg.speed, 0f)
        assertEquals(5000L, bg.durationMs)
        assertEquals(1f, bg.volume, 0f)
        assertEquals(0.2f, voice.volume, 1e-6f)
        assertEquals(4000L + 1000L, dub.startMs)
        assertEquals(1.3f, dub.volume, 1e-6f)
        assertEquals(1.1f, dub.speed, 1e-6f)

        // Dubbing the clip again replaces all three kinds of tracks.
        val again = ProjectOps.applyDub(out, 1, lines, 0f, stems)
        assertEquals(3, again.audios.size)
    }
}
