package com.cutviet.editor.ui.editor

import androidx.compose.ui.geometry.Offset
import com.cutviet.editor.model.Overlay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Where an overlay is drawn on the preview, in pixels of the preview view. */
internal class Placement(val center: Offset, val width: Float, val height: Float, val rotationDeg: Float) {
    private val rad = Math.toRadians(rotationDeg.toDouble())

    /** Rotates [v] by the overlay's rotation (clockwise on screen). */
    private fun rotateBy(v: Offset, sign: Float): Offset {
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat() * sign
        return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    fun contains(p: Offset): Boolean {
        val local = rotateBy(p - center, -1f)
        return abs(local.x) <= width / 2 && abs(local.y) <= height / 2
    }

    /** The resize handle, at the bottom-right corner. */
    val corner: Offset get() = center + rotateBy(Offset(width / 2, height / 2), 1f)
}

/**
 * The video frame inside the preview view: the player shows the whole output frame fitted in the
 * view, and overlays are placed on it the same way the export places them.
 */
internal class PreviewFrame(boxW: Float, boxH: Float, private val canvasW: Int, private val canvasH: Int) {
    /** View pixels per output pixel. */
    val scale = min(boxW / canvasW, boxH / canvasH)
    val width = canvasW * scale
    val height = canvasH * scale
    val left = (boxW - width) / 2
    val top = (boxH - height) / 2

    /** Placement of [o] in view pixels. */
    fun placement(o: Overlay): Placement {
        // Overlays are first sized to fit the whole frame, then scaled by the user.
        val fit = min(canvasW.toFloat() / o.width.coerceAtLeast(1), canvasH.toFloat() / o.height.coerceAtLeast(1))
        return Placement(
            center = Offset(left + o.x * width, top + o.y * height),
            width = o.width * fit * o.scale * scale,
            height = o.height * fit * o.scale * scale,
            rotationDeg = o.rotation,
        )
    }
}
