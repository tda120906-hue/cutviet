package com.cutviet.editor.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.VoiceSpec
import com.cutviet.editor.model.Adjustments
import com.cutviet.editor.model.AspectRatio
import com.cutviet.editor.model.CropSpec
import com.cutviet.editor.model.FilterPreset
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.TextItem
import com.cutviet.editor.ui.formatShort
import com.cutviet.editor.ui.formatSpeed
import com.cutviet.editor.ui.formatTime
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.Surface3
import com.cutviet.editor.ui.theme.TextDim
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PanelHost(vm: EditorViewModel, panel: Panel) {
    Column(Modifier.fillMaxWidth().height(290.dp).background(Color(0xFF141418))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(panel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.closePanel() }) {
                Icon(Icons.Filled.Check, "Xong", tint = Accent)
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            when (panel) {
                Panel.TRIM -> TrimPanel(vm)
                Panel.SPEED -> SpeedPanel(vm)
                Panel.VOLUME -> VolumePanel(vm)
                Panel.TRANSFORM -> TransformPanel(vm)
                Panel.CROP -> CropPanel(vm)
                Panel.FILTER -> FilterPanel(vm)
                Panel.ADJUST -> AdjustPanel(vm)
                Panel.TEXT -> TextPanel(vm, stickers = false)
                Panel.STICKER -> TextPanel(vm, stickers = true)
                Panel.AUDIO -> AudioPanel(vm)
                Panel.OVERLAY -> OverlayPanel(vm)
                Panel.OVERLAY_KEY -> OverlayKeyPanel(vm)
                Panel.VOICE -> VoicePanel(vm)
                Panel.CANVAS -> CanvasPanel(vm)
                Panel.DURATION -> DurationPanel(vm)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------- shared widgets ----------

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Accent, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = sliderColors(),
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Accent,
    inactiveTrackColor = Surface3,
)

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Accent,
            selectedLabelColor = Color.Black,
            containerColor = Surface2,
            labelColor = Color.White,
        ),
        border = null,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun ApplyAllButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("Áp dụng cho tất cả các đoạn", color = Accent) }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}"

// ---------- trim ----------

