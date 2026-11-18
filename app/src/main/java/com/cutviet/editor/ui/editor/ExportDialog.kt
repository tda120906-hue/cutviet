package com.cutviet.editor.ui.editor

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.cutviet.editor.media.Exporter
import com.cutviet.editor.model.ExportCodec
import com.cutviet.editor.model.ExportQuality
import com.cutviet.editor.model.ExportResolution
import com.cutviet.editor.model.ExportSettings
import com.cutviet.editor.ui.formatBitrate
import com.cutviet.editor.ui.formatBytes
import com.cutviet.editor.ui.formatTime
import com.cutviet.editor.ui.formatClock
import com.cutviet.editor.ui.formatDuration
import com.cutviet.editor.model.SplitExport
import com.cutviet.editor.model.SplitMode
import com.cutviet.editor.model.SplitPart
import com.cutviet.editor.model.SplitPlan
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.TextDim

@Composable
fun ExportDialog(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onStart: (ExportSettings) -> Unit,
    onStartSplit: (ExportSettings, List<SplitPart>) -> Unit,
) {
    var settings by remember { mutableStateOf(ExportSettings()) }
    var split by remember { mutableStateOf(false) }
    val total = vm.project.durationMs
    var plan by remember {
        mutableStateOf(
            SplitPlan(
                count = 2,
                fixedMs = if (total > 2 * 3_600_000L) 3_600_000L else 10 * 60_000L,
                customMs = List(2) { total / 2 },
                overlapMs = 0,
            ),
        )
    }
    var movieMode by remember { mutableStateOf(false) }
    var overlapMs by remember { mutableLongStateOf(5_000L) }
    val effective = plan.copy(overlapMs = if (movieMode) overlapMs else 0)
    val parts = if (split) SplitExport.parts(total, effective) else emptyList()
    val leftover = if (split && plan.mode == SplitMode.CUSTOM) SplitExport.customLeftoverMs(total, plan.customMs) else 0L

    val (w, h) = vm.outputSize(settings)
    val bitrate = vm.estimatedBitrate(settings)
    val mediaMs = if (split) parts.sumOf { it.durationMs } else total
    val estBytes = ((bitrate + 192_000L) * (mediaMs / 1000.0) / 8).toLong()
    val (estMs, measured) = vm.estimateExportMs(settings, mediaMs)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xuất video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    Option("1 video", !split) { split = false }
                    Option("Chia nhiều video", split) { split = true }
                }
                if (split) {
                    SplitOptions(
                        total = total, plan = plan, onPlan = { plan = it },
                        movieMode = movieMode, onMovieMode = { movieMode = it },
                        overlapMs = overlapMs, onOverlap = { overlapMs = it },
                        parts = parts, leftover = leftover,
                    )
                }
                Section("Độ phân giải")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ExportResolution.entries.forEach { r ->
                        val size = vm.outputSize(settings.copy(resolution = r))
                        // Hide options that would not change anything (larger than the source).
                        if (r != ExportResolution.ORIGINAL && size == vm.outputSize(ExportSettings())) return@forEach
                        Option(r.label, settings.resolution == r) { settings = settings.copy(resolution = r) }
                    }
                }
                Section("Chất lượng (bitrate)")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ExportQuality.entries.forEach { q ->
                        Option(q.label, settings.quality == q) { settings = settings.copy(quality = q) }
                    }
                }
                Section("Định dạng nén")
                Row {
                    ExportCodec.entries.forEach { c ->
                        Option(c.label, settings.codec == c) { settings = settings.copy(codec = c) }
                    }
                }
                if (vm.project.subtitles.isNotEmpty()) {
                    Section("Phụ đề (${vm.project.subtitles.size} câu)")
                    Row {
                        Option("Ghi vào video", settings.burnSubtitles) { settings = settings.copy(burnSubtitles = true) }
                        Option("Không", !settings.burnSubtitles) { settings = settings.copy(burnSubtitles = false) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Kích thước: $w × $h", color = Color.White)
                Text("Bitrate video: ${formatBitrate(bitrate)}", color = Color.White)
                if (split) {
                    Text("${parts.size} video · tổng ${formatClock(mediaMs)} · ước tính ${formatBytes(estBytes)}", color = Color.White)
                } else {
                    Text("Thời lượng: ${formatTime(total)} · ước tính ${formatBytes(estBytes)}", color = Color.White)
                }
                Text(
                    "Thời gian xuất: khoảng ${formatDuration(estMs)}" + if (measured) " (theo tốc độ máy bạn ở lần xuất trước)" else " (ước lượng thô, sẽ chính xác hơn sau lần xuất đầu)",
                    color = Color.White,
                )
                Text(
                    "Khung hình/giây giữ nguyên như video gốc. \"Gốc\" giữ độ phân giải và bitrate tương đương bản gốc." +
                        (if (settings.codec == ExportCodec.H265) " H.265 cho tệp nhỏ hơn nhưng một số máy cũ không phát được." else "") +
                        (if (split) " Các video được xuất lần lượt và chạy nền, tắt màn hình vẫn tiếp tục; mỗi video xong được lưu ngay vào Movies/CutViet." else ""),
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            val ok = !split || (parts.isNotEmpty() && leftover >= 0)
            Button(
                onClick = { if (split) onStartSplit(settings, parts) else onStart(settings) },
                enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text(if (split) "Xuất ${parts.size} video" else "Xuất", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@Composable
private fun SplitOptions(
    total: Long, plan: SplitPlan, onPlan: (SplitPlan) -> Unit,
    movieMode: Boolean, onMovieMode: (Boolean) -> Unit,
    overlapMs: Long, onOverlap: (Long) -> Unit,
    parts: List<SplitPart>, leftover: Long,
) {
    Section("Cách chia (dự án dài ${formatClock(total)})")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        SplitMode.entries.forEach { m -> Option(m.label, plan.mode == m) { onPlan(plan.copy(mode = m)) } }
    }
    when (plan.mode) {
        SplitMode.EVEN -> {
            CountRow("Số video", plan.count) { onPlan(plan.copy(count = it)) }
            Text("Mỗi video dài khoảng ${formatClock(total / plan.count.coerceAtLeast(1))}", color = TextDim, fontSize = 12.sp)
        }
        SplitMode.FIXED -> {
            Text("Mỗi video dài", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(10, 15, 30, 60, 90, 120).forEach { min ->
                    val ms = min * 60_000L
                    Option(if (min < 60) "$min phút" else formatDuration(ms), plan.fixedMs == ms) { onPlan(plan.copy(fixedMs = ms)) }
                }
            }
            DurationInput(plan.fixedMs, key = "fixed") { onPlan(plan.copy(fixedMs = it)) }
            if (parts.isNotEmpty()) {
                val last = SplitExport.cuts(total, plan).last().let { it.second - it.first }
                val full = parts.size - 1
                Text(
                    if (full > 0 && last != plan.fixedMs) "→ $full video ${formatClock(plan.fixedMs)} và 1 video ${formatClock(last)}" else "→ ${parts.size} video",
                    color = TextDim, fontSize = 12.sp,
                )
            }
        }
        SplitMode.CUSTOM -> {
            CountRow("Số video", plan.customMs.size) { n ->
                // Keep what was typed; new videos share what is left.
                val kept = plan.customMs.take(n)
                val rest = (total - kept.sum()).coerceAtLeast(0)
                val add = n - kept.size
                val list = if (add > 0 && rest < add * 1000L) List(n) { total / n }
                else kept + List(add.coerceAtLeast(0)) { rest / add.coerceAtLeast(1) }
                onPlan(plan.copy(customMs = list))
            }
            plan.customMs.forEachIndexed { i, ms ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Video ${i + 1}", color = Color.White, modifier = Modifier.width(64.dp))
                    DurationInput(ms, key = "custom$i") { v -> onPlan(plan.copy(customMs = plan.customMs.toMutableList().also { it[i] = v })) }
                }
            }
            when {
                leftover < 0 -> Text("Tổng dài hơn dự án ${formatClock(-leftover)}. Hãy giảm bớt.", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                leftover > 0 -> {
                    Text("Còn ${formatClock(leftover)} ở cuối dự án sẽ không được xuất.", color = Color(0xFFFFC857), fontSize = 12.sp)
                    TextButton(onClick = {
                        val list = plan.customMs.toMutableList()
                        if (list.isNotEmpty()) list[list.lastIndex] = list.last() + leftover
                        onPlan(plan.copy(customMs = list))
                    }) { Text("Video cuối lấy hết phần còn lại") }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Xuất phim", color = Color.White)
            Text("Mỗi video sau mở đầu bằng một đoạn ngắn cuối video trước", color = TextDim, fontSize = 12.sp)
        }
        Switch(checked = movieMode, onCheckedChange = onMovieMode, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
    if (movieMode) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(3, 5, 10, 15, 30, 60).forEach { sec ->
                Option("$sec giây", overlapMs == sec * 1000L) { onOverlap(sec * 1000L) }
            }
        }
    }

    if (parts.isNotEmpty()) {
        Section("Các video sẽ xuất")
        parts.take(MAX_LISTED).forEach { p ->
            Text(
                "${p.index + 1}. ${formatClock(p.startMs)} → ${formatClock(p.endMs)}  (${formatClock(p.durationMs)})",
                color = Color.White, fontSize = 13.sp,
            )
        }
        if (parts.size > MAX_LISTED) Text("… và ${parts.size - MAX_LISTED} video nữa", color = TextDim, fontSize = 12.sp)
    }
}

private const val MAX_LISTED = 12

@Composable
private fun CountRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }) { Text("−", fontSize = 20.sp) }
        Text("$value", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(SplitExport.MAX_PARTS)) }) { Text("+", fontSize = 20.sp) }
    }
}

/** Hours / minutes / seconds boxes for a length. */
@Composable
private fun DurationInput(ms: Long, key: String, onChange: (Long) -> Unit) {
    val sec = ms / 1000
    var hText by remember(key) { mutableStateOf((sec / 3600).toString()) }
    var mText by remember(key) { mutableStateOf(((sec % 3600) / 60).toString()) }
    var sText by remember(key) { mutableStateOf((sec % 60).toString()) }
    fun parse(hh: String, mm: String, ss: String) =
        ((hh.toLongOrNull() ?: 0) * 3600 + (mm.toLongOrNull() ?: 0) * 60 + (ss.toLongOrNull() ?: 0)) * 1000
    fun push(hh: String, mm: String, ss: String) = onChange(parse(hh, mm, ss))
    // Changed from outside (a quick-pick chip, "lấy hết phần còn lại"): show the new value.
    LaunchedEffect(ms) {
        if (parse(hText, mText, sText) / 1000 != sec) {
            hText = (sec / 3600).toString(); mText = ((sec % 3600) / 60).toString(); sText = (sec % 60).toString()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberBox(hText, "giờ") { hText = it; push(it, mText, sText) }
        NumberBox(mText, "phút") { mText = it; push(hText, it, sText) }
        NumberBox(sText, "giây") { sText = it; push(hText, mText, it) }
    }
}

@Composable
private fun NumberBox(value: String, unit: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        modifier = Modifier.width(58.dp).padding(end = 2.dp),
    )
    Text(unit, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
}

@Composable
private fun Section(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Accent, selectedLabelColor = Color.Black,
            containerColor = Surface2, labelColor = Color.White,
        ),
        border = null,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
fun ExportProgress(vm: EditorViewModel) {
    val context = LocalContext.current
    when (val s = vm.exportState) {
        Exporter.State.Idle -> Unit
        is Exporter.State.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Đang xuất… ${s.progress}%") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.progress / 100f },
                        color = Accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        s.etaMs?.let { "Còn khoảng ${formatDuration(it)}" } ?: "Đang tính thời gian còn lại…",
                        color = Color.White,
                    )
                    Text("${s.width} × ${s.height} · ${formatBitrate(s.bitrate)}", color = TextDim, fontSize = 12.sp)
                    Text("Giữ ứng dụng mở cho đến khi xong.", color = TextDim, fontSize = 12.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancelExport() }) { Text("Hủy xuất") } },
        )
        is Exporter.State.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Đã xuất xong") },
            text = {
                Column {
                    Text("Đã lưu vào thư viện:", color = Color.White)
                    Text(s.path, color = Accent, fontSize = 13.sp)
                    Text("Dung lượng: ${formatBytes(s.sizeBytes)}", color = TextDim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, s.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(share, "Chia sẻ video")) }
                }) { Text("Chia sẻ") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(s.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(view) }
                    }) { Text("Xem") }
                    TextButton(onClick = { vm.dismissExport() }) { Text("Đóng") }
                }
            },
        )
        is Exporter.State.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("Không xuất được") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissExport() }) { Text("Đóng") } },
        )
    }
}

