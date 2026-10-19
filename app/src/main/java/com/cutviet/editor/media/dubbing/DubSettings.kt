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

    companion object {
        const val FILE = "dub_secrets"
    }
}
