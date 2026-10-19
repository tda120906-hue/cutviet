package com.cutviet.editor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure editing operations on a [Project]. Each returns a new project (or the same one if nothing changed). */
object ProjectOps {
    const val MIN_CLIP_MS = 100L

    /** Splits the clip under timeline position [timeMs]. */
    fun splitAt(p: Project, timeMs: Long): Project {
        val (index, offset) = p.clipAt(timeMs) ?: return p
        val clip = p.clips[index]
        if (offset < MIN_CLIP_MS || clip.durationMs - offset < MIN_CLIP_MS) return p
        val cut = clip.sourceTimeAt(offset)
        val first = clip.copy(trimEndMs = cut)
        val second = clip.copy(id = newId(), trimStartMs = cut, texts = clip.texts.map { it.copy(id = newId()) })
        val clips = p.clips.toMutableList()
        clips[index] = first
        clips.add(index + 1, second)
        return p.copy(clips = clips)
    }

    fun delete(p: Project, index: Int): Project {
        if (index !in p.clips.indices) return p
        return p.copy(clips = p.clips.toMutableList().apply { removeAt(index) })
    }

    fun duplicate(p: Project, index: Int): Project {
        if (index !in p.clips.indices) return p
        val c = p.clips[index]
        val copy = c.copy(id = newId(), texts = c.texts.map { it.copy(id = newId()) })
        return p.copy(clips = p.clips.toMutableList().apply { add(index + 1, copy) })
    }

    fun move(p: Project, from: Int, to: Int): Project {
        if (from !in p.clips.indices || to !in p.clips.indices || from == to) return p
        val list = p.clips.toMutableList()
        val c = list.removeAt(from)
        list.add(to, c)
        return p.copy(clips = list)
    }

    fun insert(p: Project, at: Int, newClips: List<Clip>): Project {
        val list = p.clips.toMutableList()
        list.addAll(at.coerceIn(0, list.size), newClips)
        return p.copy(clips = list)
    }

    fun updateClip(p: Project, index: Int, f: (Clip) -> Clip): Project {
        if (index !in p.clips.indices) return p
        return p.copy(clips = p.clips.toMutableList().apply { this[index] = f(this[index]) })
    }

    fun updateAll(p: Project, f: (Clip) -> Clip): Project = p.copy(clips = p.clips.map(f))

    /** Clamps a trim to the source and the minimum clip length. */
    fun trim(c: Clip, startMs: Long, endMs: Long): Clip {
        if (c.isImage) return c.copy(trimStartMs = 0, trimEndMs = max(MIN_CLIP_MS, endMs - startMs))
        val s = startMs.coerceIn(0, max(0, c.sourceDurationMs - MIN_CLIP_MS))
        val e = endMs.coerceIn(s + MIN_CLIP_MS, c.sourceDurationMs)
        return c.copy(trimStartMs = s, trimEndMs = e)
    }

    /**
     * Moves the audio of clip [index] onto its own track at the same timeline position and mutes the clip,
     * so the sound can be moved, trimmed and adjusted on its own.
     */
    fun extractAudio(p: Project, index: Int, name: String): Project {
        val c = p.clips.getOrNull(index) ?: return p
        if (c.isImage || !c.hasAudio) return p
        val track = AudioTrack(
            kind = AudioKind.EXTRACTED, uri = c.uri, name = name, sourceDurationMs = c.sourceDurationMs,
            sourceStartMs = c.trimStartMs, sourceEndMs = c.trimEndMs, startMs = p.clipStartMs(index),
            volume = if (c.volume == 0f) 1f else c.volume, speed = c.speed,
        )
        return updateClip(p, index) { it.copy(volume = 0f) }.copy(audios = p.audios + track)
    }

    /**
     * Moves an overlay up (+1) or down (-1) in the stack. Later overlays are drawn on top. Returns
     * [p] itself when it is already at the top/bottom.
     */
    /** One dubbed sentence: where it was spoken in the clip's source, and its audio file. */
    data class DubLine(val sourceStartMs: Long, val uri: String, val durationMs: Long, val text: String, val speed: Float = 1f)

    /**
     * The clip's sound split in two: [voiceUri] and [backgroundUri] start at [sourceStartMs] of the
     * clip's source and last [durationMs]; together they equal the original sound.
     */
    data class DubStems(val voiceUri: String, val backgroundUri: String, val sourceStartMs: Long, val durationMs: Long)

