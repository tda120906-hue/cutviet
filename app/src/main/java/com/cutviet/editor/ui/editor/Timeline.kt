package com.cutviet.editor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.ui.formatShort
import com.cutviet.editor.ui.formatSpeed
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.AccentPink
import com.cutviet.editor.ui.theme.Surface2
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.roundToInt

private val ClipHeight = 56.dp
private val ThumbWidth = 40.dp

/**
 * CapCut-style timeline: the playhead stays in the middle and the tracks scroll under it.
 * Scrolling seeks the preview; playback scrolls the tracks.
 */
@Composable
fun Timeline(
    vm: EditorViewModel,
    onAddMusic: () -> Unit,
    onAddMedia: () -> Unit,
    onAddOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(with(density) { 48.dp.toPx() }) }
    val pxPerMs = pxPerSecond / 1000f
    val scroll = rememberScrollState()
    val project = vm.project
    val playing = vm.preview.isPlaying.value
    // Dragged edges snap when they come this close to another edge.
    val snapMs = (with(density) { 12.dp.toPx() } / pxPerMs).toLong()

    // Keep the playhead on the same time when zooming, then seek while the user scrolls.
    LaunchedEffect(pxPerMs) {
        scroll.scrollTo((vm.playheadMs * pxPerMs).roundToInt())
        snapshotFlow { scroll.value }.collect { v ->
            // While a clip edge is dragged the preview follows the edge, not the playhead.
            if (!vm.preview.isPlaying.value && vm.trim == null) {
                val t = (v / pxPerMs).toLong().coerceIn(0, vm.project.durationMs)
                if (kotlin.math.abs(t - vm.playheadMs) > 1) {
                    vm.playheadMs = t
                    vm.preview.seekTo(t)
                }
            }
        }
    }
    // Moving the timeline by hand shows each frame as the finger goes (Media3 scrubbing mode).
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.isScrollInProgress && !vm.preview.isPlaying.value && vm.trim == null }
            .collect { vm.setTimelineScrubbing(it) }
    }
    DisposableEffect(Unit) { onDispose { vm.setTimelineScrubbing(false) } }
    // The editor moved the playhead itself (after a trim, for example).
    val jumpsBefore = remember { vm.playheadJumps }
    LaunchedEffect(vm.playheadJumps) {
        if (vm.playheadJumps != jumpsBefore) scroll.scrollTo((vm.playheadJumpMs * pxPerMs).roundToInt())
    }
    // Follow playback.
    LaunchedEffect(playing, pxPerMs) {
        if (playing) {
            while (isActive) {
                withFrameMillis { }
                val t = vm.preview.positionMs()
                vm.playheadMs = t
                scroll.scrollTo((t * pxPerMs).roundToInt())
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().heightIn(max = 270.dp).background(Color(0xFF0E0E11))) {
        val half = maxWidth / 2
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        vm.preview.pause()
                    }
                }
                .horizontalScroll(scroll)
                .padding(vertical = 8.dp),
        ) {
            Ruler(project.durationMs, pxPerMs, half)
            Spacer(Modifier.height(4.dp))
            val totalWidth = with(density) { (project.durationMs * pxPerMs).toDp() }

            Row(Modifier.padding(bottom = 2.dp)) {
                Spacer(Modifier.width(half))
                SmallChip("Thêm lớp phủ", Icons.Filled.Layers, onAddOverlay)
            }
            // One track per overlay, stacked like the picture: the top track is the top layer.
            project.overlays.asReversed().forEach { ov ->
                key(ov.id) { OverlayTrack(vm, ov, half, totalWidth, pxPerMs, snapMs) }
            }

            // Main track.
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(half))
                project.clips.forEachIndexed { index, clip ->
                    key(clip.id) {
                        // A left edge being dragged in leaves a gap, so the rest of the clip stays put
                        // and the edge moves with the finger toward the playhead.
                        val gap = vm.trim?.takeIf { it.clipId == clip.id && it.atStart }?.gapMs ?: 0L
                        if (gap > 0) Spacer(Modifier.width(with(density) { (gap * pxPerMs).toDp() }))
                        ClipBlock(
                            vm = vm, index = index, clip = clip, pxPerMs = pxPerMs, snapMs = snapMs,
                            width = with(density) { (clip.durationMs * pxPerMs).toDp() },
                            selected = vm.selected == index,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(onClick = onAddMedia),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "Thêm", tint = Color.Black) }
                Spacer(Modifier.width(half))
            }
            Spacer(Modifier.height(6.dp))
            SubtitleLane(vm, half, totalWidth, pxPerMs)

            // Audio lanes (music, extracted sound, voice-overs).
            val total = project.durationMs
            val audioLanes = ProjectOps.lanes(project.audios, { it.startMs }, { it.startMs + minOf(it.timelineLengthMs(total), (total - it.startMs).coerceAtLeast(0)) })
            audioLanes.forEach { lane ->
                LaneRow(half, totalWidth) {
                    lane.forEach { a ->
                        val shown = minOf(a.timelineLengthMs(total), (total - a.startMs).coerceAtLeast(0)).coerceAtLeast(300)
                        LayerBar(
                            vm = vm, startMs = a.startMs, durationMs = shown, pxPerMs = pxPerMs,
                            color = when (a.kind) {
                                AudioKind.MUSIC -> Color(0xFF2B5D4F)
                                AudioKind.EXTRACTED -> Color(0xFF2F4F7A)
                                AudioKind.VOICE -> Color(0xFF8A4A1F)
                                AudioKind.DUB -> Color(0xFF6A2F7A)
                                AudioKind.BACKGROUND -> Color(0xFF3F5A3A)
                                AudioKind.SOURCE_VOICE -> Color(0xFF5A4A3A)
                            },
                            label = (if (a.speed != 1f) formatSpeed(a.speed) + " · " else "") + a.name,
                            icon = when (a.kind) {
                                AudioKind.MUSIC -> Icons.Filled.MusicNote
                                AudioKind.EXTRACTED -> Icons.Filled.GraphicEq
                                AudioKind.VOICE -> Icons.Filled.RecordVoiceOver
                                AudioKind.DUB -> Icons.Filled.Translate
                                AudioKind.BACKGROUND -> Icons.Filled.Landscape
                                AudioKind.SOURCE_VOICE -> Icons.Filled.RecordVoiceOver
                            },
                            selected = vm.selectedAudioId == a.id,
                            onClick = { vm.selectAudio(a.id) },
                            onMove = { deltaMs ->
                                ProjectOps.updateAudio(vm.project, a.id) { it.copy(startMs = (a.startMs + deltaMs).coerceAtLeast(0)) }
                            },
                            onStretchStart = if (a.loop) null else ({ deltaMs -> ProjectOps.updateAudio(vm.project, a.id) { ProjectOps.stretchAudioStart(a, deltaMs) } }),
                            onStretchEnd = if (a.loop) null else ({ deltaMs -> ProjectOps.updateAudio(vm.project, a.id) { ProjectOps.stretchAudioEnd(a, deltaMs) } }),
                        )
                    }
                }
            }
            vm.voicePreviewRange?.let { r ->
                LaneRow(half, totalWidth) {
                    Box(
                        Modifier
                            .offset(x = with(density) { (r.first * pxPerMs).toDp() })
                            .width(with(density) { ((r.last - r.first).coerceAtLeast(100) * pxPerMs).toDp() })
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x66FF9F43))
                            .border(1.dp, Color(0xFFFF9F43), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("Giọng đọc sẽ nằm ở đây", color = Color.White, fontSize = 10.sp, maxLines = 1) }
                }
            }
            Row(Modifier.padding(top = 2.dp)) {
                Spacer(Modifier.width(half))
                SmallChip("Thêm âm thanh", Icons.Filled.MusicNote, onAddMusic)
                Spacer(Modifier.width(half))
            }
        }

        // Playhead.
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp).width(2.dp).fillMaxHeight()
                .background(Color.White),
        )
        Box(Modifier.align(Alignment.TopCenter).size(8.dp).clip(CircleShape).background(Color.White))

        Column(Modifier.align(Alignment.CenterEnd).background(Color(0xCC0E0E11)), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Bám", color = if (vm.snapEnabled) Accent else Color(0xFF77777F), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { vm.snapEnabled = !vm.snapEnabled }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            IconButton(onClick = { pxPerSecond = (pxPerSecond * 1.5f).coerceAtMost(with(density) { 600.dp.toPx() }) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ZoomIn, "Phóng to", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { pxPerSecond = (pxPerSecond / 1.5f).coerceAtLeast(with(density) { 2.dp.toPx() }) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ZoomOut, "Thu nhỏ", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SmallChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).background(Surface2)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(15.dp))
        Text(" $text", color = Color.White, fontSize = 12.sp)
    }
}