/** Progress and result of "Chia nhiều video". */
@Composable
fun SplitExportProgress(vm: EditorViewModel) {
    val context = LocalContext.current
    when (val s = vm.splitState) {
        EditorViewModel.SplitState.Idle -> Unit
        is EditorViewModel.SplitState.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Đang xuất video ${s.part + 1}/${s.parts}… ${s.percent}%") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.percent / 100f }, color = Accent, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    val overall = if (s.parts > 0) (s.part * 100 + s.percent) / s.parts else 0
                    LinearProgressIndicator(progress = { overall / 100f }, color = Color.White, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(s.etaPartMs?.let { "Video này: còn khoảng ${formatDuration(it)}" } ?: "Đang tính thời gian còn lại…", color = Color.White)
                    s.etaTotalMs?.let { Text("Tất cả ${s.parts} video: còn khoảng ${formatDuration(it)} (xong lúc ${clockAt(it)})", color = Color.White) }
                    if (s.width > 0) Text("${s.width} × ${s.height}", color = TextDim, fontSize = 12.sp)
                    Text(
                        "Chạy nền: có thể tắt màn hình hoặc ra ngoài ứng dụng, theo dõi ở thanh thông báo. Video nào xong được lưu ngay vào Movies/CutViet.",
                        color = TextDim, fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancelSplit() }) { Text("Dừng xuất") } },
        )
        is EditorViewModel.SplitState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissSplit() },
            title = { Text("Đã xuất xong ${s.saved.size} video") },
            text = { SavedList(s.saved) },
            confirmButton = {
                TextButton(onClick = {
                    val uris = ArrayList(s.saved.sortedBy { it.index }.map { android.net.Uri.parse(it.uri) })
                    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "video/mp4"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(share, "Chia sẻ video")) }
                }) { Text("Chia sẻ") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissSplit() }) { Text("Đóng") } },
        )
        is EditorViewModel.SplitState.Stopped -> AlertDialog(
            onDismissRequest = { vm.dismissSplit(keepForResume = s.canResume) },
            title = { Text("Đã dừng: xong ${s.saved.size}/${s.parts} video") },
            text = {
                Column {
                    Text(s.message, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    SavedList(s.saved)
                    if (s.canResume) {
                        Text("Bấm \"Xuất tiếp\" để xuất nốt từ video ${(s.saved.maxOfOrNull { it.index } ?: -1) + 2}, không xuất lại các video đã xong.", color = TextDim, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                if (s.canResume) TextButton(onClick = { vm.resumeSplit() }) { Text("Xuất tiếp") }
            },
            dismissButton = {
                Row {
                    if (s.canResume) TextButton(onClick = { vm.dismissSplit() }) { Text("Bỏ") }
                    TextButton(onClick = { vm.dismissSplit(keepForResume = s.canResume) }) { Text("Đóng") }
                }
            },
        )
    }
}

@Composable
private fun SavedList(saved: List<com.cutviet.editor.media.SplitExportWorker.Saved>) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (saved.isNotEmpty()) Text("Đã lưu vào thư viện:", color = Color.White)
        saved.sortedBy { it.index }.forEach {
            Text("${it.path.substringAfterLast('/')} · ${formatBytes(it.sizeBytes)}", color = Accent, fontSize = 12.sp)
        }
    }
}

/** Clock time [fromNowMs] from now, e.g. "21:40" (or "21:40 ngày mai"). */
private fun clockAt(fromNowMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val at = (now.clone() as java.util.Calendar).apply { timeInMillis += fromNowMs }
    val hm = String.format(java.util.Locale.US, "%02d:%02d", at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE))
    return if (at.get(java.util.Calendar.DAY_OF_YEAR) != now.get(java.util.Calendar.DAY_OF_YEAR)) "$hm ngày mai" else hm
}
