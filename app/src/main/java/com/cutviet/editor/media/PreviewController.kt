package com.cutviet.editor.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.CompositionPlayer
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.ProjectOps

/**
 * Plays the edited project. Uses Media3's CompositionPlayer so the preview shows the same effects
 * as the export; if the device cannot play a composition it falls back to a plain ExoPlayer playlist
 * (cuts and order only). Overlay pictures are not part of it: the editor draws them on top of the
 * player itself (see OverlayPreview), which works on every phone and follows edits instantly.
 */
class PreviewController(private val context: Context) {

    val player = mutableStateOf<Player?>(null)
    val isPlaying = mutableStateOf(false)
    val simpleMode = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    private var project: Project? = null
    private var composition: CompositionPlayer? = null
    private var exo: ExoPlayer? = null
    private var withAudio = true
    private var withSpeed = true
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRebuild: Runnable? = null
    /** What is scrubbing the preview right now (the timeline, a trim handle). */
    private val scrubbers = HashSet<String>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) isPlaying.value = false
        }

        override fun onPlayerError(e: PlaybackException) {
            handleError(e)
        }
    }

    /**
     * Applies [p] to the player. Debounced so slider drags do not rebuild on every frame.
     * [positionMs]: where to show afterwards; by default the current position.
     */
    fun setProject(p: Project, immediate: Boolean = false, positionMs: Long? = null) {
        val old = project
        project = p
        pendingRebuild?.let { handler.removeCallbacks(it) }
        val r = Runnable { rebuild(positionMs ?: if (old == null) 0L else positionMs()) }
        pendingRebuild = r
        if (immediate || old == null) handler.post(r) else handler.postDelayed(r, 250)
    }

    private fun rebuild(position: Long) {
        pendingRebuild = null
        val p = project ?: return
        if (p.clips.isEmpty()) {
            releasePlayers()
            return
        }
        if (simpleMode.value) {
            loadSimple(p, position)
            return
        }
        try {
            val size = ProjectOps.fitLongSide(ProjectOps.canvasSize(p), 1280)
            val comp = CompositionFactory.build(
                context,
                p,
                CompositionFactory.Options(
                    size.first, size.second,
                    includeAudioTracks = withAudio, includeOverlays = false, includeSpeed = withSpeed,
                ),
            )
            val cp = composition ?: CompositionPlayer.Builder(context).build().also {
                it.addListener(listener)
                composition = it
                player.value = it
            }
            // Players made for a new composition do not inherit scrubbing mode: switch it on again.
            if (cp.isScrubbingModeEnabled) cp.setScrubbingModeEnabled(false)
            cp.setComposition(comp)
            cp.prepare()
            if (scrubbers.isNotEmpty()) cp.setScrubbingModeEnabled(true)
            cp.seekTo(position.coerceIn(0, p.durationMs))
        } catch (e: Exception) {
            handleError(e)
        }
    }

    private fun handleError(e: Exception) {
        val p = project ?: return
        val position = positionMs()
        // Step down: drop extra audio, then speed effects, then use the simple player.
        when {
            !simpleMode.value && withAudio && (p.audios.isNotEmpty() || p.overlays.isNotEmpty()) -> {
                withAudio = false
                error.value = "Xem trước không phát được các rãnh âm thanh trên máy này (vẫn có trong video xuất)."
                releaseComposition()
                rebuild(position)
            }
            !simpleMode.value && withSpeed && p.clips.any { it.speed != 1f } -> {
                withSpeed = false
                error.value = "Xem trước bỏ qua tốc độ (vẫn áp dụng khi xuất)."
                releaseComposition()
                rebuild(position)
            }
            !simpleMode.value -> {
                simpleMode.value = true
                error.value = "Chế độ xem trước đơn giản: hiệu ứng, chữ và bộ lọc chỉ hiện trong video xuất."
                releaseComposition()
                loadSimple(p, position)
            }
            else -> error.value = "Không phát được video: ${e.message}"
        }
    }

    private fun loadSimple(p: Project, positionMs: Long) {
        val ep = exo ?: ExoPlayer.Builder(context).build().also {
            it.addListener(listener)
            exo = it
            player.value = it
        }
        val items = p.clips.map { c ->
            val b = MediaItem.Builder().setUri(c.uri)
            if (c.isImage) {
                b.setImageDurationMs(c.durationMs)
                c.mime?.let { b.setMimeType(it) }
            } else {
                b.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.trimStartMs).setEndPositionMs(c.trimEndMs).build(),
                )
            }
            b.build()
        }
        if (ep.isScrubbingModeEnabled != scrubbers.isNotEmpty()) ep.setScrubbingModeEnabled(scrubbers.isNotEmpty())
        ep.setMediaItems(items)
        ep.prepare()
        seekSimple(p, positionMs)
    }

    fun play() {
        val p = player.value ?: return
        val proj = project ?: return
        if (p.playbackState == Player.STATE_ENDED || positionMs() >= proj.durationMs - 50) seekTo(0)
        p.play()
    }

    fun pause() {
        player.value?.pause()
    }

    /**
     * Fast, frame-accurate seeking for as long as some [key] (the timeline, a trim handle) moves
     * the picture with the finger. Pause first: Media3 resumes playback when scrubbing ends if it
     * was playing when it began.
     */
    fun setScrubbing(key: String, on: Boolean) {
        if (on) scrubbers += key else scrubbers -= key
        val enabled = scrubbers.isNotEmpty()
        if (enabled) player.value?.pause()
        composition?.let { if (it.isScrubbingModeEnabled != enabled) it.setScrubbingModeEnabled(enabled) }
        exo?.let { if (it.isScrubbingModeEnabled != enabled) it.setScrubbingModeEnabled(enabled) }
    }

    fun seekTo(timeMs: Long) {
        val p = project ?: return
        if (simpleMode.value) seekSimple(p, timeMs) else composition?.seekTo(timeMs.coerceIn(0, p.durationMs))
    }

    private fun seekSimple(p: Project, timeMs: Long) {
        val ep = exo ?: return
        val (index, offset) = p.clipAt(timeMs) ?: return
        val clip = p.clips[index]
        val local = if (clip.isImage) offset else (offset * clip.speed).toLong()
        ep.seekTo(index, local)
    }

    /** Current position on the project timeline. */
    fun positionMs(): Long {
        val p = project ?: return 0
        if (simpleMode.value) {
            val ep = exo ?: return 0
            val index = ep.currentMediaItemIndex
            if (index !in p.clips.indices) return 0
            val clip = p.clips[index]
            val local = ep.currentPosition
            return p.clipStartMs(index) + if (clip.isImage) local else (local / clip.speed).toLong()
        }
        return composition?.currentPosition ?: 0
    }

    private fun releaseComposition() {
        composition?.let {
            it.removeListener(listener)
            it.release()
        }
        composition = null
        if (!simpleMode.value) player.value = null
    }

    private fun releasePlayers() {
        releaseComposition()
        exo?.release()
        exo = null
        player.value = null
    }

    fun release() {
        pendingRebuild?.let { handler.removeCallbacks(it) }
        releasePlayers()
    }
}
