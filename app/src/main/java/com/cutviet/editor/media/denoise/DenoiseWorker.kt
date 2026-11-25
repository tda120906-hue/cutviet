package com.cutviet.editor.media.denoise

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
import com.cutviet.editor.media.dubbing.DubLog
import com.cutviet.editor.media.dubbing.DubbingWorker
import com.cutviet.editor.media.dubbing.StepUnavailableException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/** Background "Lọc tiếng ồn" job for one clip, a foreground media-processing task like the dub. */
class DenoiseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DubLog.init(applicationContext)
        val uri = inputData.getString(KEY_URI) ?: return Result.failure(error("Thiếu video nguồn"))
        val start = inputData.getLong(KEY_START, 0)
        val end = inputData.getLong(KEY_END, 0)
        val keep = runCatching { DenoisePipeline.Keep.valueOf(inputData.getString(KEY_KEEP)!!) }.getOrDefault(DenoisePipeline.Keep.MAIN)
        if (runAttemptCount > 0) {
            DubLog.w(DenoisePipeline.TAG, "denoise job $id was interrupted last time, not restarting")
            return Result.failure(error("Lần lọc trước bị dừng giữa chừng vì app bị đóng. Hãy thử lại."))
        }
        val dir = DubbingWorker.jobDir(applicationContext, id.toString()).apply { deleteRecursively(); mkdirs() }
        DubLog.i(DenoisePipeline.TAG, "denoise job $id: $keep, ${end - start} ms, ${Build.MODEL}")
        setForeground(foregroundInfo("Đang chuẩn bị", 0f))
        return try {
            var lastStep: DenoisePipeline.Step? = null
            var lastPercent = -1
            val stems = DenoisePipeline(applicationContext, dir).run(uri, start, end, keep) { step, p ->
                val percent = (p * 100).toInt()
                if (step != lastStep || percent != lastPercent) {
                    lastStep = step
                    lastPercent = percent
                    setProgressAsync(workDataOf(KEY_STEP to step.name, KEY_PROGRESS to p))
                    notify(step.label, p)
                }
            }
            resultFile(applicationContext, id.toString()).writeText(
                JSONObject().put("voice", stems.voice.absolutePath).put("background", stems.background.absolutePath)
                    .put("sourceStartMs", start).put("durationMs", stems.durationMs).toString(),
            )
            Result.success()
        } catch (e: StepUnavailableException) {
            Result.failure(error(e.message ?: "Bước này chưa dùng được trên máy"))
        } catch (e: CancellationException) {
            DubLog.i(DenoisePipeline.TAG, "denoise job $id cancelled")
            dir.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            DubLog.w(DenoisePipeline.TAG, "denoise job $id failed", e)
            dir.deleteRecursively()
            Result.failure(error("Lọc tiếng ồn thất bại: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    private fun notify(text: String, progress: Float) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(DenoisePipeline.Step.SEPARATE.label, 0f)

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
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lọc tiếng ồn", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Lọc tiếng ồn")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_KEEP = "keep"
        const val KEY_STEP = "step"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "denoise"
        private const val NOTIFICATION_ID = 4112
        const val UNIQUE_NAME = "denoise"
        const val TAG_CLIP = "clip:"
        const val TAG_LEVEL = "bg:"

        fun enqueue(context: Context, projectId: String, clipId: String, uri: String, startMs: Long, endMs: Long, keep: DenoisePipeline.Keep, backgroundLevel: Float) {
            val req = OneTimeWorkRequestBuilder<DenoiseWorker>()
                .addTag(DubbingWorker.projectTag(projectId))
                .addTag(TAG_CLIP + clipId)
                .addTag(TAG_LEVEL + backgroundLevel)
                .setInputData(workDataOf(KEY_URI to uri, KEY_START to startMs, KEY_END to endMs, KEY_KEEP to keep.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun resultFile(context: Context, jobId: String) = File(DubbingWorker.jobDir(context, jobId), "denoise.json")
    }
}
