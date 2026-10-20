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
    /** Tells people apart by their voices; null when its model is not downloaded. */
    private val diarizer: SpeakerDiarizer?,
    private val context: android.content.Context,
    private val asr: SpeechToText,
    private val translator: TextTranslator,
    private val voice: VoiceEngine,
    private val workDir: File,
) {
    enum class Step(val label: String) {
        SEPARATE("Đang tách giọng"),
        SPEAKERS("Đang nhận ra người nói"),
        ONLINE("Đang dịch online"),
        RECOGNIZE("Đang nhận dạng"),
        TRANSLATE("Đang dịch"),
        SPEAK("Đang tạo giọng"),
        MERGE("Đang ghép"),
    }

    /**
     * [distinguish]: give each person a voice of their own. [speakers]: how many people speak,
     * when the user knows; 0 finds out.
     */
    data class Settings(
        val language: SourceLanguage,
        val voiceName: String?,
        val pitch: Float = 1f,
        val distinguish: Boolean = false,
        val speakers: Int = 0,
    )

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
        DubLog.i(TAG, "start ${endMs - startMs} ms of audio, ${settings.language}, online=${online?.javaClass?.simpleName}, separation=${separator != null}, distinguish=${settings.distinguish} (speakers ${settings.speakers}, fingerprints=${diarizer != null}), ${DubLog.memory(context)}")
        val stems = separator?.let { sep ->
            timed(Step.SEPARATE, progress) { sep.separate(sourceUri, startMs, endMs) { progress(Step.SEPARATE, it) } }
        }
        // Listen to the separated voice when there is one: no music or noise for Whisper to mishear.
        val source = if (stems != null) AudioSource(android.net.Uri.fromFile(stems.voice).toString(), 0, 0, startMs)
        else AudioSource(sourceUri, startMs, endMs, startMs) // decoded from startMs, so times start there

        if (online != null) {
            try {
                val dubbed = timed(Step.ONLINE, progress) { runOnline(online, source, settings, progress) }
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

        val recognized = timed(Step.RECOGNIZE, progress) {
            asr.transcribe(source, settings.language) { progress(Step.RECOGNIZE, it) }
        }.filter { it.text.isNotBlank() && it.slotMs > 0 }
        DubLog.i(TAG, "recognized ${recognized.size} sentences")
        if (recognized.isEmpty()) return Output(emptyList(), stems, 1f, false, fallback)

        // Who says each sentence (a sentence of two people is cut in two), and whether they are
        // a man, a woman or a child; each person then gets a phone voice of their own.
        val people = if (settings.distinguish) offlineSpeakers(source, recognized, settings, progress) else recognized.map { it to null }
        val segments = people.map { it.first }
        val cast = PhoneCast(settings)

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
                    val (s, label) = people[i].second?.let { cast.of(it.key, it.gender, null) } ?: (settings to null)
                    cast.speak(i, seg, texts[i].trim(), room[i], s, reword = true)?.copy(speaker = label)?.let(out::add)
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

    private suspend fun runOnline(dubber: OnlineDubber, source: AudioSource, s: Settings, stepProgress: (Step, Float) -> Unit): List<DubbedSegment> {
        val progress = { p: Float -> stepProgress(Step.ONLINE, p) }
        val voice = decode16k(source) { progress(it * 0.1f) }
        var ranges = SpeechDetector.detect(voice, AudioPcmDecoder.SAMPLE_RATE)
        DubLog.i(TAG, "online: ${ranges.size} speech segments")
        if (ranges.isEmpty()) throw OnlineUnavailableException("Không tìm thấy đoạn lời nói nào để gửi online")
        // Who speaks each segment, from voice fingerprints; a segment of two people is cut in two.
        var speakers: List<Int?>? = null
        if (s.distinguish && dubber.tellsSpeakersApart) {
            diarize(voice, s, stepProgress)?.let { d ->
                val split = SpeakerTurns.splitRanges(ranges, d, voice)
                DubLog.i(TAG, "online: ${d.speakers} people, ${ranges.size} → ${split.size} segments")
                ranges = split.map { it.first }
                speakers = split.map { it.second }
            }
        }
        val lines = dubber.dub(voice, ranges, s.language, speakers) { progress(0.1f + 0.85f * it) }
        if (lines.isEmpty()) throw OnlineUnavailableException(dubber.notice ?: "Gemini không trả về giọng dịch nào")
        // Lines translated online but not voiced (quota) are read by the phone, a voice per person.
        val cast = PhoneCast(s)
        val out = ArrayList<DubbedSegment>()
        lines.forEachIndexed { i, l ->
            val start = l.startMs + source.offsetMs
            val slot = l.endMs - l.startMs
            val file = l.file
            if (file != null) {
                out += DubbedSegment(start, slot, l.source, l.text, file, l.durationMs, 1f, l.speed, l.speaker)
            } else {
                val seg = SpeechSegment(start, start + slot, l.source)
                val ps = if (s.distinguish && (l.speaker != null || l.gender != null)) cast.of(l.speaker ?: l.gender!!.name, l.gender, l.speaker).first else s
                cast.speak(i, seg, l.text, l.roomMs, ps, reword = false)?.copy(speaker = l.speaker)?.let(out::add)
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

    /** Kept so the offline fallback does not redo what the online branch found. */
    private var diarized: Diarization? = null

    /** Who speaks when in [voice] (16 kHz); null when it cannot be worked out on this phone. */
    private suspend fun diarize(voice: FloatArray, s: Settings, progress: (Step, Float) -> Unit): Diarization? {
        diarized?.let { return it }
        val d = diarizer ?: return null
        return try {
            timed(Step.SPEAKERS, progress) { d.diarize(voice, s.speakers) { progress(Step.SPEAKERS, it) } }.takeIf { it.speakers > 0 }?.also { diarized = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DubLog.w(TAG, "telling speakers apart failed, going by pitch only", e)
            null
        }
    }

    /** The person behind a line: [key] tells people apart, [gender] picks their pitch. */
    private class Person(val key: String, val gender: Gender?)

    /**
     * Each recognised sentence with who says it. With voice fingerprints a sentence of two people
     * is cut between their words and each person's gender comes from the pitch of all they say;
     * without, each sentence is judged by its own pitch.
     */
    private suspend fun offlineSpeakers(
        source: AudioSource,
        segments: List<SpeechSegment>,
        s: Settings,
        progress: (Step, Float) -> Unit,
    ): List<Pair<SpeechSegment, Person?>> {
        val t0 = SystemClock.elapsedRealtime()
        val voice = decode16k(source) {}
        val rate = AudioPcmDecoder.SAMPLE_RATE
        fun f0(seg: SpeechSegment): Float? {
            val from = ((seg.startMs - source.offsetMs) * rate / 1000).toInt()
            val to = ((seg.endMs - source.offsetMs) * rate / 1000).toInt()
            return PitchTracker.medianF0(voice, rate, from, to)
        }
        val d = diarize(voice, s, progress)
        val out = if (d != null) {
            val split = SpeakerTurns.splitSentences(segments, d, source.offsetMs).filter { it.first.text.isNotBlank() && it.first.slotMs > 0 }
            val genders = (0 until d.speakers).associateWith { p ->
                val f0s = split.filter { it.second == p }.take(12).mapNotNull { f0(it.first) }.sorted()
                f0s.getOrNull(f0s.size / 2)?.let(PitchTracker::gender)
            }
            split.map { (seg, p) ->
                seg to (if (p != null) Person("p$p", genders[p]) else f0(seg)?.let(PitchTracker::gender)?.let { Person(it.name, it) })
            }
        } else {
            segments.map { seg -> seg to f0(seg)?.let(PitchTracker::gender)?.let { Person(it.name, it) } }
        }
        DubLog.i(
            TAG,
            "offline speakers in ${SystemClock.elapsedRealtime() - t0} ms (fingerprints=${d != null}): ${segments.size} → ${out.size} sentences, " +
                out.groupingBy { it.second?.let { p -> "${p.key}/${p.gender?.label}" } ?: "?" }.eachCount(),
        )
        return out
    }

    /**
     * A phone voice for each person: the pitch of their gender, a little higher or lower for each
     * further person of that gender, and another of the phone's Vietnamese voices when it has
     * several. Labels read "Nam 1", "Nữ 2"; a person known only by gender is labelled by it.
     */
    private inner class PhoneCast(private val base: Settings) {
        private val others = voice.voices.value.filter { !it.needsNetwork && it.name != base.voiceName }.map { it.name }
        private val people = LinkedHashMap<String, Pair<Settings, String?>>()
        private val perGender = HashMap<Gender, Int>()

        fun of(key: String, gender: Gender?, label: String?): Pair<Settings, String?> = people.getOrPut(key) {
            val person = people.size
            val n = gender?.let { g -> ((perGender[g] ?: 0) + 1).also { perGender[g] = it } } ?: 1
            val pitch = base.pitch * pitchFor(gender) * PERSON_PITCH[(n - 1) % PERSON_PITCH.size]
            val voiceName = if (person == 0 || others.isEmpty()) base.voiceName else others[(person - 1) % others.size]
            val name = label ?: when {
                gender == null -> if (key.startsWith("p")) "Người ${person + 1}" else null
                key == gender.name -> gender.label
                else -> "${gender.label} $n"
            }
            base.copy(pitch = pitch.coerceIn(0.6f, 1.7f), voiceName = voiceName) to name
        }

        /** Speaks with the person's voice, or with the chosen voice if theirs fails on this phone. */
        suspend fun speak(index: Int, seg: SpeechSegment, text: String, roomMs: Long, s: Settings, reword: Boolean): DubbedSegment? =
            this@DubbingPipeline.speak(index, seg, text, roomMs, s, reword)
                ?: if (s.voiceName != base.voiceName) this@DubbingPipeline.speak(index, seg, text, roomMs, s.copy(voiceName = base.voiceName), reword) else null
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

    companion object {
        const val TAG = "CutVietDub"

        /** Pitch steps for the second, third… person of the same gender. */
        private val PERSON_PITCH = floatArrayOf(1f, 1.1f, 0.9f, 1.18f, 0.84f)

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
