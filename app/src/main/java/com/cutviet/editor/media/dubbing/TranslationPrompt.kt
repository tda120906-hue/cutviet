package com.cutviet.editor.media.dubbing

import com.cutviet.editor.media.VoiceEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds the translation prompt for a window of sentences and reads the model's numbered answer.
 * Each line gets a syllable budget from its time slot (Vietnamese: one word = one syllable), so the
 * translation already fits the time before the TTS fitting loop fine-tunes the speed.
 */
object TranslationPrompt {
    /** Sentences translated together; the ones before are passed as context. */
    const val WINDOW = 8
    const val CONTEXT = 3

    /**
     * Most syllables the Vietnamese line may have to be spoken in [roomMs] at up to 1.1x, for a
     * voice that speaks [syllablesPerSecond] at rate 1.0.
     */
    fun syllableBudget(roomMs: Long, syllablesPerSecond: Double = VoiceEngine.DEFAULT_SYLLABLES_PER_SECOND): Int =
        max(2, (roomMs / 1000.0 * syllablesPerSecond * 1.1).roundToInt())

    /** [budgets]: syllable limit of each line, by default from its own slot. */
    fun build(
        language: SourceLanguage,
        context: List<SpeechSegment>,
        lines: List<SpeechSegment>,
        budgets: List<Int> = lines.map { syllableBudget(it.slotMs) },
    ): String = buildString {
        append("Dịch các câu thoại sau từ ${language.label.lowercase()} sang tiếng Việt tự nhiên, giữ đúng sắc thái, ")
        append("giọng điệu và cách xưng hô phù hợp ngữ cảnh. Không dịch từng chữ. ")
        append("Đây là lời thoại để lồng tiếng, nên mỗi câu dịch không được dài hơn số âm tiết ghi trong ngoặc vuông. ")
        append("Trả về đúng ${lines.size} dòng, mỗi dòng dạng \"số. bản dịch\", không giải thích gì thêm.\n")
        if (context.isNotEmpty()) {
            append("\nNgữ cảnh (các câu ngay trước, không cần dịch):\n")
            context.forEach { append("- ").append(it.text.trim()).append('\n') }
        }
        append("\nCâu cần dịch:\n")
        lines.forEachIndexed { i, s ->
            append(i + 1).append(". [tối đa ").append(budgets[i]).append(" âm tiết] ")
            append(s.text.trim()).append('\n')
        }
    }

    /** Asks for a shorter version of one dubbed line that ran past the time it has. */
    fun shorten(language: SourceLanguage, source: String, current: String, maxSyllables: Int): String = buildString {
        append("Câu lồng tiếng tiếng Việt dưới đây quá dài so với thời gian nói. ")
        append("Hãy viết lại ngắn hơn, tối đa $maxSyllables âm tiết, giữ ý chính và giọng điệu tự nhiên, ")
        append("có thể bỏ từ đệm hoặc ý phụ. Chỉ trả về câu đã rút gọn, không giải thích.\n")
        append("Câu gốc (${language.label.lowercase()}): ").append(source.trim()).append('\n')
        append("Bản dịch hiện tại: ").append(current.trim()).append('\n')
    }

    private val NUMBERED = Regex("""^\s*(\d+)\s*[.):：、]\s*(.*)$""")

    /**
     * Reads "n. text" lines. Returns one entry per input line (null where the model skipped it), or
     * null if nothing usable came back.
     */
    fun parse(response: String, count: Int): List<String?>? {
        val out = arrayOfNulls<String>(count)
        var found = 0
        for (raw in response.lines()) {
            val m = NUMBERED.find(raw) ?: continue
            val i = m.groupValues[1].toInt() - 1
            val text = clean(m.groupValues[2])
            if (i in 0 until count && text.isNotEmpty() && out[i] == null) {
                out[i] = text
                found++
            }
        }
        if (found == 0 && count == 1) {
            val single = clean(response.lines().firstOrNull { it.isNotBlank() } ?: "")
            if (single.isNotEmpty()) return listOf(single)
        }
        return if (found == 0) null else out.toList()
    }

    private fun clean(s: String): String = s
        .replace(Regex("""^\[[^\]]*]\s*"""), "") // an echoed "[tối đa n âm tiết]"
        .replace("**", "")
        .trim()
        .trim('"', '“', '”')
        .trim()
}
