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
import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.VoiceSpec
import com.cutviet.editor.model.newId
import java.io.File
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
    AUDIO("Âm thanh"),
    OVERLAY("Lớp phủ"),
    OVERLAY_KEY("Tẩy nền lớp phủ"),
    VOICE("Giọng đọc AI"),
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
    var selectedOverlayId by mutableStateOf<String?>(null)
        private set
    var selectedAudioId by mutableStateOf<String?>(null)
        private set
    val voice = VoiceEngine(app)
    /** Timeline range the voice-over being typed will cover (shown on the timeline). */
    var voicePreviewRange by mutableStateOf<LongRange?>(null)
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
    val selectedOverlay: Overlay? get() = selectedOverlayId?.let { id -> project.overlays.firstOrNull { it.id == id } }
    val selectedAudio: AudioTrack? get() = selectedAudioId?.let { id -> project.audios.firstOrNull { it.id == id } }

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
        if (selectedOverlay == null) selectedOverlayId = null
        if (selectedAudio == null) selectedAudioId = null
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

    /** Replaces the open panel with another one, keeping a single undo step for both. */
    fun switchPanel(p: Panel) {
        if (panel == null) openPanel(p) else panel = p
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
        selectedOverlayId = null
        selectedAudioId = null
        selected = if (selected == index) null else index
        if (panel != null) closePanel()
    }

    fun selectOverlay(id: String?) {
        selected = null
        selectedAudioId = null
        selectedOverlayId = if (selectedOverlayId == id) null else id
        if (panel != null) closePanel()
    }

    fun selectAudio(id: String?) {
        selected = null
        selectedOverlayId = null
        selectedAudioId = if (selectedAudioId == id) null else id
        if (panel != null) closePanel()
    }

    fun clearSelection() {
        selected = null
        selectedOverlayId = null
        selectedAudioId = null
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

    /** Adds an audio file (or the sound of a video file) as a new track at the playhead. */
    fun addAudio(uri: Uri, kind: AudioKind = AudioKind.MUSIC) {
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val start = if (project.audios.none { it.kind == AudioKind.MUSIC } && kind == AudioKind.MUSIC) 0L else playheadMs
            val track = withContext(Dispatchers.IO) {
                MediaProbe.persist(app, uri)
                MediaProbe.probeAudio(app, uri, kind, start)
            }
            busy = false
            if (track == null) {
                toast = "Không đọc được âm thanh từ tệp này"
                return@launch
            }
            commit(project.copy(audios = project.audios + track))
            clearSelection()
            selectedAudioId = track.id
        }
    }

    fun deleteSelectedAudio() {
        val id = selectedAudioId ?: return
        commit(project.copy(audios = project.audios.filterNot { it.id == id }))
        selectedAudioId = null
        if (panel != null) closePanel()
    }

    fun updateSelectedAudio(f: (AudioTrack) -> AudioTrack) {
        val id = selectedAudioId ?: return
        commit(ProjectOps.updateAudio(project, id, f), record = panel == null)
    }

    /** Separates the selected clip's sound onto its own track ("Tách âm thanh"). */
    fun extractAudioFromSelected() {
        val i = selected ?: return
        val clip = project.clips.getOrNull(i) ?: return
        if (clip.isImage || !clip.hasAudio) {
            toast = "Đoạn này không có âm thanh"
            return
        }
        val before = project.audios.map { it.id }.toSet()
        commit(ProjectOps.extractAudio(project, i, "Âm thanh đoạn ${i + 1}"))
        project.audios.firstOrNull { it.id !in before }?.let {
            clearSelection()
            selectedAudioId = it.id
        }
        toast = "Đã tách âm thanh ra rãnh riêng"
    }

    // ---- overlays ----

    fun addOverlays(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val start = playheadMs.coerceIn(0, (project.durationMs - 100).coerceAtLeast(0))
            val list = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    MediaProbe.persist(app, uri)
                    MediaProbe.probeOverlay(app, uri, start)
                }
            }
            busy = false
            if (list.isEmpty()) {
                toast = "Không đọc được tệp đã chọn"
                return@launch
            }
            // Images default to the rest of the current clip, capped at 5 s.
            val fixed = list.map { o ->
                if (o.isImage) {
                    val d = (project.durationMs - start).coerceIn(ProjectOps.MIN_CLIP_MS, 5000)
                    o.copy(trimStartMs = 0, trimEndMs = d)
                } else o
            }
            commit(project.copy(overlays = project.overlays + fixed))
            clearSelection()
            selectedOverlayId = fixed.last().id
        }
    }

    fun updateSelectedOverlay(f: (Overlay) -> Overlay) {
        val id = selectedOverlayId ?: return
        commit(ProjectOps.updateOverlay(project, id, f), record = panel == null)
    }

    fun deleteSelectedOverlay() {
        val id = selectedOverlayId ?: return
        commit(project.copy(overlays = project.overlays.filterNot { it.id == id }))
        selectedOverlayId = null
        if (panel != null) closePanel()
    }

    fun duplicateSelectedOverlay() {
        val o = selectedOverlay ?: return
        val copy = o.copy(id = newId(), x = (o.x + 0.05f).coerceAtMost(1f), y = (o.y + 0.05f).coerceAtMost(1f))
        commit(project.copy(overlays = project.overlays + copy))
        selectedOverlayId = copy.id
    }

    /** Moves the selected overlay so it starts at the playhead. */
    fun moveOverlayToPlayhead() = updateSelectedOverlay { it.copy(startMs = playheadMs) }

    /** Called when a drag on the timeline starts, so the whole drag is one undo step. */
    fun beginDrag() {
        preview.pause()
        pushHistory()
    }

    /** Live update during a timeline drag (no history entry per frame). */
    fun dragUpdate(p: Project) {
        project = p.copy(updatedAt = System.currentTimeMillis())
        preview.setProject(project)
        scheduleSave()
    }

    // ---- voice-over ----

    /**
     * Generates speech for [spec] and adds it as a voice track at [startMs], replacing [replaceId]
     * when editing an existing voice-over.
     */
    fun addVoice(spec: VoiceSpec, startMs: Long, replaceId: String?, onDone: (Boolean) -> Unit) {
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = File(File(app.filesDir, "voice"), "voice_${System.currentTimeMillis()}.wav")
            val ms = voice.synthesize(spec.text, spec.voiceName, spec.pitch, spec.rate, file)
            busy = false
            if (ms == null || ms <= 0) {
                toast = "Không tạo được giọng đọc. Kiểm tra giọng tiếng Việt trong cài đặt chuyển văn bản thành giọng nói."
                onDone(false)
                return@launch
            }
            val name = spec.text.trim().replace(Regex("\\s+"), " ").take(40)
            val old = replaceId?.let { id -> project.audios.firstOrNull { it.id == id } }
            val track = AudioTrack(
                id = old?.id ?: newId(), kind = AudioKind.VOICE, uri = Uri.fromFile(file).toString(), name = name,
                sourceDurationMs = ms, startMs = old?.startMs ?: startMs, volume = old?.volume ?: 1f, voice = spec,
            )
            val audios = if (old != null) project.audios.map { if (it.id == old.id) track else it } else project.audios + track
            commit(project.copy(audios = audios), record = panel == null)
            clearSelection()
            selectedAudioId = track.id
            onDone(true)
        }
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
        voice.shutdown()
        saveNow()
        exporter.cancel()
        preview.release()
    }

    class Factory(private val app: Application, private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, id) as T
    }
}
