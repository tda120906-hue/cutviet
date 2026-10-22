package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiSubtitler
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeechDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSubtitlerTest {
    @Test
    fun readsLinesInsideAFence() {
        val answer = "```json\n{\"lines\":[{\"index\":2,\"source\":\"Hi\",\"vi\":\"Chào\"},{\"index\":1,\"source\":\"Bye\",\"vi\":\"Tạm biệt\"},{\"index\":9,\"source\":\"x\",\"vi\":\"y\"}]}\n```"
        val lines = GeminiSubtitler.parse(answer, setOf(0, 1))!!
        assertEquals(listOf(0, 1), lines.map { it.index })
        assertEquals("Tạm biệt", lines[0].vi)
        assertNull(GeminiSubtitler.parse("no json here", setOf(0)))
    }

    @Test
    fun promptAsksForTranscriptionWhenSpeechIsVietnamese() {
        assertTrue(GeminiSubtitler.prompt(SourceLanguage.VIETNAMESE, 3).contains("đúng chính tả"))
        assertFalse(GeminiSubtitler.prompt(SourceLanguage.ENGLISH, 3).contains("đúng chính tả"))
    }

    @Test
    fun requestCarriesOneAudioPartPerSegment() {
        val voice = FloatArray(16_000 * 4)
        val ranges = listOf(SpeechDetector.Range(0, 1_000), SpeechDetector.Range(2_000, 3_000))
        val req = GeminiSubtitler.request("m", SourceLanguage.CHINESE, voice, ranges, listOf(0, 1), schema = true)
        val input = req.getJSONArray("input")
        assertEquals(5, input.length())
        assertEquals("[S2]", input.getJSONObject(3).getString("text"))
        assertEquals("audio", input.getJSONObject(4).getString("type"))
        assertTrue(req.has("response_format"))
    }
}
