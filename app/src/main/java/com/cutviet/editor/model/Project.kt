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
 * Crop window inside the (rotated) frame. [aspect] null keeps the frame's own aspect ratio.
 * [centerX]/[centerY] are 0..1 with the origin at the top-left corner.
 */
data class CropSpec(
    val aspect: Float? = null,
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    val isIdentity: Boolean get() = aspect == null && zoom <= 1.001f
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

    /** Source position (ms) shown at [offsetMs] into this clip on the timeline. */
    fun sourceTimeAt(offsetMs: Long): Long =
        if (isImage) offsetMs else trimStartMs + (offsetMs * speed).toLong()
}

data class Music(
    val uri: String,
    val name: String,
    val sourceDurationMs: Long,
    val startMs: Long = 0,
    val volume: Float = 1f,
    val loop: Boolean = false,
    val fadeOut: Boolean = false,
)

data class Project(
    val id: String = newId(),
    val name: String,
    val clips: List<Clip> = emptyList(),
    val music: Music? = null,
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
