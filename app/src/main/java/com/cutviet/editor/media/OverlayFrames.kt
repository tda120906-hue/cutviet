package com.cutviet.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.cutviet.editor.model.Overlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures of overlays for the editor preview: the frame at a given time with background removal,
 * shape mask and eraser applied, exactly as the export will show it.
 */
object OverlayFrames {
    /** A processed picture and the source time it shows. */
    class Frame(val bitmap: Bitmap, val timeMs: Long, val exact: Boolean)

    private val decoded = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val processed = object : LruCache<String, Frame>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Frame) = value.bitmap.byteCount
    }
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()

    // Kept apart from the timeline thumbnails so a long film strip never delays the preview.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** Long side of the pictures, enough for a phone-sized preview. */
    private const val VIDEO_SIZE = 720
    private const val IMAGE_SIZE = 1080

    /**
     * Cache key for what [get] would return; changes whenever anything that affects the picture
     * changes (each eraser save writes a new file, so its path is enough).
     */
    fun key(o: Overlay, timeMs: Long, exact: Boolean): String =
        "${o.uri}|${if (o.isImage) 0 else timeMs}|${exact || o.isImage}|${o.chroma}|${OverlayPixels.unflippedMask(o)}|${o.eraseMask}"

    fun cached(o: Overlay, timeMs: Long, exact: Boolean): Frame? = processed.get(key(o, timeMs, exact))

    /**
     * The overlay at source time [timeMs]. [exact] decodes the precise frame (slower); otherwise the
     * nearest key frame is used, which is fast enough to follow a finger scrubbing the timeline.
     */
    suspend fun get(context: Context, o: Overlay, timeMs: Long, exact: Boolean): Frame? {
        val k = key(o, timeMs, exact)
        processed.get(k)?.let { return it }
        val raw = decode(context, o, timeMs, exact) ?: return null
        val frame = withContext(Dispatchers.Default) {
            Frame(process(raw, o), if (o.isImage) 0 else timeMs, exact || o.isImage)
        }
        processed.put(k, frame)
        return frame
    }

    private suspend fun decode(context: Context, o: Overlay, timeMs: Long, exact: Boolean): Bitmap? {
        val k = if (o.isImage) "${o.uri}|img" else "${o.uri}|$timeMs|$exact"
        decoded.get(k)?.let { return it }
        return withContext(io) {
            decoded.get(k) ?: runCatching {
                if (o.isImage) Thumbnails.loadImage(context, Uri.parse(o.uri), IMAGE_SIZE)
                else videoFrame(context, o.uri, timeMs, exact)
            }.getOrNull()?.also { decoded.put(k, it) }
        }
    }

    private fun videoFrame(context: Context, uri: String, timeMs: Long, exact: Boolean): Bitmap? {
        val r = retrievers.getOrPut(uri) {
            if (retrievers.size >= 4) {
                val first = retrievers.keys.first()
                retrievers.remove(first)?.let { runCatching { it.release() } }
            }
            MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
        }
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        // The scaled variant keeps the aspect ratio and fits the frame inside the square.
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(timeMs * 1000, option, VIDEO_SIZE, VIDEO_SIZE)
        } else {
            r.getFrameAtTime(timeMs * 1000, option)
        } ?: return null
        return Thumbnails.scale(frame, VIDEO_SIZE)
    }

    private fun process(src: Bitmap, o: Overlay): Bitmap {
        val mask = OverlayPixels.unflippedMask(o)
        val erasePath = o.eraseMask?.takeIf { File(it).exists() }
        if (o.chroma == null && mask == null && erasePath == null) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val erase = erasePath?.let { path ->
            BitmapFactory.decodeFile(path)?.let { bmp ->
                val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
            }
        }
        OverlayPixels.apply(pixels, w, h, o.chroma, mask, erase)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    suspend fun release() = withContext(io) {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }
}
