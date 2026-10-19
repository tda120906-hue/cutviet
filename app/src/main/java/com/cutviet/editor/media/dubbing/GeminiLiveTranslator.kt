package com.cutviet.editor.media.dubbing

import android.util.Base64
import android.util.Log
import com.cutviet.editor.media.dsp.WavWriter
import kotlinx.coroutines.channels.Channel
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
 * Online speech-to-speech dubbing with the Gemini Live API over a WebSocket: each detected speech
 * segment of the separated voice is sent as one turn, and the spoken Vietnamese that comes back
 * becomes that segment's dub. The API key is the one the owner typed in the app's settings.
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
        language: SourceLanguage,
        offsetMs: Long,
        progress: (Float) -> Unit,
    ): List<DubbedSegment> {
        if (simulateQuota) {
            Log.w(TAG, "simulated 429 from settings")
            throw OnlineUnavailableException("Gemini báo hết hạn mức (429, giả lập)")
        }
        if (apiKey.isBlank()) throw OnlineUnavailableException("Chưa nhập khoá Gemini")
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
                failure.trySend("Gemini đóng kết nối ($code): $reason")
            }
        })
        try {
            ws.send(setup(language).toString())
            await(inbox, failure, SETUP_TIMEOUT_MS) { it.has("setupComplete") }
                ?: throw OnlineUnavailableException("Gemini không phản hồi")

            val out = ArrayList<DubbedSegment>()
            ranges.forEachIndexed { i, r ->
                val pcm = slice(voice, r)
                ws.send("""{"realtimeInput":{"activityStart":{}}}""")
                for (chunk in pcm.indices step CHUNK) {
                    val end = minOf(pcm.size, chunk + CHUNK)
                    ws.send(audioMessage(pcm, chunk, end).toString())
                }
                ws.send("""{"realtimeInput":{"activityEnd":{}}}""")
                turn(inbox, failure, i, r, offsetMs)?.let(out::add)
                progress((i + 1).toFloat() / ranges.size)
            }
            return out
        } finally {
            ws.close(1000, null)
            client.dispatcher.executorService.shutdown()
        }
    }

    /** Collects one answer; null if the model said nothing for this segment. */
    private suspend fun turn(inbox: Channel<JSONObject>, failure: Channel<String>, index: Int, r: SpeechDetector.Range, offsetMs: Long): DubbedSegment? {
        val file = File(workDir, "online_$index.wav")
        var rate = 24_000
        val samples = ArrayList<ShortArray>()
        val text = StringBuilder()
        val done = await(inbox, failure, TURN_TIMEOUT_MS) { msg ->
            val sc = msg.optJSONObject("serverContent") ?: return@await false
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (p in 0 until parts.length()) {
                    val inline = parts.getJSONObject(p).optJSONObject("inlineData") ?: continue
                    Regex("rate=(\\d+)").find(inline.optString("mimeType"))?.let { rate = it.groupValues[1].toInt() }
                    val bytes = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                    val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    samples += ShortArray(sb.remaining()).also { sb.get(it) }
                }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { text.append(it) }
            sc.optBoolean("turnComplete") || sc.optBoolean("interrupted")
        } ?: throw OnlineUnavailableException("Gemini không trả lời kịp đoạn ${index + 1}")
        if (!done || samples.isEmpty()) return null
        val total = samples.sumOf { it.size }
        WavWriter(file, rate, 1).use { w ->
            for (s in samples) {
                val f = FloatArray(s.size) { s[it] / 32768f }
                w.write(f, 0, f.size)
            }
        }
        val ms = total * 1000L / rate
        val slot = r.endMs - r.startMs
        // No TTS rate to tune here: a dub that runs long is played up to 1.15x faster instead.
        val speed = if (slot > 0) (ms.toFloat() / slot).coerceIn(1f, AudioFitter.MAX_RATE) else 1f
        return DubbedSegment(r.startMs + offsetMs, slot, "", text.toString().trim(), file, ms, 1f, speed)
    }

    /** Waits for a message matching [accept]; null on timeout, throws if the connection failed. */
    private suspend fun await(inbox: Channel<JSONObject>, failure: Channel<String>, timeoutMs: Long, accept: (JSONObject) -> Boolean): Boolean? =
        withTimeoutOrNull(timeoutMs) {
            var matched = false
            while (!matched) {
                failure.tryReceive().getOrNull()?.let { throw OnlineUnavailableException(it) }
                val msg = withTimeoutOrNull(250) { inbox.receive() } ?: continue
                msg.optJSONObject("error")?.let { throw OnlineUnavailableException("Gemini báo lỗi: ${it.optString("message")}") }
                if (msg.has("goAway")) throw OnlineUnavailableException("Gemini yêu cầu ngắt phiên")
                matched = accept(msg)
            }
            true
        }

    private fun setup(language: SourceLanguage) = JSONObject().put(
        "setup",
        JSONObject()
            .put("model", "models/$model")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("languageCode", "vi-VN")),
            )
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Bạn là phiên dịch viên lồng tiếng phim. Mỗi lượt bạn nghe một câu ${language.label.lowercase()} " +
                                "và nói lại bằng tiếng Việt tự nhiên, giữ đúng sắc thái, giọng điệu và cách xưng hô theo ngữ cảnh. " +
                                "Không dịch từng chữ. Chỉ nói bản dịch, không thêm lời nào khác, và nói gọn trong khoảng thời gian của câu gốc.",
                        ),
                    ),
                ),
            )
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)))
            .put("outputAudioTranscription", JSONObject()),
    )

    private fun audioMessage(pcm: ShortArray, from: Int, to: Int): JSONObject {
        val bb = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) bb.putShort(pcm[i])
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000")))
    }

    private fun slice(voice: FloatArray, r: SpeechDetector.Range): ShortArray {
        val a = (r.startMs * 16).toInt().coerceIn(0, voice.size)
        val b = (r.endMs * 16).toInt().coerceIn(a, voice.size)
        return ShortArray(b - a) { (voice[a + it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }

    private fun describe(code: Int?, message: String?): String {
        Log.w(TAG, "websocket failed: $code $message")
        return when (code) {
            429 -> "Gemini báo hết hạn mức (429)"
            400, 401, 403 -> "Khoá Gemini không hợp lệ hoặc không có quyền dùng model $model ($code)"
            404 -> "Không tìm thấy model $model"
            else -> "Không kết nối được Gemini: ${message ?: code}"
        }
    }

    companion object {
        const val TAG = "CutVietGemini"
        /** Default model; editable in the dubbing settings. */
        const val DEFAULT_MODEL = "gemini-3.5-live-translate-preview"
        private const val CHUNK = 16_000 // one second of 16 kHz audio per message
        private const val SETUP_TIMEOUT_MS = 15_000L
        private const val TURN_TIMEOUT_MS = 45_000L
    }
}