    /**
     * Puts dubbed sentences of clip [index] on the timeline as DUB tracks at [dubVolume], replacing
     * an earlier dub of that clip. With [stems] the clip is muted and its background plays at full
     * volume with the original voice at [originalVolume] (0 removes it); without, the whole clip's
     * sound is set to [originalVolume]. Sentences outside the clip's trim are dropped.
     */
    fun applyDub(
        p: Project, index: Int, lines: List<DubLine>, originalVolume: Float,
        stems: DubStems? = null, dubVolume: Float = 1f,
    ): Project {
        val c = p.clips.getOrNull(index) ?: return p
        val clipStart = p.clipStartMs(index)
        val clipEnd = clipStart + c.durationMs
        fun timelineAt(sourceMs: Long) = clipStart + ((sourceMs - c.trimStartMs) / c.speed).toLong()
        val tracks = lines.filter { it.sourceStartMs in c.trimStartMs until c.trimEndMs && it.durationMs > 0 }.map {
            AudioTrack(
                kind = AudioKind.DUB, uri = it.uri, name = it.text.trim().take(40), sourceDurationMs = it.durationMs,
                startMs = timelineAt(it.sourceStartMs), volume = dubVolume.coerceIn(0f, 4f), speed = it.speed.coerceIn(0.5f, 2f),
            )
        }
        val stemTracks = stems?.let { st ->
            // The stems cover the clip's trim; play them at the clip's speed so they stay in sync.
            val from = (c.trimStartMs - st.sourceStartMs).coerceIn(0, st.durationMs)
            val to = (c.trimEndMs - st.sourceStartMs).coerceIn(from, st.durationMs)
            val start = timelineAt(st.sourceStartMs + from)
            listOf(
                AudioTrack(
                    kind = AudioKind.BACKGROUND, uri = st.backgroundUri, name = "Nền: ${c.uri.substringAfterLast('/').take(30)}",
                    sourceDurationMs = st.durationMs, sourceStartMs = from, sourceEndMs = to, startMs = start, speed = c.speed,
                ),
                AudioTrack(
                    kind = AudioKind.SOURCE_VOICE, uri = st.voiceUri, name = "Giọng gốc: ${c.uri.substringAfterLast('/').take(30)}",
                    sourceDurationMs = st.durationMs, sourceStartMs = from, sourceEndMs = to, startMs = start, speed = c.speed,
                    volume = originalVolume.coerceIn(0f, 2f),
                ),
            )
        }.orEmpty()
        val replaced = setOf(AudioKind.DUB, AudioKind.BACKGROUND, AudioKind.SOURCE_VOICE)
        val kept = p.audios.filterNot { it.kind in replaced && it.startMs in clipStart until clipEnd }
        val clipVolume = if (stems != null) 0f else originalVolume.coerceIn(0f, 2f)
        val clips = p.clips.toMutableList().also { it[index] = c.copy(volume = clipVolume) }
        return p.copy(clips = clips, audios = kept + stemTracks + tracks)
    }

    fun restackOverlay(p: Project, id: String, delta: Int): Project {
        val i = p.overlays.indexOfFirst { it.id == id }
        if (i < 0) return p
        val to = (i + delta).coerceIn(0, p.overlays.lastIndex)
        if (to == i) return p
        val list = p.overlays.toMutableList()
        list.add(to, list.removeAt(i))
        return p.copy(overlays = list)
    }

    fun updateOverlay(p: Project, id: String, f: (Overlay) -> Overlay): Project =
        p.copy(overlays = p.overlays.map { if (it.id == id) f(it) else it })

    fun updateAudio(p: Project, id: String, f: (AudioTrack) -> AudioTrack): Project =
        p.copy(audios = p.audios.map { if (it.id == id) f(it) else it })

    /** Trims an overlay's source range, keeping it inside the source and at least [MIN_CLIP_MS] long. */
    fun trimOverlay(o: Overlay, startMs: Long, endMs: Long): Overlay {
        if (o.isImage) return o.copy(trimStartMs = 0, trimEndMs = max(MIN_CLIP_MS, endMs - startMs))
        val s = startMs.coerceIn(0, max(0, o.sourceDurationMs - MIN_CLIP_MS))
        val e = endMs.coerceIn(s + MIN_CLIP_MS, o.sourceDurationMs)
        return o.copy(trimStartMs = s, trimEndMs = e)
    }

    fun trimAudio(a: AudioTrack, startMs: Long, endMs: Long): AudioTrack {
        val s = startMs.coerceIn(0, max(0, a.sourceDurationMs - MIN_CLIP_MS))
        val e = endMs.coerceIn(s + MIN_CLIP_MS, a.sourceDurationMs)
        return a.copy(sourceStartMs = s, sourceEndMs = e)
    }

