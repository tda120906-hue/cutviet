package com.cutviet.editor.media.denoise

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Who the main voice is, and where only other people are heard. Tuned in Python on mixes with laugh
 * tracks, a single laugh, a second talker, chatter and short 8 s clips:
 *  - [pick]: the voice sample is a 3 s window that sounds like speech and not laughter ([AudioTagger]),
 *    of the person VoxLock keeps the most of over the clip, i.e. who talks the most. The old choice
 *    (loudest 3 s in the first 30 s) took a loud laugh as "the main voice" in short clips and kept it.
 *  - [gate]: 1 s stretches of VoxLock's output whose fingerprint is unlike the main voice
 *    ([SpeakerPrint], similarity < 0.25) for at least 0.75 s are silenced: VoxLock alone lets another
 *    person through when they talk on their own. Short runs are left alone so sentence ends stay.
 * All positions are 16 kHz samples.
 */
object MainVoiceFinder {
    private const val R = 16_000
    const val SAMPLE_SECONDS = 3
    private const val MAX_CANDIDATES = 6
    private const val PROBE_SECONDS = 80

    class Candidate(val start: Int, val energy: Double, val speech: Float, val laugh: Float) {
        var kept = 0.0
    }

    /** Index of the start of the main voice's 3 s sample, or null if the audio is shorter than that. */
    suspend fun pick(
        total: Long,
        read: (Long, FloatArray, Int) -> Unit,
        tagger: AudioTagger,
        vox: VoiceIsolator,
        log: (String) -> Unit = {},
        progress: (Float) -> Unit = {},
    ): Int? {
        val len = SAMPLE_SECONDS * R
        if (total < len) return null
        val hop = if (total < 120L * R) R / 2 else 2 * R
        val seg = FloatArray(len)
        val all = ArrayList<Candidate>()
        val steps = ((total - len) / hop + 1).toInt()
        for (k in 0 until steps) {
            currentCoroutineContext().ensureActive()
            val s = k.toLong() * hop
            read(s, seg, len)
            var e = 0.0
            for (v in seg) e += v.toDouble() * v
            e /= len
            if (e < 1e-7) continue
            val p = tagger.probs(seg)
            all += Candidate(s.toInt(), e, AudioTagger.maxOf(p, AudioTagger.SPEECH), AudioTagger.maxOf(p, AudioTagger.LAUGH))
            if (k % 8 == 0) progress(0.5f * k / steps)
        }
        if (all.isEmpty()) return 0
        val good = all.filter { it.speech >= 0.5f && it.laugh < 0.3f }.ifEmpty { all.filter { it.laugh < 0.3f } }.ifEmpty { all }
        val chosen = ArrayList<Candidate>()
        for (c in good.sortedByDescending { it.energy }) {
            if (chosen.all { abs(it.start - c.start) >= 2 * R }) chosen += c
            if (chosen.size >= MAX_CANDIDATES) break
        }
        if (chosen.size > 1) {
            val probe = probe(total, read)
            var ep = 0.0
            for (v in probe) ep += v.toDouble() * v
            for ((i, c) in chosen.withIndex()) {
                val sample = FloatArray(len).also { read(c.start.toLong(), it, len) }
                var kept = 0.0
                vox.isolate(probe.size.toLong(), sample, prime = sample, read = { from, out, n ->
                    System.arraycopy(probe, from.toInt(), out, 0, n)
                }, write = { x, n -> for (j in 0 until n) kept += x[j].toDouble() * x[j] }) {}
                c.kept = kept / (ep + 1e-12)
                progress(0.5f + 0.5f * (i + 1) / chosen.size)
            }
        }
        val best = chosen.maxByOrNull { it.kept }!!
        log(chosen.joinToString(prefix = "main voice candidates: ") {
            "%.1fs speech %.2f laugh %.2f kept %.2f".format(it.start / R.toFloat(), it.speech, it.laugh, it.kept)
        } + "; using %.1fs".format(best.start / R.toFloat()))
        return best.start
    }

