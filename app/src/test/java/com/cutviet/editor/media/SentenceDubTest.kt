package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiDubber
import com.cutviet.editor.media.dubbing.Gender
import com.cutviet.editor.media.dubbing.LineVoice
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeechDetector
import com.cutviet.editor.media.dubbing.SpeechDetector.Range
import com.cutviet.editor.media.dubbing.SpeechSegment
import com.cutviet.editor.media.dubbing.VoicePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The dub cut into one line per sentence, each placed where its speech starts. */
class SentenceDubTest {
    private val rate = 16_000

    /** Quiet noise with loud "speech" bursts at the given (startMs, endMs). */
    private fun signal(totalMs: Int, bursts: List<Pair<Int, Int>>): FloatArray {
        val rnd = Random(11)
        return FloatArray(totalMs * rate / 1000) { i ->
            val ms = i * 1000 / rate
            val noise = (rnd.nextFloat() - 0.5f) * 0.002f
            if (bursts.any { ms in it.first until it.second }) noise + 0.3f * sin(2 * PI * 180 * i / rate).toFloat() else noise
        }
    }

    private fun near(expected: Long, actual: Long) = assertTrue("$actual is not near $expected", abs(actual - expected) <= 60)

    @Test
    fun everyPauseBetweenSentencesStartsANewLine() {
        val x = signal(14_000, listOf(1000 to 2500, 2900 to 4500, 4700 to 6000, 8000 to 8400, 9000 to 12_000))
        val r = SpeechDetector.sentences(x, rate)
        // 400 ms pause: two lines. 200 ms: one. A 400 ms word joins the sentence 600 ms after it.
        assertEquals(3, r.size)
        near(1000, r[0].startMs); near(2500, r[0].endMs)
        near(2900, r[1].startMs); near(6000, r[1].endMs)
        near(8000, r[2].startMs); near(12_000, r[2].endMs)
        // The subtitles still get their longer segments.
        val subtitles = SpeechDetector.detect(x, rate)
        near(1000, subtitles[0].startMs); near(6000, subtitles[0].endMs)
    }

    @Test
    fun aVoiceInAFileGivesTheSameRanges() {
        val x = signal(9_000, listOf(500 to 2000, 2600 to 4000, 6000 to 8000))
        val f = File.createTempFile("voice", ".f32")
        try {
            val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            x.forEach { bb.putFloat(it) }
            f.writeBytes(bb.array())
            assertEquals(SpeechDetector.detect(x, rate, SpeechDetector.SENTENCE_GAP_MS, SpeechDetector.SENTENCE_MAX_MS), SpeechDetector.detectFile(f, rate))
        } finally {
            f.delete()
        }
    }

    @Test
    fun shortPiecesJoinTheCloserNeighbourOnly() {
        val joined = SpeechDetector.joinShort(
            listOf(
                Range(0, 2000), Range(2300, 2700), // 400 ms, 300 ms after a sentence: joins it
                Range(5000, 5400),                 // alone: 2.3 s and 1.6 s from its neighbours
                Range(7000, 9000), Range(9500, 9900), Range(10_100, 12_000), // closer to the one after
            ),
        )
        assertEquals(listOf(Range(0, 2700), Range(5000, 5400), Range(7000, 9000), Range(9500, 12_000)), joined)
        // Never longer than a sentence may be.
        assertEquals(
            listOf(Range(0, 7900), Range(8000, 8500)),
            SpeechDetector.joinShort(listOf(Range(0, 7900), Range(8000, 8500))),
        )
    }

    @Test
    fun whisperSentencesStartWhereTheVoiceStarts() {
        val heard = listOf(SpeechSegment(5000, 9400, "a"), SpeechSegment(9400, 13_000, "b"), SpeechSegment(20_000, 21_000, "c"))
        // Pauses between: each sentence trimmed to its own speech.
        val apart = SpeechDetector.tighten(heard, listOf(Range(5600, 8900), Range(10_100, 12_700)))
        assertEquals(listOf(5600L to 8900L, 10_100L to 12_700L, 20_000L to 21_000L), apart.map { it.startMs to it.endMs })
        assertEquals(listOf("a", "b", "c"), apart.map { it.text })
        // Speech running on from one sentence into the next: the cut between them stays.
        val joined = SpeechDetector.tighten(heard, listOf(Range(5600, 12_700)))
        assertEquals(listOf(5600L to 9400L, 9400L to 12_700L), joined.take(2).map { it.startMs to it.endMs })
        // Never moved earlier, and nothing found: left as it was.
        val late = SpeechDetector.tighten(heard.take(1), listOf(Range(4000, 9000)))
        assertEquals(5000L to 9000L, late.single().let { it.startMs to it.endMs })
        assertEquals(heard, SpeechDetector.tighten(heard, emptyList()))
        // A blip at the very end is not a sentence: left as it was.
        assertEquals(heard.take(1), SpeechDetector.tighten(heard.take(1), listOf(Range(9300, 9500))))
    }

