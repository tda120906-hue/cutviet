package com.cutviet.editor.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Parts of the crop window that can be dragged in the crop editor. */
enum class CropHandle(val moveLeft: Boolean, val moveTop: Boolean, val moveRight: Boolean, val moveBottom: Boolean) {
    MOVE(true, true, true, true),
    LEFT(true, false, false, false),
    TOP(false, true, false, false),
    RIGHT(false, false, true, false),
    BOTTOM(false, false, false, true),
    TOP_LEFT(true, true, false, false),
    TOP_RIGHT(false, true, true, false),
    BOTTOM_LEFT(true, false, false, true),
    BOTTOM_RIGHT(false, false, true, true);

    val isCorner: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * Crop, rotate and mirror operations. All windows are in the turned frame (see [CropSpec]); frame
 * aspect is the turned frame's width/height in pixels.
 */
object CropOps {
    const val MAX_STRAIGHTEN = 45f

    /** Clamps a straighten angle to the supported range. */
    fun clampStraighten(degrees: Float): Float = degrees.coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN)

    /**
     * How much the picture is enlarged when straightened by [degrees] so that a frame of the given
     * aspect stays completely covered.
     */
    fun straightenScale(degrees: Float, frameAspect: Float): Float {
        val r = Math.toRadians(abs(degrees).toDouble())
        val a = frameAspect.coerceAtLeast(1e-3f)
        return (cos(r) + sin(r) * max(a, 1f / a)).toFloat()
    }

    /**
     * The 2x2 matrix (row-major a, b, c, d: x' = a·x + b·y, y' = c·x + d·y) that straightens a frame
     * of the given aspect, in normalized device coordinates (y up). Turns the picture clockwise on
     * screen and enlarges it by [straightenScale].
     */
    fun straightenMatrix(degrees: Float, frameAspect: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val a = frameAspect.coerceAtLeast(1e-3f)
        val k = straightenScale(degrees, a)
        return floatArrayOf(k * c, k * s / a, -k * s * a, k * c)
    }

    /** Turns the clip a quarter turn (the crop window turns with the picture). */
    fun turn(c: Clip, clockwise: Boolean): Clip {
        val w = c.crop
        val crop = if (clockwise) {
            w.copy(left = 1f - w.bottom, top = w.left, right = 1f - w.top, bottom = w.right)
        } else {
            w.copy(left = w.top, top = 1f - w.right, right = w.bottom, bottom = 1f - w.left)
        }.copy(aspect = w.aspect?.let { 1f / it })
        val rotation = ((c.rotation + if (clockwise) 90 else 270) % 360 + 360) % 360
        return c.copy(rotation = rotation, crop = crop)
    }

    /**
     * Mirrors the clip as seen on screen: left-right when [horizontal], else upside down. The flip
     * is stored on the source picture, which is turned afterwards, so for a sideways clip the other
     * axis is flipped.
     */
    fun mirror(c: Clip, horizontal: Boolean): Clip {
        val w = c.crop
        val crop = if (horizontal) {
            w.copy(left = 1f - w.right, right = 1f - w.left)
        } else {
            w.copy(top = 1f - w.bottom, bottom = 1f - w.top)
        }.copy(straighten = -w.straighten)
        val sideways = c.rotation % 180 != 0
        return if (horizontal != sideways) c.copy(flipH = !c.flipH, crop = crop)
        else c.copy(flipV = !c.flipV, crop = crop)
    }

    /** Back to the original orientation, keeping the same crop of the picture. */
    fun resetOrientation(c: Clip): Clip {
        var r = c
        var guard = 0
        while (r.rotation != 0 && guard++ < 4) r = turn(r, clockwise = false)
        if (r.flipH) r = mirror(r, horizontal = true)
        if (r.flipV) r = mirror(r, horizontal = false)
        return r
    }

    /**
     * The largest window with the given pixel [ratio] (width/height) that fits in the frame,
     * centred where the current window is. Null [ratio] only unlocks the window.
     */
    fun withAspect(crop: CropSpec, ratio: Float?, frameAspect: Float): CropSpec {
        if (ratio == null) return crop.copy(aspect = null)
        // Width/height ratio in frame units.
        val rn = ratio / frameAspect.coerceAtLeast(1e-3f)
        val w = min(1f, rn)
        val h = w / rn
        val cx = ((crop.left + crop.right) / 2).coerceIn(w / 2, 1f - w / 2)
        val cy = ((crop.top + crop.bottom) / 2).coerceIn(h / 2, 1f - h / 2)
        return crop.copy(left = cx - w / 2, top = cy - h / 2, right = cx + w / 2, bottom = cy + h / 2, aspect = ratio)
    }

