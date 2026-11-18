package com.cutviet.editor.media.tts

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * How the bundled voices read English words inside Vietnamese text. Before espeak sees the text, every
 * English word is rewritten in Vietnamese spelling, the way Vietnamese speakers say it ("deadline" →
 * "đét lai", "YouTube" → "diu túp"), so the Vietnamese-only voices read it like any Vietnamese word:
 *  1. the user's own corrections (made in the app),
 *  2. a built-in list of common words (assets/tts/en_vi.txt),
 *  3. for any other English word, its English pronunciation (espeak "en") turned into Vietnamese
 *     syllables by [EnglishInVietnamese.spell].
 * Before, espeak decided which words were English and missed some ("iPhone" was read as Vietnamese
 * letters, "off" spelled out as "o ép ép").
 */
object Pronunciation {
    enum class Source { USER, BUILT_IN, RULES }
    data class Reading(val word: String, val reading: String, val source: Source)

    private const val PREFS = "cutviet_tts"
    private const val KEY_USER = "user_words"

    @Volatile private var builtIn: Map<String, String>? = null
    @Volatile private var user: Map<String, String>? = null
    private val ruleCache = HashMap<String, String>()

    /** Bumped whenever the user changes a word, so screens can measure the voice again. */
    @Volatile var version = 0
        private set

    fun builtIn(context: Context): Map<String, String> = builtIn ?: synchronized(this) {
        builtIn ?: runCatching {
            context.assets.open("tts/en_vi.txt").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }.getOrDefault(emptyMap()).also { builtIn = it }
    }

    fun userWords(context: Context): Map<String, String> = user ?: synchronized(this) {
        user ?: runCatching {
            val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap()).also { user = it }
    }

    /** Saves the user's reading for [word]; a blank [reading] removes it (back to the app's own). */
    fun setUserWord(context: Context, word: String, reading: String?) {
        val key = key(word) ?: return
        synchronized(this) {
            val m = userWords(context).toMutableMap()
            val r = reading?.trim()?.replace(Regex("\\s+"), " ")
            if (r.isNullOrEmpty()) m.remove(key) else m[key] = r
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER, JSONObject(m as Map<*, *>).toString()).apply()
            user = m
            version++
        }
    }

    /** [text] with every English word written the Vietnamese way. [english] gives a word's English IPA. */
    fun respell(context: Context, text: String, english: (String) -> String?): String =
        respell(text, userWords(context), builtIn(context), english)

    /** The English words in [text] (each once) and how the voices will read them. */
    fun readingsIn(context: Context, text: String, english: (String) -> String?): List<Reading> {
        val seen = LinkedHashMap<String, Reading>()
        WORD.findAll(text).forEach { m ->
            val k = key(m.value) ?: return@forEach
            if (k in seen) return@forEach
            reading(m.value, userWords(context), builtIn(context), english)?.let { seen[k] = it }
        }
        return seen.values.toList()
    }

    // ---- Pure parts (unit-tested) ----

    /** A word: letters, with ' or - inside ("check-in", "TikTok's"). */
    private val WORD = Regex("\\p{L}+(?:['’-]\\p{L}+)*")

    fun parse(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val tab = l.indexOf('\t')
            if (tab <= 0) continue
            val k = l.substring(0, tab).trim()
            val v = l.substring(tab + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        return m
    }

    /**
     * The dictionary key of [word]: lower case, unless it is all capitals of 2+ letters ("AI", "IT"),
     * which only match in capitals so the Vietnamese "ai" is left alone. Null if not plain Latin letters.
     */
    fun key(word: String): String? {
        val w = word.replace("’", "'").trim()
        if (w.isEmpty() || !w.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-' }) return null
        return if (w.length >= 2 && w.all { it in 'A'..'Z' }) w else w.lowercase()
    }

    fun respell(text: String, user: Map<String, String>, builtIn: Map<String, String>, english: (String) -> String?): String =
        WORD.replace(text) { m -> reading(m.value, user, builtIn, english)?.reading ?: m.value }

    fun reading(word: String, user: Map<String, String>, builtIn: Map<String, String>, english: (String) -> String?): Reading? {
        val k = key(word) ?: return null
        lookup(k, user)?.let { return Reading(word, it, Source.USER) }
        // A Vietnamese word typed without its accents ("con", "in", "ban") is not English.
        val caps = k.length >= 2 && k.all { it in 'A'..'Z' }
        if (!caps && isVietnameseSyllable(k)) return null
        lookup(k, builtIn)?.let { return Reading(word, it, Source.BUILT_IN) }
        // Other capitals ("VTV", "HCM", "USB") are left to espeak, which spells them the Vietnamese way.
        if (caps) return null
        val spelled = synchronized(ruleCache) { ruleCache[k] } ?: run {
            val ipa = english(k.replace("-", " ")) ?: return null
            val s = EnglishInVietnamese.spell(ipa.replace(Regex("\\([^)]*\\)"), " "))
            if (s.isBlank()) return null
            synchronized(ruleCache) { if (ruleCache.size > 2000) ruleCache.clear(); ruleCache[k] = s }
            s
        }
        return Reading(word, spelled, Source.RULES)
    }

    /** [k] in [dict], also as "checkin" for "check-in", without "'s", and the singular of a plural. */
    private fun lookup(k: String, dict: Map<String, String>): String? {
        if (dict.isEmpty()) return null
        dict[k]?.let { return it }
        val plain = k.replace("-", "").replace(Regex("'s$"), "").replace("'", "")
        dict[plain]?.let { return it }
        if (plain.length > 3 && plain.endsWith("s")) {
            dict[plain.dropLast(1)]?.let { return it }
            if (plain.endsWith("es")) dict[plain.dropLast(2)]?.let { return it }
        }
        return null
    }

    private val ONSETS = listOf(
        "ngh", "ng", "nh", "ch", "gh", "gi", "kh", "ph", "qu", "th", "tr",
        "b", "c", "d", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x", "",
    )
    /** Rhymes written without any accent. Stop endings (p t c ch) need a tone mark, so they are not here. */
    private val RHYMES = setOf(
        "a", "ai", "ao", "au", "ay", "am", "an", "ang", "anh",
        "e", "eo", "em", "en", "eng",
        "i", "ia", "iu", "im", "in", "inh",
        "o", "oa", "oai", "oao", "oay", "oe", "oeo", "oi", "om", "on", "ong", "oong", "oan", "oang", "oanh", "oen", "oam",
        "u", "ua", "ui", "uy", "uya", "uyu", "um", "un", "ung", "uyn", "uynh",
        "y",
        // after "qu" / "gi", where the u / i belongs to the onset
        "e", "ê", "ơ",
    )

    /** True if [w] (lower case, no accents) can be a Vietnamese syllable typed without accents. */
    fun isVietnameseSyllable(w: String): Boolean {
        val s = Normalizer.normalize(w.lowercase(), Normalizer.Form.NFC)
        if (s.isEmpty() || !s.all { it in 'a'..'z' }) return false
        for (o in ONSETS) {
            if (!s.startsWith(o)) continue
            val r = s.substring(o.length)
            if (r.isEmpty()) { if (o == "gi") return true else continue }
            if (r in RHYMES) return true
        }
        return false
    }
}
