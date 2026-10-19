package com.cutviet.editor.media.dubbing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline translation with a Gemma model through MediaPipe's LLM Inference API, on the GPU
 * (Adreno on the OnePlus 13), falling back to the CPU. Sentences are translated in windows of
 * [TranslationPrompt.WINDOW] with the previous ones as context; a line the model skips is
 * retried on its own.
 *
 * Whisper is fully released before this runs, so the two big models never share RAM.
 */
class GemmaTranslator(private val context: Context, private val model: File) : TextTranslator {

    override suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, progress: (Float) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            if (!model.exists()) throw StepUnavailableException("Chưa có model dịch Gemma")
            val t0 = SystemClock.elapsedRealtime()
            val llm = open()
            Log.i(TAG, "model load ${SystemClock.elapsedRealtime() - t0} ms")
            try {
                val out = ArrayList<String>(segments.size)
                var i = 0
                while (i < segments.size) {
                    coroutineContext.ensureActive()
                    val lines = segments.subList(i, minOf(segments.size, i + TranslationPrompt.WINDOW))
                    val ctx = segments.subList(maxOf(0, i - TranslationPrompt.CONTEXT), i)
                    val parsed = TranslationPrompt.parse(ask(llm, TranslationPrompt.build(language, ctx, lines)), lines.size)
                    lines.forEachIndexed { k, s ->
                        val text = parsed?.getOrNull(k)
                            ?: TranslationPrompt.parse(ask(llm, TranslationPrompt.build(language, ctx, listOf(s))), 1)?.firstOrNull()
                            ?: s.text
                        out += text
                    }
                    i += lines.size
                    progress(i.toFloat() / segments.size)
                }
                out
            } finally {
                llm.close()
            }
        }

    private fun open(): LlmInference {
        fun build(backend: LlmInference.Backend) = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(backend)
                .build(),
        )
        return try {
            build(LlmInference.Backend.GPU)
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend failed, using CPU", e)
            try {
                build(LlmInference.Backend.CPU)
            } catch (e2: Exception) {
                throw StepUnavailableException("Không mở được model dịch: ${e2.message}")
            }
        }
    }

    /** One fresh session per request so earlier windows never leak into the answer. */
    private fun ask(llm: LlmInference, prompt: String): String {
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.3f)
                .setTopK(40)
                .build(),
        )
        return try {
            session.addQueryChunk(prompt)
            session.generateResponse()
        } finally {
            session.close()
        }
    }

    companion object {
        const val TAG = "CutVietGemma"
        /** Prompt plus answer for one window. */
        const val MAX_TOKENS = 2048
    }
}