    /** The whole audio if short, else 4 × 20 s spread over it. */
    private fun probe(total: Long, read: (Long, FloatArray, Int) -> Unit): FloatArray {
        if (total <= PROBE_SECONDS.toLong() * R) return FloatArray(total.toInt()).also { read(0, it, it.size) }
        val piece = PROBE_SECONDS * R / 4
        val out = FloatArray(4 * piece)
        val tmp = FloatArray(piece)
        for (k in 0 until 4) {
            read(k * (total - piece) / 3, tmp, piece)
            System.arraycopy(tmp, 0, out, k * piece, piece)
        }
        return out
    }

    /**
     * Per-sample gain (one value per 10 ms, [GATE_HOP] samples) that silences the stretches of [y]
     * (VoxLock's output, [total] samples) where only someone other than the main voice is heard.
     * [sampleAt]: start of the main voice sample in [y].
     */
    suspend fun gate(
        total: Long,
        read: (Long, FloatArray, Int) -> Unit,
        sampleAt: Int,
        prints: SpeakerPrint,
        progress: (Float) -> Unit = {},
        tau: Float = 0.25f,
        minRunSeconds: Float = 0.75f,
    ): FloatArray {
        val win = R
        val hop = R / 4
        val frames = (total / GATE_HOP + 1).toInt()
        val sample = FloatArray(SAMPLE_SECONDS * R).also { read(sampleAt.toLong(), it, it.size) }
        val ref0 = prints.embed(sample)
        // Fingerprint of every 1 s window with sound in it.
        val starts = ArrayList<Int>()
        val prints1 = ArrayList<FloatArray?>()
        val seg = FloatArray(win)
        val steps = if (total >= win) ((total - win) / hop + 1).toInt() else 1
        for (k in 0 until steps) {
            currentCoroutineContext().ensureActive()
            val s = k * hop
            read(s.toLong(), seg, win)
            var e = 0.0
            for (v in seg) e += v.toDouble() * v
            val db = 10 * log10(e / win + 1e-12)
            starts += s
            prints1 += if (db < -50) null else prints.embed(seg)
            if (k % 16 == 0) progress(k.toFloat() / steps)
        }
        // Reference: the sample plus every window clearly of the same person.
        val ref = FloatArray(ref0.size)
        var count = 1
        for (i in ref.indices) ref[i] = ref0[i]
        for (p in prints1) if (p != null && SpeakerPrint.similarity(p, ref0) >= 0.55f) {
            for (i in ref.indices) ref[i] += p[i]
            count++
        }
        val refN = SpeakerPrint.normalize(ref)
        val good = BooleanArray(frames)
        val covered = BooleanArray(frames)
        for ((k, s) in starts.withIndex()) {
            val p = prints1[k]
            val ok = p == null || SpeakerPrint.similarity(p, refN) >= tau
            val f0 = s / GATE_HOP
            val f1 = min(frames, (s + win) / GATE_HOP)
            for (f in f0 until f1) {
                covered[f] = true
                if (ok) good[f] = true
            }
        }
        val g = FloatArray(frames) { if (good[it] || !covered[it]) 1f else 0f }
        // Leave short runs alone.
        val minRun = (minRunSeconds * R / GATE_HOP).toInt()
        var i = 0
        while (i < frames) {
            if (g[i] == 0f) {
                var j = i
                while (j < frames && g[j] == 0f) j++
                if (j - i < minRun) for (k in i until j) g[k] = 1f
                i = j
            } else {
                i++
            }
        }
        // 50 ms smoothing.
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            var n = 0
            for (k in max(0, f - 2)..min(frames - 1, f + 2)) { s += g[k]; n++ }
            out[f] = s / n
        }
        progress(1f)
        return out
    }

    const val GATE_HOP = 160

    /** [gains] (one per [GATE_HOP] samples) at sample [i], linearly interpolated. */
    fun gainAt(gains: FloatArray, i: Long): Float {
        val pos = i.toDouble() / GATE_HOP
        val a = pos.toInt().coerceIn(0, gains.lastIndex)
        val b = (a + 1).coerceAtMost(gains.lastIndex)
        val fr = (pos - a).toFloat().coerceIn(0f, 1f)
        return gains[a] + (gains[b] - gains[a]) * fr
    }
}
