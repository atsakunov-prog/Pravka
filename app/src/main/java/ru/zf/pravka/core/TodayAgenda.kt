package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Строки «Сегодня» вида v2 (Правка 5.0, этап 2; план §4): дела со сроком
 * сегодня и встречи календаря одним списком по времени, завтрашнее — одной
 * строкой под ними. Какие дела — решает `core/DelaGroups.kt` (этап 4, одна
 * правда с вкладкой Дел и сервером): «Сейчас» — `nowOrdered` (отмеченное,
 * `focus_on` сегодня), «Сегодня» — `todayByGroups` без взятого в «Сейчас»
 * (срок ровно сегодня); просроченное — ни там, ни там, оно во «Всех делах».
 * Здесь — только раскладка экрана.
 */
object TodayAgenda {

    /** «Сейчас» — отмеченное владельцем на сегодня (`DelaGroups.nowOrdered`). */
    fun now(s: Dela.Snapshot, me: String, today: String): List<Dela.Task> = DelaGroups.nowOrdered(s, me, today)

    /**
     * Дела со сроком ровно [day] без взятого в «Сейчас» (`DelaGroups.todayByGroups`) — во всех сферах:
     * «Сегодня» на главном экране — день целиком, а не одна сфера вкладки.
     */
    fun due(s: Dela.Snapshot, me: String, day: String, today: String = day): List<Dela.Task> =
        DelaGroups.todayByGroups(s, me, day, "all", skip = now(s, me, today).map { it.id }.toSet())

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
        /** Живых идей у клиента или человека встречи — подпись «встреча · 💡 2» (захват, 15.10.2026). */
        ideasOf: (CalEvent) -> Int = { 0 },
    ): List<Row> {
        val out = ArrayList<Row>()
        for (e in events) {
            if (e.declined) continue
            val title = CalendarRules.entryTitle(e)
            val ideas = ideasOf(e).takeIf { it > 0 }?.let { " · 💡 $it" }.orEmpty()
            if (e.allDay) {
                out += Row("весь день", title, "встреча$ideas", sortAt = dayStart)
            } else {
                val past = e.end <= now
                out += Row(
                    Fmt.hm(e.start, zone) + "–" + Fmt.hm(e.end, zone),
                    title,
                    if (past) "встреча · прошла" else "встреча$ideas",
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

    // ------------------------------------------------------------ напоминания, отметки, «Вернулось» (захват, 15.10.2026)

    /**
     * Строка карточки «Напоминания» на «Сегодня»: когда, что, подпись; [taskId] — напоминание или дело Дел (у него
     * кнопки «✓ · +1 ч · завтра», когда [due] — пора), иначе — своя отметка календаря ([mark]).
     */
    data class RemindRow(
        val whenText: String,
        val title: String,
        val sub: String,
        val taskId: String? = null,
        val due: Boolean = false,
        val mark: Boolean = false,
        val sortAt: Long = Long.MAX_VALUE,
    )

    /**
     * «Напоминания» на «Сегодня» (владелец: «напоминание… стоит на „Сегодня“ в своё время»): напоминания Дел на сегодня
     * (`DelaGroups.remindersToday` — одно правило с сервером и вебом) и свои отметки календаря (событие «Свободен»,
     * `CalendarRules.mark` — встречей их Засечка не считает) — по времени; по месту — в конце.
     */
    fun reminderRows(
        s: Dela.Snapshot,
        me: String,
        today: String,
        now: Long,
        marks: List<CalEvent> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RemindRow> {
        val out = ArrayList<RemindRow>()
        for (t in DelaGroups.remindersToday(s, me, today)) {
            val at = ReminderPlan.atMs(t)
            val sub = when {
                !t.remind -> "дело" + if (t.dueDate.isNotBlank()) " на " + (DelaViews.due(t, today)?.text ?: t.dueDate) else ""
                t.remindAt.isBlank() && t.remindPlace.isNotBlank() -> "по месту"
                else -> ""
            }
            out += RemindRow(
                whenText = ReminderPlan.whenOnToday(t, today, zone),
                title = t.title,
                sub = sub,
                taskId = t.id,
                due = at != null && at <= now,
                sortAt = at ?: Long.MAX_VALUE,
            )
        }
        for (e in marks) {
            if (e.declined || e.allDay) continue
            out += RemindRow(Fmt.hm(e.start, zone), CalendarRules.cleanTitle(e.title), "отметка", mark = true, sortAt = e.start)
        }
        return out.sortedBy { it.sortAt }
    }

    /** Строка «Вернулось»: дело «потом», чей день настал, и как оно было сказано. */
    data class BackRow(val taskId: String, val title: String, val sub: String)

    /**
     * «Вернулось» (`DelaGroups.backToday`): «↩ Позвонить Ивану · ты сказал «на неделе»» — первыми строками, пока не
     * решишь: «В „Сейчас“ · Сегодня · Ещё потом · Сделано».
     */
    fun backRows(s: Dela.Snapshot, me: String, today: String): List<BackRow> =
        DelaGroups.backToday(s, me, today).map { t ->
            val said = if (t.backSaid.isNotBlank()) "ты сказал «${t.backSaid}»" else "отложено"
            val day = t.createdAt.takeIf { it.isNotBlank() }?.let { " " + DelaViews.ddmm(DelaGroups.mskDay(it), today) }.orEmpty()
            BackRow(t.id, t.title, said + day)
        }

    /**
     * Идеи к встрече (ответ владельца 5а: «всплывают перед встречей»): живые идеи клиента встречи (`CalendarRules.projectOf`
     * — имя или другое имя проекта в названии) и человека, которого название называет (имя или короткое имя, падежи —
     * `CalendarRules.stemEq`; два подходящих человека — никого: чужая идея хуже никакой). Новые сверху, без повторов.
     */
    fun meetingIdeas(e: CalEvent, s: Dela.Snapshot): List<Dela.Idea> {
        if (s.ideas.isEmpty() || e.declined) return emptyList()
        val client = CalendarRules.projectOf(e.title, s.liveProjects().map { CalProject(it.id, it.name, it.aliases) })
        val words = CalendarRules.words(e.title)
        val person = s.livePeople().filter { p ->
            val mine = (CalendarRules.words(p.name) + CalendarRules.words(p.short)).toSet()
            mine.isNotEmpty() && mine.any { w -> words.any { CalendarRules.stemEq(w, it) } }
        }.singleOrNull()
        if (client == null && person == null) return emptyList()
        return s.ideas.values.filter { x ->
            x.open && ((client != null && x.projectId == client.id) || (person != null && x.personId == person.id))
        }.sortedByDescending { it.createdAt }
    }

    /** «Ещё потом» у «Вернулось» — на ближайший понедельник (`D.next(0)` веба), не раньше завтра. */
    fun backLater(today: String): String {
        val d = runCatching { LocalDate.parse(today.take(10)) }.getOrElse { return "" }
        var x = d.plusDays(1)
        while (x.dayOfWeek != java.time.DayOfWeek.MONDAY) x = x.plusDays(1)
        return x.toString()
    }

    /**
     * Дело-созвон с часом и его событие в календаре — одна строка (spec-0 §4): `gcal.py` Встреч ставит созвон из Дел в
     * календарь, и на «Сегодня» он встал бы дважды. Строку дела прячем, если в те же ±5 минут есть событие, которое
     * узнаёт себя в названии дела (`CalendarRules.sameMeeting`).
     */
    fun dropTwinCalls(tasks: List<Dela.Task>, events: List<CalEvent>, dayStart: Long): List<Dela.Task> =
        tasks.filter { t ->
            val at = hmOf(t.dueTime)?.let { dayStart + it * 60_000L } ?: return@filter true
            events.none { e ->
                !e.allDay && kotlin.math.abs(e.start - at) <= 5 * 60_000L &&
                    CalendarRules.sameMeeting(e, CalSeen(0L, at, t.title, "", t.projectName))
            }
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