@Composable
private fun TrimPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    if (clip.isImage) {
        DurationPanel(vm)
        return
    }
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp))) {
        val n = 8
        for (i in 0 until n) {
            val t = clip.sourceDurationMs * (i + 0.5f) / n
            val bmp by produceState<Bitmap?>(null, clip.uri, i) {
                value = Thumbnails.get(context, clip.uri, false, t.toLong(), 160)
            }
            Box(Modifier.weight(1f).height(44.dp).background(Surface2)) {
                bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
    RangeSlider(
        value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(),
        onValueChange = { r ->
            vm.updateSelected { ProjectOps.trim(it, r.start.toLong(), r.endInclusive.toLong()) }
        },
        valueRange = 0f..clip.sourceDurationMs.toFloat(),
        colors = sliderColors(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text("Bắt đầu ${formatTime(clip.trimStartMs)}", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("Kết thúc ${formatTime(clip.trimEndMs)}", color = TextDim, fontSize = 12.sp)
    }
    Text(
        "Độ dài đoạn: ${formatTime(clip.trimmedMs)} / ${formatTime(clip.sourceDurationMs)}",
        color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
    )
    Row(Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = {
            vm.updateSelected { ProjectOps.trim(it, it.trimStartMs - 100, it.trimEndMs) }
        }) { Text("Đầu −0.1s") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = {
            vm.updateSelected { ProjectOps.trim(it, it.trimStartMs + 100, it.trimEndMs) }
        }) { Text("Đầu +0.1s") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = {
            vm.updateSelected { ProjectOps.trim(it, 0, it.sourceDurationMs) }
        }) { Text("Đặt lại") }
    }
}

@Composable
private fun DurationPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    LabeledSlider(
        "Thời lượng hiển thị ảnh", clip.durationMs / 1000f, 0.5f..20f,
        String.format(Locale.US, "%.1f giây", clip.durationMs / 1000f),
    ) { v -> vm.updateSelected { it.copy(trimStartMs = 0, trimEndMs = (v * 1000).toLong()) } }
    ApplyAllButton {
        val d = clip.durationMs
        vm.applyToAll { if (it.isImage) it.copy(trimStartMs = 0, trimEndMs = d) else it }
    }
}

// ---------- speed ----------

@Composable
private fun SpeedPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    LabeledSlider("Tốc độ", clip.speed, 0.25f..4f, formatSpeed(clip.speed)) { v ->
        val snapped = (v * 20).roundToInt() / 20f
        vm.updateSelected { it.copy(speed = snapped) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { s ->
            Chip(formatSpeed(s), clip.speed == s) { vm.updateSelected { it.copy(speed = s) } }
        }
    }
    Text(
        "Độ dài sau khi đổi tốc độ: ${formatTime(clip.durationMs)} (gốc ${formatTime(clip.trimmedMs)})",
        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

// ---------- volume ----------

@Composable
private fun VolumePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    LabeledSlider("Âm lượng đoạn", clip.volume, 0f..2f, "${pct(clip.volume)}%") { v ->
        vm.updateSelected { it.copy(volume = (v * 20).roundToInt() / 20f) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tắt tiếng", color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            checked = clip.volume == 0f,
            onCheckedChange = { m -> vm.updateSelected { it.copy(volume = if (m) 0f else 1f) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
    if (!clip.hasAudio) Text("Đoạn này không có âm thanh.", color = TextDim, fontSize = 12.sp)
    ApplyAllButton { val v = clip.volume; vm.applyToAll { it.copy(volume = v) } }
}

// ---------- rotate / flip / fit ----------

@Composable
private fun TransformPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ToolCircle(Icons.Filled.RotateRight, "Xoay 90°") { vm.updateSelected { it.copy(rotation = (it.rotation + 90) % 360) } }
        ToolCircle(Icons.Filled.SwapHoriz, "Lật ngang") { vm.updateSelected { it.copy(flipH = !it.flipH) } }
        ToolCircle(Icons.Filled.SwapVert, "Lật dọc") { vm.updateSelected { it.copy(flipV = !it.flipV) } }
        ToolCircle(Icons.Filled.Refresh, "Đặt lại") { vm.updateSelected { it.copy(rotation = 0, flipH = false, flipV = false) } }
    }
    Text(
        "Góc xoay: ${clip.rotation}°" + (if (clip.flipH) " · lật ngang" else "") + (if (clip.flipV) " · lật dọc" else ""),
        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp),
    )
    Text("Vị trí trong khung hình", color = Color.White, fontSize = 13.sp)
    Row(Modifier.padding(top = 6.dp)) {
        Chip("Vừa khung (giữ toàn bộ hình)", !clip.fill) { vm.updateSelected { it.copy(fill = false) } }
        Chip("Lấp đầy", clip.fill) { vm.updateSelected { it.copy(fill = true) } }
    }
    ApplyAllButton { val f = clip.fill; vm.applyToAll { it.copy(fill = f) } }
}

@Composable
private fun ToolCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White)
        }
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- crop ----------

@Composable
private fun CropPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    val crop = clip.crop
    Text("Tỉ lệ vùng cắt", color = Color.White, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        val options = listOf<Pair<String, Float?>>(
            "Tự do" to null, "1:1" to 1f, "9:16" to 9f / 16f, "16:9" to 16f / 9f,
            "4:5" to 4f / 5f, "3:4" to 3f / 4f, "4:3" to 4f / 3f, "2.35:1" to 2.35f,
        )
        options.forEach { (label, a) ->
            Chip(label, crop.aspect == a) { vm.updateSelected { it.copy(crop = it.crop.copy(aspect = a)) } }
        }
    }
    LabeledSlider("Thu phóng", crop.zoom, 1f..4f, String.format(Locale.US, "%.2fx", crop.zoom)) { v ->
        vm.updateSelected { it.copy(crop = it.crop.copy(zoom = v)) }
    }
    LabeledSlider("Ngang", crop.centerX, 0f..1f, pct(crop.centerX - 0.5f)) { v ->
        vm.updateSelected { it.copy(crop = it.crop.copy(centerX = v)) }
    }
    LabeledSlider("Dọc", crop.centerY, 0f..1f, pct(crop.centerY - 0.5f)) { v ->
        vm.updateSelected { it.copy(crop = it.crop.copy(centerY = v)) }
    }
    TextButton(onClick = { vm.updateSelected { it.copy(crop = CropSpec()) } }) { Text("Đặt lại vùng cắt", color = Accent) }
}

// ---------- filters ----------

private fun previewMatrix(f: FilterPreset): ColorMatrix? {
    fun scale(r: Float, g: Float, b: Float) = ColorMatrix(
        floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
    )
    return when (f) {
        FilterPreset.NONE -> null
        FilterPreset.BW, FilterPreset.NOIR -> ColorMatrix().apply { setToSaturation(0f) }
        FilterPreset.SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        FilterPreset.WARM -> scale(1.1f, 1.02f, 0.88f)
        FilterPreset.COOL -> scale(0.9f, 1f, 1.12f)
        FilterPreset.VIVID -> ColorMatrix().apply { setToSaturation(1.4f) }
        FilterPreset.FADE -> ColorMatrix().apply { setToSaturation(0.65f) }
        FilterPreset.DRAMA -> ColorMatrix().apply { setToSaturation(0.8f) }
        FilterPreset.PINK -> scale(1.1f, 0.94f, 1.04f)
        FilterPreset.INVERT -> ColorMatrix(
            floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
        )
    }
}

@Composable
private fun FilterPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, clip.uri, clip.trimStartMs) {
        value = Thumbnails.get(context, clip.uri, clip.isImage, clip.sourceTimeAt(clip.durationMs / 2), 200)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        FilterPreset.entries.forEach { f ->
            val selected = clip.filter == f
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 10.dp).clickable { vm.updateSelected { it.copy(filter = f) } },
            ) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Surface2)
                        .then(if (selected) Modifier.border(BorderStroke(2.dp, Accent), RoundedCornerShape(8.dp)) else Modifier),
                ) {
                    thumb?.let {
                        Image(
                            it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            colorFilter = previewMatrix(f)?.let { m -> ColorFilter.colorMatrix(m) },
                        )
                    }
                }
                Text(f.label, color = if (selected) Accent else Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    ApplyAllButton { val f = clip.filter; vm.applyToAll { it.copy(filter = f) } }
}

