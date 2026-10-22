package com.cutviet.editor.media.dubbing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cutviet.editor.model.SubtitleCues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes Vietnamese subtitles for the video clips of a project: optional voice separation (cleaner
 * speech for the recogniser), then either Gemini online (listens and translates in one go) or
 * Whisper + Gemma on the phone, with the same automatic fallback to offline as the dub. Times come
 * back on the timeline, split into lines that are easy to read.
 */
class SubtitlePipeline(private val context: Context, private val workDir: File) {
    enum class Step(val label: String) {
        SEPARATE("Đang tách giọng"),
        ONLINE("Đang nghe & dịch online"),
        RECOGNIZE("Đang nhận dạng lời nói"),
        TRANSLATE("Đang dịch"),
    }

    /** A clip's trimmed source ([trimStartMs]..[trimEndMs]) placed at [timelineStartMs] for [durationMs]. */
    data class Part(
        val uri: String,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val timelineStartMs: Long,
        val durationMs: Long,
        val speed: Float,
    )

    class Output(val cues: List<SubtitleCues.Cue>, val usedOnline: Boolean, val fallbackReason: String?)

    /** One sentence in a part's source time. */
    private class Said(val part: Int, val startMs: Long, val endMs: Long, val source: String, var text: String)

    @Volatile
    var fallbackReason: String? = null
        private set

