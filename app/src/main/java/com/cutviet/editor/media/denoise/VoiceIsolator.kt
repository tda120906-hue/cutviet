package com.cutviet.editor.media.denoise

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.cutviet.editor.media.dubbing.WhisperAsr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * VoxLock target-speaker extraction (github.com/arbazsiddiqui/voxlock, Apache-2.0): from 3 s of one
 * person's voice it keeps that person and removes everyone else. 16 kHz mono in and out.
 *
 * A port of sherpa-onnx's offline VoxLock denoiser (SpeechDenoiserVoxLockStream): the audio is
 * reflect-padded by n_fft / 2 at both ends, fed to the chunk model [framesPerChunk] frames at a time
 * with its LSTM state carried over, and the frames it returns are overlap-added at the hop and divided
 * by the summed squared Hann window. Checked against sherpa-onnx in Python (50 dB apart).
 */
class VoiceIsolator(enrollModel: File, chunkModel: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(WhisperAsr.threads().coerceAtMost(4))
    }
    private val enroll = env.createSession(enrollModel.absolutePath, opts)
    private val chunk = env.createSession(chunkModel.absolutePath, opts)
    private val meta = chunk.metadata.customMetadata

    val sampleRate = meta["sample_rate"]?.toIntOrNull() ?: 16_000
    private val nFft = meta["n_fft"]?.toIntOrNull() ?: 512
    private val hop = meta["hop_length"]?.toIntOrNull() ?: 128
    private val framesPerChunk = meta["frames_per_chunk"]?.toIntOrNull() ?: 100
    private val gain = meta["output_gain"]?.toFloatOrNull() ?: -0.5f
    private val stateShape = meta["state_shape"]?.split(',')?.map { it.trim().toLong() }?.toLongArray() ?: longArrayOf(6, 32, 256)
    val sampleSeconds = meta["voice_sample_seconds"]?.toIntOrNull() ?: 3

    private val windowSq = FloatArray(nFft) { i -> val w = (0.5 - 0.5 * cos(2 * PI * i / nFft)).toFloat(); w * w }

    /**
     * Keeps the voice of [voice] (exactly [sampleSeconds] s) in [read]'s [total] samples, writing
     * the result through [write] in order. [read] fills an array from a sample index (0 until total).
     */
    suspend fun isolate(
        total: Long,
        voice: FloatArray,
        read: (from: Long, out: FloatArray, count: Int) -> Unit,
        write: (FloatArray, Int) -> Unit,
        progress: (Float) -> Unit,
    ) {
        val pad = nFft / 2
        if (total <= pad + 1) {
            // Too short to pad: leave it as it is, like sherpa-onnx.
            val buf = FloatArray(total.toInt())
            read(0, buf, buf.size)
            write(buf, buf.size)
            return
        }
        val enrolled = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice), longArrayOf(1, voice.size.toLong())).use { x ->
            enroll.run(mapOf(enroll.inputNames.first() to x)).use { r ->
                enroll.outputNames.mapIndexed { i, name ->
                    val t = r.get(i) as OnnxTensor
                    val fb = t.floatBuffer
                    name to Pair(FloatArray(fb.remaining()).also { fb.get(it) }, t.info.shape)
                }
            }
        }
        val voiceTensors = enrolled.associate { (name, v) -> name to OnnxTensor.createTensor(env, FloatBuffer.wrap(v.first), v.second) }
        val stateSize = stateShape.fold(1L) { a, b -> a * b }.toInt()
        var h = FloatArray(stateSize)
        var c = FloatArray(stateSize)
        val names = chunk.inputNames.toList()
        val wavName = names.first()
        val hName = names[names.size - 2]
        val cName = names.last()

        val padded = total + 2L * pad
        // Padded sample i is input sample reflect(i - pad), as torch.stft(center=True).
        val maxSamples = (framesPerChunk - 1) * hop + nFft
        val raw = FloatArray(maxSamples + 2)
        val wav = FloatArray(maxSamples)
        val acc = FloatArray(nFft + framesPerChunk * hop)
        val wsum = FloatArray(nFft + framesPerChunk * hop)
        val out = FloatArray(framesPerChunk * hop + nFft)
        var off = 0L
        var toDrop = pad
        var written = 0L
        try {
            while (padded - off >= nFft) {
                currentCoroutineContext().ensureActive()
                val nf = min(framesPerChunk.toLong(), 1 + (padded - off - nFft) / hop).toInt()
                val ns = (nf - 1) * hop + nFft
                readPadded(off, ns, total, pad, read, raw, wav)
                var frames = FloatArray(0)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(wav, 0, ns), longArrayOf(1, ns.toLong())).use { w ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape).use { ht ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(c), stateShape).use { ct ->
                            val feeds = HashMap<String, OnnxTensor>(voiceTensors)
                            feeds[wavName] = w; feeds[hName] = ht; feeds[cName] = ct
                            chunk.run(feeds).use { r ->
                                frames = (r.get(0) as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }
                                h = (r.get(1) as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }
                                c = (r.get(2) as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }
                            }
                        }
                    }
                }
                require(frames.size == nf * nFft) { "VoxLock returned ${frames.size} values, expected ${nf * nFft}" }
                for (j in 0 until nf) {
                    val base = j * hop
                    for (i in 0 until nFft) {
                        acc[base + i] += frames[j * nFft + i]
                        wsum[base + i] += windowSq[i]
                    }
                }
                val done = nf * hop
                var n = 0
                for (i in 0 until done) {
                    if (toDrop > 0) { toDrop--; continue }
                    out[n++] = norm(acc[i], wsum[i])
                }
                written += emit(out, n, total - written, write)
                shift(acc, done)
                shift(wsum, done)
                off += done
                progress((off.toFloat() / padded).coerceIn(0f, 1f))
            }
            // What is left in the overlap-add buffers.
            var n = 0
            for (i in 0 until nFft) {
                if (toDrop > 0) { toDrop--; continue }
                out[n++] = norm(acc[i], wsum[i])
            }
            written += emit(out, n, total - written, write)
            // Never shorter than the input.
            if (written < total) {
                java.util.Arrays.fill(out, 0f)
                while (written < total) {
                    val k = min(out.size.toLong(), total - written).toInt()
                    write(out, k)
                    written += k
                }
            }
        } finally {
            voiceTensors.values.forEach { it.close() }
        }
    }

    private fun norm(a: Float, w: Float) = a / (if (w > 1e-11f) w else 1f) * gain

    private fun emit(out: FloatArray, n: Int, left: Long, write: (FloatArray, Int) -> Unit): Int {
        val k = min(n.toLong(), left).toInt()
        if (k > 0) write(out, k)
        return k
    }

    private fun shift(a: FloatArray, by: Int) {
        System.arraycopy(a, by, a, 0, a.size - by)
        java.util.Arrays.fill(a, a.size - by, a.size, 0f)
    }

    /** Padded samples [from, from + count) into [dst], reading the input span they need once. */
    private fun readPadded(from: Long, count: Int, total: Long, pad: Int, read: (Long, FloatArray, Int) -> Unit, raw: FloatArray, dst: FloatArray) {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (i in 0 until count) {
            val j = reflect(from - pad + i, total)
            if (j < lo) lo = j
            if (j > hi) hi = j
        }
        val span = (hi - lo + 1).toInt()
        val buf = if (span <= raw.size) raw else FloatArray(span)
        read(lo, buf, span)
        for (i in 0 until count) dst[i] = buf[(reflect(from - pad + i, total) - lo).toInt()]
    }

    override fun close() {
        chunk.close()
        enroll.close()
        opts.close()
    }

    companion object {
        fun reflect(j: Long, n: Long): Long {
            if (n == 1L) return 0
            var x = j
            while (x < 0 || x >= n) {
                if (x < 0) x = -x
                if (x >= n) x = 2 * (n - 1) - x
            }
            return x
        }

        /**
         * The loudest [seconds] s (by 10 ms frame energy) within the first [searchSeconds] s of
         * [x], as sherpa-onnx chooses VoxLock's voice sample; null if [x] is shorter than [seconds].
         */
        fun loudest(x: FloatArray, rate: Int, seconds: Int, searchSeconds: Int): FloatArray? {
            val n = min(x.size, searchSeconds * rate)
            val frame = rate / 100
            val frames = n / frame
            val window = seconds * 100
            val length = seconds * rate
            if (n < length) return null
            if (frames <= window) return x.copyOfRange(0, length)
            val energy = DoubleArray(frames) { f ->
                var s = 0.0
                for (k in f * frame until (f + 1) * frame) s += x[k].toDouble() * x[k]
                s / frame
            }
            val windows = min(frames - window + 1, (n - length) / frame + 1)
            var sum = 0.0
            for (k in 0 until window) sum += energy[k]
            var best = 0
            var bestSum = sum
            for (i in 1 until windows) {
                sum += energy[i + window - 1] - energy[i - 1]
                if (sum > bestSum) { bestSum = sum; best = i }
            }
            return x.copyOfRange(best * frame, best * frame + length)
        }
    }
}
