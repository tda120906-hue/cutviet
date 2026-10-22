package com.cutviet.editor.media.dubbing

import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.DubInfo
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps

/** A voice to read a line dubbed again with; [speaker] is the label the track gets ("Nam 1"). */
data class VoiceChoice(val label: String, val speaker: String?, val voice: LineVoice)

/** What the "Lồng lại câu này" dialog offers for one DUB track. */
object RedubChoices {

    /**
     * The line's own voice first, then the other voices of the project's dub (to fix a line read
     * in the wrong person's voice), then a new man, woman and child. All keep the line's tone.
     */
    fun voices(p: Project, track: AudioTrack): List<VoiceChoice> {
        val dubs = p.audios.filter { it.kind == AudioKind.DUB }
        val known = dubs.mapNotNull { it.dub }
        val own = ProjectOps.dubOrigin(p, track)
        val current = own?.let { voiceOf(it, known) } ?: LineVoice(null, null)
        val out = ArrayList<VoiceChoice>()
        out += VoiceChoice("Giữ giọng cũ (${describe(own?.speaker, current)})", own?.speaker, current)
        fun key(v: LineVoice) = v.gemini ?: "phone ${v.gender} ${v.pitch}"
        val seen = hashSetOf(key(current))
        for (d in known.sortedBy { it.speaker.orEmpty() }) {
            val v = voiceOf(d, known)
            if (!seen.add(key(v))) continue
            out += VoiceChoice(describe(d.speaker, v), d.speaker, v.copy(emotion = current.emotion))
        }
        val used = known.mapNotNull { it.geminiVoice }.toSet() + listOfNotNull(current.gemini)
        for (g in Gender.entries) {
            val n = known.mapNotNull { it.speaker }.distinct().count { it.startsWith(g.label + " ") } + 1
            val pool = GeminiDubber.POOLS.getValue(g)
            val voice = pool.firstOrNull { it !in used } ?: pool.first()
            out += VoiceChoice("${g.label} mới ($voice)", "${g.label} $n", LineVoice(voice, g, emotion = current.emotion, pitch = DubbingPipeline.pitchFor(g)))
        }
        return out
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

    private fun describe(speaker: String?, v: LineVoice): String =
        listOfNotNull(speaker ?: v.gender?.label, v.gemini ?: "giọng máy").joinToString(" · ")

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
