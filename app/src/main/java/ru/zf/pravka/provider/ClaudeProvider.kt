package ru.zf.pravka.provider

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings

// Direct Anthropic Messages API client. The API key is entered by the owner
// in the app settings and lives only in on-device DataStore (agreed deviation
// from spec section 10 - no VPS proxy).
class ClaudeProvider(
    private val settings: Settings,
    private val promptStore: PromptStore,
    private val client: OkHttpClient,
) : ProofreadProvider {

    override val id = "claude"

    class ApiException(message: String, val retryable: Boolean = false) : Exception(message)

    private data class ApiReply(
        val text: String,
        val inputTokens: Int,       // uncached, billed at full price
        val cacheWriteTokens: Int,  // billed at 2x (1h TTL cache write)
        val cacheReadTokens: Int,   // billed at 0.1x
        val outputTokens: Int,
    )

    override suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                // Sonnet for every mode - the only model the owner uses.
                val model = Settings.MODEL_SONNET
                // Owner-edited override if present, factory text otherwise.
                val template = promptStore.effective(mode)
                val parts = Prompts.assemble(template, dictBlock)

                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(apiKey, model, parts, input)
                ProofreadResult(
                    text = reply.text,
                    providerId = id,
                    latencyMs = System.currentTimeMillis() - started,
                    changed = reply.text.trim() != input.trim(),
                    appliedDictEntries = emptyList(),
                    modelId = model,
                    inputTokens = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                    outputTokens = reply.outputTokens,
                    costUsd = costUsd(model, reply),
                    cacheWriteTokens = reply.cacheWriteTokens,
                    cacheReadTokens = reply.cacheReadTokens,
                )
            }
        }

    // USD per million tokens: input to output. Cache pricing derives from
    // the input price: 1h-TTL writes cost 2x, reads 0.1x.
    private val prices = mapOf(
        Settings.MODEL_SONNET to (3.0 to 15.0),
    )

    private fun costUsd(model: String, reply: ApiReply): Double {
        val (pIn, pOut) = prices[model] ?: return 0.0
        val inputCost =
            (reply.inputTokens + 2.0 * reply.cacheWriteTokens + 0.1 * reply.cacheReadTokens) /
                1_000_000.0 * pIn
        return inputCost + reply.outputTokens / 1_000_000.0 * pOut
    }

    private fun requestWithOneRetry(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
    ): ApiReply {
        // Spec 6.1: one retry on network error or timeout; none on 4xx.
        return try {
            request(apiKey, model, parts, input)
        } catch (e: IOException) {
            request(apiKey, model, parts, input)
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
    ): ApiReply {
        // Rough token estimate for Russian text (~2.5 chars/token) + 30% headroom.
        val estimatedInputTokens = input.length / 2 + 1
        val maxTokens = (estimatedInputTokens * 13 / 10 + 300).coerceIn(1024, 8192)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            // Proofreading is mechanical; thinking would only add latency and cost.
            // Sonnet 5 runs adaptive thinking by default when the field is omitted.
            put("thinking", JSONObject().put("type", "disabled"))
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                // Cache breakpoint sits on the stable template prefix
                                // ONLY - the dict block varies per request and would
                                // invalidate the cache on every dictation. 1h TTL:
                                // the owner's real gaps between fixes run up to ~30
                                // min, which the default 5m TTL would keep missing.
                                // Below Sonnet's 1024-token minimum it is a no-op.
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.stablePrefix)
                                        put(
                                            "cache_control",
                                            JSONObject().put("type", "ephemeral").put("ttl", "1h"),
                                        )
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.dictPart + input + parts.afterInput)
                                    }
                                )
                            }
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(humanReadableError(response.code, responseBody))
            }
            val json = JSONObject(responseBody)
            when (val stopReason = json.optString("stop_reason")) {
                "end_turn", "stop_sequence" -> Unit
                "max_tokens" -> throw ApiException("Ответ модели обрезан по длине. Попробуй ещё раз или сократи текст.")
                "refusal" -> throw ApiException("Модель отказалась обрабатывать этот текст.")
                else -> throw ApiException("Неожиданный ответ модели ($stopReason).")
            }
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            if (sb.isEmpty()) throw ApiException("Модель вернула пустой ответ.")
            val usage = json.optJSONObject("usage")
            return ApiReply(
                text = sb.toString(),
                inputTokens = usage?.optInt("input_tokens") ?: 0,
                cacheWriteTokens = usage?.optInt("cache_creation_input_tokens") ?: 0,
                cacheReadTokens = usage?.optInt("cache_read_input_tokens") ?: 0,
                outputTokens = usage?.optInt("output_tokens") ?: 0,
            )
        }
    }

    private fun humanReadableError(code: Int, body: String): String {
        val serverMessage = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull()
        return when (code) {
            401 -> "Неверный API-ключ. Проверь его в настройках Правки."
            403 -> "У ключа нет доступа к модели." + (serverMessage?.let { " ($it)" } ?: "")
            404 -> "Модель не найдена." + (serverMessage?.let { " ($it)" } ?: "")
            429 -> "Слишком много запросов, подожди немного."
            in 500..599 -> "Сервер Anthropic недоступен ($code), попробуй позже."
            else -> "Ошибка API $code" + (serverMessage?.let { ": $it" } ?: "")
        }
    }
}
