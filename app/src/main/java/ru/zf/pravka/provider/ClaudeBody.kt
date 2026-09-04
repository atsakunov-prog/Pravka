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

// Тело: роутер и разбор одной фразы (подходы, зарядка, еда, самочувствие, вопрос),
// правила блока из прозы Notion, тренер и вопрос по тренировкам.
// Расширения ClaudeProvider: транспорт там, разбор здесь.

/**
 * Разбирает одну фразу про тело: подходы, зарядку, еду, самочувствие или
 * вопрос. Роутер и разбор в ОДНОМ вызове — два подряд стоили бы вдвое и
 * тормозили вдвое, а классификация без разбора всё равно бесполезна.
 *
 * Справочники упражнений и рациона едут в КЭШИРУЕМОЙ части промпта: они
 * байт в байт одинаковы между запросами, поэтому платятся раз в час, а не
 * на каждую фразу. Именно из-за них распознавание становится выбором из
 * списка вместо угадывания.
 */
suspend fun ClaudeProvider.parseBody(
    text: String,
    dictBlock: String,
    exerciseBook: String,
    rationBook: String,
    planBlock: String,
    lastTimeBlock: String,
    /** Где сказано: «в карточке зарядки», «в карточке силовой» — смещает роутер. */
    whereSaid: String = "",
): Result<BodyParse> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        }
        require(text.isNotBlank()) { "Пустая фраза — разбирать нечего." }
        val template = promptStore.effective(PromptStore.PromptId.BODY)
        // Стабильная часть — до маркера {VARS}: инструкция и оба
        // справочника. Она и уходит под точку кэша.
        val split = template.indexOf(Prompts.PLACEHOLDER_VARS)
        // Словарь варьируется от фразы к фразе, поэтому в заводском шаблоне
        // он стоит НИЖЕ {VARS}. Точка кэша сидит на конце head, и любой
        // изменившийся байт до неё стоил бы полной перезаписи кэша — один
        // сработавший hint делал бы дороже все наговоры дня. В head словарь
        // подменяем прочерком на случай, если владелец в правленом шаблоне
        // оставил {DICT} над {VARS}: пусть кэш живёт, а словарь ему уедет
        // в хвосте.
        val head = (if (split >= 0) template.substring(0, split) else template)
            .replace("{EXERCISES}", exerciseBook.ifBlank { "Справочник упражнений не загружен." })
            .replace("{RATION}", rationBook.ifBlank { "Справочник рациона не загружен." })
            .replace(Prompts.PLACEHOLDER_DICT, "—")
        var tail = if (split >= 0) template.substring(split + Prompts.PLACEHOLDER_VARS.length) else ""
        tail = tail
            .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
            .replace("{NOW}", nowContext())
            .replace(
                "{WHERE}",
                if (whereSaid.isBlank()) ""
                else "Владелец написал это $whereSaid — скорее всего, речь про неё.",
            )
            .replace("{PLAN}", planBlock)
            .replace("{LAST}", lastTimeBlock)
        tail = if (tail.contains(Prompts.PLACEHOLDER_INPUT)) {
            tail.replace(Prompts.PLACEHOLDER_INPUT, text)
        } else {
            tail.trimEnd() + "\n\nСказано:\n" + text
        }
        val parts = Prompts.PromptParts(
            stablePrefix = head,
            dictPart = tail,
            afterInput = "",
            // Роутер «Т» ходит много раз в день с одними справочниками —
            // кэш окупается и на Опусе.
            cacheStableAlways = true,
        )
        val started = System.currentTimeMillis()
        val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", null)
        parseBodyReply(reply).copy(latencyMs = System.currentTimeMillis() - started)
    }
}

