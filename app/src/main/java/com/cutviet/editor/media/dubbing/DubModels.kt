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
)

/** Speech to text with segment timestamps, reading the audio of a video or audio file. */
interface SpeechToText {
    suspend fun transcribe(sourceUri: String, language: SourceLanguage, progress: (Float) -> Unit): List<SpeechSegment>
}

/** Text translation into Vietnamese, given the neighbouring sentences as context. */
interface TextTranslator {
    suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, progress: (Float) -> Unit): List<String>
}

/** Thrown when a pipeline step is not available on this phone (model missing, no network...). */
class StepUnavailableException(message: String) : Exception(message)
