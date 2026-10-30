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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Background job that dubs lines again ([LineDubber]), one after another (one line, or every line
 * of a person whose voice changes), and writes the new lines to [resultFile]; the editor puts each
 * in place of its old DUB track when the job succeeds. A line that fails is left as it was.
 */
class LineDubWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DubLog.init(applicationContext)
        val batch = runCatching { decodeBatch(File(inputData.getString(KEY_BATCH)!!).readText()) }.getOrNull()
            ?: return Result.failure(error("Thiếu thông tin câu cần lồng lại"))
        if (runAttemptCount > 0) {
            DubLog.w(DubbingPipeline.TAG, "line job $id was interrupted last time, not restarting")
            return Result.failure(error("Lần lồng lại trước bị dừng giữa chừng vì app bị đóng. Hãy thử lại."))
        }
        val dir = DubbingWorker.jobDir(applicationContext, id.toString()).apply { deleteRecursively(); mkdirs() }
        setForeground(foregroundInfo("Đang chuẩn bị", 0f))
        val voice = withContext(Dispatchers.Main) { VoiceEngine(applicationContext) }
        try {
            withTimeoutOrNull(15_000) { while (voice.status.value == VoiceEngine.Status.LOADING) delay(100) }
            val done = JSONArray()
            var online = false
            var note: String? = null
            var lastError: String? = null
            var lastStep: LineDubber.Step? = null
            var lastPercent = -1
            batch.forEachIndexed { k, (trackId, request) ->
                try {
                    val out = LineDubber(applicationContext, File(dir, "work"), voice).run(request) { step, p ->
                        // Progress over the whole batch, one line after another.
                        val overall = (k + p) / batch.size
                        val percent = (overall * 100).toInt()
                        if (step != lastStep || percent != lastPercent) {
                            lastStep = step
                            lastPercent = percent
                            setProgressAsync(workDataOf(KEY_STEP to step.name, KEY_PROGRESS to overall, KEY_LINE to k + 1, KEY_LINES to batch.size))
                            notify(if (batch.size > 1) "${step.label} câu ${k + 1}/${batch.size}" else step.label, overall)
                        }
                    }
                    // The audio file outlives the work folder's scratch files.
                    val kept = File(dir, "line_$k.wav")
                    if (!out.file.renameTo(kept)) out.file.copyTo(kept, overwrite = true)
                    File(dir, "work").deleteRecursively()
                    done.put(encodeOutput(out, kept).put("track", trackId).put("request", encodeRequest(request)))
                    online = online || out.usedOnline
                    if (out.note != null) note = out.note
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = (e as? StepUnavailableException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
                    DubLog.w(DubbingPipeline.TAG, "line job $id: line ${k + 1}/${batch.size} failed", e)
                }
            }
            if (done.length() == 0) return fail(lastError ?: "Không lồng lại được câu nào")
            resultFile(applicationContext, id.toString()).writeText(JSONObject().put("lines", done).toString())
            return Result.success(
                workDataOf(KEY_ONLINE to online, KEY_NOTE to note, KEY_FAILED to batch.size - done.length(), KEY_ERROR to lastError),
            )
        } catch (e: CancellationException) {
            DubLog.i(DubbingPipeline.TAG, "line job $id cancelled")
            throw e
        } catch (e: Exception) {
            DubLog.w(DubbingPipeline.TAG, "line job $id failed", e)
            return Result.failure(error("Lồng lại thất bại: ${e.message}"))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { voice.shutdown() }
        }
    }

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    private fun fail(message: String): Result {
        DubLog.w(DubbingPipeline.TAG, "line job $id failed: $message")
        return Result.failure(error(message))
    }

    private fun notify(text: String, progress: Float) {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(LineDubber.Step.LISTEN.label, 0f)

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
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lồng lại câu", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Lồng lại câu")
            .setContentText(text)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
    }

    /** The new line, as the editor reads it back. */
    class LineResult(
        val trackId: String,
        val file: File, val durationMs: Long, val text: String, val sourceText: String, val emotion: String,
        val geminiVoice: String?, val pitch: Float, val playbackSpeed: Float, val gain: Float,
        val request: LineDubber.Request,
    )

    companion object {
        const val KEY_BATCH = "batch"
        const val KEY_LINE = "line"
        const val KEY_LINES = "lines"
        const val KEY_FAILED = "failed"
        const val KEY_STEP = "step"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_ONLINE = "online"
        const val KEY_NOTE = "note"
        private const val CHANNEL = "redub"
        private const val NOTIFICATION_ID = 4109
        const val UNIQUE_NAME = "redub"

        /** Dubs again each DUB track of [lines] (track id to request), one after another. */
        fun enqueue(context: Context, projectId: String, lines: List<Pair<String, LineDubber.Request>>) {
            val file = File(File(context.filesDir, "dub"), "redub_${System.currentTimeMillis()}.json")
            file.parentFile?.mkdirs()
            file.writeText(encodeBatch(lines).toString())
            val req = OneTimeWorkRequestBuilder<LineDubWorker>()
                .addTag(DubbingWorker.projectTag(projectId))
                .setInputData(workDataOf(KEY_BATCH to file.absolutePath))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun encodeBatch(lines: List<Pair<String, LineDubber.Request>>): JSONArray = JSONArray().apply {
            lines.forEach { (track, r) -> put(JSONObject().put("track", track).put("request", encodeRequest(r))) }
        }

        fun decodeBatch(json: String): List<Pair<String, LineDubber.Request>> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                o.getString("track") to decodeRequest(o.getJSONObject("request").toString())
            }
        }

        fun resultFile(context: Context, jobId: String) = File(DubbingWorker.jobDir(context, jobId), "line.json")

        fun encodeRequest(r: LineDubber.Request): JSONObject = JSONObject().apply {
            put("sourceUri", r.sourceUri); put("startMs", r.startMs); put("endMs", r.endMs); put("roomMs", r.roomMs)
            put("language", r.language.name); r.text?.let { put("text", it) }; put("sourceText", r.sourceText); put("around", r.around)
            put("gemini", r.voice.gemini ?: ""); put("gender", r.voice.gender?.name ?: ""); put("description", r.voice.description)
            put("emotion", r.voice.emotion); put("pitch", r.voice.pitch.toDouble()); put("phoneVoice", r.phoneVoice ?: "")
            put("speaker", r.speaker ?: "")
        }

        fun decodeRequest(json: String): LineDubber.Request {
            val o = JSONObject(json)
            return LineDubber.Request(
                o.getString("sourceUri"), o.getLong("startMs"), o.getLong("endMs"), o.getLong("roomMs"),
                runCatching { SourceLanguage.valueOf(o.getString("language")) }.getOrDefault(SourceLanguage.ENGLISH),
                if (o.has("text")) o.getString("text") else null, o.optString("sourceText"), o.optString("around"),
                LineVoice(
                    o.optString("gemini").ifEmpty { null }, runCatching { Gender.valueOf(o.optString("gender")) }.getOrNull(),
                    o.optString("description"), o.optString("emotion"), o.optDouble("pitch", 1.0).toFloat(),
                ),
                o.optString("phoneVoice").ifEmpty { null },
                o.optString("speaker").ifEmpty { null },
            )
        }

        private fun encodeOutput(out: LineDubber.Output, file: File): JSONObject = JSONObject().apply {
            put("file", file.absolutePath); put("durationMs", out.durationMs); put("text", out.text); put("sourceText", out.sourceText)
            put("emotion", out.emotion); put("geminiVoice", out.geminiVoice ?: ""); put("pitch", out.pitch.toDouble())
            put("playbackSpeed", out.playbackSpeed.toDouble()); put("gain", out.gain.toDouble())
        }

        fun decode(json: String): List<LineResult> {
            val a = JSONObject(json).getJSONArray("lines")
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LineResult(
                    o.getString("track"),
                    File(o.getString("file")), o.getLong("durationMs"), o.getString("text"), o.optString("sourceText"), o.optString("emotion"),
                    o.optString("geminiVoice").ifEmpty { null }, o.optDouble("pitch", 1.0).toFloat(),
                    o.optDouble("playbackSpeed", 1.0).toFloat(), o.optDouble("gain", 1.0).toFloat(),
                    decodeRequest(o.getJSONObject("request").toString()),
                )
            }
        }
    }
}
