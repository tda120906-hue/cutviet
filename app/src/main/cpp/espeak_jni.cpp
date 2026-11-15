// JNI bridge between CutViet (media/tts/EspeakNative.kt) and espeak-ng: text -> IPA phonemes per
// clause, exactly as Piper 1.3.0's espeakbridge.c does it, so the voices hear what they were trained on.
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>

#include <espeak-ng/speak_lib.h>

#define TAG "CutVietTts"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define CLAUSE_INTONATION_FULL_STOP 0x00000000
#define CLAUSE_INTONATION_COMMA 0x00001000
#define CLAUSE_INTONATION_QUESTION 0x00002000
#define CLAUSE_INTONATION_EXCLAMATION 0x00003000
#define CLAUSE_TYPE_CLAUSE 0x00040000
#define CLAUSE_TYPE_SENTENCE 0x00080000
#define CLAUSE_PERIOD (40 | CLAUSE_INTONATION_FULL_STOP | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_COMMA (20 | CLAUSE_INTONATION_COMMA | CLAUSE_TYPE_CLAUSE)
#define CLAUSE_QUESTION (40 | CLAUSE_INTONATION_QUESTION | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_EXCLAMATION (45 | CLAUSE_INTONATION_EXCLAMATION | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_COLON (30 | CLAUSE_INTONATION_FULL_STOP | CLAUSE_TYPE_CLAUSE)
#define CLAUSE_SEMICOLON (30 | CLAUSE_INTONATION_COMMA | CLAUSE_TYPE_CLAUSE)

namespace {
std::mutex lock;
bool ready = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cutviet_editor_media_tts_EspeakNative_init(JNIEnv *env, jobject, jstring dataDir) {
    std::lock_guard<std::mutex> g(lock);
    if (ready) return JNI_TRUE;
    const char *dir = env->GetStringUTFChars(dataDir, nullptr);
    int r = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, dir, 0);
    env->ReleaseStringUTFChars(dataDir, dir);
    if (r < 0) {
        LOGE("espeak_Initialize failed: %d", r);
        return JNI_FALSE;
    }
    ready = true;
    return JNI_TRUE;
}

// Text comes in as UTF-8 bytes. Returns the clauses joined by \u0002, each
// "<phonemes>\u0001<terminator>\u0001<1 if it ends a sentence else 0>".
extern "C" JNIEXPORT jstring JNICALL
Java_com_cutviet_editor_media_tts_EspeakNative_phonemize(JNIEnv *env, jobject, jstring voice, jbyteArray jtext) {
    std::lock_guard<std::mutex> g(lock);
    jclass stringClass = env->FindClass("java/lang/String");
    if (!ready) return nullptr;
    const char *v = env->GetStringUTFChars(voice, nullptr);
    int set = espeak_SetVoiceByName(v);
    env->ReleaseStringUTFChars(voice, v);
    if (set < 0) {
        LOGE("espeak_SetVoiceByName failed");
        return nullptr;
    }
    jsize n = env->GetArrayLength(jtext);
    std::string text((size_t) n, '\0');
    env->GetByteArrayRegion(jtext, 0, n, (jbyte *) &text[0]);

    std::string out;
    int count = 0;
    const char *p = text.c_str();
    while (p != nullptr && *p != 0) {
        int terminator = 0;
        const char *ph = espeak_TextToPhonemesWithTerminator((const void **) &p, espeakCHARS_UTF8, espeakPHONEMES_IPA, &terminator);
        terminator &= 0x000FFFFF;
        const char *t = "";
        if (terminator == CLAUSE_PERIOD) t = ".";
        else if (terminator == CLAUSE_QUESTION) t = "?";
        else if (terminator == CLAUSE_EXCLAMATION) t = "!";
        else if (terminator == CLAUSE_COMMA) t = ",";
        else if (terminator == CLAUSE_COLON) t = ":";
        else if (terminator == CLAUSE_SEMICOLON) t = ";";
        bool eos = (terminator & CLAUSE_TYPE_SENTENCE) == CLAUSE_TYPE_SENTENCE;
        if (count++) out += '\x02';
        out += ph ? ph : "";
        out += '\x01';
        out += t;
        out += '\x01';
        out += eos ? '1' : '0';
    }
    // Built from bytes: JNI's NewStringUTF wants modified UTF-8.
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), (const jbyte *) out.data());
    jclass charset = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID f = env->GetStaticFieldID(charset, "UTF_8", "Ljava/nio/charset/Charset;");
    jobject utf8 = env->GetStaticObjectField(charset, f);
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
    return (jstring) env->NewObject(stringClass, ctor, bytes, utf8);
}
