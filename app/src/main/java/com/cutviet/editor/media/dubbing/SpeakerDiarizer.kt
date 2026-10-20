package com.cutviet.editor.media.dubbing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import com.cutviet.editor.media.dsp.KaldiFbank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Who speaks when, worked out on the phone, so the dub gives the same person the same voice:
 * pyannote segmentation 3.0 (bundled, MIT) finds up to three speakers in each 10 s window, the
 * CAM++ model (downloaded) gives a voice fingerprint of each of them, and fingerprints close
 * enough are one person across the whole video. These are sherpa-onnx's offline diarization steps;
 * the clustering joins groups by average distance and folds tiny groups into the nearest real
 * one. Settings were checked against recordings of two and four speakers in English and Chinese.
 */
class SpeakerDiarizer(private val context: Context, private val embeddingModel: File) {

    /** [x]: 16 kHz mono. [speakers]: how many people there are, or 0 to find out. */
    suspend fun diarize(x: FloatArray, speakers: Int, progress: (Float) -> Unit): Diarization = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(WhisperAsr.threads())
        }
        val segModel = context.assets.open(SEGMENTATION_ASSET).use { it.readBytes() }
        val step = stepFor(x.size)
        val starts = chunkStarts(x.size, step)
        env.createSession(segModel, opts).use { seg ->
            // 1. Up to three local speakers per window.
            val labels = ArrayList<ByteArray>(starts.size)
            var frames = 0
            val input = seg.inputNames.first()
            var c = 0
            while (c < starts.size) {
                coroutineContext.ensureActive()
                val batch = min(BATCH, starts.size - c)
                val buf = FloatBuffer.allocate(batch * WINDOW)
                for (b in 0 until batch) {
                    val s = starts[c + b]
                    val n = min(WINDOW, x.size - s)
                    buf.put(x, s, n)
                    repeat(WINDOW - n) { buf.put(0f) }
                }
                buf.rewind()
                OnnxTensor.createTensor(env, buf, longArrayOf(batch.toLong(), 1, WINDOW.toLong())).use { t ->
                    seg.run(mapOf(input to t)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val y = r[0].value as Array<Array<FloatArray>>
                        for (b in 0 until batch) {
                            labels += localLabels(y[b])
                            frames = y[b].size
                        }
                    }
                }
                c += batch
                progress(0.3f * c / starts.size)
            }
            val tSeg = SystemClock.elapsedRealtime()

            // 2. A voice fingerprint for each local speaker of each window.
            val keys = ArrayList<Int>() // chunk * 3 + local speaker
            val embeddings = ArrayList<FloatArray>()
            val weights = ArrayList<Double>()
            val fbank = KaldiFbank()
            val samplesPerFrame = WINDOW.toDouble() / frames
            val audio = FloatArray(WINDOW)
            env.createSession(embeddingModel.absolutePath, opts).use { emb ->
                val embInput = emb.inputNames.first()
                for (ci in starts.indices) {
                    coroutineContext.ensureActive()
                    for (k in 0 until LOCAL_SPEAKERS) {
                        val use = embeddingFrames(labels[ci], frames, k) ?: continue
                        var len = 0
                        for (f in 0 until frames) {
                            if (!use[f]) continue
                            val a = starts[ci] + (f * samplesPerFrame).toInt()
                            val b = min(x.size, starts[ci] + ((f + 1) * samplesPerFrame).toInt())
                            for (s in a until b) if (len < audio.size) audio[len++] = x[s]
                        }
                        if (len < MIN_EMBED_SAMPLES) continue
                        val feats = fbank.compute(audio, len)
                        val t = feats.size / fbank.bins
                        subtractMean(feats, t, fbank.bins)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, t.toLong(), fbank.bins.toLong())).use { tensor ->
                            emb.run(mapOf(embInput to tensor)).use { r ->
                                @Suppress("UNCHECKED_CAST")
                                val e = (r[0].value as Array<FloatArray>)[0]
                                normalise(e)
                                keys += ci * LOCAL_SPEAKERS + k
                                embeddings += e
                                weights += len / RATE.toDouble()
                            }
                        }
                    }
                    progress(0.3f + 0.65f * (ci + 1) / starts.size)
                }
            }
            val tEmb = SystemClock.elapsedRealtime()

            // 3. The same voice across windows is one person.
            val minWeight = MIN_WEIGHT_S * RATE / step
            val cluster = SpeakerClustering.cluster(embeddings, weights.toDoubleArray(), THRESHOLD, minWeight, speakers)
            val byKey = HashMap<Int, Int>()
            keys.forEachIndexed { i, key -> byKey[key] = cluster[i] }
            val d = aggregate(starts, labels, frames, byKey, (cluster.maxOrNull() ?: -1) + 1, x.size)
            DubLog.i(
                TAG,
                "diarized ${x.size / RATE} s: ${starts.size} windows (step ${step / 16} ms), ${embeddings.size} fingerprints, " +
                    "${d.speakers} speakers; segmentation ${tSeg - t0} ms, fingerprints ${tEmb - tSeg} ms, clustering ${SystemClock.elapsedRealtime() - tEmb} ms",
            )
            progress(1f)
            d
        }
    }

    companion object {
        const val TAG = "CutVietSpeakers"
        const val SEGMENTATION_ASSET = "diarization/pyannote-segmentation-3.0.onnx"
        const val RATE = 16_000
        const val WINDOW = 160_000
        /** Model frame step and size, in samples (from the model's metadata). */
        const val FRAME_SHIFT = 270
        const val FRAME_SIZE = 991
        const val LOCAL_SPEAKERS = 3
        /** Cosine distance under which two voices are the same person (CAM++ zh-en). */
        const val THRESHOLD = 0.46
        /** A person must have this much fingerprinted speech at a 1 s window step. */
        const val MIN_WEIGHT_S = 4.0
        private const val MIN_STEP = 16_000
        private const val MAX_STEP = 32_000
        private const val TARGET_WINDOWS = 200
        private const val BATCH = 8
        private const val MIN_EMBED_SAMPLES = 8_000

        /** Powerset classes of the segmentation model: none, each alone, each pair. */
        private val POWERSET = arrayOf(
            intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, 0, 1),
            intArrayOf(1, 1, 0), intArrayOf(1, 0, 1), intArrayOf(0, 1, 1),
        )

        /** Window step: one second, longer for long videos so the work stays bounded. */
        fun stepFor(samples: Int): Int =
            if (samples <= WINDOW) MIN_STEP
            else ceil((samples - WINDOW).toDouble() / (TARGET_WINDOWS - 1)).toInt().coerceIn(MIN_STEP, MAX_STEP)

        /** Window starts covering all of [samples]; the last window is padded with silence. */
        fun chunkStarts(samples: Int, step: Int): IntArray {
            val out = ArrayList<Int>()
            var s = 0
            while (true) {
                out += s
                if (s + WINDOW >= samples) break
                s += step
            }
            return out.toIntArray()
        }

        /** Model scores (frames × 7 classes) → active flags, frames × 3 local speakers. */
        fun localLabels(y: Array<FloatArray>): ByteArray {
            val out = ByteArray(y.size * LOCAL_SPEAKERS)
            for (f in y.indices) {
                var best = 0
                for (k in 1 until y[f].size) if (y[f][k] > y[f][best]) best = k
                val m = POWERSET[best.coerceAtMost(POWERSET.lastIndex)]
                for (k in 0 until LOCAL_SPEAKERS) out[f * LOCAL_SPEAKERS + k] = m[k].toByte()
            }
            return out
        }

        /**
         * Frames to fingerprint local speaker [k]: where it speaks alone, or everywhere it
         * speaks when that is too little; null when it hardly speaks.
         */
        fun embeddingFrames(labels: ByteArray, frames: Int, k: Int): BooleanArray? {
            val alone = BooleanArray(frames)
            val all = BooleanArray(frames)
            var nAlone = 0
            var nAll = 0
            for (f in 0 until frames) {
                if (labels[f * LOCAL_SPEAKERS + k].toInt() == 0) continue
                all[f] = true
                nAll++
                var others = 0
                for (j in 0 until LOCAL_SPEAKERS) others += labels[f * LOCAL_SPEAKERS + j]
                if (others == 1) {
                    alone[f] = true
                    nAlone++
                }
            }
            return when {
                nAlone >= MIN_FRAMES -> alone
                nAll >= MIN_FRAMES -> all
                else -> null
            }
        }

        private const val MIN_FRAMES = 10

        /**
         * Joins the windows: each frame of the video gets as many speakers as the windows
         * covering it hear on average, the people most often heard there.
         */
        fun aggregate(starts: IntArray, labels: List<ByteArray>, frames: Int, cluster: Map<Int, Int>, people: Int, samples: Int): Diarization {
            val total = samples / FRAME_SHIFT + 1
            val heard = Array(total) { IntArray(max(people, 1)) }
            val count = IntArray(total)
            val active = IntArray(total)
            for (c in starts.indices) {
                val first = (starts[c].toDouble() / FRAME_SHIFT).roundToInt()
                for (f in 0 until frames) {
                    val g = first + f
                    if (g >= total) break
                    count[g]++
                    for (k in 0 until LOCAL_SPEAKERS) {
                        if (labels[c][f * LOCAL_SPEAKERS + k].toInt() == 0) continue
                        active[g]++
                        val p = cluster[c * LOCAL_SPEAKERS + k] ?: continue
                        heard[g][p]++
                    }
                }
            }
            val mask = IntArray(total)
            for (g in 0 until total) {
                if (count[g] == 0 || people == 0) continue
                val n = min(people, (active[g].toDouble() / count[g] + 0.5).toInt())
                val order = (0 until people).sortedByDescending { heard[g][it] }
                for (i in 0 until n) if (heard[g][order[i]] > 0) mask[g] = mask[g] or (1 shl order[i])
            }
            return Diarization.renumbered(mask)
        }

        private fun subtractMean(feats: FloatArray, frames: Int, bins: Int) {
            if (frames == 0) return
            for (b in 0 until bins) {
                var m = 0.0
                for (f in 0 until frames) m += feats[f * bins + b]
                m /= frames
                for (f in 0 until frames) feats[f * bins + b] -= m.toFloat()
            }
        }

        fun normalise(e: FloatArray) {
            var s = 0.0
            for (v in e) s += v.toDouble() * v
            val n = sqrt(s).toFloat()
            if (n > 0) for (i in e.indices) e[i] /= n
        }
    }
}

