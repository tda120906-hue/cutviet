package com.cutviet.editor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.runtime.mutableStateOf
import com.cutviet.editor.media.tts.PiperTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Vietnamese text-to-speech with the voices bundled in the app (Hoài My, Nam Minh: [PiperTts]),
 * offline, reading English words inside Vietnamese text in the same voice. The phone's own TTS
 * voices are no longer used (the owner did not like them, 2026-11-15).
 */
class VoiceEngine(context: Context) {
    private val appContext = context.applicationContext

    enum class Status { LOADING, READY, NO_VIETNAMESE, FAILED }

    data class VoiceOption(val name: String, val label: String, val needsNetwork: Boolean)

    /** A voice style ("mẫu giọng"): pitch applied on top of the chosen voice. */
    data class Style(val label: String, val pitch: Float)

    val status = mutableStateOf(Status.LOADING)
    val voices = mutableStateOf<List<VoiceOption>>(emptyList())

    private val tts = PiperTts.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var previewJob: Job? = null
    private var player: MediaPlayer? = null

    /** Measured syllables per second at rate 1.0, per voice; refined after every synthesis. */
    private val calibration = ConcurrentHashMap<String, Double>()

    init {
        scope.launch(Dispatchers.IO) {
            val ok = tts.prepare()
            if (ok) voices.value = PiperTts.MODELS.map { VoiceOption(it.id, it.label, needsNetwork = false) }
            status.value = if (ok) Status.READY else Status.FAILED
        }
    }

    /** Plays [text] (synthesized first, so the preview sounds exactly like the result). */
    fun speak(text: String, voiceName: String?, pitch: Float, rate: Float) {
        stop()
        previewJob = scope.launch {
            val f = File(appContext.cacheDir, "voice_preview.wav")
            synthesize(text, voiceName, pitch, rate, f) ?: return@launch
            withContext(Dispatchers.Main) {
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare()
                    start()
                }
            }
        }
    }

    fun stop() {
        previewJob?.cancel()
        previewJob = null
        val p = player
        player = null
        scope.launch(Dispatchers.Main) { runCatching { p?.stop(); p?.release() } }
    }

    /**
     * Synthesizes [text] of any length into a WAV file and returns its exact duration in ms, or null
     * on failure. Runs off the main thread; cancelling the coroutine stops it between sentences.
     */
    suspend fun synthesize(text: String, voiceName: String?, pitch: Float, rate: Float, out: File): Long? {
        if (text.isBlank()) return null
        if (status.value == Status.LOADING) withTimeoutOrNull(30_000) { while (status.value == Status.LOADING) delay(50) }
        if (status.value != Status.READY) return null
        val id = resolve(voiceName)
        val ms = withContext(Dispatchers.Default) {
            runCatching { tts.synthesize(id, text, rate, pitch, out) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        } ?: return null
        if (ms <= 0) return null
        // Learn this voice's real speed so the instant estimate gets closer each time.
        val syllables = syllableCount(text)
        val speechSec = ms / 1000.0 * rate - pauseSeconds(text)
        if (syllables >= 3 && speechSec > 0.3) calibration[id] = syllables / speechSec
        return ms
    }

    /** Instant estimate of the spoken length in ms (before synthesizing). */
    fun estimateMs(text: String, voiceName: String?, rate: Float): Long {
        val syllables = syllableCount(text)
        if (syllables == 0) return 0
        val sps = calibration[resolve(voiceName)] ?: PIPER_SYLLABLES_PER_SECOND
        val seconds = (syllables / sps + pauseSeconds(text)) / max(0.1f, rate)
        return (seconds * 1000).toLong()
    }

    /** Stops a preview. The voices stay loaded for the whole app (they are shared, see [PiperTts.get]). */
    fun shutdown() {
        stop()
    }

    companion object {
        /** Typical Vietnamese TTS speed at rate 1.0 (one word = one syllable in Vietnamese). */
        const val DEFAULT_SYLLABLES_PER_SECOND = 4.3
        /** Hoài My / Nam Minh at rate 1.0, measured on a long sentence (5.4–5.5). */
        const val PIPER_SYLLABLES_PER_SECOND = 5.4

        /** A bundled voice id for [name]: itself, or Hoài My for empty names and the old phone voices. */
        fun resolve(name: String?): String = PiperTts.MODELS.firstOrNull { it.id == name }?.id ?: PiperTts.FEMALE

        /**
         * The voice and pitch the offline dub reads a line with. Dub lines carry a pitch that stands
         * for who speaks ([com.cutviet.editor.media.dubbing.DubbingPipeline.pitchFor]: 0.78 man,
         * 1.05 woman, 1.3 child); with real male and female voices that becomes Nam Minh or Hoài My
         * at their own pitch, and a child is Hoài My raised.
         */
        fun dubVoice(name: String?, pitch: Float): Pair<String, Float> = when {
            abs(pitch - 0.78f) < 0.005f -> PiperTts.MALE to 1f
            abs(pitch - 1.05f) < 0.005f -> PiperTts.FEMALE to 1f
            abs(pitch - 1.3f) < 0.005f -> PiperTts.FEMALE to 1.25f
            else -> resolve(name) to pitch
        }

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
