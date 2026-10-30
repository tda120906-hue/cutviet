package com.cutviet.editor.media.dubbing

import android.content.Context
import android.net.Uri
import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.model.DubTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dubs one line again ("Lồng lại câu này"): listens to [Request.startMs]..[Request.endMs] of the
 * video once more and translates it in the context of the lines around it, or takes the text the
 * user corrected, then reads it in the line's voice (its Gemini voice when online, else the
 * phone's voice at the line's pitch). The new line keeps to the time it had, sped up at most to
 * the user's limit.
 */
class LineDubber(private val context: Context, private val workDir: File, private val voice: VoiceEngine) {

    class Request(
        val sourceUri: String,
        val startMs: Long,
        val endMs: Long,
        /** Time the line may take on the timeline before the next line. */
        val roomMs: Long,
        val language: SourceLanguage,
        /** Text corrected by the user: read as is, without listening again. Null: listen and translate again. */
        val text: String?,
        val sourceText: String,
        /** Lines around this one ("source → translation"), for the translation. */
        val around: String,
        val voice: LineVoice,
        val phoneVoice: String?,
        /** Label of the person ("Nam 1"), kept in the track's name. */
        val speaker: String?,
    )

    class Output(
        val file: File,
        val durationMs: Long,
        val text: String,
        val sourceText: String,
        val emotion: String,
        /** Gemini voice that read it; null when the phone did. */
        val geminiVoice: String?,
        val pitch: Float,
        val playbackSpeed: Float,
        val gain: Float,
        val usedOnline: Boolean,
        /** Why online was not used although the line has a Gemini voice, or null. */
        val note: String?,
    )

    enum class Step(val label: String) {
        SEPARATE("Đang tách giọng"),
        LISTEN("Đang nghe lại"),
        TRANSLATE("Đang dịch lại"),
        SPEAK("Đang đọc lại"),
    }

    suspend fun run(r: Request, progress: (Step, Float) -> Unit): Output {
        workDir.mkdirs()
        val s = DubSettings(context)
        // One line is a few kB of audio: online also on mobile data, unless the mode is Offline.
        val why = Engines.whyNotOnline(context, offlineReady = false)
        val api = if (why == null) GeminiApi(s.geminiKey, s.simulateQuota) else null
        DubLog.i(TAG, "line ${r.startMs}-${r.endMs}ms room=${r.roomMs}ms, ${if (r.text != null) "edited text" else "listen again"}, voice=${r.voice.gemini ?: "phone"}, online=${why ?: "yes"}")
        var note: String? = null
        try {
            var text = r.text?.trim().orEmpty()
            var source = r.sourceText
            var emotion = r.voice.emotion
            var usedOnline = false
            if (r.text == null) {
                val heard = listen(r, api, progress) { note = it }
                text = heard.text
                source = heard.source
                if (heard.emotion.isNotEmpty()) emotion = heard.emotion
                usedOnline = heard.online
            }
            if (text.isBlank()) throw StepUnavailableException("Không nghe ra lời nói nào ở câu này")
            progress(Step.SPEAK, 0f)
            val slot = r.endMs - r.startMs
            val room = maxOf(r.roomMs, slot)
            var spoken: Spoken? = null
            if (r.voice.gemini != null && api != null) {
                spoken = try {
                    geminiVoice(api, s.geminiTtsModel, text, r.voice.copy(emotion = emotion))
                } catch (e: OnlineUnavailableException) {
                    note = e.message
                    DubLog.w(TAG, "Gemini could not read the line, the phone does: ${e.message}")
                    null
                }
                if (spoken != null) usedOnline = true
            } else if (r.voice.gemini != null) {
                note = when (why) {
                    "no Gemini key" -> "chưa nhập khoá Gemini nên máy đọc"
                    "mode Offline" -> "đang để chế độ Offline nên máy đọc"
                    else -> "không có mạng nên máy đọc"
                }
            }
            val phone = spoken == null
            if (spoken == null) spoken = phoneVoice(text, slot, room, r.voice.pitch, r.phoneVoice)
                ?: throw StepUnavailableException("Giọng đọc của máy không đọc được câu này")
            progress(Step.SPEAK, 1f)
            // Longer than its time: played faster, up to the user's limit (TTS rate included).
            val maxPlayback = maxOf(1f, s.maxDubSpeed / spoken.rate)
            val playback = if (spoken.ms > room + DubTime.TOLERANCE_MS) (spoken.ms.toFloat() / room).coerceIn(1f, maxPlayback) else 1f
            val gain = Loudness.gainTo(DubbingPipeline.TARGET_LUFS, listOf(spoken.file))
            DubLog.i(TAG, "line done: \"${text.take(60)}\" ${spoken.ms}ms at rate ${spoken.rate}, played x${"%.2f".format(playback)}, room ${room}ms, ${if (phone) "phone" else r.voice.gemini}")
            return Output(
                spoken.file, spoken.ms, text, source, emotion,
                if (phone) null else r.voice.gemini, if (phone) r.voice.pitch else 1f, playback, gain, usedOnline, note,
            )
        } finally {
            api?.shutdown()
        }
    }

    private class Heard(val text: String, val source: String, val emotion: String, val online: Boolean)