/**
 * Who speaks in each model frame (a bit mask of speakers, 0 = nobody); frame g is centred at
 * g × 270 + 495 samples of 16 kHz audio. Speakers are numbered in the order they first speak.
 */
class Diarization(private val mask: IntArray, val speakers: Int) {
    val frames: Int get() = mask.size

    fun at(frame: Int): Int = mask.getOrElse(frame) { 0 }

    /** The speaker heard most in [fromMs]..[toMs] (times in the diarized audio), or null. */
    fun dominant(fromMs: Long, toMs: Long): Int? {
        if (speakers == 0) return null
        val a = frameOf(fromMs).coerceIn(0, mask.size)
        val b = frameOf(toMs).coerceIn(a, mask.size)
        val counts = IntArray(speakers)
        for (g in a until b) for (p in 0 until speakers) if (mask[g] and (1 shl p) != 0) counts[p]++
        val best = counts.indices.maxByOrNull { counts[it] } ?: return null
        return if (counts[best] > 0) best else null
    }

    companion object {
        fun frameOf(ms: Long): Int =
            ((ms / 1000.0 * SpeakerDiarizer.RATE - SpeakerDiarizer.FRAME_SIZE / 2.0) / SpeakerDiarizer.FRAME_SHIFT).roundToInt()

        /** Numbers speakers by when they first speak, dropping ones never heard. */
        fun renumbered(mask: IntArray): Diarization {
            val order = HashMap<Int, Int>()
            for (m in mask) {
                var bits = m
                var p = 0
                while (bits != 0) {
                    if (bits and 1 != 0 && p !in order) order[p] = order.size
                    bits = bits ushr 1
                    p++
                }
            }
            val out = IntArray(mask.size) { g ->
                var r = 0
                for ((from, to) in order) if (mask[g] and (1 shl from) != 0) r = r or (1 shl to)
                r
            }
            return Diarization(out, order.size)
        }
    }
}

