package com.cutviet.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCuesTest {
    @Test
    fun shortSentenceStaysWhole() {
        val c = SubtitleCues.split(1_000, 3_000, "  Xin chào   các bạn ")
        assertEquals(1, c.size)
        assertEquals("Xin chào các bạn", c[0].text)
        assertEquals(1_000, c[0].startMs)
        assertEquals(3_000, c[0].endMs)
    }

    @Test
    fun longSentenceIsSplitAtPunctuationAndCoversItsTime() {
        val text = "Hôm nay chúng ta sẽ cùng nhau đi chợ mua rau và cá cho bữa tối. " +
            "Sau đó chúng ta sẽ về nhà nấu ăn, rồi cả nhà cùng ngồi xem phim đến khuya."
        val c = SubtitleCues.split(0, 10_000, text)
        assertTrue(c.size >= 2)
        assertTrue(c.all { it.text.length <= SubtitleCues.MAX_CHARS + 20 })
        assertTrue(c[0].text.endsWith("tối."))
        assertEquals(0, c.first().startMs)
        assertEquals(10_000, c.last().endMs)
        for (i in 1 until c.size) assertEquals(c[i - 1].endMs, c[i].startMs)
        assertEquals(text.replace(" ", ""), c.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun longInTimeIsSplitEvenWhenShortInText() {
        val c = SubtitleCues.split(0, 15_000, "một hai ba bốn năm sáu bảy tám chín mười mười một mười hai")
        assertTrue(c.size >= 2)
    }

    @Test
    fun tidyGivesTimeToReadButNeverOverlaps() {
        val cues = listOf(
            SubtitleCues.Cue(5_000, 5_300, "Câu thứ hai khá dài, cần thời gian để đọc cho kịp nhé"),
            SubtitleCues.Cue(0, 200, "Một"),
            SubtitleCues.Cue(5_400, 6_000, "Ba"),
        )
        val t = SubtitleCues.tidy(cues, endMs = 20_000)
        assertEquals(listOf("Một", "Câu thứ hai khá dài, cần thời gian để đọc cho kịp nhé", "Ba"), t.map { it.text })
        assertEquals(SubtitleCues.MIN_MS, t[0].endMs - t[0].startMs)
        assertEquals(5_400 - SubtitleCues.GAP_MS, t[1].endMs)
        for (i in 1 until t.size) assertTrue(t[i].startMs >= t[i - 1].endMs)
        assertTrue(t[2].endMs <= 20_000)
    }

    @Test
    fun tidyClipsToTheVideo() {
        val t = SubtitleCues.tidy(listOf(SubtitleCues.Cue(9_500, 9_900, "Cuối"), SubtitleCues.Cue(12_000, 13_000, "Ngoài")), endMs = 10_000)
        assertEquals(1, t.size)
        assertEquals(10_000, t[0].endMs)
    }

    @Test
    fun sourceTimeMapsThroughTrimAndSpeed() {
        assertEquals(5_000, SubtitleCues.toTimeline(4_000, clipStartMs = 3_000, trimStartMs = 2_000, speed = 1f))
        assertEquals(4_000, SubtitleCues.toTimeline(4_000, clipStartMs = 3_000, trimStartMs = 2_000, speed = 2f))
    }

    @Test
    fun findsTheLineUnderThePlayhead() {
        val subs = listOf(Subtitle(startMs = 0, endMs = 1_000, text = "a"), Subtitle(startMs = 2_000, endMs = 3_000, text = "b"))
        assertEquals("a", SubtitleCues.at(subs, 0)?.text)
        assertNull(SubtitleCues.at(subs, 1_000))
        assertNull(SubtitleCues.at(subs, 1_500))
        assertEquals("b", SubtitleCues.at(subs, 2_999)?.text)
        assertNull(SubtitleCues.at(subs, 3_000))
        assertNull(SubtitleCues.at(emptyList(), 0))
    }

    @Test
    fun writesSrt() {
        val srt = SubtitleCues.srt(listOf(Subtitle(startMs = 61_234, endMs = 3_725_005, text = "Xin chào")))
        assertEquals("1\n00:01:01,234 --> 01:02:05,005\nXin chào\n\n", srt)
    }

    @Test
    fun dropsKnownWhisperInventions() {
        assertTrue(SubtitleCues.isHallucination("Hãy subscribe cho kênh Ghiền Mì Gõ để không bỏ lỡ những video hấp dẫn"))
        assertTrue(SubtitleCues.isHallucination("[Music]"))
        assertFalse(SubtitleCues.isHallucination("Tôi thích nghe nhạc vào buổi tối"))
    }

    @Test
    fun subtitlesSurviveSaving() {
        val p = Project(
            name = "t",
            subtitles = listOf(Subtitle(startMs = 100, endMs = 900, text = "Xin chào", source = "Hello")),
            subtitleStyle = SubtitleStyle(size = 0.07f, y = 0.5f, box = true),
        )
        val back = ProjectStore.fromJson(org.json.JSONObject(ProjectStore.toJson(p).toString()))
        assertEquals(p.subtitles, back.subtitles)
        assertEquals(p.subtitleStyle, back.subtitleStyle)
    }
}
