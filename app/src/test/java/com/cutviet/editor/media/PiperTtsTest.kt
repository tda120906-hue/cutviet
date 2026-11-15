package com.cutviet.editor.media

import com.cutviet.editor.media.tts.PiperTts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PiperTtsTest {
    @Test
    fun clausesBecomeSentencesOfPhonemesLikePiper() {
        // Two clauses of one sentence, then a second sentence; English part made Vietnamese.
        val raw = "hˈom nˈaj\u0001,\u00010\u0002(en)ˈap(vi) mˈəːɜj\u0001.\u00011\u0002ɗˈuːŋ\u0001?\u00011"
        val s = PiperTts.sentences(raw)
        assertEquals(2, s.size)
        assertEquals("hˈom nˈaj, ˈaːɜp mˈəːɜj.", s[0].joinToString(""))
        // Tone and diacritics are their own phonemes after NFD ("t̪" → "t", "̪").
        assertTrue(PiperTts.sentences("t̪ˈa\u0001.\u00011")[0].containsAll(listOf("t", "̪")))
    }

    @Test
    fun idsArePaddedBetweenPhonemes() {
        val map = mapOf("_" to 0, "^" to 1, "$" to 2, "a" to 14, "b" to 15)
        assertArrayEquals(longArrayOf(1, 0, 14, 0, 15, 0, 2), PiperTts.ids(listOf("a", "?", "b"), map))
    }

    @Test
    fun emojiDroppedAndOkSaidTheVietnameseWay() {
        assertEquals("Xong   ô kê nhé, Okami", PiperTts.cleanText("Xong 😀 OK nhé, Okami").replace("️", ""))
    }

    @Test
    fun longSentencesAreCutAtCommas() {
        val s = (1..1000).map { if (it % 50 == 0) "," else "a" }
        val parts = PiperTts.chunk(s)
        assertTrue(parts.all { it.size <= 400 })
        assertEquals(s, parts.flatten())
        assertEquals(",", parts[0].last())
    }

    @Test
    fun resampleKeepsAToneAtTheNewPitch() {
        val x = FloatArray(22050) { sin(2 * PI * 200 * it / 22050).toFloat() }
        val y = PiperTts.resample(x, 1.25)
        assertEquals((22050 / 1.25).toInt(), y.size)
        // 200 Hz played 1.25× faster is 250 Hz: count zero crossings over the output.
        var crossings = 0
        for (i in 1 until y.size) if ((y[i - 1] < 0) != (y[i] < 0)) crossings++
        val hz = crossings / 2.0 / (y.size / 22050.0)
        assertTrue("$hz Hz", abs(hz - 250) < 3)
    }

    @Test
    fun dubLinesPickTheMaleOrFemaleVoice() {
        assertEquals("namminh" to 1f, VoiceEngine.dubVoice("hoaimy", 0.78f))
        assertEquals("hoaimy" to 1f, VoiceEngine.dubVoice("namminh", 1.05f))
        assertEquals("namminh" to 1f, VoiceEngine.dubVoice("namminh", 1f))
        assertEquals("hoaimy", VoiceEngine.resolve("vi-vn-x-gft-local"))
    }
}