    /**
     * Drags the left edge of an overlay on the timeline by [deltaMs]: the start moves and the source
     * start moves with it, so the content stays anchored in time (like stretching a layer in CapCut).
     */
    fun stretchOverlayStart(o: Overlay, deltaMs: Long): Overlay {
        val newStart = (o.startMs + deltaMs).coerceAtLeast(0)
        val d = newStart - o.startMs
        if (o.isImage) {
            val dur = (o.trimmedMs - d).coerceAtLeast(MIN_CLIP_MS)
            return o.copy(startMs = o.endMs - dur, trimStartMs = 0, trimEndMs = dur)
        }
        val src = (o.trimStartMs + (d * o.speed).toLong()).coerceIn(0, o.trimEndMs - MIN_CLIP_MS)
        val realDelta = ((src - o.trimStartMs) / o.speed).toLong()
        return o.copy(startMs = o.startMs + realDelta, trimStartMs = src)
    }

    /** Drags the right edge of an overlay by [deltaMs]. */
    fun stretchOverlayEnd(o: Overlay, deltaMs: Long): Overlay {
        if (o.isImage) return o.copy(trimStartMs = 0, trimEndMs = (o.trimmedMs + deltaMs).coerceAtLeast(MIN_CLIP_MS))
        val e = (o.trimEndMs + (deltaMs * o.speed).toLong()).coerceIn(o.trimStartMs + MIN_CLIP_MS, o.sourceDurationMs)
        return o.copy(trimEndMs = e)
    }

    fun stretchAudioStart(a: AudioTrack, deltaMs: Long): AudioTrack {
        val newStart = (a.startMs + deltaMs).coerceAtLeast(0)
        val d = newStart - a.startMs
        val src = (a.sourceStartMs + (d * a.speed).toLong()).coerceIn(0, a.sourceEndMs - MIN_CLIP_MS)
        val realDelta = ((src - a.sourceStartMs) / a.speed).toLong()
        return a.copy(startMs = a.startMs + realDelta, sourceStartMs = src)
    }

    fun stretchAudioEnd(a: AudioTrack, deltaMs: Long): AudioTrack {
        val e = (a.sourceEndMs + (deltaMs * a.speed).toLong()).coerceIn(a.sourceStartMs + MIN_CLIP_MS, a.sourceDurationMs)
        return a.copy(sourceEndMs = e)
    }

    /** Longest an image can be stretched to on the timeline. */
    const val MAX_IMAGE_MS = 30 * 60_000L

    /**
     * Drags the left edge of a main-track clip by [deltaMs] (timeline time). The clip keeps its
     * place in the sequence, so this trims (or restores) its beginning; an image just gets shorter
     * or longer.
     */
    fun stretchClipStart(c: Clip, deltaMs: Long): Clip {
        if (c.isImage) return c.copy(trimStartMs = 0, trimEndMs = (c.trimmedMs - deltaMs).coerceIn(MIN_CLIP_MS, MAX_IMAGE_MS))
        val s = (c.trimStartMs + (deltaMs * c.speed).toLong()).coerceIn(0, c.trimEndMs - MIN_CLIP_MS)
        return c.copy(trimStartMs = s)
    }

    /** Drags the right edge of a main-track clip by [deltaMs]; images can be made as long as needed. */
    fun stretchClipEnd(c: Clip, deltaMs: Long): Clip {
        if (c.isImage) return c.copy(trimStartMs = 0, trimEndMs = (c.trimmedMs + deltaMs).coerceIn(MIN_CLIP_MS, MAX_IMAGE_MS))
        val e = (c.trimEndMs + (deltaMs * c.speed).toLong()).coerceIn(c.trimStartMs + MIN_CLIP_MS, c.sourceDurationMs)
        return c.copy(trimEndMs = e)
    }

    /** Returns [t] moved onto the nearest of [points] when it is within [thresholdMs], otherwise [t]. */
    fun snap(t: Long, points: List<Long>, thresholdMs: Long): Long {
        val best = points.minByOrNull { abs(it - t) } ?: return t
        return if (abs(best - t) <= thresholdMs) best else t
    }

    /** Edges a dragged layer can snap to: start of the video, clip boundaries, the playhead and other overlays. */
    fun snapPoints(p: Project, excludeOverlayId: String?, playheadMs: Long): List<Long> = buildList {
        add(0L)
        add(playheadMs)
        var t = 0L
        for (c in p.clips) {
            t += c.durationMs
            add(t)
        }
        for (o in p.overlays) {
            if (o.id == excludeOverlayId) continue
            add(o.startMs)
            add(o.endMs)
        }
    }

    /**
     * Time in the overlay's source file shown at timeline time [timelineMs] (its first frame before
     * it starts, its last frame after it ends).
     */
    fun overlaySourceTimeMs(o: Overlay, timelineMs: Long): Long {
        if (o.isImage) return 0
        val offset = (timelineMs - o.startMs).coerceIn(0, o.durationMs)
        return (o.trimStartMs + (offset * o.speed).toLong()).coerceIn(o.trimStartMs, max(o.trimStartMs, o.trimEndMs - 1))
    }

