package com.cutviet.editor.media

import com.cutviet.editor.media.dsp.KaldiFbank
import com.cutviet.editor.media.dubbing.Diarization
import com.cutviet.editor.media.dubbing.GeminiDubber
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SpeakerClustering
import com.cutviet.editor.media.dubbing.SpeakerDiarizer
import com.cutviet.editor.media.dubbing.SpeakerTurns
import com.cutviet.editor.media.dubbing.SpeechDetector
import com.cutviet.editor.media.dubbing.SpeechSegment
import com.cutviet.editor.media.dubbing.Word
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeakerDiarizationTest {
    @Test
    fun filterbankMatchesKaldi() {
        val x = FloatArray(4000) { i ->
            (0.3 * sin(2 * PI * 440 * i / 16000) + 0.1 * sin(2 * PI * 1234 * i / 16000) + 0.05 * sin(2 * PI * 3100 * i / 16000 + 0.5)).toFloat()
        }
        val fb = KaldiFbank()
        val f = fb.compute(x)
        assertEquals(25, fb.frames(x.size))
        assertEquals(25 * 80, f.size)
        // From kaldi-native-fbank (dither 0, snip_edges false, 80 bins, high_freq -400).
        val expected = mapOf(
            (0 to 0) to -5.3858f, (0 to 10) to -0.8459f, (0 to 25) to -4.8236f, (0 to 40) to -2.1389f, (0 to 60) to -1.5744f,
            (12 to 10) to -5.848f,
            (24 to 0) to -5.691f, (24 to 10) to -1.0409f, (24 to 25) to -1.1418f, (24 to 40) to -3.1905f, (24 to 60) to -2.4838f,
        )
        for ((at, v) in expected) assertEquals("frame ${at.first} bin ${at.second}", v, f[at.first * 80 + at.second], 0.02f)
        val middle = (0 until 80).maxByOrNull { f[12 * 80 + it] }
        assertEquals(54, middle)
    }

    private fun unit(vararg v: Float): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun clusteringJoinsTheSameVoiceAndFoldsStragglersIn() {
        val a = (0 until 6).map { unit(1f, 0.05f * it, 0f, 0.02f) }
        val b = (0 until 5).map { unit(0f, 0.03f * it, 1f, 0.05f) }
        val stray = unit(0f, 1f, 0.3f, 0f)
        val e = listOf(a[0], b[0], a[1], b[1], a[2], b[2], a[3], b[3], a[4], b[4], a[5], stray)
        val w = DoubleArray(e.size) { if (it == e.lastIndex) 0.5 else 2.0 }

        val auto = SpeakerClustering.cluster(e, w, SpeakerDiarizer.THRESHOLD, minWeight = 4.0)
        assertArrayEquals(intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1), auto)

        // Told there are three: the stray voice keeps its own group.
        val three = SpeakerClustering.cluster(e, w, SpeakerDiarizer.THRESHOLD, minWeight = 4.0, k = 3)
        assertEquals(3, three.toSet().size)
        assertTrue(three.last() !in three.dropLast(1))
        // Told there is one.
        assertEquals(setOf(0), SpeakerClustering.cluster(e, w, SpeakerDiarizer.THRESHOLD, 4.0, k = 1).toSet())
        assertEquals(0, SpeakerClustering.cluster(emptyList(), DoubleArray(0), 0.5, 1.0).size)
    }

    @Test
    fun speakersAreNumberedByWhenTheyFirstSpeak() {
        val d = Diarization.renumbered(intArrayOf(0, 4, 4, 0, 1, 5, 0))
        assertEquals(2, d.speakers)
        assertEquals(listOf(0, 1, 1, 0, 2, 3, 0), (0 until d.frames).map { d.at(it) })
    }

    /** Speaker 0 until [changeMs], speaker 1 after, over [ms] of audio. */
    private fun twoPeople(ms: Long, changeMs: Long): Diarization {
        val frames = (ms * 16 / SpeakerDiarizer.FRAME_SHIFT).toInt() + 1
        val mask = IntArray(frames) { g ->
            val centre = (g * SpeakerDiarizer.FRAME_SHIFT + SpeakerDiarizer.FRAME_SIZE / 2.0) / 16
            if (centre < changeMs) 1 else 2
        }
        return Diarization(mask, 2)
    }

    @Test
    fun theSpeakerHeardMostInATimeWins() {
        val d = twoPeople(4000, 2000)
        assertEquals(0, d.dominant(0, 1500))
        assertEquals(1, d.dominant(2000, 3000))
        assertEquals(1, d.dominant(1900, 2300))
        assertNull(d.dominant(9000, 9500))
        assertNull(Diarization(IntArray(10), 0).dominant(0, 100))
    }

    @Test
    fun windowsCoverTheWholeClip() {
        assertEquals(16_000, SpeakerDiarizer.stepFor(160_000))
        assertEquals(16_000, SpeakerDiarizer.stepFor(16_000 * 60))
        assertEquals(32_000, SpeakerDiarizer.stepFor(16_000 * 3600))
        assertArrayEquals(intArrayOf(0), SpeakerDiarizer.chunkStarts(100_000, 16_000))
        assertArrayEquals(intArrayOf(0, 16_000, 32_000, 48_000), SpeakerDiarizer.chunkStarts(200_000, 16_000))
        val long = 16_000 * 400
        val starts = SpeakerDiarizer.chunkStarts(long, SpeakerDiarizer.stepFor(long))
        assertTrue(starts.size <= 201)
        assertTrue(starts.last() + SpeakerDiarizer.WINDOW >= long)
    }

    @Test
    fun segmentationClassesBecomeLocalSpeakers() {
        fun row(best: Int) = FloatArray(7) { if (it == best) 0f else -5f }
        val l = SpeakerDiarizer.localLabels(arrayOf(row(0), row(1), row(4), row(6)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1), l)
    }

    @Test
    fun fingerprintsUseSoloSpeechWhenThereIsEnough() {
        val frames = 30
        val labels = ByteArray(frames * 3)
        for (f in 0 until frames) {
            labels[f * 3] = 1
            if (f >= 15) labels[f * 3 + 1] = 1
        }
        val first = SpeakerDiarizer.embeddingFrames(labels, frames, 0)!!
        assertEquals((0 until 15).toList(), first.indices.filter { first[it] })
        val second = SpeakerDiarizer.embeddingFrames(labels, frames, 1)!!
        assertEquals((15 until 30).toList(), second.indices.filter { second[it] })
        assertNull(SpeakerDiarizer.embeddingFrames(labels, frames, 2))
    }

    @Test
    fun windowsAreJoinedIntoOneTimeline() {
        val frames = 10
        val shift = SpeakerDiarizer.FRAME_SHIFT
        val w0 = ByteArray(frames * 3).also { for (f in 0 until frames) it[f * 3] = 1 }
        val w1 = ByteArray(frames * 3).also { for (f in 0 until frames) it[f * 3 + 1] = 1 }
        val starts = intArrayOf(0, shift * 5)

        // The same person in both windows.
        val same = SpeakerDiarizer.aggregate(starts, listOf(w0, w1), frames, mapOf(0 to 0, 4 to 0), 1, shift * 15)
        assertEquals(1, same.speakers)
        assertEquals(List(15) { 1 } + 0, (0 until same.frames).map { same.at(it) })

        // Two people: the overlap goes to one of them, each keeps their own frames.
        val two = SpeakerDiarizer.aggregate(starts, listOf(w0, w1), frames, mapOf(0 to 0, 4 to 1), 2, shift * 15)
        assertEquals(2, two.speakers)
        assertEquals(1, two.at(2))
        assertEquals(2, two.at(12))
        assertEquals(0, two.at(15))
    }

    @Test
    fun shortTurnsJoinTheirLongerNeighbour() {
        val who = listOf(0, 0, 0, null, 0, 1, 0, 0, 0) + List(8) { 1 }
        assertEquals(listOf(0 to 9, 1 to 8), SpeakerTurns.runs(who, List(who.size) { 100L }, 700))
        assertEquals(listOf(1 to 4), SpeakerTurns.runs(listOf(null, null, 1, 1), List(4) { 100L }, 700))
        assertEquals(listOf(null to 3), SpeakerTurns.runs(listOf(null, null, null), List(3) { 100L }, 700))
        assertEquals(emptyList<Pair<Int?, Int>>(), SpeakerTurns.runs(emptyList(), emptyList(), 700))
    }

    @Test
    fun aSegmentOfTwoPeopleIsCutInTheQuietBetweenThem() {
        val voice = FloatArray(16 * 4000) { i ->
            val ms = i / 16
            if (ms in 1950 until 2050) 0f else (0.3 * sin(2 * PI * 200 * i / 16000)).toFloat()
        }
        val d = twoPeople(4000, 2000)
        val out = SpeakerTurns.splitRanges(listOf(SpeechDetector.Range(0, 4000)), d, voice)
        assertEquals(listOf(0, 1), out.map { it.second })
        val cut = out[0].first.endMs
        assertTrue("cut at $cut", cut in 1950L..2050L)
        assertEquals(cut, out[1].first.startMs)
        assertEquals(4000L, out[1].first.endMs)

        // One person: the segment stays whole.
        val one = SpeakerTurns.splitRanges(listOf(SpeechDetector.Range(100, 1500)), d, voice)
        assertEquals(listOf(SpeechDetector.Range(100, 1500) to 0), one)
    }

    @Test
    fun aSentenceOfTwoPeopleIsCutBetweenTheirWords() {
        val d = twoPeople(4000, 2000)
        val words = listOf(
            Word(0, 400, " Hello"), Word(400, 800, " there"), Word(800, 1200, " my friend."),
            Word(2100, 2600, " Yes"), Word(2600, 3200, " indeed."),
        )
        val sentence = SpeechSegment(0, 3200, "Hello there my friend. Yes indeed.", words)
        // The sentences' timeline starts 10 s before the diarized audio.
        val shifted = sentence.copy(startMs = 10_000, endMs = 13_200, words = words.map { it.shifted(10_000) })
        val out = SpeakerTurns.splitSentences(listOf(shifted), d, offsetMs = 10_000)
        assertEquals(listOf("Hello there my friend.", "Yes indeed."), out.map { it.first.text })
        assertEquals(listOf(0, 1), out.map { it.second })
        assertEquals(12_100L, out[1].first.startMs)

        // A sentence without word times goes by its whole span.
        val plain = SpeakerTurns.splitSentences(listOf(SpeechSegment(10_000, 11_500, "Hi")), d, 10_000)
        assertEquals(0, plain.single().second)
    }

    @Test
    fun geminiIsToldWhoSpeaksAndCannotOverruleIt() {
        val x = FloatArray(16 * 2000)
        val ranges = listOf(SpeechDetector.Range(100, 900), SpeechDetector.Range(1100, 1900))
        val req = GeminiDubber.listenRequest("m", SourceLanguage.ENGLISH, x, ranges, listOf(0, 1), listOf(5, 6), emptyList(), schema = true, people = listOf(1, 0))
        val input = req.getJSONArray("input")
        assertTrue(input.getJSONObject(0).getString("text").contains("P<số>"))
        assertTrue(input.getJSONObject(1).getString("text").startsWith("[S1 · P2]"))
        assertTrue(input.getJSONObject(3).getString("text").startsWith("[S2 · P1]"))

        val answer = """{"speakers":[{"id":"P1","gender":"female","voice":"bright"}],
            "lines":[{"index":1,"speaker":"A","source":"Hi","vi":"Chào","emotion":""},
                     {"index":2,"speaker":"A","source":"Yo","vi":"Ê","emotion":""}]}"""
        val heard = GeminiDubber.parseListening(answer, setOf(0, 1), listOf(1, 0))!!
        assertEquals(listOf("P2", "P1"), heard.lines.map { it.speaker })
        // Without fingerprints the model's guess stands.
        assertEquals(listOf("A", "A"), GeminiDubber.parseListening(answer, setOf(0, 1))!!.lines.map { it.speaker })
    }
}