private fun ClaudeProvider.parseBodyReply(reply: ApiReply): BodyParse {
    val o = jsonObjectOf(
        reply.text,
        "Модель ответила не JSON. Сказанное сохранено — можно разобрать заново.",
    )
    val kind = o.optString("kind").trim().lowercase().ifBlank { "unknown" }

    val strength = o.optJSONObject("strength")?.let { s ->
        val exercises = mutableListOf<ExerciseParse>()
        val array = s.optJSONArray("exercises") ?: JSONArray()
        for (i in 0 until array.length()) {
            val e = array.optJSONObject(i) ?: continue
            val name = e.optString("name").trim()
            if (name.isEmpty()) continue
            val rows = mutableListOf<SetParse>()
            val sets = e.optJSONArray("sets") ?: JSONArray()
            for (j in 0 until sets.length()) {
                val r = sets.optJSONObject(j) ?: continue
                val amount = r.optInt("amount", 0)
                if (amount <= 0) continue
                rows.add(
                    SetParse(
                        amount = amount.coerceAtMost(3000),
                        weightKg = r.optDouble("weight", 0.0).coerceIn(0.0, 300.0),
                        note = r.optString("note").trim().take(120),
                    )
                )
            }
            if (rows.isEmpty()) continue
            exercises.add(
                ExerciseParse(
                    name = name.take(120),
                    sets = rows,
                    note = e.optString("note").trim().take(200),
                )
            )
        }
        StrengthParse(
            mode = if (s.optString("mode").trim().lowercase() == "add") "add" else "replace",
            exercises = exercises,
            feel = s.optInt("feel", 0).coerceIn(0, 5),
            rpe = s.optInt("rpe", 0).coerceIn(0, 10),
            minutes = s.optInt("minutes", 0).coerceIn(0, 600),
        )
    }

    val gtg = o.optJSONObject("gtg")?.let { g ->
        GtgParse(
            charged = g.optBoolean("charged", false),
            hangSec = g.optInt("hang", 0).coerceIn(0, 1200),
            negatives = g.optInt("negatives", 0).coerceIn(0, 100),
            scapular = g.optInt("scapular", 0).coerceIn(0, 100),
            pullups = g.optInt("pullups", 0).coerceIn(0, 100),
        )
    }

    val food = o.optJSONObject("food")?.let { f ->
        val items = mutableListOf<ru.zf.pravka.core.MealItem>()
        val array = f.optJSONArray("items") ?: JSONArray()
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
            items.add(if (item.kcal == 0) item.copy(kcal = item.kcalFromMacros()) else item)
        }
        val clock = f.optString("time").trim()
        FoodParse(
            kind = f.optString("kind").trim().lowercase().take(20),
            timeOfDay = if (clockOnly.matches(clock)) clock else "",
            items = items,
            note = f.optString("note").trim(),
            costUsd = 0.0,
            tokensIn = 0,
            tokensOut = 0,
            model = Settings.MODEL_OPUS,
            latencyMs = 0L,
        )
    }

    val feel = o.optJSONObject("feel")?.let { f ->
        FeelParse(
            feel = f.optInt("feel", 0).coerceIn(0, 5),
            knee = f.optString("knee").trim().lowercase().take(16),
            note = f.optString("note").trim().take(300),
        )
    }

    return BodyParse(
        kind = kind,
        strength = strength?.takeIf { it.exercises.isNotEmpty() || it.feel > 0 || it.rpe > 0 },
        gtg = gtg?.takeIf { it.charged || it.hangSec > 0 || it.negatives > 0 || it.scapular > 0 },
        food = food?.takeIf { it.items.isNotEmpty() },
        feel = feel?.takeIf { it.feel > 0 || it.knee.isNotBlank() },
        question = o.optString("question").trim(),
        note = o.optString("note").trim(),
        costUsd = costUsd(Settings.MODEL_OPUS, reply),
        tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
        tokensOut = reply.outputTokens,
        model = Settings.MODEL_OPUS,
        latencyMs = 0L,
    )
}

/**
 * Вынимает числа из страницы блока в Notion: потолок пульса, каденс, серую
 * зону, лимит пробежек. Раз в сутки — страница меняется раз в месяц.
 */
