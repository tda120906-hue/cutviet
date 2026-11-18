package com.cutviet.editor.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.cutviet.editor.model.ExportCodec
import com.cutviet.editor.model.ExportSettings
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs a Transformer export on the main looper and saves the result to the gallery. */
class Exporter(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Running(
            val progress: Int, val width: Int, val height: Int, val bitrate: Int,
            /** Time left until this export is done, once it can be told (null at the very start). */
            val etaMs: Long? = null,
        ) : State
        data class Done(val uri: Uri, val path: String, val sizeBytes: Long) : State
        data class Failed(val message: String) : State
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var outFile: File? = null
    private var onState: (State) -> Unit = {}
    private var startedAt = 0L
    private var fileName: String? = null
    private var direct = false

    fun outputSize(project: Project, settings: ExportSettings): Pair<Int, Int> =
        ProjectOps.scaleToShortSide(ProjectOps.canvasSize(project), settings.resolution.shortSide)

    /**
     * Exports [project]. [fileName]: name of the saved video (default: CutViet_<project>_<time>.mp4).
     * [direct]: on Android 11+ write straight into Movies/CutViet instead of the app cache and then
     * copying, so a long video needs its size in free space once, not twice (falls back to the copy).
     */
    fun start(project: Project, settings: ExportSettings, fileName: String? = null, direct: Boolean = false, onState: (State) -> Unit) {
        this.onState = onState
        this.fileName = fileName
        this.direct = direct
        startedAt = android.os.SystemClock.elapsedRealtime()
        run(project, settings, toneMapHdr = false)
    }

    private fun run(project: Project, settings: ExportSettings, toneMapHdr: Boolean) {
        val (w, h) = outputSize(project, settings)
        val bitrate = ProjectOps.targetBitrate(project, w to h, settings.quality)
        val composition = CompositionFactory.build(
            context,
            project,
            CompositionFactory.Options(
                width = w, height = h,
                includeSubtitles = settings.burnSubtitles,
                hdrMode = if (toneMapHdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR,
            ),
        )
        val name = fileName ?: galleryName(project.name)
        val directOut = directFile(name)
        val isDirect = directOut != null
        val file = directOut ?: cacheFile()
        outFile = file

        val mime = if (settings.codec == ExportCodec.H265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(256_000).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(mime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    ExportSpeed.record(context, w, h, project.durationMs, android.os.SystemClock.elapsedRealtime() - startedAt)
                    Thread {
                        val result = runCatching { if (isDirect) publishDirect(file) else saveToGallery(file, name) }
                        handler.post {
                            result.fold(
                                onSuccess = { (uri, path) -> onState(State.Done(uri, path, file.length())) },
                                onFailure = { onState(State.Failed("Không lưu được vào thư viện: ${it.message}")) },
                            )
                        }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    file.delete()
                    if (!toneMapHdr) {
                        // HDR sources or unusual formats: retry once with tone-mapping to SDR.
                        run(project, settings, toneMapHdr = true)
                    } else {
                        onState(State.Failed(describe(exportException)))
                    }
                }
            })
            .build()
        transformer = t
        onState(State.Running(0, w, h, bitrate))
        try {
            t.start(composition, file.absolutePath)
        } catch (e: Exception) {
            transformer = null
            onState(State.Failed("Không bắt đầu xuất được: ${e.message}"))
            return
        }
        pollProgress(w, h, bitrate)
    }

    private fun pollProgress(w: Int, h: Int, bitrate: Int) {
        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = holder.progress
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    // Too early to tell before ~2% and 10 s (encoder start-up skews it).
                    val eta = if (p >= 2 && elapsed > 10_000) elapsed * (100 - p) / p else null
                    onState(State.Running(p, w, h, bitrate, eta))
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        outFile?.delete()
        onState(State.Idle)
    }

    companion object {
        fun safeName(projectName: String): String =
            projectName.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40).ifEmpty { "video" }

        fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        fun galleryName(projectName: String) = "CutViet_${safeName(projectName)}_${stamp()}.mp4"
    }

    private fun describe(e: ExportException): String {
        val code = e.errorCodeName
        val hint = when (e.errorCode) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                "Máy không hỗ trợ mã hóa ở độ phân giải/định dạng này. Hãy thử H.264 hoặc độ phân giải thấp hơn."
            ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Máy không giải mã được một đoạn video trong dự án."
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "Không đọc được tệp gốc (có thể đã bị xóa hoặc mất quyền truy cập)."
            else -> e.message ?: ""
        }
        return "Xuất video thất bại ($code). $hint"
    }

    private fun cacheFile(): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "export_${System.currentTimeMillis()}.mp4")
    }

    /** A file in Movies/CutViet to export into directly, or null to go through the cache. */
    private fun directFile(name: String): File? {
        if (!direct || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CutViet")
            dir.mkdirs()
            val f = File(dir, name)
            // A half-written file of ours from a job that was stopped: start it again.
            if (f.exists()) f.delete()
            // Android 11+ lets an app create media files in Movies/ by path; check it really can.
            if (f.exists() || !f.createNewFile()) null else f
        }.getOrNull()
    }

    /** Makes a video written straight into Movies/CutViet show up in the gallery. */
    private fun publishDirect(file: File): Pair<Uri, String> {
        val latch = java.util.concurrent.CountDownLatch(1)
        var uri: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4")) { _, u ->
            uri = u
            latch.countDown()
        }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        return (uri ?: Uri.fromFile(file)) to "Movies/CutViet/${file.name}"
    }

    /** Copies [file] into Movies/CutViet as [name] and returns its content URI and visible path. */
    private fun saveToGallery(file: File, name: String): Pair<Uri, String> {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CutViet")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Cannot open output")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri to "Movies/CutViet/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CutViet")
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            file.delete()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
            }
            val uri = runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return (uri ?: Uri.fromFile(dest)) to dest.absolutePath
        }
    }
}
