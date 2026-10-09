package com.cutviet.editor.ui.home

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutviet.editor.media.MediaProbe
import com.cutviet.editor.media.Thumbnails
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectStore
import com.cutviet.editor.ui.formatShort
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.AccentPink
import com.cutviet.editor.ui.theme.Surface2
import com.cutviet.editor.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    sharedUris: List<Uri>,
    onSharedConsumed: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    suspend fun reload() {
        projects = withContext(Dispatchers.IO) { store.list() }
    }

    fun create(uris: List<Uri>) {
        if (uris.isEmpty()) return
        creating = true
        scope.launch {
            val clips = withContext(Dispatchers.IO) {
                uris.mapNotNull { MediaProbe.persist(context, it); MediaProbe.probeClip(context, it) }
            }
            if (clips.isEmpty()) {
                creating = false
                Toast.makeText(context, "Không đọc được tệp đã chọn", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val name = "Dự án " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
            val p = Project(name = name, clips = clips)
            withContext(Dispatchers.IO) { store.save(p) }
            creating = false
            onOpen(p.id)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { create(it) }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            onSharedConsumed()
            create(sharedUris)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text(
                        "CutViet",
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    Text("Chỉnh sửa video, xuất chất lượng gốc", color = TextDim, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    NewProjectCard {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Bản nháp", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    val list = projects
                    if (list != null && list.isEmpty()) {
                        Text(
                            "Chưa có dự án nào. Nhấn \"Dự án mới\" để chọn video hoặc ảnh.",
                            color = TextDim, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            items(projects.orEmpty(), key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    onClick = { onOpen(p.id) },
                    onRename = { renaming = p },
                    onDelete = { deleting = p },
                )
            }
        }

        if (creating) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Đang đọc tệp…", color = Color.White)
                }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Đổi tên dự án") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val updated = p.copy(name = name.ifBlank { p.name })
                    renaming = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.save(updated) }
                        reload()
                    }
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Hủy") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Xóa dự án?") },
            text = { Text("\"${p.name}\" sẽ bị xóa khỏi bản nháp. Video gốc trong thư viện không bị ảnh hưởng.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(p.id) }
                        reload()
                    }
                }) { Text("Xóa", color = AccentPink) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Hủy") } },
        )
    }
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF0B3D38), Color(0xFF3A0F22))),
                RoundedCornerShape(16.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, null, tint = Color.Black, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Dự án mới", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Chọn video và ảnh từ thư viện", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val first = project.clips.firstOrNull()
    val thumb by produceState<Bitmap?>(null, first?.uri, first?.trimStartMs) {
        value = first?.let { Thumbnails.get(context, it.uri, it.isImage, it.trimStartMs, 320) }
    }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            val b = thumb
            if (b != null) {
                Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Movie, null, tint = TextDim, modifier = Modifier.size(40.dp))
            }
            Text(
                formatShort(project.durationMs),
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Đổi tên") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Xóa") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Text(
            project.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${project.clips.size} đoạn · " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(project.updatedAt)),
            color = TextDim, fontSize = 12.sp,
        )
    }
}