    suspend fun run(
        parts: List<Part>,
        language: SourceLanguage,
        separate: Boolean,
        online: GeminiSubtitler?,
        progress: (Step, Float) -> Unit,
    ): Output {
        workDir.mkdirs()
        DubLog.i(TAG, "subtitles: ${parts.size} clips, $language, separate=$separate, online=${online != null}, ${DubLog.memory(context)}")
        val sources = parts.mapIndexed { i, p ->
            val dir = File(workDir, "part$i").apply { mkdirs() }
            val model = DubModel.MDX_VOC_FT
            if (separate && model.isReady(context)) {
                progress(Step.SEPARATE, i.toFloat() / parts.size)
                val stems = timed("separate $i") {
                    VoiceSeparator(context, model.file(context), dir).separate(p.uri, p.trimStartMs, p.trimEndMs) {
                        progress(Step.SEPARATE, (i + it) / parts.size)
                    }
                }
                stems.background.delete()
                AudioSource(Uri.fromFile(stems.voice).toString(), 0, 0, p.trimStartMs)
            } else {
                AudioSource(p.uri, p.trimStartMs, p.trimEndMs, p.trimStartMs)
            }
        }

        var said: List<Said>? = null
        if (online != null) {
            try {
                said = runOnline(online, sources, language) { progress(Step.ONLINE, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? OnlineUnavailableException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
                DubLog.w(TAG, "online subtitles failed, using offline: $reason")
                fallbackReason = reason
            } finally {
                online.shutdown()
            }
        }
        val lines = said ?: try {
            runOffline(sources, language, progress)
        } catch (e: StepUnavailableException) {
            // Say why online did not do it either.
            throw fallbackReason?.let { StepUnavailableException("${e.message}. Online cũng lỗi: $it") } ?: e
        }

        val cues = ArrayList<SubtitleCues.Cue>()
        for (s in lines) {
            val p = parts[s.part]
            val end = p.timelineStartMs + p.durationMs
            val a = SubtitleCues.toTimeline(s.startMs, p.timelineStartMs, p.trimStartMs, p.speed).coerceIn(p.timelineStartMs, end)
            val b = SubtitleCues.toTimeline(s.endMs, p.timelineStartMs, p.trimStartMs, p.speed).coerceIn(a, end)
            if (b - a < 100) continue
            cues += SubtitleCues.split(a, b, s.text, s.source)
        }
        val total = parts.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: Long.MAX_VALUE
        val tidy = SubtitleCues.tidy(cues, total)
        DubLog.i(TAG, "subtitles done: ${lines.size} sentences → ${tidy.size} lines, online=${said != null}")
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        return Output(tidy, said != null, fallbackReason)
    }

    private suspend fun runOnline(online: GeminiSubtitler, sources: List<AudioSource>, language: SourceLanguage, progress: (Float) -> Unit): List<Said> {
        val out = ArrayList<Said>()
        sources.forEachIndexed { i, src ->
            fun at(f: Float) = progress((i + f) / sources.size)
            val voice = decode16k(src) { at(it * 0.1f) }
            val ranges = SpeechDetector.detect(voice, AudioPcmDecoder.SAMPLE_RATE)
            DubLog.i(TAG, "clip $i: ${ranges.size} speech segments")
            if (ranges.isEmpty()) return@forEachIndexed
            val lines = timed("gemini $i") { online.subtitle(voice, ranges, language) { at(0.1f + 0.9f * it) } }
            lines.filter { it.vi.isNotBlank() && !SubtitleCues.isHallucination(it.vi) }.forEach { l ->
                val r = ranges[l.index]
                out += Said(i, r.startMs + src.offsetMs, r.endMs + src.offsetMs, l.source, l.vi)
            }
        }
        if (out.isEmpty()) throw OnlineUnavailableException("Gemini không nghe ra câu nào")
        return out
    }

    private suspend fun runOffline(sources: List<AudioSource>, language: SourceLanguage, progress: (Step, Float) -> Unit): List<Said> {
        val asr = Engines.speechToText(context, workDir)
        val said = ArrayList<Said>()
        sources.forEachIndexed { i, src ->
            progress(Step.RECOGNIZE, i.toFloat() / sources.size)
            val segments = timed("whisper $i") {
                asr.transcribe(src, language) { progress(Step.RECOGNIZE, (i + it) / sources.size) }
            }
            segments.filter { it.text.isNotBlank() && it.slotMs > 0 && !SubtitleCues.isHallucination(it.text) }
                .forEach { said += Said(i, it.startMs, it.endMs, it.text, it.text) }
        }
        DubLog.i(TAG, "recognized ${said.size} sentences")
        if (said.isEmpty() || language == SourceLanguage.VIETNAMESE) return said

        val model = DubModel.GEMMA_3N_E4B
        if (!model.isReady(context)) throw StepUnavailableException("Chưa tải model dịch (${model.label})")
        val translator = GemmaTranslator(context, model.file(context), subtitles = true)
        try {
            progress(Step.TRANSLATE, 0f)
            val segments = said.map { SpeechSegment(it.startMs, it.endMs, it.source) }
            val texts = timed("gemma") {
                translator.translate(segments, language, segments.map { 0 }) { progress(Step.TRANSLATE, it) }
            }
            texts.forEachIndexed { k, t -> if (k < said.size && t.isNotBlank()) said[k].text = t.trim() }
        } finally {
            translator.close()
        }
        return said
    }

    private suspend fun decode16k(source: AudioSource, progress: (Float) -> Unit): FloatArray {
        val pcm = File(workDir, "voice_16k.f32")
        val job = currentCoroutineContext()[Job]
        withContext(Dispatchers.IO) {
            AudioPcmDecoder.decode(context, Uri.parse(source.uri), source.startMs, source.endMs, pcm, progress, { job?.isActive == false })
        }
        val bytes = pcm.readBytes()
        pcm.delete()
        val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private inline fun <T> timed(what: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            DubLog.i(TAG, "$what: ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    companion object {
        const val TAG = "CutVietSub"
    }
}

/** Background subtitle job, run like the dub: a foreground "media processing" task that survives the screen going off. */
class SubtitleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DubLog.init(applicationContext)
        val language = runCatching { SourceLanguage.valueOf(inputData.getString(KEY_LANGUAGE)!!) }.getOrDefault(SourceLanguage.ENGLISH)
        val parts = runCatching { decodeParts(inputData.getString(KEY_PARTS)!!) }.getOrNull()
            ?: return Result.failure(error("Thiếu video nguồn"))
        if (runAttemptCount > 0) {
            DubLog.w(SubtitlePipeline.TAG, "subtitle job $id was interrupted last time, not restarting")
            DubLog.recordExitReasons(applicationContext)
            return Result.failure(error("Lần tạo phụ đề trước bị dừng giữa chừng vì app bị đóng. Xem tab Nhật ký, rồi thử lại."))
        }
        val dir = DubbingWorker.jobDir(applicationContext, id.toString()).apply { deleteRecursively(); mkdirs() }
        DubLog.i(SubtitlePipeline.TAG, "subtitle job $id: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, mode ${DubSettings(applicationContext).mode}")
        setForeground(foregroundInfo("Đang chuẩn bị", 0f))
        try {
            val s = DubSettings(applicationContext)
            val offlineReady = DubModel.WHISPER_LARGE_V3.isReady(applicationContext) &&
                (language == SourceLanguage.VIETNAMESE || DubModel.GEMMA_3N_E4B.isReady(applicationContext))
            val why = Engines.whyNotOnline(applicationContext, offlineReady)
            if (why != null) DubLog.i(SubtitlePipeline.TAG, "online not used: $why")
            val online = if (why == null) GeminiSubtitler(GeminiApi(s.geminiKey, s.simulateQuota), s.geminiTextModel) else null
            val pipeline = SubtitlePipeline(applicationContext, File(dir, "work"))
            var lastStep: SubtitlePipeline.Step? = null
            var lastPercent = -1
            val out = pipeline.run(parts, language, inputData.getBoolean(KEY_SEPARATE, true), online) { step, p ->
                val percent = (p * 100).toInt()
                if (step != lastStep || percent != lastPercent) {
                    lastStep = step
                    lastPercent = percent
                    val note = pipeline.fallbackReason?.let { "online lỗi, đang chạy offline" }
                    setProgressAsync(workDataOf(KEY_STEP to step.name, KEY_PROGRESS to p, KEY_NOTE to note))
                    notify(if (note != null) "${step.label} ($note)" else step.label, p)
                }
            }
            if (out.cues.isEmpty()) return fail("Không nghe thấy lời nói nào trong video")
            resultFile(applicationContext, id.toString()).writeText(encode(out.cues).toString())
            return Result.success(workDataOf(KEY_COUNT to out.cues.size, KEY_ONLINE to out.usedOnline, KEY_FALLBACK to out.fallbackReason))
        } catch (e: StepUnavailableException) {
            return fail(e.message ?: "Bước này chưa dùng được trên máy")
        } catch (e: CancellationException) {
            DubLog.i(SubtitlePipeline.TAG, "subtitle job $id cancelled")
            throw e
        } catch (e: Exception) {
            DubLog.w(SubtitlePipeline.TAG, "subtitle job $id failed", e)
            return Result.failure(error("Tạo phụ đề thất bại: ${e.message}"))
        }
    }

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    private fun fail(message: String): Result {
        DubLog.w(SubtitlePipeline.TAG, "subtitle job $id failed: $message")
        return Result.failure(error(message))
    }

    private fun notify(text: String, progress: Float) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(SubtitlePipeline.Step.RECOGNIZE.label, 0f)

    private fun foregroundInfo(text: String, progress: Float): ForegroundInfo {
        val n = notification(text, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ForegroundInfo(NOTIFICATION_ID, n, type)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun notification(text: String, progress: Float): android.app.Notification {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phụ đề", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Tạo phụ đề AI")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
    }

    companion object {
        const val KEY_PARTS = "parts"
        const val KEY_LANGUAGE = "language"
        const val KEY_SEPARATE = "separate"
        const val KEY_STEP = "step"
        const val KEY_PROGRESS = "progress"
        const val KEY_NOTE = "note"
        const val KEY_ERROR = "error"
        const val KEY_COUNT = "count"
        const val KEY_ONLINE = "online"
        const val KEY_FALLBACK = "fallback"
        private const val CHANNEL = "subtitles"
        private const val NOTIFICATION_ID = 4108
        const val UNIQUE_NAME = "subtitles"

        fun enqueue(context: Context, projectId: String, parts: List<SubtitlePipeline.Part>, language: SourceLanguage, separate: Boolean) {
            val req = OneTimeWorkRequestBuilder<SubtitleWorker>()
                .addTag(DubbingWorker.projectTag(projectId))
                .setInputData(workDataOf(KEY_PARTS to encodeParts(parts).toString(), KEY_LANGUAGE to language.name, KEY_SEPARATE to separate))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun resultFile(context: Context, jobId: String) = File(DubbingWorker.jobDir(context, jobId), "subtitles.json")

        fun encodeParts(parts: List<SubtitlePipeline.Part>): JSONArray = JSONArray().apply {
            parts.forEach { p ->
                put(JSONObject().apply {
                    put("uri", p.uri); put("trimStartMs", p.trimStartMs); put("trimEndMs", p.trimEndMs)
                    put("timelineStartMs", p.timelineStartMs); put("durationMs", p.durationMs); put("speed", p.speed.toDouble())
                })
            }
        }

        fun decodeParts(json: String): List<SubtitlePipeline.Part> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SubtitlePipeline.Part(
                    o.getString("uri"), o.getLong("trimStartMs"), o.getLong("trimEndMs"),
                    o.getLong("timelineStartMs"), o.getLong("durationMs"), o.getDouble("speed").toFloat(),
                )
            }
        }

        fun encode(cues: List<SubtitleCues.Cue>): JSONObject = JSONObject().put(
            "cues",
            JSONArray().apply {
                cues.forEach { c -> put(JSONObject().put("startMs", c.startMs).put("endMs", c.endMs).put("text", c.text).put("source", c.source)) }
            },
        )

        fun decode(json: String): List<SubtitleCues.Cue> {
            val a = JSONObject(json).getJSONArray("cues")
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SubtitleCues.Cue(o.getLong("startMs"), o.getLong("endMs"), o.getString("text"), o.optString("source"))
            }
        }
    }
}
