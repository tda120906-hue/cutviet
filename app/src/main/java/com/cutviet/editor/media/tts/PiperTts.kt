package com.cutviet.editor.media.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.cutviet.editor.media.WavConcat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Vietnamese voices bundled in the APK: Piper 1.3 models (VITS, 22.05 kHz) run with ONNX Runtime
 * on the phone, text turned into phonemes by espeak-ng exactly as in training, with English words
 * made Vietnamese by [EnglishInVietnamese]. Works offline, no voice needs to be installed.
 *
 * The models are stored in the APK with fp16 weights (32 MB each instead of 61 MB) that ONNX Runtime
 * turns back into fp32 when it loads them; the sound differs by less than −54 dB.
 */
class PiperTts private constructor(private val context: Context) {
    data class Model(val id: String, val label: String)

    /** Pause after a clause, by its punctuation (at rate 1; shorter when read faster). */
    enum class Pause(val seconds: Double) { NONE(0.04), COMMA(0.12), SENTENCE(0.25) }

    class Piece(val phonemes: List<String>, val pause: Pause)

    private class Loaded(val session: OrtSession, val ids: Map<String, Int>, val noise: Float, val length: Float, val noiseW: Float, val rate: Int)

    private val dir = File(context.noBackupFilesDir, "tts")
    private val loaded = HashMap<String, Loaded>()
    @Volatile private var prepared = false

    /** Copies espeak-ng's data and the models out of the APK (once per app version) and starts espeak. */
    @Synchronized
    fun prepare(): Boolean {
        if (prepared) return true
        if (!EspeakNative.loaded) return false
        return try {
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val marker = File(dir, "version")
            if (!marker.exists() || marker.readText() != stamp) {
                dir.deleteRecursively()
                copyAssets("espeak-ng-data", File(dir, "espeak-ng-data"))
                copyAssets("piper", File(dir, "piper"))
                marker.writeText(stamp)
            }
            prepared = EspeakNative.init(File(dir, "espeak-ng-data").absolutePath)
            prepared
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            false
        }
    }

