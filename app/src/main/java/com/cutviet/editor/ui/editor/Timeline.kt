package com.cutviet.editor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.model.Clip
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
fun Timeline(vm: EditorViewModel, onAddMusic: () -> Unit, onAddMedia: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(with(density) { 48.dp.toPx() }) }
    val pxPerMs = pxPerSecond / 1000f
    val scroll = rememberScrollState()
    val project = vm.project
    val playing = vm.preview.isPlaying.value

    // Keep the playhead on the same time when zooming, then seek while the user scrolls.
    LaunchedEffect(pxPerMs) {
        scroll.scrollTo((vm.playheadMs * pxPerMs).roundToInt())
        snapshotFlow { scroll.value }.collect { v ->
            if (!vm.preview.isPlaying.value) {
                val t = (v / pxPerMs).toLong().coerceIn(0, vm.project.durationMs)
                if (kotlin.math.abs(t - vm.playheadMs) > 1) {
                    vm.playheadMs = t
                    vm.preview.seekTo(t)
                }
            }
        }
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

    BoxWithConstraints(modifier.fillMaxWidth().background(Color(0xFF0E0E11))) {
        val half = maxWidth / 2
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        vm.preview.pause()
                    }
                }
                .horizontalScroll(scroll)
                .padding(vertical = 10.dp),
        ) {
            Ruler(project.durationMs, pxPerMs, half)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(half))
                project.clips.forEachIndexed { index, clip ->
                    ClipBlock(
                        clip = clip,
                        width = with(density) { (clip.durationMs * pxPerMs).toDp() },
                        selected = vm.selected == index,
                        onClick = { vm.select(index) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(onClick = onAddMedia),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "Thêm", tint = Color.Black) }
                Spacer(Modifier.width(half))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Spacer(Modifier.width(half))
                val music = project.music
                if (music == null) {
                    Row(
                        Modifier.height(30.dp).clip(RoundedCornerShape(6.dp)).background(Surface2)
                            .clickable(onClick = onAddMusic).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MusicNote, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Text(" Thêm nhạc", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    val len = if (music.loop) project.durationMs
                    else minOf(music.sourceDurationMs - music.startMs, project.durationMs)
                    val selected = vm.musicSelected
                    Row(
                        Modifier
                            .width(with(density) { (len.coerceAtLeast(500) * pxPerMs).toDp() })
                            .height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2B5D4F))
                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                            .clickable { vm.selectMusic() }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(
                            " ${music.name}", color = Color.White, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(half))
            }
        }

        // Playhead.
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp).width(2.dp).fillMaxHeight()
                .background(Color.White),
        )
        Box(Modifier.align(Alignment.TopCenter).size(8.dp).clip(CircleShape).background(Color.White))

        Column(Modifier.align(Alignment.CenterEnd).background(Color(0xCC0E0E11))) {
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
private fun ClipBlock(clip: Clip, width: Dp, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbCount = ceil(width / ThumbWidth).toInt().coerceIn(1, 24)
    val thumbPx = with(density) { 96.dp.roundToPx() }
    Box(
        Modifier
            .width(width)
            .height(ClipHeight)
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .then(if (selected) Modifier.border(BorderStroke(2.5.dp, Color.White), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until thumbCount) {
                val offset = (clip.durationMs * (i + 0.5f) / thumbCount).toLong()
                val time = clip.sourceTimeAt(offset)
                val bmp by produceState<Bitmap?>(null, clip.uri, time / 250) {
                    value = Thumbnails.get(context, clip.uri, clip.isImage, time, thumbPx)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    bmp?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(3.dp),
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
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                    .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
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
