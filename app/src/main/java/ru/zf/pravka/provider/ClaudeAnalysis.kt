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
import ru.zf.pravka.provider.ClaudeProvider.CoachAnswer
import ru.zf.pravka.provider.ClaudeProvider.BatchAnswer

// Разборы: запрос сейчас обычным вызовом. Батч-дорога этого файла
// (submitBatch/batchAnswer) жила для сверки дублей паттернов при синке Notion
// и снята вместе с ним 12.10.2026; ночной разбор Правки ходит своими батчами
// (ClaudeBatches.kt).
// Расширения ClaudeProvider: транспорт там, разбор здесь.

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
    /** Усилие output_config.effort; пусто — не передавать, решает API. */
    effort: String = "",
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
