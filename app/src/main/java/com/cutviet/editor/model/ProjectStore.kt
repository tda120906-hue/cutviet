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
    }

    companion object {
        fun toJson(p: Project): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("aspect", p.aspect.name)
            put("createdAt", p.createdAt)
            put("updatedAt", p.updatedAt)
            put("clips", JSONArray().apply { p.clips.forEach { put(clipJson(it)) } })
            p.music?.let { m ->
                put("music", JSONObject().apply {
                    put("uri", m.uri); put("name", m.name); put("sourceDurationMs", m.sourceDurationMs)
                    put("startMs", m.startMs); put("volume", m.volume.toDouble()); put("loop", m.loop)
                    put("fadeOut", m.fadeOut)
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
            put("cropAspect", c.crop.aspect?.toDouble() ?: -1.0); put("cropZoom", c.crop.zoom.toDouble())
            put("cropX", c.crop.centerX.toDouble()); put("cropY", c.crop.centerY.toDouble())
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
            val music = o.optJSONObject("music")?.let { m ->
                Music(
                    uri = m.getString("uri"), name = m.optString("name"),
                    sourceDurationMs = m.getLong("sourceDurationMs"), startMs = m.optLong("startMs"),
                    volume = m.optDouble("volume", 1.0).toFloat(), loop = m.optBoolean("loop"),
                    fadeOut = m.optBoolean("fadeOut"),
                )
            }
            return Project(
                id = o.getString("id"), name = o.optString("name", "Dự án"), clips = clips, music = music,
                aspect = runCatching { AspectRatio.valueOf(o.optString("aspect")) }.getOrDefault(AspectRatio.ORIGINAL),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            )
        }

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
            val cropAspect = o.optDouble("cropAspect", -1.0)
            return Clip(
                id = o.getString("id"), uri = o.getString("uri"), mime = o.optString("mime").ifEmpty { null },
                isImage = o.getBoolean("isImage"), sourceDurationMs = o.getLong("sourceDurationMs"),
                width = o.getInt("width"), height = o.getInt("height"), bitrate = o.optInt("bitrate"),
                frameRate = o.optDouble("frameRate", 30.0).toFloat(), hasAudio = o.optBoolean("hasAudio", true),
                trimStartMs = o.getLong("trimStartMs"), trimEndMs = o.getLong("trimEndMs"),
                speed = o.optDouble("speed", 1.0).toFloat(), volume = o.optDouble("volume", 1.0).toFloat(),
                rotation = o.optInt("rotation"), flipH = o.optBoolean("flipH"), flipV = o.optBoolean("flipV"),
                crop = CropSpec(
                    aspect = if (cropAspect > 0) cropAspect.toFloat() else null,
                    zoom = o.optDouble("cropZoom", 1.0).toFloat(),
                    centerX = o.optDouble("cropX", 0.5).toFloat(), centerY = o.optDouble("cropY", 0.5).toFloat(),
                ),
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
