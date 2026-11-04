package com.cutviet.editor.media.dubbing

import com.cutviet.editor.media.VoiceEngine
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Natural online dub with Gemini 3.8, one line per sentence of the video. The pieces come from the
 * voice itself ([SpeechDetector.sentences]): each starts where the person starts talking and ends
 * where they stop, so a line is never placed by a guess. The text model listens to every piece and
 * writes, for that piece alone, what was said, a Vietnamese line about as long as the piece (a
 * syllable count for its length is given) and its tone. The TTS model then reads each line on its
 * own in one voice ([preset]); the user gives single lines another voice afterwards. When the
 * daily quota runs out, the lines voiced so far are kept and the rest come back without files, so
 * the pipeline reads them with the phone's voice.
 */
class GeminiDubber(
    private val api: GeminiApi,
    private val textModel: String,
    private val ttsModel: String,
    private val workDir: File,
    /** The voice every line is read in. */
    private val preset: VoicePreset,
) : OnlineDubber {

    /** A person heard in the video (subtitles). [gender] is null when the model did not say. */
    data class Speaker(val id: String, val gender: Gender?, val description: String)

    /** One piece of speech as the model heard it ([index] into the pieces given). */
    data class Heard(val index: Int, val source: String, val text: String, val emotion: String)

    /** Who reads a line: a Gemini voice and how the speaker is described to it. */
    data class Role(val voice: String, val gender: Gender, val description: String)

    private var stoppedNotice: String? = null
    override val notice: String? get() = listOfNotNull(stoppedNotice, api.switchNote).joinToString(". ").ifEmpty { null }

    private class Voiced(val file: File, val ms: Long)

    override suspend fun dub(
        voice: FloatArray,
        segments: List<SpeechDetector.Range>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): List<OnlineLine> {
        try {
            val lines = listen(api, textModel, voice, segments, language) { progress(0.3f * it) }.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) throw OnlineUnavailableException("Gemini không nghe ra câu nào để dịch")
            // Everything below is by position in [lines], in time order.
            val ranges = lines.map { segments[it.index] }
            val room = DubbingPipeline.roomFor(ranges.map { it.startMs to it.lengthMs }, voice.size / 16L)
            val role = Role(preset.voice, preset.gender, preset.description)
            DubLog.i(TAG, "${lines.size} lines of ${segments.size} pieces, voice ${preset.voice} (${preset.description})")

            val voiced = HashMap<Int, Voiced>()
            val texts = lines.map { it.text }.toMutableList()
            var stopped: String? = null
            for (k in lines.indices) {
                try {
                    voiceLine(k, texts[k], lines[k].emotion, role)?.let { voiced[k] = it }
                } catch (e: OnlineUnavailableException) {
                    // Quota, key or network: the phone's voice reads the rest.
                    stopped = e.message
                    DubLog.w(TAG, "stopped voicing online at line $k: ${e.message}")
                    break
                }
                progress(0.3f + 0.6f * (k + 1) / lines.size)
            }
            progress(0.9f)

            // Lines still too long at 1.15x: reworded shorter (one request for all), voiced again.
            val long = lines.indices.filter { k ->
                val v = voiced[k] ?: return@filter false
                v.ms / AudioFitter.MAX_RATE > AudioFitter.upper(ranges[k].lengthMs, room[k])
            }.take(MAX_REWORDS)
            if (stopped == null && long.isNotEmpty()) {
                try {
                    reword(long, lines, voiced, ranges, room, texts, language, role)
                } catch (e: OnlineUnavailableException) {
                    DubLog.w(TAG, "rewording stopped: ${e.message}")
                }
            }
            progress(1f)

            if (stopped != null) stoppedNotice = "${lines.size - voiced.size} câu đọc bằng giọng của máy ($stopped)"
            return lines.mapIndexed { k, l ->
                val r = ranges[k]
                val v = voiced[k]
                val speed = if (v != null && room[k] > 0) (v.ms.toFloat() / room[k]).coerceIn(1f, AudioFitter.MAX_RATE) else 1f
                DubLog.i(
                    TAG,
                    "line $k at ${r.startMs}-${r.endMs}ms ${if (v != null) role.voice else "phone"} " +
                        "\"${l.source.take(50)}\" → \"${texts[k].take(50)}\"" +
                        (if (v != null) " measured=${v.ms}ms slot=${r.lengthMs}ms room=${room[k]}ms" else ""),
                )
                OnlineLine(
                    r.startMs, r.endMs, room[k], l.source, texts[k], null, if (v != null) role.gender else null, v?.file, v?.ms ?: 0, speed,
                    voice = if (v != null) role.voice else null, description = if (v != null) role.description else "", emotion = l.emotion,
                )
            }
        } finally {
            api.shutdown()
        }
    }

    /** Voices one line on its own; null when Gemini refuses that line (the phone reads it). */
    private suspend fun voiceLine(k: Int, text: String, emotion: String, role: Role): Voiced? {
        val f = File(workDir, "gemini_$k.wav")
        return try {
            tts(text, role, emotion, "đọc câu ${k + 1}", f)
            Voiced(f, WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: 0)
        } catch (e: GeminiApi.Rejected) {
            DubLog.w(TAG, "line $k refused, phone voice reads it: ${e.message}")
            null
        }
    }

    private suspend fun tts(text: String, role: Role, emotion: String, what: String, file: File) {
        val answer = api.interact(ttsRequest(ttsModel, ttsPrompt(role, emotion, text), role.voice), what)
        val (bytes, mime) = GeminiApi.outputAudio(answer) ?: throw OnlineUnavailableException("Gemini không trả về giọng đọc ($what)")
        writeAudio(bytes, mime, file)
    }

    /** One shorter wording for every line in [long], each voiced again and kept when it fits better. */
    private suspend fun reword(
        long: List<Int>,
        lines: List<Heard>,
        voiced: MutableMap<Int, Voiced>,
        ranges: List<SpeechDetector.Range>,
        room: List<Long>,
        texts: MutableList<String>,
        language: SourceLanguage,
        role: Role,
    ) {
        val asked = long.map { k -> Heard(k, lines[k].source, texts[k], lines[k].emotion) }
        val targets = long.associateWith { k ->
            val limit = AudioFitter.upper(ranges[k].lengthMs, room[k]) * AudioFitter.MAX_RATE
            (VoiceEngine.syllableCount(texts[k]) * limit / voiced.getValue(k).ms * 0.9).toInt().coerceAtLeast(2)
        }
        val text = askJson(api, "rút gọn ${long.size} câu") { schema -> shortenRequest(textModel, language, asked, targets, schema) }
        val shorter = parseShortened(text)
        for (k in long) {
            val s = shorter[k]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (VoiceEngine.syllableCount(s) >= VoiceEngine.syllableCount(texts[k])) continue
            val f = File(workDir, "gemini_${k}_short.wav")
            try {
                tts(s, role, lines[k].emotion, "đọc lại câu ${k + 1}", f)
            } catch (e: GeminiApi.Rejected) {
                continue
            }
            val ms = WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: continue
            val slot = ranges[k].lengthMs
            val before = voiced.getValue(k)
            fun playedMiss(d: Long) = AudioFitter.miss((d / AudioFitter.MAX_RATE).toLong(), slot, room[k])
            DubLog.i(TAG, "line $k reworded ${VoiceEngine.syllableCount(texts[k])}→${VoiceEngine.syllableCount(s)} syllables: ${before.ms}→$ms ms")
            if (playedMiss(ms) < playedMiss(before.ms)) {
                before.file.delete()
                voiced[k] = Voiced(f, ms)
                texts[k] = s
            } else {
                f.delete()
            }
        }
    }

    companion object {
        const val TAG = "CutVietGemini"
        const val DEFAULT_TEXT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_TTS_MODEL = "gemini-3.8-flash-tts"
        /** Gemini's Vietnamese voices at a brisk pace (one word = one syllable). */
        const val SYLLABLES_PER_SECOND = 4.6
        /** Speech per listening request; 16 kHz WAV, so well under the 20 MB request limit. */
        private const val BATCH_SPEECH_MS = 180_000L
        private const val BATCH_SEGMENTS = 40
        /** Lines that come out too long are reworded shorter, at most this many per dub. */
        private const val MAX_REWORDS = 30
        /** Audio kept around each piece sent for listening (pieces are at least 300 ms apart). */
        private const val LISTEN_PAD_MS = 100L

        /**
         * Prebuilt Gemini voices by gender (Google's voice list), most neutral first. Lines dubbed
         * before v1.0.23 got their voice from these by the person's number ("Nữ 2" → the second).
         */
        val POOLS: Map<Gender, List<String>> = mapOf(
            Gender.MALE to listOf("Charon", "Puck", "Orus", "Iapetus", "Algenib", "Umbriel", "Achird", "Sadaltager", "Fenrir", "Enceladus", "Alnilam", "Schedar", "Rasalgethi", "Algieba", "Zubenelgenubi", "Sadachbia"),
            Gender.FEMALE to listOf("Kore", "Aoede", "Despina", "Sulafat", "Callirrhoe", "Achernar", "Vindemiatrix", "Erinome", "Zephyr", "Autonoe", "Gacrux", "Pulcherrima", "Leda", "Laomedeia"),
            Gender.CHILD to listOf("Leda", "Laomedeia", "Autonoe", "Puck"),
        )

        /** Syllables a Vietnamese line should have to be read in [slotMs] by a Gemini voice. */
        fun syllableBudget(slotMs: Long): Int = Math.round(slotMs / 1000.0 * SYLLABLES_PER_SECOND).toInt().coerceAtLeast(1)

        /**
         * Listens to the speech [ranges] of [voice] (16 kHz), a few minutes per request, and returns
         * one [Heard] per piece the model answered, in time order. [context]: what is said around
         * these pieces, to translate one line again the way it fits in. Pieces left out of an answer
         * are asked for once more.
         */
        suspend fun listen(
            api: GeminiApi,
            model: String,
            voice: FloatArray,
            ranges: List<SpeechDetector.Range>,
            language: SourceLanguage,
            context: String = "",
            progress: (Float) -> Unit,
        ): List<Heard> {
            val out = ArrayList<Heard>()
            val batches = batches(ranges)
            batches.forEachIndexed { b, indices ->
                val what = "nghe ${b + 1}/${batches.size}"
                // The lines just before, so wording and forms of address carry on across requests.
                val before = out.takeLast(4).joinToString("\n") { "Trước: ${it.source} → ${it.text}" }
                val ctx = listOf(context, before).filter { it.isNotBlank() }.joinToString("\n")
                val got = HashMap(ask(api, model, voice, ranges, indices, language, ctx, what))
                val missing = indices.filter { it !in got }
                if (missing.isNotEmpty()) {
                    DubLog.w(TAG, "$what: ${missing.size} of ${indices.size} pieces not answered, asking again")
                    got += ask(api, model, voice, ranges, missing, language, ctx, "$what (lần 2)")
                }
                if (got.isEmpty()) throw OnlineUnavailableException("Không đọc được câu trả lời của Gemini ($what)")
                indices.mapNotNullTo(out) { got[it] }
                progress((b + 1f) / batches.size)
            }
            DubLog.i(TAG, "listened: ${out.count { it.text.isNotBlank() }} lines in ${ranges.size} pieces")
            return out
        }

        private suspend fun ask(
            api: GeminiApi, model: String, voice: FloatArray, ranges: List<SpeechDetector.Range>, indices: List<Int>,
            language: SourceLanguage, context: String, what: String,
        ): Map<Int, Heard> {
            val text = askJson(api, what) { schema -> listenRequest(model, language, voice, ranges, indices, context, schema) }
            return parseLines(text, indices.toSet()) ?: emptyMap<Int, Heard>().also { DubLog.w(TAG, "$what: answer was not JSON with lines") }
        }

        /** Sends a request built with a JSON schema; if the server refuses the schema, asks again without one. */
        private suspend fun askJson(api: GeminiApi, what: String, build: (schema: Boolean) -> JSONObject): String {
            val answer = try {
                api.interact(build(true), what)
            } catch (e: GeminiApi.Rejected) {
                DubLog.w(TAG, "$what: refused with a schema, asking without: ${e.message}")
                api.interact(build(false), what)
            }
            return GeminiApi.outputText(answer) ?: throw OnlineUnavailableException("Gemini không trả lời ($what)")
        }

        /** The listening request: the prompt, then each piece's label (its length and syllable budget) and audio. */
        fun listenRequest(
            model: String,
            language: SourceLanguage,
            voice: FloatArray,
            ranges: List<SpeechDetector.Range>,
            indices: List<Int>,
            context: String,
            schema: Boolean,
        ): JSONObject {
            val input = JSONArray().put(text(listenPrompt(language, indices.size, context)))
            for (i in indices) {
                val r = ranges[i]
                input.put(text("[S${i + 1}] ${seconds(r.lengthMs)} giây, khoảng ${syllableBudget(r.lengthMs)} âm tiết"))
                val from = ((r.startMs - LISTEN_PAD_MS) * 16).toInt().coerceIn(0, voice.size)
                val to = ((r.endMs + LISTEN_PAD_MS) * 16).toInt().coerceIn(from, voice.size)
                input.put(
                    JSONObject().put("type", "audio")
                        .put("data", wavBytes(voice, from, to, 16_000).toByteString().base64())
                        .put("mime_type", "audio/wav"),
                )
            }
            return JSONObject().put("model", model).put("input", input).apply {
                if (schema) put("response_format", jsonFormat(LINES_SCHEMA))
            }
        }

        /** "2.4": one decimal, a dot whatever the phone's language. */
        private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}"

        fun listenPrompt(language: SourceLanguage, count: Int, context: String = ""): String = buildString {
            val lang = language.promptName
            append("Dưới đây là $count đoạn ghi âm lời nói${lang?.let { " $it" } ?: ""} trong một video, đúng thứ tự thời gian. Mỗi đoạn có nhãn [S<số>] kèm độ dài của đoạn. ")
            if (lang == null) append(GeminiSubtitler.AUTO_LANGUAGE)
            append("Mỗi đoạn là một câu (đôi khi chỉ một phần câu); lời lồng tiếng Việt của đoạn nào sẽ được đọc đè lên đúng khoảng thời gian của đoạn đó trong video.\n")
            append("Với mỗi đoạn trả về:\n")
            append("- source: nguyên văn lời nói${lang?.let { " $it" } ?: ""} nghe được trong đoạn đó, chỉ trong đoạn đó${if (lang == null) ", viết bằng chính ngôn ngữ đó" else ""}.\n")
            append("- vi: lời lồng tiếng Việt của riêng đoạn đó: ")
            if (lang == null) append("đoạn nói tiếng Việt thì giữ đúng lời đó, viết đúng chính tả; đoạn nói tiếng khác thì ")
            append("dịch tự nhiên như người Việt nói, đúng sắc thái, xưng hô hợp ngữ cảnh (anh, chị, em, tôi, mình…), giữ nguyên tên riêng, không dịch từng chữ. ")
            append("Câu bị cắt ngang qua hai đoạn thì mỗi đoạn chỉ dịch phần của mình, sao cho đọc nối tiếp vẫn tự nhiên. ")
            append("Dài khoảng số âm tiết ghi ở nhãn để đọc vừa khít thời gian của đoạn: không dài hơn nhiều, cũng không ngắn hơn nhiều.\n")
            append("- emotion: giọng điệu bằng vài từ tiếng Anh, ví dụ neutral, excited, laughing, annoyed, sad, whispering, shouting.\n")
            if (context.isNotBlank()) {
                append("Ngữ cảnh (các câu ngay trước và sau trong video, chỉ để hiểu ý và cách xưng hô, không dịch lại):\n")
                append(context.trim()).append('\n')
            }
            append("Đoạn không có lời nói rõ (chỉ có nhạc, tiếng ồn, tiếng thở) thì trả về source và vi rỗng. Không tự bịa lời. Trả về đủ mọi đoạn, mỗi đoạn một lần.\n")
            append("Chỉ trả về JSON dạng {\"lines\":[{\"index\":1,\"source\":\"…\",\"vi\":\"…\",\"emotion\":\"neutral\"}]}, trong đó index là số trong nhãn [S<số>].")
        }

        private val LINES_SCHEMA: JSONObject
            get() = JSONObject(
                """
                {"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{
                  "index":{"type":"integer"},"source":{"type":"string"},"vi":{"type":"string"},"emotion":{"type":"string"}},
                  "required":["index","source","vi","emotion"]}}},"required":["lines"]}
                """.trimIndent(),
            )

        /** Reads a listening answer: piece index (0-based) → what was heard. Null if it is not JSON with lines. */
        fun parseLines(answer: String, expected: Set<Int>): Map<Int, Heard>? {
            val arr = jsonIn(answer)?.optJSONArray("lines") ?: return null
            val out = LinkedHashMap<Int, Heard>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt("index", 0) - 1
                if (index !in expected || index in out) continue
                val vi = o.optString("vi").ifBlank { o.optString("text") }.trim()
                out[index] = Heard(index, o.optString("source").trim(), vi, normaliseEmotion(o.optString("emotion")))
            }
            return out
        }

        /**
         * Who reads a line, the same words for every line in that voice: Gemini's voices drift
         * when the description changes from one request to the next.
         */
        fun persona(role: Role): String = buildString {
            append(
                role.description.ifBlank {
                    when (role.gender) {
                        Gender.MALE -> "an adult man"
                        Gender.FEMALE -> "an adult woman"
                        Gender.CHILD -> "a young child"
                    }
                }.trim().trimEnd('.'),
            )
            if (role.gender == Gender.CHILD && !contains("child", ignoreCase = true)) append(", a child")
        }

        /**
         * The TTS input as Google's speech guide lays it out: directions, then the transcript under
         * its own heading so the directions are not read aloud. The line's tone goes in an English
         * audio tag in front of it, which changes the delivery but not who speaks.
         */
        fun ttsPrompt(role: Role, emotion: String, line: String): String = buildString {
            append("Read the transcript aloud in Vietnamese, exactly as written.\n")
            append("Voice: ").append(persona(role)).append(". Speaks Vietnamese like a native speaker in a real conversation: natural, lively, at a brisk pace.\n")
            append("English words, names and abbreviations in the line are said in natural English by the same voice, flowing with the Vietnamese around them: no pause, no change of voice or tone.\n")
            append("#### TRANSCRIPT\n")
            audioTag(emotion)?.let { append(it).append(' ') }
            append(line)
        }

        /** "laughing" → "[laughs]"; null for no particular tone or anything that is not a short English phrase. */
        fun audioTag(emotion: String): String? {
            val e = normaliseEmotion(emotion)
            if (e.isEmpty() || !Regex("[a-z][a-z ,'-]{1,30}").matches(e)) return null
            return "[" + (TAGS[e] ?: e) + "]"
        }

        private val TAGS = mapOf(
            "laughing" to "laughs", "laugh" to "laughs", "whispering" to "whispers", "whisper" to "whispers",
            "sighing" to "sighs", "sigh" to "sighs", "giggling" to "giggles", "shout" to "shouting", "yelling" to "shouting",
        )

        /** "neutral" and friends mean no particular tone. */
        fun normaliseEmotion(e: String): String {
            val t = e.trim().lowercase().trimEnd('.')
            return if (t.isEmpty() || t in setOf("neutral", "normal", "none", "calm", "plain", "n/a")) "" else t
        }

        /** Consecutive-in-time segments, a few minutes of speech per request. */
        fun batches(ranges: List<SpeechDetector.Range>): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            var ms = 0L
            ranges.forEachIndexed { i, r ->
                val len = r.endMs - r.startMs
                if (cur.isNotEmpty() && (ms + len > BATCH_SPEECH_MS || cur.size >= BATCH_SEGMENTS)) {
                    out += cur
                    cur = ArrayList()
                    ms = 0
                }
                cur += i
                ms += len
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        fun genderOf(s: String): Gender? = when (s.trim().lowercase()) {
            "male", "man", "m", "nam" -> Gender.MALE
            "female", "woman", "f", "nữ", "nu" -> Gender.FEMALE
            "child", "kid", "boy", "girl", "trẻ em" -> Gender.CHILD
            else -> null
        }

        fun shortenRequest(model: String, language: SourceLanguage, lines: List<Heard>, targets: Map<Int, Int>, schema: Boolean): JSONObject {
            val prompt = buildString {
                append("Các câu lồng tiếng sau dài hơn thời gian của câu${language.promptName?.let { " $it" } ?: ""} gốc. ")
                append("Viết lại mỗi câu tiếng Việt cho ngắn hơn, không quá số âm tiết ghi kèm, giữ ý chính, giọng điệu và cách xưng hô, tự nhiên như người Việt nói.\n")
                lines.forEach { l -> append("[${l.index + 1}] Gốc: ${l.source} | Đang dịch: ${l.text} | Tối đa ${targets[l.index]} âm tiết\n") }
                append("Chỉ trả về JSON dạng {\"lines\":[{\"index\":1,\"vi\":\"…\"}]}.")
            }
            return JSONObject().put("model", model).put("input", JSONArray().put(text(prompt))).apply {
                if (schema) put("response_format", jsonFormat(SHORTEN_SCHEMA))
            }
        }

        private val SHORTEN_SCHEMA: JSONObject
            get() = JSONObject("""
                {"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{
                  "index":{"type":"integer"},"vi":{"type":"string"}},"required":["index","vi"]}}},"required":["lines"]}
            """.trimIndent())

        /** index (0-based) → shorter line. */
        fun parseShortened(answer: String): Map<Int, String> {
            val arr = jsonIn(answer)?.optJSONArray("lines") ?: return emptyMap()
            val out = HashMap<Int, String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out[o.optInt("index", 0) - 1] = o.optString("vi")
            }
            return out
        }

        /** One TTS request: the prompt ([ttsPrompt]) and one prebuilt voice. */
        fun ttsRequest(model: String, prompt: String, voice: String): JSONObject {
            val content = JSONObject().put("type", "text").put("text", prompt)
            return JSONObject()
                .put("model", model)
                .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(content))))
                .put("response_format", JSONObject().put("type", "audio"))
                .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        }

        /**
         * Saves Gemini's audio as a 16-bit WAV: kept as is when it already is one, wrapped in a
         * header when it is raw PCM (audio/L16 or audio/pcm, little-endian, 24 kHz unless the type says).
         */
        fun writeAudio(bytes: ByteArray, mime: String, file: File) {
            if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
                file.writeBytes(bytes)
                return
            }
            val m = mime.lowercase()
            if (m.isNotEmpty() && !m.contains("l16") && !m.contains("pcm") && !m.contains("wav")) {
                throw OnlineUnavailableException("Gemini trả về âm thanh dạng $mime, app chưa đọc được")
            }
            val rate = Regex("rate=(\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            val data = bytes.size and 1.inv()
            file.writeBytes(header(rate, data) + bytes.copyOf(data))
        }

        /** Mono 16-bit WAV of samples [from]..[to] of [x]. */
        fun wavBytes(x: FloatArray, from: Int, to: Int, rate: Int): ByteArray {
            val n = (to - from).coerceAtLeast(0)
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in from until to) bb.putShort((x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            return header(rate, n * 2) + bb.array()
        }

        private fun header(rate: Int, dataBytes: Int): ByteArray =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
                put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                put("data".toByteArray()).putInt(dataBytes)
            }.array()

        private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

        private fun jsonFormat(schema: JSONObject) =
            JSONObject().put("type", "text").put("mime_type", "application/json").put("schema", schema)

        /** The JSON object in a model answer, also when it is wrapped in a code fence or prose. */
        private fun jsonIn(answer: String): JSONObject? {
            val a = answer.indexOf('{')
            val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            return runCatching { JSONObject(answer.substring(a, b + 1)) }.getOrNull()
        }
    }
}
