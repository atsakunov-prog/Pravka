package ru.zf.pravka.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyVoice
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.provider.ClaudeProvider.ApiException
import ru.zf.pravka.provider.ClaudeProvider.MoneyHints
import ru.zf.pravka.provider.ClaudeProvider.MoneyAnswer
import ru.zf.pravka.provider.ClaudeProvider.MoneyParse
import ru.zf.pravka.provider.ClaudeProvider.MoneyText

// Деньги: наговор → траты и подсказки сверки. Расширения ClaudeProvider:
// транспорт там, разбор здесь. Заводская модель — Опус 5.5 (голос — high,
// сверка — xhigh), меняется в настройках → «Модели».

/** Каталог категорий строками «ключ — название — группа»: стабильная голова обоих промптов. */
internal fun moneyCatalogBlock(): String =
    MoneyCategories.ALL.joinToString("\n") { "${it.key} — ${it.title} — ${it.group}" }

/**
 * Собрать шаблон с кэшем на голове: всё до {TODAY} (или до {INPUT}, если
 * {TODAY} нет) стабильно между запросами — каталог, справочник, правила;
 * хвост — дата, словарь, наговор. Тот же приём, что у Разноски.
 */
private fun ClaudeProvider.moneyParts(template: String, cutAt: String, fill: (String) -> String, input: String): Prompts.PromptParts {
    val cut = template.indexOf(cutAt).takeIf { it > 0 } ?: template.indexOf(Prompts.PLACEHOLDER_INPUT)
    val headTemplate = if (cut > 0) template.substring(0, cut) else ""
    val head = fill(headTemplate)
    var tail = fill(if (cut > 0) template.substring(cut) else template)
    tail = if (tail.contains(Prompts.PLACEHOLDER_INPUT)) tail.replace(Prompts.PLACEHOLDER_INPUT, input)
    // Владелец переписал промпт и потерял {INPUT}: дописываем, а не теряем.
    else tail.trimEnd() + "\n\n" + input
    return Prompts.PromptParts(
        stablePrefix = head,
        dictPart = tail,
        afterInput = "",
        cacheStableAlways = head.isNotBlank() && !headTemplate.contains(Prompts.PLACEHOLDER_DICT),
    )
}

/**
 * Наговор → траты. [payeesBlock] — справочник владельца текстом (с телефона,
 * не из кода): «Иван П. = Помощь по дому · дети» помогает модели понять
 * «перевёл Ивану».
 */
