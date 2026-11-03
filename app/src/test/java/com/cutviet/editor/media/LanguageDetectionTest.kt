package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiDubber
import com.cutviet.editor.media.dubbing.GeminiSubtitler
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeechDetector.Range
import com.cutviet.editor.media.dubbing.SpeechSegment
import com.cutviet.editor.media.dubbing.TranslationPrompt
import com.cutviet.editor.media.dubbing.WhisperAsr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Tự phát hiện": the language is found by listening, and Vietnamese speech is kept as said. */
class LanguageDetectionTest {

    @Test
    fun theUserPicksFromEnglishChineseOrFound() {
        assertEquals(listOf("Tiếng Anh", "Tiếng Trung", "Tự phát hiện"), SourceLanguage.CHOICES.map { it.label })
        assertEquals(SourceLanguage.entries.size, SourceLanguage.entries.map { it.code }.toSet().size)
        // A saved pick comes back; Vietnamese (an older subtitle choice) or nothing saved: found by listening.
        assertEquals(SourceLanguage.CHINESE, SourceLanguage.choiceOf("CHINESE"))
        assertEquals(SourceLanguage.ENGLISH, SourceLanguage.choiceOf("ENGLISH"))
        assertEquals(SourceLanguage.AUTO, SourceLanguage.choiceOf("VIETNAMESE"))
        assertEquals(SourceLanguage.AUTO, SourceLanguage.choiceOf(null))
        assertEquals(SourceLanguage.AUTO, SourceLanguage.choiceOf("không có"))
    }

    @Test
    fun whatWhisperHeardDecidesOnlyWhenTheLanguageWasLeftToBeFound() {
        assertEquals(SourceLanguage.JAPANESE, SourceLanguage.resolve(SourceLanguage.AUTO, "ja"))
        assertEquals(SourceLanguage.VIETNAMESE, SourceLanguage.resolve(SourceLanguage.AUTO, "vi"))
        assertEquals(SourceLanguage.CANTONESE, SourceLanguage.resolve(SourceLanguage.AUTO, "yue"))
        // Not listed, or not found: still translated, without naming a language.
        assertEquals(SourceLanguage.AUTO, SourceLanguage.resolve(SourceLanguage.AUTO, "sw"))
        assertEquals(SourceLanguage.AUTO, SourceLanguage.resolve(SourceLanguage.AUTO, null))
        // Picked by the user: kept, whatever was heard.
        assertEquals(SourceLanguage.ENGLISH, SourceLanguage.resolve(SourceLanguage.ENGLISH, "ja"))
        assertNull(SourceLanguage.ofCode("auto"))
        assertEquals("tiếng nhật", SourceLanguage.JAPANESE.promptName)
        assertNull(SourceLanguage.AUTO.promptName)
    }

    @Test
    fun theLanguageIsListenedForWherePeopleSpeak() {
        // 10 s, 60 s and 30 s of speech: windows at the 2nd and 3rd stretch (the first is under 10 %).
        val speech = listOf(Range(5_000, 15_000), Range(20_000, 80_000), Range(100_000, 130_000))
        assertArrayEquals(longArrayOf(19_800, 99_800), WhisperAsr.detectionOffsets(speech))
        // Many short sentences: three places, each at least 30 s from the others.
        val sentences = (0 until 60).map { Range(it * 10_000L + 3_000, it * 10_000L + 7_000) }
        val at = WhisperAsr.detectionOffsets(sentences)
        assertEquals(3, at.size)
        assertEquals(listOf(62_800L, 302_800L, 512_800L), at.toList())
        // A short clip: one window; no speech found: from the start.
        assertArrayEquals(longArrayOf(800), WhisperAsr.detectionOffsets(listOf(Range(1_000, 3_000))))
        assertArrayEquals(longArrayOf(100), WhisperAsr.detectionOffsets(listOf(Range(300, 900), Range(1_200, 2_000))))
        assertArrayEquals(longArrayOf(0), WhisperAsr.detectionOffsets(emptyList()))
    }

    @Test
    fun geminiIsToldToFindTheLanguageAndKeepVietnamese() {
        val sub = GeminiSubtitler.prompt(SourceLanguage.AUTO, 3)
        assertTrue(sub.contains(GeminiSubtitler.AUTO_LANGUAGE))
        assertTrue(sub.contains("lượt nói tiếng Việt thì là chính câu tiếng Việt đó"))
        assertFalse(sub.contains("tự phát hiện"))
        // English and Chinese prompts stay as they were.
        val en = GeminiSubtitler.prompt(SourceLanguage.ENGLISH, 3)
        assertTrue(en.contains("3 đoạn ghi âm lời nói tiếng anh trong một video"))
        assertFalse(en.contains(GeminiSubtitler.AUTO_LANGUAGE))

        val dub = GeminiDubber.listenPrompt(SourceLanguage.AUTO, 2)
        assertTrue(dub.contains("2 đoạn ghi âm lời nói trong một video"))
        assertTrue(dub.contains(GeminiSubtitler.AUTO_LANGUAGE))
        assertTrue(dub.contains("đoạn nói tiếng Việt thì giữ đúng lời đó"))
        assertFalse(dub.contains("tự phát hiện"))
        val zh = GeminiDubber.listenPrompt(SourceLanguage.CHINESE, 2)
        assertTrue(zh.contains("2 đoạn ghi âm lời nói tiếng trung"))
        assertFalse(zh.contains(GeminiSubtitler.AUTO_LANGUAGE))
    }

    @Test
    fun gemmaIsToldTheLanguageWhisperHeard() {
        val lines = listOf(SpeechSegment(0, 2000, "こんにちは"))
        assertTrue(TranslationPrompt.build(SourceLanguage.JAPANESE, emptyList(), lines).startsWith("Dịch các câu thoại sau từ tiếng nhật sang tiếng Việt"))
        assertTrue(TranslationPrompt.build(SourceLanguage.ENGLISH, emptyList(), lines).startsWith("Dịch các câu thoại sau từ tiếng anh sang tiếng Việt"))
        assertTrue(TranslationPrompt.build(SourceLanguage.AUTO, emptyList(), lines).startsWith("Dịch các câu thoại sau sang tiếng Việt"))
        assertTrue(TranslationPrompt.buildSubtitles(SourceLanguage.AUTO, emptyList(), lines).startsWith("Dịch các câu thoại sau sang tiếng Việt để làm phụ đề"))
        assertTrue(TranslationPrompt.shorten(SourceLanguage.AUTO, "こんにちは", "Xin chào bạn", 3).contains("Câu gốc: こんにちは"))
        assertTrue(TranslationPrompt.shorten(SourceLanguage.KOREAN, "안녕", "Xin chào bạn", 3).contains("Câu gốc (tiếng hàn): 안녕"))
    }
}
