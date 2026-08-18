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
        onDelta: ((String) -> Unit)?,
        directive: String,
        contextBefore: String,
        modelOverride: String?,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                // Sonnet by default; redo chips pass Opus for extra quality.
                val model = modelOverride ?: Settings.MODEL_SONNET
                // ONE master template (CLEAN) for every mode; BUSINESS/SOFTEN
                // are style directives riding in the uncached slot, so all
                // modes share the same cached prefix.
                val template = promptStore.effective(ProofreadMode.CLEAN)
                val styleDirective = if (mode == ProofreadMode.CLEAN) "" else promptStore.effective(mode)
                val fullDirective = listOf(styleDirective, directive)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                val parts = Prompts.assemble(template, dictBlock, fullDirective, contextBefore)

                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(apiKey, model, parts, input, onDelta)
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

    /**
     * Free-form assist task (summarize / reply / translate): [instruction]
     * plus [content] in tags, NO CLEAN template. Returns a ProofreadResult so
     * the history journal and cost accounting reuse the same shape.
     */
    suspend fun assist(
        instruction: String,
        content: String,
        onDelta: ((String) -> Unit)? = null,
    ): Result<ProofreadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settings.apiKey()
                if (apiKey.isBlank()) {
                    throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
                }
                val model = Settings.MODEL_SONNET
                val prompt = instruction.trim() + "\n\n<текст>\n" + content + "\n</текст>"
                val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
                val started = System.currentTimeMillis()
                val reply = requestWithOneRetry(apiKey, model, parts, "", onDelta)
                ProofreadResult(
                    text = reply.text,
                    providerId = id,
                    latencyMs = System.currentTimeMillis() - started,
                    changed = true,
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
        Settings.MODEL_OPUS to (5.0 to 25.0),
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
        onDelta: ((String) -> Unit)?,
    ): ApiReply {
        // Spec 6.1: one retry on network error or timeout; none on client 4xx.
        // Transient server blips (429/500/529 "overloaded") last seconds - one
        // short-backoff retry turns them from a user-visible failure into
        // nothing. Previously only IOException retried and these failed hard.
        return try {
            request(apiKey, model, parts, input, onDelta)
        } catch (e: IOException) {
            request(apiKey, model, parts, input, onDelta)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(1500)
            request(apiKey, model, parts, input, onDelta)
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        parts: Prompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
    ): ApiReply {
        // Rough token estimate for Russian text (~2.5 chars/token) + 30% headroom.
        val estimatedInputTokens = input.length / 2 + 1
        // Opus (redo chips) thinks adaptively by default, and thinking tokens
        // count toward max_tokens: without headroom a 350-char redo burned the
        // whole budget on thinking and died with stop_reason=max_tokens before
        // emitting a single word (owner saw an endless spinner, 2026-08-18).
        val thinkingHeadroom = if (model == Settings.MODEL_SONNET) 0 else 8000
        val maxTokens = (estimatedInputTokens * 13 / 10 + 300 + thinkingHeadroom)
            .coerceIn(1024, 16384)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            // SSE streaming: first corrected words reach the ticker in well under
            // a second, and the 90s readTimeout becomes a per-chunk timeout
            // instead of a hard ceiling on total generation time.
            put("stream", true)
            // Proofreading is mechanical; thinking would only add latency and
            // cost, so it is disabled on Sonnet. On Opus (redo chips) the
            // parameter is omitted: adaptive thinking is its default, and an
            // explicit "disabled" there has documented failure modes.
            if (model == Settings.MODEL_SONNET) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
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
                                // Assist tasks have no stable prefix - an empty text
                                // block would be rejected by the API, so skip it.
                                if (parts.stablePrefix.isNotBlank()) put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.stablePrefix)
                                        // Cache only the everyday Sonnet path: a
                                        // rare Opus redo would pay the 2x cache
                                        // write and likely never read it back.
                                        if (model == Settings.MODEL_SONNET) {
                                            put(
                                                "cache_control",
                                                JSONObject().put("type", "ephemeral").put("ttl", "1h"),
                                            )
                                        }
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
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val transient = response.code == 429 || response.code in 500..599
                throw ApiException(humanReadableError(response.code, responseBody), retryable = transient)
            }
            val source = response.body?.source() ?: throw ApiException("Пустой ответ API.")

            // SSE: "event: ..." / "data: {json}" lines. Every data payload
            // carries its own "type", so the event: lines can be ignored.
            val sb = StringBuilder()
            var stopReason = ""
            var inputTokens = 0
            var cacheWrite = 0
            var cacheRead = 0
            var outputTokens = 0
            var lastEmit = 0L
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val event = runCatching { JSONObject(line.substring(6)) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "message_start" -> {
                        val usage = event.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = usage?.optInt("input_tokens") ?: 0
                        cacheWrite = usage?.optInt("cache_creation_input_tokens") ?: 0
                        cacheRead = usage?.optInt("cache_read_input_tokens") ?: 0
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta?.optString("type") == "text_delta") {
                            sb.append(delta.optString("text"))
                            // ~10 updates/sec is plenty for the ticker; emitting
                            // per delta would copy the growing reply hundreds of
                            // times.
                            if (onDelta != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 100) {
                                    lastEmit = now
                                    onDelta(sb.toString())
                                }
                            }
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("delta")?.optString("stop_reason")
                            ?.takeIf { it.isNotEmpty() }?.let { stopReason = it }
                        event.optJSONObject("usage")?.let { outputTokens = it.optInt("output_tokens", outputTokens) }
                    }
                    "error" -> {
                        val err = event.optJSONObject("error")
                        val overloaded = err?.optString("type") == "overloaded_error"
                        throw ApiException(
                            "Anthropic: ${err?.optString("message") ?: "ошибка стрима"}",
                            retryable = overloaded,
                        )
                    }
                    // ping / content_block_start / content_block_stop / message_stop
                }
            }
            when (stopReason) {
                "end_turn", "stop_sequence" -> Unit
                "max_tokens" -> throw ApiException("Ответ модели обрезан по длине. Попробуй ещё раз или сократи текст.")
                "refusal" -> throw ApiException("Модель отказалась обрабатывать этот текст.")
                else -> throw ApiException("Неожиданный ответ модели ($stopReason).")
            }
            if (sb.isEmpty()) throw ApiException("Модель вернула пустой ответ.")
            onDelta?.invoke(sb.toString())  // final full text, past the throttle
            return ApiReply(
                text = sb.toString(),
                inputTokens = inputTokens,
                cacheWriteTokens = cacheWrite,
                cacheReadTokens = cacheRead,
                outputTokens = outputTokens,
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
