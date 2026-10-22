package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.Gender
import com.cutviet.editor.media.dubbing.GeminiSubtitler
import com.cutviet.editor.media.dubbing.GeminiSubtitler.RawTurn
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeechDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSubtitlerTest {
    @Test
    fun readsTurnsAndSpeakersInsideAFence() {
        val answer = """
            ```json
            {"speakers":[{"id":"A","gender":"male","voice":"a man in his 60s"},{"id":"B","gender":"unsure","voice":"young"}],
             "lines":[{"index":2,"turns":[{"speaker":"A","start":0.1,"source":"Hi","vi":"Chào","emotion":"Neutral"},
                                          {"speaker":"B","start":1.5,"source":"Hey!","vi":"Chào anh!","emotion":"excited"}]},
                      {"index":1,"speaker":"B","source":"Bye","vi":"Tạm biệt","emotion":"sad"},
                      {"index":9,"turns":[]}]}
            ```
        """.trimIndent()
        val a = GeminiSubtitler.parse(answer, setOf(0, 1))!!
        assertEquals(setOf(0, 1), a.segments.keys)
        assertEquals(listOf("A", "B"), a.segments.getValue(1).map { it.speaker })
        assertEquals("", a.segments.getValue(1)[0].emotion)
        assertEquals(1.5, a.segments.getValue(1)[1].startSec!!, 1e-9)
        // The older flat shape is one turn.
        assertEquals("Tạm biệt", a.segments.getValue(0).single().vi)
        assertNull(a.segments.getValue(0).single().startSec)
        assertEquals(Gender.MALE, a.speakers[0].gender)
        assertNull(a.speakers[1].gender)
        assertNull(GeminiSubtitler.parse("no json here", setOf(0)))
    }

    @Test
    fun turnsArePlacedWhereTheModelSaysTheyStart() {
        val r = SpeechDetector.Range(10_000, 16_000)
        val turns = GeminiSubtitler.place(
            r,
            listOf(RawTurn("A", 0.1, "Where are you going?", "Anh đi đâu thế?", ""), RawTurn("B", 2.15, "Home.", "Về nhà.", "")),
        )
        assertEquals(2, turns.size)
        assertEquals(10_000, turns[0].startMs)
        // Audio starts PAD_MS before the segment.
        assertEquals(10_000 - GeminiSubtitler.PAD_MS + 2_150, turns[1].startMs)
        assertEquals(turns[1].startMs, turns[0].endMs)
        assertEquals(16_000, turns[1].endMs)
        assertEquals("B", turns[1].speaker)
    }

    @Test
    fun missingOrWrongTimesShareTheSegmentByLength() {
        val r = SpeechDetector.Range(0, 3_000)
        val turns = GeminiSubtitler.place(
            r,
            listOf(RawTurn("A", 2.0, "aaaa aaaa", "x", ""), RawTurn("B", 1.0, "bbbb", "y", ""), RawTurn("C", null, "cccc cccc cccc", "z", "")),
        )
        assertEquals(listOf("A", "B", "C"), turns.map { it.speaker })
        assertEquals(0, turns[0].startMs)
        assertTrue(turns[0].endMs in 900..1_000)
        assertEquals(3_000, turns[2].endMs)
    }

    @Test
    fun samePersonTwiceAndTinyTurnsAreJoined() {
        val r = SpeechDetector.Range(0, 5_000)
        val turns = GeminiSubtitler.place(
            r,
            listOf(
                RawTurn("A", 0.2, "One.", "Một.", ""), RawTurn("A", 1.5, "Two.", "Hai.", ""),
                RawTurn("B", 3.0, "Three.", "Ba.", ""), RawTurn("B", 4.9, "", "", ""),
            ),
        )
        assertEquals(listOf("A", "B"), turns.map { it.speaker })
        assertEquals("Một. Hai.", turns[0].vi)
        assertEquals(5_000, turns[1].endMs)
    }

    @Test
    fun promptAsksForTurnsAndTranscriptionWhenSpeechIsVietnamese() {
        assertTrue(GeminiSubtitler.prompt(SourceLanguage.VIETNAMESE, 3).contains("đúng chính tả"))
        assertFalse(GeminiSubtitler.prompt(SourceLanguage.ENGLISH, 3).contains("đúng chính tả"))
        assertTrue(GeminiSubtitler.prompt(SourceLanguage.CHINESE, 3).contains("lượt nói"))
    }

    @Test
    fun requestCarriesOneAudioPartPerSegment() {
        val voice = FloatArray(16_000 * 4)
        val ranges = listOf(SpeechDetector.Range(0, 1_000), SpeechDetector.Range(2_000, 3_000))
        val req = GeminiSubtitler.request("m", SourceLanguage.CHINESE, voice, ranges, listOf(0, 1), emptyList(), schema = true)
        val input = req.getJSONArray("input")
        assertEquals(5, input.length())
        assertEquals("[S2]", input.getJSONObject(3).getString("text"))
        assertEquals("audio", input.getJSONObject(4).getString("type"))
        assertTrue(req.has("response_format"))
        assertFalse(GeminiSubtitler.request("m", SourceLanguage.ENGLISH, voice, ranges, listOf(1), emptyList(), schema = false).has("response_format"))
    }
}
