package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiApi
import com.cutviet.editor.media.dubbing.GeminiApi.Probe
import com.cutviet.editor.media.dubbing.GeminiDubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model that is overloaded (503) or out of free requests (429) is replaced by a lighter one of the same kind. */
class GeminiOverloadTest {
    private val text = GeminiDubber.DEFAULT_TEXT_MODEL
    private val speech = GeminiDubber.DEFAULT_TTS_MODEL

    @Test
    fun listeningFallsBackToLighterTextModels() {
        assertEquals("gemini-3.6-flash", GeminiApi.nextModel(text, listOf(text)))
        assertEquals("gemini-3.5-flash-lite", GeminiApi.nextModel(text, listOf(text, "gemini-3.6-flash")))
        assertEquals("gemini-3.1-flash-lite", GeminiApi.nextModel(text, listOf(text, "gemini-3.6-flash", "gemini-3.5-flash-lite")))
        assertNull(GeminiApi.nextModel(text, listOf(text) + GeminiApi.fallbacksFor(text)))
        // A model the user typed that is itself a fallback is not tried twice.
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), GeminiApi.fallbacksFor("gemini-3.6-flash"))
        // Started from a remembered replacement: on to the one after it.
        assertEquals("gemini-3.5-flash-lite", GeminiApi.nextModel(text, listOf("gemini-3.6-flash")))
    }

    @Test
    fun speechFallsBackToTheLiteSpeechModel() {
        assertTrue(GeminiApi.isSpeech(speech))
        assertFalse(GeminiApi.isSpeech(text))
        assertEquals("gemini-3.8-flash-lite-tts", GeminiApi.nextModel(speech, listOf(speech)))
        assertNull(GeminiApi.nextModel(speech, listOf(speech, "gemini-3.8-flash-lite-tts")))
        assertEquals(emptyList<String>(), GeminiApi.fallbacksFor("gemini-3.8-flash-lite-tts"))
    }

    @Test
    fun theTestPicksTheFirstModelThatAnswers() {
        fun p(model: String, code: Int) = Probe(model, code, "")
        // The model asked for works: it is used, whatever the others say.
        assertEquals(text, GeminiApi.choose(text, listOf(p(text, 200), p("gemini-3.6-flash", 503))))
        // Overloaded, the next one out of requests: the first lighter one that answers.
        assertEquals(
            "gemini-3.5-flash-lite",
            GeminiApi.choose(text, listOf(p(text, 503), p("gemini-3.6-flash", 429), p("gemini-3.5-flash-lite", 200), p("gemini-3.1-flash-lite", 200))),
        )
        // Nothing answers, or nothing was tested: left as it is.
        assertNull(GeminiApi.choose(text, listOf(p(text, 503), p("gemini-3.6-flash", 503))))
        assertNull(GeminiApi.choose(text, emptyList()))
        assertNull(GeminiApi.choose(text, listOf(p("gemini-3.6-flash", 200))))
    }

    @Test
    fun theTestSaysWhatIsWrongWithAModel() {
        assertEquals("chạy được (1.2 giây)", GeminiApi.probeMessage(200, 1234, "{}"))
        assertTrue(GeminiApi.probeMessage(503, 4000, """{"error":{"code":"service_unavailable","message":"high demand"}}""").contains("quá tải"))
        // The Interactions API's 429 does not say which limit: either may be it.
        assertEquals(
            "hết lượt hoặc đang bị giới hạn tốc độ (429)",
            GeminiApi.probeMessage(429, 900, """{"error":{"code":"too_many_requests","message":"Your project has exceeded a quota."}}"""),
        )
        assertEquals(
            "hết lượt miễn phí hôm nay (429)",
            GeminiApi.probeMessage(429, 900, """{"error":{"message":"Quota exceeded for metric: requests per day"}}"""),
        )
        assertTrue(GeminiApi.probeMessage(404, 300, "{}").contains("404"))
        assertEquals("bị từ chối (400): bad audio", GeminiApi.probeMessage(400, 300, """{"error":{"message":"bad audio"}}"""))
        assertEquals("lỗi 500: không rõ", GeminiApi.probeMessage(500, 300, "not json"))
    }
}
