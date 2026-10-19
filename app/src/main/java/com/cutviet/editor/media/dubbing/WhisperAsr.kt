package com.cutviet.editor.media.dubbing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
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
}

/**
 * Offline speech recognition with whisper.cpp. Decodes the source's audio (only the [startMs] to
 * [endMs] range) to 16 kHz PCM, runs Whisper with the language fixed, and returns sentences with
 * word timestamps on the source's timeline.
 */
class WhisperAsr(
    private val context: Context,
    private val model: File,
    private val workDir: File,
    private val startMs: Long,
    private val endMs: Long,
) : SpeechToText {

    override suspend fun transcribe(sourceUri: String, language: SourceLanguage, progress: (Float) -> Unit): List<SpeechSegment> {
        if (!WhisperNative.available) throw StepUnavailableException("Máy này không chạy được Whisper (cần chip arm64)")
        if (!model.exists()) throw StepUnavailableException("Chưa tải model nhận dạng giọng nói")
        val pcm = File(workDir, "audio_16k.f32")
        val t0 = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            AudioPcmDecoder.decode(context, Uri.parse(sourceUri), startMs, endMs, pcm, { progress(it * 0.1f) }, { !ctx.isActive })
        }
        Log.i(TAG, "decode ${SystemClock.elapsedRealtime() - t0} ms, ${pcm.length() / 4 / AudioPcmDecoder.SAMPLE_RATE} s of audio")

        val json = try {
            runWhisper(pcm, language) { progress(0.1f + 0.9f * it / 100f) }
        } finally {
            pcm.delete()
        }
        val words = WhisperTranscript.words(WhisperTranscript.parseTokens(json))
        return WhisperTranscript.sentences(words, offsetMs = startMs)
    }

    private suspend fun runWhisper(pcm: File, language: SourceLanguage, onPercent: (Int) -> Unit): String {
        val t0 = SystemClock.elapsedRealtime()
        val handle = withContext(Dispatchers.IO) { WhisperNative.load(model.absolutePath) }
        if (handle == 0L) throw StepUnavailableException("Model nhận dạng giọng nói bị lỗi, hãy tải lại")
        Log.i(TAG, "model load ${SystemClock.elapsedRealtime() - t0} ms")
        // A dedicated thread with a large stack: whisper.cpp's decoder needs more stack than
        // coroutine worker threads have. The thread owns the model and frees it when done, so a
        // cancel (which only asks whisper to stop) can never free it while it is still in use.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { WhisperNative.abort(handle) }
            thread(name = "whisper", stackSize = 8L shl 20) {
                val t1 = SystemClock.elapsedRealtime()
                val result = try {
                    WhisperNative.transcribe(handle, pcm.absolutePath, language.code, language.initialPrompt, threads()) { onPercent(it) }
                } finally {
                    WhisperNative.free(handle)
                }
                Log.i(TAG, "transcribe ${SystemClock.elapsedRealtime() - t1} ms")
                if (cont.isActive) {
                    if (result != null) cont.resume(result)
                    else cont.resumeWith(Result.failure(StepUnavailableException("Nhận dạng giọng nói thất bại")))
                }
            }
        }
    }

    companion object {
        const val TAG = "CutVietWhisper"

        /**
         * Snapdragon 8 Elite (OnePlus 13) has 8 fast Oryon cores and no little cores, so all of them
         * help; on other phones stay off the slowest ones.
         */
        fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
