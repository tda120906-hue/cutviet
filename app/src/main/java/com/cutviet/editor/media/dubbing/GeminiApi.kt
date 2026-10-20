package com.cutviet.editor.media.dubbing

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal client for the Gemini API's Interactions endpoint (POST /v1beta/interactions), used by
 * the natural online dub for listening (gemini-3.8-flash) and speaking (gemini-3.8-flash-tts).
 * Short rate limits are waited out; a used-up daily quota is reported as [QuotaException].
 */
class GeminiApi(
    private val apiKey: String,
    /** Fail as if the quota ran out (settings test switch). */
    private val simulateQuota: Boolean = false,
) {
    /** The daily (or long) quota of a model is used up; waiting a little will not help. */
    class QuotaException(message: String) : OnlineUnavailableException(message)

    /** The server refused this request itself (400): another request may still work. */
    class Rejected(message: String) : OnlineUnavailableException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Sends one interaction and returns the finished Interaction JSON. */
    suspend fun interact(body: JSONObject, what: String): JSONObject {
        if (simulateQuota) {
            DubLog.w(TAG, "simulated 429 from settings")
            throw OnlineUnavailableException("Gemini báo hết hạn mức (429, giả lập)")
        }
        if (apiKey.isBlank()) throw OnlineUnavailableException("Chưa nhập khoá Gemini")
        val payload = body.toString()
        var attempt = 0
        while (true) {
            val t0 = SystemClock.elapsedRealtime()
            val (code, text) = try {
                post(payload)
            } catch (e: IOException) {
                if (attempt++ < MAX_RETRIES) {
                    DubLog.w(TAG, "$what: network error, retrying: ${e.message}")
                    delay(2_000L * attempt)
                    continue
                }
                throw OnlineUnavailableException("Không kết nối được Gemini: ${e.message}")
            }
            val ms = SystemClock.elapsedRealtime() - t0
            if (code == 200) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw OnlineUnavailableException("Gemini trả lời không đọc được ($what)")
                if (json.optString("status") == "failed") {
                    val err = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                    throw OnlineUnavailableException("Gemini báo lỗi ($what): ${err ?: "không rõ"}")
                }
                DubLog.i(TAG, "$what: $ms ms")
                return json
            }
            val err = errorOf(text)
            if (code == 429) {
                val quota = quotaOf(text)
                if (quota.daily) throw QuotaException("Gemini hết lượt miễn phí trong ngày cho $what (429)")
                if (attempt++ < MAX_RETRIES) {
                    val wait = (quota.retryMs ?: (5_000L * attempt)).coerceIn(1_000, 65_000)
                    DubLog.w(TAG, "$what: rate limited, waiting ${wait / 1000} s")
                    delay(wait)
                    continue
                }
                throw QuotaException("Gemini báo hết hạn mức (429) cho $what")
            }
            if (code >= 500 && attempt++ < MAX_RETRIES) {
                DubLog.w(TAG, "$what: server error $code, retrying")
                delay(3_000L * attempt)
                continue
            }
            DubLog.w(TAG, "$what failed: $code ${text.take(300)}")
            if (code == 400) throw Rejected("Gemini từ chối yêu cầu ($what): ${err ?: code}")
            throw OnlineUnavailableException(
                when (code) {
                    401, 403 -> "Khoá Gemini không hợp lệ hoặc không có quyền dùng model ($what, $code)"
                    404 -> "Không tìm thấy model ($what): ${err ?: code}"
                    else -> "Gemini lỗi $code ($what): ${err ?: ""}"
                },
            )
        }
    }

    private suspend fun post(payload: String): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(
            Request.Builder()
                .url(ENDPOINT)
                .header("x-goog-api-key", apiKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val r = response.use { it.code to (it.body?.string() ?: "") }
                if (cont.isActive) cont.resume(r)
            }
        })
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    class Quota(val daily: Boolean, val retryMs: Long?)

    companion object {
        const val TAG = "CutVietGemini"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_RETRIES = 4

        /** The text the model wrote: `output_text`, or the text items of its model_output steps. */
        fun outputText(interaction: JSONObject): String? {
            interaction.optString("output_text").takeIf { it.isNotEmpty() }?.let { return it }
            val sb = StringBuilder()
            forEachOutput(interaction) { item -> if (item.optString("type") == "text") sb.append(item.optString("text")) }
            return sb.toString().ifEmpty { null }
        }

        /** The last audio the model produced, decoded from base64, with its MIME type. */
        fun outputAudio(interaction: JSONObject): Pair<ByteArray, String>? {
            var found: Pair<String, String>? = null
            forEachOutput(interaction) { item ->
                if (item.optString("type") == "audio" && item.optString("data").isNotEmpty()) {
                    found = item.optString("data") to item.optString("mime_type")
                }
            }
            val (data, mime) = found ?: return null
            // okio's decoder works on every API level and in unit tests, and skips line breaks.
            val bytes = data.decodeBase64()?.toByteArray() ?: return null
            return bytes to mime
        }

        private fun forEachOutput(interaction: JSONObject, block: (JSONObject) -> Unit) {
            val steps = interaction.optJSONArray("steps") ?: interaction.optJSONArray("outputs") ?: return
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val type = step.optString("type")
                if (type.isNotEmpty() && type != "model_output") {
                    // "outputs" lists content items directly.
                    if (step.has("data") || step.has("text")) block(step)
                    continue
                }
                val content = step.optJSONArray("content") ?: continue
                for (k in 0 until content.length()) content.optJSONObject(k)?.let(block)
            }
        }

        fun errorOf(body: String): String? =
            runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }

        /** Reads a 429 body: is the daily quota gone, and how long the server asks to wait. */
        fun quotaOf(body: String): Quota {
            val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return Quota(false, null)
            var daily = err.optString("message").contains("per day", ignoreCase = true)
            var retry: Long? = null
            val details = err.optJSONArray("details") ?: JSONArray()
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                val type = d.optString("@type")
                if (type.endsWith("RetryInfo")) {
                    retry = Regex("([0-9.]+)s").find(d.optString("retryDelay"))?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                }
                if (type.endsWith("QuotaFailure")) {
                    val v = d.optJSONArray("violations") ?: continue
                    for (k in 0 until v.length()) {
                        val id = v.optJSONObject(k)?.optString("quotaId").orEmpty()
                        if (id.contains("PerDay", ignoreCase = true)) daily = true
                    }
                }
            }
            return Quota(daily, retry)
        }
    }
}
