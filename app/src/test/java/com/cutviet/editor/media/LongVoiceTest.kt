package com.cutviet.editor.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LongVoiceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun shortTextStaysWhole() {
        assertEquals(listOf("Xin chào các bạn."), VoiceEngine.splitForEngine("  Xin chào các bạn. ", 3950))
    }

    @Test fun longTextSplitsBetweenSentencesUnderLimit() {
        val sentence = "Hôm nay chúng ta học cách dựng video trên điện thoại. "
        val text = sentence.repeat(300)
        val parts = VoiceEngine.splitForEngine(text, 3950)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.length <= 3950); assertTrue(it.endsWith(".")) }
        assertEquals(VoiceEngine.syllableCount(text), parts.sumOf { VoiceEngine.syllableCount(it) })
    }

    @Test fun textWithoutPunctuationSplitsAtSpaces() {
        val text = "một hai ba bốn năm ".repeat(1000)
        val parts = VoiceEngine.splitForEngine(text, 500)
        parts.forEach { assertTrue(it.length <= 500) }
        assertEquals(text.trim().split(" ").size, parts.sumOf { it.split(" ").size })
    }

    @Test fun joinsWavsInOrder() {
        fun wav(name: String, samples: ShortArray) = tmp.newFile(name).apply {
            val data = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, s -> data[2 * i] = (s.toInt() and 0xFF).toByte(); data[2 * i + 1] = (s.toInt() shr 8).toByte() }
            writeBytes(WavConcat.header(1, 1000, data.size.toLong()) + data)
        }
        val a = wav("a.wav", ShortArray(500) { 1 })
        val b = wav("b.wav", ShortArray(1500) { 2 })
        val out = tmp.root.resolve("out.wav")
        assertEquals(2000L, WavConcat.join(listOf(a, b), out))
        val f = WavConcat.readFormat(out)!!
        assertEquals(4000L, f.dataSize)
        val bytes = out.readBytes()
        assertArrayEquals(byteArrayOf(1, 0), bytes.copyOfRange(44, 46))
        assertArrayEquals(byteArrayOf(2, 0), bytes.copyOfRange(bytes.size - 2, bytes.size))
    }
}
