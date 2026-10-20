package com.cutviet.editor.media.dubbing

import com.cutviet.editor.media.VoiceEngine
import com.cutviet.editor.media.dsp.WavWriter
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Natural online dub with Gemini 3.8. First the text model listens to every speech segment of the
 * separated voice in one request: who says it (the same person keeps the same id), whether that
 * person is a man, a woman or a child, how they sound, the tone of each line, and a Vietnamese
 * translation that fits the line's time. Then the TTS model reads each person's lines in a voice
 * of their own and in their tone. Lines it can no longer voice (daily quota used up) come back
 * without a file, and the pipeline reads them with the phone's voice.
 */
class GeminiDubber(
    private val api: GeminiApi,
    private val textModel: String,
    private val ttsModel: String,
    private val workDir: File,
    /** Give each person a voice of their own; off: one voice for everyone. */
    private val distinguish: Boolean,
) : OnlineDubber {

    /** A person heard in the video. [gender] is null when the model did not say. */
    data class Speaker(val id: String, val gender: Gender?, val description: String)

    /** One segment as the model heard it ([index] into the speech ranges). */
    data class Heard(val index: Int, val speaker: String, val source: String, val text: String, val emotion: String)

    class Listening(val speakers: List<Speaker>, val lines: List<Heard>)

    /** The dubbing voice of one person; [label] ("Nam 1") is null when everyone shares one voice. */
    data class Role(val label: String?, val voice: String, val gender: Gender, val description: String)

    private var stoppedNotice: String? = null
    override val notice: String? get() = stoppedNotice

    private class Voiced(val file: File, val ms: Long)

    override suspend fun dub(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): List<OnlineLine> {
        try {
            val room = DubbingPipeline.roomFor(ranges.map { it.startMs to (it.endMs - it.startMs) }, voice.size / 16L)
            val budgets = room.map { TranslationPrompt.syllableBudget(it, SYLLABLES_PER_SECOND) }
            val heard = listen(voice, ranges, budgets, language) { progress(0.3f * it) }
            val lines = heard.lines.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) throw OnlineUnavailableException("Gemini không nghe ra câu nào để dịch")

            // A person the model gave no gender gets one from the pitch of their voice.
            val speakers = heard.speakers.filter { s -> lines.any { it.speaker == s.id } }.map { s ->
                if (s.gender != null) s
                else s.copy(gender = pitchGender(voice, lines.filter { it.speaker == s.id }.map { ranges[it.index] }) ?: Gender.FEMALE)
            }
            val main = lines.groupingBy { it.speaker }.eachCount().maxByOrNull { it.value }?.key
            val roles = cast(speakers, distinguish, main)
            DubLog.i(TAG, "cast: " + speakers.joinToString { s -> "${s.id}=${roles[s.id]?.label ?: "-"}/${roles[s.id]?.voice} (${s.gender}, ${s.description.take(60)})" })
            val fallbackRole = roles[main] ?: Role(null, POOLS.getValue(Gender.FEMALE).first(), Gender.FEMALE, "")
            fun roleOf(l: Heard) = roles[l.speaker] ?: fallbackRole

            val voiced = HashMap<Int, Voiced>()
            val texts = lines.associate { it.index to it.text }.toMutableMap()
            var stopped: String? = null
            val groups = groups(lines) { roleOf(it) }
            var done = 0
            for (g in groups) {
                if (stopped == null) {
                    try {
                        voiced.putAll(voiceGroup(g, roleOf(g.first())))
                    } catch (e: OnlineUnavailableException) {
                        // Quota, key or network: the phone's voice reads the rest.
                        stopped = e.message
                        DubLog.w(TAG, "stopped voicing online: ${e.message}")
                    }
                }
                done += g.size
                progress(0.3f + 0.6f * done / lines.size)
            }

            // Lines still too long at 1.15x: reworded shorter (one request for all), voiced again.
            val long = lines.filter { l ->
                val v = voiced[l.index] ?: return@filter false
                v.ms / AudioFitter.MAX_RATE > AudioFitter.upper(ranges[l.index].let { it.endMs - it.startMs }, room[l.index])
            }.take(MAX_REWORDS)
            if (stopped == null && long.isNotEmpty()) {
                try {
                    reword(long, voiced, ranges, room, texts, language, ::roleOf)
                } catch (e: OnlineUnavailableException) {
                    DubLog.w(TAG, "rewording stopped: ${e.message}")
                }
            }
            progress(1f)

            if (stopped != null) {
                val left = lines.count { it.index !in voiced }
                stoppedNotice = "$left câu đọc bằng giọng của máy ($stopped)"
            }
            return lines.map { l ->
                val r = ranges[l.index]
                val role = roleOf(l)
                val v = voiced[l.index]
                val speed = if (v != null && room[l.index] > 0) (v.ms.toFloat() / room[l.index]).coerceIn(1f, AudioFitter.MAX_RATE) else 1f
                if (v != null) {
                    DubLog.i(TAG, "line ${l.index} ${role.label ?: ""}/${role.voice} measured=${v.ms}ms slot=${r.endMs - r.startMs}ms room=${room[l.index]}ms speed=$speed")
                }
                OnlineLine(r.startMs, r.endMs, room[l.index], l.source, texts.getValue(l.index), role.label, role.gender, v?.file, v?.ms ?: 0, speed)
            }
        } finally {
            api.shutdown()
        }
    }

    /** Asks the text model about the segments, in batches of a few minutes of speech. */
    private suspend fun listen(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        budgets: List<Int>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): Listening {
        val speakers = LinkedHashMap<String, Speaker>()
        val lines = HashMap<Int, Heard>()
        val batches = batches(ranges)
        batches.forEachIndexed { b, indices ->
            val what = "nghe ${b + 1}/${batches.size}"
            val known = speakers.values.toList()
            val text = askJson(what) { schema -> listenRequest(textModel, language, voice, ranges, indices, budgets, known, schema) }
            val parsed = parseListening(text, indices.toSet())
                ?: throw OnlineUnavailableException("Không đọc được câu trả lời của Gemini ($what)")
            parsed.speakers.forEach { s -> if (s.id !in speakers || speakers[s.id]?.gender == null) speakers[s.id] = s }
            parsed.lines.forEach { lines[it.index] = it }
            progress((b + 1f) / batches.size)
        }
        // Ids used in lines but never described.
        lines.values.map { it.speaker }.distinct().filter { it !in speakers }.forEach { speakers[it] = Speaker(it, null, "") }
        DubLog.i(TAG, "listened: ${speakers.size} speakers, ${lines.size} of ${ranges.size} segments")
        return Listening(speakers.values.toList(), lines.values.sortedBy { it.index })
    }

    /** Sends a request built with a JSON schema; if the server refuses the schema, asks again without one. */
    private suspend fun askJson(what: String, build: (schema: Boolean) -> JSONObject): String {
        val answer = try {
            api.interact(build(true), what)
        } catch (e: GeminiApi.Rejected) {
            DubLog.w(TAG, "$what: refused with a schema, asking without: ${e.message}")
            api.interact(build(false), what)
        }
        return GeminiApi.outputText(answer) ?: throw OnlineUnavailableException("Gemini không trả lời ($what)")
    }

    /**
     * Voices one group of a person's lines: in one request with long pauses between them, cut
     * back into lines at those pauses; one request per line when the cut is not clear.
     */
    private suspend fun voiceGroup(group: List<Heard>, role: Role): Map<Int, Voiced> {
        val out = HashMap<Int, Voiced>()
        val emotion = group.first().emotion
        if (group.size > 1) {
            val what = "đọc câu ${group.joinToString(",") { "${it.index + 1}" }}"
            val whole = File(workDir, "gemini_${group.first().index}_all.wav")
            val read = try {
                tts(group.joinToString(" $LONG_PAUSE ") { it.text }, role, emotion, what, whole)
                true
            } catch (e: GeminiApi.Rejected) {
                DubLog.w(TAG, "$what refused together: ${e.message}")
                false
            }
            val mono = if (read) Loudness.readWav(whole) else null
            val pieces = mono?.let { m -> SilenceSplit.split(m.samples, m.rate, group.map { VoiceEngine.syllableCount(it.text) }) }
            whole.delete()
            if (mono != null && pieces != null) {
                group.forEachIndexed { k, l ->
                    val f = File(workDir, "gemini_${l.index}.wav")
                    WavWriter(f, mono.rate, 1).use { w -> w.write(mono.samples, pieces[k].first, pieces[k].last - pieces[k].first + 1) }
                    out[l.index] = Voiced(f, WavTrim.trim(f) ?: 0)
                }
                return out
            }
            DubLog.w(TAG, "$what: pauses not clear enough to cut, voicing each line")
        }
        for (l in group) {
            val f = File(workDir, "gemini_${l.index}.wav")
            try {
                tts(l.text, role, l.emotion, "đọc câu ${l.index + 1}", f)
                out[l.index] = Voiced(f, WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: 0)
            } catch (e: GeminiApi.Rejected) {
                DubLog.w(TAG, "line ${l.index} refused, phone voice reads it: ${e.message}")
            }
        }
        return out
    }

    private suspend fun tts(text: String, role: Role, emotion: String, what: String, file: File) {
        val answer = api.interact(ttsRequest(ttsModel, text, role.voice, style(role, emotion)), what)
        val (bytes, mime) = GeminiApi.outputAudio(answer) ?: throw OnlineUnavailableException("Gemini không trả về giọng đọc ($what)")
        writeAudio(bytes, mime, file)
    }

    /** One shorter wording for every line in [long], each voiced again and kept when it fits better. */
    private suspend fun reword(
        long: List<Heard>,
        voiced: MutableMap<Int, Voiced>,
        ranges: List<SpeechDetector.Range>,
        room: List<Long>,
        texts: MutableMap<Int, String>,
        language: SourceLanguage,
        roleOf: (Heard) -> Role,
    ) {
        val targets = long.associate { l ->
            val v = voiced.getValue(l.index)
            val slot = ranges[l.index].endMs - ranges[l.index].startMs
            val limit = AudioFitter.upper(slot, room[l.index]) * AudioFitter.MAX_RATE
            l.index to (VoiceEngine.syllableCount(l.text) * limit / v.ms * 0.9).toInt().coerceAtLeast(2)
        }
        val text = askJson("rút gọn ${long.size} câu") { schema -> shortenRequest(textModel, language, long, targets, schema) }
        val shorter = parseShortened(text)
        for (l in long) {
            val s = shorter[l.index]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (VoiceEngine.syllableCount(s) >= VoiceEngine.syllableCount(l.text)) continue
            val f = File(workDir, "gemini_${l.index}_short.wav")
            try {
                tts(s, roleOf(l), l.emotion, "đọc lại câu ${l.index + 1}", f)
            } catch (e: GeminiApi.Rejected) {
                continue
            }
            val ms = WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: continue
            val slot = ranges[l.index].endMs - ranges[l.index].startMs
            val before = voiced.getValue(l.index)
            fun playedMiss(d: Long) = AudioFitter.miss((d / AudioFitter.MAX_RATE).toLong(), slot, room[l.index])
            DubLog.i(TAG, "line ${l.index} reworded ${VoiceEngine.syllableCount(l.text)}→${VoiceEngine.syllableCount(s)} syllables: ${before.ms}→$ms ms")
            if (playedMiss(ms) < playedMiss(before.ms)) {
                before.file.delete()
                voiced[l.index] = Voiced(f, ms)
                texts[l.index] = s
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
        const val LONG_PAUSE = "<long pause>"
        /** Speech per listening request; 16 kHz WAV, so well under the 20 MB request limit. */
        private const val BATCH_SPEECH_MS = 180_000L
        private const val BATCH_SEGMENTS = 40
        /** Lines read in one TTS request, and their length. */
        private const val GROUP_LINES = 6
        private const val GROUP_CHARS = 400
        private const val MAX_REWORDS = 8
        /** Audio kept around each segment sent for listening. */
        private const val PAD_MS = 150L

        /**
         * Prebuilt Gemini voices by gender (Google's voice list), most neutral first. Children get
         * light, young voices and are described as children in the style.
         */
        val POOLS: Map<Gender, List<String>> = mapOf(
            Gender.MALE to listOf("Charon", "Puck", "Orus", "Iapetus", "Algenib", "Umbriel", "Achird", "Sadaltager", "Fenrir", "Enceladus", "Alnilam", "Schedar"),
            Gender.FEMALE to listOf("Kore", "Aoede", "Despina", "Sulafat", "Callirrhoe", "Achernar", "Vindemiatrix", "Erinome", "Zephyr", "Autonoe", "Gacrux", "Pulcherrima"),
            Gender.CHILD to listOf("Leda", "Laomedeia", "Autonoe", "Puck"),
        )

        /** Voices that suit a described voice better, tried before the rest of the pool. */
        private val PREFERRED: List<Triple<Regex, Gender, List<String>>> = listOf(
            Triple(Regex("\\b(old|older|elderly|senior|aged|grandfather|grandpa|[6-9]0s)\\b"), Gender.MALE, listOf("Algenib", "Charon", "Rasalgethi")),
            Triple(Regex("\\b(old|older|elderly|senior|aged|grandmother|grandma|[6-9]0s)\\b"), Gender.FEMALE, listOf("Gacrux", "Sulafat", "Vindemiatrix")),
            Triple(Regex("\\b(young|teen|teenage|teenager|youthful|boy|20s)\\b"), Gender.MALE, listOf("Puck", "Achird", "Fenrir")),
            Triple(Regex("\\b(young|teen|teenage|teenager|youthful|girl|20s)\\b"), Gender.FEMALE, listOf("Leda", "Zephyr", "Autonoe")),
            Triple(Regex("\\b(deep|low|bass|husky|gravelly|raspy)\\b"), Gender.MALE, listOf("Charon", "Orus", "Algenib")),
            Triple(Regex("\\b(deep|low|husky|raspy)\\b"), Gender.FEMALE, listOf("Kore", "Sulafat", "Gacrux")),
            Triple(Regex("\\b(soft|gentle|quiet|breathy|calm)\\b"), Gender.MALE, listOf("Enceladus", "Umbriel", "Iapetus")),
            Triple(Regex("\\b(soft|gentle|quiet|breathy|calm)\\b"), Gender.FEMALE, listOf("Achernar", "Vindemiatrix", "Despina")),
        )

        /**
         * Gives every person a voice. Apart: each gets a voice of their gender that nobody else has
         * (while the pool lasts) and a label such as "Nữ 2". Together: everyone shares the voice of
         * the [main] speaker.
         */
        fun cast(speakers: List<Speaker>, distinguish: Boolean, main: String?): Map<String, Role> {
            if (!distinguish) {
                val m = speakers.firstOrNull { it.id == main } ?: speakers.firstOrNull()
                val g = m?.gender ?: Gender.FEMALE
                val role = Role(null, choose(m?.description.orEmpty(), g, emptySet()) ?: POOLS.getValue(g).first(), g, m?.description.orEmpty())
                return speakers.associate { it.id to role }
            }
            val used = HashSet<String>()
            val count = HashMap<Gender, Int>()
            return speakers.associate { s ->
                val g = s.gender ?: Gender.FEMALE
                val n = (count[g] ?: 0) + 1
                count[g] = n
                val pool = POOLS.getValue(g)
                val voice = choose(s.description, g, used) ?: pool[(n - 1) % pool.size]
                used += voice
                s.id to Role("${g.label} $n", voice, g, s.description)
            }
        }

        private fun choose(description: String, gender: Gender, used: Set<String>): String? {
            val d = description.lowercase()
            val preferred = PREFERRED.filter { it.second == gender && it.first.containsMatchIn(d) }.flatMap { it.third }
            return (preferred + POOLS.getValue(gender)).firstOrNull { it !in used }
        }

        /** Style for the TTS model: who speaks, in Vietnamese, at a pace that fits dubbing, and the line's tone. */
        fun style(role: Role, emotion: String): String = buildString {
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
            append(". Speaks Vietnamese like a native speaker in a real conversation: natural, lively, at a brisk pace")
            if (emotion.isNotEmpty()) append(". Tone: ").append(emotion)
        }

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

        /**
         * Lines read together: the same voice and tone, in order, at most [GROUP_LINES] lines and
         * [GROUP_CHARS] characters.
         */
        fun groups(lines: List<Heard>, roleOf: (Heard) -> Role): List<List<Heard>> {
            val open = LinkedHashMap<Pair<Role, String>, MutableList<Heard>>()
            val out = ArrayList<List<Heard>>()
            for (l in lines) {
                val key = roleOf(l) to l.emotion
                val g = open[key]
                if (g != null && g.size < GROUP_LINES && g.sumOf { it.text.length + LONG_PAUSE.length + 2 } + l.text.length <= GROUP_CHARS) {
                    g += l
                } else {
                    g?.let { out += it }
                    open[key] = mutableListOf(l)
                }
            }
            out += open.values
            return out.sortedBy { it.first().index }
        }

        fun listenRequest(
            model: String,
            language: SourceLanguage,
            voice: FloatArray,
            ranges: List<SpeechDetector.Range>,
            indices: List<Int>,
            budgets: List<Int>,
            known: List<Speaker>,
            schema: Boolean,
        ): JSONObject {
            val input = JSONArray().put(text(listenPrompt(language, indices.size, known)))
            for (i in indices) {
                input.put(text("[S${i + 1}] (tối đa ${budgets[i]} âm tiết)"))
                val r = ranges[i]
                val from = ((r.startMs - PAD_MS) * 16).toInt().coerceIn(0, voice.size)
                val to = ((r.endMs + PAD_MS) * 16).toInt().coerceIn(from, voice.size)
                input.put(JSONObject().put("type", "audio").put("data", wavBytes(voice, from, to, 16_000).toByteString().base64()).put("mime_type", "audio/wav"))
            }
            return JSONObject().put("model", model).put("input", input).apply {
                if (schema) put("response_format", jsonFormat(LISTEN_SCHEMA))
            }
        }

        private fun listenPrompt(language: SourceLanguage, count: Int, known: List<Speaker>): String = buildString {
            append("Bạn là đạo diễn lồng tiếng phim. Dưới đây là $count đoạn ghi âm lời nói ${language.label.lowercase()} trong một video, đúng thứ tự thời gian. ")
            append("Mỗi đoạn có nhãn [S<số>] và số âm tiết tối đa của câu tiếng Việt.\n")
            append("Với mỗi đoạn:\n")
            append("1. Nhận ra ai đang nói. Cùng một người ở nhiều đoạn thì dùng cùng một mã (A, B, C…). Phân biệt bằng giọng nghe được, không đoán theo nội dung.\n")
            append("2. Ghi nguyên văn câu gốc (source).\n")
            append("3. Dịch sang tiếng Việt tự nhiên như người Việt nói ngoài đời (vi): đúng sắc thái, xưng hô hợp với người nói và người nghe (anh, chị, em, tôi, mình…), không dịch từng chữ, không dài hơn số âm tiết cho phép.\n")
            append("4. Ghi giọng điệu của câu bằng vài từ tiếng Anh (emotion), ví dụ neutral, excited, laughing, annoyed, sad, whispering, shouting.\n")
            append("Với mỗi người nói: giới tính theo giọng (gender: male, female, hoặc child nếu là trẻ em) và mô tả giọng bằng một câu tiếng Anh ngắn (voice): tuổi ước chừng, giọng trầm hay cao, nói nhanh hay chậm, phong thái.\n")
            if (known.isNotEmpty()) {
                append("Những người đã nhận ra ở phần trước của video (dùng lại mã nếu đúng là họ):\n")
                known.forEach { append("- ${it.id}: ${it.gender?.name?.lowercase() ?: "?"}, ${it.description}\n") }
            }
            append("Đoạn không có lời nói rõ thì vẫn trả về, với vi rỗng.\n")
            append("Chỉ trả về JSON dạng {\"speakers\":[{\"id\":\"A\",\"gender\":\"male\",\"voice\":\"…\"}],\"lines\":[{\"index\":1,\"speaker\":\"A\",\"source\":\"…\",\"vi\":\"…\",\"emotion\":\"neutral\"}]}, trong đó index là số trong nhãn [S<số>].")
        }

        private val LISTEN_SCHEMA: JSONObject
            get() = JSONObject("""
                {"type":"object","properties":{
                  "speakers":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string"},
                    "gender":{"type":"string","enum":["male","female","child"]},
                    "voice":{"type":"string"}},"required":["id","gender","voice"]}},
                  "lines":{"type":"array","items":{"type":"object","properties":{
                    "index":{"type":"integer"},
                    "speaker":{"type":"string"},
                    "source":{"type":"string"},
                    "vi":{"type":"string"},
                    "emotion":{"type":"string"}},"required":["index","speaker","source","vi","emotion"]}}},
                 "required":["speakers","lines"]}
            """.trimIndent())

        /** Reads the listening answer; segments are numbered from 1 in the prompt. */
        fun parseListening(answer: String, expected: Set<Int>): Listening? {
            val json = jsonIn(answer) ?: return null
            val speakers = ArrayList<Speaker>()
            json.optJSONArray("speakers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) continue
                    speakers += Speaker(id, genderOf(o.optString("gender")), o.optString("voice").trim())
                }
            }
            val lines = ArrayList<Heard>()
            val arr = json.optJSONArray("lines") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt("index", -1) - 1
                if (index !in expected || lines.any { it.index == index }) continue
                val speaker = o.optString("speaker").trim().ifEmpty { "?" }
                lines += Heard(index, speaker, o.optString("source").trim(), o.optString("vi").trim(), normaliseEmotion(o.optString("emotion")))
            }
            return Listening(speakers, lines.sortedBy { it.index })
        }

        fun genderOf(s: String): Gender? = when (s.trim().lowercase()) {
            "male", "man", "m", "nam" -> Gender.MALE
            "female", "woman", "f", "nữ", "nu" -> Gender.FEMALE
            "child", "kid", "boy", "girl", "trẻ em" -> Gender.CHILD
            else -> null
        }

        fun shortenRequest(model: String, language: SourceLanguage, lines: List<Heard>, targets: Map<Int, Int>, schema: Boolean): JSONObject {
            val prompt = buildString {
                append("Các câu lồng tiếng sau dài hơn thời gian của câu ${language.label.lowercase()} gốc. ")
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

        /** One TTS request: the text, one prebuilt voice, and a style for the whole text. */
        fun ttsRequest(model: String, text: String, voice: String, style: String): JSONObject {
            val content = JSONObject().put("type", "text").put("text", text)
                .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
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

        /** Man, woman or child from the pitch of a few of a person's segments. */
        fun pitchGender(voice: FloatArray, ranges: List<SpeechDetector.Range>): Gender? {
            val f0s = ranges.take(6).mapNotNull { r ->
                PitchTracker.medianF0(voice, 16_000, (r.startMs * 16).toInt(), (r.endMs * 16).toInt())
            }
            if (f0s.isEmpty()) return null
            return PitchTracker.gender(f0s.sorted()[f0s.size / 2])
        }
    }
}
