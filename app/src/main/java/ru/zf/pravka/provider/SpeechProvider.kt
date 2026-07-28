package ru.zf.pravka.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import ru.zf.pravka.data.Settings

// On-device Russian speech-to-text via the ML Kit GenAI Speech Recognition
// API (genai-speech-recognition 1.0.0-alpha1). Advanced mode runs on Gemini
// Nano (Pixel 10). Unlike the earlier Prompt-API dead end, recognition here
// is driven from a FOREGROUND service/activity, so AICore's "background
// usage blocked" (error 30) does not apply.
//
// The recognizer reads from a ParcelFileDescriptor of the recorded WAV, so
// recording and transcription stay decoupled - the audio file is the source
// of truth and can be retried later or sent to Whisper instead.
class SpeechProvider(
    private val context: Context,
    private val settings: Settings,
) {

    class SpeechException(message: String) : Exception(message)

    private val client by lazy {
        val options = SpeechRecognizerOptions.builder()
            .apply {
                locale = Locale.forLanguageTag("ru-RU")
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED  // Gemini Nano
            }
            .build()
        SpeechRecognition.getClient(options)
    }

    suspend fun status(): Int = withContext(Dispatchers.IO) {
        runCatching { client.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
    }

    suspend fun statusText(): String = when (runCatching { status() }.getOrDefault(FeatureStatus.UNAVAILABLE)) {
        FeatureStatus.AVAILABLE -> "Готово к распознаванию"
        FeatureStatus.DOWNLOADABLE -> "Модель не скачана — нажми «Скачать»"
        FeatureStatus.DOWNLOADING -> "Модель скачивается…"
        else -> "Недоступно на этом устройстве"
    }

    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var failure: Throwable? = null
            client.download().collect { s ->
                if (s is DownloadStatus.DownloadFailed) failure = s.e
            }
            failure?.let { throw it }
            Unit
        }
    }

    /** Transcribes [wav] to text, or fails - the caller keeps the file for retry. */
    suspend fun transcribe(wav: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (client.checkStatus() != FeatureStatus.AVAILABLE) {
                throw SpeechException("Модель распознавания не готова. Открой Правку и скачай её в настройках.")
            }
            val pfd = ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use {
                val request = SpeechRecognizerRequest.builder()
                    .apply { audioSource = AudioSource.fromPfd(it) }
                    .build()
                val sb = StringBuilder()
                client.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.FinalTextResponse ->
                            sb.append(response.text)  // segment finalized
                        is SpeechRecognizerResponse.ErrorResponse ->
                            throw SpeechException(response.e.message ?: "Ошибка распознавания")
                        else -> Unit  // PartialTextResponse / CompletedResponse
                    }
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) throw SpeechException("Распознавание вернуло пустой текст.")
                text
            }
        }
    }
}
