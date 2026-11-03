package com.cutviet.editor.media.dubbing

import java.io.File

/**
 * Language spoken in the source video. The user picks English, Chinese or "Tự phát hiện" ([CHOICES]);
 * with [AUTO] Gemini or Whisper finds the language by listening. The other entries are what Whisper
 * can report, so its sentences are recognised and translated as that language.
 */
enum class SourceLanguage(val code: String, val label: String, val initialPrompt: String?) {
    ENGLISH("en", "Tiếng Anh", null),
    CHINESE("zh", "Tiếng Trung", "以下是普通话的句子。"),
    /** Found by listening: any language, also Vietnamese (then written down as said, not translated). */
    AUTO("auto", "Tự phát hiện", null),
    /** Vietnamese speech: written down as said. */
    VIETNAMESE("vi", "Tiếng Việt", "Xin chào các bạn. Hôm nay, chúng ta sẽ nói về một chủ đề rất thú vị."),
    JAPANESE("ja", "Tiếng Nhật", null),
    KOREAN("ko", "Tiếng Hàn", null),
    THAI("th", "Tiếng Thái", null),
    CANTONESE("yue", "Tiếng Quảng Đông", null),
    INDONESIAN("id", "Tiếng Indonesia", null),
    MALAY("ms", "Tiếng Mã Lai", null),
    FILIPINO("tl", "Tiếng Philippines", null),
    KHMER("km", "Tiếng Khmer", null),
    LAO("lo", "Tiếng Lào", null),
    HINDI("hi", "Tiếng Hindi", null),
    FRENCH("fr", "Tiếng Pháp", null),
    GERMAN("de", "Tiếng Đức", null),
    SPANISH("es", "Tiếng Tây Ban Nha", null),
    PORTUGUESE("pt", "Tiếng Bồ Đào Nha", null),
    ITALIAN("it", "Tiếng Ý", null),
    RUSSIAN("ru", "Tiếng Nga", null),
    ARABIC("ar", "Tiếng Ả Rập", null),
    TURKISH("tr", "Tiếng Thổ Nhĩ Kỳ", null),
    ;

    /** "tiếng anh" as the prompts write it; null for [AUTO], whose language is not known yet. */
    val promptName: String? get() = if (this == AUTO) null else label.lowercase()

    companion object {
        /** What the user picks from, in the dub, the line editor and the subtitles. */
        val CHOICES: List<SourceLanguage> = listOf(ENGLISH, CHINESE, AUTO)

        /** The language Whisper reported as [code] ("ja"), or null for one not listed here. */
        fun ofCode(code: String?): SourceLanguage? = entries.firstOrNull { it != AUTO && it.code == code }

        /** [chosen], or with [AUTO] what was heard ([heardCode]) when it is a listed language. */
        fun resolve(chosen: SourceLanguage, heardCode: String?): SourceLanguage =
            if (chosen != AUTO) chosen else ofCode(heardCode) ?: AUTO

        /** One of [CHOICES] for a saved language name: English and Chinese stay, anything else is found again. */
        fun choiceOf(name: String?): SourceLanguage =
            entries.firstOrNull { it.name == name }?.takeIf { it in CHOICES } ?: AUTO
    }
}

/** Which translation branch to use. */
enum class DubMode(val label: String) {
    AUTO("Tự động"),
    ONLINE_FIRST("Online ưu tiên"),
    OFFLINE("Offline"),
}

/** Online dubbing style. */
enum class OnlineEngine(val label: String) {
    /** Gemini listens (who speaks, man or woman, emotion), translates, and voices each person. */
    NATURAL("Tự nhiên"),
    /** Gemini Live Translate: real-time interpreter, one voice, no direction. */
    LIVE("Phiên dịch trực tiếp"),
}

/** Who speaks, as far as choosing a dubbing voice goes. */
enum class Gender(val label: String) {
    MALE("Nam"),
    FEMALE("Nữ"),
    CHILD("Trẻ em"),
}

