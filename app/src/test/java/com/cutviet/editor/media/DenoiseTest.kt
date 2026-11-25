package com.cutviet.editor.media

import com.cutviet.editor.media.denoise.MainVoiceMix
import com.cutviet.editor.media.denoise.VoiceIsolator
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class DenoiseTest {
    private fun stereo(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        return FloatArray(n * 2) { i -> (0.3 * sin(2 * PI * 440 * (i / 2) / 44100.0)).toFloat() + (r.nextFloat() - 0.5f) * 0.2f }
    }

    private fun run(voice: FloatArray, iso: FloatArray, gains: FloatArray, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        var next = 0L
        MainVoiceMix.combine(
            frames.toLong(), 44100, 2, gains,
            readVoice = { f, o, n -> for (i in 0 until n * 2) o[i] = voice.getOrElse((f * 2 + i).toInt()) { 0f } },
            readIsolated = { f, o, n -> for (i in 0 until n) o[i] = iso.getOrElse((f + i).toInt()) { 0f } },
            emit = { x, n, first ->
                assertEquals(next, first)
                System.arraycopy(x, 0, out, (first * 2).toInt(), n * 2)
                next = first + n
            },
        )
        assertEquals(frames.toLong(), next)
        return out
    }

    @Test fun combineGivesBackTheVoiceWhenNothingWasRemoved() {
        val frames = 30_000
        val v = stereo(frames, 1)
        // Same signal in both channels, VoxLock kept everything.
        for (i in 0 until frames) v[2 * i + 1] = v[2 * i]
        val iso = FloatArray(frames) { v[2 * it] }
        val out = run(v, iso, FloatArray(frames / 441 + 2) { 1f }, frames)
        var err = 0.0
        for (i in out.indices) err = maxOf(err, abs(out[i] - v[i]).toDouble())
        assertTrue("max error $err", err < 1e-4)
    }

    @Test fun combineSilencesTheHighBandWhenTheGainIsZero() {
        val frames = 20_000
        // 12 kHz tone: all above the crossover.
        val v = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * 12_000 * (i / 2) / 44100.0)).toFloat() }
        val out = run(v, FloatArray(frames), FloatArray(frames / 441 + 2), frames)
        var peak = 0f
        for (i in 4000 until out.size - 4000) peak = maxOf(peak, abs(out[i]))
        assertTrue("peak $peak", peak < 1e-3f)
    }

    @Test fun bandGainsAreOneWhenIdenticalAndZeroWhenSilenced() {
        val n = 16_000L
        val x = FloatArray(n.toInt()) { (0.3 * sin(2 * PI * 3000 * it / 16000.0)).toFloat() }
        val read: (Long, FloatArray, Int) -> Unit = { f, o, k -> for (i in 0 until k) o[i] = x[(f + i).toInt()] }
        val same = MainVoiceMix.bandGains(n, 16000, read, read)
        assertEquals(101, same.size)
        for (k in 5 until 95) assertEquals(1f, same[k], 1e-3f)
        val gone = MainVoiceMix.bandGains(n, 16000, read) { _, o, k -> java.util.Arrays.fill(o, 0, k, 0f) }
        for (k in 5 until 95) assertEquals(0f, gone[k], 1e-3f)
    }

    @Test fun loudestSampleIsTheLoudWindow() {
        val rate = 1000
        val x = FloatArray(10 * rate) { if (it in 6000 until 9000) 0.8f else 0.01f }
        val s = VoiceIsolator.loudest(x, rate, 3, 15)!!
        assertEquals(3000, s.size)
        assertTrue(s.all { it == 0.8f })
        assertNull(VoiceIsolator.loudest(FloatArray(2000), rate, 3, 15))
    }

    @Test fun reflectLikeTorch() {
        assertArrayEquals(longArrayOf(2, 1, 0, 1, 2, 3, 4, 3, 2), LongArray(9) { VoiceIsolator.reflect(it - 2L, 5) })
    }

    @Test fun applyDenoiseMutesTheClipAndAddsTwoTracks() {
        val a = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 1920, height = 1080)
        val b = Clip(uri = "content://b", mime = "video/mp4", isImage = false, sourceDurationMs = 8_000, width = 1920, height = 1080, trimStartMs = 2000, trimEndMs = 6000, volume = 0.8f)
        val p = Project(name = "t", clips = listOf(a, b))
        val out = ProjectOps.applyDenoise(p, 1, ProjectOps.DubStems("file:///v.wav", "file:///b.wav", 2000, 4000), 0.1f)
        assertEquals(0f, out.clips[1].volume)
        assertEquals(1f, out.clips[0].volume)
        val voice = out.audios.single { it.kind == AudioKind.SOURCE_VOICE }
        val bg = out.audios.single { it.kind == AudioKind.BACKGROUND }
        assertEquals(10_000, voice.startMs)
        assertEquals(0, voice.sourceStartMs)
        assertEquals(4000, voice.sourceEndMs)
        assertEquals(0.8f, voice.volume)
        assertEquals(0.1f, bg.volume)
        // Running it again replaces the tracks instead of adding more.
        val again = ProjectOps.applyDenoise(out, 1, ProjectOps.DubStems("file:///v2.wav", "file:///b2.wav", 2000, 4000), 0f)
        assertEquals(2, again.audios.size)
        assertEquals("file:///v2.wav", again.audios.single { it.kind == AudioKind.SOURCE_VOICE }.uri)
    }
}
