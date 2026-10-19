package com.cutviet.editor.model

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class FilterPreset(val label: String) {
    NONE("Gốc"),
    BW("Đen trắng"),
    SEPIA("Cổ điển"),
    WARM("Ấm áp"),
    COOL("Mát lạnh"),
    VIVID("Rực rỡ"),
    FADE("Phai màu"),
    DRAMA("Kịch tính"),
    NOIR("Noir"),
    PINK("Hồng"),
    INVERT("Âm bản"),
}

enum class AspectRatio(val label: String, val value: Float?) {
    ORIGINAL("Gốc", null),
    R9_16("9:16", 9f / 16f),
    R16_9("16:9", 16f / 9f),
    R1_1("1:1", 1f),
    R4_5("4:5", 4f / 5f),
    R3_4("3:4", 3f / 4f),
    R4_3("4:3", 4f / 3f),
    R21_9("21:9", 21f / 9f),
}

/** Colour adjustments, each in -1..1 (hue in degrees -180..180). */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val hue: Float = 0f,
) {
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f && hue == 0f
}

/**
 * Crop window and fine rotation of a clip. The window is in the "turned" frame: the picture after
 * its 90° rotation, flips and straightening, as the user sees it in the crop editor; 0..1 from the
 * top-left corner.
 */
data class CropSpec(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    /** Width/height ratio (in pixels) the window is locked to in the editor; null = free. */
    val aspect: Float? = null,
    /**
     * Fine rotation, clockwise degrees in -45..45. The picture is enlarged just enough that the
     * frame stays filled (no empty corners).
     */
    val straighten: Float = 0f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when the window is the whole frame. */
    val isFull: Boolean
        get() = left <= EDGE && top <= EDGE && right >= 1f - EDGE && bottom >= 1f - EDGE

    val isIdentity: Boolean get() = isFull && straighten == 0f

    companion object {
        private const val EDGE = 0.0005f
    }
}

/** Text or sticker drawn on top of a clip. Size is a fraction of the output height; x/y are 0..1 from top-left. */
data class TextItem(
    val id: String = newId(),
    val text: String,
    val color: Int = 0xFFFFFFFF.toInt(),
    val size: Float = 0.06f,
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    val bold: Boolean = true,
    val background: Int? = null,
    val isSticker: Boolean = false,
)

data class Clip(
    val id: String = newId(),
    val uri: String,
    val mime: String?,
    val isImage: Boolean,
    /** Full length of the source in ms (for images: the default display length). */
    val sourceDurationMs: Long,
    /** Display size of the source, metadata rotation already applied. */
    val width: Int,
    val height: Int,
    val bitrate: Int = 0,
    val frameRate: Float = 30f,
    val hasAudio: Boolean = true,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    /** Clockwise rotation in degrees: 0, 90, 180, 270. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropSpec = CropSpec(),
    val filter: FilterPreset = FilterPreset.NONE,
    val adjust: Adjustments = Adjustments(),
    val texts: List<TextItem> = emptyList(),
    /** true = fill the canvas (crop edges), false = fit inside (letterbox). */
    val fill: Boolean = false,
) {
    val trimmedMs: Long get() = trimEndMs - trimStartMs

    /** Length of this clip on the timeline. */
    val durationMs: Long get() = if (isImage) trimmedMs else (trimmedMs / speed).toLong()

    val rotatedWidth: Int get() = if (rotation % 180 == 0) width else height
    val rotatedHeight: Int get() = if (rotation % 180 == 0) height else width

    /** Size of the picture after cropping, in source pixels. */
    val croppedWidth: Int get() = maxOf(2, Math.round(rotatedWidth * crop.width))
    val croppedHeight: Int get() = maxOf(2, Math.round(rotatedHeight * crop.height))

    /** Source position (ms) shown at [offsetMs] into this clip on the timeline. */
    fun sourceTimeAt(offsetMs: Long): Long =
        if (isImage) offsetMs else trimStartMs + (offsetMs * speed).toLong()
}

enum class AudioKind(val label: String) {
    MUSIC("Nhạc"),
    EXTRACTED("Âm thanh tách"),
    VOICE("Giọng đọc"),
    /** One translated, dubbed sentence (made by the dubbing job). */
    DUB("Lồng tiếng"),
}

