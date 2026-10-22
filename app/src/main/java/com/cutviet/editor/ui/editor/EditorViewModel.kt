package com.cutviet.editor.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cutviet.editor.media.dubbing.DubbingPipeline
import com.cutviet.editor.media.dubbing.DubbingWorker
import com.cutviet.editor.media.dubbing.LineDubWorker
import com.cutviet.editor.media.dubbing.LineDubber
import com.cutviet.editor.media.dubbing.RedubChoices
import com.cutviet.editor.media.dubbing.VoiceChoice
import com.cutviet.editor.media.dubbing.SourceLanguage
import com.cutviet.editor.media.dubbing.SubtitlePipeline
import com.cutviet.editor.media.dubbing.SubtitleWorker
import com.cutviet.editor.model.Subtitle
import com.cutviet.editor.model.SubtitleCues
import com.cutviet.editor.model.SubtitleStyle
import com.cutviet.editor.media.Exporter
import com.cutviet.editor.media.MediaProbe
import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.model.AudioKind
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.DubInfo
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
    FILTER("Bộ lọc"),
    ADJUST("Điều chỉnh"),
    TEXT("Văn bản"),
    STICKER("Nhãn dán"),
    AUDIO("Âm thanh"),
    OVERLAY("Lớp phủ"),
    OVERLAY_KEY("Tẩy nền lớp phủ"),
    OVERLAY_MASK("Mặt nạ lớp phủ"),
    VOICE("Giọng đọc AI"),
    CANVAS("Tỉ lệ khung hình"),
    SUBTITLE("Phụ đề"),
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

    /** Whether layers dragged on the timeline snap to clip edges, the playhead and other layers. */
    var snapEnabled by mutableStateOf(true)

    /** Set when the editor moves the playhead itself; the timeline then scrolls to [playheadJumpMs]. */
    var playheadJumps by mutableIntStateOf(0)
        private set
    var playheadJumpMs = 0L
        private set

    /** Whether the finger is moving the timeline (the preview then shows a big time label). */
    var scrubbing by mutableStateOf(false)
        private set

    /**
     * A clip edge being dragged on the timeline. [gapMs]: how much the left edge has moved in, kept
     * as a gap so the rest of the clip stays under the finger; [edgeSourceMs]: the new first (or
     * last) moment of the source video; [stopped]: the edge reached the playhead and stops there.
     */
    class Trim(val clipId: String, val atStart: Boolean, val gapMs: Long, val edgeSourceMs: Long, val durationMs: Long, val stopped: Boolean)

    var trim by mutableStateOf<Trim?>(null)
        private set
    private var trimOrigin: Project? = null
    private var trimIndex = 0
    private var trimStop: Long? = null

    /** Clip being edited in the full-screen crop editor, if any. */
    var cropClipId by mutableStateOf<String?>(null)
        private set

    /** Overlay being edited with the eraser (full-screen editor), if any. */
    var eraserOverlayId by mutableStateOf<String?>(null)
        private set

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    /** When the last live edit (slider, typing) was recorded; edits close together share one undo step. */
    private var lastLiveEditAt = 0L
    /** Set when a timeline drag starts; the undo step is recorded once the drag actually changes something. */
    private var dragPending = false
    var historyVersion by mutableStateOf(0)
        private set
    val canUndo by derivedStateOf { historyVersion >= 0 && undoStack.isNotEmpty() }
    val canRedo by derivedStateOf { historyVersion >= 0 && redoStack.isNotEmpty() }

    private var saveJob: Job? = null

    val selectedClip: Clip? get() = selected?.let { project.clips.getOrNull(it) }
    val selectedOverlay: Overlay? get() = selectedOverlayId?.let { id -> project.overlays.firstOrNull { it.id == id } }
    val selectedAudio: AudioTrack? get() = selectedAudioId?.let { id -> project.audios.firstOrNull { it.id == id } }

    init {
        val (undo, redo) = store.loadHistory(project.id)
        undoStack.addAll(undo.takeLast(MAX_UNDO))
        redoStack.addAll(redo.takeLast(MAX_UNDO))
        historyVersion++
        preview.setProject(project, immediate = true)
    }

    // ---- state changes ----

    /**
     * Records the current project as an undo step. Live edits ([coalesce]) that follow each other
     * within a moment (one slider drag, a burst of typing) share a single step.
     */
    private fun pushHistory(coalesce: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (coalesce && lastLiveEditAt != 0L && now - lastLiveEditAt < COALESCE_MS && undoStack.isNotEmpty()) {
            lastLiveEditAt = now
            return
        }
        lastLiveEditAt = if (coalesce) now else 0L
        undoStack.addLast(project)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        historyVersion++
    }

    fun commit(p: Project, record: Boolean = true, coalesce: Boolean = false) {
        if (p == project) return
        if (record) pushHistory(coalesce)
        val old = project
        project = p.copy(updatedAt = System.currentTimeMillis())
        selected?.let { if (it !in project.clips.indices) selected = null }
        // Subtitles are drawn over the preview, not by the player: editing them needs no rebuild.
        if (withoutSubtitles(old) != withoutSubtitles(project)) preview.setProject(project)
        scheduleSave()
    }

    private fun withoutSubtitles(p: Project) = p.copy(subtitles = emptyList(), subtitleStyle = SubtitleStyle(), updatedAt = 0)

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        while (redoStack.size > MAX_UNDO) redoStack.removeFirst()
        lastLiveEditAt = 0L
        project = prev
        historyVersion++
        afterHistoryJump()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        lastLiveEditAt = 0L
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
        val undo = undoStack.toList()
        val redo = redoStack.toList()
        saveJob = viewModelScope.launch {
            delay(600)
            withContext(Dispatchers.IO) {
                runCatching { store.save(snapshot) }
                runCatching { store.saveHistory(snapshot.id, undo, redo) }
            }
        }
    }

    fun saveNow() {
        val snapshot = project
        runCatching { store.save(snapshot) }
        runCatching { store.saveHistory(snapshot.id, undoStack.toList(), redoStack.toList()) }
    }

    // ---- panels ----

    /** Opens a tool panel. Each change made in it is its own undo step. */
    fun openPanel(p: Panel) {
        preview.pause()
        lastLiveEditAt = 0L
        panel = p
    }

    /** Replaces the open panel with another one. */
    fun switchPanel(p: Panel) {
        if (panel == null) openPanel(p) else panel = p
    }

    fun closePanel() {
        lastLiveEditAt = 0L
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

    /** Selects an overlay; tapping the selected one again deselects it unless [toggle] is false. */
    fun selectOverlay(id: String?, toggle: Boolean = true) {
        if (!toggle && selectedOverlayId == id) return
        selected = null
        selectedAudioId = null
        selectedOverlayId = if (toggle && selectedOverlayId == id) null else id
        // Overlay panels stay open and follow the newly selected layer.
        val overlayPanel = panel == Panel.OVERLAY || panel == Panel.OVERLAY_KEY || panel == Panel.OVERLAY_MASK
        if (panel != null && (selectedOverlayId == null || !overlayPanel)) closePanel()
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

    /** Live edit of the selected clip (slider moves close together share one undo step). */
    fun updateSelected(f: (Clip) -> Clip) {
        val i = selected ?: return
        commit(ProjectOps.updateClip(project, i, f), coalesce = true)
    }

    fun applyToAll(f: (Clip) -> Clip) {
        commit(ProjectOps.updateAll(project, f))
        toast = "Đã áp dụng cho tất cả các đoạn"
    }

    fun updateProject(f: (Project) -> Project) {
        commit(f(project), coalesce = true)
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
        commit(ProjectOps.updateAudio(project, id, f), coalesce = true)
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
            // Every video layer needs its own decoder; phones have a limited number of them.
            val stacked = project.overlays.count { !it.isImage && start >= it.startMs && start < it.endMs }
            if (stacked >= 4) toast = "Đang có $stacked video chồng cùng lúc, máy có thể xem trước chậm hoặc xuất lỗi"
        }
    }

    fun updateSelectedOverlay(f: (Overlay) -> Overlay) {
        val id = selectedOverlayId ?: return
        commit(ProjectOps.updateOverlay(project, id, f), coalesce = true)
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
    fun moveOverlayToPlayhead() {
        val id = selectedOverlayId ?: return
        commit(ProjectOps.updateOverlay(project, id) { it.copy(startMs = playheadMs) })
    }

    /** Moves the selected overlay up (+1) or down (-1) in the stack of layers. */
    fun restackSelectedOverlay(delta: Int) {
        val id = selectedOverlayId ?: return
        val moved = ProjectOps.restackOverlay(project, id, delta)
        if (moved === project) {
            toast = if (delta > 0) "Lớp phủ đã ở trên cùng" else "Lớp phủ đã ở dưới cùng"
            return
        }
        commit(moved)
        toast = if (delta > 0) "Đã đưa lớp phủ lên trên" else "Đã đưa lớp phủ xuống dưới"
    }

    /** Opens the crop editor ("Cắt khung") for the selected clip. */
    fun openCrop() {
        val c = selectedClip ?: return
        preview.pause()
        cropClipId = c.id
    }

    fun closeCrop() {
        cropClipId = null
    }

    /** Applies what was set in the crop editor as one undo step. */
    fun saveCrop(edited: Clip) {
        cropClipId = null
        val i = project.clips.indexOfFirst { it.id == edited.id }
        if (i < 0) return
        commit(
            ProjectOps.updateClip(project, i) {
                it.copy(rotation = edited.rotation, flipH = edited.flipH, flipV = edited.flipV, crop = edited.crop)
            },
        )
    }

    fun openEraser() {
        val o = selectedOverlay ?: return
        preview.pause()
        eraserOverlayId = o.id
    }

    fun closeEraser() {
        eraserOverlayId = null
    }

    /**
     * Saves the eraser drawing for [overlayId] as one undo step. Each save writes a new file so
     * undo can go back to the previous drawing.
     */
    fun saveEraseMask(overlayId: String, mask: Bitmap?) {
        eraserOverlayId = null
        if (mask == null) {
            commit(ProjectOps.updateOverlay(project, overlayId) { it.copy(eraseMask = null) })
            return
        }
        busy = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(app.filesDir, "masks").apply { mkdirs() }
                    File(dir, "erase_${overlayId}_${System.currentTimeMillis()}.png").also { f ->
                        f.outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }.getOrNull()
            }
            busy = false
            if (file == null) {
                toast = "Không lưu được vùng tẩy"
                return@launch
            }
            commit(ProjectOps.updateOverlay(project, overlayId) { it.copy(eraseMask = file.absolutePath) })
        }
    }

    fun setTimelineScrubbing(on: Boolean) {
        if (scrubbing == on) return
        scrubbing = on
        preview.setScrubbing("timeline", on)
    }

    /**
     * Starts dragging an edge of main-track clip [index]. The preview gets the clip's whole source
     * on that side, so it can show each frame the edge passes, in or out, while the finger moves.
     * With snapping on, a left edge stops at the playhead when the playhead is inside the clip.
     */
    fun beginTrim(index: Int, atStart: Boolean) {
        val c = project.clips.getOrNull(index) ?: return
        beginDrag()
        val start = project.clipStartMs(index)
        trimOrigin = project
        trimIndex = index
        trimStop = if (snapEnabled && atStart && playheadMs > start && playheadMs < start + c.durationMs) playheadMs else null
        trim = Trim(c.id, atStart, 0, if (atStart) c.trimStartMs else c.trimEndMs, c.durationMs, false)
        if (!c.isImage) {
            val open = ProjectOps.updateClip(project, index) { if (atStart) it.copy(trimStartMs = 0) else it.copy(trimEndMs = it.sourceDurationMs) }
            preview.setScrubbing("trim", true)
            preview.setProject(open, immediate = true, positionMs = trimFrameMs(c, start, c, atStart))
        }
    }

    /** Moves the dragged edge [deltaMs] (timeline time) from where it was when the drag began. */
    fun trimTo(deltaMs: Long, snapMs: Long) {
        val origin = trimOrigin ?: return
        val c = origin.clips.getOrNull(trimIndex) ?: return
        val atStart = trim?.atStart ?: return
        val start = origin.clipStartMs(trimIndex)
        val snapTo = if (snapEnabled) buildList {
            add(playheadMs)
            origin.overlays.forEach { add(it.startMs); add(it.endMs) }
        } else emptyList()
        val clip = if (atStart) ProjectOps.trimClipStart(c, start, deltaMs, trimStop, snapTo, snapMs)
        else ProjectOps.trimClipEnd(c, start, deltaMs, snapTo, snapMs)
        val gap = if (atStart) (c.durationMs - clip.durationMs).coerceAtLeast(0) else 0
        trim = Trim(
            c.id, atStart, gap, if (atStart) clip.trimStartMs else clip.trimEndMs, clip.durationMs,
            stopped = trimStop?.let { start + gap >= it - 1 } ?: false,
        )
        val p = ProjectOps.updateClip(origin, trimIndex) { clip }
        if (p != project) {
            if (dragPending) {
                dragPending = false
                pushHistory()
            }
            project = p.copy(updatedAt = System.currentTimeMillis())
            scheduleSave()
        }
        if (!c.isImage) preview.seekTo(trimFrameMs(c, start, clip, atStart))
    }

    /** Ends an edge drag: the preview plays the trimmed project again, from the same frame as before. */
    fun endTrim() {
        val t = trim ?: return
        val origin = trimOrigin
        trim = null
        trimOrigin = null
        trimStop = null
        preview.setScrubbing("trim", false)
        val start = origin?.clipStartMs(trimIndex) ?: 0L
        val removed = if (t.atStart && origin != null) (origin.clips.getOrNull(trimIndex)?.durationMs ?: 0L) - t.durationMs else 0L
        val at = ProjectOps.playheadAfterStartTrim(playheadMs, start, removed).coerceIn(0, project.durationMs)
        preview.setProject(project, immediate = true, positionMs = at)
        jumpPlayhead(at)
    }

    /** Moves the playhead to [ms] and scrolls the timeline there. */
    fun jumpPlayhead(ms: Long) {
        playheadMs = ms
        playheadJumpMs = ms
        playheadJumps++
    }

    /**
     * Where the frame at the dragged edge of [now] sits in the preview's project, in which [was]
     * (starting at [startMs]) is opened to the start or end of its source.
     */
    private fun trimFrameMs(was: Clip, startMs: Long, now: Clip, atStart: Boolean): Long =
        if (atStart) startMs + (now.trimStartMs / was.speed).toLong()
        else (startMs + now.durationMs - (1000 / was.frameRate.coerceAtLeast(1f)).toLong().coerceIn(15, 50)).coerceAtLeast(startMs)

    /** Called when a drag on the timeline starts, so the whole drag is one undo step. */
    fun beginDrag() {
        preview.pause()
        dragPending = true
    }

    /** Live update during a timeline drag (no history entry per frame). */
    fun dragUpdate(p: Project) {
        if (p == project) return
        if (dragPending) {
            dragPending = false
            pushHistory()
        }
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
            commit(project.copy(audios = audios))
            clearSelection()
            selectedAudioId = track.id
            onDone(true)
        }
    }

    // ---- dubbing ----

    var showDubbing by mutableStateOf(false)

    /** Progress line of the running dubbing job, or null when none runs. */
    var dubStatus by mutableStateOf<String?>(null)
        private set

    // After the dubbing state above is initialised.
    init {
        watchDubbing()
    }

    /**
     * Starts dubbing the selected clip (or the one under the playhead) in the background. The job
     * outlives this screen; [watchDubbing] applies its result whenever it finishes.
     */
    fun startDubbing(language: SourceLanguage, voiceName: String?, originalVolume: Float = 0f) {
        val index = selected ?: project.clipAt(playheadMs)?.first ?: return
        val clip = project.clips[index]
        if (clip.isImage || !clip.hasAudio) {
            toast = "Đoạn này không có âm thanh để lồng tiếng."
            return
        }
        if (dubStatus != null) {
            toast = "Đang có một lượt lồng tiếng chạy."
            return
        }
        DubbingWorker.enqueue(
            getApplication(), clip.uri, clip.trimStartMs, clip.trimEndMs, language, voiceName,
            projectId = project.id, clipId = clip.id, originalVolume = originalVolume,
        )
        dubStatus = "Đang chuẩn bị lồng tiếng…"
    }

    /**
     * Follows the dubbing job of this project, also one started before the app was closed: shows
     * its progress and, once it succeeds, puts the dub on the timeline (exactly once).
     */
    private fun watchDubbing() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(DubbingWorker.UNIQUE_NAME).collect { infos ->
                val info = infos.lastOrNull { it.tags.contains(DubbingWorker.projectTag(project.id)) } ?: return@collect
                if (!info.state.isFinished) {
                    val step = info.progress.getString(DubbingWorker.KEY_STEP)
                        ?.let { runCatching { DubbingPipeline.Step.valueOf(it) }.getOrNull() }
                    val pct = (info.progress.getFloat(DubbingWorker.KEY_PROGRESS, 0f) * 100).toInt()
                    val note = info.progress.getString(DubbingWorker.KEY_NOTE)?.let { " · $it" }.orEmpty()
                    dubStatus = if (step != null) "${step.label}… $pct%$note" else "Đang chuẩn bị lồng tiếng…"
                    return@collect
                }
                val wasRunning = dubStatus != null
                dubStatus = null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> applyDubResult(app, info)
                    WorkInfo.State.FAILED -> if (wasRunning) toast = info.outputData.getString(DubbingWorker.KEY_ERROR) ?: "Lồng tiếng thất bại."
                    else -> if (wasRunning) toast = "Đã huỷ lồng tiếng."
                }
            }
        }
    }

    private suspend fun applyDubResult(app: Application, info: WorkInfo) {
        val jobId = info.id.toString()
        val file = DubbingWorker.resultFile(app, jobId)
        if (!file.exists()) return // already applied
        val result = withContext(Dispatchers.IO) { runCatching { DubbingWorker.decode(file.readText()) }.getOrNull() }
        val clipId = info.tags.firstOrNull { it.startsWith("clip:") }?.removePrefix("clip:")
        val originalVolume = info.tags.firstOrNull { it.startsWith("orig:") }?.removePrefix("orig:")?.toFloatOrNull() ?: 0f
        val at = project.clips.indexOfFirst { it.id == clipId }
        withContext(Dispatchers.IO) { file.renameTo(java.io.File(file.parentFile, "applied.json")) }
        if (result == null || at < 0) {
            toast = "Không đặt được kết quả lồng tiếng (đoạn video đã bị xoá?)."
            return
        }
        val sourceUri = project.clips[at].uri
        val lines = result.segments.map {
            val v = it.voice
            val info = DubInfo(
                sourceUri, it.startMs, it.startMs + it.slotMs, it.sourceText, it.text, result.language, it.speaker,
                geminiVoice = v?.gemini, gender = v?.gender?.name, description = v?.description.orEmpty(),
                emotion = v?.emotion.orEmpty(), phoneVoice = result.phoneVoice, pitch = v?.pitch ?: 1f,
            )
            ProjectOps.DubLine(it.startMs, Uri.fromFile(it.file).toString(), it.durationMs, it.text, it.playbackSpeed, it.speaker, info)
        }
        val stems = result.stems?.let {
            ProjectOps.DubStems(Uri.fromFile(it.voice).toString(), Uri.fromFile(it.background).toString(), result.sourceStartMs, it.durationMs)
        }
        commit(ProjectOps.applyDub(project, at, lines, originalVolume, stems, result.gain, result.slowdowns))
        val fallback = info.outputData.getString(DubbingWorker.KEY_FALLBACK)
        toast = buildString {
            append("Đã lồng tiếng ${lines.size} câu")
            append(if (info.outputData.getBoolean(DubbingWorker.KEY_ONLINE, false)) " (online)" else " (offline)")
            append(if (stems != null) ", giữ nguyên âm thanh nền." else ". Chưa có model tách giọng nên tiếng gốc chỉ được giảm cả đoạn.")
            val voices = info.outputData.getInt(DubbingWorker.KEY_SPEAKERS, 0)
            if (voices > 1) append(" Có $voices giọng khác nhau, tên mỗi track ghi ai nói.")
            val slowed = info.outputData.getInt(DubbingWorker.KEY_SLOWED, 0)
            if (slowed > 0) append(" Video được làm chậm nhẹ ở $slowed chỗ để câu lồng tiếng vừa.")
            if (fallback != null) append(" Online lỗi nên đã chuyển offline: $fallback")
            info.outputData.getString(DubbingWorker.KEY_NOTE)?.let { append(" Lưu ý: $it.") }
        }
    }

    fun cancelDubbing() {
        WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork(DubbingWorker.UNIQUE_NAME)
    }

    // ---- dub one line again ----

    /** DUB track the "Lồng lại câu này" dialog is open for. */
    var redubTrackId by mutableStateOf<String?>(null)

    /** Progress line of the running "dub one line again" job, or null. */
    var redubStatus by mutableStateOf<String?>(null)
        private set

    init {
        watchRedub()
    }

    fun openRedub() {
        val a = selectedAudio?.takeIf { it.kind == AudioKind.DUB } ?: return
        if (ProjectOps.dubOrigin(project, a) == null) {
            toast = "Không tìm thấy đoạn video của câu này."
            return
        }
        redubTrackId = a.id
    }

    /**
     * Dubs DUB track [trackId] again in the background: listened to and translated again when
     * [text] is null, else [text] read as is, in [choice]'s voice. The new line takes the old
     * one's place when done.
     */
    fun startRedub(trackId: String, text: String?, choice: VoiceChoice, language: SourceLanguage) {
        val a = project.audios.firstOrNull { it.id == trackId } ?: return
        val info = ProjectOps.dubOrigin(project, a) ?: return
        if (redubStatus != null) {
            toast = "Đang lồng lại một câu khác."
            return
        }
        // Time the line has on the timeline (longer where the video was slowed for it).
        val end = ProjectOps.timelineOfSource(project, info.sourceUri, info.sourceEndMs - 1)?.plus(1)
        val slot = (end?.minus(a.startMs) ?: (info.sourceEndMs - info.sourceStartMs)).coerceAtLeast(300)
        val room = ProjectOps.dubRoom(project, a, slot, DubbingPipeline.GAP_MS, DubbingPipeline.MAX_SPILL_MS)
        val request = LineDubber.Request(
            info.sourceUri, info.sourceStartMs, info.sourceEndMs, room, language, text?.trim(), info.sourceText,
            RedubChoices.around(project, a), choice.voice, info.phoneVoice, choice.speaker,
        )
        LineDubWorker.enqueue(getApplication(), project.id, a.id, request)
        redubStatus = "Đang chuẩn bị lồng lại câu…"
        redubTrackId = null
    }

    fun cancelRedub() {
        WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork(LineDubWorker.UNIQUE_NAME)
    }

    private fun watchRedub() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(LineDubWorker.UNIQUE_NAME).collect { infos ->
                val info = infos.lastOrNull { it.tags.contains(DubbingWorker.projectTag(project.id)) } ?: return@collect
                if (!info.state.isFinished) {
                    val step = info.progress.getString(LineDubWorker.KEY_STEP)
                        ?.let { runCatching { LineDubber.Step.valueOf(it) }.getOrNull() }
                    val pct = (info.progress.getFloat(LineDubWorker.KEY_PROGRESS, 0f) * 100).toInt()
                    redubStatus = if (step != null) "Lồng lại câu: ${step.label}… $pct%" else "Đang chuẩn bị lồng lại câu…"
                    return@collect
                }
                val wasRunning = redubStatus != null
                redubStatus = null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> applyRedubResult(app, info)
                    WorkInfo.State.FAILED -> if (wasRunning) toast = info.outputData.getString(LineDubWorker.KEY_ERROR) ?: "Lồng lại thất bại."
                    else -> if (wasRunning) toast = "Đã huỷ lồng lại câu."
                }
            }
        }
    }

    private suspend fun applyRedubResult(app: Application, info: WorkInfo) {
        val file = LineDubWorker.resultFile(app, info.id.toString())
        if (!file.exists()) return // already applied
        val result = withContext(Dispatchers.IO) {
            runCatching { LineDubWorker.decode(file.readText()) }.getOrNull().also { file.renameTo(File(file.parentFile, "line_applied.json")) }
        }
        val trackId = info.tags.firstOrNull { it.startsWith("track:") }?.removePrefix("track:")
        if (result == null || project.audios.none { it.id == trackId }) {
            toast = "Không đặt được câu lồng lại (câu cũ đã bị xoá?)."
            return
        }
        val r = result.request
        val dub = DubInfo(
            r.sourceUri, r.startMs, r.endMs, result.sourceText, result.text, r.language.name, r.speaker,
            geminiVoice = r.voice.gemini, gender = r.voice.gender?.name, description = r.voice.description,
            emotion = result.emotion, phoneVoice = r.phoneVoice, pitch = r.voice.pitch,
        )
        commit(ProjectOps.replaceDubLine(project, trackId!!, Uri.fromFile(result.file).toString(), result.durationMs, result.playbackSpeed, result.gain, dub))
        selectedAudioId = trackId
        toast = buildString {
            append("Đã lồng lại câu")
            append(if (info.outputData.getBoolean(LineDubWorker.KEY_ONLINE, false)) " (online)" else " (offline)")
            append(": \"${result.text.take(60)}\"")
            info.outputData.getString(LineDubWorker.KEY_NOTE)?.let { append(". Lưu ý: $it") }
        }
    }

    // ---- subtitles ----

    var showSubtitleDialog by mutableStateOf(false)
    /** Tab the dubbing dialog opens on (1 = models and keys). */
    var dubbingTab by mutableIntStateOf(0)

    /** Progress line of the running subtitle job, or null when none runs. */
    var subtitleStatus by mutableStateOf<String?>(null)
        private set

    /** Subtitle line being edited in the panel. */
    var selectedSubtitleId by mutableStateOf<String?>(null)

    init {
        watchSubtitles()
    }

    /** Makes subtitles for every video clip in the background; they replace the current ones when done. */
    fun startSubtitles(language: SourceLanguage, separate: Boolean) {
        if (subtitleStatus != null) {
            toast = "Đang có một lượt tạo phụ đề chạy."
            return
        }
        val parts = project.clips.mapIndexedNotNull { i, c ->
            if (c.isImage || !c.hasAudio || c.durationMs <= 0) null
            else SubtitlePipeline.Part(c.uri, c.trimStartMs, c.trimEndMs, project.clipStartMs(i), c.durationMs, c.speed)
        }
        if (parts.isEmpty()) {
            toast = "Dự án chưa có đoạn video nào có tiếng."
            return
        }
        SubtitleWorker.enqueue(getApplication(), project.id, parts, language, separate)
        subtitleStatus = "Đang chuẩn bị tạo phụ đề…"
    }

    fun cancelSubtitles() {
        WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork(SubtitleWorker.UNIQUE_NAME)
    }

    private fun watchSubtitles() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SubtitleWorker.UNIQUE_NAME).collect { infos ->
                val info = infos.lastOrNull { it.tags.contains(DubbingWorker.projectTag(project.id)) } ?: return@collect
                if (!info.state.isFinished) {
                    val step = info.progress.getString(SubtitleWorker.KEY_STEP)
                        ?.let { runCatching { SubtitlePipeline.Step.valueOf(it) }.getOrNull() }
                    val pct = (info.progress.getFloat(SubtitleWorker.KEY_PROGRESS, 0f) * 100).toInt()
                    val note = info.progress.getString(SubtitleWorker.KEY_NOTE)?.let { " · $it" }.orEmpty()
                    subtitleStatus = if (step != null) "Phụ đề: ${step.label}… $pct%$note" else "Đang chuẩn bị tạo phụ đề…"
                    return@collect
                }
                val wasRunning = subtitleStatus != null
                subtitleStatus = null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> applySubtitleResult(app, info)
                    WorkInfo.State.FAILED -> if (wasRunning) toast = info.outputData.getString(SubtitleWorker.KEY_ERROR) ?: "Tạo phụ đề thất bại."
                    else -> if (wasRunning) toast = "Đã huỷ tạo phụ đề."
                }
            }
        }
    }

    private suspend fun applySubtitleResult(app: Application, info: WorkInfo) {
        val file = SubtitleWorker.resultFile(app, info.id.toString())
        if (!file.exists()) return // already applied
        val cues = withContext(Dispatchers.IO) {
            runCatching { SubtitleWorker.decode(file.readText()) }.getOrNull().also { file.renameTo(File(file.parentFile, "subtitles_applied.json")) }
        }
        if (cues.isNullOrEmpty()) {
            toast = "Không đọc được kết quả phụ đề."
            return
        }
        commit(project.copy(subtitles = cues.map { Subtitle(startMs = it.startMs, endMs = it.endMs, text = it.text, source = it.source) }))
        val fallback = info.outputData.getString(SubtitleWorker.KEY_FALLBACK)
        toast = buildString {
            append("Đã tạo ${cues.size} câu phụ đề")
            append(if (info.outputData.getBoolean(SubtitleWorker.KEY_ONLINE, false)) " (online)." else " (offline).")
            if (fallback != null) append(" Online lỗi nên đã chuyển offline: $fallback")
        }
    }

    val selectedSubtitle: Subtitle? get() = selectedSubtitleId?.let { id -> project.subtitles.firstOrNull { it.id == id } }

    /** Keeps lines in time order after an edit. */
    private fun withSubtitles(list: List<Subtitle>) = project.copy(subtitles = list.sortedBy { it.startMs })

    fun updateSubtitle(id: String, f: (Subtitle) -> Subtitle) {
        commit(withSubtitles(project.subtitles.map { if (it.id == id) f(it).let { s -> s.copy(endMs = maxOf(s.endMs, s.startMs + 100)) } else it }), coalesce = true)
    }

    /** Moves one end of a line to the playhead. */
    fun setSubtitleEdge(id: String, start: Boolean) {
        val t = playheadMs
        updateSubtitle(id) { if (start) it.copy(startMs = minOf(t, it.endMs - 100)) else it.copy(endMs = maxOf(t, it.startMs + 100)) }
    }

    /** Adds an empty line at the playhead, up to the next line (at most 3 s). */
    fun addSubtitleAtPlayhead() {
        val t = playheadMs
        if (SubtitleCues.at(project.subtitles, t) != null) {
            toast = "Ở đây đã có phụ đề, hãy sửa câu đó."
            return
        }
        val next = project.subtitles.firstOrNull { it.startMs > t }?.startMs ?: Long.MAX_VALUE
        val end = minOf(t + 3000, next - SubtitleCues.GAP_MS, project.durationMs).coerceAtLeast(t + 300)
        val sub = Subtitle(startMs = t, endMs = end, text = "Phụ đề")
        commit(withSubtitles(project.subtitles + sub))
        selectedSubtitleId = sub.id
    }

    fun deleteSubtitle(id: String) {
        commit(project.copy(subtitles = project.subtitles.filterNot { it.id == id }))
        if (selectedSubtitleId == id) selectedSubtitleId = null
    }

    fun clearSubtitles() {
        commit(project.copy(subtitles = emptyList()))
        selectedSubtitleId = null
    }

    fun updateSubtitleStyle(f: (SubtitleStyle) -> SubtitleStyle) {
        commit(project.copy(subtitleStyle = f(project.subtitleStyle)), coalesce = true)
    }

    /** The subtitle tool: the panel when there are subtitles, else straight to making them. */
    fun openSubtitles() {
        if (project.subtitles.isEmpty() && subtitleStatus == null) showSubtitleDialog = true
        else openPanel(Panel.SUBTITLE)
    }

    /** Selects a line and shows it: the playhead moves just inside it. */
    fun showSubtitle(sub: Subtitle) {
        preview.pause()
        selectedSubtitleId = sub.id
        val t = (sub.startMs + 1).coerceAtMost(project.durationMs)
        jumpPlayhead(t)
        preview.seekTo(t)
    }

    // ---- text ----

    /** Adds a text/sticker to the selected clip, or to the clip under the playhead. */
    fun addText(item: TextItem) {
        val index = selected ?: project.clipAt(playheadMs)?.first ?: return
        selected = index
        commit(ProjectOps.updateClip(project, index) { it.copy(texts = it.texts + item) })
        editingTextId = item.id
    }

    fun updateText(id: String, f: (TextItem) -> TextItem) {
        updateSelected { c -> c.copy(texts = c.texts.map { if (it.id == id) f(it) else it }) }
    }

    fun deleteText(id: String) {
        val i = selected ?: return
        commit(ProjectOps.updateClip(project, i) { c -> c.copy(texts = c.texts.filterNot { it.id == id }) })
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

    companion object {
        /** Undo steps kept (also saved with the draft). */
        const val MAX_UNDO = 5
        private const val COALESCE_MS = 1000L
    }

    class Factory(private val app: Application, private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, id) as T
    }
}
