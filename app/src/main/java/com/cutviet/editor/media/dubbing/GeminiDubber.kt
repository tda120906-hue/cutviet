package com.cutviet.editor.media.dubbing

import com.cutviet.editor.media.VoiceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Natural online dub with Gemini 3.8. First the text model listens exactly as for the AI subtitles
 * ([GeminiSubtitler]): each segment of the separated voice is cut into turns of one person each
 * (the same person keeps the same id), with whether that person is a man, a woman or a child, how
 * they sound, the tone of each turn, and a natural subtitle-style Vietnamese translation; turns
 * that come out too long are reworded shorter later. Then the TTS model reads each turn on its own (so no line
 * can end up with another line's audio), every line of a person in the same voice and description,
 * with the line's tone as an inline tag. People are voiced one after another; when the daily quota
 * runs out, the person cut short and everyone after come back without files, and the pipeline
 * reads them with the phone's voice, so nobody is heard in two voices.
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

    /** The dubbing voice of one person; [label] ("Nam 1") is null when everyone shares one voice. */
    data class Role(val label: String?, val voice: String, val gender: Gender, val description: String)

    private var stoppedNotice: String? = null
    override val notice: String? get() = stoppedNotice

    private class Voiced(val file: File, val ms: Long)

    override suspend fun dub(
        voice: FloatArray,
        segments: List<SpeechDetector.Range>,
        language: SourceLanguage,
        progress: (Float) -> Unit,
    ): List<OnlineLine> {
        try {
            // The same listening as the AI subtitles: who says what, turn by turn, translated as a
            // subtitle (natural, not squeezed to a syllable count). Each turn is one dubbed line.
            val heard = GeminiSubtitler(api, textModel).listen(voice, segments, language) { progress(0.3f * it) }
            val ranges = heard.turns.map { SpeechDetector.Range(it.startMs, it.endMs) }
            val lines = heard.turns.mapIndexed { i, t -> Heard(i, t.speaker, t.source, t.vi, t.emotion) }.filter { it.text.isNotBlank() }
            val room = DubbingPipeline.roomFor(ranges.map { it.startMs to (it.endMs - it.startMs) }, voice.size / 16L)
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
            var done = 0
            for (person in voicingOrder(lines, ::roleOf)) {
                if (stopped == null) {
                    val mine = HashMap<Int, Voiced>()
                    for (l in person) {
                        try {
                            voiceLine(l, roleOf(l))?.let { mine[l.index] = it }
                        } catch (e: OnlineUnavailableException) {
                            // Quota, key or network: the phone's voice reads this person and the rest.
                            stopped = e.message
                            DubLog.w(TAG, "stopped voicing online at line ${l.index}: ${e.message}")
                            break
                        }
                        done++
                        progress(0.3f + 0.6f * done / lines.size)
                    }
                    if (stopped == null) voiced.putAll(mine)
                    else mine.values.forEach { it.file.delete() } // all of this person in one voice
                }
            }
            progress(0.9f)

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
                val left = lines.filter { it.index !in voiced }
                val people = left.mapNotNull { roleOf(it).label }.distinct()
                stoppedNotice = "${left.size} câu" + (if (people.isEmpty()) "" else " của " + people.joinToString()) +
                    " đọc bằng giọng của máy ($stopped)"
            }
            return lines.map { l ->
                val r = ranges[l.index]
                val role = roleOf(l)
                val v = voiced[l.index]
                val speed = if (v != null && room[l.index] > 0) (v.ms.toFloat() / room[l.index]).coerceIn(1f, AudioFitter.MAX_RATE) else 1f
                DubLog.i(
                    TAG,
                    "line ${l.index} at ${r.startMs}-${r.endMs}ms ${role.label ?: ""}/${if (v != null) role.voice else "phone"} " +
                        "\"${l.source.take(50)}\" → \"${texts.getValue(l.index).take(50)}\"" +
                        (if (v != null) " measured=${v.ms}ms room=${room[l.index]}ms speed=$speed" else ""),
                )
                OnlineLine(
                    r.startMs, r.endMs, room[l.index], l.source, texts.getValue(l.index), role.label, role.gender, v?.file, v?.ms ?: 0, speed,
                    voice = if (v != null) role.voice else null, description = role.description, emotion = l.emotion,
                )
            }
        } finally {
            api.shutdown()
        }
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

    /** Voices one line on its own; null when Gemini refuses that line (the phone reads it). */
    private suspend fun voiceLine(l: Heard, role: Role): Voiced? {
        val f = File(workDir, "gemini_${l.index}.wav")
        return try {
            tts(l.text, role, l.emotion, "đọc câu ${l.index + 1}", f)
            Voiced(f, WavTrim.trim(f) ?: VoiceEngine.durationOf(f) ?: 0)
        } catch (e: GeminiApi.Rejected) {
            DubLog.w(TAG, "line ${l.index} refused, phone voice reads it: ${e.message}")
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
        /** Speech per listening request; 16 kHz WAV, so well under the 20 MB request limit. */
        private const val BATCH_SPEECH_MS = 180_000L
        private const val BATCH_SEGMENTS = 40
        /** Translations are not squeezed to fit while listening, so more lines may need a shorter wording. */
        private const val MAX_REWORDS = 30

        /**
         * Prebuilt Gemini voices by gender (Google's voice list), most neutral first. Children get
         * light, young voices and are described as children in the style.
         */
        val POOLS: Map<Gender, List<String>> = mapOf(
            Gender.MALE to listOf("Charon", "Puck", "Orus", "Iapetus", "Algenib", "Umbriel", "Achird", "Sadaltager", "Fenrir", "Enceladus", "Alnilam", "Schedar", "Rasalgethi", "Algieba", "Zubenelgenubi", "Sadachbia"),
            Gender.FEMALE to listOf("Kore", "Aoede", "Despina", "Sulafat", "Callirrhoe", "Achernar", "Vindemiatrix", "Erinome", "Zephyr", "Autonoe", "Gacrux", "Pulcherrima", "Leda", "Laomedeia"),
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

        /**
         * Who reads a person's lines, the same words for every line of theirs: Gemini's voices
         * drift when the description changes from one request to the next.
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

        /**
         * Lines in the order they are voiced: person by person, the one with the most lines
         * first, each person's lines in time order.
         */
        fun voicingOrder(lines: List<Heard>, roleOf: (Heard) -> Role): List<List<Heard>> =
            lines.groupBy(roleOf).values.sortedWith(compareByDescending<List<Heard>> { it.size }.thenBy { it.first().index })

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
