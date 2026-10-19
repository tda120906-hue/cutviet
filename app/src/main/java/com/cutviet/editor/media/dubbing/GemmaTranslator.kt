package com.cutviet.editor.media.dubbing

import android.content.Context
import android.os.SystemClock
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
    private var backend = "?"
    /** Kept loaded after [translate] for [shorten]; loading takes over a minute. */
    private var llm: LlmInference? = null

    override suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, budgets: List<Int>, progress: (Float) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            if (!model.exists()) throw StepUnavailableException("Chưa có model dịch Gemma")
            val llm = engine()
            val out = ArrayList<String>(segments.size)
            var i = 0
            while (i < segments.size) {
                coroutineContext.ensureActive()
                val end = minOf(segments.size, i + TranslationPrompt.WINDOW)
                val lines = segments.subList(i, end)
                val limits = budgets.subList(i, end)
                val ctx = segments.subList(maxOf(0, i - TranslationPrompt.CONTEXT), i)
                val tw = SystemClock.elapsedRealtime()
                val parsed = TranslationPrompt.parse(ask(llm, TranslationPrompt.build(language, ctx, lines, limits)), lines.size)
                DubLog.i(TAG, "lines ${i + 1}-$end: ${SystemClock.elapsedRealtime() - tw} ms, read ${parsed?.count { it != null } ?: 0}/${lines.size}")
                lines.forEachIndexed { k, s ->
                    val text = parsed?.getOrNull(k)
                        ?: TranslationPrompt.parse(ask(llm, TranslationPrompt.build(language, ctx, listOf(s), listOf(limits[k]))), 1)?.firstOrNull()
                        ?: s.text
                    out += text
                }
                i = end
                progress(i.toFloat() / segments.size)
            }
            out
        }

    override suspend fun shorten(source: String, text: String, language: SourceLanguage, maxSyllables: Int): String? =
        withContext(Dispatchers.IO) {
            val llm = llm ?: return@withContext null
            val answer = runCatching { ask(llm, TranslationPrompt.shorten(language, source, text, maxSyllables)) }.getOrNull()
            answer?.let { TranslationPrompt.parse(it, 1)?.firstOrNull() }
        }

    override fun close() {
        llm?.close()
        llm = null
    }

    private fun engine(): LlmInference = llm ?: run {
        val t0 = SystemClock.elapsedRealtime()
        open().also {
            DubLog.i(TAG, "model load ${SystemClock.elapsedRealtime() - t0} ms on $backend")
            llm = it
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
        DubLog.i(TAG, "opening ${model.name} (${model.length() / (1024 * 1024)} MB), ${DubLog.memory(context)}")
        return try {
            build(LlmInference.Backend.GPU).also { backend = "GPU" }
        } catch (e: Exception) {
            DubLog.w(TAG, "GPU backend failed, using CPU (much slower)", e)
            try {
                build(LlmInference.Backend.CPU).also { backend = "CPU" }
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
