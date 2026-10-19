package com.cutviet.editor.media

import com.cutviet.editor.media.dsp.WavWriter
import com.cutviet.editor.media.dubbing.AudioFitter
import com.cutviet.editor.media.dubbing.DubbingPipeline
import com.cutviet.editor.media.dubbing.GeminiLiveTranslator
import com.cutviet.editor.media.dubbing.TranslationPrompt
import com.cutviet.editor.media.dubbing.WavTrim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DubTimingTest {
    private fun voice(atOne: Long): suspend (Float) -> Long? = { r -> (atOne / r).toLong() }

    @Test
    fun aLineMayRunIntoTheSilenceAfterItsSentence() = runBlocking {
        // 2.6 s of speech for a 2 s sentence, with 3 s until the next one: no need to speed up.
        val fit = AudioFitter.fit(estimateAtOneMs = 2600, slotMs = 2000, maxMs = 3000, synthesize = voice(2600))!!
        assertEquals(1f, fit.rate, 1e-4f)
        assertTrue(fit.withinTolerance)
    }

    @Test
    fun aLineThatWouldReachTheNextOneIsSpedUpOnlyEnough() = runBlocking {
        val fit = AudioFitter.fit(estimateAtOneMs = 3300, slotMs = 2000, maxMs = 3000, synthesize = voice(3300))!!
        assertTrue(fit.withinTolerance)
        assertTrue(fit.durationMs <= 3000)
        assertTrue("rate ${fit.rate}", fit.rate < 1.15f)
        assertEquals(0L, AudioFitter.miss(fit.durationMs, 2000, 3000))
    }

    @Test
    fun roomRunsToTheNextLineButNotFarPastTheSlot() {
        val room = DubbingPipeline.roomFor(listOf(0L to 1000L, 1500L to 500L, 10_000L to 2000L), endMs = 12_500)
        assertEquals(1500 - DubbingPipeline.GAP_MS, room[0])
        assertEquals(500 + DubbingPipeline.MAX_SPILL_MS, room[1])
        assertEquals(2500L, room[2])
        // Overlapping sentences keep at least their own slot.
        assertEquals(listOf(3000L), DubbingPipeline.roomFor(listOf(0L to 3000L), endMs = 1000).take(1))
    }

    @Test
    fun syllableBudgetFollowsTheVoiceSpeed() {
        assertTrue(TranslationPrompt.syllableBudget(2000, 3.5) < TranslationPrompt.syllableBudget(2000, 5.0))
        assertEquals(9, TranslationPrompt.syllableBudget(2000))
    }

    @Test
    fun trimCutsTheSilenceAroundSpeech() {
        val rate = 24_000
        val f = File.createTempFile("trim", ".wav")
        try {
            WavWriter(f, rate, 1).use { w ->
                val silence = FloatArray(rate / 4) // 250 ms
                val tone = FloatArray(rate / 2) { 0.3f * sin(2 * PI * 300 * it / rate).toFloat() } // 500 ms
                w.write(silence, 0, silence.size)
                w.write(tone, 0, tone.size)
                w.write(silence, 0, silence.size)
            }
            val ms = WavTrim.trim(f)!!
            assertTrue("trimmed to $ms ms", abs(ms - (500 + WavTrim.LEAD_MS + WavTrim.TAIL_MS)) <= 20)
            // The file is still a valid WAV of that length.
            assertEquals(ms, WavTrim.trim(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun liveTranslateSetupPutsEachFieldWhereTheServerWantsIt() {
        val setup = GeminiLiveTranslator.setupMessage("gemini-3.5-live-translate-preview").getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        val gen = setup.getJSONObject("generationConfig")
        assertFalse(gen.has("inputAudioTranscription"))
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals("vi", gen.getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertFalse(setup.has("systemInstruction"))
    }
}
