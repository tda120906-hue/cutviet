package com.cutviet.editor.media.dubbing

/**
 * A Gemini voice offered by name: the voice every line of a dub is read in at first, and the voices
 * a single line can be switched to afterwards. [description] is how the TTS is told who speaks; it
 * stays the same for every line in that voice, so the voice does not drift from line to line.
 */
data class VoicePreset(val id: String, val voice: String, val gender: Gender, val label: String, val description: String) {
    fun lineVoice(emotion: String = "") = LineVoice(voice, gender, description, emotion, DubbingPipeline.pitchFor(gender))

    /** Whether [v] is this voice (a line saved with it). */
    fun matches(v: LineVoice) = v.gemini == voice && (v.gender == null || v.gender == gender)
}

object VoicePresets {
    /** Women, then men, then children; Google's prebuilt voices that read Vietnamese well. */
    val ALL: List<VoicePreset> = listOf(
        VoicePreset("kore", "Kore", Gender.FEMALE, "Nữ · rõ ràng, chắc giọng (Kore)", "an adult woman with a firm, clear voice"),
        VoicePreset("aoede", "Aoede", Gender.FEMALE, "Nữ · tươi tắn (Aoede)", "a young adult woman with a light, breezy voice"),
        VoicePreset("leda", "Leda", Gender.FEMALE, "Nữ · trẻ trung (Leda)", "a young woman in her twenties with a youthful voice"),
        VoicePreset("achernar", "Achernar", Gender.FEMALE, "Nữ · nhẹ nhàng (Achernar)", "an adult woman with a soft, gentle voice"),
        VoicePreset("sulafat", "Sulafat", Gender.FEMALE, "Nữ · ấm áp (Sulafat)", "an adult woman with a warm voice"),
        VoicePreset("gacrux", "Gacrux", Gender.FEMALE, "Nữ · lớn tuổi (Gacrux)", "a mature woman in her fifties"),
        VoicePreset("charon", "Charon", Gender.MALE, "Nam · trầm, rõ (Charon)", "an adult man with a deep, clear voice"),
        VoicePreset("puck", "Puck", Gender.MALE, "Nam · trẻ, vui (Puck)", "a young man in his twenties with an upbeat voice"),
        VoicePreset("orus", "Orus", Gender.MALE, "Nam · chắc giọng (Orus)", "an adult man with a firm voice"),
        VoicePreset("fenrir", "Fenrir", Gender.MALE, "Nam · sôi nổi (Fenrir)", "an adult man with an excited, lively voice"),
        VoicePreset("enceladus", "Enceladus", Gender.MALE, "Nam · nhẹ nhàng (Enceladus)", "an adult man with a soft, breathy voice"),
        VoicePreset("algenib", "Algenib", Gender.MALE, "Nam · lớn tuổi (Algenib)", "an older man in his sixties with a gravelly voice"),
        VoicePreset("laomedeia_child", "Laomedeia", Gender.CHILD, "Trẻ em · bé gái (Laomedeia)", "a girl about eight years old, a child"),
        VoicePreset("puck_child", "Puck", Gender.CHILD, "Trẻ em · bé trai (Puck)", "a boy about eight years old, a child"),
    )

    val DEFAULT: VoicePreset = ALL.first()

    fun byId(id: String?): VoicePreset = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** The preset a saved line's voice is, or null (a voice from an older dub, or the phone's). */
    fun of(v: LineVoice): VoicePreset? =
        ALL.firstOrNull { it.matches(v) && it.description == v.description } ?: ALL.firstOrNull { it.matches(v) }
}
