package com.cutviet.editor.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Music

/** Reads the metadata the editor needs from picked media. */
object MediaProbe {
    const val DEFAULT_IMAGE_MS = 3000L

    fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun probeClip(context: Context, uri: Uri): Clip? {
        val mime = context.contentResolver.getType(uri)
        val isImage = mime?.startsWith("image/") == true ||
            (mime == null && uri.toString().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic"))
        return if (isImage) probeImage(context, uri, mime) else probeVideo(context, uri, mime)
    }

    private fun probeImage(context: Context, uri: Uri, mime: String?): Clip? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        Clip(
            uri = uri.toString(), mime = mime ?: "image/jpeg", isImage = true,
            sourceDurationMs = DEFAULT_IMAGE_MS,
            width = if (swap) opts.outHeight else opts.outWidth,
            height = if (swap) opts.outWidth else opts.outHeight,
            hasAudio = false,
        )
    }.getOrNull()

    private fun probeVideo(context: Context, uri: Uri, mime: String?): Clip? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) { val t = w; w = h; h = t }
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val (fps, videoBitrate) = trackInfo(context, uri)
            return Clip(
                uri = uri.toString(), mime = mime, isImage = false, sourceDurationMs = duration,
                width = w, height = h,
                bitrate = if (videoBitrate > 0) videoBitrate else (bitrate * 0.92).toInt(),
                frameRate = fps, hasAudio = hasAudio,
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Frame rate and (when the container reports it) the video track bitrate. */
    private fun trackInfo(context: Context, uri: Uri): Pair<Float, Int> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                val fps = when {
                    f.containsKey(MediaFormat.KEY_FRAME_RATE) -> runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30f) }
                    else -> 30f
                }
                val br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) runCatching { f.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0) else 0
                return fps.coerceIn(1f, 240f) to br
            }
        } catch (_: Exception) {
        } finally {
            runCatching { ex.release() }
        }
        return 30f to 0
    }

    fun probeMusic(context: Context, uri: Uri): Music? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            return Music(uri = uri.toString(), name = title ?: displayName(context, uri) ?: "Âm thanh", sourceDurationMs = duration)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
