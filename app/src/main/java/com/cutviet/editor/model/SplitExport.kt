package com.cutviet.editor.model

import kotlin.math.max
import kotlin.math.min

/** How a long project is cut into several exported videos. */
enum class SplitMode(val label: String) {
    /** N videos of the same length. */
    EVEN("Chia đều"),
    /** The user sets the length of each video. */
    CUSTOM("Tự chọn"),
    /** Videos of a fixed length; the last one gets what is left. */
    FIXED("Theo thời lượng"),
}

data class SplitPlan(
    val mode: SplitMode = SplitMode.EVEN,
    /** EVEN: number of videos. */
    val count: Int = 2,
    /** CUSTOM: length of each video, in order. */
    val customMs: List<Long> = emptyList(),
    /** FIXED: length of every video but the last. */
    val fixedMs: Long = 60 * 60_000L,
    /**
     * "Xuất phim": every video after the first starts with the last [overlapMs] of the one before
     * it (a short recap). 0 = videos follow each other exactly.
     */
    val overlapMs: Long = 0,
)

/** One video to export: timeline [startMs]..[endMs]; [index] counts from 0. */
data class SplitPart(val index: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

object SplitExport {
    /** A last piece shorter than this is rounding left-over; it is added to the video before it. */
    const val MIN_TAIL_MS = 1_000L
    /** A piece of a clip, overlay or sound shorter than this at a cut is left out. */
    const val MIN_PIECE_MS = 40L
    const val MAX_PARTS = 200

    /** Where each video starts and ends on the timeline, before the recap is added. */
    fun cuts(totalMs: Long, plan: SplitPlan): List<Pair<Long, Long>> {
        if (totalMs <= 0) return emptyList()
        val raw = when (plan.mode) {
            SplitMode.EVEN -> {
                val n = plan.count.coerceIn(1, MAX_PARTS)
                (0 until n).map { i -> totalMs * i / n to totalMs * (i + 1) / n }
            }
            SplitMode.FIXED -> {
                val len = max(MIN_TAIL_MS, plan.fixedMs)
                val list = mutableListOf<Pair<Long, Long>>()
                var t = 0L
                while (t < totalMs && list.size < MAX_PARTS) {
                    val end = if (list.size == MAX_PARTS - 1) totalMs else min(totalMs, t + len)
                    list += t to end
                    t = end
                }
                list
            }
            SplitMode.CUSTOM -> {
                val list = mutableListOf<Pair<Long, Long>>()
                var t = 0L
                for (len in plan.customMs.take(MAX_PARTS)) {
                    if (t >= totalMs) break
                    if (len <= 0) continue
                    val end = min(totalMs, t + len)
                    list += t to end
                    t = end
                }
                list
            }
        }.filter { it.second > it.first }
        // A crumb at the end (e.g. 6:00:00.4 split by the hour) joins the previous video.
        if (raw.size >= 2 && plan.mode != SplitMode.CUSTOM && raw.last().let { it.second - it.first } < MIN_TAIL_MS) {
            return raw.dropLast(2) + (raw[raw.size - 2].first to raw.last().second)
        }
        return raw
    }

    /** The videos to export, with the recap ("Xuất phim") prepended to every video after the first. */
    fun parts(totalMs: Long, plan: SplitPlan): List<SplitPart> =
        cuts(totalMs, plan).mapIndexed { i, (s, e) ->
            val start = if (i == 0 || plan.overlapMs <= 0) s else max(0L, s - plan.overlapMs)
            SplitPart(i, start, e)
        }

    /** CUSTOM: how much of the project the entered lengths leave out (negative = they ask for more than there is). */
    fun customLeftoverMs(totalMs: Long, customMs: List<Long>): Long = totalMs - customMs.filter { it > 0 }.sum()

    /**
     * The part of [p] between timeline [startMs] and [endMs], as a project of its own starting at 0:
     * clips, overlays, sounds and subtitles are cut at both ends and moved to the new start, so
     * exporting it gives exactly that stretch of the full video.
     */
    fun slice(p: Project, startMs: Long, endMs: Long): Project {
        val s = startMs.coerceAtLeast(0)
        val e = endMs.coerceAtMost(p.durationMs)
        if (e <= s) return p.copy(clips = emptyList(), overlays = emptyList(), audios = emptyList(), subtitles = emptyList(), texts = emptyList())

        val clips = mutableListOf<Clip>()
        var cs = 0L
        for (c in p.clips) {
            val ce = cs + c.durationMs
            val a = max(s, cs)
            val b = min(e, ce)
            if (b - a >= MIN_PIECE_MS) {
                clips += if (c.isImage) {
                    c.copy(trimStartMs = 0, trimEndMs = b - a)
                } else {
                    val from = c.trimStartMs + ((a - cs) * c.speed).toLong()
                    val to = if (b >= ce) c.trimEndMs else min(c.trimEndMs, c.trimStartMs + ((b - cs) * c.speed).toLong())
                    c.copy(trimStartMs = from, trimEndMs = to)
                }
            }
            cs = ce
            if (cs >= e) break
        }

        val overlays = p.overlays.mapNotNull { o ->
            val a = max(s, o.startMs)
            val b = min(e, o.endMs)
            when {
                b - a < MIN_PIECE_MS -> null
                o.isImage -> o.copy(startMs = a - s, trimStartMs = 0, trimEndMs = b - a)
                else -> {
                    val from = o.trimStartMs + ((a - o.startMs) * o.speed).toLong()
                    val to = if (b >= o.endMs) o.trimEndMs else min(o.trimEndMs, o.trimStartMs + ((b - o.startMs) * o.speed).toLong())
                    o.copy(startMs = a - s, trimStartMs = from, trimEndMs = to)
                }
            }
        }

        val total = p.durationMs
        val audios = p.audios.flatMap { t -> sliceAudio(t, total, s, e) }

        val subtitles = p.subtitles.mapNotNull { sub ->
            val a = max(s, sub.startMs)
            val b = min(e, sub.endMs)
            if (b - a < MIN_PIECE_MS) null else sub.copy(startMs = a - s, endMs = b - s)
        }
        val texts = p.texts.mapNotNull { t ->
            val a = max(s, t.startMs)
            val b = min(e, t.endMs)
            if (b - a < MIN_PIECE_MS) null else t.copy(startMs = a - s, durationMs = b - a)
        }
        return p.copy(clips = clips, overlays = overlays, audios = audios, subtitles = subtitles, texts = texts)
    }

    private fun sliceAudio(t: AudioTrack, projectMs: Long, s: Long, e: Long): List<AudioTrack> {
        val pass = t.durationMs
        if (pass <= 0) return emptyList()
        val regionEnd = t.startMs + t.timelineLengthMs(projectMs)
        val a = max(s, t.startMs)
        val b = min(e, regionEnd)
        if (b - a < MIN_PIECE_MS) return emptyList()
        if (!t.loop) {
            val from = t.sourceStartMs + ((a - t.startMs) * t.speed).toLong()
            val to = if (b >= t.startMs + pass) t.sourceEndMs else min(t.sourceEndMs, t.sourceStartMs + ((b - t.startMs) * t.speed).toLong())
            return listOf(t.copy(startMs = a - s, sourceStartMs = from, sourceEndMs = to))
        }
        // Looping music cut mid-pass: finish the current pass, then loop from the top as before.
        val phase = (a - t.startMs) % pass
        val out = mutableListOf<AudioTrack>()
        val firstLen = min(pass - phase, b - a)
        if (phase == 0L) {
            out += t.copy(startMs = a - s)
            return out
        }
        if (firstLen >= MIN_PIECE_MS) {
            out += t.copy(
                startMs = a - s, loop = false,
                sourceStartMs = t.sourceStartMs + (phase * t.speed).toLong(),
            )
        }
        val next = a + (pass - phase)
        if (b - next >= MIN_PIECE_MS) out += t.copy(id = newId(), startMs = next - s)
        return out
    }
}
