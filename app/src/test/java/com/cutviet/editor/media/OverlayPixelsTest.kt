package com.cutviet.editor.media

import com.cutviet.editor.model.ChromaKey
import com.cutviet.editor.model.MaskShape
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.ShapeMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPixelsTest {
    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    private fun filled(w: Int, h: Int, color: Int) = IntArray(w * h) { color }

    @Test
    fun chromaKeyRemovesGreenAndKeepsOtherColours() {
        val px = intArrayOf(0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFF2040C0.toInt())
        OverlayPixels.apply(px, 3, 1, ChromaKey(), null, null)
        assertEquals(0, alpha(px[0]))
        assertEquals(0xFFFF0000.toInt(), px[1])
        assertEquals(0xFF2040C0.toInt(), px[2])
    }

    @Test
    fun nothingToApplyLeavesPixelsAlone() {
        val px = intArrayOf(0x8000FF00.toInt())
        OverlayPixels.apply(px, 1, 1, null, null, null)
        assertEquals(0x8000FF00.toInt(), px[0])
    }

    @Test
    fun circleMaskKeepsCentreAndHidesCorners() {
        val w = 100
        val h = 100
        val px = filled(w, h, 0xFFFFFFFF.toInt())
        OverlayPixels.apply(px, w, h, null, ShapeMask(MaskShape.CIRCLE, size = 0.7f, feather = 0f), null)
        assertEquals(255, alpha(px[50 * w + 50]))
        assertEquals(0, alpha(px[0]))
        assertEquals(0, alpha(px[w * h - 1]))

        val inv = filled(w, h, 0xFFFFFFFF.toInt())
        OverlayPixels.apply(inv, w, h, null, ShapeMask(MaskShape.CIRCLE, size = 0.7f, feather = 0f, invert = true), null)
        assertEquals(0, alpha(inv[50 * w + 50]))
        assertEquals(255, alpha(inv[0]))
    }

    @Test
    fun everyShapeContainsItsCentre() {
        for (shape in listOf(MaskShape.CIRCLE, MaskShape.RECT, MaskShape.HEART, MaskShape.STAR, MaskShape.MIRROR)) {
            val s = OverlayPixels.ShapeSampler(ShapeMask(shape, feather = 0f), 16f / 9f)
            assertTrue("$shape", s.distance(0.5f, 0.5f) < 0f)
            assertTrue("$shape", s.distance(0.999f, 0.999f) > 0f)
        }
    }

    @Test
    fun linearMaskHidesTheTopHalf() {
        val w = 10
        val h = 10
        val px = filled(w, h, 0xFFFFFFFF.toInt())
        OverlayPixels.apply(px, w, h, null, ShapeMask(MaskShape.LINEAR, feather = 0f), null)
        assertEquals(0, alpha(px[0]))
        assertEquals(255, alpha(px[(h - 1) * w]))
    }

    @Test
    fun maskCentreIsMeasuredFromTheTop() {
        val w = 100
        val h = 100
        val px = filled(w, h, 0xFFFFFFFF.toInt())
        OverlayPixels.apply(px, w, h, null, ShapeMask(MaskShape.CIRCLE, cx = 0.2f, cy = 0.2f, size = 0.2f, feather = 0f), null)
        assertEquals(255, alpha(px[20 * w + 20]))
        assertEquals(0, alpha(px[80 * w + 20]))
    }

    @Test
    fun eraserScalesAlpha() {
        val px = intArrayOf(0xFF336699.toInt(), 0xFF336699.toInt())
        OverlayPixels.apply(px, 2, 1, null, null, intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt()))
        assertEquals(0, alpha(px[0]))
        assertEquals(0x80, alpha(px[1]))
        assertEquals(0x336699, px[1] and 0xFFFFFF)
    }

    @Test
    fun flippedOverlayMirrorsItsMask() {
        val o = Overlay(uri = "u", mime = null, isImage = true, sourceDurationMs = 1000, width = 10, height = 10)
        assertNull(OverlayPixels.unflippedMask(o))
        val m = ShapeMask(cx = 0.2f, angle = 30f)
        assertEquals(m, OverlayPixels.unflippedMask(o.copy(mask = m)))
        val flipped = OverlayPixels.unflippedMask(o.copy(mask = m, flipH = true))!!
        assertEquals(0.8f, flipped.cx, 1e-6f)
        assertEquals(-30f, flipped.angle, 1e-6f)
    }
}
