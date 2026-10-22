package com.cutviet.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cutviet.editor.media.dubbing.DubMode
import com.cutviet.editor.media.dubbing.DubSettings
import com.cutviet.editor.media.dubbing.RedubChoices
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.ui.formatTime
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim

/**
 * "Lồng lại câu này" for one DUB track: listen to its part of the video and translate it again,
 * or read the corrected text; either in the line's own voice or another person's.
 */
@Composable
fun RedubDialog(vm: EditorViewModel, trackId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val track = vm.project.audios.firstOrNull { it.id == trackId } ?: return
    val info = remember(trackId) { ProjectOps.dubOrigin(vm.project, track) } ?: return
    val choices = remember(trackId) { RedubChoices.voices(vm.project, track) }
    val settings = remember { DubSettings(context) }
    val canOnline = settings.mode != DubMode.OFFLINE && settings.geminiKey.isNotBlank()
    var text by remember(trackId) { mutableStateOf(info.text) }
    var choice by remember(trackId) { mutableStateOf(choices.first()) }
    var language by remember(trackId) {
        mutableStateOf(runCatching { SourceLanguage.valueOf(info.language) }.getOrNull()?.takeIf { it.dubbable } ?: SourceLanguage.ENGLISH)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lồng lại câu này", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDismiss) { Text("Đóng", color = Accent) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    "Câu ở ${formatTime(track.startMs)}, dài ${formatTime(info.sourceEndMs - info.sourceStartMs)} trong video" +
                        (info.speaker?.let { " · $it" } ?: ""),
                    color = TextDim, fontSize = 13.sp,
                )
                if (info.sourceText.isNotBlank()) {
                    Label("Lời gốc")
                    Text(info.sourceText, color = Color.White, fontSize = 14.sp)
                }

                Label("Lời tiếng Việt")
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                Label("Ngôn ngữ trong video")
                Chips(SourceLanguage.entries.filter { it.dubbable }, language, { it.label }) { language = it }

                Label("Giọng đọc")
                choices.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { choice = c }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = c == choice, onClick = { choice = c }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                        Text(c.label, color = Color.White, fontSize = 14.sp)
                    }
                }
                Text(
                    if (canOnline) "Giọng Gemini cần mạng; mất mạng hay hết lượt thì máy đọc thay."
                    else "Chưa dùng được Gemini (chưa có khoá hoặc đang để Offline) nên máy sẽ đọc, cao độ theo giọng đã chọn.",
                    color = TextDim, fontSize = 12.sp,
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.startRedub(trackId, null, choice, language) },
                    enabled = vm.redubStatus == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Nghe & dịch lại từ video") }
                Text(
                    "AI nghe lại đúng đoạn video của câu này và dịch lại theo ngữ cảnh các câu trước và sau, rồi đọc bằng giọng đã chọn.",
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedButton(
                    onClick = { vm.startRedub(trackId, text, choice, language) },
                    enabled = vm.redubStatus == null && text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Đọc lại lời đã sửa", color = Color.White) }
                Text(
                    "Giữ nguyên lời bạn sửa ở trên, chỉ tạo lại giọng (dùng khi dịch sai một chữ hay muốn đổi giọng).",
                    color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
                vm.redubStatus?.let {
                    Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { vm.cancelRedub() }) { Text("Huỷ", color = Accent) }
                }
            }
        }
    }
}
