package com.cutviet.editor.media.denoise

import android.content.Context
import android.os.SystemClock
import com.cutviet.editor.media.dsp.SincResampler
import com.cutviet.editor.media.dsp.WavWriter
import com.cutviet.editor.media.dubbing.DubLog
import com.cutviet.editor.media.dubbing.DubModel
import com.cutviet.editor.media.dubbing.Stems
import com.cutviet.editor.media.dubbing.StepUnavailableException
import com.cutviet.editor.media.dubbing.VoiceSeparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Lọc tiếng ồn": a clip's sound split into a clean voice track and everything else.
 *  1. MDX-Net (the dub's voice separation) takes the voice out of music, noise and ambience.
 *  2. [Keep.MAIN] only: VoxLock keeps the main speaker (learnt from the loudest 3 s in the first
 *     30 s of speech) and removes other people's voices, joined back to full band by [MainVoiceMix].
 * Voice + background always add up to the original sound, so nothing is lost: the background track
 * can be turned back up at any time.
 */
class DenoisePipeline(private val context: Context, private val workDir: File) {
    enum class Keep { MAIN, ALL }

    enum class Step(val label: String) {
        SEPARATE("Đang tách giọng khỏi tiếng ồn"),
        ISOLATE("Đang giữ lại giọng chính"),
        MIX("Đang ghép âm thanh"),
    }

    suspend fun run(uri: String, startMs: Long, endMs: Long, keep: Keep, progress: (Step, Float) -> Unit): Stems = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val mdx = DubModel.MDX_VOC_FT
        if (!mdx.isReady(context)) throw StepUnavailableException("Chưa tải model tách giọng (${mdx.label})")
        if (keep == Keep.MAIN) {
            for (m in listOf(DubModel.VOXLOCK_ENROLL, DubModel.VOXLOCK_CHUNK)) {
                if (!m.isReady(context)) throw StepUnavailableException("Chưa tải model ${m.label}")
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val stems = VoiceSeparator(context, mdx.file(context), workDir).separate(uri, startMs, endMs) { progress(Step.SEPARATE, it) }
        DubLog.i(TAG, "separate ${SystemClock.elapsedRealtime() - t0} ms, ${stems.durationMs} ms of sound")
        if (keep == Keep.ALL) return@withContext stems
        mainVoice(stems, progress)
    }

    private suspend fun mainVoice(stems: Stems, progress: (Step, Float) -> Unit): Stems {
        val t0 = SystemClock.elapsedRealtime()
        val v16 = FloatFile(File(workDir, "voice_16k.f32"))
        val y16 = FloatFile(File(workDir, "main_16k.f32"))
        val up = FloatFile(File(workDir, "main_44k.f32"))
        val clean = File(workDir, "main_voice.wav")
        val rest = File(workDir, "rest.wav")
        try {
            var total44 = 0L
            WavReader(stems.voice, CH).use { voice ->
                total44 = voice.frames
                // 1. The separated voice in mono at 16 kHz for VoxLock.
                val rs = SincResampler(RATE, VOX_RATE, 1)
                val block = FloatArray(BLOCK * CH)
                val mono = FloatArray(BLOCK)
                val one = FloatArray(1)
                var pos = 0L
                while (pos < total44) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(BLOCK.toLong(), total44 - pos).toInt()
                    voice.read(pos, block, n)
                    for (i in 0 until n) mono[i] = (block[2 * i] + block[2 * i + 1]) * 0.5f
                    rs.push(mono, n) { f -> one[0] = f[0]; v16.write(one, 1) }
                    pos += n
                }
                rs.finish(total44) { f -> one[0] = f[0]; v16.write(one, 1) }
                v16.finish()
            }
            val total16 = v16.length
            // 2. VoxLock.
            VoiceIsolator(DubModel.VOXLOCK_ENROLL.file(context), DubModel.VOXLOCK_CHUNK.file(context)).use { vox ->
                val head = FloatArray(minOf(total16, SEARCH_SECONDS.toLong() * VOX_RATE).toInt())
                v16.read(0, head, head.size)
                val sample = VoiceIsolator.loudest(head, VOX_RATE, vox.sampleSeconds, SEARCH_SECONDS)
                    ?: throw StepUnavailableException("Đoạn video quá ngắn để nhận ra giọng chính (cần ít nhất ${vox.sampleSeconds} giây)")
                vox.isolate(total16, sample, { from, out, n -> v16.read(from, out, n) }, { x, n -> y16.write(x, n) }) {
                    progress(Step.ISOLATE, it)
                }
                y16.finish()
            }
            DubLog.i(TAG, "voxlock ${SystemClock.elapsedRealtime() - t0} ms")
            // 3. How much VoxLock took away, per 10 ms.
            val gains = MainVoiceMix.bandGains(total16, VOX_RATE, { f, o, n -> v16.read(f, o, n) }, { f, o, n -> y16.read(f, o, n) })
            // 4. VoxLock's output back at 44.1 kHz.
            run {
                val rs = SincResampler(VOX_RATE, RATE, 1)
                val block = FloatArray(BLOCK)
                val one = FloatArray(1)
                var pos = 0L
                while (pos < total16) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(BLOCK.toLong(), total16 - pos).toInt()
                    y16.read(pos, block, n)
                    rs.push(block, n) { f -> one[0] = f[0]; up.write(one, 1) }
                    pos += n
                }
                rs.finish(total16) { f -> one[0] = f[0]; up.write(one, 1) }
                up.finish()
            }
            // 5. Full-band main voice, and the rest = original - main voice.
            WavReader(stems.voice, CH).use { voice ->
                WavReader(stems.background, CH).use { bg ->
                    WavWriter(clean, RATE, CH).use { cw ->
                        WavWriter(rest, RATE, CH).use { rw ->
                            var vb = FloatArray(0)
                            var bb = FloatArray(0)
                            var ob = FloatArray(0)
                            MainVoiceMix.combine(
                                total44, RATE, CH, gains,
                                readVoice = { f, o, n -> voice.read(f, o, n) },
                                readIsolated = { f, o, n -> up.read(f, o, n) },
                                emit = { x, n, first ->
                                    if (vb.size < n * CH) { vb = FloatArray(n * CH); bb = FloatArray(n * CH); ob = FloatArray(n * CH) }
                                    voice.read(first, vb, n)
                                    bg.read(first, bb, n)
                                    for (i in 0 until n * CH) ob[i] = bb[i] + vb[i] - x[i]
                                    cw.write(x, 0, n * CH)
                                    rw.write(ob, 0, n * CH)
                                },
                                progress = { progress(Step.MIX, it) },
                            )
                        }
                    }
                }
            }
        } finally {
            v16.close(); y16.close(); up.close()
            v16.file.delete(); y16.file.delete(); up.file.delete()
        }
        stems.voice.delete()
        stems.background.delete()
        val voice = File(workDir, "voice.wav").also { clean.renameTo(it) }
        val background = File(workDir, "background.wav").also { rest.renameTo(it) }
        DubLog.i(TAG, "main voice ${SystemClock.elapsedRealtime() - t0} ms")
        return Stems(voice, background, stems.durationMs)
    }

    companion object {
        const val TAG = "CutVietDenoise"
        private const val RATE = VoiceSeparator.RATE
        private const val VOX_RATE = 16_000
        private const val CH = 2
        private const val BLOCK = 1 shl 15
        /** Where the main speaker's voice sample is looked for. */
        private const val SEARCH_SECONDS = 30
    }
}
