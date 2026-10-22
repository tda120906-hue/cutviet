package com.cutviet.editor.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.cutviet.editor.model.Subtitle
import com.cutviet.editor.model.SubtitleCues
import com.cutviet.editor.model.SubtitleStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws one subtitle line as a bitmap for a frame of [frameW]×[frameH]: centred, wrapped to the
 * frame width, with a black outline or a dark box. The editor preview and the export use the same
 * drawing, so the preview shows exactly what will be burned in.
 */
object SubtitleRenderer {
    /** Text is wrapped to this share of the frame width. */
    const val WIDTH = 0.88f

    fun textPx(style: SubtitleStyle, frameW: Int, frameH: Int): Float = max(8f, style.size * min(frameW, frameH))

    fun render(text: String, style: SubtitleStyle, frameW: Int, frameH: Int): Bitmap {
        val px = textPx(style, frameW, frameH)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val maxW = (frameW * WIDTH).roundToInt().coerceAtLeast(16)
        val layout = layout(text.trim(), paint, maxW)
        var textW = 0f
        for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
        val pad = if (style.box) px * 0.35f else px * 0.12f
        val w = (ceil(textW) + pad * 2).toInt().coerceIn(2, frameW)
        val h = (layout.height + pad * 2).toInt().coerceAtLeast(2)
        // Lay out again at the real width so lines centre inside the bitmap.
        val inner = (w - pad * 2).toInt().coerceAtLeast(1)
        val l = layout(text.trim(), paint, inner)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (style.box) {
            val r = px * 0.25f
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        }
        c.translate(pad, pad)
        if (!style.box) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = px * 0.14f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xFF000000.toInt()
            l.draw(c)
        }
        paint.style = Paint.Style.FILL
        paint.color = style.color
        l.draw(c)
        return bmp
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
}

/**
 * Burns [subs] into the video: the line due at each frame's time, bottom-centred at the style's
 * height. A [TextOverlay] so Media3 also handles it on HDR video; it hands out its own bitmap.
 * Frame times must be timeline times (it is applied to the full-length frame-clock layer).
 */
class SubtitleOverlay(private val subs: List<Subtitle>, private val style: SubtitleStyle) : TextOverlay() {
    private var frame = Size(1920, 1080)
    private var shownId: String? = null
    private var shown: Bitmap? = null
    private val empty: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 1f - style.y.coerceIn(0.05f, 1f) * 2f)
        .setOverlayFrameAnchor(0f, -1f)
        .build()

    override fun configure(videoSize: Size) {
        if (videoSize != frame) {
            frame = videoSize
            shownId = null
        }
    }

    override fun getText(presentationTimeUs: Long): SpannableString =
        SpannableString(SubtitleCues.at(subs, presentationTimeUs / 1000)?.text ?: " ")

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val sub = SubtitleCues.at(subs, presentationTimeUs / 1000)?.takeIf { it.text.isNotBlank() } ?: return empty
        if (sub.id != shownId) {
            shown?.recycle()
            shown = SubtitleRenderer.render(sub.text, style, frame.width, frame.height)
            shownId = sub.id
        }
        return shown!!
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