/**
 * Agglomerative clustering of unit-length voice fingerprints by cosine distance, joining the two
 * groups with the smallest average distance until it passes the threshold (or until [k] groups
 * are left). Groups with less than [minWeight] seconds of speech are then dropped and every
 * fingerprint goes to the nearest remaining group.
 */
object SpeakerClustering {
    fun cluster(e: List<FloatArray>, weights: DoubleArray, threshold: Double, minWeight: Double, k: Int = 0): IntArray {
        val n = e.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1)
        val d = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val v = max(0.0, 1.0 - dot(e[i], e[j]))
            d[i][j] = v
            d[j][i] = v
        }
        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val owner = IntArray(n) { it }
        var groups = n
        while (groups > 1 && (k <= 0 || groups > k)) {
            var bi = -1
            var bj = -1
            var best = Double.MAX_VALUE
            for (i in 0 until n) {
                if (!alive[i]) continue
                val row = d[i]
                for (j in i + 1 until n) if (alive[j] && row[j] < best) {
                    best = row[j]
                    bi = i
                    bj = j
                }
            }
            if (k <= 0 && best > threshold) break
            for (m in 0 until n) {
                if (!alive[m] || m == bi || m == bj) continue
                val v = (size[bi] * d[bi][m] + size[bj] * d[bj][m]) / (size[bi] + size[bj])
                d[bi][m] = v
                d[m][bi] = v
            }
            size[bi] += size[bj]
            alive[bj] = false
            groups--
            for (t in 0 until n) if (owner[t] == bj) owner[t] = bi
        }
        val reps = (0 until n).filter { alive[it] }
        val index = HashMap<Int, Int>().apply { reps.forEachIndexed { i, r -> put(r, i) } }
        var label = IntArray(n) { index.getValue(owner[it]) }
        if (k <= 0) {
            val weight = DoubleArray(reps.size)
            for (t in 0 until n) weight[label[t]] += weights[t]
            val big = reps.indices.filter { weight[it] >= minWeight }.ifEmpty { listOf(weight.indices.maxByOrNull { weight[it] }!!) }
            val centroids = big.map { g ->
                val c = FloatArray(e[0].size)
                for (t in 0 until n) if (label[t] == g) for (i in c.indices) c[i] += e[t][i]
                SpeakerDiarizer.normalise(c)
                c
            }
            label = IntArray(n) { t -> centroids.indices.maxByOrNull { dot(centroids[it], e[t]) }!! }
        }
        // Number groups by first appearance.
        val order = HashMap<Int, Int>()
        return IntArray(n) { t -> order.getOrPut(label[t]) { order.size } }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i].toDouble() * b[i]
        return s
    }
}

