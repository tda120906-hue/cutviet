package com.cutviet.editor.ui.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cutviet.editor.media.Exporter
import com.cutviet.editor.media.MediaProbe
import com.cutviet.editor.media.PreviewController
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.ExportSettings
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps
import com.cutviet.editor.model.ProjectStore
import com.cutviet.editor.model.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Panel(val title: String) {
    TRIM("Cắt đoạn"),
    SPEED("Tốc độ"),
    VOLUME("Âm lượng"),
    TRANSFORM("Xoay & lật"),
    CROP("Cắt khung"),
    FILTER("Bộ lọc"),
    ADJUST("Điều chỉnh"),
    TEXT("Văn bản"),
    STICKER("Nhãn dán"),
    MUSIC("Âm nhạc"),
    CANVAS("Tỉ lệ khung hình"),
    DURATION("Thời lượng ảnh"),
}

class EditorViewModel(app: Application, projectId: String) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    val preview = PreviewController(app)
    private val exporter = Exporter(app)

    var project by mutableStateOf(store.load(projectId) ?: Project(id = projectId, name = "Dự án"))
        private set
    var selected by mutableStateOf<Int?>(null)
    var musicSelected by mutableStateOf(false)
    var panel by mutableStateOf<Panel?>(null)
        private set
    var editingTextId by mutableStateOf<String?>(null)
    var exportState by mutableStateOf<Exporter.State>(Exporter.State.Idle)
        private set
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
    /** Timeline position under the playhead. */
    var playheadMs by mutableLongStateOf(0L)

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    var historyVersion by mutableStateOf(0)
        private set
    val canUndo by derivedStateOf { historyVersion >= 0 && undoStack.isNotEmpty() }
    val canRedo by derivedStateOf { historyVersion >= 0 && redoStack.isNotEmpty() }

    private var saveJob: Job? = null

    val selectedClip: Clip? get() = selected?.let { project.clips.getOrNull(it) }

    init {
        preview.setProject(project, immediate = true)
    }

    // ---- state changes ----

    private fun pushHistory() {
        undoStack.addLast(project)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        historyVersion++
    }

    fun commit(p: Project, record: Boolean = true) {
        if (p == project) return
        if (record) pushHistory()
        project = p.copy(updatedAt = System.currentTimeMillis())
        selected?.let { if (it !in project.clips.indices) selected = null }
        preview.setProject(project)
        scheduleSave()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        project = prev
        historyVersion++
        afterHistoryJump()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        project = next
        historyVersion++
        afterHistoryJump()
    }

    private fun afterHistoryJump() {
        selected?.let { if (it !in project.clips.indices) selected = null }
        if (project.music == null) musicSelected = false
        preview.setProject(project, immediate = true)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = project
        saveJob = viewModelScope.launch {
            delay(600)
            withContext(Dispatchers.IO) { runCatching { store.save(snapshot) } }
        }
    }

    fun saveNow() {
        val snapshot = project
        runCatching { store.save(snapshot) }
    }

    // ---- panels ----

    /** Opens a tool panel; one undo step covers everything changed until it closes. */
    fun openPanel(p: Panel) {
        preview.pause()
        pushHistory()
        panel = p
    }

    fun closePanel() {
        // Drop the snapshot if the panel changed nothing.
        if (undoStack.lastOrNull() == project) {
            undoStack.removeLastOrNull()
            historyVersion++
        }
        panel = null
        editingTextId = null
    }

    // ---- selection ----

    fun select(index: Int?) {
        musicSelected = false
        selected = if (selected == index) null else index
        if (panel != null) closePanel()
    }

    fun selectMusic() {
        selected = null
        musicSelected = !musicSelected
        if (panel != null) closePanel()
    }

    // ---- clip operations ----

    fun splitAtPlayhead() {
        val t = playheadMs
        val before = project
        val after = ProjectOps.splitAt(project, t)
        if (after === before) {
            toast = "Không thể tách tại vị trí này"
            return
        }
        commit(after)
        selected = project.clipAt(t)?.first
    }

    fun deleteSelected() {
        val i = selected ?: return
        commit(ProjectOps.delete(project, i))
        selected = null
    }

    fun duplicateSelected() {
        val i = selected ?: return
        commit(ProjectOps.duplicate(project, i))
        selected = i + 1
    }

    fun moveSelected(delta: Int) {
        val i = selected ?: return
        val to = i + delta
        if (to !in project.clips.indices) return
        commit(ProjectOps.move(project, i, to))
        selected = to
    }

    /** Live edit of the selected clip (history is handled by the open panel). */
    fun updateSelected(f: (Clip) -> Clip) {
        val i = selected ?: return
        commit(ProjectOps.updateClip(project, i, f), record = panel == null)
    }

    fun applyToAll(f: (Clip) -> Clip) {
        commit(ProjectOps.updateAll(project, f), record = panel == null)
        toast = "Đã áp dụng cho tất cả các đoạn"
    }

    fun updateProject(f: (Project) -> Project) {
        commit(f(project), record = panel == null)
    }

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val clips = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    MediaProbe.persist(app, uri)
                    MediaProbe.probeClip(app, uri)
                }
            }
            busy = false
            if (clips.isEmpty()) {
                toast = "Không đọc được tệp đã chọn"
                return@launch
            }
            if (clips.size < uris.size) toast = "Bỏ qua ${uris.size - clips.size} tệp không hỗ trợ"
            val at = if (project.clips.isEmpty()) 0 else (project.clipAt(playheadMs)?.first ?: project.clips.lastIndex) + 1
            commit(ProjectOps.insert(project, at, clips))
        }
    }

    fun setMusic(uri: Uri) {
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val music = withContext(Dispatchers.IO) {
                MediaProbe.persist(app, uri)
                MediaProbe.probeMusic(app, uri)
            }
            busy = false
            if (music == null) {
                toast = "Không đọc được tệp âm thanh"
                return@launch
            }
            commit(project.copy(music = music))
            musicSelected = true
        }
    }

    fun removeMusic() {
        commit(project.copy(music = null))
        musicSelected = false
        if (panel == Panel.MUSIC) closePanel()
    }

    // ---- text ----

    /** Adds a text/sticker to the selected clip, or to the clip under the playhead. */
    fun addText(item: TextItem) {
        val index = selected ?: project.clipAt(playheadMs)?.first ?: return
        selected = index
        commit(ProjectOps.updateClip(project, index) { it.copy(texts = it.texts + item) }, record = panel == null)
        editingTextId = item.id
    }

    fun updateText(id: String, f: (TextItem) -> TextItem) {
        updateSelected { c -> c.copy(texts = c.texts.map { if (it.id == id) f(it) else it }) }
    }

    fun deleteText(id: String) {
        updateSelected { c -> c.copy(texts = c.texts.filterNot { it.id == id }) }
        if (editingTextId == id) editingTextId = null
    }

    // ---- export ----

    fun outputSize(settings: ExportSettings) = exporter.outputSize(project, settings)

    fun estimatedBitrate(settings: ExportSettings) =
        ProjectOps.targetBitrate(project, outputSize(settings), settings.quality)

    fun export(settings: ExportSettings) {
        preview.pause()
        saveNow()
        exporter.start(project, settings) { exportState = it }
    }

    fun cancelExport() = exporter.cancel()

    fun dismissExport() {
        exportState = Exporter.State.Idle
    }

    override fun onCleared() {
        saveNow()
        exporter.cancel()
        preview.release()
    }

    class Factory(private val app: Application, private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, id) as T
    }
}