suspend fun ClaudeProvider.extractRules(pageText: String): Result<RulesParse> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
        require(pageText.isNotBlank()) { "Страница блока пуста." }
        val template = promptStore.effective(PromptStore.PromptId.RULES)
        // Стена текста режется: страница блока это две-три тысячи знаков, а
        // не роман, и платить за случайно вставленный роман незачем.
        val body = pageText.take(20_000)
        val prompt = if (template.contains(Prompts.PLACEHOLDER_INPUT)) {
            template.replace(Prompts.PLACEHOLDER_INPUT, body)
        } else {
            template.trimEnd() + "\n\nСтраница блока:\n" + body
        }
        val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        val reply = requestWithOneRetry(apiKey, Settings.MODEL_SONNET, parts, "", null)
        val o = jsonObjectOf(reply.text, "Правила блока не разобрались — модель ответила не JSON.")
        val week = mutableListOf<Pair<String, String>>()
        o.optJSONArray("week")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.optJSONObject(i) ?: continue
                val day = w.optString("day").trim()
                val session = w.optString("session").trim()
                if (day.isNotBlank() && session.isNotBlank()) week.add(day to session)
            }
        }
        val extra = mutableListOf<String>()
        o.optJSONArray("extra")?.let { a ->
            for (i in 0 until a.length()) {
                a.optString(i).trim().takeIf { it.isNotBlank() }?.let { extra.add(it.take(200)) }
            }
        }
        RulesParse(
            hrCeiling = o.optInt("hrCeiling", 0).coerceIn(0, 220),
            greyLow = o.optInt("greyLow", 0).coerceIn(0, 220),
            greyHigh = o.optInt("greyHigh", 0).coerceIn(0, 220),
            cadence = o.optInt("cadence", 0).coerceIn(0, 240),
            runsMax = o.optInt("runsMax", 0).coerceIn(0, 14),
            hoursBetween = o.optInt("hoursBetween", 0).coerceIn(0, 168),
            rampNeedsPositiveTsb = o.optBoolean("rampNeedsPositiveTsb", false),
            testPrep = o.optString("testPrep").trim().take(300),
            cancel = o.optString("cancel").trim().take(300),
            kneeGreen = o.optString("kneeGreen").trim().take(300),
            kneeYellow = o.optString("kneeYellow").trim().take(300),
            kneeRed = o.optString("kneeRed").trim().take(300),
            week = week,
            extra = extra.take(8),
            costUsd = costUsd(Settings.MODEL_SONNET, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/**
 * Отвечает на вопрос о тренировках. Опус: считать тут нечего - все цифры
 * уже в [contextBlock], - а вот сказать «сегодня не грузись, и вот почему»
 * это суждение, и оно стоит своих денег.
 *
 * Ответ стримится в [onDelta], чтобы первые слова появлялись на экране
 * сразу, как в Правке.
 */
suspend fun ClaudeProvider.coach(
    question: String,
    contextBlock: String,
    onDelta: ((String) -> Unit)? = null,
): Result<CoachAnswer> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        }
        val template = promptStore.effective(PromptStore.PromptId.COACH)
        var prompt = template
            .replace("{CONTEXT}", contextBlock.ifBlank { "Данных нет — выгрузка не удалась." })
            .replace("{TODAY}", todayContext())
        val asked = question.trim().ifBlank { "Как у меня дела?" }
        prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
            prompt.replace(Prompts.PLACEHOLDER_INPUT, asked)
        } else {
            prompt.trimEnd() + "\n\nВопрос:\n" + asked
        }
        val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        val started = System.currentTimeMillis()
        val reply = requestWithOneRetry(apiKey, Settings.MODEL_OPUS, parts, "", onDelta)
        CoachAnswer(
            text = reply.text.trim(),
            costUsd = costUsd(Settings.MODEL_OPUS, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
            model = Settings.MODEL_OPUS,
            latencyMs = System.currentTimeMillis() - started,
        )
    }
}

/**
 * Тренер-консультант: короткий вопрос про упражнение. Сонет, а не Опус:
 * техника отвечается карточкой движения и правилами недели, полная
 * телеметрия тут лишняя — и по деньгам, и по скорости между подходами.
 */
suspend fun ClaudeProvider.trainer(
    question: String,
    focusBlock: String,
    weekBlock: String,
    onDelta: ((String) -> Unit)? = null,
): Result<CoachAnswer> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        }
        val template = promptStore.effective(PromptStore.PromptId.TRAINER)
        var prompt = template
            .replace("{FOCUS}", focusBlock.ifBlank { "Упражнение не из справочника." })
            .replace("{WEEK}", weekBlock.ifBlank { "Правил недели в кэше нет." })
        val asked = question.trim().ifBlank { "Как правильно делать это упражнение?" }
        prompt = if (prompt.contains(Prompts.PLACEHOLDER_INPUT)) {
            prompt.replace(Prompts.PLACEHOLDER_INPUT, asked)
        } else {
            prompt.trimEnd() + "\n\nВопрос:\n" + asked
        }
        val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        val started = System.currentTimeMillis()
        val reply = requestWithOneRetry(apiKey, Settings.MODEL_SONNET, parts, "", onDelta)
        CoachAnswer(
            text = reply.text.trim(),
            costUsd = costUsd(Settings.MODEL_SONNET, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
            model = Settings.MODEL_SONNET,
            latencyMs = System.currentTimeMillis() - started,
        )
    }
}
