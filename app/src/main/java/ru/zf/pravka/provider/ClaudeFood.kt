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
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore

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

// Еда: сказанное (и снятое) -> КБЖУ. Расширения ClaudeProvider: транспорт там, разбор здесь.

/**
 * Разбирает один приём пищи. Модель — дорога «Тело и еда» (заводская Опус,
 * меняется в настройках): трудное - узнать блюдо и прикинуть порцию, а не
 * рассудить; таблицы КБЖУ модель знает.
 *
 * [image] - снимок тарелки: тогда к промпту добавляется указание мерить
 * порции по посуде в кадре и читать этикетку, если она попала в кадр.
 * Текст при этом может быть пустым - фото само себе описание.
 */
suspend fun ClaudeProvider.parseFood(
    text: String,
    dictBlock: String,
    profileLine: String,
    image: ImagePart? = null,
    rationBlock: String = "—",
    recentBlock: String = "—",
): Result<FoodParse> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        }
        require(text.isNotBlank() || image != null) { "Нечего разбирать: ни слов, ни снимка." }
        val template = promptStore.effective(PromptStore.PromptId.FOOD)
        var prompt = template
            .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
            .replace("{NOW}", nowContext())
            .replace("{PROFILE}", profileLine.ifBlank { "вес и пороги неизвестны" })
            // Рацион статичен (assets) — уезжает в кэшируемую голову;
            // история приёмов живая — в переменном хвосте после словаря.
            .replace("{RATION}", rationBlock.ifBlank { "—" })
            .replace("{RECENT}", recentBlock.ifBlank { "—" })
        if (image != null) prompt = prompt.trimEnd() + "\n\n" + Prompts.FOOD_PHOTO_HINT
        val said = text.ifBlank { "(без слов — только снимок)" }
        prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
            prompt.replace(Prompts.PLACEHOLDER_INPUT, said)
        } else {
            prompt.trimEnd() + "\n\nСъедено:\n" + said
        }
        // Правила еды стабильны — под кэш; словарь, время и вход в хвосте.
        // Сплит по маркеру словаря: правил его шаблон может и не иметь
        // (правится в «Промптах») — тогда весь текст уезжает хвостом.
        // Со снимком кэш не включаем: блок изображения стоит ПЕРЕД
        // текстом и ломает префикс — платили бы за запись впустую.
        val cut = prompt.indexOf("Словарь владельца")
        val parts = if (cut > 0) {
            Prompts.PromptParts(
                stablePrefix = prompt.substring(0, cut),
                dictPart = prompt.substring(cut),
                afterInput = "",
                cacheStableAlways = image == null,
            )
        } else {
            Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        }
        val started = System.currentTimeMillis()
        val choice = settings.modelChoice(ModelRoute.BODY)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            images = listOfNotNull(image),
            effortOverride = choice.effort,
        )
        val parsed = parseFoodReply(reply.text)
        FoodParse(
            kind = parsed.kind,
            timeOfDay = parsed.timeOfDay,
            items = parsed.items,
            note = parsed.note,
            costUsd = costUsd(choice.model, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
            model = choice.model,
            latencyMs = System.currentTimeMillis() - started,
        )
    }
}

private class FoodReply(
    val kind: String,
    val timeOfDay: String,
    val items: List<ru.zf.pravka.core.MealItem>,
    val note: String,
)

private fun ClaudeProvider.parseFoodReply(raw: String): FoodReply {
    val o = jsonObjectOf(raw, "Модель ответила не JSON. Скажи ещё раз или поправь руками.")
    val out = mutableListOf<ru.zf.pravka.core.MealItem>()
    val array = o.optJSONArray("items") ?: JSONArray()
    for (i in 0 until array.length()) {
        val t = array.optJSONObject(i) ?: continue
        val name = t.optString("name").trim()
        if (name.isEmpty()) continue
        val item = ru.zf.pravka.core.MealItem(
            name = name.take(120),
            grams = t.optInt("grams", 0).coerceIn(0, 5000),
            kcal = t.optInt("kcal", 0).coerceIn(0, 10_000),
            protein = t.optInt("protein", 0).coerceIn(0, 500),
            fat = t.optInt("fat", 0).coerceIn(0, 500),
            carbs = t.optInt("carbs", 0).coerceIn(0, 1000),
            fiber = t.optInt("fiber", 0).coerceIn(0, 200),
            sureness = t.optString("sure").trim().take(12),
        )
        // Калорий модель не дала, а макросы дала - считаем сами, чтобы
        // позиция не пришла в дневник нулевой.
        out.add(if (item.kcal == 0) item.copy(kcal = item.kcalFromMacros()) else item)
    }
    val time = o.optString("time").trim()
    return FoodReply(
        kind = o.optString("kind").trim().lowercase().take(20),
        timeOfDay = if (clockOnly.matches(time)) time else "",
        items = out,
        note = o.optString("note").trim(),
    )
}
