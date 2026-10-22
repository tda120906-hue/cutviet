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
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.TextDim

@Composable
fun ExportDialog(vm: EditorViewModel, onDismiss: () -> Unit, onStart: (ExportSettings) -> Unit) {
    var settings by remember { mutableStateOf(ExportSettings()) }
    val (w, h) = vm.outputSize(settings)
    val bitrate = vm.estimatedBitrate(settings)
    val seconds = vm.project.durationMs / 1000.0
    val estBytes = ((bitrate + 192_000) * seconds / 8).toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xuất video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Text("Thời lượng: ${formatTime(vm.project.durationMs)} · ước tính ${formatBytes(estBytes)}", color = Color.White)
                Text(
                    "Khung hình/giây giữ nguyên như video gốc. \"Gốc\" giữ độ phân giải và bitrate tương đương bản gốc." +
                        if (settings.codec == ExportCodec.H265) " H.265 cho tệp nhỏ hơn nhưng một số máy cũ không phát được." else "",
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(settings) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Xuất", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
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
