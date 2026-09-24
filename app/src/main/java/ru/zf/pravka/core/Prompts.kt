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

    // Справочник витаминов и элементов: ключи, единицы, нормы. Стоит в
    // СТАБИЛЬНОЙ части промпта (выше словаря и выше {VARS}) — он не меняется
    // от запроса к запросу и обязан жить под часовым кэшем.
    const val PLACEHOLDER_MICRO = "{MICRO}"

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

    // ---- Деньги: наговор -> траты (Опус) и подсказки сверки (…см. prompts/PromptsMoney.kt)
    val MONEY: String get() = ru.zf.pravka.core.prompts.PromptsMoney.MONEY

    val MONEY_MATCH: String get() = ru.zf.pravka.core.prompts.PromptsMoney.MONEY_MATCH

    val MONEY_ASK: String get() = ru.zf.pravka.core.prompts.PromptsMoney.MONEY_ASK

    val MONEY_PATTERNS: String get() = ru.zf.pravka.core.prompts.PromptsMoney.MONEY_PATTERNS

    val MONEY_ANSWER: String get() = ru.zf.pravka.core.prompts.PromptsMoney.MONEY_ANSWER

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
        // Ставить точку кэша на stablePrefix. Решает вызывающий: разборы
        // режимов ходят десятки раз в день с одним сводом правил — кэш
        // окупается с двух вызовов; чистка ставит её на повседневной модели,
        // а редкая переделка на другой модели — нет (запись за 2x впустую).
        val cacheStableAlways: Boolean = false,
    ) {
        val beforeInput: String get() = stablePrefix + dictPart
    }

    /**
     * Кто диктует (профиль установки, 25.09.2026). Промпт чистки писался под
     * владельца: «Кто диктует: мужчина» и дальше его темы — сделки, приложения,
     * IFS, проза. Для Марианны это значило бы «я сделала» → «я сделал».
     */
    data class Author(val name: String, val female: Boolean, val owner: Boolean) {
        companion object {
            /** Владелец: шаблон и приписки — ровно прежние, байт в байт (кэш промпта). */
            val OWNER = Author("Саша", female = false, owner = true)
        }
    }

    private const val WHO_START = "Кто диктует:"
    private const val WHO_END = "Сначала пойми по содержанию"
    private const val TOPICS = "Он диктует:"

    /** Первая строка абзаца «Кто диктует»: имя, пол и род авторской речи. */
    private fun whoLine(a: Author): String {
        val (kind, rod, say, notSay) = if (a.female) listOf("женщина", "женском", "я подумала", "я подумал")
        else listOf("мужчина", "мужском", "я подумал", "я подумала")
        return "$WHO_START ${a.name}, $kind. Авторская речь от первого лица — всегда\n" +
            "в $rod роде (\"$say\", не \"$notSay\")."
    }

    /**
     * Шаблон чистки под автора (владелец, 25.09.2026: «промпты надо ей дать мои,
     * но без художественной прозы и с пониманием, что диктует женщина»).
     * У владельца — как есть, байт в байт. У другого абзац «Кто диктует» — тот
     * же владельцев, но: имя и род автора; без оговорки «в прозе род по
     * персонажу» и без пункта «художественную прозу…»; «сообщения жене» —
     * «родным». Остальной текст не трогается: правила формы вида «в прозе —
     * словами» без прозы просто не срабатывают. Маркеров нет (свой текст
     * владельца, переписанный правкой промпта) — род первой строкой сверху,
     * иначе чистка писала бы её «я сделала» мужским родом.
     */
    fun forAuthor(template: String, author: Author): String {
        if (author.owner) return template
        val start = template.indexOf(WHO_START)
        val end = template.indexOf(WHO_END)
        if (start < 0 || end <= start) return whoLine(author) + "\n\n" + template
        val topics = ownerTopics(template.substring(start, end))
        val pronoun = if (author.female) "Она диктует:" else "Он диктует:"
        val body = if (topics != null) " $pronoun\n$topics" else " $pronoun что угодно: сообщения родным,\n" +
            "друзьям и коллегам, рабочие и учебные заметки, списки дел,\nвопросы ассистенту.\n"
        return template.substring(0, start) + whoLine(author) + body + template.substring(end)
    }

    /**
     * Темы владельца из его абзаца — пункты после «Он диктует:», без
     * художественной прозы. null — абзац не той формы (переписан), тогда
     * берётся нейтральный список.
     */
    private fun ownerTopics(paragraph: String): String? {
        val at = paragraph.indexOf(TOPICS)
        if (at < 0) return null
        val items = paragraph.substring(at + TOPICS.length).trim('\n', ' ')
            .split("\n— ").map { it.removePrefix("— ").trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("художественн") }
            .map { it.replace("сообщения жене, детям и коллегам", "сообщения родным, детям и коллегам") }
        if (items.isEmpty()) return null
        val last = items.last().trimEnd(';', ',', '.') + "."
        return (items.dropLast(1) + last).joinToString("\n") { "— $it" } + "\n"
    }

    /**
     * Кто диктует — приписка в начало промптов режимов (Засечка, Дела, Еда,
     * Деньги, Тело) у не-владельца. Промпты написаны про Сашу: «владелец»,
     * «Саша диктует», примеры с его семьёй. Для владельца — пусто (кэш цел).
     */
    fun speakerNote(author: Author): String {
        if (author.owner) return ""
        val rod = if (author.female) "женский («я купила», не «я купил»)" else "мужской («я купил», не «я купила»)"
        return "ВАЖНО: диктует не Саша, а ${author.name} (${if (author.female) "женщина" else "мужчина"}). " +
            "Всё, что ниже сказано об авторе — «владелец», «Саша диктует», «его», — относится к " +
            "${author.name}: «я», «мне», «мой» в диктовке — это ${author.name}; род автора — $rod. " +
            "Саша в тексте — отдельный человек из семьи.\n\n"
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
        /** Кто диктует: род в приписке к разговору. С завода — владелец. */
        author: Author = Author.OWNER,
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
            val gender = if (author.female) "автор — женщина, «говорила», а не «говорил»"
            else "автор — мужчина, «говорил», а не «говорила»"
            extras += "Ниже в тегах <разговор> — предыдущие сообщения автора в этом же " +
                "чате. Используй их, чтобы понять, о чём идёт речь, выдержать тон и " +
                "правильно согласовать род и имена ($gender). Сами сообщения не правь и в ответ не включай.\n" +
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
