package com.cutviet.editor.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves drafts as JSON files in the app's private storage. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? =
        File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun save(p: Project) {
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.history").delete()
    }

    /** Saves the undo/redo steps next to the draft so they survive closing the project. */
    fun saveHistory(id: String, undo: List<Project>, redo: List<Project>) {
        val json = JSONObject().apply {
            put("undo", JSONArray().apply { undo.forEach { put(toJson(it)) } })
            put("redo", JSONArray().apply { redo.forEach { put(toJson(it)) } })
        }
        val tmp = File(dir, "$id.history.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "$id.history"))
    }

    fun loadHistory(id: String): Pair<List<Project>, List<Project>> {
        val f = File(dir, "$id.history")
        if (!f.exists()) return emptyList<Project>() to emptyList()
        return runCatching {
            val o = JSONObject(f.readText())
            fun list(key: String): List<Project> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { i -> runCatching { fromJson(a.getJSONObject(i)) }.getOrNull() }
            }
            list("undo") to list("redo")
        }.getOrDefault(emptyList<Project>() to emptyList())
    }

    companion object {
        fun toJson(p: Project): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("aspect", p.aspect.name)
            put("createdAt", p.createdAt)
            put("updatedAt", p.updatedAt)
            put("clips", JSONArray().apply { p.clips.forEach { put(clipJson(it)) } })
            put("overlays", JSONArray().apply { p.overlays.forEach { put(overlayJson(it)) } })
            put("audios", JSONArray().apply { p.audios.forEach { put(audioJson(it)) } })
        }

        private fun overlayJson(o: Overlay) = JSONObject().apply {
            put("id", o.id); put("uri", o.uri); put("name", o.name); put("mime", o.mime ?: ""); put("isImage", o.isImage)
            put("sourceDurationMs", o.sourceDurationMs); put("width", o.width); put("height", o.height)
            put("hasAudio", o.hasAudio); put("trimStartMs", o.trimStartMs); put("trimEndMs", o.trimEndMs)
            put("startMs", o.startMs); put("speed", o.speed.toDouble()); put("volume", o.volume.toDouble())
            put("x", o.x.toDouble()); put("y", o.y.toDouble()); put("scale", o.scale.toDouble())
            put("rotation", o.rotation.toDouble()); put("opacity", o.opacity.toDouble()); put("flipH", o.flipH)
            o.chroma?.let { k ->
                put("chroma", JSONObject().apply {
                    put("color", k.color); put("strength", k.strength.toDouble()); put("softness", k.softness.toDouble())
                })
            }
            o.mask?.let { m ->
                put("mask", JSONObject().apply {
                    put("shape", m.shape.name); put("cx", m.cx.toDouble()); put("cy", m.cy.toDouble())
                    put("size", m.size.toDouble()); put("feather", m.feather.toDouble()); put("angle", m.angle.toDouble())
                    put("roundness", m.roundness.toDouble()); put("invert", m.invert)
                })
            }
            o.eraseMask?.let { put("eraseMask", it) }
        }

        private fun audioJson(a: AudioTrack) = JSONObject().apply {
            put("id", a.id); put("kind", a.kind.name); put("uri", a.uri); put("name", a.name)
            put("sourceDurationMs", a.sourceDurationMs); put("sourceStartMs", a.sourceStartMs)
            put("sourceEndMs", a.sourceEndMs); put("startMs", a.startMs); put("volume", a.volume.toDouble())
            put("speed", a.speed.toDouble()); put("loop", a.loop)
            a.voice?.let { v ->
                put("voice", JSONObject().apply {
                    put("text", v.text); put("voiceName", v.voiceName ?: ""); put("pitch", v.pitch.toDouble())
                    put("rate", v.rate.toDouble())
                })
            }
        }

        private fun clipJson(c: Clip) = JSONObject().apply {
            put("id", c.id); put("uri", c.uri); put("mime", c.mime ?: ""); put("isImage", c.isImage)
            put("sourceDurationMs", c.sourceDurationMs); put("width", c.width); put("height", c.height)
            put("bitrate", c.bitrate); put("frameRate", c.frameRate.toDouble()); put("hasAudio", c.hasAudio)
            put("trimStartMs", c.trimStartMs); put("trimEndMs", c.trimEndMs)
            put("speed", c.speed.toDouble()); put("volume", c.volume.toDouble())
            put("rotation", c.rotation); put("flipH", c.flipH); put("flipV", c.flipV)
            put("cropAspect", c.crop.aspect?.toDouble() ?: -1.0); put("cropStraighten", c.crop.straighten.toDouble())
            put("cropL", c.crop.left.toDouble()); put("cropT", c.crop.top.toDouble())
            put("cropR", c.crop.right.toDouble()); put("cropB", c.crop.bottom.toDouble())
            put("filter", c.filter.name)
            put("brightness", c.adjust.brightness.toDouble()); put("contrast", c.adjust.contrast.toDouble())
            put("saturation", c.adjust.saturation.toDouble()); put("warmth", c.adjust.warmth.toDouble())
            put("hue", c.adjust.hue.toDouble())
            put("fill", c.fill)
            put("texts", JSONArray().apply {
                c.texts.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id); put("text", t.text); put("color", t.color); put("size", t.size.toDouble())
                        put("x", t.x.toDouble()); put("y", t.y.toDouble()); put("bold", t.bold)
                        put("background", t.background ?: 0); put("hasBackground", t.background != null)
                        put("isSticker", t.isSticker)
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): Project {
            val clips = o.getJSONArray("clips").let { a -> (0 until a.length()).map { clipFrom(a.getJSONObject(it)) } }
            val audios = mutableListOf<AudioTrack>()
            // Drafts saved before audio tracks existed had a single "music" entry.
            o.optJSONObject("music")?.let { m ->
                val d = m.getLong("sourceDurationMs")
                audios += AudioTrack(
                    kind = AudioKind.MUSIC, uri = m.getString("uri"), name = m.optString("name"),
                    sourceDurationMs = d, sourceStartMs = m.optLong("startMs"), sourceEndMs = d,
                    volume = m.optDouble("volume", 1.0).toFloat(), loop = m.optBoolean("loop"),
                )
            }
            o.optJSONArray("audios")?.let { a -> for (i in 0 until a.length()) audios += audioFrom(a.getJSONObject(i)) }
            val overlays = o.optJSONArray("overlays")?.let { a -> (0 until a.length()).map { overlayFrom(a.getJSONObject(it)) } }.orEmpty()
            return Project(
                id = o.getString("id"), name = o.optString("name", "Dự án"), clips = clips, overlays = overlays, audios = audios,
                aspect = runCatching { AspectRatio.valueOf(o.optString("aspect")) }.getOrDefault(AspectRatio.ORIGINAL),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            )
        }

        private fun overlayFrom(o: JSONObject): Overlay = Overlay(
            id = o.getString("id"), uri = o.getString("uri"), name = o.optString("name"), mime = o.optString("mime").ifEmpty { null },
            isImage = o.getBoolean("isImage"), sourceDurationMs = o.getLong("sourceDurationMs"),
            width = o.getInt("width"), height = o.getInt("height"), hasAudio = o.optBoolean("hasAudio", true),
            trimStartMs = o.getLong("trimStartMs"), trimEndMs = o.getLong("trimEndMs"), startMs = o.optLong("startMs"),
            speed = o.optDouble("speed", 1.0).toFloat(), volume = o.optDouble("volume", 1.0).toFloat(),
            x = o.optDouble("x", 0.5).toFloat(), y = o.optDouble("y", 0.5).toFloat(),
            scale = o.optDouble("scale", 0.5).toFloat(), rotation = o.optDouble("rotation", 0.0).toFloat(),
            opacity = o.optDouble("opacity", 1.0).toFloat(), flipH = o.optBoolean("flipH"),
            chroma = o.optJSONObject("chroma")?.let { k ->
                ChromaKey(
                    color = k.optInt("color", 0xFF00FF00.toInt()), strength = k.optDouble("strength", 0.35).toFloat(),
                    softness = k.optDouble("softness", 0.15).toFloat(),
                )
            },
            mask = o.optJSONObject("mask")?.let { m ->
                ShapeMask(
                    shape = runCatching { MaskShape.valueOf(m.getString("shape")) }.getOrDefault(MaskShape.CIRCLE),
                    cx = m.optDouble("cx", 0.5).toFloat(), cy = m.optDouble("cy", 0.5).toFloat(),
                    size = m.optDouble("size", 0.7).toFloat(), feather = m.optDouble("feather", 0.1).toFloat(),
                    angle = m.optDouble("angle", 0.0).toFloat(), roundness = m.optDouble("roundness", 0.2).toFloat(),
                    invert = m.optBoolean("invert"),
                )
            },
            eraseMask = o.optString("eraseMask").ifEmpty { null },
        )

        private fun audioFrom(o: JSONObject): AudioTrack = AudioTrack(
            id = o.getString("id"),
            kind = runCatching { AudioKind.valueOf(o.getString("kind")) }.getOrDefault(AudioKind.MUSIC),
            uri = o.getString("uri"), name = o.optString("name"), sourceDurationMs = o.getLong("sourceDurationMs"),
            sourceStartMs = o.optLong("sourceStartMs"), sourceEndMs = o.getLong("sourceEndMs"),
            startMs = o.optLong("startMs"), volume = o.optDouble("volume", 1.0).toFloat(),
            speed = o.optDouble("speed", 1.0).toFloat(), loop = o.optBoolean("loop"),
            voice = o.optJSONObject("voice")?.let { v ->
                VoiceSpec(
                    text = v.optString("text"), voiceName = v.optString("voiceName").ifEmpty { null },
                    pitch = v.optDouble("pitch", 1.0).toFloat(), rate = v.optDouble("rate", 1.0).toFloat(),
                )
            },
        )

        private fun clipFrom(o: JSONObject): Clip {
            val textsArr = o.optJSONArray("texts") ?: JSONArray()
            val texts = (0 until textsArr.length()).map { i ->
                val t = textsArr.getJSONObject(i)
                TextItem(
                    id = t.optString("id", newId()), text = t.getString("text"), color = t.optInt("color", -1),
                    size = t.optDouble("size", 0.06).toFloat(), x = t.optDouble("x", 0.5).toFloat(),
                    y = t.optDouble("y", 0.8).toFloat(), bold = t.optBoolean("bold", true),
                    background = if (t.optBoolean("hasBackground")) t.optInt("background") else null,
                    isSticker = t.optBoolean("isSticker"),
                )
            }
            val cropAspect = o.optDouble("cropAspect", -1.0).takeIf { it > 0 }?.toFloat()
            val rotation = o.optInt("rotation")
            val crop = if (o.has("cropL")) {
                CropSpec(
                    left = o.optDouble("cropL", 0.0).toFloat(), top = o.optDouble("cropT", 0.0).toFloat(),
                    right = o.optDouble("cropR", 1.0).toFloat(), bottom = o.optDouble("cropB", 1.0).toFloat(),
                    aspect = cropAspect, straighten = CropOps.clampStraighten(o.optDouble("cropStraighten", 0.0).toFloat()),
                )
            } else {
                // Drafts from before 1.0.8 stored ratio, zoom and centre.
                val w = o.getInt("width")
                val h = o.getInt("height")
                CropOps.fromLegacy(
                    cropAspect, o.optDouble("cropZoom", 1.0).toFloat(),
                    o.optDouble("cropX", 0.5).toFloat(), o.optDouble("cropY", 0.5).toFloat(),
                    if (rotation % 180 == 0) w else h, if (rotation % 180 == 0) h else w,
                )
            }
            return Clip(
                id = o.getString("id"), uri = o.getString("uri"), mime = o.optString("mime").ifEmpty { null },
                isImage = o.getBoolean("isImage"), sourceDurationMs = o.getLong("sourceDurationMs"),
                width = o.getInt("width"), height = o.getInt("height"), bitrate = o.optInt("bitrate"),
                frameRate = o.optDouble("frameRate", 30.0).toFloat(), hasAudio = o.optBoolean("hasAudio", true),
                trimStartMs = o.getLong("trimStartMs"), trimEndMs = o.getLong("trimEndMs"),
                speed = o.optDouble("speed", 1.0).toFloat(), volume = o.optDouble("volume", 1.0).toFloat(),
                rotation = rotation, flipH = o.optBoolean("flipH"), flipV = o.optBoolean("flipV"),
                crop = crop,
                filter = runCatching { FilterPreset.valueOf(o.optString("filter")) }.getOrDefault(FilterPreset.NONE),
                adjust = Adjustments(
                    brightness = o.optDouble("brightness", 0.0).toFloat(), contrast = o.optDouble("contrast", 0.0).toFloat(),
                    saturation = o.optDouble("saturation", 0.0).toFloat(), warmth = o.optDouble("warmth", 0.0).toFloat(),
                    hue = o.optDouble("hue", 0.0).toFloat(),
                ),
                texts = texts,
                fill = o.optBoolean("fill"),
            )
        }
    }
}
