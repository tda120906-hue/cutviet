package com.cutviet.editor.media.denoise

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.cutviet.editor.media.dsp.Fft
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Sound-event tagger (CED-mini, AudioSet's 527 classes, Apache-2.0, int8 from sherpa-onnx), bundled
 * in the app. Used to tell speech from laughter so the main voice is never learnt from a laugh.
 * 16 kHz mono in. Features: 64-bin log-mel (512-point FFT, 10 ms hop, centred), as the model was trained.
 */
class AudioTagger private constructor(model: ByteArray, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val session = env.createSession(model, opts)
    private val input = session.inputNames.first()

    /** Probabilities of the 527 AudioSet classes for [x]. */
    fun probs(x: FloatArray): FloatArray {
        val (f, t) = features(x)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, MELS.toLong(), t.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    override fun close() {
        session.close()
        opts.close()
    }

    companion object {
        const val ASSET = "denoise/ced_mini_int8.onnx"
        private const val N = 512
        private const val HOP = 160
        private const val MELS = 64
        val SPEECH = 0..5
        val LAUGH = 16..21

        fun open(context: Context, threads: Int): AudioTagger =
            AudioTagger(context.assets.open(ASSET).use { it.readBytes() }, threads)

        fun maxOf(p: FloatArray, range: IntRange): Float {
            var m = 0f
            for (i in range) m = max(m, p[i])
            return m
        }

        private val window = FloatArray(N) { i -> (0.5 - 0.5 * cos(2 * PI * i / N)).toFloat() }
        private val bank: Array<FloatArray> = run {
            fun hz2mel(f: Double) = 2595 * log10(1 + f / 700)
            fun mel2hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
            val bins = N / 2 + 1
            val lo = hz2mel(0.0)
            val hi = hz2mel(8000.0)
            val pts = DoubleArray(MELS + 2) { mel2hz(lo + (hi - lo) * it / (MELS + 1)) }
            Array(MELS) { m ->
                FloatArray(bins) { k ->
                    val f = 8000.0 * k / (bins - 1)
                    max(0.0, min((f - pts[m]) / (pts[m + 1] - pts[m]), (pts[m + 2] - f) / (pts[m + 2] - pts[m + 1]))).toFloat()
                }
            }
        }

        /** Log-mel features of [x], laid out [mel][frame], and the frame count. */
        fun features(x: FloatArray): Pair<FloatArray, Int> {
            val n = x.size
            val pad = N / 2
            val t = 1 + n / HOP
            val fft = Fft(N)
            val re = FloatArray(N)
            val im = FloatArray(N)
            val out = FloatArray(MELS * t)
            var top = -Float.MAX_VALUE
            for (f in 0 until t) {
                for (i in 0 until N) {
                    re[i] = sample(x, f * HOP + i - pad) * window[i]
                    im[i] = 0f
                }
                fft.forward(re, im)
                for (m in 0 until MELS) {
                    val b = bank[m]
                    var e = 0.0
                    for (k in 0..N / 2) if (b[k] != 0f) e += b[k] * (re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
                    val db = (10 * log10(max(e, 1e-10))).toFloat()
                    out[m * t + f] = db
                    if (db > top) top = db
                }
            }
            val floor = top - 120f
            for (i in out.indices) if (out[i] < floor) out[i] = floor
            return out to t
        }

        /** x[j] with reflect padding (numpy "reflect"). */
        private fun sample(x: FloatArray, j: Int): Float {
            if (x.size == 1) return x[0]
            var k = j
            while (k < 0 || k >= x.size) {
                if (k < 0) k = -k
                if (k >= x.size) k = 2 * (x.size - 1) - k
            }
            return x[k]
        }
    }
}
