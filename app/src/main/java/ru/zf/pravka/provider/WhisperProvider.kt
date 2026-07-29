package ru.zf.pravka.provider

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.data.Settings

// On-device Russian speech-to-text via whisper.cpp (small/base ggml model,
// owner's choice over the ML Kit alpha). File-based, so the recorded WAV is
// the source of truth and a failed take can be retried later. The model is
// downloaded once into filesDir/models; the native context is kept warm
// between takes so only the first transcription after a cold start pays the
// load cost.
class WhisperProvider(
    private val context: Context,
    private val settings: Settings,
) {

    class WhisperException(message: String) : Exception(message)

    private val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private data class Loaded(val engine: String, val ptr: Long)
    @Volatile private var loaded: Loaded? = null

    // ggml models from the canonical whisper.cpp HF repo. q5_1 is the sweet
    // spot: ~half the size and faster than f16, negligible quality loss.
    private data class ModelSpec(val engine: String, val file: String, val url: String, val approxMb: Int)

    private fun spec(engine: String): ModelSpec = when (engine) {
        Settings.SPEECH_WHISPER_BASE -> ModelSpec(
            engine, "ggml-base-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", 60,
        )
        else -> ModelSpec(
            Settings.SPEECH_WHISPER_SMALL, "ggml-small-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190,
        )
    }

    private fun modelFile(engine: String): File = File(modelsDir, spec(engine).file)

    fun isDownloaded(engine: String): Boolean = modelFile(engine).let { it.exists() && it.length() > 1_000_000 }

    suspend fun statusText(engine: String): String = when {
        !WhisperNative.ensureLoaded() -> "Библиотека распознавания не загрузилась"
        isDownloaded(engine) -> "Модель готова"
        else -> "Модель не скачана — нажми «Скачать»"
    }

    /** Downloads the ggml model for [engine] into filesDir with a temp+rename. */
    suspend fun download(engine: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = spec(engine)
            val target = modelFile(engine)
            if (isDownloaded(engine)) return@runCatching
            val tmp = File(modelsDir, s.file + ".tmp")
            val conn = (java.net.URL(s.url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (tmp.length() < 1_000_000) {
                tmp.delete()
                throw WhisperException("Скачивание оборвалось (получено ${tmp.length()} байт).")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
        }
    }

    fun deleteModel(engine: String) {
        modelFile(engine).delete()
        loaded?.takeIf { it.engine == engine }?.let {
            WhisperNative.freeContext(it.ptr)
            loaded = null
        }
    }

    suspend fun transcribe(wav: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!WhisperNative.ensureLoaded()) {
                throw WhisperException("Нативная библиотека распознавания недоступна.")
            }
            val engine = settings.speechEngine().takeIf { it.startsWith("whisper") }
                ?: Settings.SPEECH_WHISPER_SMALL
            if (!isDownloaded(engine)) {
                throw WhisperException("Модель распознавания не скачана. Открой Правку и скачай её в настройках.")
            }
            val ctx = context(engine)
            val samples = decodeWav(wav)
            if (samples.isEmpty()) throw WhisperException("Пустая или нечитаемая запись.")
            // 4 threads targets the phone's performance cores; piling on the
            // little cores (availableProcessors is 8-9 on Tensor) just adds
            // scheduling contention and runs slower.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val text = WhisperNative.transcribe(ctx, samples, threads, "ru").trim()
            if (text.isEmpty()) throw WhisperException("Распознавание вернуло пустой текст.")
            text
        }
    }

    @Synchronized
    private fun context(engine: String): Long {
        loaded?.let { if (it.engine == engine && it.ptr != 0L) return it.ptr }
        loaded?.let { WhisperNative.freeContext(it.ptr) }
        val ptr = WhisperNative.initContext(modelFile(engine).absolutePath)
        if (ptr == 0L) throw WhisperException("Не удалось загрузить модель распознавания.")
        loaded = Loaded(engine, ptr)
        return ptr
    }

    // 16 kHz mono PCM16 WAV -> float [-1,1]. Our recorder always writes this
    // exact format (data starts at byte 44).
    private fun decodeWav(wav: File): FloatArray {
        RandomAccessFile(wav, "r").use { raf ->
            val len = raf.length()
            if (len <= 44) return FloatArray(0)
            raf.seek(44)
            val dataLen = (len - 44).toInt()
            val bytes = ByteArray(dataLen)
            raf.readFully(bytes)
            val n = dataLen / 2
            val out = FloatArray(n)
            var j = 0
            for (i in 0 until n) {
                val lo = bytes[j].toInt() and 0xff
                val hi = bytes[j + 1].toInt()  // signed high byte
                val sample = (hi shl 8) or lo
                out[i] = sample / 32768f
                j += 2
            }
            return out
        }
    }
}
