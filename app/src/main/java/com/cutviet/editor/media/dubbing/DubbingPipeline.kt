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
    /** Online dubbing; when it fails the offline engines take over. */
    private val online: OnlineDubber?,
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

    /** [distinguish]: tell speakers apart (offline: men, women and children by the pitch of their voice). */
    data class Settings(val language: SourceLanguage, val voiceName: String?, val pitch: Float = 1f, val distinguish: Boolean = false)

    class Output(
        val segments: List<DubbedSegment>,
        val stems: Stems?,
        val gain: Float,
        val usedOnline: Boolean,
        val fallbackReason: String?,
        /** Something the user should know about a dub that went through. */
        val notice: String? = null,
    )

    /** Time spent in each step, in ms. */
    val timings = linkedMapOf<Step, Long>()

    /** Why the online branch was given up, as soon as that happens (shown while offline runs). */
    @Volatile
    var fallbackReason: String? = null
        private set

    suspend fun run(sourceUri: String, startMs: Long, endMs: Long, settings: Settings, progress: (Step, Float) -> Unit): Output {
        workDir.mkdirs()
        DubLog.i(TAG, "start ${endMs - startMs} ms of audio, ${settings.language}, online=${online?.javaClass?.simpleName}, separation=${separator != null}, distinguish=${settings.distinguish}, ${DubLog.memory(context)}")
        val stems = separator?.let { sep ->
            timed(Step.SEPARATE, progress) { sep.separate(sourceUri, startMs, endMs) { progress(Step.SEPARATE, it) } }
        }
        // Listen to the separated voice when there is one: no music or noise for Whisper to mishear.
        val source = if (stems != null) AudioSource(android.net.Uri.fromFile(stems.voice).toString(), 0, 0, startMs)
        else AudioSource(sourceUri, startMs, endMs, startMs) // decoded from startMs, so times start there

        if (online != null) {
            try {
                val dubbed = timed(Step.ONLINE, progress) { runOnline(online, source, settings) { progress(Step.ONLINE, it) } }
                val gain = timed(Step.MERGE, progress) { Loudness.gainTo(TARGET_LUFS, dubbed.map { it.file }) }
                DubLog.i(TAG, "online done: ${dubbed.size} lines, ${dubbed.mapNotNull { it.speaker }.distinct().size} voices, notice=${online.notice}")
                return Output(dubbed, stems, gain, usedOnline = true, fallbackReason = null, notice = online.notice)
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

        // Men, women and children told apart by pitch, each read at a pitch of their own.
        val genders: List<Gender?> = if (settings.distinguish) speakerGenders(source, segments) else segments.map { null }

        // Each line may use the silence after its sentence; the translation is asked to fit that
        // time at this voice's real speed.
        val room = roomFor(segments.map { it.startMs to it.slotMs }, endMs)
        val speed = voiceSpeed(settings)
        val budgets = segments.indices.map { TranslationPrompt.syllableBudget(room[it], speed) }
        val dubbed = try {
            val texts = timed(Step.TRANSLATE, progress) {
                translator.translate(segments, settings.language, budgets) { progress(Step.TRANSLATE, it) }
            }
            require(texts.size == segments.size) { "translator returned ${texts.size} of ${segments.size} lines" }
            timed(Step.SPEAK, progress) {
                val out = ArrayList<DubbedSegment>()
                segments.forEachIndexed { i, seg ->
                    progress(Step.SPEAK, i.toFloat() / segments.size)
                    val g = genders[i]
                    speak(i, seg, texts[i].trim(), room[i], settings.forGender(g))?.copy(speaker = g?.label)?.let(out::add)
                }
                out
            }
        } finally {
            translator.close()
        }
        val gain = timed(Step.MERGE, progress) {
            Loudness.gainTo(TARGET_LUFS, dubbed.map { it.file })
        }
        return Output(dubbed, stems, gain, false, fallback)
    }

    private suspend fun runOnline(dubber: OnlineDubber, source: AudioSource, s: Settings, progress: (Float) -> Unit): List<DubbedSegment> {
        val voice = decode16k(source) { progress(it * 0.1f) }
        val ranges = SpeechDetector.detect(voice, AudioPcmDecoder.SAMPLE_RATE)
        DubLog.i(TAG, "online: ${ranges.size} speech segments")
        if (ranges.isEmpty()) throw OnlineUnavailableException("Không tìm thấy đoạn lời nói nào để gửi online")
        val lines = dubber.dub(voice, ranges, s.language) { progress(0.1f + 0.85f * it) }
        if (lines.isEmpty()) throw OnlineUnavailableException(dubber.notice ?: "Gemini không trả về giọng dịch nào")
        // Lines translated online but not voiced (quota) are read by the phone, at the pitch of the speaker.
        val out = ArrayList<DubbedSegment>()
        lines.forEachIndexed { i, l ->
            val start = l.startMs + source.offsetMs
            val slot = l.endMs - l.startMs
            val file = l.file
            if (file != null) {
                out += DubbedSegment(start, slot, l.source, l.text, file, l.durationMs, 1f, l.speed, l.speaker)
            } else {
                val seg = SpeechSegment(start, start + slot, l.source)
                val g = if (s.distinguish) l.gender else null
                speak(i, seg, l.text, l.roomMs, s.forGender(g), reword = false)?.copy(speaker = l.speaker)?.let(out::add)
            }
            progress(0.95f + 0.05f * (i + 1) / lines.size)
        }
        return out
    }

    /** [source] as 16 kHz mono floats. */
    private suspend fun decode16k(source: AudioSource, progress: (Float) -> Unit): FloatArray {
        val pcm = File(workDir, "voice_16k.f32")
        val job = kotlinx.coroutines.currentCoroutineContext()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AudioPcmDecoder.decode(context, android.net.Uri.parse(source.uri), source.startMs, source.endMs, pcm, progress, {
                job[kotlinx.coroutines.Job]?.isActive == false
            })
        }
        return readFloats(pcm).also { pcm.delete() }
    }

    /** Man, woman or child for each recognised sentence, from the pitch of the voice. */
    private suspend fun speakerGenders(source: AudioSource, segments: List<SpeechSegment>): List<Gender?> {
        val t0 = SystemClock.elapsedRealtime()
        val voice = decode16k(source) {}
        val rate = AudioPcmDecoder.SAMPLE_RATE
        val out = segments.map { seg ->
            val from = ((seg.startMs - source.offsetMs) * rate / 1000).toInt()
            val to = ((seg.endMs - source.offsetMs) * rate / 1000).toInt()
            PitchTracker.medianF0(voice, rate, from, to)?.let(PitchTracker::gender)
        }
        DubLog.i(TAG, "speakers by pitch in ${SystemClock.elapsedRealtime() - t0} ms: " + out.groupingBy { it?.label ?: "?" }.eachCount())
        return out
    }

    private fun readFloats(f: File): FloatArray {
        val bytes = f.readBytes()
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** Speaks one line into its time; a line still too long at 1.15x is reworded shorter once ([reword]). */
    private suspend fun speak(index: Int, seg: SpeechSegment, text: String, roomMs: Long, s: Settings, reword: Boolean = true): DubbedSegment? {
        if (text.isEmpty()) return null
        // One sentence is far below the engine's input limit; cut defensively anyway.
        var line = text.take(voice.maxInputLength)
        var best = render(index, 0, line, seg.slotMs, roomMs, s) ?: return null
        val limit = AudioFitter.upper(seg.slotMs, roomMs)
        if (reword && best.fit.durationMs > limit) {
            val syllables = VoiceEngine.syllableCount(line)
            val target = (syllables * limit.toDouble() / best.fit.durationMs * 0.9).toInt().coerceAtLeast(1)
            val shorter = translator.shorten(seg.text, line, s.language, target)?.take(voice.maxInputLength)
            if (shorter != null && VoiceEngine.syllableCount(shorter) < syllables) {
                val again = render(index, 1, shorter, seg.slotMs, roomMs, s)
                DubLog.i(TAG, "segment $index reworded $syllables→${VoiceEngine.syllableCount(shorter)} syllables: ${again?.fit?.durationMs} ms")
                if (again != null && AudioFitter.miss(again.fit.durationMs, seg.slotMs, roomMs) < AudioFitter.miss(best.fit.durationMs, seg.slotMs, roomMs)) {
                    best.file.delete()
                    best = again
                    line = shorter
                } else {
                    again?.file?.delete()
                }
            }
        }
        val fit = best.fit
        DubLog.i(TAG, "segment $index rate=${fit.rate} measured=${fit.durationMs}ms slot=${seg.slotMs}ms room=${roomMs}ms attempts=${fit.attempts}")
        if (!fit.withinTolerance) DubLog.w(TAG, "segment $index misses its time by ${AudioFitter.miss(fit.durationMs, seg.slotMs, roomMs)} ms at rate ${fit.rate}")
        return DubbedSegment(seg.startMs, seg.slotMs, seg.text, line, best.file, fit.durationMs, fit.rate)
    }

    private class Rendered(val file: File, val fit: AudioFitter.Fit)

    /** Closed-loop synthesis of [line]; the engine's silence before and after is cut off. */
    private suspend fun render(index: Int, take: Int, line: String, slotMs: Long, roomMs: Long, s: Settings): Rendered? {
        val files = HashMap<Float, File>()
        val fit = AudioFitter.fit(voice.estimateMs(line, s.voiceName, 1f), slotMs, roomMs) { rate ->
            val f = File(workDir, "seg_${index}_${take}_${(rate * 1000).toInt()}.wav")
            voice.synthesize(line, s.voiceName, s.pitch, rate, f)?.let { ms -> WavTrim.trim(f) ?: ms }?.also { files[rate] = f }
        } ?: return null
        val file = files[fit.rate] ?: return null
        files.values.filter { it != file }.forEach { it.delete() }
        return Rendered(file, fit)
    }

    /** Syllables per second of the chosen voice at rate 1.0, measured on a test sentence. */
    private suspend fun voiceSpeed(s: Settings): Double {
        val f = File(workDir, "calibrate.wav")
        val ms = voice.synthesize(CALIBRATION, s.voiceName, s.pitch, 1f, f)?.let { ms -> WavTrim.trim(f) ?: ms }
        f.delete()
        val seconds = (ms ?: 0) / 1000.0 - VoiceEngine.pauseSeconds(CALIBRATION)
        val sps = if (seconds < 1.0) VoiceEngine.DEFAULT_SYLLABLES_PER_SECOND
        else (VoiceEngine.syllableCount(CALIBRATION) / seconds).coerceIn(2.5, 7.0)
        DubLog.i(TAG, "voice speed ${"%.2f".format(sps)} syllables/s (test sentence ${ms} ms)")
        return sps
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

    private fun Settings.forGender(g: Gender?) = if (g == null) this else copy(pitch = (pitch * pitchFor(g)).coerceIn(0.6f, 1.7f))

    companion object {
        const val TAG = "CutVietDub"

        /** Pitch on top of the chosen voice for a man, a woman or a child, so they sound apart. */
        fun pitchFor(g: Gender?): Float = when (g) {
            Gender.MALE -> 0.78f
            Gender.FEMALE -> 1.05f
            Gender.CHILD -> 1.3f
            null -> 1f
        }
        private const val CALIBRATION = "Xin chào các bạn, hôm nay chúng ta cùng nhau nói về một chuyện rất thú vị."
        /** Kept free before the next line starts. */
        const val GAP_MS = 80L
        /** Furthest a line may run past its own sentence into the silence after it. */
        const val MAX_SPILL_MS = 2_000L

        /**
         * Time each line ([start] to [slot] pairs, in order) may take: until the next line starts
         * less [GAP_MS], at most [MAX_SPILL_MS] past its own slot, never less than the slot.
         */
        fun roomFor(lines: List<Pair<Long, Long>>, endMs: Long): List<Long> = lines.mapIndexed { i, (start, slot) ->
            val next = lines.getOrNull(i + 1)?.first?.minus(GAP_MS) ?: if (endMs > start) endMs else start + slot + MAX_SPILL_MS
            maxOf(slot, minOf(next - start, slot + MAX_SPILL_MS))
        }
        /** Loudness of the Vietnamese voice (EBU R128 streaming level). */
        const val TARGET_LUFS = -16.0
    }
}
