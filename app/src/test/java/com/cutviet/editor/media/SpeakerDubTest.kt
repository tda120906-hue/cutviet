package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.GeminiApi
import com.cutviet.editor.media.dubbing.GeminiDubber
import com.cutviet.editor.media.dubbing.Gender
import com.cutviet.editor.media.dubbing.Loudness
import com.cutviet.editor.media.dubbing.PitchTracker
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeakerDubTest {
    /** A voiced sound at [f0] Hz: five harmonics, falling off like a real voice. */
    private fun voice(f0: Double, ms: Int, rate: Int = 16_000): FloatArray = FloatArray(ms * rate / 1000) { i ->
        val t = i.toDouble() / rate
        val amps = doubleArrayOf(1.0, 0.6, 0.4, 0.3, 0.2)
        (0.12 * amps.indices.sumOf { k -> amps[k] * sin(2 * PI * f0 * (k + 1) * t) }).toFloat()
    }

    @Test
    fun pitchTellsMenWomenAndChildrenApart() {
        for ((f0, gender) in listOf(120.0 to Gender.MALE, 220.0 to Gender.FEMALE, 350.0 to Gender.CHILD)) {
            val x = voice(f0, 1000)
            val got = PitchTracker.medianF0(x, 16_000)
            assertNotNull("no pitch for $f0 Hz", got)
            assertTrue("$f0 Hz measured as $got", abs(got!! - f0) < f0 * 0.05)
            assertEquals(gender, PitchTracker.gender(got))
        }
        // Silence has no pitch.
        assertNull(PitchTracker.medianF0(FloatArray(16_000), 16_000))
    }

    @Test
    fun everyPersonGetsAVoiceOfTheirOwnAndOfTheirGender() {
        val speakers = listOf(
            GeminiDubber.Speaker("A", Gender.MALE, "a man in his 30s"),
            GeminiDubber.Speaker("B", Gender.FEMALE, "a young woman, bright voice"),
            GeminiDubber.Speaker("C", Gender.MALE, "an elderly man, gravelly"),
            GeminiDubber.Speaker("D", Gender.CHILD, "a small boy"),
        )
        val roles = GeminiDubber.cast(speakers, distinguish = true, main = "A")
        assertEquals(listOf("Nam 1", "Nữ 1", "Nam 2", "Trẻ em 1"), speakers.map { roles.getValue(it.id).label })
        assertEquals(4, roles.values.map { it.voice }.toSet().size)
        assertTrue(roles.getValue("A").voice in GeminiDubber.POOLS.getValue(Gender.MALE))
        assertTrue(roles.getValue("B").voice in GeminiDubber.POOLS.getValue(Gender.FEMALE))
        assertEquals("Algenib", roles.getValue("C").voice)
        assertEquals("Leda", roles.getValue("B").voice)

        // Off: one shared voice, the main speaker's, and no labels.
        val one = GeminiDubber.cast(speakers, distinguish = false, main = "B")
        assertEquals(1, one.values.toSet().size)
        assertEquals(Gender.FEMALE, one.getValue("A").gender)
        assertNull(one.getValue("A").label)
    }

    @Test
    fun peopleAreVoicedOneAfterAnotherMostLinesFirst() {
        val a = GeminiDubber.Role("Nam 1", "Charon", Gender.MALE, "")
        val b = GeminiDubber.Role("Nữ 1", "Kore", Gender.FEMALE, "")
        fun h(i: Int, who: String, emotion: String = "") = GeminiDubber.Heard(i, who, "src", "câu số $i", emotion)
        val lines = listOf(h(0, "B"), h(1, "A"), h(2, "A"), h(3, "A", "laughing"), h(4, "B"), h(5, "A"))
        val order = GeminiDubber.voicingOrder(lines) { if (it.speaker == "A") a else b }
        // Every line once, each person's lines together and in time order, whatever their tone.
        assertEquals(listOf(listOf(1, 2, 3, 5), listOf(0, 4)), order.map { g -> g.map { it.index } })
    }

    @Test
    fun everyLineOfAPersonIsDescribedTheSameWayAndToneIsATag() {
        val role = GeminiDubber.Role("Nam 1", "Charon", Gender.MALE, "a man in his 40s, deep and calm")
        val plain = GeminiDubber.ttsPrompt(role, "", "Chào anh.")
        val laughing = GeminiDubber.ttsPrompt(role, "laughing", "Thật à?")
        // Same directions for both lines; only the transcript differs.
        assertEquals(plain.substringBefore("#### TRANSCRIPT"), laughing.substringBefore("#### TRANSCRIPT"))
        assertTrue(plain.contains("a man in his 40s, deep and calm"))
        assertTrue(plain.endsWith("#### TRANSCRIPT\nChào anh."))
        assertTrue(laughing.endsWith("#### TRANSCRIPT\n[laughs] Thật à?"))
        assertEquals("[annoyed]", GeminiDubber.audioTag("Annoyed"))
        assertNull(GeminiDubber.audioTag("neutral"))
        assertNull(GeminiDubber.audioTag("tức giận"))
        val child = GeminiDubber.Role("Trẻ em 1", "Leda", Gender.CHILD, "")
        assertTrue(GeminiDubber.persona(child).contains("child"))
    }

    @Test
    fun requestsHaveTheShapeTheInteractionsApiDocuments() {
        val tts = GeminiDubber.ttsRequest("gemini-3.8-flash-tts", "Xin chào", "Kore")
        assertEquals("gemini-3.8-flash-tts", tts.getString("model"))
        val turn = tts.getJSONArray("input").getJSONObject(0)
        assertEquals("user_input", turn.getString("type"))
        val content = turn.getJSONArray("content").getJSONObject(0)
        assertEquals("Xin chào", content.getString("text"))
        assertFalse(content.has("annotations"))
        assertEquals("audio", tts.getJSONObject("response_format").getString("type"))
        assertEquals("Kore", tts.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
    }

    @Test
    fun rawPcmFromGeminiBecomesAWav() {
        val pcm = java.nio.ByteBuffer.allocate(4800).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(2400) { pcm.putShort((8000 * sin(2 * PI * 300 * it / 24_000)).toInt().toShort()) }
        val f = File.createTempFile("gemini", ".wav")
        try {
            GeminiDubber.writeAudio(pcm.array(), "audio/L16;codec=pcm;rate=24000", f)
            val mono = Loudness.readWav(f)!!
            assertEquals(24_000, mono.rate)
            assertEquals(2400, mono.samples.size)
            // A WAV is kept as it is.
            val again = File.createTempFile("gemini", ".wav")
            GeminiDubber.writeAudio(f.readBytes(), "audio/wav", again)
            assertTrue(f.readBytes().contentEquals(again.readBytes()))
            again.delete()
        } finally {
            f.delete()
        }
    }

    @Test
    fun interactionOutputAndQuotaErrorsAreRead() {
        val audio = byteArrayOf(1, 2, 3, 4).toByteString().base64()
        val interaction = JSONObject(
            """{"status":"completed","steps":[{"type":"user_input","content":[{"type":"text","text":"q"}]},
               {"type":"model_output","content":[{"type":"text","text":"{\"a\":1}"},{"type":"audio","mime_type":"audio/wav","data":"$audio"}]}]}""",
        )
        assertEquals("{\"a\":1}", GeminiApi.outputText(interaction))
        val (bytes, mime) = GeminiApi.outputAudio(interaction)!!
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals("audio/wav", mime)
        assertEquals("hi", GeminiApi.outputText(JSONObject("""{"output_text":"hi"}""")))

        val daily = GeminiApi.quotaOf(
            """{"error":{"code":429,"message":"Quota exceeded","details":[
               {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
               {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"41s"}]}}""",
        )
        assertTrue(daily.daily)
        assertEquals(41_000L, daily.retryMs)
        val minute = GeminiApi.quotaOf(
            """{"error":{"code":429,"message":"Resource exhausted","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7.5s"}]}}""",
        )
        assertFalse(minute.daily)
        assertEquals(7_500L, minute.retryMs)
    }

    @Test
    fun theSpeakerIsShownOnTheTrackName() {
        val c = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 1920, height = 1080)
        val p = Project(name = "p", clips = listOf(c))
        val lines = listOf(
            ProjectOps.DubLine(1000, "file:///a.wav", 1200, "Chào chị", speaker = "Nam 1"),
            ProjectOps.DubLine(3000, "file:///b.wav", 900, "Chào anh"),
        )
        val names = ProjectOps.applyDub(p, 0, lines, 0f).audios.filter { it.kind == AudioKind.DUB }.map { it.name }
        assertEquals(listOf("Nam 1: Chào chị", "Chào anh"), names)
        assertNotEquals(names[0], names[1])
    }
}
