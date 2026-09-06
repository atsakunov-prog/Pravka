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

    /** Реплика разговора: роль «user» или «assistant» и её текст. */
    data class Turn(val role: String, val text: String)

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
    ): Result<Reply> = chat(
        model = model,
        system = system,
        turns = listOf(Turn("user", question)),
        maxTokens = maxTokens,
        effort = effort,
        onDelta = onDelta,
    )

    /**
     * Разговор из нескольких реплик, при желании - с поиском в интернете.
     *
     * [webSearch] подключает серверный инструмент `web_search`: модель сама
     * ищет и читает, а сюда приходит только текст. Поиск платный - десять
     * долларов за тысячу запросов, - поэтому число обращений ограничено, а
     * каждое учитывается в цене ответа.
     */
    suspend fun chat(
        model: String,
        system: List<Block>,
        turns: List<Turn>,
        maxTokens: Int = 1400,
        effort: String = "",
        webSearch: Boolean = false,
        onDelta: (String) -> Unit = {},
    ): Result<Reply> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.now().apiKey
            if (key.isBlank()) throw ApiException("Не задан ключ Anthropic - вставь его в настройках.")

            val body = JSONObject().apply {
                put("model", model)
                if (webSearch) {
                    put("tools", JSONArray().put(
                        JSONObject()
                            .put("type", "web_search_20260209")
                            .put("name", "web_search")
                            .put("max_uses", MAX_WEB_SEARCHES)
                    ))
                }
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
                put("messages", JSONArray().apply {
                    turns.filter { it.text.isNotBlank() }.forEach { t ->
                        put(JSONObject().put("role", t.role).put("content", t.text))
                    }
                })
            }

            val request = Request.Builder()
                .url("$API/messages")
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

    // ------------------------------------------------------------ пакеты

    /**
     * Пакетный запрос (Message Batches): те же запросы к /v1/messages, но без
     * ожидания у экрана и вдвое дешевле. Ответ обычно за час, крайний срок -
     * сутки. Так считается справочник по книге: книга целиком - это сотни тысяч
     * токенов, платить за них полную цену и держать телефон в руках незачем.
     */
    data class Batch(
        val id: String,
        /** «in_progress», «canceling» или «ended». */
        val status: String,
        val resultsUrl: String?,
        val succeeded: Int,
        val errored: Int,
        val processing: Int,
    ) {
        val ended get() = status == "ended"
    }

    /**
     * [requests] - объекты вида {custom_id, params}, где params - обычное тело
     * запроса к /v1/messages (без stream).
     */
    suspend fun createBatch(requests: List<JSONObject>): Result<Batch> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("requests", JSONArray(requests))
            val req = builder("$API/messages/batches")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            // Книга целиком - несколько мегабайт JSON; на отправку отводится больше.
            parseBatch(executeJson(slow.newCall(req)))
        }
    }

    suspend fun batch(id: String): Result<Batch> = withContext(Dispatchers.IO) {
        runCatching { parseBatch(executeJson(client.newCall(builder("$API/messages/batches/$id").get().build()))) }
    }

    /**
     * Результаты пакета - JSONL: по объекту на запрос, {custom_id, result}.
     * Порядок не гарантирован, сверять по custom_id.
     */
    suspend fun batchResults(url: String): Result<List<JSONObject>> = withContext(Dispatchers.IO) {
        runCatching {
            slow.newCall(builder(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(errorText(resp.code, text))
                text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .toList()
            }
        }
    }

    private fun builder(url: String): Request.Builder {
        val key = settings.now().apiKey
        if (key.isBlank()) throw ApiException("Не задан ключ Anthropic - вставь его в настройках.")
        return Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
    }

    private fun executeJson(call: okhttp3.Call): JSONObject = call.execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(errorText(resp.code, text))
        JSONObject(text)
    }

    private fun parseBatch(o: JSONObject): Batch {
        val counts = o.optJSONObject("request_counts")
        return Batch(
            id = o.optString("id"),
            status = o.optString("processing_status"),
            resultsUrl = o.optString("results_url").takeIf { it.isNotBlank() && it != "null" },
            succeeded = counts?.optInt("succeeded") ?: 0,
            errored = counts?.optInt("errored") ?: 0,
            processing = counts?.optInt("processing") ?: 0,
        )
    }

    /** Тот же клиент, но с запасом на отправку и приём мегабайтов: книга целиком. */
    private val slow: OkHttpClient by lazy {
        client.newBuilder()
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun stream(call: okhttp3.Call, model: String, onDelta: (String) -> Unit): Reply {
        val sb = StringBuilder()
        var inputTokens = 0
        var cacheWrite = 0
        var cacheRead = 0
        var outputTokens = 0
        var webSearches = 0
        var refused = false

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
                        val usage = obj.optJSONObject("usage")
                        outputTokens = usage?.optInt("output_tokens") ?: outputTokens
                        usage?.optJSONObject("server_tool_use")?.let { st ->
                            webSearches = st.optInt("web_search_requests", webSearches)
                        }
                        // Фильтр безопасности отвечает не ошибкой, а обычным 200 со
                        // stop_reason «refusal»; проверять надо его, а не текст.
                        if (obj.optJSONObject("delta")?.optString("stop_reason") == "refusal") refused = true
                    }
                    "error" -> throw ApiException(
                        obj.optJSONObject("error")?.optString("message") ?: "Ошибка модели"
                    )
                }
            }
        }
        if (refused) throw ApiException("Модель отказалась отвечать на этот запрос")
        if (sb.isBlank()) throw IOException("Модель не ответила")
        return Reply(
            text = sb.toString().trim(),
            costUsd = costUsd(model, inputTokens, outputTokens, cacheWrite, cacheRead) +
                webSearches * WEB_SEARCH_USD,
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
        private const val API = "https://api.anthropic.com/v1"

        /** Пакетные запросы стоят половину обычной цены - за ожидание. */
        const val BATCH_DISCOUNT = 0.5

        /** Усилия, на которых мысли занимают заметную часть бюджета ответа. */
        private val DEEP_EFFORTS = setOf("xhigh", "max")

        /** Поисков в интернете на один ответ: трёх хватает узнать, что говорят о книге. */
        private const val MAX_WEB_SEARCHES = 3
        /** Десять долларов за тысячу поисков. */
        private const val WEB_SEARCH_USD = 0.01

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

        /** Цена чтения кэша за миллион токенов - для прикидки «следующие вопросы». */
        fun cacheReadPerMillion(model: String): Double = PRICES[model]?.cacheRead ?: 0.0

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
