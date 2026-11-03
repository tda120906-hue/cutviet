package com.cutviet.editor.media.dubbing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** JNI entry points of libcutviet_whisper.so (src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    fun interface Listener {
        fun onProgress(percent: Int)
    }

    /** False on phones without the arm64 library (the APK only ships arm64-v8a). */
    val available: Boolean by lazy { runCatching { System.loadLibrary("cutviet_whisper") }.isSuccess }

    external fun load(modelPath: String): Long
    external fun free(handle: Long)
    external fun abort(handle: Long)
    external fun transcribe(handle: Long, pcmPath: String, language: String, prompt: String?, threads: Int, listener: Listener?): String?

    /** "ja 0.93": the language heard in 30 s windows from [offsetsMs], or null. */
    external fun detectLanguage(handle: Long, pcmPath: String, offsetsMs: LongArray, threads: Int): String?
}

/**
 * Offline speech recognition with whisper.cpp. Decodes the audio to 16 kHz PCM, runs Whisper with
 * the language fixed (or, for [SourceLanguage.AUTO], first finds it from a few stretches where
 * someone speaks), and returns sentences with word timestamps on the clip's source timeline.
 * It listens to the separated voice when there is one, which keeps noise and music out of it.
 */
class WhisperAsr(
    private val context: Context,
    private val model: File,
    private val workDir: File,
) : SpeechToText {

    @Volatile
    override var heardLanguage: String? = null
        private set

    override suspend fun transcribe(source: AudioSource, language: SourceLanguage, progress: (Float) -> Unit): List<SpeechSegment> {
        heardLanguage = null
        if (!WhisperNative.available) throw StepUnavailableException("Máy này không chạy được Whisper (cần chip arm64)")
        if (!model.exists()) throw StepUnavailableException("Chưa tải model nhận dạng giọng nói")
        val pcm = File(workDir, "audio_16k.f32")
        val t0 = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            AudioPcmDecoder.decode(context, Uri.parse(source.uri), source.startMs, source.endMs, pcm, { progress(it * 0.1f) }, { !ctx.isActive })
        }
        DubLog.i(TAG, "decode ${SystemClock.elapsedRealtime() - t0} ms, ${pcm.length() / 4 / AudioPcmDecoder.SAMPLE_RATE} s of audio")

        val json = try {
            // Where someone speaks, to find the language there and not in a silent intro.
            val offsets = if (language == SourceLanguage.AUTO) {
                withContext(Dispatchers.IO) { detectionOffsets(SpeechDetector.detectFile(pcm, AudioPcmDecoder.SAMPLE_RATE)) }
            } else {
                null
            }
            runWhisper(pcm, language, offsets) { progress(0.1f + 0.9f * it / 100f) }
        } finally {
            pcm.delete()
        }
        val words = WhisperTranscript.words(WhisperTranscript.parseTokens(json))
        return WhisperTranscript.sentences(words, offsetMs = source.offsetMs)
    }

    private suspend fun runWhisper(pcm: File, language: SourceLanguage, detectAt: LongArray?, onPercent: (Int) -> Unit): String {
        val t0 = SystemClock.elapsedRealtime()
        val handle = withContext(Dispatchers.IO) { WhisperNative.load(model.absolutePath) }
        if (handle == 0L) throw StepUnavailableException("Model nhận dạng giọng nói bị lỗi, hãy tải lại")
        DubLog.i(TAG, "model load ${SystemClock.elapsedRealtime() - t0} ms")
        // A dedicated thread with a large stack: whisper.cpp's decoder needs more stack than
        // coroutine worker threads have. The thread owns the model and frees it when done, so a
        // cancel (which only asks whisper to stop) can never free it while it is still in use.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { WhisperNative.abort(handle) }
            Thread(null, {
                val t1 = SystemClock.elapsedRealtime()
                val result = try {
                    var code = language.code
                    var prompt = language.initialPrompt
                    if (detectAt != null) {
                        val t2 = SystemClock.elapsedRealtime()
                        val found = WhisperNative.detectLanguage(handle, pcm.absolutePath, detectAt, threads())
                        val heard = found?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                        DubLog.i(TAG, "language: $found from ${detectAt.size} places in ${SystemClock.elapsedRealtime() - t2} ms")
                        heardLanguage = heard
                        // Whisper finds it again itself on the first 30 s if this did not work.
                        code = heard ?: "auto"
                        prompt = SourceLanguage.ofCode(heard)?.initialPrompt
                    }
                    WhisperNative.transcribe(handle, pcm.absolutePath, code, prompt, threads()) { onPercent(it) }
                } finally {
                    WhisperNative.free(handle)
                }
                DubLog.i(TAG, "transcribe ${SystemClock.elapsedRealtime() - t1} ms")
                if (cont.isActive) {
                    if (result != null) cont.resume(result)
                    else cont.resumeWith(Result.failure(StepUnavailableException("Nhận dạng giọng nói thất bại")))
                }
            }, "whisper", 8L shl 20).start()
        }
    }

    companion object {
        const val TAG = "CutVietWhisper"

        /**
         * Snapdragon 8 Elite (OnePlus 13) has 8 fast Oryon cores and no little cores, so all of them
         * help; on other phones stay off the slowest ones.
         */
        fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        /**
         * Where to listen for the language: the start of the speech at about 10 %, 50 % and 85 % of
         * all that is said, at least 30 s apart (one window for a short clip; the start when no
         * speech was found).
         */
        fun detectionOffsets(speech: List<SpeechDetector.Range>): LongArray {
            if (speech.isEmpty()) return longArrayOf(0)
            val total = speech.sumOf { it.lengthMs }
            val out = ArrayList<Long>()
            for (share in doubleArrayOf(0.1, 0.5, 0.85)) {
                var left = (total * share).toLong()
                val r = speech.firstOrNull { left -= it.lengthMs; left < 0 } ?: speech.last()
                val at = (r.startMs - 200).coerceAtLeast(0)
                if (out.none { kotlin.math.abs(it - at) < 30_000 }) out += at
            }
            return out.toLongArray()
        }
    }
}