/** Settings used to generate a text-to-speech track, kept so it can be edited and regenerated. */
data class VoiceSpec(
    val text: String,
    val voiceName: String?,
    val pitch: Float = 1f,
    val rate: Float = 1f,
)

/** An audio layer placed on the timeline (music, audio extracted from a clip, or a voice-over). */
data class AudioTrack(
    val id: String = newId(),
    val kind: AudioKind,
    val uri: String,
    val name: String,
    val sourceDurationMs: Long,
    val sourceStartMs: Long = 0,
    val sourceEndMs: Long = sourceDurationMs,
    /** Position on the timeline where this track starts. */
    val startMs: Long = 0,
    val volume: Float = 1f,
    val speed: Float = 1f,
    /** Repeat until the end of the video (music only). */
    val loop: Boolean = false,
    val voice: VoiceSpec? = null,
) {
    /** Length of one pass on the timeline. */
    val durationMs: Long get() = ((sourceEndMs - sourceStartMs) / speed).toLong()

    /** Length on the timeline including looping, before clipping to the project end. */
    fun timelineLengthMs(projectDurationMs: Long): Long =
        if (loop) (projectDurationMs - startMs).coerceAtLeast(0) else durationMs
}

/** Colour key used to remove a solid background (green/blue screen) from an overlay. */
data class ChromaKey(
    val color: Int = 0xFF00FF00.toInt(),
    /** 0..1, how far from the key colour still counts as background. */
    val strength: Float = 0.35f,
    /** 0..1, width of the soft edge. */
    val softness: Float = 0.15f,
)

/** Shape used to cut out part of an overlay ("Mặt nạ"). */
enum class MaskShape(val label: String) {
    CIRCLE("Tròn"),
    RECT("Chữ nhật"),
    HEART("Trái tim"),
    STAR("Ngôi sao"),
    LINEAR("Đường thẳng"),
    MIRROR("Dải gương"),
}

/** Keeps only the part of an overlay inside a shape (or outside it when [invert]). */
data class ShapeMask(
    val shape: MaskShape = MaskShape.CIRCLE,
    /** Centre of the shape, 0..1 from the top-left of the overlay. */
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** 0..1, 1 = as large as the overlay's shorter side (rectangle: the whole overlay). */
    val size: Float = 0.7f,
    /** 0..1, width of the soft edge. */
    val feather: Float = 0.1f,
    /** Clockwise degrees. */
    val angle: Float = 0f,
    /** 0..1, corner rounding of the rectangle. */
    val roundness: Float = 0.2f,
    val invert: Boolean = false,
)

/** A picture-in-picture layer (video or image) drawn above the main track. */
data class Overlay(
    val id: String = newId(),
    val uri: String,
    /** File name shown on the timeline. */
    val name: String = "",
    val mime: String?,
    val isImage: Boolean,
    val sourceDurationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean = true,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val startMs: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    /** Centre position, 0..1 from the top-left of the frame. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Size relative to "fit the whole frame". */
    val scale: Float = 0.5f,
    /** Clockwise degrees. */
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val flipH: Boolean = false,
    val chroma: ChromaKey? = null,
    val mask: ShapeMask? = null,
    /** PNG painted with the eraser: its alpha is how much of the overlay stays visible. */
    val eraseMask: String? = null,
) {
    val trimmedMs: Long get() = trimEndMs - trimStartMs
    val durationMs: Long get() = if (isImage) trimmedMs else (trimmedMs / speed).toLong()
    val endMs: Long get() = startMs + durationMs
}

data class Project(
    val id: String = newId(),
    val name: String,
    val clips: List<Clip> = emptyList(),
    val overlays: List<Overlay> = emptyList(),
    val audios: List<AudioTrack> = emptyList(),
    val aspect: AspectRatio = AspectRatio.ORIGINAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    fun clipStartMs(index: Int): Long {
        var t = 0L
        for (i in 0 until index.coerceAtMost(clips.size)) t += clips[i].durationMs
        return t
    }

    /** Index of the clip under timeline position [timeMs] and the offset into it. */
    fun clipAt(timeMs: Long): Pair<Int, Long>? {
        if (clips.isEmpty()) return null
        var start = 0L
        clips.forEachIndexed { i, c ->
            val end = start + c.durationMs
            if (timeMs < end || i == clips.lastIndex) {
                return i to (timeMs - start).coerceIn(0L, c.durationMs)
            }
            start = end
        }
        return null
    }
}
