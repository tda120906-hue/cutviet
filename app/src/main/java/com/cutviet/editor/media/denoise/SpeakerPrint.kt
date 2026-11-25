package com.cutviet.editor.media.denoise

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.cutviet.editor.media.dsp.Fft
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Voice fingerprint (WeSpeaker ResNet34, VoxCeleb, from sherpa-onnx): a unit vector per stretch of
 * 16 kHz speech; two stretches of the same person score high on [similarity]. Features: Kaldi-style
 * 80-bin log fbank (25 ms Povey window, 10 ms hop, pre-emphasis 0.97), mean-normalised per stretch.
 */
class SpeakerPrint(model: File, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val session = env.createSession(model.absolutePath, opts)
    private val input = session.inputNames.first()
    private val fft = Fft(NFFT)
    private val re = FloatArray(NFFT)
    private val im = FloatArray(NFFT)

    /** Unit-length fingerprint of [x] (at least 25 ms of 16 kHz audio). */
    fun embed(x: FloatArray): FloatArray {
        val t = 1 + (x.size - WIN) / HOP
        require(t >= 1) { "too short" }
        val feats = FloatArray(t * MELS)
        val frame = DoubleArray(WIN)
        for (f in 0 until t) {
            var mean = 0.0
            for (i in 0 until WIN) { frame[i] = x[f * HOP + i] * 32768.0; mean += frame[i] }
            mean /= WIN
            for (i in 0 until WIN) frame[i] -= mean
            for (i in WIN - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (i in 0 until WIN) re[i] = (frame[i] * povey[i]).toFloat()
            fft.forward(re, im)
            for (m in 0 until MELS) {
                val b = bank[m]
                var e = 0.0
                for (k in 0..NFFT / 2) if (b[k] != 0f) e += b[k] * (re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
                feats[f * MELS + m] = ln(max(e, 1.1920929e-07)).toFloat()
            }
        }
        for (m in 0 until MELS) {
            var s = 0.0
            for (f in 0 until t) s += feats[f * MELS + m]
            val mu = (s / t).toFloat()
            for (f in 0 until t) feats[f * MELS + m] -= mu
        }
        val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, t.toLong(), MELS.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        return normalize(e)
    }

    override fun close() {
        session.close()
        opts.close()
    }

    companion object {
        private const val WIN = 400
        private const val HOP = 160
        private const val NFFT = 512
        private const val MELS = 80
        private val povey = DoubleArray(WIN) { i -> (0.5 - 0.5 * cos(2 * PI * i / (WIN - 1))).pow(0.85) }
        private val bank: Array<FloatArray> = run {
            fun mel(f: Double) = 1127 * ln(1 + f / 700)
            val lo = mel(20.0)
            val hi = mel(8000.0)
            val c = DoubleArray(MELS + 2) { lo + (hi - lo) * it / (MELS + 1) }
            Array(MELS) { m ->
                FloatArray(NFFT / 2 + 1) { k ->
                    val mf = mel(k * 16000.0 / NFFT)
                    max(0.0, min((mf - c[m]) / (c[m + 1] - c[m]), (c[m + 2] - mf) / (c[m + 2] - c[m + 1]))).toFloat()
                }
            }
        }

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / n }
        }
    }
}