/** Fits the diarization to the dub's lines: one speaker per line, and lines cut where the speaker changes. */
object SpeakerTurns {
    /** A change shorter than this is not taken as another person speaking. */
    const val MIN_TURN_MS = 700L
    private const val BLOCK_MS = 100L
    /** How far a cut may move to land in the quietest spot. */
    private const val SNAP_MS = 150L

    /**
     * Cuts the speech [ranges] (ms in [voice], 16 kHz) where the speaker changes, and gives each
     * piece its speaker (null where the diarization heard nobody).
     */
    fun splitRanges(ranges: List<SpeechDetector.Range>, d: Diarization, voice: FloatArray): List<Pair<SpeechDetector.Range, Int?>> {
        val out = ArrayList<Pair<SpeechDetector.Range, Int?>>()
        for (r in ranges) {
            val blocks = ((r.endMs - r.startMs + BLOCK_MS - 1) / BLOCK_MS).toInt().coerceAtLeast(1)
            val who = (0 until blocks).map { b ->
                val a = r.startMs + b * BLOCK_MS
                d.dominant(a, min(r.endMs, a + BLOCK_MS))
            }
            val runs = runs(who, List(blocks) { BLOCK_MS }, MIN_TURN_MS)
            var from = r.startMs
            var block = 0
            runs.forEachIndexed { i, (speaker, count) ->
                block += count
                val to = if (i == runs.lastIndex || from >= r.endMs - 1) r.endMs else quietest(voice, r.startMs + block * BLOCK_MS).coerceIn(from + 1, r.endMs)
                if (to > from) out += SpeechDetector.Range(from, to) to speaker
                from = to
            }
        }
        return out
    }

