package ru.zf.pravka.core

/**
 * Развилка сказанного в Засечку: дело в ленту, мысль к текущему делу, еда
 * или дела в Todoist. Владелец (25.09.2026): «если в тексте засечки есть:
 * запиши мысль, я тут подумал, запиши коммент — пиши коммент к делу; запиши
 * еду, запиши блюдо — слушает Едой; запиши дело, надо не забыть, не забыть,
 * запиши важное — это запись дел».
 *
 * Узнаём по словам, а не моделью, нарочно. Это команды, и промах здесь
 * дорог: мысль, уехавшая в Todoist задачей, хуже лишней секунды. Слова
 * предсказуемы, владелец их выучит, список — в одном месте под тестами
 * (как голосовые команды Правки, `VoiceCommands`), и лишнего хода в модель
 * нет.
 *
 * Где команда может стоять. «Сильная» — глагол с предметом («запиши мысль»,
 * «запиши дело», «запиши еду») — в начале фразы или в самом конце («купить
 * молоко, запиши дело»). «Слабая» — одно слово-сигнал («подумал», «мысль»,
 * «не забыть», «напомни») — только в начале: посреди рассказа «думаю по
 * дороге про сделку» — это рассказ, а не команда. «Думаю над презентацией» —
 * занятие для ленты, мыслью считается только «думаю, что…».
 *
 * Чего нарочно нет. «Еду» без глагола — это дорога («еду к Илье»). «Запиши
 * обед с часу до двух» и «добавь дело …» — вставки в ленту, их Засечка
 * умеет сама, поэтому завтрак/обед/ужин не еда-команда, а «добавь» с «делом»
 * не Todoist («добавь задачу» — Todoist).
 */
object ZasechkaIntent {

    enum class Kind {
        /** Обычная засечка — разбор ленты. */
        ENTRY,

        /** Мысль, коммент к текущему делу. */
        COMMENT,

        /** Приём пищи — разбор Еды. */
        FOOD,

        /** Дело или несколько — разбор Дел и Todoist. */
        TASKS,
    }

    /**
     * Куда и что: [text] — сказанное без самой команды (у [Kind.ENTRY] — как
     * было); [why] — пара слов модели «почему так», для журнала (у слов — пусто).
     */
    data class Route(val kind: Kind, val text: String, val why: String = "")

    /**
     * Ответ Сонета на развилке (26.09.2026, владелец: «чтобы Сонет очень
     * быстро просматривал фразу и сначала говорил, что это такое… и дальше
     * уже отдавал Опусу»). null — ответ не прочитался: тогда решают слова
     * ([route]), и сказанное не теряется.
     *
     * Лента получает фразу как сказана, а не пересказ модели: её разбирает
     * Опус со всеми правилами времени, и лишняя переформулировка там только
     * теряет «последние полчаса». Мысли, еде и делам модель отдаёт текст без
     * служебных слов; пусто — берём сказанное целиком.
     */
    fun fromModel(reply: String, original: String): Route? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { org.json.JSONObject(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        val kind = when (o.optString("kind").trim().lowercase()) {
            "entry" -> Kind.ENTRY
            "comment" -> Kind.COMMENT
            "food" -> Kind.FOOD
            "tasks" -> Kind.TASKS
            else -> return null
        }
        val said = original.trim()
        val text = if (kind == Kind.ENTRY) said else o.optString("text").trim().ifBlank { said }
        return Route(kind, text, o.optString("why").trim())
    }

    /** Что сказать владельцу в конце (в гарнитуру и на записке). */
    fun said(kind: Kind, count: Int = 1): String = when (kind) {
        Kind.COMMENT -> "записал коммент"
        Kind.FOOD -> "записал еду"
        Kind.TASKS -> if (count > 1) "записал дела" else "записал дело"
        Kind.ENTRY -> ""
    }

    fun route(raw: String): Route {
        val text = raw.trim()
        if (text.isEmpty()) return Route(Kind.ENTRY, "")
        for ((kind, rx) in startStrong) {
            rx.find(text)?.let { return Route(kind, tidy(text.substring(it.range.last + 1))) }
        }
        for ((kind, rx) in startWeak) {
            rx.find(text)?.let { return Route(kind, tidy(text.substring(it.range.last + 1))) }
        }
        for ((kind, rx) in endStrong) {
            val m = rx.find(text) ?: continue
            val head = m.groupValues[1]
            if (head.isNotBlank()) return Route(kind, tidy(head))
        }
        return Route(Kind.ENTRY, text)
    }

    // ---- словарь команд ----

    /** Разделители между словами: распознаватель ставит их по-разному, а то и не ставит. */
    private const val SEP = "[\\s,.:;!?—–\\-]"

    /** Слово кончилось (Java `\b` кириллицу не знает). */
    private const val END = "(?![\\p{L}\\p{N}])"

    /** Разгон перед командой: «так, слушай, запиши дело…». */
    private const val FILLERS =
        "(?:(?:так|вот|слушай|короче|кстати|ещ[её]|и|а|ну|давай|пожалуйста|значит|смотри|окей|итак)$SEP+){0,3}"

    private const val VERB = "(?:запиши-ка|запишите|запишем|записать|запиши|занеси|сохрани|добавь)"

    /** «Добавь дело» — вставка в ленту, поэтому у дел свой глагол без «добавь». */
    private const val VERB_TASKS = "(?:запиши-ка|запишите|запишем|записать|запиши|занеси|сохрани)"

    /** «…к делу», «…к текущему делу» после команды мысли. */
    private const val TO_ENTRY = "(?:$SEP+(?:к|в)\\s+(?:этому\\s+|текущему\\s+)?делу$END)?"

    private const val TASK_OBJ =
        "(?:задачи|задачу|напоминание|в\\s+дела|в\\s+задачи|в\\s+тудуист|в\\s+todoist)"

    private val strong = listOf(
        Kind.COMMENT to "$VERB$SEP+(?:комментарий|коммент|мысли|мысль|заметку|идею)$END$TO_ENTRY",
        Kind.FOOD to "$VERB$SEP+(?:еду|блюда|блюдо|перекус|при[её]м\\s+пищи|что\\s+(?:я\\s+)?(?:съел|поел|выпил))$END",
        Kind.TASKS to "(?:$VERB_TASKS$SEP+(?:дела|дело|важное|$TASK_OBJ)|добавь$SEP+$TASK_OBJ)$END",
    )

    private val weak = listOf(
        Kind.COMMENT to
            "(?:подумалось|(?:я\\s+)?(?:тут\\s+|вот\\s+)?подумал[аи]?|мысли\\s+вслух|мысль|" +
            "(?:я\\s+)?думаю,?\\s+что|комментарий|коммент|идея|заметка)$END",
        Kind.TASKS to "(?:(?:надо\\s+|нужно\\s+)?не\\s+забыть|не\\s+забудь|напомнить|напомни|важное|задача)$END",
    )

    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val startStrong = strong.map { (k, p) -> k to Regex("(?iu)^\\s*$FILLERS$p", options) }
    private val startWeak = weak.map { (k, p) -> k to Regex("(?iu)^\\s*$FILLERS$p", options) }
    private val endStrong = strong.map { (k, p) -> k to Regex("(?iu)^(.*?)$SEP+$p[\\s.!?]*$", options) }

    /** Хвост после команды: разделители и «что» — долой, первая буква — заглавная. */
    private val lead = Regex("(?iu)^$SEP*(?:(?:что|чтобы)(?=[\\s,]|$))?$SEP*")

    private fun tidy(rest: String): String =
        rest.replaceFirst(lead, "").trim().replaceFirstChar { it.titlecase() }
}
