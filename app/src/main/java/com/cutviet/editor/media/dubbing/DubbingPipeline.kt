package com.cutviet.editor.media.dubbing

import android.os.SystemClock
import android.util.Log
import com.cutviet.editor.media.VoiceEngine
import java.io.File

/**
 * Runs speech to text → translation → fitted text to speech for the audio of one source file.
 * Each step reports its own time to the log ("CutVietDub").
 */
class DubbingPipeline(
    private val asr: SpeechToText,
    private val translator: TextTranslator,
    private val voice: VoiceEngine,
    private val workDir: File,
) {
    enum class Step(val label: String) {
        RECOGNIZE("Đang nhận dạng"),
        TRANSLATE("Đang dịch"),
        SPEAK("Đang tạo giọng"),
        MERGE("Đang ghép"),
    }

    data class Settings(val language: SourceLanguage, val voiceName: String?, val pitch: Float = 1f)

    /** Time spent in each step, in ms. */
    val timings = linkedMapOf<Step, Long>()

    suspend fun run(sourceUri: String, settings: Settings, progress: (Step, Float) -> Unit): List<DubbedSegment> {
        workDir.mkdirs()
        val segments = timed(Step.RECOGNIZE) {
            asr.transcribe(sourceUri, settings.language) { progress(Step.RECOGNIZE, it) }
        }.filter { it.text.isNotBlank() && it.slotMs > 0 }
        if (segments.isEmpty()) return emptyList()

        val texts = timed(Step.TRANSLATE) {
            translator.translate(segments, settings.language) { progress(Step.TRANSLATE, it) }
        }
        require(texts.size == segments.size) { "translator returned ${texts.size} of ${segments.size} lines" }

        return timed(Step.SPEAK) {
            val out = ArrayList<DubbedSegment>()
            segments.forEachIndexed { i, seg ->
                progress(Step.SPEAK, i.toFloat() / segments.size)
                speak(i, seg, texts[i].trim(), settings)?.let(out::add)
            }
            out
        }
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
        if (!fit.withinTolerance) Log.w(TAG, "segment $index off by ${fit.durationMs - seg.slotMs} ms at rate ${fit.rate}")
        return DubbedSegment(seg.startMs, seg.slotMs, seg.text, line, file, fit.durationMs, fit.rate)
    }

    private inline fun <T> timed(step: Step, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val ms = SystemClock.elapsedRealtime() - t0
            timings[step] = ms
            Log.i(TAG, "${step.name}: $ms ms")
        }
    }

    companion object {
        const val TAG = "CutVietDub"
    }
}
