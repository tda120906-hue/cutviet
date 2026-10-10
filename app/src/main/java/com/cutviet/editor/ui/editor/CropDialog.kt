package com.cutviet.editor.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.CropHandle
import com.cutviet.editor.model.CropOps
import com.cutviet.editor.model.CropSpec
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val PillColor = Color(0xFF1F2230)
private val DialTick = Color(0xFFB8BCCB)

/**
 * Full-screen crop editor ("Cắt khung"): drag the corners or edges of the window to resize it,
 * drag inside to move it, straighten with the dial, mirror, turn a quarter, or pick a ratio.
 * Nothing changes in the project until "Xong".
 */
@Composable
fun CropDialog(vm: EditorViewModel, clip: Clip) {
    val context = LocalContext.current
    var draft by remember(clip.id) { mutableStateOf(clip) }
    var tab by remember { mutableIntStateOf(0) }
    val changed = draft.rotation != clip.rotation || draft.flipH != clip.flipH || draft.flipV != clip.flipV || draft.crop != clip.crop
    val atStart = draft.rotation == 0 && !draft.flipH && !draft.flipV && draft.crop == CropSpec()

    // Where to start: the frame under the playhead if it is on this clip.
    val trimmed = clip.trimmedMs.coerceAtLeast(1)
    val startPos = remember(clip.id) {
        val i = vm.project.clips.indexOfFirst { it.id == clip.id }
        val offset = if (i >= 0) vm.playheadMs - vm.project.clipStartMs(i) else -1
        if (!clip.isImage && offset in 0 until clip.durationMs) (clip.sourceTimeAt(offset) - clip.trimStartMs).coerceIn(0, trimmed - 1) else 0L
    }
    val player = remember(clip.id) {
        if (clip.isImage) null
        else ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.Builder().setUri(clip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build(),
                    ).build(),
            )
            playbackParameters = PlaybackParameters(clip.speed)
            volume = clip.volume.coerceIn(0f, 1f)
            prepare()
            seekTo(startPos)
        }
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(startPos) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            withFrameMillis { }
            position = player.currentPosition
        }
        if (player != null) position = player.currentPosition
    }
    val image by produceState<Bitmap?>(null, clip.uri) {
        if (clip.isImage) {
            value = withContext(Dispatchers.IO) { runCatching { Thumbnails.loadImage(context, Uri.parse(clip.uri), 1440) }.getOrNull() }
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED || position >= trimmed - 60) p.seekTo(0)
            p.play()
        }
    }

    Dialog(
        onDismissRequest = { vm.closeCrop() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cắt khung", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(enabled = !atStart, onClick = { draft = CropOps.resetOrientation(draft).copy(crop = CropSpec()) }) {
                    Text("Đặt lại", color = if (atStart) TextDim else Accent)
                }
            }

            CropStage(
                clip = draft,
                onCropChange = { draft = draft.copy(crop = it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                picture = {
                    if (player != null) {
                        AndroidView(
                            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        image?.let { bmp ->
                            val img = remember(bmp) { bmp.asImageBitmap() }
                            Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        }
                    }
                },
                controls = {
                    if (player != null) {
                        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                            IconButton(onClick = { togglePlay() }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (playing) "Tạm dừng" else "Phát",
                                    tint = Color.White, modifier = Modifier.size(30.dp),
                                )
                            }
                            Text(
                                "${clock((position / clip.speed).toLong())} / ${clock(clip.durationMs)}",
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 4f)),
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                },
            )

            if (player != null) {
                var resume by remember { mutableStateOf(false) }
                ScrubBar(
                    fraction = position.toFloat() / trimmed,
                    onStart = {
                        resume = player.isPlaying
                        player.pause()
                    },
                    onSeek = { f ->
                        val t = (f * trimmed).toLong().coerceIn(0, trimmed - 1)
                        position = t
                        player.seekTo(t)
                    },
                    onEnd = { if (resume) player.play() },
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }

            Box(Modifier.fillMaxWidth().height(112.dp)) {
                if (tab == 0) {
                    RotateControls(
                        angle = draft.crop.straighten,
                        onMirror = { draft = CropOps.mirror(draft, horizontal = true) },
                        onAngle = { draft = draft.copy(crop = draft.crop.copy(straighten = it)) },
                        onTurn = { draft = CropOps.turn(draft, clockwise = false) },
                    )
                } else {
                    AspectControls(
                        clip = draft,
                        onPick = { ratio ->
                            val frameAspect = draft.rotatedWidth.toFloat() / draft.rotatedHeight.coerceAtLeast(1)
                            draft = draft.copy(crop = CropOps.withAspect(draft.crop, ratio, frameAspect))
                        },
                    )
                }
            }

            // Bottom bar: cancel, the two tools, confirm.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.Close, "Hủy", Modifier.weight(1f)) { vm.closeCrop() }
                Row(
                    Modifier.weight(2f).height(64.dp).clip(RoundedCornerShape(32.dp)).background(PillColor).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TabButton(Icons.Filled.CropRotate, "Cắt và xoay", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    TabButton(Icons.Filled.AspectRatio, "Tỉ lệ khung", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }
                RoundButton(Icons.Filled.Check, "Xong", Modifier.weight(1f), dim = !changed) {
                    if (changed) vm.saveCrop(draft) else vm.closeCrop()
                }
            }
        }
    }
}

/** "mm:ss" like the reference player. */
private fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/**
 * The turned picture fitted in the available space with the crop window over it. [picture] fills
 * the unturned source frame; [controls] are laid over the bottom of the picture.
 */
@Composable
private fun CropStage(
    clip: Clip,
    onCropChange: (CropSpec) -> Unit,
    modifier: Modifier,
    picture: @Composable () -> Unit,
    controls: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.padding(horizontal = 28.dp, vertical = 20.dp)) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val frameAspect = clip.rotatedWidth.toFloat().coerceAtLeast(1f) / clip.rotatedHeight.toFloat().coerceAtLeast(1f)
        val fw = min(areaW, areaH * frameAspect)
        val fh = fw / frameAspect
        val fl = (areaW - fw) / 2
        val ft = (areaH - fh) / 2
        val frameOffset = IntOffset(fl.roundToInt(), ft.roundToInt())
        val frameW = with(density) { fw.toDp() }
        val frameH = with(density) { fh.toDp() }

        // The picture, turned, mirrored and straightened like the export.
        Box(Modifier.offset { frameOffset }.size(frameW, frameH).clipToBounds(), contentAlignment = Alignment.Center) {
            val sideways = clip.rotation % 180 != 0
            val k = CropOps.straightenScale(clip.crop.straighten, frameAspect)
            Box(
                Modifier
                    .requiredSize(if (sideways) frameH else frameW, if (sideways) frameW else frameH)
                    .graphicsLayer {
                        rotationZ = clip.rotation + clip.crop.straighten
                        scaleX = k * (if (clip.flipH) -1f else 1f)
                        scaleY = k * (if (clip.flipV) -1f else 1f)
                    },
            ) { picture() }
        }

        var dragging by remember { mutableStateOf(false) }
        val crop by rememberUpdatedState(clip.crop)
        val onCrop by rememberUpdatedState(onCropChange)
        Canvas(
            Modifier.fillMaxSize().pointerInput(fl, ft, fw, fh, frameAspect) {
                val cornerReach = 36.dp.toPx()
                val edgeReach = 26.dp.toPx()
                val minPx = 56.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = crop
                    val handle = hitHandle(windowRect(start, fl, ft, fw, fh), down.position, cornerReach, edgeReach)
                        ?: return@awaitEachGesture
                    down.consume()
                    var moved = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val d = change.position - down.position
                            if (!moved && d.getDistance() < viewConfiguration.touchSlop) continue
                            if (!moved) {
                                moved = true
                                dragging = true
                            }
                            change.consume()
                            onCrop(CropOps.drag(start, handle, d.x / fw, d.y / fh, frameAspect, minPx / fw, minPx / fh))
                        }
                    } finally {
                        dragging = false
                    }
                }
            },
        ) {
            drawWindow(windowRect(clip.crop, fl, ft, fw, fh), Rect(fl, ft, fl + fw, ft + fh), dragging)
        }

        Box(Modifier.offset { frameOffset }.size(frameW, frameH)) { controls() }
    }
}

