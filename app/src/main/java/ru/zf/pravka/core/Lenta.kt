package ru.zf.pravka.core

import ru.zf.pravka.core.DayAssembler.DayItem

/**
 * Лента дня строками — с вечера к утру (Правка 5.0, этап 2; владелец
 * 13.10.2026, поправка к холсту): у каждого отрезка «от–до», название, под
 * ним мелко категория и откуда (голос, авто, Garmin, руками), справа
 * длительность. Под отрезком — что в нём случилось (еда, спорт, дела, деньги,
 * сон) отдельными строками со значком вида на месте времени; свежее сверху и
 * там. Событие лежит в том отрезке, в чьё «от–до» попадает его время.
 *
 * Строки собираются из хроники дня (`DayAssembler.assemble`): она уже
 * привязала отметки режимов к записям — здесь только порядок и слова. Одна
 * правда на «Сегодня» и Засечку; рисунок строки — `ui/Ribbon.kt`.
 */
object Lenta {

    /** Вид события внутри отрезка — по нему значок на месте времени. */
    enum class Kind { FOOD, SPORT, SLEEP, DELA, MONEY }

    sealed class Row {
        abstract val key: String

        /** Отрезок ленты: [from] — настоящее начало (сон с вечера — вчерашнее), [sub] — «категория · откуда». */
        data class Seg(
            val entryId: Long,
            val from: Long,
            val to: Long,
            val title: String,
            val category: String,
            val worth: Int,
            val sub: String,
            val minutes: Int,
            val gap: Boolean,
            val current: Boolean,
        ) : Row() {
            override val key get() = "s$entryId"
        }

        /** Что случилось внутри отрезка: время, вид, слова; [ref] — куда ведёт тап, [pending] — ждёт «Записать». */
        data class Event(
            val entryId: Long,
            val at: Long,
            val kind: Kind,
            val text: String,
            val ref: String,
            val pending: Boolean = false,
        ) : Row() {
            override val key get() = "v$entryId-$at-${kind.name}-${text.hashCode()}"
        }
    }

    /**
     * Строки ленты прошлого дня из хроники [items]: отрезки свежие сверху, под
     * каждым — его события, тоже свежие сверху. Будущее (после «сейчас») сюда не
     * идёт — это лента, а не план. [sourceOf] — машинный источник записи по id
     * (`voice`, `auto`…), [rawOf] — сказанное (по нему узнаётся Garmin).
     */
    fun rows(
        items: List<DayItem>,
        sourceOf: (Long) -> String = { "" },
        rawOf: (Long) -> String = { "" },
    ): List<Row> {
        val segs = ArrayList<Pair<Row.Seg, MutableList<Row.Event>>>()
        val loose = ArrayList<Row.Event>()
        for (item in items) {
            when (item) {
                is DayItem.Entry -> {
                    // У заполнителя подписи нет: «не размечено» — не категория и не источник, а дыра.
                    val sub = if (item.gap) "" else listOfNotNull(
                        item.category.takeIf { it.isNotBlank() },
                        sourceWord(sourceOf(item.id), item.category, rawOf(item.id)).takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    segs += Row.Seg(
                        entryId = item.id,
                        from = item.fullStart,
                        to = item.end,
                        title = if (item.gap) "не размечено — разметить" else item.title,
                        category = item.category,
                        worth = item.worth,
                        sub = sub,
                        minutes = item.fullMinutes,
                        gap = item.gap,
                        current = item.current,
                    ) to ArrayList()
                }
                is DayItem.Mark -> event(item.entryId, item.at, item.source, item.text, item.ref, false)
                    .let { e -> segs.lastOrNull()?.second?.add(e) ?: loose.add(e) }
                is DayItem.Pending -> event(item.entryId, item.at, item.source, item.text, item.ref, true)
                    .let { e -> segs.lastOrNull()?.second?.add(e) ?: loose.add(e) }
                is DayItem.Now -> break
                else -> Unit
            }
        }
        val out = ArrayList<Row>()
        for ((seg, events) in segs.asReversed()) {
            out += seg
            out += events.sortedByDescending { it.at }
        }
        // День ещё пуст, а отметки есть: строки событий без отрезка.
        out += loose.sortedByDescending { it.at }
        return out
    }

    private fun event(entryId: Long, at: Long, source: DayAssembler.Source, text: String, ref: String, pending: Boolean) =
        Row.Event(entryId, at, kindOf(source, ref), text, ref, pending)

    /** Вид события: по источнику отметки, сон — по ссылке (`sleep`, его кладёт спорт). */
    fun kindOf(source: DayAssembler.Source, ref: String): Kind = when {
        ref.startsWith("sleep") -> Kind.SLEEP
        source == DayAssembler.Source.FOOD -> Kind.FOOD
        source == DayAssembler.Source.SPORT -> Kind.SPORT
        source == DayAssembler.Source.MONEY -> Kind.MONEY
        else -> Kind.DELA
    }

    /**
     * Откуда запись словом — для второй строки отрезка: голос, текст, руками,
     * из дела, метка, автопилот; авто-факт часов (сон, тренировка) — «Garmin»,
     * остальное авто (звонок, сидение в телефоне) — «авто». Заполнитель дыр —
     * пусто: у него вместо названия «не размечено».
     */
    fun sourceWord(source: String, category: String, raw: String = ""): String = when (source) {
        "voice" -> "голос"
        "text" -> "текст"
        "edit" -> "руками"
        "task" -> "из дела"
        "todoist" -> "из Todoist"
        "nfc" -> "метка"
        "autopilot", CalendarRules.SOURCE -> "автопилот"
        "auto" -> if (raw.contains("garmin", ignoreCase = true) || DayReport.isSleep(category) || DayReport.isSport(category)) "Garmin" else "авто"
        "gap", "" -> ""
        else -> "голос"
    }
}
