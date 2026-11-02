package com.cutviet.editor.media

import com.cutviet.editor.media.dubbing.Gender
import com.cutviet.editor.media.dubbing.RedubChoices
import com.cutviet.editor.media.dubbing.VoicePresets
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.DubInfo
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedubTest {
    private val a = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 20_000, width = 1920, height = 1080, trimStartMs = 1000, trimEndMs = 9000)
    private val b = Clip(uri = "content://b", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = 1920, height = 1080, speed = 2f)

    private fun dub(start: Long, name: String, info: DubInfo? = null) =
        AudioTrack(kind = AudioKind.DUB, uri = "file:///$start.wav", name = name, sourceDurationMs = 1500, startMs = start, dub = info)

    @Test
    fun dubInfoIsSavedWithTheProject() {
        val d = DubInfo("content://a", 3000, 4500, "hello", "xin chào", "ENGLISH", "Nữ 2", "Aoede", "FEMALE", "young woman", "excited", "vi-vn-x", 1.05f)
        assertEquals(d, ProjectStore.dubFrom(ProjectStore.dubJson(d)))
        val bare = DubInfo("content://a", 0, 10)
        assertEquals(bare, ProjectStore.dubFrom(ProjectStore.dubJson(bare)))
    }

    @Test
    fun linesFromOlderDubsFindTheirPlaceAndPerson() {
        val p = Project(name = "p", clips = listOf(a, b))
        val info = ProjectOps.dubOrigin(p, dub(2000, "Nữ 2: xin chào"))!!
        assertEquals("content://a", info.sourceUri)
        assertEquals(3000L, info.sourceStartMs)
        assertEquals(4500L, info.sourceEndMs)
        assertEquals("xin chào", info.text)
        assertEquals("Nữ 2", info.speaker)
        assertEquals("FEMALE", info.gender)
        // Clip b starts at 8000 on the timeline and plays twice as fast.
        val second = ProjectOps.dubOrigin(p, dub(9000, "chào"))!!
        assertEquals("content://b", second.sourceUri)
        assertEquals(2000L, second.sourceStartMs)
        assertNull(second.speaker)
        // The cast gives the second woman the second voice for women.
        val v = RedubChoices.voiceOf(info)
        assertEquals("Aoede", v.gemini)
        assertEquals(Gender.FEMALE, v.gender)
        assertEquals(1.05f, v.pitch, 1e-6f)
        assertNull(RedubChoices.voiceOf(second).gemini)
    }

    @Test
    fun voicesOfferTheLineOwnThenEveryNamedVoiceThenThePhone() {
        val charon = VoicePresets.byId("charon")
        val man = DubInfo("content://a", 2000, 3000, "hi", "chào", "ENGLISH", null, "Charon", "MALE", charon.description, "excited", pitch = 0.78f)
        val woman = DubInfo("content://a", 4000, 5000, "yes", "vâng", "ENGLISH", null, "Kore", "FEMALE", VoicePresets.DEFAULT.description, "calm")
        val t1 = dub(1000, "chào", man)
        val p = Project(name = "p", clips = listOf(a), audios = listOf(t1, dub(3000, "vâng", woman), dub(5000, "ừ", man.copy(text = "ừ"))))
        val v = RedubChoices.voices(p, t1)
        assertTrue(v.first().label.startsWith("Giữ giọng hiện tại"))
        assertTrue(v.first().label.contains("Charon"))
        assertEquals("Charon", v.first().voice.gemini)
        // Every other named voice once (Charon is the current one), then the phone for a man, a woman and a child.
        val gemini = v.drop(1).mapNotNull { it.voice.gemini }
        assertEquals(VoicePresets.ALL.size - 1, gemini.size)
        assertTrue(v.drop(1).none { VoicePresets.of(it.voice) == charon })
        assertEquals(3, v.count { it.voice.gemini == null })
        assertTrue(v.all { it.speaker == null })
        // Every choice keeps this line's tone; a named voice brings its own description.
        assertTrue(v.all { it.voice.emotion == "excited" })
        val leda = v.first { it.voice.gemini == "Leda" }
        assertEquals(VoicePresets.byId("leda").description, leda.voice.description)
        assertEquals(Gender.FEMALE, leda.voice.gender)

        // Lines in one voice share a key; another voice or the phone has another.
        assertEquals(RedubChoices.voiceKey(RedubChoices.voiceOf(man)), RedubChoices.voiceKey(RedubChoices.voiceOf(man.copy(text = "ừ", emotion = ""))))
        assertTrue(RedubChoices.voiceKey(RedubChoices.voiceOf(man)) != RedubChoices.voiceKey(RedubChoices.voiceOf(woman)))
        val phone = DubInfo("content://a", 0, 10, phoneVoice = "vi-vn-x")
        assertTrue(RedubChoices.voiceKey(RedubChoices.voiceOf(phone)) != RedubChoices.voiceKey(RedubChoices.voiceOf(woman)))

        val around = RedubChoices.around(p, p.audios[1])
        assertEquals("Trước: hi → chào\nSau: hi → ừ", around)
    }

    @Test
    fun olderLinesWithAPersonKeepTheirLabelWhenTheirVoiceIsKept() {
        val man = DubInfo("content://a", 2000, 3000, "hi", "chào", "ENGLISH", "Nam 1", "Charon", "MALE", "deep voice", "excited")
        val t1 = dub(1000, "Nam 1: chào", man)
        val p = Project(name = "p", clips = listOf(a), audios = listOf(t1))
        val v = RedubChoices.voices(p, t1)
        assertEquals("Nam 1", v.first().speaker)
        assertEquals("deep voice", v.first().voice.description)
        // It is Charon, so Charon is not offered a second time.
        assertTrue(v.drop(1).none { it.voice.gemini == "Charon" && it.voice.gender == Gender.MALE })
    }

    @Test
    fun roomTimelineAndReplacing() {
        val t1 = dub(1000, "x")
        val p = Project(name = "p", clips = listOf(a, b), audios = listOf(t1, dub(4000, "y")))
        assertEquals(2920L, ProjectOps.dubRoom(p, t1, 1000, 80, 2000))
        assertEquals(1000L, ProjectOps.dubRoom(p, t1, 1000, 80, 0))
        assertEquals(3000L, ProjectOps.dubRoom(p, p.audios[1], 1000, 80, 2000))
        assertEquals(2000L, ProjectOps.timelineOfSource(p, "content://a", 3000))
        assertEquals(9000L, ProjectOps.timelineOfSource(p, "content://b", 2000))
        assertNull(ProjectOps.timelineOfSource(p, "content://a", 9500))

        val info = DubInfo("content://a", 2000, 3000, text = "câu mới", speaker = "Nam 1")
        val out = ProjectOps.replaceDubLine(p, t1.id, "file:///new.wav", 2500, 1.2f, 0.9f, info)
        val r = out.audios.first { it.id == t1.id }
        assertEquals(1000L, r.startMs)
        assertEquals("Nam 1: câu mới", r.name)
        assertEquals(2500L, r.sourceEndMs)
        assertEquals(1.2f, r.speed, 0f)
        assertEquals(info, r.dub)
        assertEquals(p.audios[1], out.audios[1])
    }
}