private fun windowRect(c: CropSpec, fl: Float, ft: Float, fw: Float, fh: Float) =
    Rect(fl + c.left * fw, ft + c.top * fh, fl + c.right * fw, ft + c.bottom * fh)

/** Which part of the window a finger at [p] grabs, or null if none. */
private fun hitHandle(r: Rect, p: Offset, cornerReach: Float, edgeReach: Float): CropHandle? {
    // Shrink the grab zones on a small window so its middle can still be moved.
    val corner = min(cornerReach, min(r.width, r.height) * 0.4f)
    val edge = min(edgeReach, min(r.width, r.height) * 0.2f)
    val corners = listOf(
        CropHandle.TOP_LEFT to r.topLeft, CropHandle.TOP_RIGHT to r.topRight,
        CropHandle.BOTTOM_LEFT to r.bottomLeft, CropHandle.BOTTOM_RIGHT to r.bottomRight,
    )
    val nearest = corners.minByOrNull { (it.second - p).getDistance() }
    if (nearest != null && (nearest.second - p).getDistance() <= corner) return nearest.first
    val withinX = p.x >= r.left - edge && p.x <= r.right + edge
    val withinY = p.y >= r.top - edge && p.y <= r.bottom + edge
    if (withinY && abs(p.x - r.left) <= edge) return CropHandle.LEFT
    if (withinY && abs(p.x - r.right) <= edge) return CropHandle.RIGHT
    if (withinX && abs(p.y - r.top) <= edge) return CropHandle.TOP
    if (withinX && abs(p.y - r.bottom) <= edge) return CropHandle.BOTTOM
    if (r.contains(p)) return CropHandle.MOVE
    return null
}

