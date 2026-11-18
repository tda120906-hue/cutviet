package com.cutviet.editor.ui.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.TextItem
import kotlin.math.roundToInt

/** Text and sticker layers shown at [timeMs]. */
internal fun visibleTexts(texts: List<TextItem>, timeMs: Long): List<TextItem> =
    texts.filter { timeMs >= it.startMs && timeMs < it.endMs && it.text.isNotBlank() }

/**
 * Draws the text and sticker layers under the playhead over the preview, placed and sized like the
 * export (centre at x/y, size as a fraction of the frame height). Moving and resizing them is done
 * by [OverlayGizmo], which reads the sizes measured here.
 */
@Composable
fun TextLayerPreview(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    if (project.clips.isEmpty() || project.texts.isEmpty() || vm.trim != null) return
    val visible = visibleTexts(project.texts, vm.playheadMs)
    if (visible.isEmpty()) return
    val (canvasW, canvasH) = ProjectOps.canvasSize(project)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val frame = PreviewFrame(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), canvasW, canvasH)
        Layout(
            content = {
                visible.forEach { t ->
                    key(t.id) {
                        val px = (t.size * frame.height).coerceAtLeast(4f)
                        Text(
                            t.text,
                            style = TextStyle(
                                color = if (t.isSticker) Color.White else Color(t.color),
                                fontSize = with(density) { px.toSp() },
                                fontWeight = if (t.bold && !t.isSticker) FontWeight.Bold else FontWeight.Normal,
                                background = t.background?.takeIf { !t.isSticker }?.let { Color(it) } ?: Color.Unspecified,
                            ),
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p ->
                    val t = visible[i]
                    IntSize(p.width, p.height).let { sz -> if (vm.textSizes[t.id] != sz) vm.textSizes[t.id] = sz }
                    val cx = frame.left + t.x * frame.width
                    val cy = frame.top + t.y * frame.height
                    p.place(IntOffset((cx - p.width / 2f).roundToInt(), (cy - p.height / 2f).roundToInt()))
                }
            }
        }
    }
}
