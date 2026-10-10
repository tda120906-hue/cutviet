package com.cutviet.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropOpsTest {
    private fun clip(w: Int = 1920, h: Int = 1080) =
        Clip(uri = "v", mime = "video/mp4", isImage = false, sourceDurationMs = 10_000, width = w, height = h)

    private fun assertWindow(expected: CropSpec, actual: CropSpec) {
        assertEquals(expected.left, actual.left, 1e-4f)
        assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.right, actual.right, 1e-4f)
        assertEquals(expected.bottom, actual.bottom, 1e-4f)
    }

    @Test
    fun legacyCropBecomesAWindow() {
        assertTrue(CropOps.fromLegacy(null, 1f, 0.5f, 0.5f, 1920, 1080).isFull)
        val zoomed = CropOps.fromLegacy(null, 2f, 0.5f, 0.5f, 1920, 1080)
        assertWindow(CropSpec(0.25f, 0.25f, 0.75f, 0.75f), zoomed)
        // A square from a 16:9 frame, pushed to the right edge.
        val square = CropOps.fromLegacy(1f, 1f, 1f, 0.5f, 1920, 1080)
        assertEquals(1f, square.right, 1e-4f)
        assertEquals(1080f / 1920f, square.width, 1e-4f)
        assertEquals(1f, square.aspect)
    }

    @Test
    fun turningMovesTheWindowWithThePicture() {
        val c = clip().copy(crop = CropSpec(0.1f, 0.2f, 0.5f, 0.9f, aspect = 2f))
        val ccw = CropOps.turn(c, clockwise = false)
        assertEquals(270, ccw.rotation)
        // The top-left corner of the picture ends up bottom-left.
        assertWindow(CropSpec(0.2f, 0.5f, 0.9f, 0.9f), ccw.crop)
        assertEquals(0.5f, ccw.crop.aspect!!, 1e-6f)
        val cw = CropOps.turn(c, clockwise = true)
        assertEquals(90, cw.rotation)
        assertWindow(CropSpec(0.1f, 0.1f, 0.8f, 0.5f), cw.crop)
        val back = CropOps.turn(ccw, clockwise = true)
        assertEquals(0, back.rotation)
        assertWindow(c.crop, back.crop)
        assertEquals(2f, back.crop.aspect!!, 1e-6f)
        var r = c
        repeat(4) { r = CropOps.turn(r, clockwise = false) }
        assertEquals(0, r.rotation)
        assertWindow(c.crop, r.crop)
    }

    @Test
    fun mirroringFlipsTheAxisSeenOnScreen() {
        val c = clip().copy(crop = CropSpec(0.1f, 0.2f, 0.5f, 0.9f, straighten = 5f))
        val m = CropOps.mirror(c, horizontal = true)
        assertTrue(m.flipH)
        assertFalse(m.flipV)
        assertWindow(CropSpec(0.5f, 0.2f, 0.9f, 0.9f), m.crop)
        assertEquals(-5f, m.crop.straighten)
        val again = CropOps.mirror(m, horizontal = true)
        assertFalse(again.flipH)
        assertWindow(c.crop, again.crop)
        assertEquals(5f, again.crop.straighten)

        val sideways = CropOps.turn(clip(), clockwise = true)
        val ms = CropOps.mirror(sideways, horizontal = true)
        assertFalse(ms.flipH)
        assertTrue(ms.flipV)
        val upsideDown = CropOps.mirror(clip(), horizontal = false)
        assertTrue(upsideDown.flipV)
    }

    @Test
    fun resetOrientationKeepsTheSameCrop() {
        val original = clip().copy(crop = CropSpec(0.1f, 0.2f, 0.5f, 0.9f))
        val messed = CropOps.mirror(CropOps.turn(CropOps.turn(original, false), false), horizontal = true)
        val back = CropOps.resetOrientation(messed)
        assertEquals(0, back.rotation)
        assertFalse(back.flipH)
        assertFalse(back.flipV)
        assertWindow(original.crop, back.crop)
    }

    @Test
    fun pickingARatioGivesTheLargestCentredWindow() {
        val frame = 16f / 9f
        val sq = CropOps.withAspect(CropSpec(), 1f, frame)
        assertEquals(9f / 16f, sq.width, 1e-4f)
        assertEquals(1f, sq.height, 1e-4f)
        assertEquals(0.5f, (sq.left + sq.right) / 2, 1e-4f)
        assertEquals(1f, sq.aspect)
        // Same ratio as the frame: the whole frame.
        assertTrue(CropOps.withAspect(CropSpec(), frame, frame).isFull)
        // Free keeps the window.
        assertWindow(sq, CropOps.withAspect(sq, null, frame))
        assertEquals(null, CropOps.withAspect(sq, null, frame).aspect)
        // Stays inside when the window was near an edge.
        val edge = CropOps.withAspect(CropSpec(0.8f, 0f, 1f, 1f), 1f, frame)
        assertEquals(1f, edge.right, 1e-4f)
    }

    @Test
    fun freeDragResizesAndStaysInside() {
        val s = CropSpec(0.2f, 0.2f, 0.8f, 0.8f)
        val d = CropOps.drag(s, CropHandle.TOP_LEFT, -0.5f, 0.1f, 1f, 0.1f, 0.1f)
        assertWindow(CropSpec(0f, 0.3f, 0.8f, 0.8f), d)
        val tiny = CropOps.drag(s, CropHandle.RIGHT, -1f, 0f, 1f, 0.1f, 0.1f)
        assertEquals(0.3f, tiny.right, 1e-4f)
        val moved = CropOps.drag(s, CropHandle.MOVE, 0.5f, -0.5f, 1f, 0.1f, 0.1f)
        assertWindow(CropSpec(0.4f, 0f, 1f, 0.6f), moved)
    }

    @Test
    fun lockedDragKeepsTheRatio() {
        val frame = 16f / 9f
        val s = CropOps.withAspect(CropSpec(), 1f, frame).let {
            CropOps.drag(it, CropHandle.BOTTOM_RIGHT, -0.1f, -0.1f, frame, 0.05f, 0.05f)
        }
        fun pixelRatio(c: CropSpec) = c.width * frame / c.height
        assertEquals(1f, pixelRatio(s), 1e-3f)
        for (h in CropHandle.values()) {
            val d = CropOps.drag(s, h, 0.3f, -0.2f, frame, 0.05f, 0.05f)
            assertEquals("$h", 1f, pixelRatio(d), 1e-3f)
            assertTrue("$h", d.left >= -1e-4f && d.top >= -1e-4f && d.right <= 1f + 1e-4f && d.bottom <= 1f + 1e-4f)
        }
    }

    @Test
    fun straighteningCoversTheFrame() {
        assertEquals(1f, CropOps.straightenScale(0f, 1.5f), 1e-6f)
        val m90 = CropOps.straightenMatrix(90f, 1f)
        // A quarter turn clockwise moves the top of the frame to the right.
        assertEquals(1f, m90[0] * 0f + m90[1] * 1f, 1e-4f)
        assertEquals(0f, m90[2] * 0f + m90[3] * 1f, 1e-4f)
        for (aspect in listOf(16f / 9f, 9f / 16f, 1f)) {
            for (deg in listOf(-45f, -12.5f, 3f, 30f, 45f)) {
                val m = CropOps.straightenMatrix(deg, aspect)
                val det = m[0] * m[3] - m[1] * m[2]
                // Every corner of the output frame must come from inside the picture.
                for ((x, y) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
                    val sx = (m[3] * x - m[1] * y) / det
                    val sy = (-m[2] * x + m[0] * y) / det
                    assertTrue("$aspect $deg", abs(sx) <= 1.0001f && abs(sy) <= 1.0001f)
                }
            }
        }
    }

    @Test
    fun canvasFollowsTheFirstClipsCrop() {
        val c = clip().copy(crop = CropSpec(0f, 0f, 0.5f, 1f))
        val p = Project(name = "t", clips = listOf(c))
        assertEquals(960 to 1080, ProjectOps.canvasSize(p))
    }
}
