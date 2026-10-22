package com.cutviet.editor.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Turns recognised (and translated) sentences into subtitle lines that are comfortable to read:
 * at most two lines of text each, on screen long enough, never overlapping. Also maps clip source
 * time to the timeline, finds the line under the playhead and writes .srt files.
 */
object SubtitleCues {
    /** About two lines of 42 characters. */
    const val MAX_CHARS = 84
    /** A line longer than this on screen is split even when its text is short. */
    const val MAX_MS = 7_000L
    const val MIN_MS = 1_000L
    /** Kept free between two lines so the eye sees the change. */
    const val GAP_MS = 40L
    /** Reading speed: a line shown for less than this many ms per character is stretched, if there is room. */
    const val MS_PER_CHAR = 55L
    /** Furthest a line is stretched past the end of its speech. */
    const val MAX_HOLD_MS = 1_500L

    data class Cue(val startMs: Long, val endMs: Long, val text: String, val source: String = "")

    /** Splits one sentence into readable pieces; time is shared out by the length of each piece. */
    fun split(startMs: Long, endMs: Long, text: String, source: String = ""): List<Cue> {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return emptyList()
        val duration = (endMs - startMs).coerceAtLeast(1)
        val byChars = ceil(t.length / MAX_CHARS.toDouble()).toInt()
        val byTime = ceil(duration / MAX_MS.toDouble()).toInt()
        // Never cut into pieces of only a word or two.
        val n = max(byChars, byTime).coerceAtMost(max(1, t.length / 16))
        if (n <= 1) return listOf(Cue(startMs, endMs, t, source))
        val pieces = breakText(t, n)
        val total = pieces.sumOf { it.length }.toDouble()
        val out = ArrayList<Cue>()
        var at = startMs
        var chars = 0
        pieces.forEachIndexed { i, p ->
            chars += p.length
            val end = if (i == pieces.lastIndex) endMs else startMs + (duration * chars / total).roundToLong()
            out += Cue(at, end, p, if (i == 0) source else "")
            at = end
        }
        return out
    }

    /** Cuts [t] into about [n] pieces of similar length, at sentence ends, then commas, then spaces. */
    fun breakText(t: String, n: Int): List<String> {
        // Candidate cut positions (index of the first character of the next piece) and their cost.
        val candidates = ArrayList<Pair<Int, Int>>()
        for (i in 1 until t.length) {
            if (t[i] != ' ') continue
            val prev = t[i - 1]
            val cost = when (prev) {
                '.', '?', '!', '…', '。', '？', '！' -> 0
                ',', ';', ':', '，', '、', '；', '：', '-' -> 6
                else -> 14
            }
            candidates += (i + 1) to cost
        }
        if (candidates.isEmpty()) return listOf(t)
        val cuts = ArrayList<Int>()
        var last = 0
        for (k in 1 until n) {
            val ideal = last + (t.length - last) / (n - k + 1)
            val best = candidates
                .filter { it.first > last + 8 && it.first < t.length - 8 }
                .minByOrNull { abs(it.first - ideal) + it.second }
                ?: break
            cuts += best.first
            last = best.first
        }
        val out = ArrayList<String>()
        var from = 0
        for (c in cuts) {
            out += t.substring(from, c).trim()
            from = c
        }
        out += t.substring(from).trim()
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Sorts the lines, gives short ones time to be read (up to the next line), and keeps a small gap
     * between lines so none overlap. Lines are clipped to [endMs] of the video.
     */
    fun tidy(cues: List<Cue>, endMs: Long = Long.MAX_VALUE): List<Cue> {
        val sorted = cues.filter { it.text.isNotBlank() && it.startMs < endMs }.sortedBy { it.startMs }
        val out = ArrayList<Cue>(sorted.size)
        sorted.forEachIndexed { i, c ->
            val next = sorted.getOrNull(i + 1)?.startMs ?: endMs
            val limit = if (i < sorted.lastIndex) next - GAP_MS else endMs
            val wanted = max(MIN_MS, c.text.length * MS_PER_CHAR)
            val stretched = max(c.endMs, minOf(c.startMs + wanted, c.endMs + MAX_HOLD_MS))
            val end = minOf(stretched, limit, endMs)
            // A line squeezed by the next one keeps at least a short moment on screen.
            out += c.copy(endMs = max(end, c.startMs + minOf(300, (next - c.startMs).coerceAtLeast(1))))
        }
        // Lines that started at the same moment would still overlap: push the later one on.
        for (i in 1 until out.size) {
            if (out[i].startMs < out[i - 1].endMs) {
                val s = out[i - 1].endMs + GAP_MS
                out[i] = out[i].copy(startMs = s, endMs = max(out[i].endMs, s + 300))
            }
        }
        return out
    }

    /** Where [sourceMs] of a clip's source lands on the timeline, for a clip starting at [clipStartMs]. */
    fun toTimeline(sourceMs: Long, clipStartMs: Long, trimStartMs: Long, speed: Float): Long =
        clipStartMs + ((sourceMs - trimStartMs) / speed.toDouble()).roundToLong()

    /** The line shown at [timeMs], or null. [subs] must be sorted (as [Project.subtitles] is). */
    fun at(subs: List<Subtitle>, timeMs: Long): Subtitle? {
        var lo = 0
        var hi = subs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = subs[mid]
            when {
                timeMs < s.startMs -> hi = mid - 1
                timeMs >= s.endMs -> lo = mid + 1
                else -> return s
            }
        }
        return null
    }

    fun srt(subs: List<Subtitle>): String = buildString {
        subs.filter { it.text.isNotBlank() }.forEachIndexed { i, s ->
            append(i + 1).append('\n')
            append(srtTime(s.startMs)).append(" --> ").append(srtTime(s.endMs)).append('\n')
            append(s.text.trim()).append("\n\n")
        }
    }

    fun srtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    /** Things Whisper is known to make up from silence or music in Vietnamese videos. */
    private val HALLUCINATIONS = listOf(
        Regex("ghiền mì gõ", RegexOption.IGNORE_CASE),
        Regex("subscribe cho kênh", RegexOption.IGNORE_CASE),
        Regex("đăng ký kênh .*để không bỏ lỡ", RegexOption.IGNORE_CASE),
        Regex("^\\s*\\[?(music|âm nhạc|nhạc)\\]?\\s*$", RegexOption.IGNORE_CASE),
    )

    fun isHallucination(text: String): Boolean = HALLUCINATIONS.any { it.containsMatchIn(text) }
}
