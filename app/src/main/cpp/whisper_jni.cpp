#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "PravkaWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_ru_zf_pravka_provider_WhisperNative_initContext(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only on Tensor - the reliable path
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed for %s", path);
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_ru_zf_pravka_provider_WhisperNative_transcribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jfloatArray samples,
        jint threads, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = lang;
    params.n_threads        = threads > 0 ? threads : 4;
    params.no_context       = true;
    params.single_segment   = false;

    std::string text;
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(n)) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed");
    }

    env->ReleaseStringUTFChars(language, lang);
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_ru_zf_pravka_provider_WhisperNative_freeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}
