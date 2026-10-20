package com.cutviet.editor.ui.editor

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cutviet.editor.media.dubbing.DubLog
import com.cutviet.editor.media.dubbing.DubMode
import com.cutviet.editor.media.dubbing.OnlineEngine
import com.cutviet.editor.media.dubbing.DubModel
import com.cutviet.editor.media.dubbing.DubSettings
import com.cutviet.editor.media.dubbing.ModelDownloadWorker
import com.cutviet.editor.media.dubbing.ModelStore
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.ui.formatBytes
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Dịch lồng tiếng": start a dub of the selected clip, and manage models and keys. */
@Composable
fun DubbingDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DubSettings(context) }
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Dịch lồng tiếng", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDismiss) { Text("Đóng", color = Accent) }
            }
            TabRow(selectedTabIndex = tab, containerColor = Color(0xFF101014), contentColor = Color.White) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Lồng tiếng") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Model & khoá") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Nhật ký") })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (tab) {
                    0 -> DubTab(vm, settings, onOpenModels = { tab = 1 }, onStarted = onDismiss)
                    1 -> ModelsTab(settings)
                    else -> LogTab()
                }
            }
        }
    }
}

@Composable
private fun DubTab(vm: EditorViewModel, settings: DubSettings, onOpenModels: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(SourceLanguage.ENGLISH) }
    var mode by remember { mutableStateOf(settings.mode) }
    var engine by remember { mutableStateOf(settings.onlineEngine) }
    var distinguish by remember { mutableStateOf(settings.distinguishSpeakers) }
    var original by remember { mutableFloatStateOf(settings.originalVolume) }
    val voices = vm.voice.voices.value
    var voiceName by remember { mutableStateOf(voices.firstOrNull { !it.needsNetwork }?.name ?: voices.firstOrNull()?.name) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val index = vm.selected ?: vm.project.clipAt(vm.playheadMs)?.first
    val clip = index?.let { vm.project.clips.getOrNull(it) }

    Label("Đoạn được lồng tiếng")
    Text(
        if (clip == null) "Chọn một đoạn video trên timeline trước." else "Đoạn ${index + 1} · ${com.cutviet.editor.ui.formatTime(clip.durationMs)}",
        color = if (clip == null) Color(0xFFFF6B6B) else Color.White, fontSize = 14.sp,
    )

    Label("Ngôn ngữ trong video")
    Chips(SourceLanguage.entries, language, { it.label }) { language = it }

    Label("Cách dịch")
    Chips(DubMode.entries, mode, { it.label }) { mode = it; settings.mode = it }
    Text(
        when (mode) {
            DubMode.AUTO -> "Online (Gemini) khi có Wi-Fi hoặc khi chưa có model offline; lỗi mạng hay hết hạn mức thì tự chuyển offline."
            DubMode.ONLINE_FIRST -> "Luôn thử Gemini trước khi có mạng; lỗi thì chuyển offline."
            DubMode.OFFLINE -> "Chỉ dùng Whisper + Gemma trên máy, chạy được cả khi bật chế độ máy bay."
        },
        color = TextDim, fontSize = 12.sp,
    )

    if (mode != DubMode.OFFLINE) {
        Label("Kiểu lồng tiếng online")
        Chips(OnlineEngine.entries, engine, { it.label }) { engine = it; settings.onlineEngine = it }
        Text(
            when (engine) {
                OnlineEngine.NATURAL -> "Gemini 3.8 nghe cả đoạn, nhận ra từng người nói (nam, nữ, trẻ em) và giọng điệu của họ, dịch cho vừa thời gian, rồi đọc bằng giọng tự nhiên. Dùng lượt miễn phí trong ngày của khoá; hết lượt thì các câu còn lại đọc bằng giọng của máy."
                OnlineEngine.LIVE -> "Gemini Live Translate phiên dịch theo thời gian thực bằng một giọng chung. Nhanh, nhưng giọng đều đều và không phân biệt người nói."
            },
            color = TextDim, fontSize = 12.sp,
        )
    }

    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Phân biệt giọng từng người", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (mode != DubMode.OFFLINE && engine == OnlineEngine.NATURAL) {
                    "Mỗi người một giọng riêng đúng giới tính. Offline chỉ phân biệt được nam, nữ và trẻ em theo độ cao của giọng."
                } else {
                    "Offline: nam, nữ và trẻ em đọc ở độ cao giọng khác nhau, đoán theo giọng gốc."
                },
                color = TextDim, fontSize = 12.sp,
            )
        }
        Switch(checked = distinguish, onCheckedChange = { distinguish = it; settings.distinguishSpeakers = it })
    }

    Label("Giọng tiếng Việt")
    if (voices.isEmpty()) {
        Text("Máy chưa có giọng đọc tiếng Việt. Cài Google Text-to-Speech và tải giọng tiếng Việt.", color = Color(0xFFFF6B6B), fontSize = 13.sp)
    } else {
        Chips(voices, voices.firstOrNull { it.name == voiceName }, { it.label + if (it.needsNetwork) " (mạng)" else "" }) { voiceName = it.name }
        if (mode != DubMode.OFFLINE && engine == OnlineEngine.NATURAL) {
            Text("Dùng khi chạy offline, hoặc cho các câu Gemini không đọc được.", color = TextDim, fontSize = 12.sp)
        }
    }

    Label("Giọng gốc: ${if (original == 0f) "xoá hẳn" else "${(original * 100).toInt()}%"}")
    Slider(value = original, onValueChange = { original = it }, onValueChangeFinished = { settings.originalVolume = original }, valueRange = 0f..0.5f)
    Text("Âm thanh nền (tạp âm, tiếng đồ vật, nhạc) luôn được giữ nguyên khi đã tải model tách giọng.", color = TextDim, fontSize = 12.sp)

    Spacer(Modifier.height(12.dp))
    val missing = missingModels(context, mode, settings)
    if (missing.isNotEmpty()) {
        Text("Còn thiếu: " + missing.joinToString { it.label }, color = Color(0xFFFFC46B), fontSize = 13.sp)
        TextButton(onClick = onOpenModels) { Text("Tải model", color = Accent) }
    }

    vm.dubStatus?.let { status ->
        Spacer(Modifier.height(8.dp))
        Text(status, color = Color.White, fontSize = 14.sp)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Accent)
        OutlinedButton(onClick = { vm.cancelDubbing() }) { Text("Huỷ lồng tiếng") }
    } ?: Button(
        onClick = {
            if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            settings.originalVolume = original
            vm.startDubbing(language, voiceName, original)
            onStarted()
        },
        enabled = clip != null && voices.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Bắt đầu lồng tiếng") }
    Text("Chạy nền kể cả khi tắt màn hình; xem tiến trình trên thanh thông báo.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

private fun missingModels(context: Context, mode: DubMode, s: DubSettings): List<DubModel> = buildList {
    if (!DubModel.MDX_VOC_FT.isReady(context)) add(DubModel.MDX_VOC_FT)
    val offlineNeeded = mode == DubMode.OFFLINE || s.geminiKey.isBlank()
    if (offlineNeeded) {
        if (!DubModel.WHISPER_LARGE_V3.isReady(context)) add(DubModel.WHISPER_LARGE_V3)
        if (!DubModel.GEMMA_3N_E4B.isReady(context)) add(DubModel.GEMMA_3N_E4B)
    }
}

@Composable
private fun ModelsTab(settings: DubSettings) {
    val context = LocalContext.current
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var hf by remember { mutableStateOf(settings.hfToken) }
    var key by remember { mutableStateOf(settings.geminiKey) }
    var model by remember { mutableStateOf(settings.geminiModel) }
    var textModel by remember { mutableStateOf(settings.geminiTextModel) }
    var ttsModel by remember { mutableStateOf(settings.geminiTtsModel) }
    var simulate by remember { mutableStateOf(settings.simulateQuota) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Chỉ tải qua Wi-Fi", color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; settings.wifiOnly = it })
    }
    DubModel.entries.forEach { m ->
        ModelRow(m, wifiOnly, onDownload = { if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) })
    }

    Label("Token Hugging Face (để tải Gemma)")
    Text("Đăng nhập huggingface.co, mở trang google/gemma-3n-E4B-it-litert-preview, bấm đồng ý giấy phép, rồi tạo token loại Read và dán vào đây.", color = TextDim, fontSize = 12.sp)
    Secret(hf) { hf = it; settings.hfToken = it }

    Label("Khoá API Gemini (nhánh online)")
    Text("Khoá chỉ lưu trong máy này, không nằm trong APK và không vào bản sao lưu.", color = TextDim, fontSize = 12.sp)
    Secret(key) { key = it; settings.geminiKey = it }
    Label("Model nghe & dịch (kiểu Tự nhiên)")
    OutlinedTextField(value = textModel, onValueChange = { textModel = it; settings.geminiTextModel = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Label("Model đọc (kiểu Tự nhiên)")
    OutlinedTextField(value = ttsModel, onValueChange = { ttsModel = it; settings.geminiTtsModel = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Để trống là dùng model mặc định. Xem lượt miễn phí còn lại ở aistudio.google.com/rate-limit.", color = TextDim, fontSize = 12.sp)
    Label("Model Gemini Live (kiểu Phiên dịch trực tiếp)")
    OutlinedTextField(value = model, onValueChange = { model = it; settings.geminiModel = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Giả lập lỗi 429", color = Color.White)
            Text("Để thử: nhánh online báo hết hạn mức và tự chuyển sang offline.", color = TextDim, fontSize = 12.sp)
        }
        Switch(checked = simulate, onCheckedChange = { simulate = it; settings.simulateQuota = it })
    }
}

/** The on-phone dubbing log, to copy and send when something goes wrong. */
@Composable
private fun LogTab() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        text = withContext(Dispatchers.IO) {
            DubLog.recordExitReasons(context)
            DubLog.read(context)
        }
    }
    Text(
        "Ghi lại từng bước, thời gian, lý do chuyển offline và lý do app bị đóng. Khi có lỗi, bấm Sao chép rồi dán vào tin nhắn gửi Claude. Nhật ký không chứa khoá API.",
        color = TextDim, fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button(
            onClick = { text?.let { clipboard.setText(AnnotatedString(it.takeLast(60_000))) } },
            enabled = !text.isNullOrEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) { Text("Sao chép") }
        OutlinedButton(onClick = { reload++ }) { Text("Tải lại") }
        OutlinedButton(onClick = { DubLog.clear(context); reload++ }) { Text("Xoá") }
    }
    val shown = text
    when {
        shown == null -> Text("Đang đọc…", color = TextDim, fontSize = 13.sp)
        shown.isEmpty() -> Text("Chưa có gì.", color = TextDim, fontSize = 13.sp)
        // Newest at the top; the full log goes to the clipboard.
        else -> Text(
            // Entries start with a time stamp; their continuation lines stay with them.
            shown.trim().split(Regex("\n(?=\\d{2}/\\d{2} \\d{2}:)")).takeLast(200).reversed().joinToString("\n"),
            color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
        )
    }
}

@Composable
private fun ModelRow(m: DubModel, wifiOnly: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val infos by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelStore.workName(m)).collectAsState(initial = emptyList())
    val work = infos.lastOrNull()
    var importing by remember { mutableStateOf<Float?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val ready = remember(refresh, work?.state) { m.isReady(context) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = 0f
            val ok = withContext(Dispatchers.IO) { importModel(context, uri, m) { importing = it } }
            importing = null
            refresh++
            if (!ok) android.widget.Toast.makeText(context, "Không chép được tệp model", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(Surface2).padding(12.dp)) {
        Text(m.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("Khoảng ${formatBytes(m.approxBytes)}", color = TextDim, fontSize = 12.sp)
        val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
        when {
            ready -> Text("Đã có trên máy", color = Color(0xFF6BD68B), fontSize = 13.sp)
            importing != null -> {
                Text("Đang chép tệp…", color = Color.White, fontSize = 13.sp)
                LinearProgressIndicator(progress = { importing ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
            }
            running -> {
                val p = work?.progress?.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f) ?: 0f
                Text(if (work?.state == WorkInfo.State.ENQUEUED) "Đang chờ mạng…" else "Đang tải ${(p * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
            }
            work?.state == WorkInfo.State.FAILED -> Text(work.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Tải thất bại", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (ready) {
                OutlinedButton(onClick = { ModelStore.delete(context, m); refresh++ }) { Text("Xoá") }
            } else if (running) {
                OutlinedButton(onClick = { ModelStore.cancel(context, m) }) { Text("Huỷ") }
            } else {
                Button(onClick = { onDownload(); ModelStore.download(context, m, wifiOnly) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Tải về") }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Chọn tệp") }
            }
        }
    }
}

/** Copies a model file the owner downloaded themselves into the app's model folder. */
private fun importModel(context: Context, uri: Uri, m: DubModel, progress: (Float) -> Unit): Boolean = runCatching {
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    val tmp = File(ModelStore.dir(context), m.path + ".import")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (size > 0) progress(done.toFloat() / size)
            }
        }
    }
    if (tmp.length() < m.approxBytes / 2) { tmp.delete(); return false }
    m.file(context).delete()
    tmp.renameTo(m.file(context))
}.getOrDefault(false)

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun <T> Chips(items: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(4).forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onPick(item) },
                label = { Text(label(item), maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, labelColor = Color.White),
            )
        }
    }
    if (items.size > 4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.drop(4).take(4).forEach { item ->
                FilterChip(
                    selected = item == selected,
                    onClick = { onPick(item) },
                    label = { Text(label(item), maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, labelColor = Color.White),
                )
            }
        }
    }
}

@Composable
private fun Secret(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.width(1.dp))
}