    /**
     * Gives each recognised sentence its speaker; a sentence whose words come from two people is
     * cut between their words. [offsetMs]: where the diarized audio starts on the sentences' timeline.
     */
    fun splitSentences(segments: List<SpeechSegment>, d: Diarization, offsetMs: Long): List<Pair<SpeechSegment, Int?>> {
        val out = ArrayList<Pair<SpeechSegment, Int?>>()
        for (s in segments) {
            if (s.words.size < 2) {
                out += s to d.dominant(s.startMs - offsetMs, s.endMs - offsetMs)
                continue
            }
            val who = s.words.map { w -> d.dominant(w.startMs - offsetMs, max(w.endMs, w.startMs + 1) - offsetMs) }
            val runs = runs(who, s.words.map { (it.endMs - it.startMs).coerceAtLeast(1) }, MIN_TURN_MS)
            if (runs.size == 1) {
                out += s to runs[0].first
                continue
            }
            var at = 0
            for ((speaker, count) in runs) {
                val words = s.words.subList(at, at + count)
                at += count
                val text = WhisperTranscript.joinWords(words)
                if (text.isNotBlank()) out += SpeechSegment(words.first().startMs, words.last().endMs, text, words) to speaker
            }
        }
        return out
    }

    /**
     * Runs of the same speaker over items of the given lengths: unknown items take their
     * neighbour's speaker, and runs shorter than [minMs] join the neighbour they sit next to longest.
     * Returns (speaker, item count) pairs.
     */
    fun runs(who: List<Int?>, lengths: List<Long>, minMs: Long): List<Pair<Int?, Int>> {
        if (who.isEmpty()) return emptyList()
        val filled = who.toMutableList()
        val firstKnown = filled.firstOrNull { it != null }
        var last = firstKnown
        for (i in filled.indices) {
            if (filled[i] == null) filled[i] = last else last = filled[i]
        }
        data class Run(var speaker: Int?, var count: Int, var ms: Long)
        val list = ArrayList<Run>()
        filled.forEachIndexed { i, p ->
            val l = list.lastOrNull()
            if (l != null && l.speaker == p) {
                l.count++
                l.ms += lengths[i]
            } else {
                list += Run(p, 1, lengths[i])
            }
        }
        while (list.size > 1) {
            val i = list.indices.minByOrNull { list[it].ms }!!
            if (list[i].ms >= minMs) break
            // Into the longer neighbour.
            val j = when (i) {
                0 -> 1
                list.lastIndex -> i - 1
                else -> if (list[i - 1].ms >= list[i + 1].ms) i - 1 else i + 1
            }
            list[j].count += list[i].count
            list[j].ms += list[i].ms
            list.removeAt(i)
            // Neighbours of the same speaker become one run.
            var k = 0
            while (k < list.size - 1) {
                if (list[k].speaker == list[k + 1].speaker) {
                    list[k].count += list[k + 1].count
                    list[k].ms += list[k + 1].ms
                    list.removeAt(k + 1)
                } else {
                    k++
                }
            }
        }
        return list.map { it.speaker to it.count }
    }

    /** The quietest 10 ms within [SNAP_MS] of [ms] in [voice] (16 kHz), as a time in ms. */
    fun quietest(voice: FloatArray, ms: Long): Long {
        var best = ms
        var bestDb = Double.MAX_VALUE
        var t = ms - SNAP_MS
        while (t <= ms + SNAP_MS) {
            val a = (t * 16).toInt()
            val b = a + 160
            if (a >= 0 && b <= voice.size) {
                var s = 0.0
                for (i in a until b) s += voice[i].toDouble() * voice[i]
                val db = 10 * log10(max(s / 160, 1e-12))
                if (db < bestDb) {
                    bestDb = db
                    best = t + 5
                }
            }
            t += 10
        }
        return best
    }
}