    private class Spoken(val file: File, val ms: Long, val rate: Float)

    /** Listens to the line again: Gemini when it can, else Whisper and Gemma. */
    private suspend fun listen(r: Request, api: GeminiApi?, progress: (Step, Float) -> Unit, onNote: (String) -> Unit): Heard {
        // A little audio around the line so its first and last words are whole.
        val from = (r.startMs - PAD_MS).coerceAtLeast(0)
        val to = r.endMs + PAD_MS
        val separator = Engines.separator(context, workDir)
        val audio = if (separator != null) {
            progress(Step.SEPARATE, 0f)
            val stems = separator.separate(r.sourceUri, from, to) { progress(Step.SEPARATE, it) }
            AudioSource(Uri.fromFile(stems.voice).toString(), 0, 0, from)
        } else {
            AudioSource(r.sourceUri, from, to, from)
        }
        if (api != null) {
            try {
                progress(Step.LISTEN, 0f)
                val voice = decode16k(audio)
                val range = SpeechDetector.Range(r.startMs - from, (r.endMs - from).coerceAtMost(voice.size / 16L))
                val heard = GeminiSubtitler(api, DubSettings(context).geminiTextModel)
                    .listen(voice, listOf(range), r.language, r.around) { progress(Step.LISTEN, it) }
                val turns = heard.turns.filter { it.vi.isNotBlank() }
                if (turns.isEmpty()) throw OnlineUnavailableException("Gemini không nghe ra lời nói ở câu này")
                if (turns.map { it.speaker }.distinct().size > 1) DubLog.i(TAG, "${turns.size} turns of several people in one line, read in one voice")
                return Heard(
                    turns.joinToString(" ") { it.vi.trim() }, turns.joinToString(" ") { it.source.trim() },
                    turns.firstOrNull { it.emotion.isNotEmpty() }?.emotion.orEmpty(), true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = (e as? OnlineUnavailableException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
                DubLog.w(TAG, "listening online failed, using offline: $why")
                onNote("online lỗi nên dịch offline: $why")
            }
        }
        progress(Step.LISTEN, 0f)
        val segments = Engines.speechToText(context, workDir).transcribe(audio, r.language) { progress(Step.LISTEN, it) }
            .filter { it.text.isNotBlank() && !com.cutviet.editor.model.SubtitleCues.isHallucination(it.text) }
        if (segments.isEmpty()) throw StepUnavailableException("Không nghe ra lời nói nào ở câu này")
        val line = SpeechSegment(segments.first().startMs, segments.last().endMs, segments.joinToString(" ") { it.text.trim() })
        progress(Step.TRANSLATE, 0f)
        val vi = Engines.translator(context).use { t ->
            t.translate(listOf(line), r.language, listOf(TranslationPrompt.syllableBudget(r.roomMs))) { progress(Step.TRANSLATE, it) }
        }.firstOrNull().orEmpty().trim()
        return Heard(vi, line.text, "", false)
    }

    private suspend fun geminiVoice(api: GeminiApi, model: String, text: String, v: LineVoice): Spoken {
        val gender = v.gender ?: Gender.FEMALE
        val role = GeminiDubber.Role(null, v.gemini!!, gender, v.description)
        val f = File(workDir, "line_gemini.wav")
        val answer = api.interact(GeminiDubber.ttsRequest(model, GeminiDubber.ttsPrompt(role, v.emotion, text), role.voice), "đọc lại câu")
        val (bytes, mime) = GeminiApi.outputAudio(answer) ?: throw OnlineUnavailableException("Gemini không trả về giọng đọc")
        GeminiDubber.writeAudio(bytes, mime, f)
        val ms = WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: throw OnlineUnavailableException("Không đọc được âm thanh Gemini trả về")
        return Spoken(f, ms, 1f)
    }

    /** The phone's voice, fitted to the line's time like a full dub. */
    private suspend fun phoneVoice(text: String, slotMs: Long, roomMs: Long, pitch: Float, name: String?): Spoken? {
        // English words respelled for the Vietnamese voice; the track keeps the text as written.
        val line = SpeakableText.forPhone(text).take(voice.maxInputLength)
        val files = HashMap<Float, File>()
        val fit = AudioFitter.fit(voice.estimateMs(line, name, 1f), slotMs, roomMs) { rate ->
            val f = File(workDir, "line_phone_${(rate * 1000).toInt()}.wav")
            voice.synthesize(line, name, pitch, rate, f)?.let { ms -> WavTrim.trim(f) ?: ms }?.also { files[rate] = f }
        } ?: return null
        val file = files[fit.rate] ?: return null
        files.values.filter { it != file }.forEach { it.delete() }
        return Spoken(file, fit.durationMs, fit.rate)
    }

    private suspend fun decode16k(source: AudioSource): FloatArray {
        val pcm = File(workDir, "line_16k.f32")
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        withContext(Dispatchers.IO) {
            AudioPcmDecoder.decode(context, Uri.parse(source.uri), source.startMs, source.endMs, pcm, {}, { job?.isActive == false })
        }
        val fb = java.nio.ByteBuffer.wrap(pcm.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        pcm.delete()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    companion object {
        const val TAG = DubbingPipeline.TAG
        private const val PAD_MS = 300L
    }
}
