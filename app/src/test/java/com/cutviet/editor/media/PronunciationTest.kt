package com.cutviet.editor.media

import com.cutviet.editor.media.tts.Pronunciation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationTest {
    private val builtIn = Pronunciation.parse(
        """
        # comment
        deadline	đét lai
        youtube	diu túp
        video	vi đi ô
        checkin	chéc in
        on	on
        AI	ây ai
        """.trimIndent(),
    )
    /** Stand-in for espeak "en". */
    private val english = mapOf("smooth" to "smˈuːð", "iphone" to "ˈaɪ fˈəʊn", "off" to "ˈɒf")
    private fun en(w: String) = english[w.lowercase()]

    @Test fun vietnameseSyllables() {
        for (w in listOf("con", "ban", "in", "an", "hay", "long", "qua", "quan", "gi", "gia", "nhanh", "khoe", "toan", "y")) {
            assertTrue(w, Pronunciation.isVietnameseSyllable(w))
        }
        for (w in listOf("video", "like", "game", "top", "hot", "chat", "share", "fan", "bus", "set", "email", "deadline", "ok", "smooth")) {
            assertFalse(w, Pronunciation.isVietnameseSyllable(w))
        }
    }

    @Test fun keys() {
        assertEquals("tiktok", Pronunciation.key("TikTok"))
        assertEquals("AI", Pronunciation.key("AI"))
        assertEquals("check-in", Pronunciation.key("Check-in"))
        assertNull(Pronunciation.key("Hôm"))
    }

    @Test fun respellsEnglishOnly() {
        val out = Pronunciation.respell("Hôm nay deadline gấp, xem video trên YouTube bằng iPhone nhé", emptyMap(), builtIn, ::en)
        assertEquals("Hôm nay đét lai gấp, xem vi đi ô trên diu túp bằng ai phôn nhé", out)
    }

    @Test fun pluralsHyphensAndCase() {
        assertEquals("vi đi ô", Pronunciation.respell("videos", emptyMap(), builtIn, ::en))
        assertEquals("chéc in", Pronunciation.respell("check-in", emptyMap(), builtIn, ::en))
        assertEquals("diu túp", Pronunciation.respell("YouTube's", emptyMap(), builtIn, ::en))
        // "AI" only in capitals: "ai" is Vietnamese ("who").
        assertEquals("ây ai và ai", Pronunciation.respell("AI và ai", emptyMap(), builtIn, ::en))
    }

    @Test fun vietnameseWordsWithoutAccentsAreLeftAlone() {
        assertEquals("con on in", Pronunciation.respell("con on in", emptyMap(), builtIn, ::en))
        // Capitals not in the list are left to espeak (VTV, USB…).
        assertEquals("VTV", Pronunciation.respell("VTV", emptyMap(), builtIn, ::en))
    }

    @Test fun rulesForOtherWords() {
        assertEquals("xờ mút", Pronunciation.respell("smooth", emptyMap(), builtIn, ::en))
        assertEquals("óp", Pronunciation.respell("off", emptyMap(), builtIn, ::en))
        // No English pronunciation available: left as it is.
        assertEquals("zyx", Pronunciation.respell("zyx", emptyMap(), builtIn, ::en))
    }

    @Test fun userWordsWin() {
        val user = mapOf("deadline" to "đét lai nờ", "con" to "côn")
        assertEquals("đét lai nờ côn", Pronunciation.respell("Deadline con", user, builtIn, ::en))
        val r = Pronunciation.reading("deadline", user, builtIn, ::en)!!
        assertEquals(Pronunciation.Source.USER, r.source)
        assertEquals(Pronunciation.Source.BUILT_IN, Pronunciation.reading("video", emptyMap(), builtIn, ::en)!!.source)
        assertEquals(Pronunciation.Source.RULES, Pronunciation.reading("smooth", emptyMap(), builtIn, ::en)!!.source)
    }
}