    /**
     * Drags [handle] of the window [start] by ([dx], [dy]) (fractions of the frame). Keeps the window
     * inside the frame, at least [minW] x [minH], and at its locked ratio if it has one.
     */
    fun drag(start: CropSpec, handle: CropHandle, dx: Float, dy: Float, frameAspect: Float, minW: Float, minH: Float): CropSpec {
        if (handle == CropHandle.MOVE) {
            val mx = dx.coerceIn(-start.left, 1f - start.right)
            val my = dy.coerceIn(-start.top, 1f - start.bottom)
            return start.copy(left = start.left + mx, right = start.right + mx, top = start.top + my, bottom = start.bottom + my)
        }
        val ratio = start.aspect
        return if (ratio == null) dragFree(start, handle, dx, dy, minW, minH)
        else dragLocked(start, handle, dx, dy, ratio / frameAspect.coerceAtLeast(1e-3f), minW, minH)
    }

    private fun dragFree(s: CropSpec, h: CropHandle, dx: Float, dy: Float, minW: Float, minH: Float): CropSpec {
        val mw = min(minW, 1f)
        val mh = min(minH, 1f)
        var l = s.left
        var t = s.top
        var r = s.right
        var b = s.bottom
        if (h.moveLeft) l = (s.left + dx).coerceIn(0f, max(0f, s.right - mw))
        if (h.moveRight) r = (s.right + dx).coerceIn(min(1f, s.left + mw), 1f)
        if (h.moveTop) t = (s.top + dy).coerceIn(0f, max(0f, s.bottom - mh))
        if (h.moveBottom) b = (s.bottom + dy).coerceIn(min(1f, s.top + mh), 1f)
        return s.copy(left = l, top = t, right = r, bottom = b)
    }

    /** [rn] is the locked width/height ratio in frame units. */
    private fun dragLocked(s: CropSpec, h: CropHandle, dx: Float, dy: Float, rn: Float, minW: Float, minH: Float): CropSpec {
        // Smallest allowed width that also respects the minimum height.
        val least = max(minW, minH * rn)
        if (h.isCorner) {
            // The opposite corner stays put.
            val ax = if (h.moveLeft) s.right else s.left
            val ay = if (h.moveTop) s.bottom else s.top
            val wFromX = if (h.moveLeft) s.width - dx else s.width + dx
            val wFromY = (if (h.moveTop) s.height - dy else s.height + dy) * rn
            val roomW = if (h.moveLeft) ax else 1f - ax
            val roomH = if (h.moveTop) ay else 1f - ay
            val most = min(roomW, roomH * rn)
            val w = ((wFromX + wFromY) / 2).coerceIn(min(least, most), most)
            val hh = w / rn
            val l = if (h.moveLeft) ax - w else ax
            val t = if (h.moveTop) ay - hh else ay
            return s.copy(left = l, top = t, right = l + w, bottom = t + hh)
        }
        if (h == CropHandle.LEFT || h == CropHandle.RIGHT) {
            // Width follows the finger, height follows the ratio around the same middle.
            val cy = (s.top + s.bottom) / 2
            val roomW = if (h == CropHandle.LEFT) s.right else 1f - s.left
            val most = min(roomW, 2 * min(cy, 1f - cy) * rn)
            val w = (if (h == CropHandle.LEFT) s.width - dx else s.width + dx).coerceIn(min(least, most), most)
            val hh = w / rn
            val l = if (h == CropHandle.LEFT) s.right - w else s.left
            return s.copy(left = l, right = l + w, top = cy - hh / 2, bottom = cy + hh / 2)
        }
        val cx = (s.left + s.right) / 2
        val roomH = if (h == CropHandle.TOP) s.bottom else 1f - s.top
        val most = min(roomH * rn, 2 * min(cx, 1f - cx))
        val w = ((if (h == CropHandle.TOP) s.height - dy else s.height + dy) * rn).coerceIn(min(least, most), most)
        val hh = w / rn
        val t = if (h == CropHandle.TOP) s.bottom - hh else s.top
        return s.copy(top = t, bottom = t + hh, left = cx - w / 2, right = cx + w / 2)
    }

    /**
     * Converts a crop saved by versions before 1.0.8 (ratio, zoom and centre inside the turned
     * frame of [frameW] x [frameH]) to a window.
     */
    fun fromLegacy(aspect: Float?, zoom: Float, centerX: Float, centerY: Float, frameW: Int, frameH: Int): CropSpec {
        val w = frameW.toFloat().coerceAtLeast(1f)
        val h = frameH.toFloat().coerceAtLeast(1f)
        val ratio = aspect ?: (w / h)
        var cw: Float
        var ch: Float
        if (w / h > ratio) {
            ch = h; cw = h * ratio
        } else {
            cw = w; ch = w / ratio
        }
        val z = zoom.coerceAtLeast(1f)
        cw /= z; ch /= z
        val nw = (cw / w).coerceIn(0f, 1f)
        val nh = (ch / h).coerceIn(0f, 1f)
        val cx = centerX.coerceIn(nw / 2, 1 - nw / 2)
        val cy = centerY.coerceIn(nh / 2, 1 - nh / 2)
        return CropSpec(left = cx - nw / 2, top = cy - nh / 2, right = cx + nw / 2, bottom = cy + nh / 2, aspect = aspect)
    }
}
