package com.cutviet.editor.media.dubbing

import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini listens to every speech segment once and returns, for each person's turn inside it:
 * who speaks (the same person keeps the same id across the video), when the turn starts, what was
 * said, a Vietnamese subtitle-style translation (for Vietnamese speech: the words as said, cleanly
 * punctuated) and the tone. Each person also gets a gender and a short voice description.
 *
 * Subtitles show each turn as its own line; the natural online dub voices each turn in the voice
 * of its person. One request per few minutes of speech.
 */
class GeminiSubtitler(private val api: GeminiApi, private val model: String) {

    /** One person's turn, times in ms relative to the audio that was given. */
    data class Turn(val startMs: Long, val endMs: Long, val speaker: String, val source: String, val vi: String, val emotion: String = "")

    class Heard(val speakers: List<GeminiDubber.Speaker>, val turns: List<Turn>)

    /** A turn as the model wrote it: [startSec] from the start of the segment's audio, or null. */
    data class RawTurn(val speaker: String, val startSec: Double?, val source: String, val vi: String, val emotion: String)

    class Answer(val speakers: List<GeminiDubber.Speaker>, val segments: Map<Int, List<RawTurn>>)

    suspend fun listen(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): Heard {
        val speakers = LinkedHashMap<String, GeminiDubber.Speaker>()
        val turns = ArrayList<Turn>()
        val batches = GeminiDubber.batches(ranges)
        batches.forEachIndexed { b, indices ->
            val what = "nghe ${b + 1}/${batches.size}"
            val known = speakers.values.toList()
            val answer = try {
                api.interact(request(model, language, voice, ranges, indices, known, schema = true), what)
            } catch (e: GeminiApi.Rejected) {
                DubLog.w(TAG, "$what: refused with a schema, asking without: ${e.message}")
                api.interact(request(model, language, voice, ranges, indices, known, schema = false), what)
            }
            val text = GeminiApi.outputText(answer) ?: throw OnlineUnavailableException("Gemini không trả lời ($what)")
            val parsed = parse(text, indices.toSet()) ?: throw OnlineUnavailableException("Không đọc được câu trả lời của Gemini ($what)")
            parsed.speakers.forEach { s -> if (s.id !in speakers || speakers[s.id]?.gender == null) speakers[s.id] = s }
            if (parsed.segments.size < indices.size) DubLog.w(TAG, "$what: answered ${parsed.segments.size} of ${indices.size} segments")
            for (i in indices) {
                val raw = parsed.segments[i] ?: continue
                val placed = place(ranges[i], raw)
                if (placed.size > 1) DubLog.i(TAG, "segment $i ${ranges[i].startMs}-${ranges[i].endMs}ms: ${placed.joinToString { "${it.speaker}@${it.startMs}" }}")
                turns += placed
            }
            progress((b + 1f) / batches.size)
        }
        turns.map { it.speaker }.distinct().filter { it !in speakers }.forEach { speakers[it] = GeminiDubber.Speaker(it, null, "") }
        DubLog.i(TAG, "listened: ${speakers.size} speakers, ${turns.size} turns in ${ranges.size} segments")
        return Heard(speakers.values.toList(), turns)
    }

    fun shutdown() = api.shutdown()

