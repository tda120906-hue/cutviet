package com.cutviet.editor.media

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
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
import com.cutviet.editor.model.Music
import com.cutviet.editor.model.Project
import com.cutviet.editor.model.TextItem
import com.google.common.collect.ImmutableList
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns a [Project] into a Media3 [Composition], used both for preview and for export. */
object CompositionFactory {

    data class Options(
        val width: Int,
        val height: Int,
        val includeMusic: Boolean = true,
        val includeSpeed: Boolean = true,
        val hdrMode: Int = Composition.HDR_MODE_KEEP_HDR,
    )

    fun build(project: Project, o: Options): Composition {
        val items = project.clips.map { clipItem(it, o) }
        val sequences = mutableListOf(EditedMediaItemSequence(items, false))
        val music = project.music
        if (o.includeMusic && music != null && project.durationMs > 0) {
            sequences += musicSequence(music, project.durationMs)
        }
        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .setHdrMode(o.hdrMode)
            .build()
    }

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

        if (!c.isImage && c.speed != 1f && o.includeSpeed) {
            val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeedProvider(c.speed))
            audio += pair.first
            video += pair.second
        }
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
        if (c.isImage) {
            edited.setFrameRate(30)
            @Suppress("DEPRECATION")
            edited.setDurationUs(c.durationMs * 1000)
        }
        return edited.build()
    }

    private fun musicSequence(m: Music, videoDurationMs: Long): EditedMediaItemSequence {
        val available = m.sourceDurationMs - m.startMs
        val end = if (m.loop) m.sourceDurationMs else m.startMs + minOf(available, videoDurationMs)
        val item = MediaItem.Builder().setUri(m.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(m.startMs.coerceAtLeast(0))
                    .setEndPositionMs(max(m.startMs + 100, end))
                    .build(),
            ).build()
        val audio = listOfNotNull(volumeProcessor(m.volume))
        val edited = EditedMediaItem.Builder(item)
            .setRemoveVideo(true)
            .setEffects(Effects(audio, emptyList()))
            .build()
        return EditedMediaItemSequence(listOf(edited), m.loop)
    }

    private fun volumeProcessor(volume: Float): AudioProcessor? {
        if (volume == 1f) return null
        return ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(volume))
            putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(volume))
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
        val settings = OverlaySettings.Builder()
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