/** Darkens the picture outside the window, outlines it and draws the corner brackets. */
private fun DrawScope.drawWindow(r: Rect, frame: Rect, grid: Boolean) {
    val dim = Color.Black.copy(alpha = 0.6f)
    drawRect(dim, Offset(frame.left, frame.top), Size(frame.width, (r.top - frame.top).coerceAtLeast(0f)))
    drawRect(dim, Offset(frame.left, r.bottom), Size(frame.width, (frame.bottom - r.bottom).coerceAtLeast(0f)))
    drawRect(dim, Offset(frame.left, r.top), Size((r.left - frame.left).coerceAtLeast(0f), r.height))
    drawRect(dim, Offset(r.right, r.top), Size((frame.right - r.right).coerceAtLeast(0f), r.height))
    drawRect(Color.White.copy(alpha = 0.8f), r.topLeft, r.size, style = Stroke(1.dp.toPx()))
    if (grid) {
        val line = Color.White.copy(alpha = 0.55f)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            val x = r.left + r.width * i / 3
            val y = r.top + r.height * i / 3
            drawLine(line, Offset(x, r.top), Offset(x, r.bottom), w)
            drawLine(line, Offset(r.left, y), Offset(r.right, y), w)
        }
    }
    // Rounded L-shaped brackets just outside each corner.
    val gap = 7.dp.toPx()
    val radius = 12.dp.toPx()
    val len = min(30.dp.toPx(), min(r.width, r.height) / 2 + gap)
    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val cx = (if (sx < 0) r.left else r.right) + sx * gap
        val cy = (if (sy < 0) r.top else r.bottom) + sy * gap
        val rr = min(radius, len)
        val path = Path().apply {
            moveTo(cx, cy - sy * len)
            lineTo(cx, cy - sy * rr)
            quadraticTo(cx, cy, cx - sx * rr, cy)
            lineTo(cx - sx * len, cy)
        }
        drawPath(path, Color.White, style = stroke)
    }
}

/** Thin track with a white handle; drag or tap to move through the clip. */
@Composable
private fun ScrubBar(fraction: Float, onStart: () -> Unit, onSeek: (Float) -> Unit, onEnd: () -> Unit) {
    val start by rememberUpdatedState(onStart)
    val seek by rememberUpdatedState(onSeek)
    val end by rememberUpdatedState(onEnd)
    Canvas(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 24.dp).pointerInput(Unit) {
            fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                start()
                seek(at(down.position.x))
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        seek(at(change.position.x))
                    }
                } finally {
                    end()
                }
            }
        },
    ) {
        val y = size.height / 2
        val track = 6.dp.toPx()
        drawLine(Color(0xFF4A4C58), Offset(track / 2, y), Offset(size.width - track / 2, y), track, cap = StrokeCap.Round)
        val x = fraction.coerceIn(0f, 1f) * size.width
        drawLine(Color.White, Offset(x, y - 16.dp.toPx()), Offset(x, y + 16.dp.toPx()), 5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** "Phản chiếu" · straighten dial · "Xoay". */
@Composable
private fun RotateControls(angle: Float, onMirror: () -> Unit, onAngle: (Float) -> Unit, onTurn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Phản chiếu", color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(angleLabel(angle), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("Xoay", color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.width(84.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(PillColor).clickable(onClick = onMirror),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Flip, "Phản chiếu", tint = Color.White) }
            StraightenDial(angle, onAngle, Modifier.weight(1f).fillMaxHeight())
            Box(
                Modifier.width(84.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 32.dp, bottomEnd = 32.dp))
                    .background(PillColor).clickable(onClick = onTurn),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Rotate90DegreesCcw, "Xoay", tint = Color.White) }
        }
    }
}

