package com.cutviet.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.cutviet.editor.model.Adjustments
import com.cutviet.editor.model.Clip
import com.cutviet.editor.model.CropSpec
import com.cutviet.editor.model.FilterPreset
import com.cutviet.editor.model.AudioTrack
import com.cutviet.editor.model.Overlay
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.TextItem
import com.google.common.collect.ImmutableList
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns a [Project] into a Media3 [Composition], used both for preview and for export. */
object CompositionFactory {

    data class Options(
        val width: Int,
        val height: Int,
        val includeAudioTracks: Boolean = true,
        val includeOverlays: Boolean = true,
        val includeSpeed: Boolean = true,
        val hdrMode: Int = Composition.HDR_MODE_KEEP_HDR,
    )

    private val AUDIO_ONLY = setOf(C.TRACK_TYPE_AUDIO)
    private val VIDEO_ONLY = setOf(C.TRACK_TYPE_VIDEO)
    private val AUDIO_VIDEO = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)

    fun build(context: Context, project: Project, o: Options): Composition {
        val total = project.durationMs
        val main = EditedMediaItemSequence.Builder(AUDIO_VIDEO)
            .addItems(project.clips.map { clipItem(it, o) })
            .build()
        val videoSequences = mutableListOf<EditedMediaItemSequence>()
        val audioSequences = mutableListOf<EditedMediaItemSequence>()
        // Sequence index -> overlay drawn by that sequence (for the compositor settings).
        val overlayByIndex = HashMap<Int, Overlay>()

        val overlays = if (o.includeOverlays) project.overlays.filter { it.startMs < total && it.durationMs > 0 } else emptyList()
        if (overlays.isEmpty()) {
            videoSequences += main
        } else {
            // Media3's compositor draws the first sequence on top and takes output timestamps from it.
            // Sequence 0 is an invisible full-length frame clock at the source frame rate, then the overlays
            // (newest on top), then the main track at the bottom.
            val transparent = transparentImageUri(context)
            val fps = project.clips.filter { !it.isImage }.maxOfOrNull { it.frameRate }?.roundToInt()?.coerceIn(24, 120) ?: 30
            videoSequences += EditedMediaItemSequence.Builder(VIDEO_ONLY)
                .addItem(fillerItem(transparent, total, fps))
                .build()
            overlays.asReversed().forEach { ov ->
                val visibleMs = minOf(ov.durationMs, total - ov.startMs)
                val b = EditedMediaItemSequence.Builder(VIDEO_ONLY)
                if (ov.startMs > 0) b.addItem(fillerItem(transparent, ov.startMs, 30))
                b.addItem(overlayItem(ov, visibleMs, o))
                val after = total - ov.startMs - visibleMs
                if (after > 0) b.addItem(fillerItem(transparent, after, 30))
                overlayByIndex[videoSequences.size] = ov
                videoSequences += b.build()
                if (ov.hasAudio && !ov.isImage && ov.volume > 0f && o.includeAudioTracks) {
                    audioSequences += overlayAudioSequence(ov, visibleMs)
                }
            }
            videoSequences += main
        }

        if (o.includeAudioTracks) {
            project.audios.forEach { a -> audioTrackSequence(a, total)?.let { audioSequences += it } }
        }

        val builder = Composition.Builder(videoSequences + audioSequences).setHdrMode(o.hdrMode)
        if (overlayByIndex.isNotEmpty()) builder.setVideoCompositorSettings(LayerSettings(o.width, o.height, overlayByIndex))
        return builder.build()
    }

    /** Places each layer: the clock layer is hidden, overlays use their position/scale/opacity, the main track fills the frame. */
    private class LayerSettings(
        private val width: Int,
        private val height: Int,
        private val overlays: Map<Int, Overlay>,
    ) : VideoCompositorSettings {
        private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        private val full = StaticOverlaySettings.Builder().build()
        private val settings = overlays.mapValues { (_, ov) ->
            StaticOverlaySettings.Builder()
                .setAlphaScale(ov.opacity.coerceIn(0f, 1f))
                .setScale(ov.scale.coerceIn(0.05f, 4f), ov.scale.coerceIn(0.05f, 4f))
                .setRotationDegrees(-ov.rotation)
                .setBackgroundFrameAnchor(ov.x.coerceIn(0f, 1f) * 2 - 1, 1 - ov.y.coerceIn(0f, 1f) * 2)
                .build()
        }

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
            when (inputId) {
                0 -> hidden
                else -> settings[inputId] ?: full
            }
    }

    private fun fillerItem(uri: String, durationMs: Long, fps: Int): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(durationMs.coerceAtLeast(1)).build(),
        ).setFrameRate(fps).build()

    /** A fully transparent PNG used to fill time where a layer shows nothing. */
    private fun transparentImageUri(context: Context): String {
        val file = File(context.cacheDir, "transparent_16.png")
        if (!file.exists()) {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Uri.fromFile(file).toString()
    }

    private fun overlayItem(ov: Overlay, visibleMs: Long, o: Options): EditedMediaItem {
        val builder = MediaItem.Builder().setUri(ov.uri)
        if (ov.isImage) {
            builder.setImageDurationMs(visibleMs)
            ov.mime?.let { builder.setMimeType(it) }
        } else {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(ov.trimStartMs)
                    .setEndPositionMs(minOf(ov.trimEndMs, ov.trimStartMs + (visibleMs * ov.speed).toLong()))
                    .build(),
            )
        }
        val video = mutableListOf<Effect>()
        ov.chroma?.let { video += ChromaKeyEffect(it.color, it.strength, it.softness) }
        // Masks are drawn on the picture as the user sees it, so mirror their position when flipped.
        if (ov.mask != null || ov.eraseMask != null) {
            video += MaskEffect(ov.mask?.let { m -> if (ov.flipH) m.copy(cx = 1f - m.cx, angle = -m.angle) else m }, ov.eraseMask?.takeIf { File(it).exists() })
        }
        if (ov.flipH) video += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
        // Size the layer to "fit the frame"; the compositor then applies the user's scale.
        val k = minOf(o.width.toFloat() / ov.width.coerceAtLeast(1), o.height.toFloat() / ov.height.coerceAtLeast(1))
        video += Presentation.createForWidthAndHeight(
            even((ov.width * k).roundToInt()), even((ov.height * k).roundToInt()), Presentation.LAYOUT_STRETCH_TO_FIT,
        )
        val edited = EditedMediaItem.Builder(builder.build())
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), video))
        if (!ov.isImage) edited.setSourceDuration(ov.sourceDurationMs)
        if (ov.isImage) edited.setFrameRate(30)
        else if (ov.speed != 1f && o.includeSpeed) edited.setSpeed(ConstantSpeedProvider(ov.speed))
        return edited.build()
    }

    private fun overlayAudioSequence(ov: Overlay, visibleMs: Long): EditedMediaItemSequence {
        val item = MediaItem.Builder().setUri(ov.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(ov.trimStartMs)
                    .setEndPositionMs(minOf(ov.trimEndMs, ov.trimStartMs + (visibleMs * ov.speed).toLong()))
                    .build(),
            ).build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveVideo(true)
            .setEffects(Effects(listOfNotNull(volumeProcessor(ov.volume)), emptyList()))
            .setSourceDuration(ov.sourceDurationMs)
        if (ov.speed != 1f) edited.setSpeed(ConstantSpeedProvider(ov.speed))
        val b = EditedMediaItemSequence.Builder(AUDIO_ONLY)
        if (ov.startMs > 0) b.addGap(ov.startMs * 1000)
        return b.addItem(edited.build()).build()
    }

    /** One audio layer, starting at its timeline position and cut at the end of the video. */
    private fun audioTrackSequence(a: AudioTrack, totalMs: Long): EditedMediaItemSequence? {
        val available = totalMs - a.startMs
        if (available < 50 || a.durationMs <= 0) return null
        val b = EditedMediaItemSequence.Builder(AUDIO_ONLY)
        if (a.startMs > 0) b.addGap(a.startMs * 1000)
        var remaining = minOf(a.timelineLengthMs(totalMs), available)
        var count = 0
        while (remaining >= 50 && count < 200) {
            val passMs = minOf(remaining, a.durationMs)
            val item = MediaItem.Builder().setUri(a.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(a.sourceStartMs)
                        .setEndPositionMs(minOf(a.sourceEndMs, a.sourceStartMs + (passMs * a.speed).toLong()))
                        .build(),
                ).build()
            val edited = EditedMediaItem.Builder(item)
                .setRemoveVideo(true)
                .setEffects(Effects(listOfNotNull(volumeProcessor(a.volume)), emptyList()))
                .setSourceDuration(a.sourceDurationMs)
            if (a.speed != 1f) edited.setSpeed(ConstantSpeedProvider(a.speed))
            b.addItem(edited.build())
            remaining -= passMs
            count++
            if (!a.loop) break
        }
        return if (count == 0) null else b.build()
    }

    private fun even(v: Int) = max(2, v - v % 2)

    /**
     * CompositionPlayer needs the full length of every video/audio file up front to lay out the
     * timeline (images get it from their display duration).
     */
    private fun EditedMediaItem.Builder.setSourceDuration(ms: Long): EditedMediaItem.Builder =
        if (ms > 0) setDurationUs(ms * 1000) else this

    private fun clipItem(c: Clip, o: Options): EditedMediaItem {
        val builder = MediaItem.Builder().setUri(c.uri)
        if (c.isImage) {
            builder.setImageDurationMs(c.durationMs)
            c.mime?.let { builder.setMimeType(it) }
        } else {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimEndMs)
                    .build(),
            )
        }
        val mediaItem = builder.build()

        val video = mutableListOf<Effect>()
        val audio = mutableListOf<AudioProcessor>()

        if (c.rotation != 0 || c.flipH || c.flipV) {
            video += ScaleAndRotateTransformation.Builder()
                .setScale(if (c.flipH) -1f else 1f, if (c.flipV) -1f else 1f)
                // Media3 rotates counter-clockwise; the model stores clockwise degrees.
                .setRotationDegrees(((360 - c.rotation) % 360).toFloat())
                .build()
        }
        cropEffect(c)?.let { video += it }
        video += filterEffects(c.filter)
        video += adjustEffects(c.adjust)
        video += Presentation.createForWidthAndHeight(
            o.width, o.height,
            if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        if (c.texts.isNotEmpty()) {
            val overlays = c.texts.filter { it.text.isNotBlank() }.map { textOverlay(it, o.height) }
            if (overlays.isNotEmpty()) video += OverlayEffect(ImmutableList.copyOf(overlays))
        }

        volumeProcessor(c.volume)?.let { audio += it }

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audio, video))
        if (!c.isImage) edited.setSourceDuration(c.sourceDurationMs)
        if (c.isImage) edited.setFrameRate(30)
        else if (c.speed != 1f && o.includeSpeed) edited.setSpeed(ConstantSpeedProvider(c.speed))
        return edited.build()
    }

    private fun volumeProcessor(volume: Float): AudioProcessor? {
        if (volume == 1f) return null
        return ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(volume))
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(volume))
        }
    }

    /** Converts the crop window to Media3's normalized device coordinates. */
    private fun cropEffect(c: Clip): Effect? {
        val s: CropSpec = c.crop
        if (s.isIdentity) return null
        val w = c.rotatedWidth.toFloat().coerceAtLeast(1f)
        val h = c.rotatedHeight.toFloat().coerceAtLeast(1f)
        val aspect = s.aspect ?: (w / h)
        var cw: Float
        var ch: Float
        if (w / h > aspect) { ch = h; cw = h * aspect } else { cw = w; ch = w / aspect }
        val zoom = s.zoom.coerceAtLeast(1f)
        cw /= zoom; ch /= zoom
        val nw = cw / w
        val nh = ch / h
        val cx = s.centerX.coerceIn(nw / 2, 1 - nw / 2)
        val cy = s.centerY.coerceIn(nh / 2, 1 - nh / 2)
        val left = (cx - nw / 2) * 2 - 1
        val right = (cx + nw / 2) * 2 - 1
        val top = 1 - (cy - nh / 2) * 2
        val bottom = 1 - (cy + nh / 2) * 2
        return Crop(left, right, bottom, top)
    }

    fun filterEffects(f: FilterPreset): List<Effect> = when (f) {
        FilterPreset.NONE -> emptyList()
        FilterPreset.BW -> listOf(RgbFilter.createGrayscaleFilter())
        FilterPreset.INVERT -> listOf(RgbFilter.createInvertedFilter())
        FilterPreset.SEPIA -> listOf(ColorMatrixEffect(SEPIA), Contrast(0.05f))
        FilterPreset.WARM -> listOf(rgb(1.10f, 1.02f, 0.88f), HslAdjustment.Builder().adjustSaturation(8f).build())
        FilterPreset.COOL -> listOf(rgb(0.90f, 1.0f, 1.12f))
        FilterPreset.VIVID -> listOf(HslAdjustment.Builder().adjustSaturation(35f).build(), Contrast(0.12f))
        FilterPreset.FADE -> listOf(HslAdjustment.Builder().adjustSaturation(-35f).adjustLightness(6f).build(), Contrast(-0.2f))
        FilterPreset.DRAMA -> listOf(Contrast(0.3f), HslAdjustment.Builder().adjustSaturation(-20f).build())
        FilterPreset.NOIR -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.35f))
        FilterPreset.PINK -> listOf(rgb(1.10f, 0.94f, 1.04f), HslAdjustment.Builder().adjustLightness(4f).build())
    }

    private fun adjustEffects(a: Adjustments): List<Effect> {
        if (a.isIdentity) return emptyList()
        val list = mutableListOf<Effect>()
        if (a.brightness != 0f) list += Brightness(a.brightness.coerceIn(-1f, 1f) * 0.5f)
        if (a.contrast != 0f) list += Contrast(a.contrast.coerceIn(-1f, 1f) * 0.7f)
        if (a.saturation != 0f || a.hue != 0f) {
            list += HslAdjustment.Builder()
                .adjustSaturation(a.saturation.coerceIn(-1f, 1f) * 100f)
                .adjustHue(a.hue)
                .build()
        }
        if (a.warmth != 0f) {
            val k = a.warmth.coerceIn(-1f, 1f) * 0.18f
            list += rgb(1f + k, 1f, 1f - k)
        }
        return list
    }

    private fun rgb(r: Float, g: Float, b: Float): Effect =
        RgbAdjustment.Builder().setRedScale(r).setGreenScale(g).setBlueScale(b).build()

    private fun textOverlay(t: TextItem, outputHeight: Int): TextureOverlay {
        val text = SpannableString(t.text)
        val px = max(8, (t.size * outputHeight).roundToInt())
        val all = Spanned.SPAN_INCLUSIVE_INCLUSIVE
        text.setSpan(AbsoluteSizeSpan(px), 0, text.length, all)
        if (!t.isSticker) {
            text.setSpan(ForegroundColorSpan(t.color), 0, text.length, all)
            if (t.bold) text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, all)
            t.background?.let { text.setSpan(BackgroundColorSpan(it), 0, text.length, all) }
        }
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(t.x.coerceIn(0f, 1f) * 2 - 1, 1 - t.y.coerceIn(0f, 1f) * 2)
            .build()
        return TextOverlay.createStaticTextOverlay(text, settings)
    }

    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /** A fixed 4x4 colour matrix (column-major). */
    private class ColorMatrixEffect(private val matrix: FloatArray) : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }

    private val SEPIA = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f,
    )
}
