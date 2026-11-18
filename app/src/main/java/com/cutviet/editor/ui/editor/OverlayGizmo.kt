package com.cutviet.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import kotlin.math.max

/**
 * Lets the user handle overlays directly on the preview: tap one to select it, drag it to move it,
 * drag the corner handle to resize it.
 */
@Composable
fun OverlayGizmo(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    if (project.clips.isEmpty() || (project.overlays.isEmpty() && project.texts.isEmpty())) return
    val (canvasW, canvasH) = ProjectOps.canvasSize(project)
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val frame = PreviewFrame(boxW, boxH, canvasW, canvasH)
        val frameW = frame.width
        val frameH = frame.height
        val left = frame.left
        val top = frame.top

        fun placementOf(o: Overlay): Placement = frame.placement(o)

        fun visibleAt(o: Overlay, t: Long) = t >= o.startMs && t < o.endMs && o.startMs < vm.project.durationMs

        val place by rememberUpdatedState(::placementOf)
        val t = vm.playheadMs
        val selected = vm.selectedOverlay?.takeIf { visibleAt(it, t) }
        val showBox = selected != null && !vm.preview.isPlaying.value
        val selectedText = vm.editingText?.takeIf { it.id in visibleTexts(project.texts, t).map { v -> v.id } }

        /** Centre and half size (view pixels) of a text layer, as drawn by TextLayerPreview. */
        fun textBox(id: String): Pair<Offset, Size>? {
            val item = vm.project.texts.firstOrNull { it.id == id } ?: return null
            val s = vm.textSizes[id] ?: return null
            return Offset(left + item.x * frameW, top + item.y * frameH) to Size(s.width / 2f, s.height / 2f)
        }
        val boxOf by rememberUpdatedState(::textBox)
        // The preview changes size when a panel opens (e.g. on touching a text); a gesture in
        // progress must survive that, so it reads the current frame instead of restarting.
        val frameNow by rememberUpdatedState(frame)

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val handleRadius = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = vm.playheadMs
                    val visible = vm.project.overlays.filter { visibleAt(it, now) }
                    // Text and stickers lie above the overlays: they get the touch first.
                    val slop = 12.dp.toPx()
                    val texts = visibleTexts(vm.project.texts, now)
                    // The corner handle of the selected text resizes it with one finger.
                    val editingVisible = texts.firstOrNull { it.id == vm.editingTextId }
                    val cornerHit = editingVisible?.takeIf { tx ->
                        boxOf(tx.id)?.let { (c, h) -> (down.position - Offset(c.x + h.width + 4.dp.toPx(), c.y + h.height + 4.dp.toPx())).getDistance() <= handleRadius } ?: false
                    }
                    val hitText = cornerHit ?: (listOfNotNull(editingVisible) + texts.asReversed()).firstOrNull { tx ->
                        boxOf(tx.id)?.let { (c, h) -> abs(down.position.x - c.x) <= h.width + slop && abs(down.position.y - c.y) <= h.height + slop } ?: false
                    }
                    if (hitText != null) {
                        down.consume()
                        vm.preview.pause()
                        // Selected for moving and resizing here; its panel opens only from the timeline.
                        if (vm.editingTextId != hitText.id) vm.pickTextOnPreview(hitText.id)
                        val origin = hitText
                        val center = boxOf(origin.id)?.first ?: down.position
                        val startDistance = max(1f, (down.position - center).getDistance())
                        var started = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            pan += event.calculatePan()
                            zoom *= event.calculateZoom()
                            if (!started && pan.getDistance() < viewConfiguration.touchSlop && abs(zoom - 1f) < 0.02f) continue
                            if (!started) {
                                started = true
                                vm.beginDrag()
                            }
                            event.changes.forEach { it.consume() }
                            val f = frameNow
                            val updated = if (cornerHit != null) {
                                val finger = event.changes.firstOrNull { it.id == down.id }?.position ?: (down.position + pan)
                                val k = (finger - center).getDistance() / startDistance
                                origin.copy(size = (origin.size * k).coerceIn(0.02f, 0.5f))
                            } else {
                                var x = (origin.x + pan.x / f.width).coerceIn(0f, 1f)
                                var y = (origin.y + pan.y / f.height).coerceIn(0f, 1f)
                                if (vm.snapEnabled) {
                                    if (abs(x - 0.5f) < 0.015f) x = 0.5f
                                    if (abs(y - 0.5f) < 0.015f) y = 0.5f
                                }
                                origin.copy(x = x, y = y, size = (origin.size * zoom).coerceIn(0.02f, 0.5f))
                            }
                            vm.dragUpdate(ProjectOps.updateText(vm.project, origin.id) { it.copy(x = updated.x, y = updated.y, size = updated.size) })
                        }
                        return@awaitEachGesture
                    }
                    // A touch on empty picture lets go of a text picked on the preview.
                    if (vm.panel == null && vm.editingTextId != null && visible.none { place(it).contains(down.position) }) vm.editingTextId = null
                    if (visible.isEmpty()) return@awaitEachGesture
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
                            var x = (origin.x + delta.x / frameNow.width).coerceIn(0f, 1f)
                            var y = (origin.y + delta.y / frameNow.height).coerceIn(0f, 1f)
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
            if (selectedText != null && !vm.preview.isPlaying.value) {
                textBox(selectedText.id)?.let { (c, h) ->
                    val pad = 4.dp.toPx()
                    drawRect(
                        Accent,
                        topLeft = Offset(c.x - h.width - pad, c.y - h.height - pad),
                        size = Size(h.width * 2 + pad * 2, h.height * 2 + pad * 2),
                        style = Stroke(2.dp.toPx()),
                    )
                    val corner = Offset(c.x + h.width + pad, c.y + h.height + pad)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = corner)
                    drawCircle(Accent, radius = 7.dp.toPx(), center = corner)
                }
            }
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
