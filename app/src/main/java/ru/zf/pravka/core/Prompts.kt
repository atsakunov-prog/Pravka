package ru.zf.pravka.core

// Factory prompt texts, verbatim from the spec (section 7.1). Do not edit here:
// the owner edits them on the device (stage 6); these are the fallback defaults.
//
// Этот файл — ИНДЕКС и сборка (assemble). Сами тексты лежат по режимам в
// core/prompts/Prompts<Режим>.kt: правка промпта Еды не заставляет читать тысячу
// строк про чистку текста и таймшит. Имена Prompts.X сохранены — их читает
// PromptStore и все вызывающие.
object Prompts {
    const val PLACEHOLDER_INPUT = "{INPUT}"
    const val PLACEHOLDER_DICT = "{DICT}"

    // Factory CLEAN v2.0: merged from the owner's external review (another (…см. prompts/PromptsPravka.kt)
    val CLEAN_CLAUDE: String get() = ru.zf.pravka.core.prompts.PromptsPravka.CLEAN_CLAUDE

    // BUSINESS and SOFTEN are no longer standalone templates: they are style (…см. prompts/PromptsPravka.kt)
    val BUSINESS: String get() = ru.zf.pravka.core.prompts.PromptsPravka.BUSINESS

    val SOFTEN: String get() = ru.zf.pravka.core.prompts.PromptsPravka.SOFTEN

    // One-tap redo directives (result-bar chips / FAB menu). Same slot.
    val REDO_SHORTER: String get() = ru.zf.pravka.core.prompts.PromptsPravka.REDO_SHORTER

    val REDO_LONGER: String get() = ru.zf.pravka.core.prompts.PromptsPravka.REDO_LONGER

    val REDO_POLISH: String get() = ru.zf.pravka.core.prompts.PromptsPravka.REDO_POLISH

    // Fiction mode (settings toggle): the owner writes prose, where CLEAN's (…см. prompts/PromptsPravka.kt)
    val PROSE: String get() = ru.zf.pravka.core.prompts.PromptsPravka.PROSE

    // Meeting transcripts from Whisper on the owner's computer. NOT sent by (…см. prompts/PromptsPravka.kt)
    val MEETING: String get() = ru.zf.pravka.core.prompts.PromptsPravka.MEETING

    // ---- assist tasks (FAB menu, orange column): free-form actions on the (…см. prompts/PromptsPravka.kt)
    val ASSIST_SUMMARY: String get() = ru.zf.pravka.core.prompts.PromptsPravka.ASSIST_SUMMARY

    val ASSIST_REPLY: String get() = ru.zf.pravka.core.prompts.PromptsPravka.ASSIST_REPLY

    val ASSIST_TRANSLATE: String get() = ru.zf.pravka.core.prompts.PromptsPravka.ASSIST_TRANSLATE

    // ---- Разноска: наговор -> дела в Todoist. Runs on Opus (the split is the (…см. prompts/PromptsRaznoska.kt)
    val TASKS: String get() = ru.zf.pravka.core.prompts.PromptsRaznoska.TASKS

    // ---- Еда: сказанное -> КБЖУ. Работает на Сонете: это не суждение, а (…см. prompts/PromptsFood.kt)
    val FOOD: String get() = ru.zf.pravka.core.prompts.PromptsFood.FOOD

    // То же, но по снимку тарелки: текста может не быть вообще.
    val FOOD_PHOTO_HINT: String get() = ru.zf.pravka.core.prompts.PromptsFood.FOOD_PHOTO_HINT

    // ---- Тело: один микрофон на подходы, еду, зарядку и вопросы.
    //
    // Роутер и разбор ОДНИМ вызовом: два запроса подряд стоили бы вдвое дороже
    // и вдвое дольше, а классификация без разбора всё равно бесполезна.
    //
    // Шаблон делится на две части маркером {VARS}: всё выше него байт в байт
    // одинаково между запросами (инструкция + справочник упражнений +
    // справочник рациона), поэтому именно туда встаёт точка кэша на час. Всё
    // ниже — переменное: словарь, план на сегодня, прошлый раз, сказанное.
    const val PLACEHOLDER_VARS = "{VARS}"

    val BODY: String get() = ru.zf.pravka.core.prompts.PromptsBody.BODY

    // ---- Тренер-консультант: короткий вопрос про упражнение. Сонет и никакой (…см. prompts/PromptsBody.kt)
    val TRAINER: String get() = ru.zf.pravka.core.prompts.PromptsBody.TRAINER

    val PATTERNS: String get() = ru.zf.pravka.core.prompts.PromptsAnalysis.PATTERNS

    val CHAT_HANDOFF: String get() = ru.zf.pravka.core.prompts.PromptsAnalysis.CHAT_HANDOFF

    val RULES: String get() = ru.zf.pravka.core.prompts.PromptsBody.RULES

    // ---- Спорт: вопрос по своим тренировкам. Опус: трудное здесь не (…см. prompts/PromptsBody.kt)
    val COACH: String get() = ru.zf.pravka.core.prompts.PromptsBody.COACH

