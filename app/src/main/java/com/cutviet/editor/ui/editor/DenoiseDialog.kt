package com.cutviet.editor.ui.editor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cutviet.editor.media.denoise.DenoisePipeline
import com.cutviet.editor.media.dubbing.DubModel
import com.cutviet.editor.media.dubbing.DubSettings
import com.cutviet.editor.media.dubbing.ModelDownloadWorker
import com.cutviet.editor.media.dubbing.ModelStore
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim
import kotlinx.coroutines.delay

/** "Lọc tiếng ồn" for the selected clip: keep only the main voice, or every voice, without the noise. */
@Composable
fun DenoiseDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keep by remember { mutableStateOf(DenoisePipeline.Keep.MAIN) }
    var level by remember { mutableFloatStateOf(0f) }
    val needed = if (keep == DenoisePipeline.Keep.MAIN) listOf(DubModel.MDX_VOC_FT, DubModel.VOXLOCK_ENROLL, DubModel.VOXLOCK_CHUNK, DubModel.SPEAKER_PRINT)
    else listOf(DubModel.MDX_VOC_FT)
    // Re-check the model files every second while a download runs.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val ready = remember(tick, keep) { needed.all { it.isReady(context) } }
    val missing = remember(tick, keep) { needed.filterNot { it.isReady(context) } }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val running = vm.denoiseStatus
    val clip = vm.selected?.let { vm.project.clips.getOrNull(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lọc tiếng ồn") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                // Main buttons first.
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running != null) {
                        OutlinedButton(onClick = { vm.cancelDenoise() }) { Text("Huỷ lọc") }
                    } else {
                        Button(
                            enabled = ready && clip != null,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                vm.startDenoise(keep, level)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        ) { Text("Bắt đầu lọc") }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDismiss) { Text("Đóng") }
                }
                if (running != null) {
                    Text(running, color = Accent, fontSize = 13.sp)
                    Text("Chạy nền, bạn có thể tắt màn hình hoặc làm việc khác.", color = TextDim, fontSize = 12.sp)
                }
                if (clip == null) Text("Hãy chọn một đoạn video trên dòng thời gian trước.", color = Color(0xFFFFB74D), fontSize = 13.sp)

                Text("Giữ lại", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = keep == DenoisePipeline.Keep.MAIN, onClick = { keep = DenoisePipeline.Keep.MAIN }, label = { Text("Chỉ giọng chính") })
                    FilterChip(selected = keep == DenoisePipeline.Keep.ALL, onClick = { keep = DenoisePipeline.Keep.ALL }, label = { Text("Mọi giọng nói") })
                }
                Text(
                    if (keep == DenoisePipeline.Keep.MAIN) {
                        "Bỏ nhạc nền, tiếng ồn, tiếng cười chèn vào và cả giọng người khác, chỉ giữ người nói chính. " +
                            "App tự nhận giọng chính là người nói nhiều nhất trong đoạn (bỏ qua chỗ có tiếng cười)."
                    } else {
                        "Bỏ nhạc nền và tiếng ồn, giữ mọi giọng nói (hợp với video có nhiều người nói chuyện)."
                    },
                    color = TextDim, fontSize = 12.sp,
                )

                Text("Tiếng ồn còn lại: ${(level * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Slider(value = level, onValueChange = { level = (it * 20).toInt() / 20f }, valueRange = 0f..0.5f)
                Text(
                    "0% là bỏ hẳn. Phần đã lọc ra vẫn nằm ở một dòng âm thanh riêng, chỉnh âm lượng của nó bất cứ lúc nào.",
                    color = TextDim, fontSize = 12.sp,
                )

                if (missing.isNotEmpty()) {
                    val mb = missing.sumOf { it.approxBytes } / 1_000_000
                    Text("Cần tải model (một lần, khoảng $mb MB)", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    for (m in missing) ModelLine(m)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        val wifi = DubSettings(context).wifiOnly
                        missing.forEach { ModelStore.download(context, it, wifi) }
                    }) { Text("Tải model") }
                }
                Text(
                    "Mất khoảng 1–1,5 lần độ dài đoạn video. Âm thanh đã lọc giữ chất lượng gốc (44,1 kHz, stereo).",
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ModelLine(m: DubModel) {
    val context = LocalContext.current
    val infos by remember(m) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelStore.workName(m)) }
        .collectAsState(initial = emptyList())
    val info = infos.lastOrNull()
    val state = when {
        info == null -> "chưa tải"
        info.state == WorkInfo.State.RUNNING -> "đang tải ${(info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f) * 100).toInt()}%"
        info.state == WorkInfo.State.ENQUEUED -> "đang chờ mạng"
        info.state == WorkInfo.State.FAILED -> info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "tải lỗi, bấm Tải model để thử lại"
        else -> "chưa tải"
    }
    Text("• ${m.label}: $state", color = Color.White, fontSize = 12.sp)
}
