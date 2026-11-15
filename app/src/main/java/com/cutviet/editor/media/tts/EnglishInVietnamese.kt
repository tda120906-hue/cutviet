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

    internal fun word(ipa: String): String {
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
        if (syls.isEmpty()) return ""
        syls.last().coda = cons
        // Between two vowels the first consonant closes the syllable before, the rest open the next.
        for (k in 1 until syls.size) {
            val on = syls[k].onset
            if (on.size > 1) {
                val keep = if (on.takeLast(2) == listOf("t", "ɹ") || on.last() == "w" || on.last() == "j") on.takeLast(2) else on.takeLast(1)
                val rest = on.dropLast(keep.size)
                syls[k - 1].coda = rest.take(1)
                syls[k].onset = rest.drop(1) + keep
            }
        }
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
}
