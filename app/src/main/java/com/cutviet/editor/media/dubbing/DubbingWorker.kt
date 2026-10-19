package com.cutviet.editor.media.dubbing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cutviet.editor.media.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Background dubbing job: runs [DubbingPipeline] as a foreground "media processing" task, so it
 * keeps going with the screen off, and writes the dubbed segments to [resultFile]. The editor adds
 * them to the project as DUB tracks when it sees the job succeed.
 *
 * Files live in filesDir/dub/<job id>/, never in the export cache that Exporter clears.
 */
class DubbingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE) ?: return Result.failure(error("Thiếu video nguồn"))
        val language = runCatching { SourceLanguage.valueOf(inputData.getString(KEY_LANGUAGE)!!) }
            .getOrDefault(SourceLanguage.ENGLISH)
        val dir = jobDir(applicationContext, id.toString()).apply { deleteRecursively(); mkdirs() }

        setForeground(foregroundInfo(DubbingPipeline.Step.RECOGNIZE.label, 0f))
        val voice = withContext(Dispatchers.Main) { VoiceEngine(applicationContext) }
        try {
            val ready = withTimeoutOrNull(15_000) {
                while (voice.status.value == VoiceEngine.Status.LOADING) delay(100)
                voice.status.value == VoiceEngine.Status.READY
            } ?: false
            if (!ready) return Result.failure(error("Máy chưa có giọng đọc tiếng Việt"))

            val pipeline = DubbingPipeline(
                asr = Engines.speechToText(applicationContext, dir, inputData.getLong(KEY_START, 0), inputData.getLong(KEY_END, 0)),
                translator = Engines.translator(applicationContext),
                voice = voice,
                workDir = dir,
            )
            val segments = pipeline.run(source, DubbingPipeline.Settings(language, inputData.getString(KEY_VOICE))) { step, p ->
                setProgressAsync(workDataOf(KEY_STEP to step.name, KEY_PROGRESS to p))
                notify(step.label, p)
            }
            if (segments.isEmpty()) return Result.failure(error("Không nghe thấy lời nói nào trong video"))
            resultFile(applicationContext, id.toString()).writeText(encode(segments, pipeline.timings).toString())
            return Result.success(workDataOf(KEY_COUNT to segments.size))
        } catch (e: StepUnavailableException) {
            return Result.failure(error(e.message ?: "Bước này chưa dùng được trên máy"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(error("Lồng tiếng thất bại: ${e.message}"))
        } finally {
            withContext(Dispatchers.Main) { voice.shutdown() }
        }
    }

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    private fun notify(text: String, progress: Float) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(text, progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(DubbingPipeline.Step.RECOGNIZE.label, 0f)

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
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lồng tiếng", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Dịch lồng tiếng")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", cancel)
            .build()
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE = "voice"
        const val KEY_START = "startMs"
        const val KEY_END = "endMs"
        const val KEY_STEP = "step"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_COUNT = "count"
        private const val CHANNEL = "dubbing"
        private const val NOTIFICATION_ID = 4107
        /** One dubbing job at a time. */
        const val UNIQUE_NAME = "dubbing"

        /** Dubs [startMs]..[endMs] of the source (its trimmed part on the timeline). */
        fun enqueue(context: Context, sourceUri: String, startMs: Long, endMs: Long, language: SourceLanguage, voiceName: String?): java.util.UUID {
            val req = OneTimeWorkRequestBuilder<DubbingWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SOURCE to sourceUri, KEY_START to startMs, KEY_END to endMs,
                        KEY_LANGUAGE to language.name, KEY_VOICE to voiceName,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
            return req.id
        }

        fun jobDir(context: Context, jobId: String) = File(File(context.filesDir, "dub"), jobId)
        fun resultFile(context: Context, jobId: String) = File(jobDir(context, jobId), "result.json")

        fun encode(segments: List<DubbedSegment>, timings: Map<DubbingPipeline.Step, Long>): JSONObject =
            JSONObject().apply {
                put("segments", JSONArray().apply {
                    segments.forEach { s ->
                        put(JSONObject().apply {
                            put("startMs", s.startMs); put("slotMs", s.slotMs)
                            put("sourceText", s.sourceText); put("text", s.text)
                            put("file", s.file.absolutePath); put("durationMs", s.durationMs); put("rate", s.rate.toDouble())
                        })
                    }
                })
                put("timings", JSONObject().apply { timings.forEach { (k, v) -> put(k.name, v) } })
            }

        fun decode(json: String): List<DubbedSegment> {
            val arr = JSONObject(json).getJSONArray("segments")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DubbedSegment(
                    o.getLong("startMs"), o.getLong("slotMs"), o.getString("sourceText"), o.getString("text"),
                    File(o.getString("file")), o.getLong("durationMs"), o.getDouble("rate").toFloat(),
                )
            }
        }
    }
}

/** Chooses the speech-to-text and translation engines. Translation is filled in by the next steps (Gemma, Gemini). */
object Engines {
    fun speechToText(context: Context, workDir: File, startMs: Long, endMs: Long): SpeechToText {
        val model = DubModel.WHISPER_LARGE_V3
        if (!model.isReady(context)) {
            return object : SpeechToText {
                override suspend fun transcribe(sourceUri: String, language: SourceLanguage, progress: (Float) -> Unit) =
                    throw StepUnavailableException("Chưa tải model nhận dạng giọng nói (${model.label})")
            }
        }
        return WhisperAsr(context, model.file(context), workDir, startMs, endMs)
    }

    fun translator(context: Context): TextTranslator = object : TextTranslator {
        override suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, progress: (Float) -> Unit) =
            throw StepUnavailableException("Chưa có bộ dịch (Gemma đang được làm)")
    }
}
