package com.cutviet.editor.media.tts

import java.text.Normalizer

/**
 * English words inside Vietnamese text for the Piper voices (Hoài My, Nam Minh). Those models were
 * trained on Vietnamese only, so English phonemes (ɪ, æ, ɹ, clusters like "str", a final "s") come
 * out garbled. espeak-ng already marks English words in a Vietnamese clause as "(en)…(vi)"; here each
 * English word is rebuilt as Vietnamese syllables a Vietnamese speaker would say ("deadline" →
 * đét-lai, "smooth" → xơ-mút, "edit" → e-đít), in espeak's own Vietnamese IPA, so the voice only
 * sees sound patterns it knows and reads them in the same voice, without a pause.
 *
 * Rules worked out on the voices with a speech recogniser (2026-11-15): English words came out
 * recognisable 2–8× more often than when the English phonemes were passed through.
 */
object EnglishInVietnamese {
    private val VOWELS = listOf(
        "eɪ", "aɪ", "ɔɪ", "aʊ", "əʊ", "oʊ", "ɪə", "eə", "ʊə", "iː", "uː", "ɑː", "ɔː", "ɜː", "aː",
        "ɪ", "i", "ᵻ", "ɛ", "e", "æ", "a", "ɑ", "ɒ", "ɔ", "ʌ", "ə", "ɚ", "ɝ", "ɐ", "ʊ", "u", "o",
    )
    private val CONSONANTS = listOf(
        "tʃ", "dʒ", "p", "b", "t", "d", "k", "ɡ", "g", "m", "n", "ŋ", "f", "v", "θ", "ð", "s", "z",
        "ʃ", "ʒ", "h", "l", "ɫ", "ɹ", "r", "ɾ", "w", "j", "x", "ʔ",
    )
    private val UNITS = VOWELS + CONSONANTS + listOf("ˈ", "ˌ")

    /** English nucleus → Vietnamese vowel and off-glide. "IU" is "ju" after a consonant ("review" → viu). */
    private val NUCLEUS: Map<String, Pair<String, String>> = mapOf(
        "IU" to ("i" to "w"), "eɪ" to ("e" to "j"), "aɪ" to ("aː" to "j"), "ɔɪ" to ("ɔ" to "j"), "aʊ" to ("aː" to "w"),
        "əʊ" to ("o" to ""), "oʊ" to ("o" to ""), "ɪə" to ("iə" to ""), "eə" to ("ɛ" to ""), "ʊə" to ("uə" to ""),
        "iː" to ("i" to ""), "uː" to ("u" to ""), "ɑː" to ("aː" to ""), "ɔː" to ("ɔ" to ""), "ɜː" to ("əː" to ""),
        "aː" to ("aː" to ""), "ɪ" to ("i" to ""), "i" to ("i" to ""), "ᵻ" to ("i" to ""), "ɛ" to ("ɛ" to ""),
        "e" to ("ɛ" to ""), "æ" to ("ɛ" to ""), "a" to ("aː" to ""), "ɑ" to ("aː" to ""), "ɒ" to ("ɔ" to ""),
        "ɔ" to ("ɔ" to ""), "ʌ" to ("a" to ""), "ə" to ("ə" to ""), "ɚ" to ("ə" to ""), "ɝ" to ("əː" to ""),
        "ɐ" to ("a" to ""), "ʊ" to ("u" to ""), "u" to ("u" to ""), "o" to ("o" to ""),
    )
    private val ONSET: Map<String, String> = mapOf(
        "w" to "w", "p" to "p", "b" to "b", "t" to "t", "d" to "ɗ", "k" to "k", "ɡ" to "ɣ", "g" to "ɣ", "m" to "m",
        "n" to "n", "ŋ" to "ŋ", "f" to "f", "v" to "v", "θ" to "t", "ð" to "ɗ", "s" to "s", "z" to "z", "ʃ" to "s",
        "ʒ" to "z", "h" to "h", "l" to "l", "ɫ" to "l", "ɹ" to "z", "r" to "z", "ɾ" to "t̪", "j" to "z", "x" to "x",
        "tʃ" to "tʃ", "dʒ" to "z",
    )
    /** Final consonants a Vietnamese syllable cannot end in become p / t / c, read with the sắc tone. */
    private val STOP: Map<String, String> = mapOf(
        "p" to "p", "b" to "p", "t" to "t", "d" to "t", "k" to "c", "ɡ" to "c", "g" to "c", "θ" to "t", "ð" to "t",
        "s" to "t", "z" to "t", "ʃ" to "c", "ʒ" to "c", "tʃ" to "c", "dʒ" to "c", "f" to "p", "v" to "p", "x" to "c",
    )
    private val NASAL: Map<String, String> = mapOf("m" to "m", "n" to "n", "ŋ" to "ŋ", "l" to "n", "ɫ" to "n")

