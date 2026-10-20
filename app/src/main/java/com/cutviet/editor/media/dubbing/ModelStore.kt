package com.cutviet.editor.media.dubbing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** On-device models used by dubbing, stored in filesDir/models/. */
enum class DubModel(
    val label: String,
    /**
     * Hugging Face repo and file; the SHA-256 is read from the repo's file list and checked after
     * download. An empty repo means [directUrl] is used and only the size is checked.
     */
    val repo: String,
    val path: String,
    val approxBytes: Long,
    /** Needs a Hugging Face token whose account accepted the model's licence. */
    val gated: Boolean,
    val directUrl: String? = null,
    /** Known SHA-256 of a [directUrl] file, checked after download. */
    val sha256: String? = null,
) {
    WHISPER_LARGE_V3(
        label = "Nhận dạng giọng nói (Whisper large-v3, Q5)",
        repo = "ggerganov/whisper.cpp",
        path = "ggml-large-v3-q5_0.bin",
        approxBytes = 1_080_000_000L,
        gated = false,
    ),

    /** Google's on-device Gemma build for MediaPipe; owner picked it on 2026-10-19. */
    GEMMA_3N_E4B(
        label = "Dịch offline (Gemma 3n E4B, int4)",
        repo = "google/gemma-3n-E4B-it-litert-preview",
        path = "gemma-3n-E4B-it-int4.task",
        approxBytes = 4_400_000_000L,
        gated = true,
    ),

    /** UVR's MDX-Net vocal model (MIT licence), published on the UVR model repo's GitHub releases. */
    MDX_VOC_FT(
        label = "Tách giọng nói khỏi tạp âm (MDX-Net Voc FT)",
        repo = "",
        path = "UVR-MDX-NET-Voc_FT.onnx",
        approxBytes = 66_000_000L,
        gated = false,
        directUrl = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
    ),

    /**
     * 3D-Speaker's CAM++ voice fingerprint model trained on Chinese and English (Apache 2.0), as
     * sherpa-onnx publishes it. Tells people apart for the dub.
     */
    SPEAKER_CAMPP(
        label = "So giọng người nói (CAM++)",
        repo = "",
        path = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        approxBytes = 28_281_164L,
        gated = false,
        directUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        sha256 = "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2",
    );

    fun file(context: Context) = File(ModelStore.dir(context), path)
    fun isReady(context: Context) = file(context).let { it.exists() && it.length() > approxBytes / 2 }
    val url: String get() = directUrl ?: "https://huggingface.co/$repo/resolve/main/$path"
}

object ModelStore {
    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    /** Starts (or resumes) downloading [model]; [wifiOnly] waits for an unmetered network. */
    fun download(context: Context, model: DubModel, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context, model: DubModel) = WorkManager.getInstance(context).cancelUniqueWork(workName(model))

    fun delete(context: Context, model: DubModel) {
        model.file(context).delete()
        File(dir(context), model.path + ".part").delete()
    }

    fun workName(model: DubModel) = "model-" + model.name
}

/**
 * Downloads a model with HTTP range resume into a .part file, checks its SHA-256 against the value
 * Hugging Face publishes for that file, then moves it into place.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = runCatching { DubModel.valueOf(inputData.getString(KEY_MODEL)!!) }.getOrNull()
            ?: return@withContext Result.failure()
        val target = model.file(applicationContext)
        if (model.isReady(applicationContext)) return@withContext Result.success()
        setForeground(info(model, 0f))
        try {
            val (sha, size) = expected(model)
            val part = File(target.path + ".part")
            if (part.length() > size) part.delete()
            var done = part.length()
            if (done < size) {
                val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    authorize(model)
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    if (done > 0) setRequestProperty("Range", "bytes=$done-")
                }
                val code = conn.responseCode
                if (code == 401 || code == 403) return@withContext Result.failure(workDataOf(KEY_ERROR to denied(model)))
                if (code != 200 && code != 206) return@withContext retryOrFail("HTTP $code")
                if (code == 200) done = 0 // server ignored the range: start over
                conn.inputStream.use { input ->
                    FileOutputStream(part, code == 206).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 8_000_000) {
                                lastReport = done
                                val p = done.toFloat() / size
                                setProgress(workDataOf(KEY_PROGRESS to p))
                                setForeground(info(model, p))
                            }
                        }
                    }
                }
            }
            if (part.length() != size) return@withContext retryOrFail("incomplete ${part.length()}/$size")
            setForeground(info(model, 1f, verifying = true))
            if (sha != null && !sha256(part).equals(sha, ignoreCase = true)) {
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Tệp tải về bị lỗi (sai mã kiểm tra), hãy tải lại"))
            }
            target.delete()
            if (!part.renameTo(target)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Không lưu được model"))
            Result.success()
        } catch (e: DeniedException) {
            Result.failure(workDataOf(KEY_ERROR to denied(model)))
        } catch (e: java.io.IOException) {
            DubLog.w(TAG, "download ${model.name}", e)
            retryOrFail(e.message ?: "network")
        }
    }

    private class DeniedException : java.io.IOException("denied")

    private fun HttpURLConnection.authorize(model: DubModel) {
        val token = DubSettings(applicationContext).hfToken
        if (model.gated && token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }

    private fun denied(model: DubModel): String =
        if (DubSettings(applicationContext).hfToken.isBlank()) "Model này cần token Hugging Face. Hãy dán token trong Cài đặt lồng tiếng."
        else "Hugging Face từ chối tải. Hãy mở trang ${model.repo} bằng tài khoản của token và bấm đồng ý giấy phép."

    private fun retryOrFail(reason: String): Result {
        DubLog.w(TAG, "download failed: $reason (attempt $runAttemptCount)")
        return if (runAttemptCount < 5) Result.retry()
        else Result.failure(workDataOf(KEY_ERROR to "Tải model thất bại: $reason"))
    }

    /** SHA-256 (when published) and size of the file. */
    private fun expected(model: DubModel): Pair<String?, Long> {
        if (model.repo.isEmpty()) {
            val head = (URL(model.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            val size = head.contentLengthLong
            head.disconnect()
            if (size <= 0) throw java.io.IOException("no size for ${model.path}")
            return model.sha256 to size
        }
        val conn = URL("https://huggingface.co/api/models/${model.repo}/tree/main").openConnection() as HttpURLConnection
        conn.authorize(model)
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (conn.responseCode == 401 || conn.responseCode == 403) throw DeniedException()
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("path") == model.path) {
                val lfs = o.optJSONObject("lfs") ?: break
                return lfs.getString("oid") to lfs.getLong("size")
            }
        }
        throw java.io.IOException("model not listed")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun info(model: DubModel, progress: Float, verifying: Boolean = false): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tải model", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(model.label)
            .setContentText(if (verifying) "Đang kiểm tra tệp…" else "Đang tải ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "models"
        private const val NOTIFICATION_ID = 4108
        private const val TAG = "CutVietModels"
    }
}
