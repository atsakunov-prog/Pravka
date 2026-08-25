package ru.zf.pravka.desktop.speech

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

// Клиент локального распознавателя (scripts/whisper): OpenAI-совместимый
// multipart-запрос. Совместимость выбрана нарочно - сменить реализацию сервера
// можно, поменяв URL в настройках, без правки приложения.
class WhisperServerClient(private val client: OkHttpClient) {

    class WhisperException(message: String) : Exception(message)

    // Отдельные таймауты: час встречи на large-v3 идёт минуты, а вот
    // соединение с localhost или устанавливается сразу, или его нет.
    private val call = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .build()

    /**
     * @param hint слова из словаря: Whisper получает их как initial_prompt и
     *   распознаёт фамилии с терминами правильно сразу, а не чинит потом.
     */
    suspend fun transcribe(
        wav: File,
        url: String,
        model: String,
        hint: String = "",
        language: String = "ru",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!wav.exists() || wav.length() <= 44) throw WhisperException("Пустая запись.")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .apply { if (hint.isNotBlank()) addFormDataPart("prompt", hint) }
                .build()

            val response = try {
                call.newCall(Request.Builder().url(url).post(body).build()).execute()
            } catch (e: java.io.IOException) {
                throw WhisperException(
                    "Распознаватель не отвечает ($url). Запущен ли сервер? " +
                        "См. scripts/whisper/README.md."
                )
            }

            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    throw WhisperException("Распознаватель ответил ${it.code}: ${text.take(200)}")
                }
                val parsed = runCatching { JSONObject(text).optString("text") }.getOrNull()
                    ?: text  // response_format=text
                parsed.trim().ifEmpty { throw WhisperException("Распознавание вернуло пустой текст.") }
            }
        }
    }

    /** Живой ли сервер и что у него загружено - для строки состояния в настройках. */
    suspend fun health(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val healthUrl = url.substringBefore("/v1/").trimEnd('/') + "/health"
            client.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw WhisperException("HTTP ${r.code}")
                val o = JSONObject(body)
                val loaded = o.optJSONArray("loaded")
                val names = (0 until (loaded?.length() ?: 0)).joinToString(", ") { i -> loaded!!.getString(i) }
                if (names.isBlank()) "Сервер жив, модель ещё не загружена" else "Готов: $names"
            }
        }
    }
}
