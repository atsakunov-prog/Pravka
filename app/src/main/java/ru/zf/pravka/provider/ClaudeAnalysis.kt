package ru.zf.pravka.provider

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ParsedTask
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings

import ru.zf.pravka.provider.ClaudeProvider.ApiException
import ru.zf.pravka.provider.ClaudeProvider.ApiReply
import ru.zf.pravka.provider.ClaudeProvider.ImagePart
import ru.zf.pravka.provider.ClaudeProvider.LearnProposals
import ru.zf.pravka.provider.ClaudeProvider.DictProposal
import ru.zf.pravka.provider.ClaudeProvider.RuleProposal
import ru.zf.pravka.provider.ClaudeProvider.OptimizedRules
import ru.zf.pravka.provider.ClaudeProvider.ZasechkaParse
import ru.zf.pravka.provider.ClaudeProvider.SplitResult
import ru.zf.pravka.provider.ClaudeProvider.FoodParse
import ru.zf.pravka.provider.ClaudeProvider.BodyParse
import ru.zf.pravka.provider.ClaudeProvider.SetParse
import ru.zf.pravka.provider.ClaudeProvider.ExerciseParse
import ru.zf.pravka.provider.ClaudeProvider.StrengthParse
import ru.zf.pravka.provider.ClaudeProvider.GtgParse
import ru.zf.pravka.provider.ClaudeProvider.FeelParse
import ru.zf.pravka.provider.ClaudeProvider.RulesParse
import ru.zf.pravka.provider.ClaudeProvider.CoachAnswer
import ru.zf.pravka.provider.ClaudeProvider.BatchAnswer

// Разборы: запрос сейчас и Batches API (половина цены за ночной разбор).
// Расширения ClaudeProvider: транспорт там, батч-дорога здесь.

/**
 * Разбор ПРЯМО СЕЙЧАС, обычным запросом. Владелец: «если сделать разбор
 * сейчас — он не батчем должен уходить, а то я сижу жду». Батч дешевле
 * вдвое, но отвечает когда захочет; ручная кнопка платит полную цену за
 * ответ через минуту. Запрос идёт потоком, поэтому 90-секундный
 * readTimeout становится таймаутом между кусками, а не потолком на всю
 * генерацию.
 */
suspend fun ClaudeProvider.analyzeNow(
    system: String,
    user: String,
    model: String,
    maxTokens: Int,
    effort: String = "high",
): Result<BatchAnswer> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
        val parts = Prompts.PromptParts(
            stablePrefix = system.trim() + "\n\n",
            dictPart = user,
            afterInput = "",
            // Свод правил и профиль владельца стабильны байт-в-байт —
            // ночной батч и ручной запуск греют один и тот же кэш.
            cacheStableAlways = true,
        )
        // Бюджет и глубину задаём явно теми же числами, что у батча:
        // иначе ручной разбор и ночной отвечали бы по-разному, и сравнить
        // их было бы нельзя.
        val reply = requestWithOneRetry(
            apiKey, model, parts, "", null,
            maxTokensOverride = maxTokens,
            effortOverride = effort,
            tolerateTruncation = true,
        )
        BatchAnswer(
            text = reply.text.trim(),
            costUsd = costUsd(model, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/** Заявка в батч. Возвращает id, по которому потом забирается ответ. */
suspend fun ClaudeProvider.submitBatch(
    system: String,
    user: String,
    model: String,
    maxTokens: Int,
    effort: String = "high",
): Result<String> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
        val params = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("output_config", JSONObject().put("effort", effort))
            put("system", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", system)
            }))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", user)
                }))
            }))
        }
        val body = JSONObject().put(
            "requests",
            JSONArray().put(JSONObject().apply {
                put("custom_id", "analysis")
                put("params", params)
            }),
        )
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages/batches")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException("Батч не принят: HTTP ${response.code} ${text.take(200)}")
            }
            JSONObject(text).optString("id").ifBlank {
                throw ApiException("Батч принят, но без id")
            }
        }
    }
}

/**
 * Ответ батча, если он готов. null — ещё считается: это НЕ ошибка, опрос
 * просто повторится на следующем тике службы.
 */
suspend fun ClaudeProvider.batchAnswer(batchId: String, model: String): Result<BatchAnswer?> =
    withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
            val head = Request.Builder()
                .url("https://api.anthropic.com/v1/messages/batches/" + batchId)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()
            val status = client.newCall(head).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException("Батч не читается: HTTP ${response.code}")
                }
                JSONObject(text)
            }
            if (status.optString("processing_status") != "ended") return@runCatchingApi null
            val resultsUrl = status.optString("results_url").ifBlank {
                "https://api.anthropic.com/v1/messages/batches/" + batchId + "/results"
            }
            val results = Request.Builder()
                .url(resultsUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()
            client.newCall(results).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException("Результат не забрался: HTTP ${response.code}")
                }
                // Результаты приезжают построчным JSON (JSONL) и в любом
                // порядке — у нас в батче одна заявка, берём первую строку
                // с нашим custom_id.
                val line = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("{") }
                    .firstOrNull { it.contains("\"analysis\"") }
                    ?: throw ApiException("Батч закончился, но результата нет")
                val o = JSONObject(line)
                val result = o.optJSONObject("result") ?: JSONObject()
                when (result.optString("type")) {
                    "succeeded" -> {
                        val message = result.optJSONObject("message") ?: JSONObject()
                        val content = message.optJSONArray("content") ?: JSONArray()
                        val out = StringBuilder()
                        for (i in 0 until content.length()) {
                            val block = content.optJSONObject(i) ?: continue
                            if (block.optString("type") == "text") out.append(block.optString("text"))
                        }
                        val usage = message.optJSONObject("usage") ?: JSONObject()
                        val tin = usage.optInt("input_tokens") +
                            usage.optInt("cache_creation_input_tokens") +
                            usage.optInt("cache_read_input_tokens")
                        val tout = usage.optInt("output_tokens")
                        BatchAnswer(
                            text = out.toString().trim(),
                            // Батч стоит половину обычного вызова.
                            costUsd = Pricing.costUsd(
                                model,
                                inputTokens = usage.optInt("input_tokens"),
                                outputTokens = tout,
                                cacheWriteTokens = usage.optInt("cache_creation_input_tokens"),
                                cacheReadTokens = usage.optInt("cache_read_input_tokens"),
                            ) / 2.0,
                            tokensIn = tin,
                            tokensOut = tout,
                        )
                    }
                    "errored" -> BatchAnswer(
                        "", 0.0, 0, 0,
                        error = result.optJSONObject("error")?.optString("message")
                            .orEmpty().ifBlank { "модель вернула ошибку" },
                    )
                    "expired" -> BatchAnswer("", 0.0, 0, 0, error = "батч просрочен, нужен новый")
                    else -> BatchAnswer("", 0.0, 0, 0, error = "батч отменён")
                }
            }
        }
    }