private val OverlayTrackHeight = 44.dp

/**
 * One overlay on its own track, drawn as a film strip like the main track. Tap selects; long-press
 * and drag moves it in time, and up or down to another layer; the edge handles of the selected
 * layer lengthen or shorten it.
 */
@Composable
private fun OverlayTrack(vm: EditorViewModel, ov: Overlay, half: Dp, totalWidth: Dp, pxPerMs: Float, snapMs: Long) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(ov)
    var dragY by remember { mutableFloatStateOf(0f) }
    val selected = vm.selectedOverlayId == ov.id
    val width = with(density) { (ov.durationMs * pxPerMs).toDp() }.coerceAtLeast(24.dp)
    Row(Modifier.padding(vertical = 2.dp)) {
        Spacer(Modifier.width(half))
        Box(Modifier.width(totalWidth).height(OverlayTrackHeight).background(Color(0xFF15151A))) {
            Box(
                Modifier
                    .offset(x = with(density) { (ov.startMs * pxPerMs).toDp() })
                    .width(width)
                    .fillMaxHeight()
                    .graphicsLayer { translationY = dragY }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A2340))
                    .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { vm.selectOverlay(ov.id) }
                    .pointerInput(ov.id, pxPerMs) {
                        var totalX = 0f
                        var origin: Overlay? = null
                        val rowPx = (OverlayTrackHeight + 4.dp).toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                totalX = 0f
                                dragY = 0f
                                origin = current
                                vm.selectOverlay(current.id, toggle = false)
                                vm.beginDrag()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                totalX += drag.x
                                dragY += drag.y
                                val o = origin ?: return@detectDragGesturesAfterLongPress
                                var start = (o.startMs + (totalX / pxPerMs).toLong()).coerceAtLeast(0)
                                if (vm.snapEnabled) {
                                    val points = ProjectOps.snapPoints(vm.project, o.id, vm.playheadMs)
                                    val snapped = ProjectOps.snap(start, points, snapMs)
                                    start = if (snapped != start) snapped else ProjectOps.snap(start + o.durationMs, points, snapMs) - o.durationMs
                                }
                                vm.dragUpdate(ProjectOps.updateOverlay(vm.project, o.id) { it.copy(startMs = start.coerceAtLeast(0)) })
                            },
                            onDragEnd = {
                                // Dragging up puts the layer above the ones it passed.
                                val rows = -(dragY / rowPx).roundToInt()
                                dragY = 0f
                                origin?.let { if (rows != 0) vm.dragUpdate(ProjectOps.restackOverlay(vm.project, it.id, rows)) }
                                origin = null
                            },
                            onDragCancel = {
                                dragY = 0f
                                origin = null
                            },
                        )
                    },
            ) {
                FilmStrip(
                    uri = ov.uri, isImage = ov.isImage, durationMs = ov.durationMs, width = width,
                    sourceTimeAt = { offset -> ov.trimStartMs + (offset * ov.speed).toLong() },
                )
                Row(
                    Modifier.align(Alignment.TopStart).padding(start = if (selected) 16.dp else 4.dp, top = 3.dp)
                        .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Layers, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Text(
                        " " + ov.name.ifEmpty { if (ov.isImage) "Ảnh phủ" else "Video phủ" } + "  " + formatShort(ov.durationMs),
                        color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Handle(Modifier.align(Alignment.CenterStart), pxPerMs, vm) { deltaMs ->
                        var newStart = ov.startMs + deltaMs
                        if (vm.snapEnabled) newStart = ProjectOps.snap(newStart, ProjectOps.snapPoints(vm.project, ov.id, vm.playheadMs), snapMs)
                        ProjectOps.updateOverlay(vm.project, ov.id) { ProjectOps.stretchOverlayStart(ov, newStart - ov.startMs) }
                    }
                    Handle(Modifier.align(Alignment.CenterEnd), pxPerMs, vm) { deltaMs ->
                        var newEnd = ov.endMs + deltaMs
                        if (vm.snapEnabled) newEnd = ProjectOps.snap(newEnd, ProjectOps.snapPoints(vm.project, ov.id, vm.playheadMs), snapMs)
                        ProjectOps.updateOverlay(vm.project, ov.id) { ProjectOps.stretchOverlayEnd(ov, newEnd - ov.endMs) }
                    }
                }
            }
        }
        Spacer(Modifier.width(half))
    }
}