    private fun copyAssets(path: String, to: File) {
        val names = context.assets.list(path).orEmpty()
        if (names.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it, 256 * 1024) } }
            return
        }
        to.mkdirs()
        for (n in names) copyAssets("$path/$n", File(to, n))
    }

    @Synchronized
    private fun model(id: String): Loaded {
        loaded[id]?.let { return it }
        val json = JSONObject(File(dir, "piper/$id.onnx.json").readText())
        val map = HashMap<String, Int>()
        val m = json.getJSONObject("phoneme_id_map")
        for (k in m.keys()) map[k] = m.getJSONArray(k).getInt(0)
        val inf = json.optJSONObject("inference")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        }
        val session = env.createSession(File(dir, "piper/$id.onnx").absolutePath, opts)
        return Loaded(
            session, map,
            inf?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f,
            inf?.optDouble("length_scale", 1.0)?.toFloat() ?: 1f,
            inf?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f,
            json.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050,
        ).also { loaded[id] = it }
    }

    /** Clauses of [text] as Piper phonemes (one String per phoneme), English made Vietnamese. */
    fun clausesOf(text: String): List<Piece> {
        val vietnamese = Pronunciation.respell(context, cleanText(text), ::englishIpa)
        val raw = EspeakNative.phonemize("vi", vietnamese.toByteArray(Charsets.UTF_8)) ?: return emptyList()
        return clauses(raw)
    }

    /** espeak's English pronunciation of [word] (IPA), or null before the voices are ready. */
    fun englishIpa(word: String): String? {
        if (!prepare()) return null
        val raw = EspeakNative.phonemize("en", word.toByteArray(Charsets.UTF_8)) ?: return null
        return raw.split('\u0002').joinToString(" ") { it.substringBefore('\u0001') }.trim().ifEmpty { null }
    }

    /** The English words in [text] and how the voices will read them, for the "Cách đọc" screen. */
    fun englishReadings(text: String): List<Pronunciation.Reading> =
        Pronunciation.readingsIn(context, cleanText(text), ::englishIpa)

    /**
     * Synthesizes [text] in voice [id] into a 16-bit mono WAV; returns its length in ms or null.
     * [rate] > 1 reads faster; [pitch] > 1 is higher (formants move too, like the phone voices did).
     */
    suspend fun synthesize(id: String, text: String, rate: Float, pitch: Float, out: File): Long? {
        if (!prepare()) return null
        val m = model(id)
        val pieces = clausesOf(text).flatMap { c ->
            val parts = chunk(c.phonemes)
            parts.mapIndexed { i, ph -> Piece(ph, if (i == parts.lastIndex) c.pause else Pause.NONE) }
        }
        if (pieces.isEmpty()) return null
        val r = rate.coerceIn(0.25f, 4f)
        val p = pitch.coerceIn(0.5f, 2f)
        val lengthScale = m.length / r * p
        out.parentFile?.mkdirs()
        var samples = 0L
        var gain: Float? = null
        RandomAccessFile(out, "rw").use { w ->
            w.setLength(0)
            w.write(WavConcat.header(1, m.rate, 0))
            pieces.forEachIndexed { i, piece ->
                currentCoroutineContext().ensureActive()
                val ids = ids(piece.phonemes, m.ids)
                var audio = infer(m, ids, lengthScale) ?: return null
                if (abs(p - 1f) > 0.01f) audio = resample(audio, p.toDouble())
                // Each clause is read on its own and its silences cut, so the pause at a comma or a
                // full stop is the one set here (shorter at a faster rate), not the model's long one.
                val voiced = trimSilence(audio, m.rate)
                // One loudness for the whole text (set by the first clause, lowered only to avoid clipping).
                var peak = 0f
                for (x in voiced) peak = max(peak, abs(x))
                if (peak > 1e-4f) gain = min(gain ?: (0.95f / peak), 0.99f / peak)
                val pcm = toPcm(voiced, gain ?: 1f)
                w.write(bytes(pcm)); samples += pcm.size
                if (i < pieces.lastIndex) {
                    val gap = ShortArray((m.rate * piece.pause.seconds / r).toInt())
                    w.write(bytes(gap)); samples += gap.size
                }
            }
            w.seek(0)
            w.write(WavConcat.header(1, m.rate, samples * 2))
        }
        return samples * 1000L / m.rate
    }

    private fun infer(m: Loaded, ids: LongArray, lengthScale: Float): FloatArray? {
        val env = OrtEnvironment.getEnvironment()
        return try {
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)).use { lens ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(m.noise, lengthScale, m.noiseW)), longArrayOf(3)).use { scales ->
                        m.session.run(mapOf("input" to input, "input_lengths" to lens, "scales" to scales)).use { r ->
                            val fb = (r.get(0) as OnnxTensor).floatBuffer
                            FloatArray(fb.remaining()).also { fb.get(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
            null
        }
    }

    fun close() {
        synchronized(this) {
            loaded.values.forEach { runCatching { it.session.close() } }
            loaded.clear()
        }
    }

    companion object {
        private const val TAG = "CutVietTts"
        /**
         * espeak's clauses as single phonemes, each with the pause after it. Like Piper 1.3 the
         * punctuation stays in (it shapes the melody), every character is split into code points after
         * NFD (tone marks and diacritics are their own phoneme).
         */
        fun clauses(raw: String): List<Piece> {
            val out = ArrayList<Piece>()
            for (clause in raw.split('\u0002')) {
                val parts = clause.split('\u0001')
                val ph = EnglishInVietnamese.convertClause(parts[0]) + parts.getOrElse(1) { "" }
                val list = codePoints(ph)
                if (list.none { it.isNotBlank() && it !in PUNCTUATION }) continue
                val term = parts.getOrElse(1) { "" }
                val pause = when {
                    parts.getOrElse(2) { "0" } == "1" -> Pause.SENTENCE
                    term == "," || term == ":" || term == ";" -> Pause.COMMA
                    else -> Pause.NONE
                }
                out += Piece(list, pause)
            }
            return out
        }

        private val PUNCTUATION = setOf(".", ",", "?", "!", ":", ";")

        private fun codePoints(s: String): List<String> {
            val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
            val out = ArrayList<String>()
            var i = 0
            while (i < nfd.length) {
                val cp = nfd.codePointAt(i)
                out += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            return out
        }

        /** [audio] without the near-silence before and after the voice (20 ms kept, with a short fade). */
        fun trimSilence(audio: FloatArray, rate: Int): FloatArray {
            var peak = 0f
            for (a in audio) peak = max(peak, abs(a))
            if (peak < 1e-4f) return FloatArray(0)
            val thr = peak * 0.01f // −40 dB
            val win = rate / 100
            fun loud(start: Int): Boolean {
                var e = 0.0
                val end = min(audio.size, start + win)
                for (k in start until end) e += audio[k] * audio[k]
                return kotlin.math.sqrt(e / max(1, end - start)) > thr
            }
            var a = 0
            while (a < audio.size && !loud(a)) a += win
            var b = audio.size
            while (b > a && !loud(max(a, b - win))) b -= win
            val keep = rate / 50
            val from = max(0, a - keep)
            val to = min(audio.size, b + keep)
            val out = audio.copyOfRange(from, to)
            val fade = min(rate / 200, out.size / 2)
            for (k in 0 until fade) {
                val g = k.toFloat() / fade
                out[k] *= g
                out[out.size - 1 - k] *= g
            }
            return out
        }

        val MODELS = listOf(
            Model("hoaimy", "Hoài My (nữ)"),
            Model("namminh", "Nam Minh (nam)"),
        )
        const val FEMALE = "hoaimy"
        const val MALE = "namminh"

        @Volatile private var instance: PiperTts? = null

        fun get(context: Context): PiperTts =
            instance ?: synchronized(this) { instance ?: PiperTts(context.applicationContext).also { instance = it } }

        /** Longest phoneme run sent to the model at once; longer sentences are cut between clauses. */
        private const val MAX_PHONEMES = 400

        /**
         * Text the voice should read: emoji and other symbols removed (espeak would read their names),
         * a space after every punctuation mark, "OK" said the Vietnamese way.
         */
        fun cleanText(text: String): String {
            val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
            val sb = StringBuilder()
            var i = 0
            while (i < nfc.length) {
                val cp = nfc.codePointAt(i)
                val type = Character.getType(cp)
                val drop = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
                if (drop) sb.append(' ') else sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return sb.toString()
                // "…" and "..." are a pause, not silence-less run-on text.
                .replace(Regex("(…|\\.{2,})"), ". ")
                // espeak reads a full stop glued to the next word as the word "chấm" (it takes it for
                // "video.mp4"), and drops a comma glued to the next word: always leave a space.
                .replace(Regex("\\s*([.,!?;:])(?=\\p{L})"), "$1 ")
                .replace(Regex("(?<!\\p{L})(?i:ok|okay|oke)(?!\\p{L})"), "ô kê")
        }

        /** A sentence longer than [MAX_PHONEMES], cut after a comma or a space near the limit. */
        fun chunk(s: List<String>): List<List<String>> {
            if (s.size <= MAX_PHONEMES) return listOf(s)
            val out = ArrayList<List<String>>()
            var start = 0
            while (s.size - start > MAX_PHONEMES) {
                val window = start + MAX_PHONEMES
                var cut = (window - 1 downTo start + MAX_PHONEMES / 2).firstOrNull { s[it] in setOf(",", ";", ":") }
                    ?: (window - 1 downTo start + MAX_PHONEMES / 2).firstOrNull { s[it] == " " }
                    ?: (window - 1)
                cut += 1
                out += s.subList(start, cut)
                start = cut
            }
            if (start < s.size) out += s.subList(start, s.size)
            return out
        }

        /** BOS, then each phoneme followed by the pad, then EOS (unknown phonemes are skipped). */
        fun ids(phonemes: List<String>, map: Map<String, Int>): LongArray {
            val out = ArrayList<Long>(phonemes.size * 2 + 3)
            val pad = (map["_"] ?: 0).toLong()
            out += (map["^"] ?: 1).toLong(); out += pad
            for (p in phonemes) {
                val id = map[p] ?: continue
                out += id.toLong(); out += pad
            }
            out += (map["$"] ?: 2).toLong()
            return out.toLongArray()
        }

        /** [audio] times [gain] as 16-bit samples. */
        fun toPcm(audio: FloatArray, gain: Float): ShortArray =
            ShortArray(audio.size) { (audio[it] * gain * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }

        /**
         * Plays [x] [factor] times faster (windowed-sinc interpolation, low-passed when shrinking so
         * nothing aliases). With the model told to read [factor] times slower first, the length stays
         * and the pitch moves by [factor].
         */
        fun resample(x: FloatArray, factor: Double): FloatArray {
            val n = (x.size / factor).toInt()
            val fc = min(1.0, 1.0 / factor)
            val half = 12
            val out = FloatArray(n)
            for (j in 0 until n) {
                val pos = j * factor
                val c = floor(pos).toInt()
                var acc = 0.0
                for (k in c - half + 1..c + half) {
                    if (k < 0 || k >= x.size) continue
                    val d = pos - k
                    val arg = PI * d * fc
                    val sinc = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
                    val win = 0.5 + 0.5 * cos(PI * d / half)
                    acc += x[k] * sinc * fc * win
                }
                out[j] = acc.toFloat()
            }
            return out
        }

        private fun bytes(s: ShortArray): ByteArray {
            val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.asShortBuffer().put(s)
            return b.array()
        }
    }
}