suspend fun ClaudeProvider.parseMoney(
    transcript: String,
    dictBlock: String,
    payeesBlock: String,
): Result<MoneyParse> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        require(transcript.isNotBlank()) { "Пустой наговор — разбирать нечего." }
        val template = promptStore.effective(PromptStore.PromptId.MONEY)
        val parts = moneyParts(template, "{TODAY}", { s ->
            s.replace("{CATEGORIES}", moneyCatalogBlock())
                .replace("{PAYEES}", payeesBlock.ifBlank { "— пока пусто" })
                .replace("{TODAY}", todayContext())
                .replace(Prompts.PLACEHOLDER_DICT, dictBlock.ifBlank { "—" })
        }, transcript)
        val choice = settings.modelChoice(ModelRoute.MONEY)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
            routeKey = ModelRoute.MONEY.key,
        )
        val o = jsonObjectOf(reply.text, "Модель ответила не JSON. Наговор сохранён — разбери ещё раз.")
        val items = mutableListOf<MoneyVoice.Item>()
        val array = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            // Сумма — строкой: число из JSON через Double потеряло бы копейки.
            val minor = MoneyFormat.parseKop(t.optString("amount").replace(" ", ""))?.let { kotlin.math.abs(it) } ?: 0L
            val what = t.optString("what").trim()
            if (what.isEmpty() && minor == 0L) continue
            items.add(
                MoneyVoice.Item(
                    what = what.ifEmpty { "трата" },
                    minor = minor,
                    currency = t.optString("currency").trim().ifEmpty { "RUB" },
                    heard = t.optString("heard").trim(),
                    income = t.optString("direction").trim() == "in",
                    category = t.optString("category").trim(),
                    who = t.optString("who").trim(),
                    cash = t.optBoolean("cash", false),
                    date = t.optString("date").trim().takeIf { isoDate.matches(it) }.orEmpty(),
                    // Сумма не распозналась совсем — это тоже сомнение, и главное.
                    doubt = if (minor == 0L) "сумма не распозналась — впиши руками" else t.optString("doubt").trim(),
                )
            )
        }
        MoneyParse(
            items, o.optString("notes").trim(), costUsd(choice.model, reply), choice.model,
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/**
 * Неузнанные получатели → догадки и вопросы. [groupsBlock] — по строке на
 * группу: «ключ | название | раз | сумма | сообщение | MCC | категория банка».
 */
suspend fun ClaudeProvider.hintPayees(
    groupsBlock: String,
    payeesBlock: String,
): Result<MoneyHints> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        require(groupsBlock.isNotBlank()) { "Спрашивать не о чем — все получатели узнаны." }
        val template = promptStore.effective(PromptStore.PromptId.MONEY_MATCH)
        val parts = moneyParts(template, Prompts.PLACEHOLDER_INPUT, { s ->
            s.replace("{CATEGORIES}", moneyCatalogBlock())
                .replace("{PAYEES}", payeesBlock.ifBlank { "— пока пусто" })
        }, groupsBlock)
        val choice = settings.modelChoice(ModelRoute.MONEY_MATCH)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
            routeKey = ModelRoute.MONEY_MATCH.key,
        )
        val o = jsonObjectOf(reply.text, "Модель ответила не JSON — попробуй ещё раз.")
        val out = mutableListOf<MoneyHints.Hint>()
        val array = o.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until array.length()) {
            val g = array.optJSONObject(i) ?: continue
            val key = g.optString("key").trim()
            if (key.isEmpty()) continue
            out.add(
                MoneyHints.Hint(
                    key = key,
                    category = MoneyCategories.of(g.optString("category").trim())?.key.orEmpty(),
                    who = g.optString("who").trim().takeIf { w -> MoneyCategories.WHO.any { it.first == w } }.orEmpty(),
                    sure = g.optBoolean("sure", false),
                    question = g.optString("question").trim(),
                )
            )
        }
        MoneyHints(
            out, costUsd(choice.model, reply), choice.model,
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/** Текстовый ответ по выжимке журнала: вопрос владельца или паттерны. */
private suspend fun ClaudeProvider.moneyText(
    promptId: PromptStore.PromptId,
    route: ModelRoute,
    data: String,
    input: String,
): Result<MoneyText> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        val template = promptStore.effective(promptId)
        // Выжимка журнала — в голове под кэшем: второй вопрос подряд её не оплачивает заново.
        val parts = moneyParts(template, "{TODAY}", { s ->
            s.replace("{CATEGORIES}", moneyCatalogBlock())
                .replace("{DATA}", data)
                .replace("{TODAY}", todayContext())
        }, input)
        val choice = settings.modelChoice(route)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
            routeKey = route.key,
        )
        MoneyText(
            reply.text.trim(), costUsd(choice.model, reply), choice.model,
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/** «Сколько я трачу на кофе?» — ответ по данным журнала. */
suspend fun ClaudeProvider.askMoney(question: String, data: String): Result<MoneyText> {
    if (question.isBlank()) return Result.failure(IllegalArgumentException("Пустой вопрос"))
    return moneyText(PromptStore.PromptId.MONEY_ASK, ModelRoute.MONEY_ASK, data, question.trim())
}

/** Паттерны за год: структура, ритм, тренды, утечки, советы. */
suspend fun ClaudeProvider.moneyPatterns(data: String): Result<MoneyText> =
    moneyText(PromptStore.PromptId.MONEY_PATTERNS, ModelRoute.MONEY_PATTERNS, data, "")

/**
 * Голосовой ответ на карточку вопроса → категория, «для кого», запомнить ли.
 * Дорога — `MONEY` (та же, что траты голосом): это тот же навык — понять
 * сказанное владельцем про деньги.
 */
suspend fun ClaudeProvider.interpretMoneyAnswer(card: String, spoken: String, payeesBlock: String): Result<MoneyAnswer> =
    withContext(Dispatchers.IO) {
        runCatchingApi {
            val apiKey = settings.apiKey()
            if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
            require(spoken.isNotBlank()) { "Ответ пустой — скажи ещё раз." }
            val template = promptStore.effective(PromptStore.PromptId.MONEY_ANSWER)
            val parts = moneyParts(template, Prompts.PLACEHOLDER_INPUT, { s ->
                s.replace("{CATEGORIES}", moneyCatalogBlock())
                    .replace("{PAYEES}", payeesBlock.ifBlank { "— пока пусто" })
            }, "КАРТОЧКА\n$card\nОТВЕТ ВЛАДЕЛЬЦА\n$spoken")
            val choice = settings.modelChoice(ModelRoute.MONEY)
            val reply = requestWithOneRetry(
                apiKey, choice.model, parts, "", null,
                effortOverride = choice.effort,
                routeKey = ModelRoute.MONEY.key,
            )
            val o = jsonObjectOf(reply.text, "Модель ответила не JSON — скажи ещё раз.")
            val cat = MoneyCategories.of(o.optString("category").trim())?.key
                ?: MoneyCategories.find(o.optString("category"))?.key.orEmpty()
            MoneyAnswer(
                category = cat,
                who = o.optString("who").trim().takeIf { w -> MoneyCategories.WHO.any { it.first == w } }.orEmpty(),
                remember = o.optBoolean("remember", true),
                comment = o.optString("comment").trim().take(60),
                unsure = o.optBoolean("unsure", false) || cat.isEmpty(),
                costUsd = costUsd(choice.model, reply),
                tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
                tokensOut = reply.outputTokens,
            )
        }
    }
