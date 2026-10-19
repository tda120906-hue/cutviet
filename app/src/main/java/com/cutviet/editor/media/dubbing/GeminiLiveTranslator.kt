package com.cutviet.editor.media.dubbing

import android.os.SystemClock
import android.util.Base64
import com.cutviet.editor.media.dsp.WavWriter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** The online branch could not run (no key, quota, network); the job falls back to offline. */
class OnlineUnavailableException(message: String) : Exception(message)

/**
 * Online speech-to-speech dubbing with Gemini Live Translate over the Live API WebSocket. The
 * model is an interpreter: it takes a continuous audio stream (no turns, no instructions) and
 * speaks the Vietnamese translation as it goes. Each detected speech segment of the separated
 * voice is streamed in real time, followed by a little silence, and the speech that comes back
 * until the model falls quiet becomes that segment's dub. The API key is the one the owner typed
 * in the app's settings.
 */
class GeminiLiveTranslator(
    private val apiKey: String,
    private val model: String,
    private val workDir: File,
    /** Fail as if the quota ran out (settings test switch). */
    private val simulateQuota: Boolean = false,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** [voice] is 16 kHz mono; returns one dubbed segment per range that produced speech. */
    suspend fun dub(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        offsetMs: Long,
        progress: (Float) -> Unit,
    ): List<DubbedSegment> {
        if (simulateQuota) {
            DubLog.w(TAG, "simulated 429 from settings")
            throw OnlineUnavailableException("Gemini báo hết hạn mức (429, giả lập)")
        }
        if (apiKey.isBlank()) throw OnlineUnavailableException("Chưa nhập khoá Gemini")
        val room = DubbingPipeline.roomFor(ranges.map { it.startMs to (it.endMs - it.startMs) }, voice.size / 16L)
        try {
            val out = ArrayList<DubbedSegment>()
            var next = 0
            while (next < ranges.size) {
                next = session(voice, ranges, room, next, offsetMs, out) { done -> progress(done.toFloat() / ranges.size) }
            }
            return out
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * One Live session, from segment [from] on, ending early when the server asks to (sessions are
     * time-limited) or after [SESSION_AUDIO_MS] of streamed audio. Returns the next segment to do.
     */
    private suspend fun session(
        voice: FloatArray,
        ranges: List<SpeechDetector.Range>,
        room: List<Long>,
        from: Int,
        offsetMs: Long,
        out: MutableList<DubbedSegment>,
        onDone: (Int) -> Unit,
    ): Int {
        val inbox = Channel<JSONObject>(Channel.UNLIMITED)
        val failure = Channel<String>(Channel.CONFLATED)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { inbox.trySend(JSONObject(text)) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { inbox.trySend(JSONObject(bytes.utf8())) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.trySend(describe(response?.code, t.message))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                failure.trySend("Gemini đóng kết nối ($code): ${reason.ifBlank { "không rõ lý do" }}")
            }
        })
        try {
            val t0 = SystemClock.elapsedRealtime()
            ws.send(setupMessage(model).toString())
            val ready = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                while (true) {
                    failure.tryReceive().getOrNull()?.let { throw OnlineUnavailableException(it) }
                    val msg = withTimeoutOrNull(100) { inbox.receive() } ?: continue
                    checkError(msg)
                    if (msg.has("setupComplete")) break
                }
                true
            } ?: throw OnlineUnavailableException("Gemini không phản hồi khi mở phiên")
            DubLog.i(TAG, "session open in ${SystemClock.elapsedRealtime() - t0} ms ($model), from segment ${from + 1}, ready=$ready")

            var streamedMs = 0L
            var i = from
            while (i < ranges.size) {
                val r = ranges[i]
                val heard = Heard()
                segment(ws, inbox, failure, voice, r, heard)
                heard.toSegment(i, r, room[i], offsetMs)?.let(out::add)
                streamedMs += r.endMs - r.startMs + TAIL_MS
                i++
                onDone(i)
                if (heard.goAway || streamedMs >= SESSION_AUDIO_MS) {
                    DubLog.i(TAG, "new session after segment $i (goAway=${heard.goAway}, streamed ${streamedMs / 1000} s)")
                    break
                }
            }
            return i
        } finally {
            ws.close(1000, null)
        }
    }

    /** What came back for one segment. */
    private inner class Heard {
        val samples = ArrayList<ShortArray>()
        var rate = 24_000
        val text = StringBuilder()
        val source = StringBuilder()
        var lastAt = 0L
        var complete = false
        var goAway = false

        fun take(msg: JSONObject) {
            checkError(msg)
            if (msg.has("goAway")) goAway = true
            val sc = msg.optJSONObject("serverContent") ?: return
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (p in 0 until parts.length()) {
                    val inline = parts.getJSONObject(p).optJSONObject("inlineData") ?: continue
                    Regex("rate=(\\d+)").find(inline.optString("mimeType"))?.let { rate = it.groupValues[1].toInt() }
                    val bytes = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                    val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    samples += ShortArray(sb.remaining()).also { sb.get(it) }
                    lastAt = SystemClock.elapsedRealtime()
                }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                lastAt = SystemClock.elapsedRealtime()
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { source.append(it) }
            if (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")) complete = true
        }

        fun toSegment(index: Int, r: SpeechDetector.Range, roomMs: Long, offsetMs: Long): DubbedSegment? {
            val total = samples.sumOf { it.size }
            val slot = r.endMs - r.startMs
            if (total == 0) {
                DubLog.i(TAG, "segment $index: no speech back (slot ${slot} ms)")
                return null
            }
            val file = File(workDir, "online_$index.wav")
            WavWriter(file, rate, 1).use { w ->
                for (s in samples) {
                    val f = FloatArray(s.size) { s[it] / 32768f }
                    w.write(f, 0, f.size)
                }
            }
            val ms = WavTrim.trim(file) ?: (total * 1000L / rate)
            // No TTS rate to tune here: a dub that runs past the next line is played up to 1.15x faster.
            val speed = if (roomMs > 0) (ms.toFloat() / roomMs).coerceIn(1f, AudioFitter.MAX_RATE) else 1f
            DubLog.i(TAG, "segment $index measured=${ms}ms slot=${slot}ms room=${roomMs}ms speed=$speed")
            return DubbedSegment(r.startMs + offsetMs, slot, source.toString().trim(), text.toString().trim(), file, ms, 1f, speed)
        }
    }

    /**
     * Streams one segment in real-time 100 ms chunks, then silence so the interpreter finishes the
     * sentence, then listens until it has been quiet for a moment.
     */
    private suspend fun segment(
        ws: WebSocket,
        inbox: Channel<JSONObject>,
        failure: Channel<String>,
        voice: FloatArray,
        r: SpeechDetector.Range,
        heard: Heard,
    ) {
        fun drain() {
            failure.tryReceive().getOrNull()?.let { throw OnlineUnavailableException(it) }
            while (true) heard.take(inbox.tryReceive().getOrNull() ?: break)
        }
        val pcm = slice(voice, r)
        val total = pcm.size + TAIL_MS.toInt() * 16
        var pos = 0
        while (pos < total) {
            val end = minOf(total, pos + CHUNK)
            if (!ws.send(audioMessage(pcm, pos, end).toString())) throw OnlineUnavailableException("Mất kết nối Gemini")
            pos = end
            drain()
            delay(CHUNK_MS)
        }
        val sentAt = SystemClock.elapsedRealtime()
        while (true) {
            drain()
            val now = SystemClock.elapsedRealtime()
            if (now - sentAt > MAX_WAIT_MS) break
            if (heard.complete && heard.samples.isNotEmpty()) break
            // Nothing yet: give the interpreter longer before deciding it heard no speech.
            val quietLimit = if (heard.samples.isEmpty()) NOTHING_WAIT_MS else QUIET_MS
            if (now - maxOf(sentAt, heard.lastAt) >= quietLimit) break
            delay(50)
        }
    }

    private fun checkError(msg: JSONObject) {
        msg.optJSONObject("error")?.let { throw OnlineUnavailableException("Gemini báo lỗi: ${it.optString("message")}") }
    }


    /** Samples [from]..[to] of [pcm], zeros past its end. */
    private fun audioMessage(pcm: ShortArray, from: Int, to: Int): JSONObject {
        val bb = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) bb.putShort(if (i < pcm.size) pcm[i] else 0)
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000")))
    }

    private fun slice(voice: FloatArray, r: SpeechDetector.Range): ShortArray {
        val a = (r.startMs * 16).toInt().coerceIn(0, voice.size)
        val b = (r.endMs * 16).toInt().coerceIn(a, voice.size)
        return ShortArray(b - a) { (voice[a + it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }

    private fun describe(code: Int?, message: String?): String {
        DubLog.w(TAG, "websocket failed: $code $message")
        return when (code) {
            429 -> "Gemini báo hết hạn mức (429)"
            400, 401, 403 -> "Khoá Gemini không hợp lệ hoặc không có quyền dùng model $model ($code)"
            404 -> "Không tìm thấy model $model"
            else -> "Không kết nối được Gemini: ${message ?: code}"
        }
    }

    companion object {
        const val TAG = "CutVietGemini"

        /**
         * Live Translate setup: a target language and the transcription switches, nothing else.
         * The transcription switches belong to the setup itself and the translation settings to
         * its generationConfig (as the google-genai SDK sends them; the server rejects
         * inputAudioTranscription inside generationConfig).
         */
        fun setupMessage(model: String): JSONObject = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", "models/$model")
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("translationConfig", JSONObject().put("targetLanguageCode", "vi")),
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject()),
        )
        /** Default model; editable in the dubbing settings. */
        const val DEFAULT_MODEL = "gemini-3.5-live-translate-preview"
        /** 100 ms of 16 kHz audio per message, as the Live Translate guide recommends. */
        private const val CHUNK = 1_600
        private const val CHUNK_MS = 100L
        /** Silence sent after each segment so the interpreter finishes the sentence. */
        private const val TAIL_MS = 1_500L
        private const val QUIET_MS = 1_200L
        private const val NOTHING_WAIT_MS = 4_000L
        private const val MAX_WAIT_MS = 15_000L
        private const val SETUP_TIMEOUT_MS = 15_000L
        /** Streamed audio per session before reconnecting, well inside the Live API session limit. */
        private const val SESSION_AUDIO_MS = 8 * 60_000L
    }
}
