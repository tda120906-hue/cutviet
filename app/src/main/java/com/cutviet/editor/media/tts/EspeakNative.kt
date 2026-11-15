package com.cutviet.editor.media.tts

/** espeak-ng (src/main/cpp/espeak_jni.cpp), used only to turn text into phonemes for [PiperTts]. */
object EspeakNative {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("cutviet_tts") }.isSuccess }

    external fun init(dataDir: String): Boolean

    /** Clauses joined by \u0002, each "phonemes\u0001terminator\u0001(1 if it ends a sentence)". */
    external fun phonemize(voice: String, text: ByteArray): String?
}