    companion object {
        const val TAG = "CutVietSub"
        /** Audio kept around each segment sent for listening. */
        const val PAD_MS = 150L
        /** Shortest turn kept apart; anything shorter joins the turn before it. */
        const val MIN_TURN_MS = 400L

        /**
         * Places a segment's turns in time. The model's start times are seconds into the segment's
         * audio (which begins [PAD_MS] early); when they are missing or out of order the segment is
         * shared out by the length of what each person said.
         */
        fun place(r: SpeechDetector.Range, raw: List<RawTurn>): List<Turn> {
            val kept = raw.filter { it.vi.isNotBlank() }
            if (kept.isEmpty()) return emptyList()
            val audioStart = (r.startMs - PAD_MS).coerceAtLeast(0)
            val given = kept.map { t -> t.startSec?.let { (audioStart + it * 1000).toLong().coerceIn(r.startMs, r.endMs) } }
            val usable = given.all { it != null } && given.zipWithNext().all { (a, b) -> b!! - a!! >= MIN_TURN_MS } &&
                r.endMs - given.last()!! >= MIN_TURN_MS
            val starts: List<Long> = if (usable) {
                listOf(r.startMs) + given.drop(1).map { it!! }
            } else {
                val weights = kept.map { maxOf(1, (it.source.ifBlank { it.vi }).length) }
                val total = weights.sum().toDouble()
                var acc = 0
                weights.map { w -> (r.startMs + (r.endMs - r.startMs) * acc / total).toLong().also { acc += w } }
            }
            // The same person twice in a row is one turn; a turn too short joins the one before.
            val out = ArrayList<Turn>()
            kept.forEachIndexed { k, t ->
                val start = starts[k]
                val end = starts.getOrNull(k + 1) ?: r.endMs
                val last = out.lastOrNull()
                if (last != null && (last.speaker == t.speaker || end - start < MIN_TURN_MS)) {
                    out[out.lastIndex] = last.copy(endMs = end, source = join(last.source, t.source), vi = join(last.vi, t.vi))
                } else {
                    out += Turn(start, end, t.speaker, t.source, t.vi, t.emotion)
                }
            }
            return out
        }

        private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

        fun request(
            model: String,
            language: SourceLanguage,
            voice: FloatArray,
            ranges: List<SpeechDetector.Range>,
            indices: List<Int>,
            known: List<GeminiDubber.Speaker>,
            schema: Boolean,
        ): JSONObject {
            val input = JSONArray().put(text(prompt(language, indices.size, known)))
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

        fun prompt(language: SourceLanguage, count: Int, known: List<GeminiDubber.Speaker> = emptyList()): String = buildString {
            val lang = language.label.lowercase()
            append("Dưới đây là $count đoạn ghi âm lời nói $lang trong một video, đúng thứ tự thời gian, mỗi đoạn có nhãn [S<số>]. ")
            append("Làm phụ đề tiếng Việt cho video.\n")
            append("Trong mỗi đoạn có thể có một hoặc nhiều người nói nối tiếp nhau. Tách đoạn thành các lượt nói (turns): mỗi khi người nói đổi là một lượt mới.\n")
            append("Với mỗi lượt nói:\n")
            append("- speaker: mã người nói. Cùng một người trong cả video thì dùng cùng một mã (A, B, C…). Phân biệt bằng giọng nghe được (cao độ, âm sắc, cách nói), không đoán theo nội dung.\n")
            append("- start: lượt nói bắt đầu ở giây thứ mấy tính từ đầu đoạn ghi âm đó (số thập phân, ví dụ 2.4).\n")
            append("- source: nguyên văn lời nói $lang nghe được.\n")
            if (language == SourceLanguage.VIETNAMESE) {
                append("- vi: chính câu tiếng Việt đó, viết đúng chính tả, có dấu câu đầy đủ, không thêm hay bớt ý, bỏ tiếng ậm ừ vô nghĩa.\n")
            } else {
                append("- vi: phụ đề tiếng Việt, dịch tự nhiên như người Việt nói, đúng sắc thái, xưng hô hợp với người nói và người nghe (anh, chị, em, tôi, mình…), gọn để người xem đọc kịp, giữ nguyên tên riêng, không dịch từng chữ.\n")
            }
            append("- emotion: giọng điệu bằng vài từ tiếng Anh, ví dụ neutral, excited, laughing, annoyed, sad, whispering, shouting.\n")
            append("Với mỗi người nói: gender theo giọng (male, female, hoặc child nếu là trẻ em) và voice: mô tả giọng bằng một câu tiếng Anh ngắn (tuổi ước chừng, giọng trầm hay cao, nói nhanh hay chậm, phong thái).\n")
            if (known.isNotEmpty()) {
                append("Những người đã nhận ra ở phần trước của video (dùng lại mã nếu đúng là họ):\n")
                known.forEach { append("- ${it.id}: ${it.gender?.name?.lowercase() ?: "?"}, ${it.description}\n") }
            }
            append("Đoạn không có lời nói rõ (chỉ có nhạc, tiếng ồn) thì vẫn trả về với turns rỗng. Không tự bịa lời.\n")
            append("Chỉ trả về JSON dạng {\"speakers\":[{\"id\":\"A\",\"gender\":\"male\",\"voice\":\"…\"}],")
            append("\"lines\":[{\"index\":1,\"turns\":[{\"speaker\":\"A\",\"start\":0.0,\"source\":\"…\",\"vi\":\"…\",\"emotion\":\"neutral\"}]}]}, trong đó index là số trong nhãn [S<số>].")
        }

        private val SCHEMA: JSONObject
            get() = JSONObject(
                """
                {"type":"object","properties":{
                  "speakers":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string"},
                    "gender":{"type":"string","enum":["male","female","child"]},
                    "voice":{"type":"string"}},"required":["id","gender","voice"]}},
                  "lines":{"type":"array","items":{"type":"object","properties":{
                    "index":{"type":"integer"},
                    "turns":{"type":"array","items":{"type":"object","properties":{
                      "speaker":{"type":"string"},
                      "start":{"type":"number"},
                      "source":{"type":"string"},
                      "vi":{"type":"string"},
                      "emotion":{"type":"string"}},"required":["speaker","start","source","vi","emotion"]}}},
                    "required":["index","turns"]}}},
                 "required":["speakers","lines"]}
                """.trimIndent(),
            )

        /**
         * Reads the answer; segments are numbered from 1 in the prompt. A line without "turns" but
         * with its own speaker/vi (the older flat shape) counts as one turn. Null if it is not JSON with lines.
         */
        fun parse(answer: String, expected: Set<Int>): Answer? {
            val a = answer.indexOf('{')
            val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            val json = runCatching { JSONObject(answer.substring(a, b + 1)) }.getOrNull() ?: return null
            val arr = json.optJSONArray("lines") ?: return null
            val speakers = ArrayList<GeminiDubber.Speaker>()
            json.optJSONArray("speakers")?.let { sp ->
                for (i in 0 until sp.length()) {
                    val o = sp.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isNotEmpty()) speakers += GeminiDubber.Speaker(id, GeminiDubber.genderOf(o.optString("gender")), o.optString("voice").trim())
                }
            }
            val segments = LinkedHashMap<Int, List<RawTurn>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt("index", -1) - 1
                if (index !in expected || index in segments) continue
                val turns = o.optJSONArray("turns")
                segments[index] = if (turns != null) {
                    (0 until turns.length()).mapNotNull { k -> turns.optJSONObject(k)?.let(::rawTurn) }
                } else {
                    listOf(rawTurn(o))
                }
            }
            return Answer(speakers, segments)
        }

        private fun rawTurn(o: JSONObject) = RawTurn(
            speaker = o.optString("speaker").trim().ifEmpty { "?" },
            startSec = if (o.has("start")) o.optDouble("start").takeIf { !it.isNaN() && it >= 0 } else null,
            source = o.optString("source").trim(),
            vi = o.optString("vi").trim(),
            emotion = GeminiDubber.normaliseEmotion(o.optString("emotion")),
        )

        private fun text(s: String) = JSONObject().put("type", "text").put("text", s)
    }
}
