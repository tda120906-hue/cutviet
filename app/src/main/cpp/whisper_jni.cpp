// JNI bridge between CutViet (media/dubbing/WhisperNative.kt) and whisper.cpp.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <fstream>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "CutVietWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct Callback {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
};

// Text goes out as hex bytes: whisper tokens can split a multi-byte UTF-8 character (common in
// Chinese), which JNI's modified UTF-8 strings cannot carry. Kotlin joins and decodes the bytes.
void appendHex(std::string &out, const char *s) {
    static const char *digits = "0123456789abcdef";
    for (const char *p = s; *p; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        out += digits[c >> 4];
        out += digits[c & 15];
    }
}

bool readPcm(const char *path, std::vector<float> &pcm) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    const std::streamsize bytes = in.tellg();
    if (bytes <= 0) return false;
    in.seekg(0);
    pcm.resize(static_cast<size_t>(bytes) / sizeof(float));
    return static_cast<bool>(in.read(reinterpret_cast<char *>(pcm.data()), static_cast<std::streamsize>(pcm.size() * sizeof(float))));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_cutviet_editor_media_dubbing_WhisperNative_load(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ctx) {
        LOGE("failed to load model");
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    LOGI("model loaded: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cutviet_editor_media_dubbing_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cutviet_editor_media_dubbing_WhisperNative_abort(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h) h->abort.store(true);
}

/**
 * Transcribes a raw little-endian float32, mono, 16 kHz PCM file. Returns JSON:
 * {"segments":[{"t0":ms,"t1":ms,"text":"<hex utf-8>","words":[{"t0":ms,"t1":ms,"p":0.9,"text":"<hex>"}]}]}
 * or null on failure or abort.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_cutviet_editor_media_dubbing_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jstring pcmPath, jstring language, jstring prompt,
        jint threads, jobject listener) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h || !h->ctx) return nullptr;

    std::vector<float> pcm;
    {
        const char *p = env->GetStringUTFChars(pcmPath, nullptr);
        const bool ok = readPcm(p, pcm);
        env->ReleaseStringUTFChars(pcmPath, p);
        if (!ok) {
            LOGE("cannot read pcm");
            return nullptr;
        }
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *initial = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.n_threads = threads;
    params.language = lang;
    params.detect_language = false;
    params.translate = false;
    params.initial_prompt = initial;
    params.token_timestamps = true;
    params.split_on_word = true;
    params.suppress_nst = true;
    params.no_context = true;  // avoids repetition loops carrying over between 30 s windows
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;

    Callback cb{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
    }
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *user) {
        auto *c = static_cast<Callback *>(user);
        if (c->listener && c->onProgress) c->env->CallVoidMethod(c->listener, c->onProgress, progress);
    };
    params.progress_callback_user_data = &cb;
    params.abort_callback = [](void *user) { return static_cast<Handle *>(user)->abort.load(); };
    params.abort_callback_user_data = h;

    const int rc = whisper_full(h->ctx, params, pcm.data(), static_cast<int>(pcm.size()));

    env->ReleaseStringUTFChars(language, lang);
    if (initial) env->ReleaseStringUTFChars(prompt, initial);
    if (rc != 0 || h->abort.load()) {
        LOGE("whisper_full failed (%d) or aborted", rc);
        return nullptr;
    }

    const whisper_token eot = whisper_token_eot(h->ctx);
    std::string json = "{\"segments\":[";
    const int n = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < n; ++i) {
        if (i) json += ',';
        json += "{\"t0\":" + std::to_string(whisper_full_get_segment_t0(h->ctx, i) * 10);
        json += ",\"t1\":" + std::to_string(whisper_full_get_segment_t1(h->ctx, i) * 10);
        json += ",\"text\":\"";
        appendHex(json, whisper_full_get_segment_text(h->ctx, i));
        json += "\",\"words\":[";
        bool first = true;
        const int nt = whisper_full_n_tokens(h->ctx, i);
        for (int j = 0; j < nt; ++j) {
            const whisper_token_data d = whisper_full_get_token_data(h->ctx, i, j);
            if (d.id >= eot) continue;  // special and timestamp tokens
            if (!first) json += ',';
            first = false;
            json += "{\"t0\":" + std::to_string(d.t0 * 10) + ",\"t1\":" + std::to_string(d.t1 * 10);
            json += ",\"p\":" + std::to_string(d.p) + ",\"text\":\"";
            appendHex(json, whisper_full_get_token_text(h->ctx, i, j));
            json += "\"}";
        }
        json += "]}";
    }
    json += "]}";
    return env->NewStringUTF(json.c_str());
}

/**
 * Finds the language spoken in a raw float32, mono, 16 kHz PCM file from 30 s windows starting at
 * [offsetsMs] (where someone speaks): the language probabilities are added up over the windows, so
 * one window of music or silence cannot decide alone. Returns "code probability" ("ja 0.93"), or
 * null when no window could be read.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_cutviet_editor_media_dubbing_WhisperNative_detectLanguage(
        JNIEnv *env, jobject, jlong handle, jstring pcmPath, jlongArray offsetsMs, jint threads) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h || !h->ctx) return nullptr;

    std::vector<float> pcm;
    {
        const char *p = env->GetStringUTFChars(pcmPath, nullptr);
        const bool ok = readPcm(p, pcm);
        env->ReleaseStringUTFChars(pcmPath, p);
        if (!ok) {
            LOGE("cannot read pcm");
            return nullptr;
        }
    }
    const jsize count = env->GetArrayLength(offsetsMs);
    std::vector<jlong> offsets(static_cast<size_t>(count));
    env->GetLongArrayRegion(offsetsMs, 0, count, offsets.data());

    const int languages = whisper_lang_max_id() + 1;
    std::vector<float> sum(static_cast<size_t>(languages), 0.0f);
    std::vector<float> probs(static_cast<size_t>(languages), 0.0f);
    const size_t window = static_cast<size_t>(30) * WHISPER_SAMPLE_RATE;
    int used = 0;
    for (const jlong off : offsets) {
        if (h->abort.load()) return nullptr;
        const size_t from = static_cast<size_t>(std::max<jlong>(0, off)) * WHISPER_SAMPLE_RATE / 1000;
        if (from >= pcm.size()) continue;
        const size_t n = std::min(window, pcm.size() - from);
        if (n < static_cast<size_t>(WHISPER_SAMPLE_RATE)) continue;  // under a second
        if (whisper_pcm_to_mel(h->ctx, pcm.data() + from, static_cast<int>(n), threads) != 0) continue;
        if (whisper_lang_auto_detect(h->ctx, 0, threads, probs.data()) < 0) continue;
        for (int i = 0; i < languages; ++i) sum[static_cast<size_t>(i)] += probs[static_cast<size_t>(i)];
        ++used;
    }
    if (used == 0) return nullptr;
    const int best = static_cast<int>(std::max_element(sum.begin(), sum.end()) - sum.begin());
    const std::string out = std::string(whisper_lang_str(best)) + " " + std::to_string(sum[static_cast<size_t>(best)] / used);
    LOGI("language %s from %d windows", out.c_str(), used);
    return env->NewStringUTF(out.c_str());
}
