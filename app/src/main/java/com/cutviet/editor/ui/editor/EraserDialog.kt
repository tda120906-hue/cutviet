package com.cutviet.editor.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim
import kotlin.math.max
import kotlin.math.roundToInt

private const val MASK_LONG_SIDE = 720
private const val MAX_STROKE_UNDO = 15

/**
 * Full-screen eraser for an overlay: paint over the parts to hide ("Tẩy"), or paint them back
 * ("Khôi phục"). The drawing is a mask image whose alpha is how much of each pixel stays visible;
 * it is stored in the overlay's own (unflipped) orientation.
 */
@Composable
fun EraserDialog(vm: EditorViewModel, overlay: Overlay) {
    val context = LocalContext.current
    val k = MASK_LONG_SIDE.toFloat() / max(overlay.width, overlay.height).coerceAtLeast(1)
    val maskW = max(16, (overlay.width * k).roundToInt())
    val maskH = max(16, (overlay.height * k).roundToInt())
    val mask = remember(overlay.id) { loadMask(overlay.eraseMask, maskW, maskH) }
    val maskImage = remember(mask) { mask.asImageBitmap() }
    val strokeUndo = remember(overlay.id) { ArrayDeque<Bitmap>() }
    var version by remember { mutableIntStateOf(0) }
    var erasing by remember { mutableStateOf(true) }
    var moving by remember { mutableStateOf(false) }
    var brush by remember { mutableFloatStateOf(0.06f) }
    var softness by remember { mutableFloatStateOf(0.3f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }

    // Show the frame under the playhead when it is inside the overlay, else its first frame.
    val frameTimeMs = remember(overlay.id) {
        val t = vm.playheadMs
        if (!overlay.isImage && t in overlay.startMs until overlay.endMs) {
            overlay.trimStartMs + ((t - overlay.startMs) * overlay.speed).toLong()
        } else overlay.trimStartMs
    }
    val frame by produceState<Bitmap?>(null, overlay.id) {
        value = Thumbnails.get(context, overlay.uri, overlay.isImage, frameTimeMs, 1080)
    }

    fun pushUndo() {
        strokeUndo.addLast(mask.copy(Bitmap.Config.ARGB_8888, false))
        while (strokeUndo.size > MAX_STROKE_UNDO) strokeUndo.removeFirst()
    }

    fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (brush * maskW).coerceAtLeast(1f)
        color = android.graphics.Color.WHITE
        if (erasing) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        if (softness > 0.02f) maskFilter = BlurMaskFilter(strokeWidth * 0.5f * softness + 0.5f, BlurMaskFilter.Blur.NORMAL)
    }

    fun drawSegment(p: Paint, a: Offset, b: Offset) {
        val canvas = android.graphics.Canvas(mask)
        if (a == b) {
            val dot = Paint(p).apply { style = Paint.Style.FILL }
            canvas.drawCircle(a.x, a.y, p.strokeWidth / 2f, dot)
        } else {
            canvas.drawLine(a.x, a.y, b.x, b.y, p)
        }
    }

    Dialog(
        onDismissRequest = { vm.closeEraser() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.closeEraser() }) { Text("Hủy", color = Color.White) }
                Text(
                    "Tẩy một phần lớp phủ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.saveEraseMask(overlay.id, if (isFullyOpaque(mask)) null else mask.copy(Bitmap.Config.ARGB_8888, false)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("Xong", fontWeight = FontWeight.Bold) }
            }
            Text(
                if (moving) "Kéo một ngón để di chuyển, chụm hai ngón để phóng to." else if (erasing) "Tô lên phần muốn xóa khỏi lớp phủ." else "Tô lên phần đã xóa để hiện lại.",
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )

            // Drawing area.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp).clipToBounds()
                    .pointerInput(moving) {
                        if (!moving) return@pointerInput
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                            pan += panChange
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val ratio = maskW.toFloat() / maskH
                val boxW = maxWidth
                val boxH = maxHeight
                val (w, h) = if (boxW / boxH > ratio) (boxH * ratio) to boxH else boxW to (boxW / ratio)
                Box(
                    Modifier.size(w, h).graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = pan.x; translationY = pan.y
                    },
                ) {
                    // Checkerboard shows through where the overlay has been erased.
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = 12.dp.toPx()
                        drawRect(Color(0xFF9A9A9A))
                        var y = 0f
                        var row = 0
                        while (y < size.height) {
                            var x = if (row % 2 == 0) 0f else cell
                            while (x < size.width) {
                                drawRect(Color(0xFFD0D0D0), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(cell, cell))
                                x += cell * 2
                            }
                            y += cell
                            row++
                        }
                    }
                    Canvas(
                        Modifier.fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .pointerInput(moving) {
                                if (moving) return@pointerInput
                                fun toMask(pos: Offset): Offset {
                                    val fx = (pos.x / size.width).coerceIn(0f, 1f)
                                    val fy = (pos.y / size.height).coerceIn(0f, 1f)
                                    return Offset((if (overlay.flipH) 1f - fx else fx) * maskW, fy * maskH)
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val p = paint()
                                    pushUndo()
                                    var last = toMask(down.position)
                                    drawSegment(p, last, last)
                                    cursor = down.position
                                    version++
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val next = toMask(change.position)
                                        drawSegment(p, last, next)
                                        last = next
                                        cursor = change.position
                                        version++
                                        change.consume()
                                    }
                                    cursor = null
                                }
                            },
                    ) {
                        // Reading the counter redraws the canvas after every brush stroke.
                        if (version < 0) return@Canvas
                        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        withTransform({ if (overlay.flipH) scale(-1f, 1f) }) {
                            frame?.let {
                                drawImage(it.asImageBitmap(), IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, dst)
                            } ?: drawRect(Color(0xFF444450))
                            drawImage(maskImage, IntOffset.Zero, IntSize(maskW, maskH), IntOffset.Zero, dst, blendMode = BlendMode.DstIn)
                        }
                    }
                    cursor?.let { c ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color.White, radius = brush * size.width / 2f, center = c, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(Color(0xFF141418)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    ModeChip("Tẩy", !moving && erasing) { moving = false; erasing = true }
                    ModeChip("Khôi phục", !moving && !erasing) { moving = false; erasing = false }
                    ModeChip("Di chuyển / phóng to", moving) { moving = true }
                }
                EraserSlider("Cỡ cọ", brush, 0.01f..0.25f, "${(brush * 100).roundToInt()}") { brush = it }
                EraserSlider("Độ mềm viền", softness, 0f..1f, "${(softness * 100).roundToInt()}") { softness = it }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = strokeUndo.isNotEmpty() || version < 0,
                        onClick = {
                            strokeUndo.removeLastOrNull()?.let { prev ->
                                android.graphics.Canvas(mask).drawBitmap(prev, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
                                version++
                            }
                        },
                    ) { Text("Hoàn tác nét") }
                    OutlinedButton(onClick = {
                        pushUndo()
                        mask.eraseColor(android.graphics.Color.WHITE)
                        version++
                    }) { Text("Hiện lại toàn bộ") }
                    OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Vừa khung") }
                }
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            modifier = Modifier.padding(end = 8.dp),
        ) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text(text, color = Color.White) }
    }
}

@Composable
private fun EraserSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
        Text(shown, color = TextDim, fontSize = 12.sp, modifier = Modifier.width(32.dp))
    }
}

/** Loads the saved eraser drawing (scaled to the working size) or starts fully visible. */
private fun loadMask(path: String?, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(android.graphics.Color.WHITE)
    val saved = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } ?: return out
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        saved, null, android.graphics.Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) },
    )
    saved.recycle()
    return out
}

private fun isFullyOpaque(b: Bitmap): Boolean {
    val pixels = IntArray(b.width * b.height)
    b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
    return pixels.all { (it ushr 24) == 0xFF }
}
