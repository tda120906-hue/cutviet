package com.cutviet.editor.media.dubbing

import android.os.SystemClock
import com.cutviet.editor.media.VoiceEngine
import java.io.File

/**
 * Runs voice separation → speech to text → translation → fitted text to speech → loudness
 * normalisation for [startMs]..[endMs] of one source file. Each step logs its time ("CutVietDub").
 */
class DubbingPipeline(
    private val separator: VoiceSeparator?,
    /** Online speech-to-speech; when it fails the offline engines take over. */
    private val online: GeminiLiveTranslator?,
    private val context: android.content.Context,
    private val asr: SpeechToText,
    private val translator: TextTranslator,
    private val voice: VoiceEngine,
    private val workDir: File,
) {
    enum class Step(val label: String) {
        SEPARATE("Đang tách giọng"),
        ONLINE("Đang dịch online"),
        RECOGNIZE("Đang nhận dạng"),
        TRANSLATE("Đang dịch"),
        SPEAK("Đang tạo giọng"),
        MERGE("Đang ghép"),
    }

    data class Settings(val language: SourceLanguage, val voiceName: String?, val pitch: Float = 1f)

    class Output(val segments: List<DubbedSegment>, val stems: Stems?, val gain: Float, val usedOnline: Boolean, val fallbackReason: String?)

    /** Time spent in each step, in ms. */
    val timings = linkedMapOf<Step, Long>()

    /** Why the online branch was given up, as soon as that happens (shown while offline runs). */
    @Volatile
    var fallbackReason: String? = null
        private set

    suspend fun run(sourceUri: String, startMs: Long, endMs: Long, settings: Settings, progress: (Step, Float) -> Unit): Output {
        workDir.mkdirs()
        DubLog.i(TAG, "start ${endMs - startMs} ms of audio, ${settings.language}, online=${online != null}, separation=${separator != null}, ${DubLog.memory(context)}")
        val stems = separator?.let { sep ->
            timed(Step.SEPARATE, progress) { sep.separate(sourceUri, startMs, endMs) { progress(Step.SEPARATE, it) } }
        }
        // Listen to the separated voice when there is one: no music or noise for Whisper to mishear.
        val source = if (stems != null) AudioSource(android.net.Uri.fromFile(stems.voice).toString(), 0, 0, startMs)
        else AudioSource(sourceUri, startMs, endMs, startMs) // decoded from startMs, so times start there

        if (online != null) {
            try {
                val dubbed = timed(Step.ONLINE, progress) { runOnline(online, source) { progress(Step.ONLINE, it) } }
                val gain = timed(Step.MERGE, progress) { Loudness.gainTo(TARGET_LUFS, dubbed.map { it.file }) }
                DubLog.i(TAG, "online done: ${dubbed.size} lines")
                return Output(dubbed, stems, gain, usedOnline = true, fallbackReason = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever went wrong online (quota, key, network, an answer we could not read),
                // the offline engines still do the job.
                val reason = (e as? OnlineUnavailableException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
                DubLog.w(TAG, "online branch failed, using offline: $reason")
                fallbackReason = reason
            }
        }
        val fallback = fallbackReason

        val segments = timed(Step.RECOGNIZE, progress) {
            asr.transcribe(source, settings.language) { progress(Step.RECOGNIZE, it) }
        }.filter { it.text.isNotBlank() && it.slotMs > 0 }
        DubLog.i(TAG, "recognized ${segments.size} sentences")
        if (segments.isEmpty()) return Output(emptyList(), stems, 1f, false, fallback)

        val texts = timed(Step.TRANSLATE, progress) {
            translator.translate(segments, settings.language) { progress(Step.TRANSLATE, it) }
        }
        require(texts.size == segments.size) { "translator returned ${texts.size} of ${segments.size} lines" }

        val dubbed = timed(Step.SPEAK, progress) {
            val out = ArrayList<DubbedSegment>()
            segments.forEachIndexed { i, seg ->
                progress(Step.SPEAK, i.toFloat() / segments.size)
                speak(i, seg, texts[i].trim(), settings)?.let(out::add)
            }
            out
        }
        val gain = timed(Step.MERGE, progress) {
            Loudness.gainTo(TARGET_LUFS, dubbed.map { it.file })
        }
        return Output(dubbed, stems, gain, false, fallback)
    }

    private suspend fun runOnline(gemini: GeminiLiveTranslator, source: AudioSource, progress: (Float) -> Unit): List<DubbedSegment> {
        val pcm = File(workDir, "online_16k.f32")
        val job = kotlinx.coroutines.currentCoroutineContext()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AudioPcmDecoder.decode(context, android.net.Uri.parse(source.uri), source.startMs, source.endMs, pcm, { progress(it * 0.1f) }, {
                job[kotlinx.coroutines.Job]?.isActive == false
            })
        }
        val voice = readFloats(pcm).also { pcm.delete() }
        val ranges = SpeechDetector.detect(voice, AudioPcmDecoder.SAMPLE_RATE)
        DubLog.i(TAG, "online: ${ranges.size} speech segments")
        if (ranges.isEmpty()) throw OnlineUnavailableException("Không tìm thấy đoạn lời nói nào để gửi online")
        val dubbed = gemini.dub(voice, ranges, source.offsetMs) { progress(0.1f + 0.9f * it) }
        if (dubbed.isEmpty()) throw OnlineUnavailableException("Gemini không trả về giọng dịch nào")
        return dubbed
    }

    private fun readFloats(f: File): FloatArray {
        val bytes = f.readBytes()
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private suspend fun speak(index: Int, seg: SpeechSegment, text: String, s: Settings): DubbedSegment? {
        if (text.isEmpty()) return null
        // One sentence is far below the engine's input limit; cut defensively anyway.
        val line = text.take(voice.maxInputLength)
        var lastFile: File? = null
        val files = HashMap<Float, File>()
        val fit = AudioFitter.fit(voice.estimateMs(line, s.voiceName, 1f), seg.slotMs) { rate ->
            val f = File(workDir, "seg_${index}_${(rate * 1000).toInt()}.wav")
            voice.synthesize(line, s.voiceName, s.pitch, rate, f)?.also { files[rate] = f; lastFile = f }
        } ?: return null
        val file = files[fit.rate] ?: lastFile ?: return null
        files.values.filter { it != file }.forEach { it.delete() }
        DubLog.i(TAG, "segment $index rate=${fit.rate} measured=${fit.durationMs}ms slot=${seg.slotMs}ms attempts=${fit.attempts}")
        if (!fit.withinTolerance) DubLog.w(TAG, "segment $index off by ${fit.durationMs - seg.slotMs} ms at rate ${fit.rate}")
        return DubbedSegment(seg.startMs, seg.slotMs, seg.text, line, file, fit.durationMs, fit.rate)
    }

    /** Runs one step: shows it right away (at 0%) and logs how long it took. */
    private inline fun <T> timed(step: Step, progress: (Step, Float) -> Unit, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        progress(step, 0f)
        DubLog.i(TAG, "${step.name} start")
        try {
            return block()
        } finally {
            val ms = SystemClock.elapsedRealtime() - t0
            timings[step] = ms
            DubLog.i(TAG, "${step.name}: $ms ms")
        }
    }

    companion object {
        const val TAG = "CutVietDub"
        /** Loudness of the Vietnamese voice (EBU R128 streaming level). */
        const val TARGET_LUFS = -16.0
    }
}
