package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeechSegment
import com.cutviet.editor.media.dubbing.TranslationPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptTest {
    @Test
    fun promptNumbersLinesWithSyllableBudgets() {
        val p = TranslationPrompt.build(
            SourceLanguage.ENGLISH,
            context = listOf(SpeechSegment(0, 1000, "Hi.")),
            lines = listOf(SpeechSegment(1000, 3000, "How are you?"), SpeechSegment(3000, 4000, "Fine.")),
        )
        assertTrue(p.contains("1. [tối đa ${TranslationPrompt.syllableBudget(2000)} âm tiết] How are you?"))
        assertTrue(p.contains("2. [tối đa ${TranslationPrompt.syllableBudget(1000)} âm tiết] Fine."))
        assertTrue(p.contains("- Hi."))
        assertEquals(9, TranslationPrompt.syllableBudget(2000))
    }

    @Test
    fun parsesNumberedAnswersAndCleansEchoes() {
        val r = "Đây là bản dịch:\n1. [tối đa 9 âm tiết] Bạn khỏe không?\n2) \"Tôi ổn.\"\n"
        assertEquals(listOf("Bạn khỏe không?", "Tôi ổn."), TranslationPrompt.parse(r, 2))
    }

    @Test
    fun missingLinesComeBackAsNull() {
        assertEquals(listOf("Một", null, "Ba"), TranslationPrompt.parse("1. Một\n3. Ba", 3))
        assertNull(TranslationPrompt.parse("không có số", 2))
        assertEquals(listOf("Xin chào"), TranslationPrompt.parse("Xin chào", 1))
    }
}
