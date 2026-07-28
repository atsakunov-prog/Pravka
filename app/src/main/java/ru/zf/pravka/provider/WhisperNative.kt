package ru.zf.pravka.provider

// JNI bridge to whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). The
// native library is built only on CI. Methods map 1:1 to the C++ symbols
// Java_ru_zf_pravka_provider_WhisperNative_*.
object WhisperNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("pravka_whisper")
            loaded = true
            true
        }.getOrDefault(false)
    }

    /** Returns a native context pointer (0 on failure). */
    external fun initContext(modelPath: String): Long

    /** Transcribes 16 kHz mono float PCM in [-1,1]; returns the text. */
    external fun transcribe(ctxPtr: Long, samples: FloatArray, threads: Int, language: String): String

    external fun freeContext(ctxPtr: Long)
}