// ---------- adjustments ----------

@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: return
    val a = clip.adjust
    fun set(f: (Adjustments) -> Adjustments) = vm.updateSelected { it.copy(adjust = f(it.adjust)) }
    LabeledSlider("Độ sáng", a.brightness, -1f..1f, pct(a.brightness)) { v -> set { it.copy(brightness = v) } }
    LabeledSlider("Độ tương phản", a.contrast, -1f..1f, pct(a.contrast)) { v -> set { it.copy(contrast = v) } }
    LabeledSlider("Độ bão hòa", a.saturation, -1f..1f, pct(a.saturation)) { v -> set { it.copy(saturation = v) } }
    LabeledSlider("Nhiệt độ màu", a.warmth, -1f..1f, pct(a.warmth)) { v -> set { it.copy(warmth = v) } }
    LabeledSlider("Sắc độ", a.hue, -180f..180f, "${a.hue.roundToInt()}°") { v -> set { it.copy(hue = v) } }
    Row {
        TextButton(onClick = { set { Adjustments() } }) { Text("Đặt lại", color = Accent) }
        ApplyAllButton { vm.applyToAll { it.copy(adjust = a) } }
    }
}

// ---------- text & stickers ----------

private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFE14D, 0xFFFF3D6E, 0xFF00E5C8, 0xFF3D8BFF, 0xFFFF8A00, 0xFF8BE04E, 0xFFB46CFF,
).map { it.toInt() }

