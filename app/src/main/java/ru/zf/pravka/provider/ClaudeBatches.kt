package ru.zf.pravka.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.data.Settings

// Транспорт Message Batches API — для ночного разбора (16.09.2026). Владелец:
// «ежедневный разбор через Fable 5.1 batch processing (high) всего, что
// возможно». Батч — те же запросы Messages, но пачкой и без ожидания ответа:
// вдвое дешевле, результат в течение часа (максимум суток). Здесь только
// HTTP: создать, спросить статус, забрать JSONL результатов; кто что кладёт в
// батч и что с этим делает — core/NightReview.kt.
class ClaudeBatches(private val settings: Settings, private val client: OkHttpClient) {

    class BatchException(message: String) : Exception(message)

    data class Status(
        val processing: String,
        val resultsUrl: String?,
        val succeeded: Int,
        val errored: Int,
        val inFlight: Int,
        val expired: Int,
        val canceled: Int,
    ) {
        val ended: Boolean get() = processing == "ended"
    }

    /** Один результат батча (или одиночного запроса): текст ответа и расход. */
    data class Item(
        val customId: String,
        val type: String,
        val text: String,
        val stopReason: String,
        val error: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheRead: Int,
        val cacheWrite: Int,
    ) {
        /** Fable может ответить отказом (stop_reason=refusal, HTTP 200) — это не результат. */
        val ok: Boolean get() = type == "succeeded" && stopReason != "refusal"
        val failure: String
            get() = when {
                stopReason == "refusal" -> "модель отказалась отвечать (refusal)"
                error.isNotBlank() -> error
                type != "succeeded" -> type
                else -> ""
            }
    }

    companion object {
        private const val BATCHES = "https://api.anthropic.com/v1/messages/batches"
        private const val MESSAGES = "https://api.anthropic.com/v1/messages"
        private const val VERSION = "2023-06-01"
        /** Батч стоит половину обычной цены — на все токены, включая кэш. */
        const val DISCOUNT = 0.5

        /**
         * Параметры одного запроса Messages (внутри батча или одиночного).
         * Форма по модели и усилию — RequestPolicy: Fable размышляет всегда,
         * параметр thinking ей не передаётся, глубину задаёт effort.
         *
         * [cacheSystem] — точка кэша на системном блоке с часовым сроком:
         * ночной разбор кладёт туда свидетельства ночи, одинаковые для первого
         * прохода и проверки, — проверка через полчаса-час читает их из кэша
         * (у Fable чтение — сороковая часть цены входа).
         */
        fun params(
            model: String,
            effort: String,
            maxTokens: Int,
            system: String,
            user: String,
            cacheSystem: Boolean = false,
        ): JSONObject =
            JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens + RequestPolicy.thinkingHeadroom(model, effort))
                if (effort.isNotBlank()) put("output_config", JSONObject().put("effort", effort))
                if (RequestPolicy.thinkingOff(model, effort)) put("thinking", JSONObject().put("type", "disabled"))
                if (system.isNotBlank()) {
                    if (cacheSystem) {
                        put(
                            "system",
                            JSONArray().put(
                                JSONObject().put("type", "text").put("text", system)
                                    .put("cache_control", JSONObject().put("type", "ephemeral").put("ttl", "1h"))
                            ),
                        )
                    } else put("system", system)
                }
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            }

        /** Строка результата батча или тело одиночного ответа — в один Item. */
        fun parseItem(o: JSONObject): Item {
            val res = o.optJSONObject("result") ?: o
            val type = if (o.has("result")) res.optString("type") else "succeeded"
            val msg = res.optJSONObject("message") ?: if (o.has("result")) null else o
            val text = msg?.optJSONArray("content")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.optString("text") }
            }.orEmpty()
            val u = msg?.optJSONObject("usage")
            val err = res.optJSONObject("error")?.optString("message").orEmpty()
            return Item(
                customId = o.optString("custom_id"),
                type = type,
                text = text,
                stopReason = msg?.optString("stop_reason").orEmpty(),
                error = err,
                inputTokens = u?.optInt("input_tokens") ?: 0,
                outputTokens = u?.optInt("output_tokens") ?: 0,
                cacheRead = u?.optInt("cache_read_input_tokens") ?: 0,
                cacheWrite = u?.optInt("cache_creation_input_tokens") ?: 0,
            )
        }
    }

    private suspend fun apiKey(): String {
        val key = settings.apiKey()
        if (key.isBlank()) throw BatchException("Не задан API-ключ.")
        return key
    }

    private fun builder(url: String, key: String) = Request.Builder()
        .url(url)
        .header("x-api-key", key)
        .header("anthropic-version", VERSION)

    // Правило 6: причина доходит до владельца целой, без «что-то пошло не так».
    private fun fail(code: Int, body: String): Nothing {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
            .getOrDefault(body.take(300))
        throw BatchException("Anthropic ответил $code: $detail")
    }

    /** Создаёт батч из (custom_id, params); возвращает его id. */
    suspend fun create(requests: List<Pair<String, JSONObject>>): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        val body = JSONObject().put(
            "requests",
            JSONArray().apply { for ((id, p) in requests) put(JSONObject().put("custom_id", id).put("params", p)) },
        )
        client.newCall(
            builder(BATCHES, key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, text)
            JSONObject(text).getString("id")
        }
    }

    suspend fun status(batchId: String): Status = withContext(Dispatchers.IO) {
        val key = apiKey()
        client.newCall(builder("$BATCHES/$batchId", key).get().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, text)
            val o = JSONObject(text)
            val c = o.optJSONObject("request_counts") ?: JSONObject()
            Status(
                processing = o.optString("processing_status"),
                resultsUrl = o.optString("results_url").takeIf { it.isNotBlank() && it != "null" },
                succeeded = c.optInt("succeeded"),
                errored = c.optInt("errored"),
                inFlight = c.optInt("processing"),
                expired = c.optInt("expired"),
                canceled = c.optInt("canceled"),
            )
        }
    }

    /** JSONL результатов: строка на запрос, порядок произвольный — сопоставлять по custom_id. */
    suspend fun results(resultsUrl: String): List<Item> = withContext(Dispatchers.IO) {
        val key = apiKey()
        client.newCall(builder(resultsUrl, key).get().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, text)
            text.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { parseItem(JSONObject(line)) }.getOrNull() }
                .toList()
        }
    }

    /**
     * Один обычный запрос без потока — для ответа владельца в текстбоксе, где
     * ждать батч нельзя. Fable на high может думать минуты: таймаут чтения свой.
     */
    suspend fun single(params: JSONObject): Item = withContext(Dispatchers.IO) {
        val key = apiKey()
        val long = client.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
        long.newCall(
            builder(MESSAGES, key).post(params.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) fail(r.code, text)
            parseItem(JSONObject(text))
        }
    }
}
