package com.cutviet.editor.media.dubbing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.cutviet.editor.media.dsp.StereoStft
import com.cutviet.editor.media.dsp.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The voice and everything else, as two WAV files that add up to the original sound. */
data class Stems(val voice: File, val background: File, val durationMs: Long)

/**
 * Splits the clip's sound into the spoken voice and the rest (noise, objects, ambience, music)
 * with a UVR MDX-Net vocal model run by ONNX Runtime on the phone.
 *
 * The model sees 44.1 kHz stereo in chunks of [CHUNK] samples; each chunk overlaps its neighbours
 * by half an FFT so the joins are clean. The background is the original minus the voice estimate,
 * so voice + background always equals the original exactly, and turning the voice track down
 * leaves every other sound untouched.
 */
class VoiceSeparator(private val context: Context, private val model: File, private val workDir: File) {

    suspend fun separate(sourceUri: String, startMs: Long, endMs: Long, progress: (Float) -> Unit): Stems = withContext(Dispatchers.IO) {
        if (!model.exists()) throw StepUnavailableException("Chưa tải model tách giọng")
        val t0 = SystemClock.elapsedRealtime()
        val pcm = File(workDir, "mix_44k.f32")
        val job = coroutineContext
        val frames = AudioPcmDecoder.decode(
            context, Uri.parse(sourceUri), startMs, endMs, pcm, { progress(it * 0.1f) }, { !job.isActiveSafe() },
            outRate = RATE, outChannels = 2,
        )
        DubLog.i(TAG, "decode ${SystemClock.elapsedRealtime() - t0} ms, ${frames / RATE} s")

        val voice = File(workDir, "voice.wav")
        val background = File(workDir, "background.wav")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(WhisperAsr.threads())
        }
        val t1 = SystemClock.elapsedRealtime()
        try {
            env.createSession(model.absolutePath, opts).use { session ->
                DubLog.i(TAG, "model load ${SystemClock.elapsedRealtime() - t1} ms")
                val inputName = session.inputNames.first()
                val stft = StereoStft(N_FFT, HOP, DIM_F)
                val dimT = stft.frames(CHUNK)
                val shape = longArrayOf(1, 4, DIM_F.toLong(), dimT.toLong())
                val left = FloatArray(CHUNK)
                val right = FloatArray(CHUNK)
                val outL = FloatArray(CHUNK)
                val outR = FloatArray(CHUNK)
                val gen = CHUNK - 2 * TRIM
                val chunks = ((frames + gen - 1) / gen).toInt()
                RandomAccessFile(pcm, "r").use { raf ->
                    WavWriter(voice, RATE, 2).use { vw ->
                        WavWriter(background, RATE, 2).use { bw ->
                            val outV = FloatArray(gen * 2)
                            val outB = FloatArray(gen * 2)
                            for (k in 0 until chunks) {
                                coroutineContext.ensureActive()
                                val start = k.toLong() * gen
                                read(raf, start - TRIM, CHUNK, frames, left, right)
                                val spec = stft.forward(left, right, CHUNK)
                                val est = run(env, session, inputName, spec, shape)
                                // Denoise as UVR does: the model's error on the negated input cancels out.
                                for (i in spec.indices) spec[i] = -spec[i]
                                val neg = run(env, session, inputName, spec, shape)
                                for (i in est.indices) est[i] = (est[i] - neg[i]) * 0.5f * COMPENSATE
                                stft.inverse(est, CHUNK, outL, outR)
                                val n = minOf(gen.toLong(), frames - start).toInt()
                                for (i in 0 until n) {
                                    val ml = left[TRIM + i]; val mr = right[TRIM + i]
                                    val vl = outL[TRIM + i]; val vr = outR[TRIM + i]
                                    outV[2 * i] = vl; outV[2 * i + 1] = vr
                                    outB[2 * i] = ml - vl; outB[2 * i + 1] = mr - vr
                                }
                                vw.write(outV, 0, 2 * n)
                                bw.write(outB, 0, 2 * n)
                                progress(0.1f + 0.9f * (k + 1) / chunks)
                            }
                        }
                    }
                }
            }
        } finally {
            opts.close()
            pcm.delete()
        }
        DubLog.i(TAG, "separate ${SystemClock.elapsedRealtime() - t1} ms")
        Stems(voice, background, frames * 1000 / RATE)
    }

    private fun run(env: OrtEnvironment, session: OrtSession, input: String, spec: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), shape).use { t ->
            session.run(mapOf(input to t)).use { r ->
                val out = r.get(0) as OnnxTensor
                val fb = out.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }

    /** Reads [count] stereo frames starting at [from] (may be negative or past the end: zeros). */
    private fun read(raf: RandomAccessFile, from: Long, count: Int, total: Long, left: FloatArray, right: FloatArray) {
        java.util.Arrays.fill(left, 0f)
        java.util.Arrays.fill(right, 0f)
        val a = maxOf(0L, from)
        val b = minOf(total, from + count)
        if (b <= a) return
        val bytes = ByteArray(((b - a) * 8).toInt())
        raf.seek(a * 8)
        raf.readFully(bytes)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val off = (a - from).toInt()
        for (i in 0 until (b - a).toInt()) {
            left[off + i] = fb.get(2 * i)
            right[off + i] = fb.get(2 * i + 1)
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe() = this[kotlinx.coroutines.Job]?.isActive != false

    companion object {
        const val TAG = "CutVietSeparate"

        // UVR-MDX-NET-Voc_FT parameters (UVR model_data: dim_f 3072, n_fft 7680, dim_t 2^8, compensate 1.021).
        const val RATE = 44_100
        const val N_FFT = 7680
        const val HOP = 1024
        const val DIM_F = 3072
        const val CHUNK = HOP * (256 - 1)
        const val TRIM = N_FFT / 2
        const val COMPENSATE = 1.021f
    }
}
