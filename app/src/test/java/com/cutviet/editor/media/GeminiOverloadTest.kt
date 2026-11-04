package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiApi
import com.cutviet.editor.media.dubbing.GeminiDubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A model Google reports as overloaded (503) is replaced by a lighter one of the same kind. */
class GeminiOverloadTest {
    @Test
    fun listeningFallsBackToLighterTextModels() {
        val asked = GeminiDubber.DEFAULT_TEXT_MODEL
        assertEquals("gemini-3.5-flash", GeminiApi.nextModel(asked, listOf(asked)))
        assertEquals("gemini-3.5-flash-lite", GeminiApi.nextModel(asked, listOf(asked, "gemini-3.5-flash")))
        assertNull(GeminiApi.nextModel(asked, listOf(asked, "gemini-3.5-flash", "gemini-3.5-flash-lite")))
        // A model the user typed that is itself a fallback is not tried twice.
        assertEquals(listOf("gemini-3.5-flash-lite"), GeminiApi.fallbacksFor("gemini-3.5-flash"))
    }

    @Test
    fun speechFallsBackToTheLiteSpeechModel() {
        val asked = GeminiDubber.DEFAULT_TTS_MODEL
        assertEquals("gemini-3.8-flash-lite-tts", GeminiApi.nextModel(asked, listOf(asked)))
        assertNull(GeminiApi.nextModel(asked, listOf(asked, "gemini-3.8-flash-lite-tts")))
        assertEquals(emptyList<String>(), GeminiApi.fallbacksFor("gemini-3.8-flash-lite-tts"))
    }
}
