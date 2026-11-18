package com.cutviet.editor.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cutviet.editor.model.ExportCodec
import com.cutviet.editor.model.ExportQuality
import com.cutviet.editor.model.ExportResolution
import com.cutviet.editor.model.ExportSettings
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.ProjectStore
import com.cutviet.editor.model.SplitExport
import com.cutviet.editor.model.SplitPart
import com.cutviet.editor.ui.formatBytes
import com.cutviet.editor.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Exports a long project as several videos, one after another, as a foreground "media processing"
 * job so it keeps going with the screen off or the app in the background. Each finished video is
 * written straight into Movies/CutViet and noted in a state file, so a job that is stopped (app
 * killed, Android's time limit for long background work, "Huỷ") can carry on from the next video.
 * Only one video's composition exists at a time, which keeps memory low for 6-7 hour projects.
 */
class SplitExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class Job(val project: Project, val settings: ExportSettings, val parts: List<SplitPart>, val baseName: String)

    override suspend fun doWork(): Result {
        val dir = File(inputData.getString(KEY_DIR) ?: return failure("Thiếu dữ liệu xuất", 0))
        val job = runCatching { readJob(dir) }.getOrElse { return failure("Không đọc được dữ liệu xuất: ${it.message}", 0) }
        val done = readDone(dir).toMutableList()
        if (runAttemptCount > 3) return failure("Xuất bị dừng nhiều lần liên tiếp.", done.size)

        try {
            setForeground(foregroundInfo("Đang chuẩn bị", 0))
        } catch (e: Exception) {
            // Android does not let a background app start this kind of job (e.g. after its time limit).
            return failure("Android không cho xuất tiếp khi ứng dụng đang ở nền. Mở ứng dụng và bấm \"Xuất tiếp\".", done.size)
        }

        val n = job.parts.size
        val (w, h) = ProjectOps.exportSize(ProjectOps.canvasSize(job.project), job.settings)
        val bitrate = ProjectOps.targetBitrate(job.project, w to h, job.settings.quality)
        val learned = ExportSpeed.rate(applicationContext)
        val runStart = SystemClock.elapsedRealtime()
        var mediaDoneThisRun = 0L
        val exporter = withContext(Dispatchers.Main) { Exporter(applicationContext) }

        for (part in job.parts) {
            if (done.any { it.index == part.index }) continue
            val remainingAfter = job.parts.filter { p -> p.index > part.index && done.none { it.index == p.index } }.sumOf { it.durationMs }

            // Room for this video? (written once, straight into the gallery folder)
            val need = ((bitrate + 256_000L) * part.durationMs / 8_000 * 1.05).toLong()
            val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < need + 200L * 1024 * 1024) {
                return failure("Bộ nhớ máy còn ${formatBytes(free)}, video ${part.index + 1} cần khoảng ${formatBytes(need)}. Giải phóng bộ nhớ rồi bấm \"Xuất tiếp\".", done.size)
            }

            val slice = SplitExport.slice(job.project, part.startMs, part.endMs)
            val name = "${job.baseName}_phan${(part.index + 1).toString().padStart(2, '0')}.mp4"
            var lastPct = -1
            val result = exportOne(exporter, slice, job.settings, name) { st ->
                val partDone = part.durationMs * st.progress / 100
                val elapsed = SystemClock.elapsedRealtime() - runStart
                val mediaSoFar = mediaDoneThisRun + partDone
                // Speed measured in this run once there is enough of it, else the learned one.
                val partEta: Long
                val totalEta: Long
                if (elapsed > 20_000 && mediaSoFar > 0) {
                    val msPerMedia = elapsed.toDouble() / mediaSoFar
                    partEta = ((part.durationMs - partDone) * msPerMedia).toLong()
                    totalEta = partEta + (remainingAfter * msPerMedia).toLong()
                } else {
                    partEta = ExportSpeed.estimateMs(learned, w, h, part.durationMs - partDone)
                    totalEta = partEta + ExportSpeed.estimateMs(learned, w, h, remainingAfter)
                }
                if (st.progress != lastPct) {
                    lastPct = st.progress
                    setProgressAsync(
                        workDataOf(
                            KEY_PART to part.index, KEY_PARTS to n, KEY_DONE to done.size, KEY_PERCENT to st.progress,
                            KEY_ETA_PART to partEta, KEY_ETA_TOTAL to totalEta, KEY_WIDTH to w, KEY_HEIGHT to h,
                        ),
                    )
                    notify("Video ${part.index + 1}/$n · ${st.progress}% · còn khoảng ${formatDuration(totalEta)}", part.index, n, st.progress)
                }
            }
            when (result) {
                is Exporter.State.Done -> {
                    done += Saved(part.index, result.path, result.uri.toString(), result.sizeBytes)
                    writeDone(dir, done)
                    mediaDoneThisRun += part.durationMs
                }
                is Exporter.State.Failed -> return failure("Video ${part.index + 1}/$n: ${result.message}", done.size)
                else -> return failure("Xuất bị dừng.", done.size)
            }
        }
        return Result.success(workDataOf(KEY_DONE to done.size, KEY_PARTS to n))
    }

    /** Runs one export on the main thread and waits for it; cancelling the job cancels the export. */
    private suspend fun exportOne(
        exporter: Exporter, project: Project, settings: ExportSettings, name: String,
        onProgress: (Exporter.State.Running) -> Unit,
    ): Exporter.State = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            exporter.start(project, settings, fileName = name, direct = true) { st ->
                when (st) {
                    is Exporter.State.Running -> onProgress(st)
                    is Exporter.State.Done, is Exporter.State.Failed -> if (cont.isActive) cont.resume(st)
                    Exporter.State.Idle -> Unit
                }
            }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { exporter.cancel() } }
        }
    }

    private fun failure(message: String, saved: Int): Result =
        Result.failure(workDataOf(KEY_ERROR to message, KEY_DONE to saved))

    private fun notify(text: String, part: Int, parts: Int, percent: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(text, overall(part, parts, percent)))
    }

    private fun overall(part: Int, parts: Int, percent: Int) = if (parts <= 0) 0 else (part * 100 + percent) / parts

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Đang xuất", 0)

    private fun foregroundInfo(text: String, progress: Int): ForegroundInfo {
        val n = notification(text, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ForegroundInfo(NOTIFICATION_ID, n, type)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun notification(text: String, progress: Int): android.app.Notification {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Xuất video", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Xuất chia nhiều video")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", cancel)
            .build()
    }

    /** A video already saved by this job. */
    data class Saved(val index: Int, val path: String, val uri: String, val sizeBytes: Long)

    companion object {
        const val UNIQUE_NAME = "split_export"
        const val KEY_DIR = "dir"
        const val KEY_PART = "part"
        const val KEY_PARTS = "parts"
        const val KEY_DONE = "done"
        const val KEY_PERCENT = "percent"
        const val KEY_ETA_PART = "etaPart"
        const val KEY_ETA_TOTAL = "etaTotal"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "split_export"
        private const val NOTIFICATION_ID = 4211

        fun projectTag(projectId: String) = "split:$projectId"

        /** Folder holding a project's split job (the plan and the videos already saved). */
        fun jobDir(context: Context, projectId: String) = File(File(context.filesDir, "split"), projectId)

        /** Writes a new job for [project] (forgetting any earlier one) and starts it. */
        fun start(context: Context, project: Project, settings: ExportSettings, parts: List<SplitPart>) {
            val dir = jobDir(context, project.id).apply { deleteRecursively(); mkdirs() }
            val job = JSONObject().apply {
                put("project", ProjectStore.toJson(project))
                put("resolution", settings.resolution.name)
                put("customShortSide", settings.customShortSide)
                put("quality", settings.quality.name)
                put("codec", settings.codec.name)
                put("burnSubtitles", settings.burnSubtitles)
                put("baseName", "CutViet_${Exporter.safeName(project.name)}_${Exporter.stamp()}")
                put("parts", JSONArray().apply {
                    parts.forEach { put(JSONObject().put("index", it.index).put("start", it.startMs).put("end", it.endMs)) }
                })
            }
            File(dir, "job.json").writeText(job.toString())
            enqueue(context, project.id)
        }

        /** Carries on a stopped job from the first video not saved yet. */
        fun resume(context: Context, projectId: String): Boolean {
            if (!File(jobDir(context, projectId), "job.json").exists()) return false
            enqueue(context, projectId)
            return true
        }

        private fun enqueue(context: Context, projectId: String) {
            val req = OneTimeWorkRequestBuilder<SplitExportWorker>()
                .addTag(projectTag(projectId))
                .setInputData(workDataOf(KEY_DIR to jobDir(context, projectId).absolutePath))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun saved(context: Context, projectId: String): List<Saved> = readDone(jobDir(context, projectId))

        fun partCount(context: Context, projectId: String): Int =
            runCatching { JSONObject(File(jobDir(context, projectId), "job.json").readText()).getJSONArray("parts").length() }.getOrDefault(0)

        /** The user has seen how the job ended; the plan is kept only while it can be resumed. */
        fun forget(context: Context, projectId: String) {
            jobDir(context, projectId).deleteRecursively()
        }

        private fun readJob(dir: File): Job {
            val o = JSONObject(File(dir, "job.json").readText())
            val settings = ExportSettings(
                resolution = ExportResolution.valueOf(o.getString("resolution")),
                customShortSide = o.optInt("customShortSide", 1080),
                quality = ExportQuality.valueOf(o.getString("quality")),
                codec = ExportCodec.valueOf(o.getString("codec")),
                burnSubtitles = o.getBoolean("burnSubtitles"),
            )
            val arr = o.getJSONArray("parts")
            val parts = (0 until arr.length()).map {
                val p = arr.getJSONObject(it)
                SplitPart(p.getInt("index"), p.getLong("start"), p.getLong("end"))
            }
            return Job(ProjectStore.fromJson(o.getJSONObject("project")), settings, parts, o.getString("baseName"))
        }

        private fun readDone(dir: File): List<Saved> = runCatching {
            val arr = JSONArray(File(dir, "done.json").readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Saved(o.getInt("index"), o.getString("path"), o.getString("uri"), o.getLong("size"))
            }
        }.getOrDefault(emptyList())

        private fun writeDone(dir: File, done: List<Saved>) {
            val arr = JSONArray()
            done.forEach { arr.put(JSONObject().put("index", it.index).put("path", it.path).put("uri", it.uri).put("size", it.sizeBytes)) }
            val tmp = File(dir, "done.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, "done.json"))
        }
    }
}
