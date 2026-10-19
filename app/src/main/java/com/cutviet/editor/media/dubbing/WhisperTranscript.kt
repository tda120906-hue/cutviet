package com.cutviet.editor.media.dubbing

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.max

/** Turns whisper.cpp's token output into words and then into sentences sized for dubbing. */
object WhisperTranscript {
    /** One whisper token: raw UTF-8 bytes (possibly part of a character) and its time in ms. */
    class Token(val bytes: ByteArray, val t0: Long, val t1: Long, val p: Float)

    /** Gap in speech that always ends a sentence. */
    const val PAUSE_MS = 800L
    /** A sentence longer than this is cut at the next comma. */
    const val LONG_SENTENCE_MS = 9_000L
    /** And longer than this is cut at the next word. */
    const val MAX_SENTENCE_MS = 15_000L

    private val SENTENCE_END = setOf('.', '?', '!', '…', '。', '？', '！')
    private val CLAUSE_END = setOf(',', ';', ':', '，', '、', '；', '：')

    /** Parses the JNI JSON into tokens (all segments in order). */
    fun parseTokens(json: String): List<Token> {
        val segments = JSONObject(json).getJSONArray("segments")
        val out = ArrayList<Token>()
        for (i in 0 until segments.length()) {
            val words = segments.getJSONObject(i).getJSONArray("words")
            for (j in 0 until words.length()) {
                val w = words.getJSONObject(j)
                out += Token(hex(w.getString("text")), w.getLong("t0"), w.getLong("t1"), w.optDouble("p", 1.0).toFloat())
            }
        }
        return out
    }

    /**
     * Joins tokens into words. A word starts at a token beginning with a space; a complete CJK
     * character is a word on its own. Bytes are only decoded once they form whole characters.
     */
    fun words(tokens: List<Token>): List<Word> {
        val out = ArrayList<Word>()
        var buf = ByteArray(0)
        var t0 = 0L
        var t1 = 0L
        fun flush() {
            if (buf.isEmpty()) return
            val text = decode(buf) ?: String(buf, Charsets.UTF_8)
            if (text.isNotBlank() && !text.trim().startsWith("[")) out += Word(t0, max(t0, t1), text)
            buf = ByteArray(0)
        }
        for (tok in tokens) {
            if (tok.bytes.isEmpty()) continue
            val startsWord = tok.bytes[0] == ' '.code.toByte()
            val current = if (buf.isEmpty()) null else decode(buf)
            if (startsWord || (current != null && current.any(::isCjk))) flush()
            if (buf.isEmpty()) t0 = tok.t0
            buf += tok.bytes
            t1 = tok.t1
        }
        flush()
        return out
    }

    /** Groups words into sentences: at sentence punctuation, long pauses, or when too long. */
    fun sentences(words: List<Word>, offsetMs: Long = 0): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        var cur = ArrayList<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            val text = joinWords(cur)
            if (text.isNotBlank()) {
                out += SpeechSegment(cur.first().startMs + offsetMs, cur.last().endMs + offsetMs, text, cur.map { it.shifted(offsetMs) })
            }
            cur = ArrayList()
        }
        words.forEachIndexed { i, w ->
            if (cur.isNotEmpty() && w.startMs - cur.last().endMs > PAUSE_MS) flush()
            cur += w
            val last = w.text.trimEnd().lastOrNull()
            val length = w.endMs - cur.first().startMs
            val endsHere = last in SENTENCE_END ||
                (length > LONG_SENTENCE_MS && last in CLAUSE_END) ||
                length > MAX_SENTENCE_MS
            if (endsHere || i == words.lastIndex) flush()
        }
        return out
    }

    /** Joins words back into text: Latin words carry their own leading space, CJK words none. */
    fun joinWords(words: List<Word>): String = words.joinToString("") { it.text }.trim().replace(Regex("\\s+"), " ")

    fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA ||
            it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
    } || c in '　'..'〿' || c in '＀'..'￯'

    /** Strict UTF-8 decode; null while the bytes end in the middle of a character. */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
    }
}
