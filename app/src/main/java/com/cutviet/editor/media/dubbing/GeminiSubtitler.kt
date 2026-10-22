package com.cutviet.editor.media.dubbing

import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Online subtitles: Gemini listens to every speech segment and writes down what is said and a
 * Vietnamese subtitle for it (for Vietnamese speech: the words as said, cleanly punctuated). One
 * request per few minutes of speech; nothing is voiced, so it costs far less quota than a dub.
 */
class GeminiSubtitler(private val api: GeminiApi, private val model: String) {

    /** One segment's text: [index] into the speech ranges. */
    data class Line(val index: Int, val source: String, val vi: String)

    suspend fun subtitle(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): List<Line> {
        val out = HashMap<Int, Line>()
        val batches = GeminiDubber.batches(ranges)
        batches.forEachIndexed { b, indices ->
            val what = "phụ đề ${b + 1}/${batches.size}"
            val answer = try {
                api.interact(request(model, language, voice, ranges, indices, schema = true), what)
            } catch (e: GeminiApi.Rejected) {
                DubLog.w(TAG, "$what: refused with a schema, asking without: ${e.message}")
                api.interact(request(model, language, voice, ranges, indices, schema = false), what)
            }
            val text = GeminiApi.outputText(answer) ?: throw OnlineUnavailableException("Gemini không trả lời ($what)")
            val lines = parse(text, indices.toSet()) ?: throw OnlineUnavailableException("Không đọc được câu trả lời của Gemini ($what)")
            if (lines.size < indices.size) DubLog.w(TAG, "$what: answered ${lines.size} of ${indices.size} segments")
            lines.forEach { out[it.index] = it }
            progress((b + 1f) / batches.size)
        }
        return out.values.sortedBy { it.index }
    }

    fun shutdown() = api.shutdown()

    companion object {
        const val TAG = "CutVietSub"
        private const val PAD_MS = 150L

        fun request(
            model: String,
            language: SourceLanguage,
            voice: FloatArray,
            ranges: List<SpeechDetector.Range>,
            indices: List<Int>,
            schema: Boolean,
        ): JSONObject {
            val input = JSONArray().put(text(prompt(language, indices.size)))
            for (i in indices) {
                input.put(text("[S${i + 1}]"))
                val r = ranges[i]
                val from = ((r.startMs - PAD_MS) * 16).toInt().coerceIn(0, voice.size)
                val to = ((r.endMs + PAD_MS) * 16).toInt().coerceIn(from, voice.size)
                input.put(
                    JSONObject().put("type", "audio")
                        .put("data", GeminiDubber.wavBytes(voice, from, to, 16_000).toByteString().base64())
                        .put("mime_type", "audio/wav"),
                )
            }
            return JSONObject().put("model", model).put("input", input).apply {
                if (schema) {
                    put("response_format", JSONObject().put("type", "text").put("mime_type", "application/json").put("schema", SCHEMA))
                }
            }
        }

        fun prompt(language: SourceLanguage, count: Int): String = buildString {
            append("Dưới đây là $count đoạn ghi âm lời nói ${language.label.lowercase()} trong một video, đúng thứ tự thời gian, mỗi đoạn có nhãn [S<số>]. ")
            append("Làm phụ đề tiếng Việt cho video.\n")
            append("Với mỗi đoạn:\n")
            append("1. source: ghi nguyên văn lời nói ${language.label.lowercase()} nghe được.\n")
            if (language == SourceLanguage.VIETNAMESE) {
                append("2. vi: chính câu tiếng Việt đó, viết đúng chính tả, có dấu câu đầy đủ, không thêm hay bớt ý, bỏ tiếng ậm ừ vô nghĩa.\n")
            } else {
                append("2. vi: phụ đề tiếng Việt, dịch tự nhiên như người Việt nói, đúng sắc thái, xưng hô hợp ngữ cảnh (anh, chị, em, tôi, mình…), gọn để người xem đọc kịp, giữ nguyên tên riêng, không dịch từng chữ.\n")
            }
            append("Đoạn không có lời nói rõ (chỉ có nhạc, tiếng ồn) thì vẫn trả về, với source và vi rỗng. Không tự bịa lời.\n")
            append("Chỉ trả về JSON dạng {\"lines\":[{\"index\":1,\"source\":\"…\",\"vi\":\"…\"}]}, trong đó index là số trong nhãn [S<số>].")
        }

        private val SCHEMA: JSONObject
            get() = JSONObject(
                """
                {"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{
                  "index":{"type":"integer"},"source":{"type":"string"},"vi":{"type":"string"}},
                  "required":["index","source","vi"]}}},"required":["lines"]}
                """.trimIndent(),
            )

        /** Reads the answer; segments are numbered from 1 in the prompt. Null if it is not JSON with lines. */
        fun parse(answer: String, expected: Set<Int>): List<Line>? {
            val a = answer.indexOf('{')
            val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            val arr = runCatching { JSONObject(answer.substring(a, b + 1)) }.getOrNull()?.optJSONArray("lines") ?: return null
            val out = ArrayList<Line>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt("index", -1) - 1
                if (index !in expected || out.any { it.index == index }) continue
                out += Line(index, o.optString("source").trim(), o.optString("vi").trim())
            }
            return out.sortedBy { it.index }
        }

        private fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    }
}
