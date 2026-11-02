package com.cutviet.editor.media.dubbing

import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.DubInfo
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps

/**
 * A voice to read a line dubbed again with. [speaker] is the label the track keeps (only lines of
 * older dubs have one, "Nam 1"); [group] heads the list the choice is shown in.
 */
data class VoiceChoice(val label: String, val speaker: String?, val voice: LineVoice, val group: String = "")

/** What the line editor offers for one DUB track. */
object RedubChoices {
    const val GROUP_CURRENT = "Đang dùng"
    const val GROUP_PHONE = "Giọng của máy (không cần mạng)"

    /**
     * The line's own voice first, then every Gemini voice by name (women, men, children), then the
     * phone's voice for a man, a woman and a child. All keep the line's tone.
     */
    fun voices(p: Project, track: AudioTrack): List<VoiceChoice> {
        val known = p.audios.filter { it.kind == AudioKind.DUB }.mapNotNull { it.dub }
        val own = ProjectOps.dubOrigin(p, track)
        val current = own?.let { voiceOf(it, known) } ?: LineVoice(null, null)
        val out = ArrayList<VoiceChoice>()
        out += VoiceChoice("Giữ giọng hiện tại: ${describe(current)}", own?.speaker, current, GROUP_CURRENT)
        val here = VoicePresets.of(current)
        for (preset in VoicePresets.ALL) {
            if (preset == here) continue
            out += VoiceChoice(preset.label, null, preset.lineVoice(current.emotion), "Giọng Gemini · ${preset.gender.label}")
        }
        for (g in Gender.entries) {
            val v = LineVoice(null, g, emotion = current.emotion, pitch = DubbingPipeline.pitchFor(g))
            if (current.gemini == null && current.gender == g) continue
            out += VoiceChoice("Giọng máy · ${g.label}", null, v, GROUP_PHONE)
        }
        return out
    }

    /** Lines read in the same voice share this key (a Gemini voice, or the phone's at one pitch). */
    fun voiceKey(v: LineVoice): String = v.gemini?.let { "$it/${v.gender ?: ""}" } ?: "phone/${v.gender ?: ""}/${v.pitch}"

    /** "Nữ · rõ ràng, chắc giọng (Kore)", "Charon", "giọng máy · Nam". */
    fun describe(v: LineVoice): String {
        VoicePresets.of(v)?.let { return it.label }
        if (v.gemini != null) return listOfNotNull(v.gender?.label, v.gemini).joinToString(" · ")
        return listOfNotNull("giọng máy", v.gender?.label).joinToString(" · ")
    }

    /**
     * The voice of a saved line. Lines dubbed before voices were saved only have the speaker in
     * their name: the voice the cast gives that person ("Nữ 2" → the second voice for women), or
     * the voice of another line of the same person that has it.
     */
    fun voiceOf(d: DubInfo, known: List<DubInfo> = emptyList()): LineVoice {
        val gender = d.gender?.let { runCatching { Gender.valueOf(it) }.getOrNull() }
        if (d.geminiVoice != null || d.phoneVoice != null || d.description.isNotEmpty() || d.pitch != 1f) {
            return LineVoice(d.geminiVoice, gender, d.description, d.emotion, d.pitch)
        }
        known.firstOrNull { it.speaker != null && it.speaker == d.speaker && it.geminiVoice != null }?.let {
            return LineVoice(it.geminiVoice, gender, it.description, d.emotion, it.pitch)
        }
        if (gender == null) return LineVoice(null, null, emotion = d.emotion)
        val n = d.speaker?.substringAfterLast(' ')?.toIntOrNull() ?: 1
        val pool = GeminiDubber.POOLS.getValue(gender)
        return LineVoice(pool[(n - 1).coerceAtLeast(0) % pool.size], gender, emotion = d.emotion, pitch = DubbingPipeline.pitchFor(gender))
    }

    /** Up to [n] lines before and after [track], "what was said → translation", to translate it in context. */
    fun around(p: Project, track: AudioTrack, n: Int = 3): String {
        val dubs = p.audios.filter { it.kind == AudioKind.DUB }.sortedBy { it.startMs }
        val i = dubs.indexOfFirst { it.id == track.id }
        if (i < 0) return ""
        fun line(a: AudioTrack): String {
            val d = a.dub
            val text = d?.text?.ifBlank { null } ?: a.name.substringAfter(": ")
            val who = d?.speaker?.let { "$it: " }.orEmpty()
            return who + (d?.sourceText?.ifBlank { null }?.let { "$it → " }.orEmpty()) + text
        }
        return buildString {
            dubs.subList(maxOf(0, i - n), i).forEach { append("Trước: ").append(line(it)).append('\n') }
            dubs.subList(i + 1, minOf(dubs.size, i + 1 + n)).forEach { append("Sau: ").append(line(it)).append('\n') }
        }.trim()
    }
}
