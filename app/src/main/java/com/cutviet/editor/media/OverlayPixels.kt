package com.cutviet.editor.media

import com.cutviet.editor.model.ChromaKey
import com.cutviet.editor.model.MaskShape
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.ShapeMask
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU version of [ChromaKeyEffect] and [MaskEffect], used to show overlays on the editor preview.
 * Works on ARGB pixels (top row first, alpha not premultiplied) and gives the same result as the
 * shaders used for export.
 */
object OverlayPixels {

    /**
     * The overlay's shape mask in its own (unflipped) orientation. Masks are placed on the picture
     * as the user sees it, so their position is mirrored when the overlay is flipped.
     */
    fun unflippedMask(o: Overlay): ShapeMask? =
        o.mask?.let { m -> if (o.flipH) m.copy(cx = 1f - m.cx, angle = -m.angle) else m }

    /**
     * Applies background removal, the shape mask and the eraser drawing to [pixels] in place.
     * [mask] is in the overlay's own (unflipped) orientation, like [MaskEffect] receives it;
     * [erase] holds one ARGB pixel per image pixel and only its alpha is used.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, chroma: ChromaKey?, mask: ShapeMask?, erase: IntArray?) {
        if (chroma == null && mask == null && erase == null) return
        val key = chroma?.let { chromaOf(it.color) }
        val threshold = chroma?.let { 0.02f + it.strength.coerceIn(0f, 1f) * 0.4f } ?: 0f
        val smooth = chroma?.let { 0.005f + it.softness.coerceIn(0f, 1f) * 0.2f } ?: 0f
        val shape = mask?.let { ShapeSampler(it, width.toFloat() / max(1, height)) }
        for (y in 0 until height) {
            // Texture coordinates have y pointing up.
            val v = 1f - (y + 0.5f) / height
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val c = pixels[i]
                var a = ((c ushr 24) and 0xFF) / 255f
                var r = ((c shr 16) and 0xFF) / 255f
                var g = ((c shr 8) and 0xFF) / 255f
                var b = (c and 0xFF) / 255f
                if (key != null) {
                    val cb = -0.169f * r - 0.331f * g + 0.5f * b
                    val cr = 0.5f * r - 0.419f * g - 0.081f * b
                    val dx = cb - key[0]
                    val dy = cr - key[1]
                    val k = smoothstep(threshold, threshold + smooth, sqrt(dx * dx + dy * dy))
                    if (k < 1f) {
                        // Reduce colour spill from the screen on the remaining edges.
                        val gray = 0.299f * r + 0.587f * g + 0.114f * b
                        r = gray + (r - gray) * k
                        g = gray + (g - gray) * k
                        b = gray + (b - gray) * k
                    }
                    a *= k
                }
                if (shape != null) a *= shape.alpha((x + 0.5f) / width, v)
                if (erase != null) a *= ((erase[i] ushr 24) and 0xFF) / 255f
                pixels[i] = (to8(a) shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
    }

    private fun to8(f: Float): Int = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun chromaOf(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return floatArrayOf(-0.169f * r - 0.331f * g + 0.5f * b, 0.5f * r - 0.419f * g - 0.081f * b)
    }

    internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How visible each point of the overlay is under a shape mask. */
    class ShapeSampler(private val m: ShapeMask, private val aspect: Float) {
        private val cx = m.cx
        private val cy = 1f - m.cy
        private val size = m.size.coerceIn(0.02f, 2f)
        private val feather = 0.002f + m.feather.coerceIn(0f, 1f) * 0.25f
        private val round = m.roundness.coerceIn(0f, 1f)
        private val angle = Math.toRadians(m.angle.toDouble())
        private val cosA = cos(angle).toFloat()
        private val sinA = sin(angle).toFloat()

        /** Visibility at texture coordinate ([u], [v]), v pointing up. */
        fun alpha(u: Float, v: Float): Float {
            val a = 1f - OverlayPixels.smoothstep(-feather, feather, distance(u, v))
            return if (m.invert) 1f - a else a
        }

        /** Signed distance to the shape edge (negative inside), in units of the frame height. */
        fun distance(u: Float, v: Float): Float {
            val qx = (u - cx) * aspect
            val qy = v - cy
            val px = cosA * qx - sinA * qy
            val py = sinA * qx + cosA * qy
            val hs = 0.5f * size * min(aspect, 1f)
            return when (m.shape) {
                MaskShape.CIRCLE -> sqrt(px * px + py * py) - hs
                MaskShape.RECT -> {
                    val bx = 0.5f * aspect * size
                    val by = 0.5f * size
                    val r = round * min(bx, by)
                    val ex = abs(px) - bx + r
                    val ey = abs(py) - by + r
                    val ox = max(ex, 0f)
                    val oy = max(ey, 0f)
                    sqrt(ox * ox + oy * oy) + min(max(ex, ey), 0f) - r
                }
                MaskShape.HEART -> {
                    val k = 2f * hs / 1.1f
                    heart(px / k, py / k + 0.55f) * k
                }
                MaskShape.STAR -> star(px, py, hs, 0.45f)
                MaskShape.LINEAR -> py
                MaskShape.MIRROR -> abs(py) - 0.5f * size
            }
        }

        private fun heart(x0: Float, y: Float): Float {
            val x = abs(x0)
            if (y + x > 1f) {
                val dx = x - 0.25f
                val dy = y - 0.75f
                return sqrt(dx * dx + dy * dy) - sqrt(2f) / 4f
            }
            val ax = x
            val ay = y - 1f
            val s = 0.5f * max(x + y, 0f)
            val bx = x - s
            val by = y - s
            return sqrt(min(ax * ax + ay * ay, bx * bx + by * by)) * sign(x - y)
        }

        private fun star(x0: Float, y0: Float, r: Float, rf: Float): Float {
            val k1x = 0.809016994375f
            val k1y = -0.587785252292f
            val k2x = -k1x
            val k2y = k1y
            var x = abs(x0)
            var y = y0
            var d = 2f * max(k1x * x + k1y * y, 0f)
            x -= d * k1x; y -= d * k1y
            d = 2f * max(k2x * x + k2y * y, 0f)
            x -= d * k2x; y -= d * k2y
            x = abs(x)
            y -= r
            val bax = rf * -k1y
            val bay = rf * k1x - 1f
            val h = ((x * bax + y * bay) / (bax * bax + bay * bay)).coerceIn(0f, r)
            val lx = x - bax * h
            val ly = y - bay * h
            return sqrt(lx * lx + ly * ly) * sign(y * bax - x * bay)
        }
    }
}
