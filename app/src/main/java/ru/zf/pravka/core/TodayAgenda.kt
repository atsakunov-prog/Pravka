package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Списки дел «Сегодня» вида v2 (Правка 5.0, этап 2; план §4): «Сейчас» —
 * только отмеченное владельцем, «Сегодня» — дела со сроком ровно сегодня и
 * встречи календаря, завтрашнее — одной строкой под ними. Просроченное само
 * ни туда, ни туда не попадает — оно во «Всех делах» (решение владельца,
 * план §4 «Дела»).
 *
 * Смысл «Сейчас» и «Сегодня» во вкладке Дел ведёт `core/DelaGroups.kt`; здесь —
 * маленький помощник одного экрана: не трогать чужие правила ради своей
 * раскладки.
 */
object TodayAgenda {

    private fun mine(t: Dela.Task, me: String) = me.isBlank() || t.ownerId == me

    /** «Сейчас»: открытые свои дела, отмеченные владельцем на сегодня (`focus_on`). */
    fun now(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> =
        s.tasks.values
            .filter { it.open && !it.goal && mine(it, me) && it.focusOn == today }
            .sortedWith(Dela.ORDER)

    /** Дела со сроком ровно [day] — по часу, потом кто дольше ждёт. Уже в «Сейчас» — не повторяются. */
    fun due(s: Dela.Snapshot, me: String, day: String, today: String = day): List<Dela.Task> =
        s.tasks.values
            .filter { it.open && !it.goal && mine(it, me) && it.dueDate == day && it.focusOn != today }
            .sortedWith(compareBy<Dela.Task>({ it.dueTime.ifBlank { "99" } }, { it.createdAt }, { it.num }))

    /** Строка «Сегодня»: когда, что, подпись; [past] — встреча уже прошла (бледно), [taskId] — дело. */
    data class Row(
        val whenText: String,
        val title: String,
        val sub: String,
        val past: Boolean = false,
        val taskId: String? = null,
        val sortAt: Long = Long.MAX_VALUE,
    )

    /**
     * «Сегодня»: встречи календаря дня и дела со сроком сегодня одним списком
     * по времени; дело без часа — «за день» в конце. [now] — чтобы прошедшая
     * встреча стала бледной.
     */
    fun rows(
        tasks: List<Dela.Task>,
        events: List<CalEvent>,
        dayStart: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Row> {
        val out = ArrayList<Row>()
        for (e in events) {
            if (e.declined) continue
            val title = CalendarRules.entryTitle(e)
            if (e.allDay) {
                out += Row("весь день", title, "встреча", sortAt = dayStart)
            } else {
                val past = e.end <= now
                out += Row(
                    Fmt.hm(e.start, zone) + "–" + Fmt.hm(e.end, zone),
                    title,
                    if (past) "встреча · прошла" else "встреча",
                    past = past,
                    sortAt = e.start,
                )
            }
        }
        for (t in tasks) {
            val at = hmOf(t.dueTime)?.let { dayStart + it * 60_000L }
            out += Row(
                whenText = at?.let { Fmt.hm(it, zone) } ?: "за день",
                title = t.title,
                sub = listOfNotNull(
                    t.personShort.takeIf { it.isNotBlank() } ?: t.projectName.takeIf { it.isNotBlank() },
                    if (t.ball == Dela.WAITING) "жду" else null,
                ).joinToString(" · "),
                taskId = t.id,
                sortAt = at ?: (dayStart + 86_400_000L),
            )
        }
        return out.sortedBy { it.sortAt }
    }

    /**
     * Завтра одной строкой: «завтра: Дима 9:00, созвон с банком 14:00, …» —
     * встречи и дела со сроком завтра по времени; пусто — строки нет.
     */
    fun tomorrowLine(tasks: List<Dela.Task>, events: List<CalEvent>, zone: ZoneId = ZoneId.systemDefault(), max: Int = 4): String {
        val parts = ArrayList<Pair<Long, String>>()
        for (e in events) {
            if (e.declined) continue
            val title = CalendarRules.entryTitle(e).take(40)
            parts += (if (e.allDay) 0L else e.start) to (if (e.allDay) title else "$title ${shortHm(e.start, zone)}")
        }
        for (t in tasks) {
            val m = hmOf(t.dueTime)
            parts += (m?.toLong() ?: Long.MAX_VALUE / 2) to (t.title.take(40) + (m?.let { " " + shortHmOfMin(it) } ?: ""))
        }
        if (parts.isEmpty()) return ""
        val sorted = parts.sortedBy { it.first }.map { it.second }
        val shown = sorted.take(max).joinToString(", ")
        return "завтра: " + shown + if (sorted.size > max) " и ещё ${sorted.size - max}" else ""
    }

    /** «11:30» → минуты от полуночи; пусто или мусор — null. */
    private fun hmOf(s: String): Int? = runCatching {
        val (h, m) = s.split(':').take(2).map { it.trim().toInt() }
        h * 60 + m
    }.getOrNull()

    /** «9:00» без нуля впереди — как говорят, а не как в таблице. */
    private fun shortHm(ms: Long, zone: ZoneId): String = Fmt.hm(ms, zone).removePrefix("0")

    private fun shortHmOfMin(m: Int): String = Fmt.hmOfMin(m).removePrefix("0")

    /** Начало суток [day] в поясе [zone] — для календаря. */
    fun dayStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
