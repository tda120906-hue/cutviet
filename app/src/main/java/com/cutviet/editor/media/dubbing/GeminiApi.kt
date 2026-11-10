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
 * Short rate limits are waited out. A model that is overloaded (503) or out of free requests (429)
 * is replaced by the next lighter one ([fallbacksFor]), which has its own allowance; later requests
 * skip the busy model for a while ([SWITCH_MEMORY_MS]). Only when every model is used up is the
 * quota reported as [QuotaException].
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

    /** Why a model was left for a lighter one. */
    enum class Why(val vi: String, val log: String) {
        OVERLOADED("quá tải", "overloaded (503)"),
        QUOTA("hết lượt", "out of quota (429)"),
        MISSING("không dùng được", "not available (404)"),
    }

    private class Replaced(val model: String, val why: Why, val at: Long)

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
        val asked = body.optString("model")
        var model = replacementFor(asked)?.model ?: asked
        val tried = mutableListOf(model)
        var payload = body.put("model", model).toString()
        var attempt = 0
        var overloaded = 0

        /** Moves on to the next lighter model, for this request and the ones after; false when none is left. */
        fun switch(why: Why): Boolean {
            val next = nextModel(asked, tried) ?: return false
            DubLog.w(TAG, "$what: $model ${why.log}, using $next")
            // The note for the user keeps why the model they asked for was left.
            replaced[asked] = Replaced(next, replacementFor(asked)?.why ?: why, SystemClock.elapsedRealtime())
            model = next
            tried += next
            payload = body.put("model", next).toString()
            attempt = 0
            overloaded = 0
            return true
        }
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
                DubLog.i(TAG, "$what: $ms ms${if (model != asked) " ($model)" else ""}")
                return json
            }
            val err = errorOf(text)
            val chain = if (tried.size > 1) ", đã thử ${tried.joinToString()}" else ""
            if (code == 429) {
                val quota = quotaOf(text)
                if (!quota.daily && attempt++ < MAX_RETRIES) {
                    val wait = (quota.retryMs ?: (5_000L * attempt)).coerceIn(1_000, 65_000)
                    DubLog.w(TAG, "$what: $model rate limited, waiting ${wait / 1000} s")
                    delay(wait)
                    continue
                }
                // Out of requests on this model: a lighter one has its own, larger allowance.
                DubLog.w(TAG, "$what: $model out of quota: ${text.take(300)}")
                if (switch(Why.QUOTA)) continue
                throw QuotaException(
                    if (quota.daily) "Gemini hết lượt miễn phí trong ngày cho $what (429$chain)" else "Gemini báo hết hạn mức (429) cho $what$chain",
                )
            }
            if (code == 503) {
                // Each try may count against the day's few free requests: one more, then a lighter model.
                if (overloaded++ < OVERLOAD_RETRIES) {
                    DubLog.w(TAG, "$what: $model overloaded (503), retrying")
                    delay(3_000L * overloaded)
                    continue
                }
                if (switch(Why.OVERLOADED)) continue
                DubLog.w(TAG, "$what: overloaded (503) on ${tried.joinToString()}: ${text.take(300)}")
                throw OnlineUnavailableException("Máy chủ Gemini đang quá tải (503, lỗi phía Google) với ${tried.joinToString()} ($what)")
            }
            // A replacement this key cannot use is passed over.
            if (code == 404 && model != asked && switch(Why.MISSING)) continue
            if (code >= 500 && attempt++ < MAX_RETRIES) {
                DubLog.w(TAG, "$what: server error $code, retrying")
                delay(3_000L * attempt)
                continue
            }
            DubLog.w(TAG, "$what failed on $model: $code ${text.take(300)}")
            if (code == 400) throw Rejected("Gemini từ chối yêu cầu ($what): ${err ?: code}")
            throw OnlineUnavailableException(
                when (code) {
                    401, 403 -> "Khoá Gemini không hợp lệ hoặc không có quyền dùng model ($what, $code)"
                    404 -> "Không tìm thấy model $model ($what$chain): ${err ?: code}"
                    else -> "Gemini lỗi $code ($what$chain): ${err ?: ""}"
                },
            )
        }
    }

    /** What one model answered to a small test request ([probe]). */
    class Probe(val model: String, val code: Int, val message: String) {
        val ok: Boolean get() = code == 200
    }

    /**
     * One small request to [model], without retries or replacements: does it answer this key right
     * now? A speech model is asked to say two words, any other to write one.
     */
    suspend fun probe(model: String): Probe {
        if (apiKey.isBlank()) return Probe(model, 0, "chưa nhập khoá Gemini")
        val body = if (isSpeech(model)) {
            GeminiDubber.ttsRequest(model, "Xin chào.", "Kore")
        } else {
            JSONObject().put("model", model)
                .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", "Trả lời đúng một chữ: OK")))
        }
        val t0 = SystemClock.elapsedRealtime()
        val (code, text) = try {
            post(body.toString())
        } catch (e: IOException) {
            DubLog.w(TAG, "probe $model: ${e.javaClass.simpleName}: ${e.message}")
            return Probe(model, 0, "không kết nối được (${e.message})")
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val failed = code == 200 && runCatching { JSONObject(text).optString("status") }.getOrNull() == "failed"
        DubLog.i(TAG, "probe $model: $code in $ms ms${if (code != 200 || failed) " ${text.take(300)}" else ""}")
        if (failed) return Probe(model, -1, "Gemini báo lỗi khi trả lời")
        return Probe(model, code, probeMessage(code, ms, text))
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

        /** Extra tries on an overloaded (503) model before a lighter one is used. */
        private const val OVERLOAD_RETRIES = 1

        /** How long later requests skip a model that was overloaded or out of requests. */
        const val SWITCH_MEMORY_MS = 30 * 60_000L

        /**
         * Lighter models to use when the one asked for is overloaded or out of free requests, best
         * first. The Flash-Lite ones have a much larger free allowance per day than Flash.
         */
        private val TEXT_FALLBACKS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        private val TTS_FALLBACKS = listOf("gemini-3.8-flash-lite-tts")

        /** Asked model → the model used instead, shared by every request of the app. */
        private val replaced = java.util.concurrent.ConcurrentHashMap<String, Replaced>()

        private fun replacementFor(asked: String): Replaced? {
            val r = replaced[asked] ?: return null
            if (SystemClock.elapsedRealtime() - r.at < SWITCH_MEMORY_MS) return r
            replaced.remove(asked)
            return null
        }

        /**
         * "gemini-3.8-flash quá tải nên dùng gemini-3.6-flash" for the models swapped now, or null.
         * [speech]: only the models that speak (true) or the ones that listen and write (false).
         */
        fun switchNote(speech: Boolean? = null): String? =
            replaced.keys.sorted().filter { speech == null || isSpeech(it) == speech }
                .mapNotNull { a -> replacementFor(a)?.let { "$a ${it.why.vi} nên dùng ${it.model}" } }
                .joinToString("; ").ifEmpty { null }

        fun isSpeech(model: String): Boolean = model.contains("tts", ignoreCase = true)

        /** Models to try instead of [model]: speech ones for a TTS model, text ones otherwise. */
        fun fallbacksFor(model: String): List<String> = (if (isSpeech(model)) TTS_FALLBACKS else TEXT_FALLBACKS).filter { it != model }

        /** The next model to try for [asked] after [tried], or null when none is left. */
        fun nextModel(asked: String, tried: List<String>): String? = fallbacksFor(asked).firstOrNull { it !in tried }

        /**
         * What the test ([probe]) found for [asked] and its replacements, in order: the first model
         * that answered is the one to use from now on, or null to keep things as they are.
         */
        fun choose(asked: String, probes: List<Probe>): String? {
            val first = probes.firstOrNull() ?: return null
            if (first.model != asked) return null
            if (first.ok) return asked
            return probes.drop(1).firstOrNull { it.ok }?.model
        }

        /** Makes later requests use what the test found working ([choose]). */
        fun remember(asked: String, probes: List<Probe>) {
            val use = choose(asked, probes) ?: return
            if (use == asked) {
                replaced.remove(asked)
            } else {
                val why = when (probes.first().code) {
                    429 -> Why.QUOTA
                    404 -> Why.MISSING
                    else -> Why.OVERLOADED
                }
                replaced[asked] = Replaced(use, why, SystemClock.elapsedRealtime())
                DubLog.i(TAG, "after the test: $asked ${why.log}, using $use")
            }
        }

        /** The test's verdict on one model, for the user. */
        fun probeMessage(code: Int, ms: Long, body: String): String = when (code) {
            200 -> "chạy được (${ms / 1000}.${(ms % 1000) / 100} giây)"
            503 -> "đang quá tải (503, lỗi phía máy chủ Google)"
            429 -> if (quotaOf(body).daily) "hết lượt miễn phí hôm nay (429)" else "hết lượt hoặc đang bị giới hạn tốc độ (429)"
            404 -> "khoá này không dùng được model này (404)"
            401, 403 -> "khoá không hợp lệ hoặc không có quyền ($code)"
            400 -> "bị từ chối (400): ${errorOf(body) ?: "không rõ"}"
            else -> "lỗi $code: ${errorOf(body) ?: "không rõ"}"
        }

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
