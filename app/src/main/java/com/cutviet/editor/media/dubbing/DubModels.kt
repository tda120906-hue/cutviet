package com.cutviet.editor.media.dubbing

import java.io.File

/** Language spoken in the source video. Fixed by the user so Whisper never guesses wrong. */
enum class SourceLanguage(val code: String, val label: String, val initialPrompt: String?) {
    ENGLISH("en", "Tiếng Anh", null),
    CHINESE("zh", "Tiếng Trung", "以下是普通话的句子。"),
}

/** Which translation branch to use. */
enum class DubMode(val label: String) {
    AUTO("Tự động"),
    ONLINE_FIRST("Online ưu tiên"),
    OFFLINE("Offline"),
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
)

/**
 * Audio to listen to: [startMs]..[endMs] of [uri] (0..0 = all of it). Times found in it are moved
 * by [offsetMs] so they land on the clip's source timeline.
 */
data class AudioSource(val uri: String, val startMs: Long, val endMs: Long, val offsetMs: Long)

/** Speech to text with segment timestamps. */
interface SpeechToText {
    suspend fun transcribe(source: AudioSource, language: SourceLanguage, progress: (Float) -> Unit): List<SpeechSegment>
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

    override fun close() {}
}

/** Thrown when a pipeline step is not available on this phone (model missing, no network...). */
class StepUnavailableException(message: String) : Exception(message)
