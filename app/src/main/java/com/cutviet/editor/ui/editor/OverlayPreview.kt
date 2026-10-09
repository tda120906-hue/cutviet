package com.cutviet.editor.ui.editor

import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.cutviet.editor.media.OverlayFrames
import com.cutviet.editor.media.OverlayVideoPlayer
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.ProjectOps
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** While playing, overlays starting this soon get their first picture ready. */
private const val PRELOAD_MS = 1500L
/** Phones can only decode a few videos at once next to the main video. */
private const val MAX_LIVE_VIDEOS = 3
/** While playing without a video player, the picture steps this often. */
private const val STEP_MS = 500L

/**
 * Draws the overlays over the preview video, placed exactly as the export places them, with
 * background removal, mask and eraser applied. When paused each overlay shows its exact frame at the
 * playhead (and follows edits instantly); while playing, video overlays play in step with the project.
 */
@Composable
fun OverlayPreview(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    if (project.clips.isEmpty() || project.overlays.isEmpty()) return
    val (canvasW, canvasH) = ProjectOps.canvasSize(project)
    val playing = vm.preview.isPlaying.value
    val t = vm.playheadMs
    val total = project.durationMs

    fun inside(o: Overlay) = t >= o.startMs && t < o.endMs
    // In drawing order: the last overlay is the top layer.
    val shown = project.overlays.filter { o ->
        o.durationMs > 0 && o.startMs < total &&
            (inside(o) || (playing && o.startMs > t && o.startMs - t <= PRELOAD_MS))
    }
    if (shown.isEmpty()) return
    val liveIds = shown.filter { !it.isImage && inside(it) }.takeLast(MAX_LIVE_VIDEOS).mapTo(HashSet()) { it.id }

    BoxWithConstraints(modifier) {
        val frame = PreviewFrame(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), canvasW, canvasH)
        val density = LocalDensity.current
        Layout(
            content = {
                shown.forEach { o ->
                    key(o.id) {
                        OverlayLayer(vm, o, t, playing, visible = inside(o), live = o.id in liveIds)
                    }
                }
            },
            // Overlays are cut off at the edges of the video frame, as in the export.
            modifier = Modifier
                .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                .clipToBounds(),
        ) { measurables, constraints ->
            val placed = measurables.mapIndexed { i, m ->
                val p = frame.placement(shown[i])
                m.measure(Constraints.fixed(max(1, p.width.roundToInt()), max(1, p.height.roundToInt()))) to p
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.forEach { (placeable, p) ->
                    placeable.place(
                        (p.center.x - frame.left - placeable.width / 2f).roundToInt(),
                        (p.center.y - frame.top - placeable.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }
}

/** One overlay, filling the box it is measured into. */
@Composable
private fun OverlayLayer(vm: EditorViewModel, o: Overlay, t: Long, playing: Boolean, visible: Boolean, live: Boolean) {
    val context = LocalContext.current
    var liveFailed by remember(o.id) { mutableStateOf(false) }
    var liveOnScreen by remember(o.id) { mutableStateOf(false) }
    val canPlay = !o.isImage && live && !liveFailed

    // Which source time the still picture shows. While a video player shows the motion, the picture
    // stays as it was (it only covers the player's start-up).
    val held = remember(o.id) { longArrayOf(-1L) }
    val sourceMs = ProjectOps.overlaySourceTimeMs(o, t)
    val wantedMs = when {
        o.isImage -> 0L
        !playing -> sourceMs
        canPlay && held[0] >= 0 -> held[0]
        canPlay -> sourceMs
        else -> o.trimStartMs + (sourceMs - o.trimStartMs) / STEP_MS * STEP_MS
    }
    SideEffect { held[0] = wantedMs }

    var still by remember(o.id) { mutableStateOf<OverlayFrames.Frame?>(null) }
    LaunchedEffect(OverlayFrames.key(o, wantedMs, exact = true)) {
        OverlayFrames.cached(o, wantedMs, exact = true)?.let {
            still = it
            return@LaunchedEffect
        }
        if (!o.isImage) {
            // While the playhead is being dragged: a nearby key frame first, then the exact frame
            // once it rests.
            delay(30)
            OverlayFrames.get(context, o, wantedMs, exact = false)?.let { still = it }
            delay(120)
        }
        OverlayFrames.get(context, o, wantedMs, exact = true)?.let { still = it }
    }

    // After pausing, the video keeps showing until the exact picture for that spot is ready.
    val stillUpToDate = still?.let { it.exact && it.timeMs == wantedMs } == true
    val useVideo = canPlay && visible && (playing || (liveOnScreen && !stillUpToDate))

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = if (visible) o.opacity.coerceIn(0f, 1f) else 0f
            rotationZ = o.rotation
            scaleX = if (o.flipH) -1f else 1f
        },
    ) {
        val s = still
        if (s != null && !(useVideo && liveOnScreen)) {
            val image = remember(s) { s.bitmap.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        if (useVideo) {
            LiveVideo(
                vm, o, playing,
                onShown = { liveOnScreen = it },
                onFailed = { liveFailed = true },
            )
        }
    }
}

/** A video overlay playing in step with the project. */
@Composable
private fun LiveVideo(
    vm: EditorViewModel,
    o: Overlay,
    playing: Boolean,
    onShown: (Boolean) -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    // Only what changes the played picture or its timing needs a new player.
    val player = remember(o.uri, o.trimStartMs, o.trimEndMs, o.speed, o.startMs, o.chroma, o.mask, o.flipH, o.eraseMask) {
        OverlayVideoPlayer(context, o)
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
            onShown(false)
        }
    }
    val failed = player.failed.value
    val shown = player.showingFrame.value && !failed
    LaunchedEffect(shown) { onShown(shown) }
    LaunchedEffect(failed) { if (failed) onFailed() }
    LaunchedEffect(player, playing) {
        while (true) {
            player.sync(vm.preview.positionMs(), playing)
            if (!playing) break
            delay(100)
        }
    }
    if (!failed) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).apply { isOpaque = false } },
            update = { player.attach(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
