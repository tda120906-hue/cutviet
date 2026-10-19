package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.WhisperTranscript
import com.cutviet.editor.media.dubbing.WhisperTranscript.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperTranscriptTest {
    private fun tok(text: String, t0: Long, t1: Long) = Token(text.toByteArray(Charsets.UTF_8), t0, t1, 1f)

    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test
    fun englishTokensBecomeWordsAndSentences() {
        val tokens = listOf(
            tok(" Hello", 0, 400), tok(" every", 400, 700), tok("one", 700, 900), tok(".", 900, 950),
            tok(" How", 1200, 1400), tok(" are", 1400, 1500), tok(" you", 1500, 1700), tok("?", 1700, 1750),
        )
        val words = WhisperTranscript.words(tokens)
        assertEquals(listOf(" Hello", " everyone.", " How", " are", " you?"), words.map { it.text })
        assertEquals(400L, words[1].startMs)
        val s = WhisperTranscript.sentences(words, offsetMs = 10_000)
        assertEquals(listOf("Hello everyone.", "How are you?"), s.map { it.text })
        assertEquals(10_000L, s[0].startMs)
        assertEquals(10_950L, s[0].endMs)
        assertEquals(11_200L, s[1].startMs)
    }

    @Test
    fun chineseCharactersSplitAcrossTokensAreJoined() {
        // "你好。" where "你" (e4 bd a0) arrives as two tokens.
        val ni = "你".toByteArray(Charsets.UTF_8)
        val tokens = listOf(
            Token(ni.copyOfRange(0, 2), 0, 100, 1f),
            Token(ni.copyOfRange(2, 3), 100, 200, 1f),
            tok("好", 200, 400),
            tok("。", 400, 450),
            tok("再见", 2000, 2500),
        )
        val words = WhisperTranscript.words(tokens)
        assertEquals(listOf("你", "好", "。", "再见"), words.map { it.text })
        assertEquals(0L, words[0].startMs)
        assertEquals(200L, words[0].endMs)
        val s = WhisperTranscript.sentences(words)
        assertEquals(listOf("你好。", "再见"), s.map { it.text })
    }

    @Test
    fun longPausesAndLongSentencesAreCut() {
        val words = WhisperTranscript.words(
            listOf(tok(" one", 0, 500), tok(" two", 2000, 2500)) +
                (0 until 40).map { tok(" w$it", 3000L + it * 400, 3000L + it * 400 + 300) },
        )
        val s = WhisperTranscript.sentences(words)
        assertEquals("one", s[0].text)
        s.forEach { assertTrue(it.endMs - it.startMs <= WhisperTranscript.MAX_SENTENCE_MS + 400) }
        assertEquals(words.size, s.sumOf { it.words.size })
    }

    @Test
    fun parsesTheJniJsonAndDropsBracketTags() {
        val json = """{"segments":[{"t0":0,"t1":2000,"text":"${hex(" [Music] Hi.")}","words":[
            {"t0":0,"t1":500,"p":0.9,"text":"${hex(" [Music]")}"},
            {"t0":600,"t1":900,"p":0.8,"text":"${hex(" Hi")}"},
            {"t0":900,"t1":950,"p":0.8,"text":"${hex(".")}"}]}]}"""
        val words = WhisperTranscript.words(WhisperTranscript.parseTokens(json))
        assertEquals(listOf(" Hi."), words.map { it.text })
    }
}
