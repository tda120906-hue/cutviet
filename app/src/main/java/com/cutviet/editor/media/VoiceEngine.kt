package com.cutviet.editor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Vietnamese text-to-speech using the voices installed on the phone (Google TTS, Samsung TTS…). */
class VoiceEngine(context: Context) {
    private val appContext = context.applicationContext

    enum class Status { LOADING, READY, NO_VIETNAMESE, FAILED }

    data class VoiceOption(val name: String, val label: String, val needsNetwork: Boolean)

    /** A voice style ("mẫu giọng"): pitch applied on top of the chosen voice. */
    data class Style(val label: String, val pitch: Float)

    val status = mutableStateOf(Status.LOADING)
    val voices = mutableStateOf<List<VoiceOption>>(emptyList())

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var tts: TextToSpeech? = null

    /** Measured syllables per second at rate 1.0, per voice; refined after every synthesis. */
    private val calibration = HashMap<String, Double>()

    init {
        tts = TextToSpeech(appContext) { s ->
            if (s != TextToSpeech.SUCCESS) {
                status.value = Status.FAILED
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.complete(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.complete(false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { pending.remove(it)?.complete(false) }
                }
            })
            val vi = Locale("vi", "VN")
            val available = engine.isLanguageAvailable(vi)
            if (available < TextToSpeech.LANG_AVAILABLE) {
                status.value = Status.NO_VIETNAMESE
                return@TextToSpeech
            }
            engine.language = vi
            val list = runCatching { engine.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "vi" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
            voices.value = list.mapIndexed { i, v ->
                VoiceOption(
                    name = v.name,
                    label = "Giọng ${i + 1}" + if (v.isNetworkConnectionRequired) " (online)" else "",
                    needsNetwork = v.isNetworkConnectionRequired,
                )
            }.ifEmpty { listOf(VoiceOption(name = "", label = "Giọng mặc định", needsNetwork = false)) }
            status.value = Status.READY
        }
    }

    private fun apply(voiceName: String?, pitch: Float, rate: Float) {
        val engine = tts ?: return
        if (!voiceName.isNullOrEmpty()) {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        } else {
            engine.language = Locale("vi", "VN")
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    fun speak(text: String, voiceName: String?, pitch: Float, rate: Float) {
        val engine = tts ?: return
        apply(voiceName, pitch, rate)
        // Text longer than the engine takes at once is queued piece by piece.
        splitForEngine(text, maxInputLength).forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "preview$i")
        }
    }

    fun stop() {
        tts?.stop()
    }

    /** Most characters the engine takes in one request; longer text is split ([splitForEngine]). */
    val maxInputLength: Int
        get() = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000) - 50

    /**
     * Synthesizes [text] of any length into a WAV file and returns its exact duration in ms, or null
     * on failure. Text over the engine's limit is read piece by piece (split between sentences) and
     * the pieces are joined into one WAV. Must be called from a coroutine.
     */
    suspend fun synthesize(text: String, voiceName: String?, pitch: Float, rate: Float, out: File): Long? {
        if (status.value != Status.READY || text.isBlank()) return null
        val parts = splitForEngine(text, maxInputLength)
        val ms = if (parts.size == 1) {
            synthesizeOne(parts[0], voiceName, pitch, rate, out)
        } else {
            val pieces = parts.mapIndexed { i, _ -> File(out.parentFile, "${out.nameWithoutExtension}_part$i.wav") }
            try {
                for (i in parts.indices) {
                    synthesizeOne(parts[i], voiceName, pitch, rate, pieces[i]) ?: return null
                }
                WavConcat.join(pieces, out)
            } finally {
                pieces.forEach { it.delete() }
            }
        } ?: return null
        // Learn this voice's real speed so the instant estimate gets closer each time.
        val syllables = syllableCount(text)
        val speechSec = ms / 1000.0 * rate - pauseSeconds(text)
        if (syllables >= 3 && speechSec > 0.3) calibration[voiceName.orEmpty()] = syllables / speechSec
        return ms
    }

    private suspend fun synthesizeOne(text: String, voiceName: String?, pitch: Float, rate: Float, out: File): Long? {
        val engine = tts ?: return null
        val id = "u" + System.nanoTime()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        synchronized(this) {
            apply(voiceName, pitch, rate)
            out.parentFile?.mkdirs()
            val r = engine.synthesizeToFile(text, Bundle(), out, id)
            if (r != TextToSpeech.SUCCESS) {
                pending.remove(id)
                return null
            }
        }
        val ok = withTimeoutOrNull(120_000) { done.await() } ?: false
        if (!ok || !out.exists() || out.length() < 100) return null
        return durationOf(out)
    }

    /** Instant estimate of the spoken length in ms (before synthesizing). */
    fun estimateMs(text: String, voiceName: String?, rate: Float): Long {
        val syllables = syllableCount(text)
        if (syllables == 0) return 0
        val sps = calibration[voiceName.orEmpty()] ?: DEFAULT_SYLLABLES_PER_SECOND
        val seconds = (syllables / sps + pauseSeconds(text)) / max(0.1f, rate)
        return (seconds * 1000).toLong()
    }

    fun shutdown() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        /** Typical Vietnamese TTS speed at rate 1.0 (one word = one syllable in Vietnamese). */
        const val DEFAULT_SYLLABLES_PER_SECOND = 4.3

        val STYLES = listOf(
            Style("Tự nhiên", 1.0f),
            Style("Trầm ấm", 0.82f),
            Style("Trong trẻo", 1.15f),
            Style("Trẻ em", 1.45f),
            Style("Lớn tuổi", 0.7f),
        )

        /**
         * Splits [text] into pieces of at most [max] characters for the engine, cutting after a
         * sentence end if possible, else after a comma, else at a space; short text stays whole.
         */
        fun splitForEngine(text: String, max: Int): List<String> {
            val limit = max.coerceAtLeast(20)
            val parts = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                val cut = listOf(Regex("[.!?…\\n][\"”’)\\]]*(?=\\s)"), Regex("[,;:](?=\\s)"), Regex("\\s"))
                    .firstNotNullOfOrNull { r -> r.findAll(window).lastOrNull()?.takeIf { it.range.last >= limit / 2 }?.range?.last }
                val end = (cut ?: (limit - 1)) + 1
                parts += rest.substring(0, end).trim()
                rest = rest.substring(end).trim()
            }
            if (rest.isNotEmpty()) parts += rest
            return parts.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
        }

        fun syllableCount(text: String): Int =
            text.split(Regex("[\\s\\p{Punct}…“”‘’–—]+")).count { w -> w.any { it.isLetterOrDigit() } }

        /** Time the engine pauses on punctuation at rate 1.0. */
        fun pauseSeconds(text: String): Double {
            var s = 0.0
            for (ch in text) {
                s += when (ch) {
                    ',', ';', ':' -> 0.25
                    '.', '!', '?', '…' -> 0.45
                    '\n' -> 0.35
                    else -> 0.0
                }
            }
            return s
        }

        fun durationOf(file: File): Long? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }
}
