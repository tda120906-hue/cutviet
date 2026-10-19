package com.cutviet.editor.ui.editor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cutviet.editor.media.Exporter
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.ui.formatTime
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // The editor gets its own ViewModelStore so its player is released as soon as the screen closes.
    val owner = remember(projectId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm: EditorViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = EditorViewModel.Factory(context.applicationContext as Application, projectId),
    )
    EditorContent(vm, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(vm: EditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var showExport by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) {
        vm.addMedia(it)
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addAudio(uri)
    }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
        vm.addOverlays(it)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showExport = true
        else Toast.makeText(context, "Cần quyền bộ nhớ để lưu video", Toast.LENGTH_LONG).show()
    }
    fun addMedia() = mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun addMusic() = musicPicker.launch(arrayOf("audio/*", "video/*"))
    fun addOverlay() = overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun openExport() {
        if (vm.project.clips.isEmpty()) {
            Toast.makeText(context, "Dự án chưa có đoạn nào", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            showExport = true
        }
    }

    vm.toast?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.toast = null
        }
    }
    val view = LocalView.current
    val exporting = vm.exportState is Exporter.State.Running
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    // While the keyboard is open (typing a text or a voice-over) the preview, timeline and panel
    // shrink so everything still fits above the keyboard.
    val typing = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(vm, onBack, onExport = { openExport() })

        // Preview.
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val player = vm.preview.player.value
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { if (it.player !== player) it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            OverlayPreview(vm, Modifier.fillMaxSize())
            OverlayGizmo(vm, Modifier.fillMaxSize())
            if (vm.project.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Dự án trống", color = TextDim)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { addMedia() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
                        Text("Thêm video / ảnh")
                    }
                }
            }
            vm.preview.error.value?.let { msg ->
                Text(
                    msg, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Color(0xCC333340), RoundedCornerShape(6.dp))
                        .clickable { vm.preview.error.value = null }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (vm.busy) CircularProgressIndicator(color = Accent)
        }

        // Transport.
        if (!typing) Row(
            Modifier.fillMaxWidth().background(Color(0xFF0E0E11)).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatTime(vm.playheadMs)} / ${formatTime(vm.project.durationMs)}",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            val playing = vm.preview.isPlaying.value
            IconButton(onClick = { if (playing) vm.preview.pause() else vm.preview.play() }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Phát", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
        }

        Timeline(
            vm, onAddMusic = { addMusic() }, onAddMedia = { addMedia() }, onAddOverlay = { addOverlay() },
            modifier = if (typing) Modifier.heightIn(max = 130.dp) else Modifier,
        )

        val panel = vm.panel
        if (panel != null) {
            PanelHost(vm, panel, Modifier.height(if (typing) 220.dp else 290.dp))
        } else {
            vm.dubStatus?.let { DubbingBanner(it, onOpen = { vm.showDubbing = true }) }
            Toolbar(vm, onAddMedia = { addMedia() }, onAddMusic = { addMusic() }, onAddOverlay = { addOverlay() })
        }
    }

    if (showExport) {
        ExportDialog(vm, onDismiss = { showExport = false }, onStart = { settings ->
            showExport = false
            vm.export(settings)
        })
    }
    ExportProgress(vm)
    vm.cropClipId?.let { id ->
        vm.project.clips.firstOrNull { it.id == id }?.let { CropDialog(vm, it) }
    }
    vm.eraserOverlayId?.let { id ->
        vm.project.overlays.firstOrNull { it.id == id }?.let { EraserDialog(vm, it) }
    }
    if (vm.showDubbing) DubbingDialog(vm, onDismiss = { vm.showDubbing = false })
}

@Composable
private fun DubbingBanner(status: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2A1F3A)).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Translate, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(status, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TopBar(vm: EditorViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) }
        Text(
            vm.project.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, "Hoàn tác", tint = if (vm.canUndo) Color.White else Color.DarkGray)
        }
        IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, "Làm lại", tint = if (vm.canRedo) Color.White else Color.DarkGray)
        }
        Button(
            onClick = onExport,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(end = 8.dp),
        ) { Text("Xuất", fontWeight = FontWeight.Bold) }
    }
}

private data class Tool(val label: String, val icon: ImageVector, val danger: Boolean = false, val action: () -> Unit)