/** Thumbnails across a layer, taken from the part of the source it plays. */
@Composable
private fun FilmStrip(uri: String, isImage: Boolean, durationMs: Long, width: Dp, sourceTimeAt: (Long) -> Long) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbCount = ceil(width / ThumbWidth).toInt().coerceIn(1, 24)
    val thumbPx = with(density) { 96.dp.roundToPx() }
    Row(Modifier.fillMaxSize()) {
        for (i in 0 until thumbCount) {
            val time = if (isImage) 0L else sourceTimeAt((durationMs * (i + 0.5f) / thumbCount).toLong())
            val bmp by produceState<Bitmap?>(null, uri, time / 250) {
                value = Thumbnails.get(context, uri, isImage, time, thumbPx)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LaneRow(half: Dp, totalWidth: Dp, content: @Composable BoxScope.() -> Unit) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Spacer(Modifier.width(half))
        Box(Modifier.width(totalWidth).height(32.dp), content = content)
        Spacer(Modifier.width(half))
    }
}

/**
 * A layer on an audio lane. Tap selects it; long-press and drag moves it in time;
 * when selected, the white handles on each end stretch it (trim) like in CapCut.
 */
@Composable
private fun BoxScope.LayerBar(
    vm: EditorViewModel,
    startMs: Long,
    durationMs: Long,
    pxPerMs: Float,
    color: Color,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onMove: (Long) -> Project,
    onStretchStart: ((Long) -> Project)?,
    onStretchEnd: ((Long) -> Project)?,
) {
    val density = LocalDensity.current
    val move by rememberUpdatedState(onMove)
    Box(
        Modifier
            .offset(x = with(density) { (startMs * pxPerMs).toDp() })
            .width(with(density) { (durationMs * pxPerMs).toDp() }.coerceAtLeast(24.dp))
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .pointerInput(pxPerMs) {
                var total = 0f
                // Capture the layer as it was when the drag began; deltas are applied to that.
                var fn: ((Long) -> Project)? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { total = 0f; fn = move; vm.beginDrag() },
                    onDrag = { change, drag ->
                        change.consume()
                        total += drag.x
                        fn?.let { vm.dragUpdate(it((total / pxPerMs).toLong())) }
                    },
                )
            },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (selected) 14.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(" $label", color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            onStretchStart?.let { Handle(Modifier.align(Alignment.CenterStart), pxPerMs, vm, it) }
            onStretchEnd?.let { Handle(Modifier.align(Alignment.CenterEnd), pxPerMs, vm, it) }
        }
    }
}

