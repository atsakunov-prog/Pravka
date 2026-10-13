package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore

/**
 * «В ленте идёт приём пищи, а в дневнике пусто» (Правка 5.0, доводка 14.10.2026;
 * холст `Eda.dc.html` и «Сегодня»): отрезок категории «Еда» в Засечке идёт или
 * только что кончился, а приёма в дневнике еды за это время нет — подсказка
 * «Записать?» с голосом и снимком (существующие дороги еды). Без Claude:
 * сверка двух своих журналов, ленту и дневник она не трогает (еда ленту не
 * отращивает — `docs/agreements.md`).
 *
 * Окна — с запасом в обе стороны: приём пишут и до того, как сказали «обедаю»
 * (сфотографировал тарелку и сел), и после того, как обед кончился (по дороге
 * обратно). Тот, что лежит в окне, — уже «записано», даже не подтверждённый:
 * он ждёт «ОК», а не записи.
 */
object MealGap {

    /** Отрезок «Еды», под который в дневнике пусто: [open] — идёт сейчас. */
    data class Gap(val entryId: Long, val title: String, val start: Long, val end: Long, val open: Boolean)

    /** «Только что был» — кончился не раньше стольких минут назад. */
    const val RECENT_MS = 90 * 60_000L

    /** Приём, записанный до начала отрезка, — его. */
    const val BEFORE_MS = 30 * 60_000L

    /** И после конца. */
    const val AFTER_MS = 60 * 60_000L

    /** Закрытый отрезок короче — оговорка или промах разметки, не трапеза. */
    const val MIN_MS = 5 * 60_000L

    /** Верхний уровень категории «Еда» — как у цвета «чей это» (`CategoryTone.top`). */
    fun isFood(category: String): Boolean = CategoryTone.top(category) == "еда"

    /**
     * Последний отрезок «Еды», идущий или кончившийся за [RECENT_MS] до [now],
     * у которого в окне [начало − BEFORE_MS, конец (или сейчас) + AFTER_MS] нет
     * ни одного приёма ([mealTimes] — когда съедено). [dismissed] — отрезки, про
     * которые владелец сказал «не надо». Нет такого — null.
     */
    fun find(entries: List<ZasechkaStore.Entry>, mealTimes: List<Long>, now: Long, dismissed: Set<Long> = emptySet()): Gap? {
        val e = entries
            .filter { isFood(it.category) && it.source != "gap" && it.start <= now && it.id !in dismissed }
            .filter { if (it.open) true else it.end > 0 && now - it.end in 0..RECENT_MS && it.end - it.start >= MIN_MS }
            .maxByOrNull { it.start } ?: return null
        val until = if (e.open) now else e.end
        val from = e.start - BEFORE_MS
        val to = until + AFTER_MS
        if (mealTimes.any { it in from..to }) return null
        return Gap(e.id, e.title.ifBlank { e.raw.take(40) }.ifBlank { e.category }, e.start, if (e.open) 0L else e.end, e.open)
    }

    /** «Сейчас в ленте «Обед» (с 13:10), а в дневнике пусто. Записать?» — слова подсказки. */
    fun words(g: Gap): String =
        if (g.open) "Сейчас в ленте «${g.title}» (с ${Fmt.hm(g.start)}), а в дневнике еды пусто. Записать?"
        else "В ленте был «${g.title}» (${Fmt.hm(g.start)}–${Fmt.hm(g.end)}), а в дневнике еды пусто. Записать?"
}
