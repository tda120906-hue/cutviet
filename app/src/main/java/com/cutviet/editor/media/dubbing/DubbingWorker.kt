package com.cutviet.editor.media.dubbing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.NonCancellable
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

            val sourceStart = inputData.getLong(KEY_START, 0)
            val sourceEnd = inputData.getLong(KEY_END, 0)
            val pipeline = DubbingPipeline(
                separator = Engines.separator(applicationContext, dir),
                online = Engines.online(applicationContext, dir),
                context = applicationContext,
                asr = Engines.speechToText(applicationContext, dir),
                translator = Engines.translator(applicationContext),
                voice = voice,
                workDir = dir,
            )
            var lastStep: DubbingPipeline.Step? = null
            var lastPercent = -1
            val out = pipeline.run(source, sourceStart, sourceEnd, DubbingPipeline.Settings(language, inputData.getString(KEY_VOICE))) { step, p ->
                // Only report whole-percent changes: the system drops notifications sent too often.
                val percent = (p * 100).toInt()
                if (step != lastStep || percent != lastPercent) {
                    lastStep = step
                    lastPercent = percent
                    setProgressAsync(workDataOf(KEY_STEP to step.name, KEY_PROGRESS to p))
                    notify(step.label, p)
                }
            }
            val timings = pipeline.timings.entries.joinToString { "${it.key.name}=${it.value}ms" }
            Log.i(DubbingPipeline.TAG, "job $id: ${out.segments.size} lines, separated=${out.stems != null}, online=${out.usedOnline}, fallback=${out.fallbackReason}, $timings")
            if (out.segments.isEmpty()) return Result.failure(error("Không nghe thấy lời nói nào trong video"))
            resultFile(applicationContext, id.toString()).writeText(encode(out, sourceStart, pipeline.timings).toString())
            return Result.success(
                workDataOf(
                    KEY_COUNT to out.segments.size, KEY_SEPARATED to (out.stems != null),
                    KEY_ONLINE to out.usedOnline, KEY_FALLBACK to out.fallbackReason,
                ),
            )
        } catch (e: StepUnavailableException) {
            return Result.failure(error(e.message ?: "Bước này chưa dùng được trên máy"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(error("Lồng tiếng thất bại: ${e.message}"))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { voice.shutdown() }
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
        const val KEY_SEPARATED = "separated"
        const val KEY_ONLINE = "online"
        const val KEY_FALLBACK = "fallback"
        private const val CHANNEL = "dubbing"
        private const val NOTIFICATION_ID = 4107
        /** One dubbing job at a time. */
        const val UNIQUE_NAME = "dubbing"

        fun projectTag(projectId: String) = "project:$projectId"

        /**
         * Dubs [startMs]..[endMs] of the source (its trimmed part on the timeline). The project,
         * clip and original-voice level ride along as tags so the editor can place the result even
         * after the app was closed while the job ran.
         */
        fun enqueue(
            context: Context, sourceUri: String, startMs: Long, endMs: Long, language: SourceLanguage, voiceName: String?,
            projectId: String, clipId: String, originalVolume: Float,
        ): java.util.UUID {
            val req = OneTimeWorkRequestBuilder<DubbingWorker>()
                .addTag(projectTag(projectId))
                .addTag("clip:$clipId")
                .addTag("orig:$originalVolume")
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

        /** What a finished job hands back to the editor. */
        class JobResult(val segments: List<DubbedSegment>, val stems: Stems?, val sourceStartMs: Long, val gain: Float)

        fun encode(out: DubbingPipeline.Output, sourceStartMs: Long, timings: Map<DubbingPipeline.Step, Long>): JSONObject =
            JSONObject().apply {
                put("segments", JSONArray().apply {
                    out.segments.forEach { s ->
                        put(JSONObject().apply {
                            put("startMs", s.startMs); put("slotMs", s.slotMs)
                            put("sourceText", s.sourceText); put("text", s.text)
                            put("file", s.file.absolutePath); put("durationMs", s.durationMs); put("rate", s.rate.toDouble())
                            put("playbackSpeed", s.playbackSpeed.toDouble())
                        })
                    }
                })
                out.stems?.let { st ->
                    put("stems", JSONObject().apply {
                        put("voice", st.voice.absolutePath); put("background", st.background.absolutePath); put("durationMs", st.durationMs)
                    })
                }
                put("sourceStartMs", sourceStartMs)
                put("gain", out.gain.toDouble())
                put("timings", JSONObject().apply { timings.forEach { (k, v) -> put(k.name, v) } })
            }

        fun decode(json: String): JobResult {
            val root = JSONObject(json)
            val arr = root.getJSONArray("segments")
            val segments = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DubbedSegment(
                    o.getLong("startMs"), o.getLong("slotMs"), o.getString("sourceText"), o.getString("text"),
                    File(o.getString("file")), o.getLong("durationMs"), o.getDouble("rate").toFloat(),
                    o.optDouble("playbackSpeed", 1.0).toFloat(),
                )
            }
            val stems = root.optJSONObject("stems")?.let {
                Stems(File(it.getString("voice")), File(it.getString("background")), it.getLong("durationMs"))
            }
            return JobResult(segments, stems, root.optLong("sourceStartMs"), root.optDouble("gain", 1.0).toFloat())
        }
    }
}

/** Chooses the offline speech-to-text and translation engines. */
object Engines {
    /**
     * Router for the online branch. Offline: never. Online first: whenever a key is set and the phone
     * is online. Auto: online on Wi-Fi, or on mobile data when the offline models are missing.
     */
    fun online(context: Context, workDir: File): GeminiLiveTranslator? {
        val s = DubSettings(context)
        if (s.mode == DubMode.OFFLINE) return null
        if (s.simulateQuota) return GeminiLiveTranslator(s.geminiKey, s.geminiModel, workDir, simulateQuota = true)
        if (s.geminiKey.isBlank()) return null
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return null
        val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val offlineReady = DubModel.WHISPER_LARGE_V3.isReady(context) && DubModel.GEMMA_3N_E4B.isReady(context)
        val use = when (s.mode) {
            DubMode.ONLINE_FIRST -> true
            DubMode.AUTO -> unmetered || !offlineReady
            DubMode.OFFLINE -> false
        }
        return if (use) GeminiLiveTranslator(s.geminiKey, s.geminiModel, workDir) else null
    }

    /** Null when the separation model is not downloaded: the dub then only turns the whole clip down. */
    fun separator(context: Context, workDir: File): VoiceSeparator? {
        val model = DubModel.MDX_VOC_FT
        return if (model.isReady(context)) VoiceSeparator(context, model.file(context), workDir) else null
    }

    fun speechToText(context: Context, workDir: File): SpeechToText {
        val model = DubModel.WHISPER_LARGE_V3
        if (!model.isReady(context)) {
            return object : SpeechToText {
                override suspend fun transcribe(source: AudioSource, language: SourceLanguage, progress: (Float) -> Unit) =
                    throw StepUnavailableException("Chưa tải model nhận dạng giọng nói (${model.label})")
            }
        }
        return WhisperAsr(context, model.file(context), workDir)
    }

    fun translator(context: Context): TextTranslator {
        val model = DubModel.GEMMA_3N_E4B
        if (!model.isReady(context)) {
            return object : TextTranslator {
                override suspend fun translate(segments: List<SpeechSegment>, language: SourceLanguage, progress: (Float) -> Unit) =
                    throw StepUnavailableException("Chưa tải model dịch (${model.label})")
            }
        }
        return GemmaTranslator(context, model.file(context))
    }
}
