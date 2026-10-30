package com.cutviet.editor.media.dubbing

/**
 * English words inside a Vietnamese dub line ("Mình vừa đăng lên Facebook, OK?"). Gemini's voices
 * say them in English in the same voice by themselves; the phone's Vietnamese voice cannot switch
 * language mid-sentence, so for it they are respelled the way a Vietnamese speaker says them
 * ("phây-búc", "ô-kê"): by Gemma when it is loaded, else from [WORDS] and letter names for
 * abbreviations. The line shown on the timeline keeps the English spelling.
 */
object SpeakableText {
    /** A whole word in plain letters (not the "Ng" of "Người"). */
    private val TOKEN = Regex("(?<!\\p{L})[A-Za-z][A-Za-z'’]*(?!\\p{L})")

    private const val ONSET = "(ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|b|c|d|g|h|k|l|m|n|p|r|s|t|v|x)?"
    private const val NUCLEUS = "(uye|uya|oai|oay|oeo|uay|uoi|uou|ieu|yeu|uoc|ai|ao|au|ay|eo|eu|ia|ie|iu|oa|oe|oi|oo|ua|ue|ui|uo|uu|uy|ye|a|e|i|o|u|y)"
    private const val CODA = "(ch|ng|nh|c|m|n|p|t)?"
    /** A Vietnamese syllable written without its marks ("ban", "nguoi", "khong"). */
    private val VIETNAMESE = Regex("^$ONSET$NUCLEUS$CODA$", RegexOption.IGNORE_CASE)

    /**
     * Words of [text] that are English (or other foreign words and abbreviations): written in plain
     * letters and not a Vietnamese syllable without its marks. A real Vietnamese word typed without
     * marks ("ban") is not caught, and neither is an English word that looks like one ("can").
     */
    fun englishWords(text: String): List<String> =
        TOKEN.findAll(text).map { it.value }.filter { w ->
            val plain = w.replace("'", "").replace("’", "")
            plain.length >= 2 && (isAbbreviation(plain) || !VIETNAMESE.matches(plain))
        }.toList()

    fun hasEnglish(text: String): Boolean = englishWords(text).isNotEmpty()

    private fun isAbbreviation(w: String) = w.length in 2..5 && w.all { it in 'A'..'Z' } && w !in setOf("OK", "OKAY")

    /** [text] with every English word the dictionary knows respelled, and abbreviations spelled out. */
    fun respell(text: String): String = TOKEN.replace(text) { m ->
        val w = m.value
        val plain = w.replace("’", "'")
        WORDS[plain.lowercase()]
            ?: if (isAbbreviation(plain)) plain.map { LETTERS.getValue(it) }.joinToString(" ") else w
    }

    /** Respelled by [ask] (Gemma) when it gives an answer that still reads like the line, else by [respell]. */
    suspend fun forPhone(text: String, ask: suspend (String) -> String? = { null }): String {
        if (!hasEnglish(text)) return text
        val model = ask(text)?.trim()?.takeIf { it.isNotEmpty() }
        if (model != null && acceptable(text, model)) return respell(model)
        return respell(text)
    }

    /** A model's respelling that is about as long as the line and left fewer English words. */
    fun acceptable(original: String, respelled: String): Boolean {
        val ratio = respelled.length.toDouble() / original.length.coerceAtLeast(1)
        return ratio in 0.6..2.5 && englishWords(respelled).size < englishWords(original).size
    }

    /** Vietnamese names of the letters, for abbreviations ("VIP" → "vi ai pi"). */
    private val LETTERS: Map<Char, String> = mapOf(
        'A' to "ây", 'B' to "bi", 'C' to "xi", 'D' to "đi", 'E' to "i", 'F' to "ép", 'G' to "gi", 'H' to "ếch",
        'I' to "ai", 'J' to "giây", 'K' to "kây", 'L' to "eo", 'M' to "em", 'N' to "en", 'O' to "ô", 'P' to "pi",
        'Q' to "kiu", 'R' to "a", 'S' to "ét", 'T' to "ti", 'U' to "iu", 'V' to "vi", 'W' to "đắp liu", 'X' to "ích",
        'Y' to "oai", 'Z' to "dét",
    )

    /** Common English words and names in Vietnamese speech, as Vietnamese people say them. */
    val WORDS: Map<String, String> = mapOf(
        "ok" to "ô kê", "okay" to "ô kê", "hello" to "hê lô", "bye" to "bai", "goodbye" to "gút bai",
        "sorry" to "so ri", "thank" to "thanh", "thanks" to "thanh", "you" to "diu", "please" to "pli",
        "yes" to "dét", "wow" to "quao", "oh" to "ồ", "baby" to "bê bi", "boss" to "bốt",
        "team" to "tim", "game" to "gêm", "show" to "sô", "live" to "lai", "livestream" to "lai xờ trim", "stream" to "xờ trim",
        "idol" to "ai đồ", "fan" to "phen", "fans" to "phen", "email" to "i meo", "online" to "on lai", "offline" to "óp lai",
        "video" to "vi đê ô", "clip" to "cờ líp", "facebook" to "phây búc", "youtube" to "diu túp", "tiktok" to "tích tóc",
        "google" to "gu gồ", "iphone" to "ai phôn", "phone" to "phôn", "laptop" to "láp tóp", "wifi" to "oai phai",
        "internet" to "in tơ nét", "app" to "áp", "coffee" to "cà phê", "party" to "pa ti", "shopping" to "sóp ping",
        "style" to "xờ tai", "cool" to "cun", "hot" to "hót", "top" to "tóp", "vip" to "víp", "level" to "le vồ",
        "check" to "chéc", "like" to "lai", "share" to "se", "comment" to "com men", "post" to "pốt", "selfie" to "seo phi",
        "sale" to "seo", "shop" to "sóp", "menu" to "mê nu", "taxi" to "tắc xi", "bus" to "bút", "hotel" to "hô ten",
        "manager" to "me nì giơ", "deadline" to "đét lai", "meeting" to "mít tinh", "project" to "pờ rô dét",
        "marketing" to "ma két tinh", "sir" to "xơ", "mister" to "mít xtơ", "miss" to "mít", "mom" to "mom", "dad" to "đét",
        "love" to "lớp", "happy" to "hép pi", "birthday" to "bớt đây", "christmas" to "crít mát", "weekend" to "uých en",
        "bro" to "bờ rô", "guys" to "gai", "super" to "su pơ", "fine" to "phai", "nice" to "nai",
        "good" to "gút", "password" to "pát uốt", "camera" to "ca mê ra", "zoom" to "dum",
        "pizza" to "pít da", "hamburger" to "hăm bơ gơ", "chocolate" to "sô cô la", "smartphone" to "xờ mát phôn",
    )
}