@Composable
private fun Handle(modifier: Modifier, pxPerMs: Float, vm: EditorViewModel, onDrag: (Long) -> Project) {
    val latest by rememberUpdatedState(onDrag)
    Box(
        modifier
            .width(14.dp)
            .fillMaxHeight()
            .background(Color.White)
            .pointerInput(pxPerMs) {
                var total = 0f
                var fn: ((Long) -> Project)? = null
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; fn = latest; vm.beginDrag() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        fn?.let { vm.dragUpdate(it((total / pxPerMs).toLong())) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(12.dp).background(Color(0xFF333333)))
    }
}

/**
 * An edge handle of a main-track clip. While it moves, the preview shows the frame at the edge;
 * a left edge stops at the playhead when snapping is on, with a tick felt in the hand.
 */
@Composable
private fun ClipHandle(modifier: Modifier, pxPerMs: Float, snapMs: Long, vm: EditorViewModel, index: Int, atStart: Boolean) {
    val haptic = LocalHapticFeedback.current
    val snap by rememberUpdatedState(snapMs)
    val stopped = vm.trim?.takeIf { it.atStart == atStart }?.stopped == true
    LaunchedEffect(stopped) { if (stopped) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    DisposableEffect(Unit) { onDispose { if (vm.trim?.atStart == atStart) vm.endTrim() } }
    Box(
        modifier
            .width(14.dp)
            .fillMaxHeight()
            .background(if (stopped) Accent else Color.White)
            .pointerInput(pxPerMs, index, atStart) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; vm.beginTrim(index, atStart) },
                    onDragEnd = { vm.endTrim() },
                    onDragCancel = { vm.endTrim() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        vm.trimTo((total / pxPerMs).toLong(), snap)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(12.dp).background(Color(0xFF333333)))
    }
}

@Composable
private fun Ruler(durationMs: Long, pxPerMs: Float, half: Dp) {
    val density = LocalDensity.current
    // Pick a label step that keeps labels at least ~60dp apart.
    val minGapMs = with(density) { 60.dp.toPx() } / pxPerMs
    val steps = longArrayOf(500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000)
    val step = steps.firstOrNull { it >= minGapMs } ?: 600_000
    val count = (durationMs / step).toInt() + 1
    val stepDp = with(density) { (step * pxPerMs).toDp() }
    Row {
        Spacer(Modifier.width(half - 20.dp))
        for (i in 0 until count) {
            Box(Modifier.width(stepDp)) {
                Text(
                    formatShort(i * step), color = Color(0xFF8A8A94), fontSize = 10.sp,
                    modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(half))
    }
}

@Composable
private fun ClipBlock(vm: EditorViewModel, index: Int, clip: Clip, pxPerMs: Float, snapMs: Long, width: Dp, selected: Boolean) {
    Box(
        Modifier
            .width(width)
            .height(ClipHeight)
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .then(if (selected) Modifier.border(BorderStroke(2.5.dp, Color.White), RoundedCornerShape(6.dp)) else Modifier)
            .clickable { vm.select(index) },
    ) {
        FilmStrip(uri = clip.uri, isImage = clip.isImage, durationMs = clip.durationMs, width = width, sourceTimeAt = clip::sourceTimeAt)
        Row(
            Modifier.align(Alignment.TopStart).padding(start = if (selected) 16.dp else 3.dp, top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!clip.isImage && clip.speed != 1f) Badge(formatSpeed(clip.speed), AccentPink)
            if (!clip.isImage && clip.volume == 0f) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff, null, tint = Color.White,
                    modifier = Modifier.size(14.dp).background(Color(0x99000000), CircleShape).padding(2.dp),
                )
            }
            if (clip.texts.isNotEmpty()) Badge("T${clip.texts.size}", Color(0xFF6C4CF5))
        }
        if (selected) {
            Text(
                formatShort(clip.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 3.dp)
                    .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
            // Edge handles: trim a video, or make a picture shorter/longer.
            ClipHandle(Modifier.align(Alignment.CenterStart), pxPerMs, snapMs, vm, index, atStart = true)
            ClipHandle(Modifier.align(Alignment.CenterEnd), pxPerMs, snapMs, vm, index, atStart = false)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 3.dp).background(color, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
    )
}