    private const val SAC = "ɜ"
    private const val HUYEN = "2"

    /** An espeak "vi" clause with its English parts made Vietnamese and every language flag removed. */
    fun convertClause(raw: String): String {
        val mixed = Regex("\\(en[^)]*\\)(.*?)(\\([^)]*\\)|$)").replace(raw) { m -> convert(m.groupValues[1]) }
        return mixed.replace(Regex("\\([^)]+\\)"), "")
    }

    /** English IPA (one or more words) as Vietnamese syllables in espeak-vi IPA. */
    fun convert(englishIpa: String): String =
        englishIpa.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word(it) }.trim()

    private fun units(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val u = UNITS.firstOrNull { s.startsWith(it, i) }
            if (u == null) i++ else { out += u; i += u.length }
        }
        return out
    }

    private class Syl(var onset: List<String>, var nucleus: String, var coda: List<String>, val stress: String?)

    /** [ipa] cut into syllables; between two vowels the first consonant closes the syllable before. */
    private fun syllables(ipa: String): List<Syl> {
        val u = units(Normalizer.normalize(ipa, Normalizer.Form.NFC))
        val syls = ArrayList<Syl>()
        var cons = ArrayList<String>()
        var stress: String? = null
        for (x in u) {
            when {
                x == "ˈ" || x == "ˌ" -> stress = x
                x in NUCLEUS -> { syls += Syl(cons, x, emptyList(), stress); cons = ArrayList(); stress = null }
                x != "ʔ" -> cons += x
            }
        }
        if (syls.isEmpty()) return syls
        syls.last().coda = cons
        for (k in 1 until syls.size) {
            val on = syls[k].onset
            if (on.size > 1) {
                val keep = if (on.takeLast(2) == listOf("t", "ɹ") || on.last() == "w" || on.last() == "j") on.takeLast(2) else on.takeLast(1)
                val rest = on.dropLast(keep.size)
                syls[k - 1].coda = rest.take(1)
                syls[k].onset = rest.drop(1) + keep
            }
        }
        return syls
    }

    internal fun word(ipa: String): String {
        val syls = syllables(ipa)
        if (syls.isEmpty()) return ""
        val out = ArrayList<String>()
        for (s in syls) {
            var on = s.onset
            var nuc = s.nucleus
            if (on.takeLast(2) == listOf("t", "ɹ")) on = on.dropLast(2) + "tʃ"
            var glideOnset = ""
            if (on.size >= 2 && on.last() == "w") {
                glideOnset = "w"; on = on.dropLast(1)
            } else if (on.size >= 2 && on.last() == "j") {
                on = on.dropLast(1)
                if (nuc in setOf("uː", "ʊ", "ʊə") && s.coda.isEmpty()) nuc = "IU"
            }
            // Extra consonants at the start become their own "pờ / xờ" syllable.
            if (on.size > 1) {
                for (c in on.dropLast(1)) out += ONSET[c].orEmpty() + "ˈəː" + HUYEN
                on = on.takeLast(1)
            }
            if (glideOnset.isNotEmpty()) on = on + glideOnset
            val stressed: Boolean? = when {
                s.stress == "ˌ" -> null
                s.stress == "ˈ" || syls.size == 1 -> true
                else -> false
            }
            out += syllable(on, nuc, s.coda.firstOrNull(), stressed)
        }
        return out.joinToString(" ")
    }

    /** [stressed]: true primary, false unstressed, null secondary. */
    private fun syllable(onset: List<String>, nuc: String, codaIn: String?, stressed: Boolean?): String {
        var (n, glide) = NUCLEUS.getValue(nuc)
        var coda = codaIn
        // ă and â cannot end an open Vietnamese syllable.
        if (coda == null && glide.isEmpty()) n = when (n) { "a" -> "aː"; "ə" -> "əː"; else -> n }
        if (glide.isNotEmpty() && coda != null && nuc != "eɪ") coda = null
        if ((nuc == "ə" || nuc == "ɚ") && (coda == "l" || coda == "ɫ")) { coda = null; n = "əː" }
        if (nuc == "eɪ" && coda != null) { n = "e"; glide = "" }
        var tone = ""
        var c = ""
        when (coda) {
            in STOP -> {
                c = STOP.getValue(coda!!)
                if (c == "t") c = "t̪"
                if (c == "c" && (n == "ɔ" || n == "o")) c = "k"
                tone = SAC
            }
            in NASAL -> {
                c = NASAL.getValue(coda!!)
                if (c == "ŋ" && (n == "i" || n == "e")) c = "ɲ"
                if ((coda == "l" || coda == "ɫ") && n in setOf("ɛ", "e", "i", "iə")) c = "w"
            }
            "w" -> c = "w"
            "j" -> c = "j"
        }
        var on = onset.joinToString("") { ONSET[it].orEmpty() }
        // Vietnamese "t", not the aspirated "th".
        if (on.startsWith("t") && !on.startsWith("tʃ") && !on.startsWith("t̪")) on = "t̪" + on.substring(1)
        return on + (if (stressed == null) "ˌ" else "ˈ") + n + tone + glide + c
    }

    // ---- The same sounds written in Vietnamese spelling ("dˈɛdlaɪn" → "đét lai"). ----
    // Written out, an English word goes through espeak's Vietnamese rules like any Vietnamese word, can
    // be shown to the user and corrected by them in the same form.

    /** English IPA (one or more words) as Vietnamese spelling, syllables separated by spaces. */
    fun spell(englishIpa: String): String =
        englishIpa.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { spellWord(it) }
            .replace(Regex("\\s+"), " ").trim()

    private val SHORT = setOf("ɪ", "ɛ", "e", "æ", "ʌ", "ɒ", "ʊ", "a")
    private val HELD = setOf("p", "t", "k", "f", "s", "θ", "tʃ")

    private class Part(val onset: String, val w: Boolean, val n: String, val glide: String, val coda: String, val tone: String)

    internal fun spellWord(ipa: String): String {
        val syls = syllables(ipa)
        if (syls.isEmpty()) return ""
        // A short stressed vowel before p, t, k, f, s… holds that sound too, as Vietnamese speakers say
        // it: "happy" → háp pi, "copy" → cóp pi, "office" → óp phít.
        for (k in 0 until syls.size - 1) {
            val s = syls[k]
            val next = syls[k + 1].onset
            if ((s.stress == "ˈ") && s.coda.isEmpty() && s.nucleus in SHORT && next.size == 1 && next[0] in HELD) {
                s.coda = listOf(next[0])
            }
        }
        val out = ArrayList<String>()
        for (s in syls) {
            var on = s.onset
            var nuc = s.nucleus
            if (on.takeLast(2) == listOf("t", "ɹ")) on = on.dropLast(2) + "tʃ"
            var w = false
            if (on.size >= 2 && on.last() == "w") { w = true; on = on.dropLast(1) }
            else if (on == listOf("w")) { w = true; on = emptyList() }
            else if (on.size >= 2 && on.last() == "j") {
                on = on.dropLast(1)
                if (nuc in setOf("uː", "ʊ", "ʊə") && s.coda.isEmpty()) nuc = "IU"
            }
            // Extra consonants at the start become their own "pờ / xờ" syllable.
            if (on.size > 1) {
                for (c in on.dropLast(1)) out += spellPart(Part(ONSET[c].orEmpty(), false, "əː", "", "", HUYEN))
                on = on.takeLast(1)
            }
            out += spellPart(part(on.firstOrNull()?.let { ONSET[it] }.orEmpty(), w, nuc, s.coda.firstOrNull()))
        }
        return out.joinToString(" ")
    }

    private fun part(onset: String, w: Boolean, nuc: String, codaIn: String?): Part {
        var (n, glide) = NUCLEUS.getValue(nuc)
        var coda = codaIn
        if (coda == null && glide.isEmpty()) n = when (n) { "a" -> "aː"; "ə" -> "əː"; else -> n }
        if (glide.isNotEmpty() && coda != null && nuc != "eɪ") coda = null
        // "apple" → áp pồ, "level" → le vồ, "google" → gu gồ.
        if ((nuc == "ə" || nuc == "ɚ") && (coda == "l" || coda == "ɫ")) return Part(onset, w, "o", "", "", HUYEN)
        // "email" → i meo, "sale" → xeo.
        if (nuc == "eɪ" && (coda == "l" || coda == "ɫ")) return Part(onset, w, "ɛ", "w", "", "")
        if (nuc == "eɪ" && coda != null) { n = "e"; glide = "" }
        var tone = ""
        var c = ""
        when (coda) {
            in STOP -> {
                c = STOP.getValue(coda!!)
                tone = SAC
            }
            in NASAL -> {
                c = NASAL.getValue(coda!!)
                if (c == "ŋ" && (n == "i" || n == "e")) c = "ɲ"
                if ((coda == "l" || coda == "ɫ") && n in setOf("ɛ", "e", "i", "iə")) c = "w"
            }
        }
        if (c == "w" || c == "j") { glide = c; c = "" }
        return Part(onset, w, n, glide, c, tone)
    }

    private val LETTER = mapOf(
        "i" to "i", "e" to "ê", "ɛ" to "e", "aː" to "a", "a" to "ă", "ə" to "â", "əː" to "ơ", "ɔ" to "o", "o" to "ô", "u" to "u",
    )
    private val WITH_J = mapOf(
        "aː" to "ai", "a" to "ay", "ə" to "ây", "əː" to "ơi", "ɔ" to "oi", "o" to "ôi", "u" to "ui", "uə" to "uôi",
        "e" to "ây", "ɛ" to "ay", "i" to "i",
    )
    private val WITH_W = mapOf(
        "i" to "iu", "e" to "êu", "ɛ" to "eo", "aː" to "ao", "a" to "au", "ə" to "âu", "iə" to "iêu", "u" to "u",
        "o" to "ô", "ɔ" to "ao", "əː" to "ơ", "uə" to "uô",
    )
    /** A "w" sound before the vowel: "oa", "oe", "uy"… ("twist" → tuýt, "window" → uyn đô). */
    private val ROUNDED = mapOf(
        "a" to "oa", "ă" to "oă", "e" to "oe", "ê" to "uê", "i" to "uy", "ơ" to "uơ", "â" to "uâ", "ia" to "uya",
        "iê" to "uyê", "ai" to "oai", "ay" to "oay", "ao" to "oao", "eo" to "oeo",
    )

    private fun spellPart(p: Part): String {
        val n = p.n
        var v = when {
            p.glide == "j" -> WITH_J[n] ?: (LETTER[n].orEmpty() + "i")
            p.glide == "w" -> WITH_W[n] ?: (LETTER[n].orEmpty() + "u")
            n == "iə" -> if (p.coda.isEmpty()) "ia" else "iê"
            n == "uə" -> if (p.coda.isEmpty()) "ua" else "uô"
            else -> LETTER[n].orEmpty()
        }
        if (n == "iə" && p.onset.isEmpty() && !p.w && v.startsWith("iê")) v = "y" + v.substring(1)
        val rounded = p.w && n !in setOf("ɔ", "o", "u", "uə")
        if (rounded) v = ROUNDED[v] ?: v
        val coda = when (p.coda) {
            "p" -> "p"; "t" -> "t"; "k" -> "c"; "m" -> "m"; "n" -> "n"; "ŋ" -> "ng"; "ɲ" -> "nh"
            "c" -> if (n == "i" || n == "e") "ch" else "c"
            else -> ""
        }
        var onset: String
        if (rounded && p.onset == "k") {
            // "qu" carries the w: "qua", "que", "quy", "quét".
            onset = "qu"
            v = v.drop(1)
        } else {
            val first = v.firstOrNull() ?: 'a'
            val front = first in "ieêy"
            onset = when (p.onset) {
                "k" -> if (front) "k" else "c"
                "ɣ" -> if (front && first != 'y') "gh" else "g"
                "ŋ" -> if (front && first != 'y') "ngh" else "ng"
                "p" -> "p"; "b" -> "b"; "t", "t̪", "ɾ" -> "t"; "tʃ" -> "ch"; "ɗ" -> "đ"; "m" -> "m"; "n" -> "n"
                "f" -> "ph"; "v" -> "v"; "s" -> "x"; "z" -> "d"; "h" -> "h"; "l" -> "l"; "x" -> "kh"
                else -> ""
            }
        }
        val mark = when (p.tone) { SAC -> "\u0301"; HUYEN -> "\u0300"; else -> "" }
        if (mark.isNotEmpty() && v.isNotEmpty()) {
            val special = v.indexOfLast { it in "êơôâăư" }
            val vowels = v.indices.filter { v[it] in "aeiouy" }
            val at = when {
                special >= 0 -> special
                vowels.isEmpty() -> v.lastIndex
                coda.isNotEmpty() || v in setOf("oa", "oe", "uy") -> vowels.last()
                else -> vowels.first()
            }
            v = v.substring(0, at + 1) + mark + v.substring(at + 1)
        }
        return Normalizer.normalize(onset + v + coda, Normalizer.Form.NFC)
    }
}
