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
    fun clausesKeepPunctuationAndGetTheirPause() {
        val raw = "hˈom nˈaj\u0001,\u00010\u0002(en)ˈap(vi) mˈəːɜj\u0001.\u00011\u0002ɗˈuːŋ\u0001?\u00011\u0002\u0001\u00010"
        val c = PiperTts.clauses(raw)
        assertEquals(3, c.size)
        assertEquals("hˈom nˈaj,", c[0].phonemes.joinToString(""))
        assertEquals(PiperTts.Pause.COMMA, c[0].pause)
        assertEquals("ˈaːɜp mˈəːɜj.", c[1].phonemes.joinToString(""))
        assertEquals(PiperTts.Pause.SENTENCE, c[1].pause)
        // Tone and diacritics are their own phonemes after NFD ("t̪" → "t", "̪").
        assertTrue(PiperTts.clauses("t̪ˈa\u0001.\u00011")[0].phonemes.containsAll(listOf("t", "\u032A")))
    }

    @Test
    fun silenceAroundAClauseIsCut() {
        val rate = 22050
        val voice = FloatArray(rate / 2) { sin(2 * PI * 200 * it / rate).toFloat() * 0.5f }
        val x = FloatArray(rate / 3) + voice + FloatArray(rate / 3)
        val y = PiperTts.trimSilence(x, rate)
        // Half a second of voice plus about 20 ms kept on each side.
        assertTrue("${y.size}", y.size in voice.size..(voice.size + rate / 20 + rate / 50))
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
    fun punctuationGluedToTheNextWordGetsASpace() {
        // Without it espeak says "chấm" for the full stop and skips the comma's pause.
        assertEquals("Xin chào. Hôm nay, trời đẹp", PiperTts.cleanText("Xin chào.Hôm nay,trời đẹp"))
        assertEquals("Xin chào. Hôm", PiperTts.cleanText("Xin chào .Hôm"))
        assertEquals("Ừ.  Thôi", PiperTts.cleanText("Ừ… Thôi"))
        // Numbers keep their point.
        assertEquals("Giá 1.5 triệu", PiperTts.cleanText("Giá 1.5 triệu"))
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