    @Test
    fun eachPieceIsAskedForWithItsLengthAndSyllables() {
        val voice = FloatArray(10 * rate)
        val ranges = listOf(Range(0, 1000), Range(2000, 4400), Range(6000, 9000))
        val req = GeminiDubber.listenRequest("gemini-3.8-flash", SourceLanguage.ENGLISH, voice, ranges, listOf(1, 2), "Trước: hi → chào", schema = true)
        val input = req.getJSONArray("input")
        // Prompt, then a label and the audio for each piece.
        assertEquals(5, input.length())
        val prompt = input.getJSONObject(0).getString("text")
        assertTrue(prompt.contains("2 đoạn ghi âm lời nói tiếng anh"))
        assertTrue(prompt.contains("Trước: hi → chào"))
        assertFalse(prompt.contains("speaker"))
        assertEquals("[S2] 2.4 giây, khoảng 11 âm tiết", input.getJSONObject(1).getString("text"))
        assertEquals("audio", input.getJSONObject(2).getString("type"))
        assertEquals("[S3] 3.0 giây, khoảng 14 âm tiết", input.getJSONObject(3).getString("text"))
        // 3 s and 100 ms on each side, 16-bit at 16 kHz, plus the WAV header.
        val wav = java.util.Base64.getDecoder().decode(input.getJSONObject(4).getString("data"))
        assertEquals(44 + 3200 * 16 * 2, wav.size)
        assertTrue(req.has("response_format"))
        assertFalse(GeminiDubber.listenRequest("m", SourceLanguage.CHINESE, voice, ranges, listOf(0), "", schema = false).has("response_format"))
        assertEquals(1, GeminiDubber.syllableBudget(100))
    }

    @Test
    fun answersAreReadPieceByPiece() {
        val answer = """```json
            {"lines":[
              {"index":2,"source":"Where are you going?","vi":"Anh đi đâu đấy?","emotion":"Curious"},
              {"index":1,"source":"","vi":"","emotion":"neutral"},
              {"index":3,"source":"Home.","text":"Về nhà.","emotion":"neutral"},
              {"index":9,"source":"x","vi":"y","emotion":""},
              {"index":2,"source":"again","vi":"lần nữa","emotion":""}
            ]}
            ```"""
        val got = GeminiDubber.parseLines(answer, setOf(0, 1, 2))!!
        assertEquals(setOf(0, 1, 2), got.keys)
        assertEquals("Anh đi đâu đấy?", got.getValue(1).text)
        assertEquals("Where are you going?", got.getValue(1).source)
        assertEquals("curious", got.getValue(1).emotion)
        assertEquals("", got.getValue(0).text)
        assertEquals("Về nhà.", got.getValue(2).text)
        assertEquals("", got.getValue(2).emotion)
        assertNull(GeminiDubber.parseLines("không phải JSON", setOf(0)))
    }

    @Test
    fun namedVoicesAreFoundAgainFromASavedLine() {
        assertEquals(VoicePresets.ALL.size, VoicePresets.ALL.map { it.id }.toSet().size)
        assertEquals("Kore", VoicePresets.byId(null).voice)
        assertEquals("Kore", VoicePresets.byId("không có").voice)
        assertEquals(Gender.MALE, VoicePresets.byId("charon").gender)
        val boy = VoicePresets.byId("puck_child")
        assertEquals(boy, VoicePresets.of(boy.lineVoice("excited")))
        assertEquals("puck", VoicePresets.of(LineVoice("Puck", Gender.MALE))?.id)
        assertNull(VoicePresets.of(LineVoice("Zephyr", Gender.FEMALE)))
        assertNull(VoicePresets.of(LineVoice(null, Gender.FEMALE)))
        // Every gender has voices to pick from.
        Gender.entries.forEach { g -> assertTrue(VoicePresets.ALL.any { it.gender == g }) }
        // The child voices are told to sound like children.
        assertTrue(GeminiDubber.persona(GeminiDubber.Role(boy.voice, boy.gender, boy.description)).contains("child"))
    }
}