private val STICKERS = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "🥳", "😭", "😡", "🤔", "😴", "👍", "👏", "🙏", "💪",
    "🔥", "✨", "⭐", "🎉", "❤️", "💔", "💯", "✅", "❌", "⚡", "🌈", "☀️", "🌙", "🎵", "🎬", "📷",
    "🍀", "🌸", "🐶", "🐱", "🍕", "🎂", "🎁", "🚀", "💥", "👀", "😱", "🤯", "🙌", "👉", "👈", "⬆️",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextPanel(vm: EditorViewModel, stickers: Boolean) {
    val clip = vm.selectedClip
    val items = clip?.texts?.filter { it.isSticker == stickers }.orEmpty()
    val editing = clip?.texts?.firstOrNull { it.id == vm.editingTextId }

    if (stickers && editing == null) {
        FlowRow {
            STICKERS.forEach { e ->
                Box(
                    Modifier.size(44.dp).clickable {
                        vm.addText(TextItem(text = e, size = 0.12f, x = 0.5f, y = 0.5f, bold = false, isSticker = true))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 26.sp) }
            }
        }
    } else if (!stickers && editing == null) {
        Button(
            onClick = { vm.addText(TextItem(text = "Văn bản")) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        ) { Text("+ Thêm văn bản") }
    }

    if (clip == null) {
        Text("Hãy đặt đầu phát lên một đoạn video.", color = TextDim, fontSize = 12.sp)
        return
    }
    Text(
        "Văn bản và nhãn dán hiển thị trong suốt đoạn đang chọn (${formatShort(clip.durationMs)}). " +
            "Muốn hiện trong một phần, hãy tách đoạn trước.",
        color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp),
    )

    if (items.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            items.forEach { t ->
                Chip(t.text.take(14), t.id == vm.editingTextId) {
                    vm.editingTextId = if (vm.editingTextId == t.id) null else t.id
                }
            }
        }
    }

    if (editing != null) TextEditor(vm, editing)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextEditor(vm: EditorViewModel, t: TextItem) {
    fun set(f: (TextItem) -> TextItem) = vm.updateText(t.id, f)
    if (!t.isSticker) {
        OutlinedTextField(
            value = t.text,
            onValueChange = { v -> set { it.copy(text = v) } },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text("Nội dung") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TEXT_COLORS.forEach { c ->
                Box(
                    Modifier.padding(end = 8.dp).size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (t.color == c) 3.dp else 1.dp, if (t.color == c) Accent else Color.Gray, CircleShape)
                        .clickable { set { it.copy(color = c) } },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("Chữ đậm", t.bold) { set { it.copy(bold = !it.bold) } }
            Chip("Nền chữ", t.background != null) {
                set { it.copy(background = if (it.background == null) Color(0xAA000000).toArgb() else null) }
            }
        }
    }
    LabeledSlider("Cỡ", t.size, 0.02f..0.3f, pct(t.size)) { v -> set { it.copy(size = v) } }
    LabeledSlider("Vị trí ngang", t.x, 0f..1f, pct(t.x)) { v -> set { it.copy(x = v) } }
    LabeledSlider("Vị trí dọc", t.y, 0f..1f, pct(t.y)) { v -> set { it.copy(y = v) } }
    Row {
        TextButton(onClick = { vm.deleteText(t.id) }) {
            Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF6B6B))
            Text(" Xóa", color = Color(0xFFFF6B6B))
        }
        TextButton(onClick = { vm.editingTextId = null }) { Text("Xong", color = Accent) }
    }
}

// ---------- canvas ----------

@Composable
private fun CanvasPanel(vm: EditorViewModel) {
    val p = vm.project
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        AspectRatio.entries.forEach { a ->
            Chip(a.label, p.aspect == a) { vm.updateProject { it.copy(aspect = a) } }
        }
    }
    val (w, h) = ProjectOps.canvasSize(p)
    Text("Kích thước khung hình: $w × $h", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Text(
        "\"Gốc\" dùng đúng kích thước của video đầu tiên. Với tỉ lệ khác, cạnh dài được giữ nguyên để không giảm chất lượng.",
        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
    )
    Text("Đoạn không khớp tỉ lệ:", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.padding(top = 4.dp)) {
        Chip("Vừa khung", p.clips.none { it.fill }) { vm.updateProject { it.copy(clips = it.clips.map { c -> c.copy(fill = false) }) } }
        Chip("Lấp đầy", p.clips.isNotEmpty() && p.clips.all { it.fill }) {
            vm.updateProject { it.copy(clips = it.clips.map { c -> c.copy(fill = true) }) }
        }
    }
}

// ---------- audio tracks ----------

