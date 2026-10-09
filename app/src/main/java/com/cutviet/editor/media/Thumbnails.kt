package com.cutviet.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Small cached frames for the timeline and the drafts list. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Frame of [uri] at [timeMs], about [sizePx] on its long side. */
    suspend fun get(context: Context, uri: String, isImage: Boolean, timeMs: Long, sizePx: Int): Bitmap? {
        // Round the time so neighbouring requests share cache entries.
        val t = if (isImage) 0 else (timeMs / 250) * 250
        val key = "$uri@$t@$sizePx"
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            cache.get(key) ?: runCatching {
                if (isImage) loadImage(context, Uri.parse(uri), sizePx) else loadFrame(context, uri, t, sizePx)
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private fun loadFrame(context: Context, uri: String, timeMs: Long, sizePx: Int): Bitmap? {
        val r = retrievers.getOrPut(uri) {
            if (retrievers.size >= 6) {
                val first = retrievers.keys.first()
                retrievers.remove(first)?.let { runCatching { it.release() } }
            }
            MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
        }
        val frame = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return scale(frame, sizePx)
    }

    internal fun loadImage(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val rotated = if (deg != 0f) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        } else bmp
        return scale(rotated, sizePx)
    }

    internal fun scale(b: Bitmap, sizePx: Int): Bitmap {
        val l = max(b.width, b.height)
        if (l <= sizePx) return b
        val k = sizePx.toFloat() / l
        return Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true)
    }

    suspend fun releaseRetrievers() = withContext(dispatcher) {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }
}