@Composable
private fun Toolbar(vm: EditorViewModel, onAddMedia: () -> Unit, onAddMusic: () -> Unit, onAddOverlay: () -> Unit) {
    val clip = vm.selectedClip
    // Opens a clip tool on the selected clip, or on the clip under the playhead.
    fun clipTool(p: Panel) {
        if (vm.selected == null) {
            val i = vm.project.clipAt(vm.playheadMs)?.first ?: return
            vm.select(i)
        }
        vm.openPanel(p)
    }
    val tools = when {
        vm.selectedOverlay != null -> listOf(
            Tool("Chỉnh lớp phủ", Icons.Filled.Tune) { vm.openPanel(Panel.OVERLAY) },
            Tool("Tẩy một phần", Icons.Filled.Brush) { vm.openEraser() },
            Tool("Mặt nạ", Icons.Filled.Category) { vm.openPanel(Panel.OVERLAY_MASK) },
            Tool("Tẩy nền", Icons.Filled.AutoFixHigh) { vm.openPanel(Panel.OVERLAY_KEY) },
            Tool("Lên trên", Icons.Filled.FlipToFront) { vm.restackSelectedOverlay(1) },
            Tool("Xuống dưới", Icons.Filled.FlipToBack) { vm.restackSelectedOverlay(-1) },
            Tool("Thêm lớp phủ", Icons.Filled.Layers) { onAddOverlay() },
            Tool("Đến đầu phát", Icons.Filled.VerticalAlignCenter) { vm.moveOverlayToPlayhead() },
            Tool("Nhân bản", Icons.Filled.ContentCopy) { vm.duplicateSelectedOverlay() },
            Tool("Xóa lớp phủ", Icons.Filled.Delete, danger = true) { vm.deleteSelectedOverlay() },
        )
        vm.selectedAudio != null -> buildList {
            add(Tool("Chỉnh âm thanh", Icons.Filled.GraphicEq) { vm.openPanel(Panel.AUDIO) })
            if (vm.selectedAudio?.kind == AudioKind.VOICE) add(Tool("Sửa giọng đọc", Icons.Filled.RecordVoiceOver) { vm.openPanel(Panel.VOICE) })
            add(Tool("Đến đầu phát", Icons.Filled.VerticalAlignCenter) { vm.updateSelectedAudio { it.copy(startMs = vm.playheadMs) } })
            add(Tool("Xóa", Icons.Filled.Delete, danger = true) { vm.deleteSelectedAudio() })
        }
        clip != null -> buildList {
            add(Tool("Tách", Icons.Filled.VerticalSplit) { vm.splitAtPlayhead() })
            add(Tool("Lớp phủ", Icons.Filled.Layers) { onAddOverlay() })
            if (clip.isImage) add(Tool("Thời lượng", Icons.Filled.Timer) { vm.openPanel(Panel.DURATION) })
            else {
                add(Tool("Cắt", Icons.Filled.ContentCut) { vm.openPanel(Panel.TRIM) })
                add(Tool("Tốc độ", Icons.Filled.Speed) { vm.openPanel(Panel.SPEED) })
                add(Tool("Âm lượng", Icons.AutoMirrored.Filled.VolumeUp) { vm.openPanel(Panel.VOLUME) })
                add(Tool("Tách âm thanh", Icons.AutoMirrored.Filled.CallSplit) { vm.extractAudioFromSelected() })
                add(Tool("Lồng tiếng AI", Icons.Filled.Translate) { vm.showDubbing = true })
            }
            add(Tool("Bộ lọc", Icons.Filled.AutoAwesome) { vm.openPanel(Panel.FILTER) })
            add(Tool("Điều chỉnh", Icons.Filled.Tune) { vm.openPanel(Panel.ADJUST) })
            add(Tool("Văn bản", Icons.Filled.Title) { vm.openPanel(Panel.TEXT) })
            add(Tool("Nhãn dán", Icons.Filled.EmojiEmotions) { vm.openPanel(Panel.STICKER) })
            add(Tool("Cắt khung", Icons.Filled.Crop) { vm.openCrop() })
            add(Tool("Xoay/Lật", Icons.Filled.Flip) { vm.openPanel(Panel.TRANSFORM) })
            add(Tool("Nhân bản", Icons.Filled.ContentCopy) { vm.duplicateSelected() })
            add(Tool("Sang trái", Icons.Filled.ChevronLeft) { vm.moveSelected(-1) })
            add(Tool("Sang phải", Icons.Filled.ChevronRight) { vm.moveSelected(1) })
            add(Tool("Xóa", Icons.Filled.Delete, danger = true) { vm.deleteSelected() })
        }
        else -> listOf(
            Tool("Thêm", Icons.Filled.AddPhotoAlternate) { onAddMedia() },
            Tool("Tách", Icons.Filled.VerticalSplit) { vm.splitAtPlayhead() },
            Tool("Âm thanh", Icons.Filled.MusicNote) { onAddMusic() },
            Tool("Lớp phủ", Icons.Filled.Layers) { onAddOverlay() },
            Tool("Giọng đọc AI", Icons.Filled.RecordVoiceOver) { vm.openPanel(Panel.VOICE) },
            Tool("Lồng tiếng AI", Icons.Filled.Translate) { vm.showDubbing = true },
            Tool("Văn bản", Icons.Filled.Title) { clipTool(Panel.TEXT) },
            Tool("Nhãn dán", Icons.Filled.EmojiEmotions) { clipTool(Panel.STICKER) },
            Tool("Bộ lọc", Icons.Filled.AutoAwesome) { clipTool(Panel.FILTER) },
            Tool("Điều chỉnh", Icons.Filled.Tune) { clipTool(Panel.ADJUST) },
            Tool("Tỉ lệ", Icons.Filled.AspectRatio) { vm.openPanel(Panel.CANVAS) },
        )
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF141418))) {
        if (clip != null) {
            Text(
                "Đoạn ${(vm.selected ?: 0) + 1}/${vm.project.clips.size} · nhấn lại để bỏ chọn",
                color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tools, key = { it.label }) { t ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(min = 68.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = t.action)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Icon(t.icon, t.label, tint = if (t.danger) Color(0xFFFF6B6B) else Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, color = if (t.danger) Color(0xFFFF6B6B) else Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