    /** Puts items into rows so that items in the same row never overlap in time. */
    fun <T> lanes(items: List<T>, start: (T) -> Long, end: (T) -> Long): List<List<T>> {
        val rows = mutableListOf<MutableList<T>>()
        val rowEnds = mutableListOf<Long>()
        for (item in items.sortedBy(start)) {
            val i = rowEnds.indexOfFirst { it <= start(item) }
            if (i >= 0) {
                rows[i].add(item); rowEnds[i] = end(item)
            } else {
                rows.add(mutableListOf(item)); rowEnds.add(end(item))
            }
        }
        return rows
    }

    /**
     * Output canvas size at original quality: keeps the long side of the first clip. With the
     * original ratio ("Gốc") the frame takes the shape of that clip after cropping.
     */
    fun canvasSize(p: Project): Pair<Int, Int> {
        val base = (p.clips.firstOrNull { !it.isImage } ?: p.clips.firstOrNull())?.takeIf { it.width > 0 && it.height > 0 }
        var w = base?.rotatedWidth ?: 1080
        var h = base?.rotatedHeight ?: 1920
        // The crop's shape from the window itself (pixel counts are rounded and would skew it).
        val ratio = p.aspect.value
            ?: base?.takeIf { !it.crop.isFull }?.let { b ->
                b.crop.aspect ?: (b.rotatedWidth * b.crop.width) / (b.rotatedHeight * b.crop.height).coerceAtLeast(1e-3f)
            }
        if (ratio != null) {
            // The long side stays the same so cropping or changing the ratio never lowers quality.
            val longSide = max(w, h)
            if (ratio >= 1f) {
                w = longSide; h = nearestEven(longSide / ratio)
            } else {
                h = longSide; w = nearestEven(longSide * ratio)
            }
        }
        return even(w) to even(h)
    }

    private fun nearestEven(v: Float): Int = max(2, (v / 2f).roundToInt() * 2)

    /** Scales [size] down so that its short side is at most [shortSide] (never up). */
    fun scaleToShortSide(size: Pair<Int, Int>, shortSide: Int?): Pair<Int, Int> {
        if (shortSide == null) return size
        val (w, h) = size
        val s = min(w, h)
        if (s <= shortSide) return size
        val k = shortSide.toFloat() / s
        return even((w * k).roundToInt()) to even((h * k).roundToInt())
    }

    /** Scales [size] down so its long side is at most [longSide]. */
    fun fitLongSide(size: Pair<Int, Int>, longSide: Int): Pair<Int, Int> {
        val (w, h) = size
        val l = max(w, h)
        if (l <= longSide) return size
        val k = longSide.toFloat() / l
        return even((w * k).roundToInt()) to even((h * k).roundToInt())
    }

    private fun even(v: Int): Int = max(2, v - v % 2)

    /**
     * Video bitrate that keeps original quality: the highest source bitrate (scaled by output pixels),
     * never below a resolution-based floor so re-encoding does not visibly degrade the picture.
     */
    fun targetBitrate(p: Project, size: Pair<Int, Int>, quality: ExportQuality): Int {
        val outPixels = size.first.toLong() * size.second
        val fps = p.clips.filter { !it.isImage }.maxOfOrNull { it.frameRate }?.takeIf { it > 0 } ?: 30f
        var source = 0.0
        for (c in p.clips) {
            if (c.isImage || c.bitrate <= 0) continue
            val px = max(1L, c.width.toLong() * c.height)
            val scaled = c.bitrate * min(1.0, outPixels.toDouble() / px)
            source = max(source, scaled)
        }
        // ~0.12 bits per pixel per frame is visually transparent for H.264 at these resolutions.
        val floor = outPixels * fps * 0.12
        val base = max(source * 1.05, floor)
        val bitrate = base * quality.factor
        return bitrate.coerceIn(1_000_000.0, 150_000_000.0).toInt()
    }
}

enum class ExportResolution(val label: String, val shortSide: Int?) {
    ORIGINAL("Gốc", null),
    P2160("4K", 2160),
    P1440("2K", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
}

enum class ExportQuality(val label: String, val factor: Double) {
    ORIGINAL("Gốc", 1.0),
    HIGH("Cao nhất", 1.6),
    SMALL("Tiết kiệm", 0.55),
}

enum class ExportCodec(val label: String) {
    H264("H.264"),
    H265("H.265 (HEVC)"),
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val quality: ExportQuality = ExportQuality.ORIGINAL,
    val codec: ExportCodec = ExportCodec.H264,
)
