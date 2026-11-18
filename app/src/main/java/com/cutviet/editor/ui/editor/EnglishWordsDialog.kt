package com.cutviet.editor.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.ui.theme.Accent
import com.cutviet.editor.ui.theme.TextDim
import com.cutviet.editor.media.tts.PiperTts
import com.cutviet.editor.media.tts.Pronunciation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The English words in [text] and how the voices read them, each one correctable in Vietnamese spelling. */
@Composable
fun EnglishWordsDialog(
    text: String,
    engine: VoiceEngine,
    voiceName: String?,
    pitch: Float,
    rate: Float,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val readings = produceState<List<Pronunciation.Reading>?>(null, text) {
        value = withContext(Dispatchers.Default) { PiperTts.get(context).englishReadings(text) }
    }.value
    // What the user typed in each box, by word key.
    val edits = remember { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = { engine.stop(); onDismiss() },
        title = { Text("Cách đọc từ tiếng Anh") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                // Main buttons first.
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = edits.isNotEmpty(),
                        onClick = {
                            for ((k, v) in edits) Pronunciation.setUserWord(context, k, v)
                            engine.stop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    ) { Text("Lưu cách đọc") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { engine.stop(); onDismiss() }) { Text("Đóng") }
                }
                Text(
                    "Mỗi từ được đọc theo cách viết tiếng Việt ở ô bên phải. Đọc chưa đúng thì sửa ô đó, ví dụ " +
                        "deadline → đét lai, rồi bấm loa để nghe thử. Cách đọc bạn lưu dùng cho mọi giọng đọc và lồng tiếng.",
                    color = TextDim, fontSize = 12.sp,
                )
                when {
                    readings == null -> Text("Đang tìm từ tiếng Anh…", color = TextDim, modifier = Modifier.padding(top = 8.dp))
                    readings.isEmpty() -> Text("Không có từ tiếng Anh nào trong lời đọc.", modifier = Modifier.padding(top = 8.dp))
                    else -> for (r in readings) {
                        val key = Pronunciation.key(r.word) ?: continue
                        val value = edits[key] ?: r.reading
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.word, Modifier.width(92.dp), fontSize = 14.sp, maxLines = 2)
                            OutlinedTextField(
                                value = value,
                                onValueChange = { edits[key] = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            IconButton(enabled = value.isNotBlank(), onClick = { engine.speak(value, voiceName, pitch, rate) }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Nghe")
                            }
                            if (r.source == Pronunciation.Source.USER && key !in edits) {
                                // Back to the app's own reading.
                                IconButton(onClick = { edits[key] = "" }) { Icon(Icons.Filled.Refresh, "Cách đọc gốc") }
                            }
                        }
                        if (key in edits && edits[key].isNullOrBlank()) {
                            Text("Sẽ đọc theo cách mặc định của app", color = TextDim, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
