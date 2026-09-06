package ru.zf.slushalka.ask

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.data.Settings

/**
 * Anthropic Messages API напрямую с телефона - как в Правке: ключ вводится в
 * настройках и с устройства не уезжает, никаких прокси посередине.
 */
class ClaudeClient(private val settings: Settings) {

    /** Кусок системного промпта. [cache] - можно ли кэшировать его на час. */
    data class Block(val text: String, val cache: Boolean = false)

    data class Reply(val text: String, val costUsd: Double)

    class ApiException(message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Таймаут стрима считается по молчанию между кусками, а не по всей
        // генерации: длинный ответ не обрывается на середине.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var active: okhttp3.Call? = null

    fun cancel() {
        runCatching { active?.cancel() }
    }

    suspend fun ask(
        model: String,
        system: List<Block>,
        question: String,
        maxTokens: Int = 1400,
        /** output_config.effort; пусто — не передавать, решает API. */
        effort: String = "",
        onDelta: (String) -> Unit = {},
    ): Result<Reply> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.now().apiKey
            if (key.isBlank()) throw ApiException("Не задан ключ Anthropic - вставь его в настройках.")

            val body = JSONObject().apply {
                put("model", model)
                // Размышления считаются в тот же max_tokens: на глубоком
                // усилии без запаса ответ обрывался бы на мыслях, не начавшись.
                put("max_tokens", maxTokens + if (effort in DEEP_EFFORTS) 8000 else 0)
                if (effort.isNotBlank()) put("output_config", JSONObject().put("effort", effort))
                // Параметр thinking не передаётся: адаптивные мысли — поведение
                // по умолчанию у всех трёх моделей, а явное «disabled» Fable
                // отвергает с 400.
                put("stream", true)
                put("system", JSONArray().apply {
                    system.filter { it.text.isNotBlank() }.forEach { b ->
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", b.text)
                            // Час жизни кэша: за вечер вопросов бывает
                            // несколько, и книга-контекст не должна
                            // оплачиваться каждый раз заново. Ровно та же
                            // связка, что работает в Правке, - без бета-заголовка,
                            // который на этом аккаунте не нужен.
                            if (b.cache) put(
                                "cache_control",
                                JSONObject().put("type", "ephemeral").put("ttl", "1h"),
                            )
                        })
                    }
                })
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", question)
                ))
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            active = call
            try {
                stream(call, model, onDelta)
            } finally {
                active = null
            }
        }
    }

    private fun stream(call: okhttp3.Call, model: String, onDelta: (String) -> Unit): Reply {
        val sb = StringBuilder()
        var inputTokens = 0
        var cacheWrite = 0
        var cacheRead = 0
        var outputTokens = 0

        call.execute().use { resp ->
            val source = resp.body?.source() ?: throw ApiException("Пустой ответ сервера")
            if (!resp.isSuccessful) {
                val raw = runCatching { source.readUtf8() }.getOrDefault("")
                throw ApiException(errorText(resp.code, raw))
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta") ?: continue
                        val piece = delta.optString("text")
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            onDelta(sb.toString())
                        }
                    }
                    "message_start" -> {
                        val usage = obj.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = usage?.optInt("input_tokens") ?: 0
                        cacheWrite = usage?.optInt("cache_creation_input_tokens") ?: 0
                        cacheRead = usage?.optInt("cache_read_input_tokens") ?: 0
                    }
                    "message_delta" -> {
                        outputTokens = obj.optJSONObject("usage")?.optInt("output_tokens")
                            ?: outputTokens
                    }
                    "error" -> throw ApiException(
                        obj.optJSONObject("error")?.optString("message") ?: "Ошибка модели"
                    )
                }
            }
        }
        if (sb.isBlank()) throw IOException("Модель не ответила")
        return Reply(
            text = sb.toString().trim(),
            costUsd = costUsd(model, inputTokens, outputTokens, cacheWrite, cacheRead),
        )
    }

    private fun errorText(code: Int, raw: String): String {
        val msg = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            401 -> "Ключ Anthropic не принят (401). Проверь его в настройках."
            429 -> "Слишком часто (429). Через минуту получится."
            in 500..599 -> "Сервер модели сейчас не отвечает ($code)."
            else -> if (msg.isNotBlank()) "$code: $msg" else "Ошибка запроса ($code)"
        }
    }

    companion object {
        /** Усилия, на которых мысли занимают заметную часть бюджета ответа. */
        private val DEEP_EFFORTS = setOf("xhigh", "max")

        private class Price(val input: Double, val output: Double, cacheRead: Double? = null) {
            val cacheRead: Double = cacheRead ?: (input * 0.1)
        }

        // Доллары за миллион токенов; кэш - производная от входной цены:
        // запись на час стоит 2x, чтение 0.1x, если у модели нет своей цены.
        private val PRICES = mapOf(
            // Сонет 5 дешевле 4.6 ($3/$15): старая цена завышала расход в полтора раза.
            Settings.MODEL_SONNET to Price(2.0, 10.0),
            Settings.MODEL_OPUS to Price(5.0, 25.0),
            // Fable 5.1: вдвое дороже Опуса, чтение кэша — $0.25 за миллион.
            Settings.MODEL_FABLE to Price(10.0, 50.0, cacheRead = 0.25),
        )

        fun costUsd(
            model: String,
            inputTokens: Int,
            outputTokens: Int,
            cacheWriteTokens: Int = 0,
            cacheReadTokens: Int = 0,
        ): Double {
            val p = PRICES[model] ?: return 0.0
            val input =
                (inputTokens + 2.0 * cacheWriteTokens) / 1_000_000.0 * p.input +
                    cacheReadTokens / 1_000_000.0 * p.cacheRead
            return input + outputTokens / 1_000_000.0 * p.output
        }
    }
}
