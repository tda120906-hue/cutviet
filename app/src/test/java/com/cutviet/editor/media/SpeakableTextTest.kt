package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.SpeakableText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextTest {
    @Test
    fun findsEnglishWordsButNotVietnamese() {
        assertEquals(listOf("Facebook", "OK"), SpeakableText.englishWords("Người ta vừa đăng lên Facebook rồi, OK?"))
        assertEquals(listOf("CEO", "meeting"), SpeakableText.englishWords("Anh ấy là CEO, sáng nay có meeting."))
        // Vietnamese written with or without marks is not English.
        assertFalse(SpeakableText.hasEnglish("Không sao đâu, ban nay toi khong biet."))
        assertFalse(SpeakableText.hasEnglish("Nguyễn Văn An đi học."))
    }

    @Test
    fun respellsKnownWordsAndAbbreviations() {
        assertEquals("Lên phây búc đi, ô kê?", SpeakableText.respell("Lên Facebook đi, OK?"))
        assertEquals("Mua cái ti vi mới", SpeakableText.respell("Mua cái TV mới"))
        // Unknown words stay as written.
        assertEquals("Gặp Jennifer nhé", SpeakableText.respell("Gặp Jennifer nhé"))
    }

    @Test
    fun usesTheModelOnlyWhenItsAnswerFits() = runBlocking {
        val line = "Gặp Jennifer ở Starbucks nhé"
        assertEquals("Gặp gie ni phơ ở xta bấc nhé", SpeakableText.forPhone(line) { "Gặp gie ni phơ ở xta bấc nhé" })
        // An answer that is no longer the line (or still English) falls back to the word list.
        assertEquals(line, SpeakableText.forPhone(line) { "Sure! Here is the sentence: Gặp Jennifer ở Starbucks nhé, rewritten for you." })
        assertEquals(line, SpeakableText.forPhone(line) { null })
        // Nothing English: the model is not asked.
        var asked = false
        assertEquals("Chào anh", SpeakableText.forPhone("Chào anh") { asked = true; "x" })
        assertFalse(asked)
        assertTrue(SpeakableText.acceptable("Lên Facebook đi", "Lên phây búc đi"))
    }
}
