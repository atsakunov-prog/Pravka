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
import ru.zf.pravka.provider.ClaudeProvider.MoneyParse

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
