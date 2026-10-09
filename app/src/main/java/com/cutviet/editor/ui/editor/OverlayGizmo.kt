package com.cutviet.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.ui.theme.Accent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where an overlay is drawn on the preview, in pixels of the preview view. */
private class Placement(val center: Offset, val width: Float, val height: Float, val rotationDeg: Float) {
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
 * Lets the user handle overlays directly on the preview: tap one to select it, drag it to move it,
 * drag the corner handle to resize it.
 */
@Composable
fun OverlayGizmo(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    if (project.clips.isEmpty() || project.overlays.isEmpty()) return
    val (canvasW, canvasH) = ProjectOps.canvasSize(project)
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // The player shows the whole video frame fitted inside the view.
        val s = min(boxW / canvasW, boxH / canvasH)
        val frameW = canvasW * s
        val frameH = canvasH * s
        val left = (boxW - frameW) / 2
        val top = (boxH - frameH) / 2

        fun placementOf(o: Overlay): Placement {
            val fit = min(canvasW.toFloat() / o.width.coerceAtLeast(1), canvasH.toFloat() / o.height.coerceAtLeast(1))
            return Placement(
                center = Offset(left + o.x * frameW, top + o.y * frameH),
                width = o.width * fit * o.scale * s,
                height = o.height * fit * o.scale * s,
                rotationDeg = o.rotation,
            )
        }

        fun visibleAt(o: Overlay, t: Long) = t >= o.startMs && t < o.endMs && o.startMs < vm.project.durationMs

        val place by rememberUpdatedState(::placementOf)
        val t = vm.playheadMs
        val selected = vm.selectedOverlay?.takeIf { visibleAt(it, t) }
        val showBox = selected != null && !vm.preview.isPlaying.value

        Canvas(
            Modifier.fillMaxSize().pointerInput(canvasW, canvasH, boxW, boxH) {
                val handleRadius = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = vm.playheadMs
                    val visible = vm.project.overlays.filter { visibleAt(it, now) }
                    val current = vm.selectedOverlay?.takeIf { o -> visible.any { it.id == o.id } }
                    var resizing = false
                    val target: Overlay? = when {
                        current != null && (down.position - place(current).corner).getDistance() <= handleRadius -> {
                            resizing = true
                            current
                        }
                        current != null && place(current).contains(down.position) -> current
                        // The topmost layer under the finger.
                        else -> visible.lastOrNull { place(it).contains(down.position) }
                    }
                    if (target == null) return@awaitEachGesture
                    down.consume()
                    vm.preview.pause()
                    vm.selectOverlay(target.id, toggle = false)
                    vm.beginDrag()
                    val origin = target
                    val center = place(origin).center
                    val startDistance = max(1f, (down.position - center).getDistance())
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - down.position
                        if (!moved && delta.getDistance() < viewConfiguration.touchSlop) continue
                        moved = true
                        change.consume()
                        if (resizing) {
                            val scale = (origin.scale * (change.position - center).getDistance() / startDistance).coerceIn(0.05f, 4f)
                            vm.dragUpdate(ProjectOps.updateOverlay(vm.project, origin.id) { it.copy(scale = scale) })
                        } else {
                            var x = (origin.x + delta.x / frameW).coerceIn(0f, 1f)
                            var y = (origin.y + delta.y / frameH).coerceIn(0f, 1f)
                            // Settle on the centre lines when close to them.
                            if (vm.snapEnabled) {
                                if (abs(x - 0.5f) < 0.015f) x = 0.5f
                                if (abs(y - 0.5f) < 0.015f) y = 0.5f
                            }
                            vm.dragUpdate(ProjectOps.updateOverlay(vm.project, origin.id) { it.copy(x = x, y = y) })
                        }
                    }
                }
            },
        ) {
            if (!showBox || selected == null) return@Canvas
            val p = placementOf(selected)
            val stroke = 2.dp.toPx()
            rotate(p.rotationDeg, pivot = p.center) {
                drawRect(
                    Accent,
                    topLeft = Offset(p.center.x - p.width / 2, p.center.y - p.height / 2),
                    size = Size(p.width, p.height),
                    style = Stroke(stroke),
                )
                val corner = Offset(p.center.x + p.width / 2, p.center.y + p.height / 2)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = corner)
                drawCircle(Accent, radius = 7.dp.toPx(), center = corner)
            }
            if (vm.snapEnabled && p.center.x == left + 0.5f * frameW) {
                drawLine(Accent.copy(alpha = 0.5f), Offset(p.center.x, top), Offset(p.center.x, top + frameH), strokeWidth = 1.dp.toPx())
            }
            if (vm.snapEnabled && p.center.y == top + 0.5f * frameH) {
                drawLine(Accent.copy(alpha = 0.5f), Offset(left, p.center.y), Offset(left + frameW, p.center.y), strokeWidth = 1.dp.toPx())
            }
        }
    }
}