@Composable
private fun AudioPanel(vm: EditorViewModel) {
    val a = vm.selectedAudio ?: return
    val total = vm.project.durationMs
    Text("${a.kind.label}: ${a.name}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        "Trên dòng thời gian: ${formatTime(a.startMs)} → ${formatTime(a.startMs + minOf(a.timelineLengthMs(total), (total - a.startMs).coerceAtLeast(0)))}" +
            " · độ dài ${formatTime(a.durationMs)}",
        color = TextDim, fontSize = 12.sp,
    )
    LabeledSlider("Âm lượng", a.volume, 0f..2f, "${pct(a.volume)}%") { v ->
        vm.updateSelectedAudio { it.copy(volume = (v * 20).roundToInt() / 20f) }
    }
    LabeledSlider("Tốc độ", a.speed, 0.5f..3f, formatSpeed(a.speed)) { v ->
        vm.updateSelectedAudio { it.copy(speed = (v * 20).roundToInt() / 20f) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { sp ->
            Chip(formatSpeed(sp), a.speed == sp) { vm.updateSelectedAudio { it.copy(speed = sp) } }
        }
    }
    Text("Đoạn âm thanh dùng", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    RangeSlider(
        value = a.sourceStartMs.toFloat()..a.sourceEndMs.toFloat(),
        onValueChange = { r -> vm.updateSelectedAudio { ProjectOps.trimAudio(it, r.start.toLong(), r.endInclusive.toLong()) } },
        valueRange = 0f..a.sourceDurationMs.toFloat(),
        colors = sliderColors(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(a.sourceStartMs), color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(formatTime(a.sourceEndMs), color = TextDim, fontSize = 12.sp)
    }
    if (a.kind == AudioKind.MUSIC) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lặp lại đến hết video", color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = a.loop,
                onCheckedChange = { l -> vm.updateSelectedAudio { it.copy(loop = l) } },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
    val allMuted = vm.project.clips.all { it.volume == 0f }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tắt tiếng video gốc", color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            checked = allMuted,
            onCheckedChange = { mute -> vm.updateProject { p -> p.copy(clips = p.clips.map { it.copy(volume = if (mute) 0f else 1f) }) } },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        OutlinedButton(onClick = { vm.updateSelectedAudio { it.copy(startMs = vm.playheadMs) } }) { Text("Đặt tại đầu phát") }
        Spacer(Modifier.width(8.dp))
        if (a.kind == AudioKind.VOICE) {
            OutlinedButton(onClick = { vm.switchPanel(Panel.VOICE) }) { Text("Sửa giọng đọc") }
            Spacer(Modifier.width(8.dp))
        }
        OutlinedButton(onClick = { vm.deleteSelectedAudio() }) { Text("Xóa", color = Color(0xFFFF6B6B)) }
    }
}

// ---------- overlays ----------

@Composable
private fun OverlayPanel(vm: EditorViewModel) {
    val o = vm.selectedOverlay ?: return
    fun set(f: (com.cutviet.editor.model.Overlay) -> com.cutviet.editor.model.Overlay) = vm.updateSelectedOverlay(f)
    Text(
        "${formatTime(o.startMs)} → ${formatTime(o.endMs)} · giữ và kéo thanh tím trên dòng thời gian để di chuyển, kéo hai mép trắng để kéo dãn",
        color = TextDim, fontSize = 11.sp,
    )
    LabeledSlider("Kích thước", o.scale, 0.1f..1.5f, "${pct(o.scale)}%") { v -> set { it.copy(scale = v) } }
    LabeledSlider("Vị trí ngang", o.x, 0f..1f, pct(o.x)) { v -> set { it.copy(x = v) } }
    LabeledSlider("Vị trí dọc", o.y, 0f..1f, pct(o.y)) { v -> set { it.copy(y = v) } }
    LabeledSlider("Xoay", o.rotation, -180f..180f, "${o.rotation.roundToInt()}°") { v -> set { it.copy(rotation = v.roundToInt().toFloat()) } }
    LabeledSlider("Độ trong suốt", o.opacity, 0f..1f, "${pct(o.opacity)}%") { v -> set { it.copy(opacity = v) } }
    if (o.isImage) {
        LabeledSlider(
            "Thời lượng", o.durationMs / 1000f, 0.5f..30f, String.format(Locale.US, "%.1f giây", o.durationMs / 1000f),
        ) { v -> set { it.copy(trimStartMs = 0, trimEndMs = (v * 1000).toLong()) } }
    } else {
        if (o.hasAudio) {
            LabeledSlider("Âm lượng lớp phủ", o.volume, 0f..2f, "${pct(o.volume)}%") { v ->
                set { it.copy(volume = (v * 20).roundToInt() / 20f) }
            }
        }
        LabeledSlider("Tốc độ", o.speed, 0.25f..4f, formatSpeed(o.speed)) { v -> set { it.copy(speed = (v * 20).roundToInt() / 20f) } }
        Text("Cắt lớp phủ", color = Color.White, fontSize = 13.sp)
        RangeSlider(
            value = o.trimStartMs.toFloat()..o.trimEndMs.toFloat(),
            onValueChange = { r -> set { ProjectOps.trimOverlay(it, r.start.toLong(), r.endInclusive.toLong()) } },
            valueRange = 0f..o.sourceDurationMs.toFloat(),
            colors = sliderColors(),
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
        OutlinedButton(onClick = { vm.switchPanel(Panel.OVERLAY_KEY) }) { Text("Tẩy nền") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { vm.moveOverlayToPlayhead() }) { Text("Đặt tại đầu phát") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { set { it.copy(flipH = !it.flipH) } }) { Text("Lật ngang") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { set { it.copy(scale = 1f, x = 0.5f, y = 0.5f, rotation = 0f) } }) { Text("Toàn khung") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { vm.deleteSelectedOverlay() }) { Text("Xóa", color = Color(0xFFFF6B6B)) }
    }
}

private val KEY_COLORS = listOf(
    "Phông xanh lá" to 0xFF00B140.toInt(),
    "Phông xanh dương" to 0xFF0047BB.toInt(),
    "Nền đen" to 0xFF000000.toInt(),
    "Nền trắng" to 0xFFFFFFFF.toInt(),
)

@Composable
private fun OverlayKeyPanel(vm: EditorViewModel) {
    val o = vm.selectedOverlay ?: return
    val k = o.chroma
    fun set(f: (com.cutviet.editor.model.ChromaKey?) -> com.cutviet.editor.model.ChromaKey?) = vm.updateSelectedOverlay { it.copy(chroma = f(it.chroma)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Xóa màu nền của lớp phủ", color = Color.White, modifier = Modifier.weight(1f))
        Switch(
            checked = k != null,
            onCheckedChange = { on -> set { if (on) com.cutviet.editor.model.ChromaKey() else null } },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
    if (k == null) {
        Text(
            "Dùng cho video quay trên phông xanh lá/xanh dương, hoặc ảnh có nền một màu. Phần nền sẽ trong suốt để lộ video bên dưới.",
            color = TextDim, fontSize = 12.sp,
        )
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        KEY_COLORS.forEach { (label, c) ->
            Row(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (k.color == c) Accent else Surface2)
                    .clickable { set { it?.copy(color = c) } }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c)).border(1.dp, Color.Gray, CircleShape))
                Text(" $label", color = if (k.color == c) Color.Black else Color.White, fontSize = 12.sp)
            }
        }
    }
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(k.color, it) }
    LabeledSlider("Màu tùy chỉnh (sắc độ)", hsv[0], 0f..360f, "${hsv[0].roundToInt()}°") { h ->
        set { it?.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 0.8f))) }
    }
    LabeledSlider("Cường độ tẩy", k.strength, 0f..1f, pct(k.strength)) { v -> set { it?.copy(strength = v) } }
    LabeledSlider("Làm mềm viền", k.softness, 0f..1f, pct(k.softness)) { v -> set { it?.copy(softness = v) } }
}

