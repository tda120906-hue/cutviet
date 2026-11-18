package com.cutviet.editor.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cutviet.editor.model.TextItem
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** Draws a text or sticker layer into a transparent PNG, so it can become an image overlay (e.g. to erase part of it). */
object TextBitmap {
    /** Renders [t] at the size it has in a frame [frameHeight] pixels tall; returns the file and its size. */
    fun render(t: TextItem, frameHeight: Int, dir: File): Pair<File, Pair<Int, Int>> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(8f, t.size * frameHeight)
            color = if (t.isSticker) android.graphics.Color.WHITE else t.color
            typeface = if (t.bold && !t.isSticker) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = t.text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() }.coerceAtLeast(2)
        val layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
        val h = layout.height.coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        t.background?.takeIf { !t.isSticker }?.let { canvas.drawColor(it) }
        layout.draw(canvas)
        dir.mkdirs()
        val file = File(dir, "text_${t.id}_${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file to (width to h)
    }
}
