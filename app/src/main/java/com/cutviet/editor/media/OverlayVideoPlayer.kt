package com.cutviet.editor.media

import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cutviet.editor.model.Overlay
import java.io.File
import kotlin.math.abs

/**
 * Plays one video overlay on the editor preview while the project plays, kept in step with the
 * main player. Muted: the overlay's sound comes from the main player.
 */
class OverlayVideoPlayer(private val context: Context, private val overlay: Overlay) {
    /** True once a frame is on screen. */
    val showingFrame = mutableStateOf(false)
    /** True if the device cannot play this overlay here (the still picture is used instead). */
    val failed = mutableStateOf(false)

    private var player: ExoPlayer? = null
    private var view: TextureView? = null
    private var withEffects = true
    private var lastSeekAt = 0L

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            showingFrame.value = true
        }

        override fun onPlayerError(error: PlaybackException) {
            val position = player?.currentPosition ?: 0
            releasePlayer()
            if (withEffects && effects().isNotEmpty()) {
                // Play it without background removal/mask rather than not at all.
                withEffects = false
                create(position)
            } else {
                failed.value = true
            }
        }
    }

    init {
        create(0)
    }

    private fun effects(): List<Effect> {
        val list = mutableListOf<Effect>()
        overlay.chroma?.let { list += ChromaKeyEffect(it.color, it.strength, it.softness) }
        val erase = overlay.eraseMask?.takeIf { File(it).exists() }
        val mask = OverlayPixels.unflippedMask(overlay)
        if (mask != null || erase != null) list += MaskEffect(mask, erase)
        if (list.isNotEmpty()) list += PremultiplyEffect()
        return list
    }

    private fun create(positionMs: Long) {
        val p = runCatching { ExoPlayer.Builder(context).build() }.getOrElse {
            failed.value = true
            return
        }
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        if (withEffects) effects().takeIf { it.isNotEmpty() }?.let { p.setVideoEffects(it) }
        p.setMediaItem(
            MediaItem.Builder().setUri(overlay.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(overlay.trimStartMs)
                        .setEndPositionMs(overlay.trimEndMs)
                        .build(),
                ).build(),
        )
        p.playbackParameters = PlaybackParameters(overlay.speed)
        p.addListener(listener)
        view?.let { p.setVideoTextureView(it) }
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        player = p
    }

    fun attach(textureView: TextureView) {
        if (view === textureView) return
        view = textureView
        player?.setVideoTextureView(textureView)
    }

    /** Follows the main player: plays while the timeline is inside the overlay, at the matching spot. */
    fun sync(timelineMs: Long, playing: Boolean) {
        val p = player ?: return
        val inside = timelineMs >= overlay.startMs && timelineMs < overlay.endMs
        if (!playing || !inside) {
            if (p.playWhenReady) p.pause()
            if (!inside && timelineMs < overlay.startMs && p.currentPosition > 0) p.seekTo(0)
            return
        }
        // Position inside the trimmed part, in source time.
        val expected = ((timelineMs - overlay.startMs) * overlay.speed).toLong()
        val now = SystemClock.uptimeMillis()
        val drift = abs(p.currentPosition - expected)
        // Re-align when it falls behind (e.g. while starting up), but not over and over while it seeks.
        if (drift > 250 && p.playbackState != Player.STATE_BUFFERING && now - lastSeekAt > 1000) {
            p.seekTo(expected)
            lastSeekAt = now
        }
        if (!p.playWhenReady) p.play()
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    fun release() {
        releasePlayer()
        view = null
    }
}