/** One recognised word with its time in the source, in ms. */
data class Word(val startMs: Long, val endMs: Long, val text: String) {
    fun shifted(ms: Long) = copy(startMs = startMs + ms, endMs = endMs + ms)
}

/** A sentence of speech in the source, with its place on the source timeline and its words. */
data class SpeechSegment(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList()) {
    val slotMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** A translated segment turned into speech, ready to be placed on the timeline. */
data class DubbedSegment(
    val startMs: Long,
    val slotMs: Long,
    val sourceText: String,
    val text: String,
    val file: File,
    val durationMs: Long,
    val rate: Float,
    /** Played this much faster on the timeline (online dubs that run a little long). */
    val playbackSpeed: Float = 1f,
    /** Who says it, as shown on the timeline ("Nam 1", "Nữ 2"), when speakers were told apart. */
    val speaker: String? = null,
    /** The voice that read it, kept so the line can be dubbed again in the same voice. */
    val voice: LineVoice? = null,
)

/**
 * The voice of one dubbed line: a Gemini voice ([gemini], with the person's [description] and the
 * line's [emotion]) or, when [gemini] is null, the phone's voice at [pitch].
 */
data class LineVoice(
    val gemini: String?,
    val gender: Gender?,
    val description: String = "",
    val emotion: String = "",
    val pitch: Float = 1f,
)

/**
 * One line from an online dubber, times relative to the audio it was given. [file] is null when
 * the line was translated but could not be voiced online (quota), so the phone's voice reads it.
 */
data class OnlineLine(
    val startMs: Long,
    val endMs: Long,
    val roomMs: Long,
    val source: String,
    val text: String,
    val speaker: String?,
    val gender: Gender?,
    val file: File?,
    val durationMs: Long,
    val speed: Float,
    /** Gemini voice that read [file]; null when the phone reads it. */
    val voice: String? = null,
    val description: String = "",
    val emotion: String = "",
)

/** Online dubbing of the speech [ranges] (ms) of [voice], 16 kHz mono. */
interface OnlineDubber {
    suspend fun dub(voice: FloatArray, ranges: List<SpeechDetector.Range>, language: SourceLanguage, progress: (Float) -> Unit): List<OnlineLine>

    /** What the user should know about a dub that went through, such as lines the phone had to read. */
    val notice: String? get() = null
}

/**
 * Audio to listen to: [startMs]..[endMs] of [uri] (0..0 = all of it). Times found in it are moved
 * by [offsetMs] so they land on the clip's source timeline.
 */
data class AudioSource(val uri: String, val startMs: Long, val endMs: Long, val offsetMs: Long)

/** Speech to text with segment timestamps. */
interface SpeechToText {
    /** [language] [SourceLanguage.AUTO]: the language is found first, and told by [heardLanguage]. */
    suspend fun transcribe(source: AudioSource, language: SourceLanguage, progress: (Float) -> Unit): List<SpeechSegment>

    /** Code of the language the last [transcribe] heard ("ja"), or null when it was not detected. */
    val heardLanguage: String? get() = null
}

/**
 * Text translation into Vietnamese, given the neighbouring sentences as context. The engine may
 * stay loaded after [translate] so [shorten] is quick; [close] frees it.
 */
interface TextTranslator : AutoCloseable {
    /** [budgets]: most syllables each line may have to fit its time. */
    suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, budgets: List<Int>, progress: (Float) -> Unit): List<String>

    /** A shorter version of a translated line, at most [maxSyllables], or null. */
    suspend fun shorten(source: String, text: String, language: SourceLanguage, maxSyllables: Int): String? = null

    /**
     * [text] with its English words respelled for a Vietnamese voice ("Facebook" → "phây-búc"),
     * or null when the engine is not loaded ([SpeakableText] then uses its word list).
     */
    suspend fun speakable(text: String): String? = null

    override fun close() {}
}

/** Thrown when a pipeline step is not available on this phone (model missing, no network...). */
class StepUnavailableException(message: String) : Exception(message)