// ---------- voice-over (text to speech) ----------

@Composable
private fun VoicePanel(vm: EditorViewModel) {
    val context = LocalContext.current
    val engine = vm.voice
    val editing = vm.selectedAudio?.takeIf { it.kind == AudioKind.VOICE }
    val spec = editing?.voice
    var text by remember(editing?.id) { mutableStateOf(spec?.text ?: "") }
    var voiceName by remember(editing?.id) { mutableStateOf(spec?.voiceName) }
    var pitch by remember(editing?.id) { mutableFloatStateOf(spec?.pitch ?: 1f) }
    var rate by remember(editing?.id) { mutableFloatStateOf(spec?.rate ?: 1f) }
    var exactMs by remember { mutableStateOf<Long?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val startMs = editing?.startMs ?: vm.playheadMs
    val status = engine.status.value
    val voices = engine.voices.value
    if (voiceName == null && voices.isNotEmpty()) voiceName = voices.first().name.ifEmpty { null }

    val estimate = remember(text, voiceName, rate, exactMs) { engine.estimateMs(text, voiceName, rate) }
    val lengthMs = exactMs ?: estimate

    // Measure the exact length by synthesizing in the background once typing pauses.
    LaunchedEffect(text, voiceName, pitch, rate, status) {
        exactMs = null
        if (status != VoiceEngine.Status.READY || text.isBlank()) return@LaunchedEffect
        delay(900)
        measuring = true
        val f = java.io.File(context.cacheDir, "voice_measure.wav")
        exactMs = engine.synthesize(text, voiceName, pitch, rate, f)
        measuring = false
    }
    LaunchedEffect(startMs, lengthMs, text) {
        vm.voicePreviewRange = if (text.isBlank()) null else startMs..(startMs + lengthMs)
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.voicePreviewRange = null
            engine.stop()
        }
    }

    when (status) {
        VoiceEngine.Status.LOADING -> {
            Text("Đang khởi động bộ đọc tiếng Việt…", color = TextDim)
            return
        }
        VoiceEngine.Status.NO_VIETNAMESE, VoiceEngine.Status.FAILED -> {
            Text(
                "Điện thoại chưa có giọng đọc tiếng Việt. Hãy cài \"Dịch vụ lời nói của Google\" (Speech Services by Google) " +
                    "và tải dữ liệu tiếng Việt, rồi mở lại mục này.",
                color = Color.White, fontSize = 13.sp,
            )
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Tải giọng tiếng Việt") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Cài đặt giọng nói") }
            }
            return
        }
        VoiceEngine.Status.READY -> Unit
    }

    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= engine.maxInputLength) text = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Nhập văn bản cần đọc") },
        minLines = 2,
        maxLines = 5,
    )

    // Duration estimate and where it lands on the video.
    val endMs = startMs + lengthMs
    val total = vm.project.durationMs
    val firstClip = vm.project.clipAt(startMs)?.first
    val lastClip = vm.project.clipAt((endMs - 1).coerceAtLeast(startMs))?.first
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Surface2, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(
            when {
                text.isBlank() -> "Gõ văn bản để xem thời lượng đọc"
                exactMs != null -> "Thời lượng đọc: ${formatTime(lengthMs)} (đã đo chính xác)"
                measuring -> "Ước tính: ${formatTime(lengthMs)} · đang đo chính xác…"
                else -> "Ước tính: ${formatTime(lengthMs)}"
            },
            color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        if (text.isNotBlank()) {
            Text(
                "Đọc từ ${formatTime(startMs)} đến ${formatTime(endMs)}" +
                    (if (firstClip != null && lastClip != null) {
                        if (firstClip == lastClip) " · trong đoạn ${firstClip + 1}" else " · từ đoạn ${firstClip + 1} đến đoạn ${lastClip + 1}"
                    } else ""),
                color = Color.White, fontSize = 12.sp,
            )
            if (endMs > total) {
                Text(
                    "Dài hơn phần còn lại của video ${formatTime(endMs - total)}. Hãy tăng tốc độ đọc hoặc rút gọn văn bản.",
                    color = Color(0xFFFFB74D), fontSize = 12.sp,
                )
            }
            Text(
                "${VoiceEngine.syllableCount(text)} tiếng · ${text.length}/${engine.maxInputLength} ký tự",
                color = TextDim, fontSize = 11.sp,
            )
        }
    }

    Text("Giọng đọc tiếng Việt", color = Color.White, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        voices.forEach { v ->
            Chip(v.label, (voiceName ?: "") == v.name) { voiceName = v.name.ifEmpty { null } }
        }
    }
    Text("Mẫu giọng", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        VoiceEngine.STYLES.forEach { st ->
            Chip(st.label, kotlin.math.abs(pitch - st.pitch) < 0.01f) { pitch = st.pitch }
        }
    }
    LabeledSlider("Tốc độ đọc", rate, 0.5f..3f, formatSpeed(rate)) { v -> rate = (v * 20).roundToInt() / 20f }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { r -> Chip(formatSpeed(r), rate == r) { rate = r } }
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = text.isNotBlank(),
            onClick = {
                if (speaking) { engine.stop(); speaking = false } else { engine.speak(text, voiceName, pitch, rate); speaking = true }
            },
        ) { Text(if (speaking) "Dừng" else "Nghe thử") }
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = text.isNotBlank() && !vm.busy,
            onClick = {
                engine.stop()
                vm.addVoice(VoiceSpec(text.trim(), voiceName, pitch, rate), startMs, editing?.id) { ok -> if (ok) vm.closePanel() }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        ) { Text(if (editing != null) "Cập nhật giọng đọc" else "Thêm vào video") }
    }
}
