package com.cutviet.editor.ui.editor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutviet.editor.media.SubtitleRenderer
import com.cutviet.editor.media.dubbing.DubMode
import com.cutviet.editor.media.dubbing.DubModel
import com.cutviet.editor.media.dubbing.DubSettings
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.SubtitleCues
import com.cutviet.editor.ui.formatTime
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.TextDim
import kotlin.math.roundToInt

/** "Phụ đề AI": choose the spoken language and start making subtitles for the whole video. */
@Composable
fun SubtitleDialog(vm: EditorViewModel, onDismiss: () -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DubSettings(context) }
    var language by remember { mutableStateOf(SourceLanguage.ENGLISH) }
    var mode by remember { mutableStateOf(settings.mode) }
    val separationReady = remember { DubModel.MDX_VOC_FT.isReady(context) }
    var separate by remember { mutableStateOf(separationReady) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Phụ đề AI", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDismiss) { Text("Đóng", color = Accent) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    "Nghe lời nói trong tất cả các đoạn video, dịch sang tiếng Việt và đặt phụ đề đúng thời điểm trên timeline. " +
                        "Sửa từng câu được, và khi xuất có thể ghi phụ đề vào video.",
                    color = TextDim, fontSize = 13.sp,
                )

                Label("Ngôn ngữ trong video")
                Chips(SourceLanguage.entries, language, { it.label }) { language = it }
                if (language == SourceLanguage.VIETNAMESE) {
                    Text("Video nói tiếng Việt: phụ đề là chính lời nói đó, chép lại có dấu câu.", color = TextDim, fontSize = 12.sp)
                }

                Label("Cách làm")
                Chips(DubMode.entries, mode, { it.label }) { mode = it; settings.mode = it }
                Text(
                    when (mode) {
                        DubMode.AUTO -> "Online (Gemini) khi có Wi-Fi hoặc khi chưa có model offline; lỗi mạng hay hết lượt thì tự chuyển offline."
                        DubMode.ONLINE_FIRST -> "Luôn thử Gemini trước khi có mạng; lỗi thì chuyển offline."
                        DubMode.OFFLINE -> "Chỉ dùng Whisper" + (if (language == SourceLanguage.VIETNAMESE) "" else " + Gemma") + " trên máy, không cần mạng."
                    },
                    color = TextDim, fontSize = 12.sp,
                )
                Text("Phụ đề không đọc thành tiếng nên tốn rất ít lượt Gemini so với lồng tiếng.", color = TextDim, fontSize = 12.sp)

                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tách giọng trước", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (separationReady) "Bỏ nhạc và tiếng ồn để nghe lời rõ hơn. Chính xác hơn nhưng lâu hơn (khoảng 1,3 giây cho mỗi giây video)."
                            else "Cần tải model tách giọng ở mục Model & khoá.",
                            color = TextDim, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = separate && separationReady, enabled = separationReady, onCheckedChange = { separate = it })
                }

                Spacer(Modifier.height(12.dp))
                val missing = buildList {
                    if (mode == DubMode.OFFLINE || settings.geminiKey.isBlank()) {
                        if (!DubModel.WHISPER_LARGE_V3.isReady(context)) add(DubModel.WHISPER_LARGE_V3)
                        if (language != SourceLanguage.VIETNAMESE && !DubModel.GEMMA_3N_E4B.isReady(context)) add(DubModel.GEMMA_3N_E4B)
                    }
                }
                if (missing.isNotEmpty()) {
                    Text("Còn thiếu: " + missing.joinToString { it.label } + " (hoặc nhập khoá Gemini để làm online)", color = Color(0xFFFFC46B), fontSize = 13.sp)
                }
                TextButton(onClick = onOpenModels) { Text("Model & khoá", color = Accent) }
                if (vm.project.subtitles.isNotEmpty()) {
                    Text("Phụ đề mới sẽ thay ${vm.project.subtitles.size} câu đang có.", color = Color(0xFFFFC46B), fontSize = 12.sp)
                }

                val status = vm.subtitleStatus
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = Color.White, fontSize = 14.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Accent)
                    OutlinedButton(onClick = { vm.cancelSubtitles() }) { Text("Huỷ tạo phụ đề") }
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            vm.startSubtitles(language, separate && separationReady)
                            onDismiss()
                        },
                        enabled = vm.project.clips.any { !it.isImage && it.hasAudio },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Tạo phụ đề") }
                    Text("Chạy nền kể cả khi tắt màn hình; xem tiến trình trên thanh thông báo.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** The "Phụ đề" panel: make, edit, time and style the subtitle lines, or save them as .srt. */
@Composable
fun SubtitlePanel(vm: EditorViewModel) {
    val context = LocalContext.current
    val project = vm.project
    val subs = project.subtitles
    val style = project.subtitleStyle
    val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(SubtitleCues.srt(vm.project.subtitles).toByteArray()) }
            }.isSuccess
            vm.toast = if (ok) "Đã lưu tệp phụ đề." else "Không lưu được tệp phụ đề."
        }
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.showSubtitleDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
            Text(if (subs.isEmpty()) "Tạo phụ đề AI" else "Tạo lại")
        }
        OutlinedButton(onClick = { vm.addSubtitleAtPlayhead() }) { Text("Thêm câu") }
        if (subs.isNotEmpty()) {
            OutlinedButton(onClick = { saveSrt.launch("${project.name}.srt") }) { Text("Lưu .srt") }
            OutlinedButton(onClick = { vm.clearSubtitles() }) { Text("Xoá hết", color = Color(0xFFFF6B6B)) }
        }
    }
    vm.subtitleStatus?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    if (subs.isEmpty()) {
        Text(
            "Chưa có phụ đề. Bấm \"Tạo phụ đề AI\" để nghe và dịch lời nói tiếng Anh, tiếng Trung (hoặc chép lời tiếng Việt), hoặc \"Thêm câu\" để tự gõ.",
            color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    // The line being edited: the one under the playhead, else the one picked last.
    val current = SubtitleCues.at(subs, vm.playheadMs) ?: vm.selectedSubtitle
    if (current != null) {
        Spacer(Modifier.height(8.dp))
        Text("${formatTime(current.startMs)} → ${formatTime(current.endMs)}", color = Accent, fontSize = 12.sp)
        OutlinedTextField(
            value = current.text,
            onValueChange = { t -> vm.updateSubtitle(current.id) { it.copy(text = t) } },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
        )
        if (current.source.isNotBlank() && current.source != current.text) {
            Text("Gốc: ${current.source}", color = TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Đầu −0,1s") { vm.updateSubtitle(current.id) { it.copy(startMs = (it.startMs - 100).coerceAtLeast(0)) } }
            SmallButton("Đầu +0,1s") { vm.updateSubtitle(current.id) { it.copy(startMs = minOf(it.startMs + 100, it.endMs - 100)) } }
            SmallButton("Đầu = kim") { vm.setSubtitleEdge(current.id, start = true) }
            SmallButton("Cuối −0,1s") { vm.updateSubtitle(current.id) { it.copy(endMs = maxOf(it.endMs - 100, it.startMs + 100)) } }
            SmallButton("Cuối +0,1s") { vm.updateSubtitle(current.id) { it.copy(endMs = it.endMs + 100) } }
            SmallButton("Cuối = kim") { vm.setSubtitleEdge(current.id, start = false) }
            SmallButton("Xoá câu", Color(0xFFFF6B6B)) { vm.deleteSubtitle(current.id) }
        }
    } else {
        Text("Kim đang ở chỗ không có phụ đề. Chọn một câu bên dưới để sửa.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }

    Spacer(Modifier.height(8.dp))
    LabeledSlider("Cỡ chữ", style.size, 0.025f..0.1f, "${(style.size * 1000).roundToInt()}") { v -> vm.updateSubtitleStyle { it.copy(size = v) } }
    LabeledSlider("Vị trí (từ trên xuống)", style.y, 0.15f..0.98f, "${(style.y * 100).roundToInt()}%") { v -> vm.updateSubtitleStyle { it.copy(y = v) } }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Chip("Chữ trắng", style.color == WHITE) { vm.updateSubtitleStyle { it.copy(color = WHITE) } }
        Chip("Chữ vàng", style.color == YELLOW) { vm.updateSubtitleStyle { it.copy(color = YELLOW) } }
        Chip("Viền đen", !style.box) { vm.updateSubtitleStyle { it.copy(box = false) } }
        Chip("Nền tối", style.box) { vm.updateSubtitleStyle { it.copy(box = true) } }
        Chip("Đậm", style.bold) { vm.updateSubtitleStyle { it.copy(bold = !it.bold) } }
    }

    Text("${subs.size} câu · chạm để tới câu đó", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    subs.forEach { s ->
        val on = s.id == current?.id
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp))
                .background(if (on) Color(0xFF2A2340) else Surface2)
                .clickable { vm.showSubtitle(s) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(formatTime(s.startMs), color = if (on) Accent else TextDim, fontSize = 11.sp, modifier = Modifier.width(52.dp))
            Text(s.text, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val YELLOW = 0xFFFFE14D.toInt()

@Composable
private fun SmallButton(text: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Surface2).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Draws the subtitle under the playhead over the preview, with the same drawing as the export,
 * scaled to the preview. Tapping it opens the subtitle panel on that line.
 */
@Composable
fun SubtitlePreview(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    if (project.clips.isEmpty() || project.subtitles.isEmpty() || vm.trim != null) return
    val sub = SubtitleCues.at(project.subtitles, vm.playheadMs)?.takeIf { it.text.isNotBlank() } ?: return
    val (canvasW, canvasH) = ProjectOps.canvasSize(project)
    BoxWithConstraints(modifier) {
        val frame = PreviewFrame(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), canvasW, canvasH)
        val w = frame.width.roundToInt().coerceAtLeast(16)
        val h = frame.height.roundToInt().coerceAtLeast(16)
        val style = project.subtitleStyle
        val bitmap = remember(sub.text, style, w, h) { SubtitleRenderer.render(sub.text, style, w, h).asImageBitmap() }
        Layout(
            content = {
                Image(
                    bitmap, null,
                    modifier = Modifier.clickable {
                        vm.selectedSubtitleId = sub.id
                        vm.openPanel(Panel.SUBTITLE)
                    },
                )
            },
        ) { measurables, constraints ->
            val bw = bitmap.width
            val bh = bitmap.height
            val p = measurables.first().measure(Constraints.fixed(bw, bh))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (frame.left + frame.width / 2 - bw / 2f).roundToInt()
                val y = (frame.top + style.y.coerceIn(0.05f, 1f) * frame.height - bh).roundToInt()
                p.place(IntOffset(x, y))
            }
        }
    }
}

/** The subtitle lane on the timeline: one block per line; tapping a block edits that line. */
@Composable
fun SubtitleLane(vm: EditorViewModel, half: Dp, totalWidth: Dp, pxPerMs: Float) {
    val subs = vm.project.subtitles
    if (subs.isEmpty()) return
    val density = LocalDensity.current
    Row(Modifier.padding(vertical = 2.dp)) {
        Spacer(Modifier.width(half))
        Box(Modifier.width(totalWidth).height(26.dp)) {
            subs.forEach { s ->
                val selected = s.id == vm.selectedSubtitleId && vm.panel == Panel.SUBTITLE
                Box(
                    Modifier
                        .offset(x = with(density) { (s.startMs * pxPerMs).toDp() })
                        .width(with(density) { ((s.endMs - s.startMs).coerceAtLeast(60) * pxPerMs).toDp() }.coerceAtLeast(3.dp))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF3A3A14))
                        .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(4.dp)) else Modifier)
                        .clickable {
                            vm.showSubtitle(s)
                            if (vm.panel != Panel.SUBTITLE) vm.openPanel(Panel.SUBTITLE)
                        }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(s.text, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
        Spacer(Modifier.width(half))
    }
}

/** Shown above the toolbar while subtitles are being made. */
@Composable
fun SubtitleBanner(status: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2E2A14)).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
        Spacer(Modifier.width(8.dp))
        Text(status, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}
