package com.cutviet.editor.media

import com.cutviet.editor.media.tts.EnglishInVietnamese
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Expected values come from the Python prototype the rules were tuned with (espeak-ng 212928b). */
class EnglishInVietnameseTest {
    private val words = listOf(
        "ˈɛdɪt" to "ˈɛ ɗˈiɜt̪",
        "vˈɪdɪʲəʊ" to "vˈi ɗˈi ˈo",
        "lˈaɪk" to "lˈaːj",
        "ʃˈeə" to "sˈɛ",
        "səbskɹˈaɪb" to "sˈəɜp sˈəː2 kˈəː2 zˈaːj",
        "dˈɛdlaɪn" to "ɗˈɛɜt̪ lˈaːj",
        "pɹˈɒdʒɛkt" to "pˈəː2 zˈɔ zˈɛɜc",
        "tˈiːm" to "t̪ˈim",
        "mˈiːtɪŋ" to "mˈi t̪ˈiɲ",
        "lˈaptɒp" to "lˈaːɜp t̪ˈɔɜp",
        "smˈuːð" to "sˈəː2 mˈuɜt̪",
        "dɪzˈaɪn" to "ɗˈi zˈaːj",
        "ˈʌpdeɪt" to "ˈaɜp ɗˈeɜt̪",
        "ˈap" to "ˈaːɜp",
        "kˈuːl" to "kˈun",
        "pɹəfˈɛʃənəl" to "pˈəː2 zˈəː fˈɛ sˈəː nˈəː",
        "ˈɔːdə" to "ˈɔ ɗˈəː",
        "ˈɒnlaɪn" to "ˈɔn lˈaːj",
        "tʃˈɛk" to "tʃˈɛɜc",
        "ˈiːmeɪl" to "ˈi mˈew",
        "vˈaʊtʃə" to "vˈaːw tʃˈəː",
        "ɹɪvjˈuː" to "zˈi vˈiw",
        "fˈəʊn" to "fˈon",
        "ɡˈeɪm" to "ɣˈem",
        "stɹˈiːm" to "sˈəː2 tʃˈim",
        "hˈapi" to "hˈaː pˈi",
        "wiːkˈɛnd" to "wˈi kˈɛn",
        "kˈɒfi" to "kˈɔ fˈi",
        "ʃˈɒpɪŋ" to "sˈɔ pˈiɲ",
        "stˈaɪl" to "sˈəː2 t̪ˈaːj",
        "lˈɛvəl" to "lˈɛ vˈəː",
        "kˈɒmɛnt" to "kˈɔ mˈɛn",
        "pˈəʊst" to "pˈoɜt̪",
        "sˈeɪl" to "sˈew",
        "mˈanɪdʒə" to "mˈaː nˈi zˈəː",
        "mˈɑːkɪtɪŋ" to "mˈaː kˈi t̪ˈiɲ",
        "bˈɜːθdeɪ" to "bˈəːɜt̪ ɗˈej",
        "mjˈuːzɪk" to "mˈiw zˈiɜc",
        "kˈamɹə" to "kˈaːm zˈəː",
        "dˈaʊnləʊd" to "ɗˈaːw lˈoɜt̪",
        "pˈaswɜːd" to "pˈaː swˈəːɜt̪",
        "mˈɛsɪdʒ" to "mˈɛ sˈiɜc",
        "twˈɪst" to "t̪wˈiɜt̪",
        "plˈɒt" to "pˈəː2 lˈɔɜt̪",
    )

    private val clauses = listOf(
        "hˈom nˈaj mˈi2ɲ (en)ˈɛdɪt vˈɪdɪʲəʊ(vi) tʃˈen (en)tˈɪk tˈɒk(vi)" to "hˈom nˈaj mˈi2ɲ ˈɛ ɗˈiɜt̪ vˈi ɗˈi ˈo tʃˈen t̪ˈiɜc t̪ˈɔɜk",
        "ɲˈəːɜ (en)lˈaɪk(vi)" to "ɲˈəːɜ lˈaːj",
        "(en)ʃˈeə(vi) vˌaː2 (en)səbskɹˈaɪb(vi) kˈeɲ (en)juː tjˈuːb(vi) ɲˈɛɜ" to "sˈɛ vˌaː2 sˈəɜp sˈəː2 kˈəː2 zˈaːj kˈeɲ zˈu t̪ˈuɜp ɲˈɛɜ",
    )

    @Test
    fun englishWordsBecomeVietnameseSyllables() {
        for ((ipa, vi) in words) assertEquals(ipa, vi, EnglishInVietnamese.convert(ipa))
    }

    @Test
    fun clausesKeepVietnameseAndDropFlags() {
        for ((raw, out) in clauses) assertEquals(raw, out, EnglishInVietnamese.convertClause(raw))
    }

    @Test
    fun onlyPhonemesTheVoiceKnows() {
        // Every symbol espeak-ng's Vietnamese voice itself produces (checked on all syllables).
        val known = "ˈˌ wəjtːcaŋi2456ɜmenɲp̪kyzɔɛosʃuɣh1ɗxlfvbʲɪʊ-".toSet()
        for ((ipa, _) in words) {
            val out = EnglishInVietnamese.convert(ipa)
            assertFalse("$ipa → $out", out.any { it !in known })
        }
    }

    @Test
    fun vietnameseOnlyClauseIsUnchanged() {
        assertEquals("hˈom nˈaj", EnglishInVietnamese.convertClause("hˈom nˈaj"))
    }
}
