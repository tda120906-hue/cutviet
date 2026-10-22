package com.cutviet.editor.media.dubbing

import android.content.Context

/**
 * Dubbing settings. Keys typed by the owner (Gemini API key, Hugging Face token) live only in this
 * app's private storage on the phone and are excluded from backups (res/xml/backup_rules.xml);
 * nothing secret is compiled into the APK, which is built publicly.
 */
class DubSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var mode: DubMode
        get() = runCatching { DubMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(DubMode.AUTO)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "").orEmpty()
        set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()

    /** Gemini Live model id used by the online branch. */
    var geminiModel: String
        get() = prefs.getString("gemini_model", null)?.takeIf { it.isNotBlank() } ?: GeminiLiveTranslator.DEFAULT_MODEL
        set(v) = prefs.edit().putString("gemini_model", v.trim()).apply()

    /** Online style: natural (speakers told apart) or the real-time interpreter. */
    var onlineEngine: OnlineEngine
        get() = runCatching { OnlineEngine.valueOf(prefs.getString("online_engine", null)!!) }.getOrDefault(OnlineEngine.NATURAL)
        set(v) = prefs.edit().putString("online_engine", v.name).apply()

    /** Gemini model that listens and translates in the natural online dub. */
    var geminiTextModel: String
        get() = prefs.getString("gemini_text_model", null)?.takeIf { it.isNotBlank() } ?: GeminiDubber.DEFAULT_TEXT_MODEL
        set(v) = prefs.edit().putString("gemini_text_model", v.trim()).apply()

    /** Gemini model that reads the natural online dub. */
    var geminiTtsModel: String
        get() = prefs.getString("gemini_tts_model", null)?.takeIf { it.isNotBlank() } ?: GeminiDubber.DEFAULT_TTS_MODEL
        set(v) = prefs.edit().putString("gemini_tts_model", v.trim()).apply()

    /** Give each person (online) or men, women and children (offline) a voice of their own. */
    var distinguishSpeakers: Boolean
        get() = prefs.getBoolean("distinguish_speakers", true)
        set(v) = prefs.edit().putBoolean("distinguish_speakers", v).apply()

    /** Test switch: the online branch acts as if Gemini answered 429, to check the offline fallback. */
    var simulateQuota: Boolean
        get() = prefs.getBoolean("simulate_429", false)
        set(v) = prefs.edit().putBoolean("simulate_429", v).apply()

    var hfToken: String
        get() = prefs.getString("hf_token", "").orEmpty()
        set(v) = prefs.edit().putString("hf_token", v.trim()).apply()

    /** Download models only on Wi-Fi. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    /** Volume of the original sound under the dub: 0 mutes it, 0.2 keeps it faintly. */
    var originalVolume: Float
        get() = prefs.getFloat("original_volume", 0f)
        set(v) = prefs.edit().putFloat("original_volume", v).apply()

    /** Fastest a dubbed line may be played to fit its time (1.4 at most). */
    var maxDubSpeed: Float
        get() = prefs.getFloat("max_dub_speed", com.cutviet.editor.model.DubTime.DEFAULT_MAX_DUB).coerceIn(1f, 1.4f)
        set(v) = prefs.edit().putFloat("max_dub_speed", v.coerceIn(1f, 1.4f)).apply()

    /** Slowest the video under a long line may be played (0.8 at least; 1 = never slowed). */
    var minVideoSpeed: Float
        get() = prefs.getFloat("min_video_speed", com.cutviet.editor.model.DubTime.DEFAULT_MIN_VIDEO).coerceIn(0.8f, 1f)
        set(v) = prefs.edit().putFloat("min_video_speed", v.coerceIn(0.8f, 1f)).apply()

    companion object {
        const val FILE = "dub_secrets"
    }
}