private fun angleLabel(a: Float): String {
    val r = (a * 10).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) "${r.toInt()}°" else String.format(Locale.US, "%.1f°", r)
}

/** Ruler that scrolls under a fixed centre line; drag sideways to straighten, double-tap for 0°. */
@Composable
private fun StraightenDial(angle: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(angle)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PillColor)
            .pointerInput(Unit) {
                val perDegree = 8.dp.toPx()
                var value = 0f
                detectHorizontalDragGestures(
                    onDragStart = { value = current },
                    onDragEnd = { if (abs(current) < 0.5f) change(0f) },
                ) { c, dx ->
                    c.consume()
                    val before = value
                    value = CropOps.clampStraighten(value - dx / perDegree)
                    // A tick when passing level.
                    if ((before < 0f) != (value < 0f) || value == 0f && before != 0f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    change((value * 10).roundToInt() / 10f)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) },
    ) {
        val perDegree = 8.dp.toPx()
        val mid = size.width / 2
        val cy = size.height / 2
        for (d in -45..45) {
            val x = mid + (d - angle) * perDegree
            if (x < 4.dp.toPx() || x > size.width - 4.dp.toPx()) continue
            val h = when {
                d == 0 -> size.height * 0.46f
                d % 5 == 0 -> size.height * 0.36f
                else -> size.height * 0.22f
            }
            // Fade towards the ends of the ruler.
            val fade = (1f - abs(x - mid) / mid).coerceIn(0.15f, 1f)
            drawLine(DialTick.copy(alpha = fade), Offset(x, cy - h / 2), Offset(x, cy + h / 2), 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(Color.White, Offset(mid, cy - size.height * 0.33f), Offset(mid, cy + size.height * 0.33f), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Ratio choices for the crop window. */
@Composable
private fun AspectControls(clip: Clip, onPick: (Float?) -> Unit) {
    val original = clip.rotatedWidth.toFloat().coerceAtLeast(1f) / clip.rotatedHeight.toFloat().coerceAtLeast(1f)
    val options = listOf<Pair<String, Float?>>(
        "Tự do" to null, "Gốc" to original, "Vuông" to 1f, "9:16" to 9f / 16f, "16:9" to 16f / 9f,
        "4:5" to 4f / 5f, "5:4" to 5f / 4f, "3:4" to 3f / 4f, "4:3" to 4f / 3f,
        "2:3" to 2f / 3f, "3:2" to 3f / 2f, "21:9" to 21f / 9f,
    )
    val current = clip.crop.aspect
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (label, ratio) ->
            val selected = if (ratio == null) current == null else current != null && abs(current - ratio) < 0.01f
            Column(
                Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Accent.copy(alpha = 0.18f) else PillColor)
                    .clickable { onPick(ratio) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (selected) Accent else Color.White
                Canvas(Modifier.size(28.dp)) {
                    val r = ratio ?: 1f
                    val w = if (r >= 1f) size.width else size.height * r
                    val h = if (r >= 1f) size.width / r else size.height
                    val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
                    val stroke = Stroke(2.dp.toPx(), pathEffect = if (ratio == null) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null)
                    drawRoundRect(tint, tl, Size(w, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                }
                Spacer(Modifier.height(6.dp))
                Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier, dim: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(PillColor).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (dim) Color.White.copy(alpha = 0.35f) else Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(26.dp))
            .background(if (selected) Accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) Accent else Color.White, modifier = Modifier.size(28.dp))
    }
}