class DubSplitTest {
    private val a = Clip(uri = "content://a", mime = "video/mp4", isImage = false, sourceDurationMs = 20_000, width = 1920, height = 1080)

    @Test
    fun splitsALineInTwoAtThePlayhead() {
        val info = DubInfo("content://a", 2000, 6000, "Hello there. How are you?", "Chào bạn. Bạn có khoẻ không?", speaker = "Nam 1", geminiVoice = "Charon")
        val t = AudioTrack(kind = AudioKind.DUB, uri = "file:///d.wav", name = "Nam 1: Chào bạn. Bạn có khoẻ không?", sourceDurationMs = 4000, startMs = 2000, dub = info)
        val p = Project(name = "p", clips = listOf(a), audios = listOf(t))
        assertNull(ProjectOps.splitDubLine(p, t.id, 2100))
        assertNull(ProjectOps.splitDubLine(p, t.id, 5900))
        val out = ProjectOps.splitDubLine(p, t.id, 3500)!!
        val (x, y) = out.audios
        assertEquals(t.id, x.id)
        assertEquals(2000L, x.startMs)
        assertEquals(1500L, x.sourceEndMs)
        assertEquals(3500L, y.startMs)
        assertEquals(1500L, y.sourceStartMs)
        assertEquals(4000L, y.sourceEndMs)
        assertEquals(4000L, x.durationMs + y.durationMs)
        // The text is cut at the sentence end, the video's time in the same place as the audio.
        assertEquals("Chào bạn.", x.dub!!.text)
        assertEquals("Bạn có khoẻ không?", y.dub!!.text)
        assertEquals("Hello there.", x.dub!!.sourceText)
        assertEquals(3500L, x.dub!!.sourceEndMs)
        assertEquals(3500L, y.dub!!.sourceStartMs)
        assertEquals("Charon", y.dub!!.geminiVoice)
        assertEquals("Nam 1: Bạn có khoẻ không?", y.name)
    }

    @Test
    fun splitTextPrefersSentenceEnds() {
        assertEquals("một hai ba," to "bốn năm sáu", ProjectOps.splitText("một hai ba, bốn năm sáu", 0.4))
        assertEquals("xin" to "chào", ProjectOps.splitText("xin chào", 0.9))
        assertEquals("" to "", ProjectOps.splitText("", 0.5))
    }
}