    // The assembled prompt in three segments. stablePrefix is byte-identical
    // across requests (the template before {DICT}) - ClaudeProvider puts the
    // cache_control breakpoint there. dictPart varies per request (matched
    // dictionary entries + the template between {DICT} and {INPUT}), so it
    // must stay OUTSIDE the cached prefix or the cache never hits.
    data class PromptParts(
        val stablePrefix: String,
        val dictPart: String,
        val afterInput: String,
        // Кэшировать stablePrefix и на Опусе: по умолчанию кэш живёт только
        // на повседневном Сонете, но Засечка ходит Опусом десятки раз в день
        // с одним и тем же сводом правил — там кэш окупается с двух вызовов.
        val cacheStableAlways: Boolean = false,
    ) {
        val beforeInput: String get() = stablePrefix + dictPart
    }

    // Splits at {DICT} and {INPUT} (empty dict block leaves no stray blank
    // lines). If a user-edited template loses {INPUT}, the input is appended
    // at the end - never silently dropped.
    //
    // [directive]: a style/redo task (BUSINESS, SOFTEN, redo chips) that rides
    // in the UNCACHED slot right after the dict block - every mode shares the
    // one cached CLEAN prefix.
    // [context]: what already stands in the field before a mid-field insert;
    // read-only for the model, used to get the capital letter and punctuation
    // right at the seam.
    fun assemble(
        template: String,
        dictBlock: String,
        directive: String = "",
        context: String = "",
        // Previous takes in the same chat. A SEPARATE envelope from [context]:
        // field context is "punctuation at the seam" material, conversation
        // context is "tone, gender, what we're talking about" material -
        // stuffing both under the seam instruction neutered the second.
        conversation: String = "",
    ): PromptParts {
        val inputIdx = template.indexOf(PLACEHOLDER_INPUT)
        val before = if (inputIdx >= 0) template.substring(0, inputIdx) else template
        val after = if (inputIdx >= 0) {
            template.substring(inputIdx + PLACEHOLDER_INPUT.length).trimEnd()
        } else ""

        val dict = if (dictBlock.isBlank()) "" else dictBlock.trim() + "\n\n"
        var extras = ""
        if (directive.isNotBlank()) {
            extras += "ДОПОЛНИТЕЛЬНОЕ ЗАДАНИЕ ПОВЕРХ ПРАВКИ:\n" + directive.trim() + "\n\n"
        }
        if (conversation.isNotBlank()) {
            extras += "Ниже в тегах <разговор> — предыдущие сообщения автора в этом же " +
                "чате. Используй их, чтобы понять, о чём идёт речь, выдержать тон и " +
                "правильно согласовать род и имена (автор — мужчина, «говорил», а не " +
                "«говорила»). Сами сообщения не правь и в ответ не включай.\n" +
                "<разговор>\n" + conversation.trim() + "\n</разговор>\n\n"
        }
        if (context.isNotBlank()) {
            extras += "Перед текстом для правки в поле уже стоит текст (ниже " +
                "в тегах <контекст>). Используй его ТОЛЬКО чтобы правильно " +
                "выбрать заглавную или строчную букву и пунктуацию на стыке. " +
                "Контекст не правь и в ответ не включай.\n" +
                "<контекст>\n" + context + "\n</контекст>\n\n"
        }

        // The standard template wraps {DICT} in <словарь> tags. Those tags must
        // travel WITH the dict content into the variable slot: splitting after
        // the opening tag used to leave it in the stable prefix, so the style
        // directive and the conversation context were injected INSIDE the
        // dictionary tags - the model was told they were dictionary entries.
        val tagged = Regex("<словарь>\\n*\\{DICT\\}\\n*</словарь>\\n*").find(before)
        if (tagged != null) {
            val stable = before.substring(0, tagged.range.first).trim() + "\n\n"
            val rest = before.substring(tagged.range.last + 1).trimStart()
            val tail = if (inputIdx >= 0) rest else rest.trimEnd() + "\n\n"
            val taggedDict = "<словарь>\n" + dictBlock.trim() + "\n</словарь>\n\n"
            return PromptParts(stable, taggedDict + extras + tail, after)
        }
        val match = Regex("\\n*\\{DICT\\}\\n*").find(before)
        return if (match != null) {
            val stable = before.substring(0, match.range.first).trim() + "\n\n"
            val rest = before.substring(match.range.last + 1).trimStart()
            // Without {INPUT} the input is appended after the template tail.
            val tail = if (inputIdx >= 0) rest else rest.trimEnd() + "\n\n"
            PromptParts(stable, dict + extras + tail, after)
        } else {
            // No {DICT} placeholder: the dict block is dropped, matching the
            // editor warning ("без {DICT} подсказки не попадают в промпт") -
            // but a style directive / field context must never be lost.
            val stable = if (inputIdx >= 0) before else before.trimEnd() + "\n\n"
            PromptParts(stable, extras, after)
        }
    }
}
