package ru.zf.pravka.core

import kotlin.math.roundToInt

// Хроника дня «Сегодня» — Правка 4.0 (07.10.2026, DESIGN §1, §11.5, TASK
// «Сборка хроники»). Основа — лента Засечки: непрерывные записи «что делал».
// К каждой записи прикреплены отметки других режимов в тот момент, когда они
// случились (еда, траты, тренировка, новые дела из встреч и почты). После
// линии «сейчас» лента продолжается в будущее: свободные окна, календарь,
// план тренировки, дела на сегодня одно за другим по оценке минут и сон.
//
// Здесь — только правило, без Android и сторов: вход — простые модели,
// выход — список строк `DayItem` в порядке экрана. Экран (`TodayScreen.kt`)
// собирает вход из сторов и рисует строки деталями набора (`ui/Timeline.kt`).
// Под JVM-тестами (`DayAssemblerTest`) — пример из DESIGN §11.5 один в один.

object DayAssembler {

    const val MIN = 60_000L
    const val HOUR = 3_600_000L

    // ---- вход ----

    /** Запись ленты Засечки. [end] = 0 — идёт. [worth] — очки за час категории. */
    data class EntryIn(
        val id: Long,
        val start: Long,
        val end: Long,
        val title: String,
        val category: String,
        val worth: Int,
        val client: String = "",
        val useful: Int = 0,
        val comment: String = "",
        val gap: Boolean = false,
    )

    /** Откуда отметка: от этого зависят монета, цвет и куда ведёт тап. */
    enum class Source { SPORT, FOOD, MONEY, DELA }

    /** Отметка другого режима в момент [at]. [ref] — что открыть по тапу. */
    data class MarkIn(val at: Long, val source: Source, val text: String, val ref: String = "")

    /** Ждёт подтверждения (еда с фото или голоса): «Паста, салат · ≈ 720 ккал» и «Записать». */
    data class PendingIn(val at: Long, val source: Source, val text: String, val ref: String)

    /** Событие календаря. */
    data class CalendarIn(val start: Long, val end: Long, val title: String, val note: String = "")

    /** Плановая тренировка. [start] = null — время не назначено. */
    data class WorkoutIn(val start: Long?, val minutes: Int, val title: String, val note: String = "", val ref: String = "")

    /**
     * Дело на сегодня в порядке списка «На сегодня». [fixedAt] — плановое
     * время с сервера (тогда дело стоит на нём), [estimateMin] — оценка.
     */
    data class TaskIn(
        val id: String,
        val title: String,
        val second: String,
        val estimateMin: Int,
        val fixedAt: Long? = null,
    )

    data class Input(
        val dayStart: Long,
        val now: Long,
        val entries: List<EntryIn>,
        val marks: List<MarkIn> = emptyList(),
        val pending: List<PendingIn> = emptyList(),
        val calendar: List<CalendarIn> = emptyList(),
        val workouts: List<WorkoutIn> = emptyList(),
        val tasks: List<TaskIn> = emptyList(),
        /** Время сна, минуты от полуночи (заводское 23:30). */
        val bedtimeMin: Int = 23 * 60 + 30,
        /** Оценка дела без своей оценки, минуты. */
        val defaultEstimateMin: Int = 15,
    )

    // ---- выход ----

    sealed class DayItem {
        abstract val key: String

        /**
         * Запись прошлого. [start] обрезан по началу дня; [fullStart] —
         * настоящее начало (сон с вечера: «с 23:40 вчера»). [points] — очки
         * завершённой записи; у текущей — null (пока идёт, не показываются).
         */
        data class Entry(
            val id: Long,
            val start: Long,
            val end: Long,
            val fullStart: Long,
            val title: String,
            val category: String,
            val worth: Int,
            val client: String,
            val useful: Int,
            val comment: String,
            val points: Int?,
            val current: Boolean,
            val gap: Boolean,
        ) : DayItem() {
            override val key get() = "e$id"
            val minutes: Int get() = ((end - start) / MIN).toInt()
            val fullMinutes: Int get() = ((end - fullStart) / MIN).toInt()
            val fromYesterday: Boolean get() = fullStart < start
        }

        data class Mark(val entryId: Long, val at: Long, val source: Source, val text: String, val ref: String) : DayItem() {
            override val key get() = "m$entryId-$at-${source.name}-${text.hashCode()}"
        }

        data class Pending(val entryId: Long, val at: Long, val source: Source, val text: String, val ref: String) : DayItem() {
            override val key get() = "p$ref"
        }

        data class Now(val at: Long) : DayItem() {
            override val key get() = "now"
        }

        data class Free(val from: Long, val to: Long) : DayItem() {
            override val key get() = "f$from"
            val minutes: Int get() = ((to - from) / MIN).toInt()
        }

        /** План: событие календаря или тренировка. */
        data class Planned(val start: Long, val end: Long, val title: String, val workout: Boolean, val note: String, val ref: String) : DayItem() {
            override val key get() = "pl$start-${title.hashCode()}"
            val minutes: Int get() = ((end - start) / MIN).toInt()
        }

        /** Тренировка в плане без времени — строкой перед делами. */
        data class PlannedLoose(val minutes: Int, val title: String, val note: String, val ref: String) : DayItem() {
            override val key get() = "pll${title.hashCode()}"
        }

        data class TaskGroup(val count: Int, val minutes: Int) : DayItem() {
            override val key get() = "tg"
        }

        /** Дело в очереди: [start] — когда до него дойдёт, [first] — первое (время светлее). */
        data class Task(val id: String, val start: Long, val minutes: Int, val title: String, val second: String, val first: Boolean, val fixed: Boolean) : DayItem() {
            override val key get() = "t$id"
        }

        /** Сон во время из настроек. [overflowMin] > 0 — «дела не влезают на N м». */
        data class Sleep(val at: Long, val overflowMin: Int) : DayItem() {
            override val key get() = "sleep"
        }
    }

    /** Итог сборки: строки, балл дня и индекс линии «сейчас» (−1 — её нет). */
    data class Result(val items: List<DayItem>, val score: Int, val nowIndex: Int, val bedtime: Long)

    // ---- правило ----

    fun assemble(input: Input): Result {
        val dayStart = input.dayStart
        val dayEnd = dayStart + 24 * HOUR
        val now = input.now
        val today = now in dayStart until dayEnd
        val past = now >= dayEnd
        val future = now < dayStart
        val bedtime = dayStart + input.bedtimeMin * MIN

        val out = ArrayList<DayItem>()
        var score = 0.0

        // ---- прошлое: записи Засечки с их отметками ----
        val inDay = input.entries
            .filter { it.start < dayEnd && (it.end == 0L || it.end > dayStart) }
            .sortedBy { it.start }
        if (!future) {
            val rows = inDay.map { e ->
                val open = e.end == 0L
                val end = if (open) minOf(now, dayEnd) else minOf(e.end, dayEnd)
                val start = maxOf(e.start, dayStart)
                // Сон, начавшийся вечером: стор режет запись по полуночи, и
                // голова лежит во вчерашнем дне. Настоящее начало — у неё.
                val fullStart = if (e.start <= dayStart) headOf(input.entries, e, dayStart) else start
                val ms = (end - start).coerceAtLeast(0L)
                val exact = e.worth * ms.toDouble() / HOUR
                score += exact
                DayItem.Entry(
                    id = e.id, start = start, end = end, fullStart = fullStart,
                    title = e.title, category = e.category, worth = e.worth,
                    client = e.client, useful = e.useful, comment = e.comment,
                    points = if (open) null else exact.roundToInt(),
                    current = open && today,
                    gap = e.gap,
                )
            }
            val marks = input.marks.filter { it.at in dayStart until dayEnd && it.at <= now }.sortedBy { it.at }
            val pend = input.pending.filter { it.at < dayEnd }.sortedBy { it.at }
            for ((i, r) in rows.withIndex()) {
                out += r
                val last = i == rows.lastIndex
                fun owns(at: Long) = when {
                    i == 0 && at < r.start -> true
                    last -> at >= r.start
                    else -> at >= r.start && at < rows[i + 1].start
                }
                // Отметки и ждущее — одной очередью по времени: время отметки
                // видно в колонке времени, и «18:50» над «18:40» читалось бы ошибкой.
                (
                    marks.filter { owns(it.at) }.map { it.at to DayItem.Mark(r.id, it.at, it.source, it.text, it.ref) } +
                        pend.filter { owns(it.at) }.map { it.at to DayItem.Pending(r.id, it.at, it.source, it.text, it.ref) }
                    ).sortedBy { it.first }.forEach { out += it.second }
            }
            // Отметки без записей (день ещё пуст) — всё равно видны.
            if (rows.isEmpty()) {
                marks.forEach { out += DayItem.Mark(0L, it.at, it.source, it.text, it.ref) }
                pend.forEach { out += DayItem.Pending(0L, it.at, it.source, it.text, it.ref) }
            }
        }

        var nowIndex = -1
        if (today) {
            nowIndex = out.size
            out += DayItem.Now(now)
        }
        if (past) return Result(out, score.roundToInt(), -1, bedtime)

        // ---- будущее: фиксированные события, очередь дел, сон ----
        val from = if (future) dayStart + 7 * HOUR else now
        data class Fixed(val start: Long, val end: Long, val item: DayItem)
        val fixed = ArrayList<Fixed>()
        input.calendar
            .filter { it.end > from && it.start < dayEnd }
            .forEach { c -> fixed += Fixed(c.start, c.end, DayItem.Planned(c.start, c.end, c.title, workout = false, note = c.note, ref = "")) }
        val loose = ArrayList<DayItem.PlannedLoose>()
        for (w in input.workouts) {
            val s = w.start
            if (s == null) {
                loose += DayItem.PlannedLoose(w.minutes, w.title, w.note, w.ref)
            } else if (s + w.minutes * MIN > from && s < dayEnd) {
                fixed += Fixed(s, s + w.minutes * MIN, DayItem.Planned(s, s + w.minutes * MIN, w.title, workout = true, note = w.note, ref = w.ref))
            }
        }
        val queued = ArrayList<TaskIn>()
        for (t in input.tasks) {
            val at = t.fixedAt
            val min = t.estimateMin.takeIf { it > 0 } ?: input.defaultEstimateMin
            if (at != null && at >= from && at < dayEnd) {
                fixed += Fixed(at, at + min * MIN, DayItem.Task(t.id, at, min, t.title, t.second, first = false, fixed = true))
            } else {
                queued += t
            }
        }
        fixed.sortBy { it.start }

        // Очередь дел — после последнего фиксированного события вечера
        // (календарь, тренировка) + 15 минут, вверх до четверти часа; нет
        // событий — от конца текущей записи (у идущей — от «сейчас»).
        val lastEvent = fixed.filter { it.item !is DayItem.Task }.maxOfOrNull { it.end }
        val queueStart = roundUpQuarter((lastEvent ?: from) + 15 * MIN, dayStart)

        var cursor = from
        fun gapTo(t: Long) {
            if (t - cursor > FREE_MIN_MS) out += DayItem.Free(cursor, t)
        }
        for (f in fixed) {
            gapTo(f.start)
            out += f.item
            cursor = maxOf(cursor, f.end)
        }
        loose.forEach { out += it }

        var queueEnd = cursor
        if (queued.isNotEmpty()) {
            val start = maxOf(queueStart, roundUpQuarter(cursor, dayStart))
            gapTo(start)
            val total = queued.sumOf { it.estimateMin.takeIf { m -> m > 0 } ?: input.defaultEstimateMin }
            out += DayItem.TaskGroup(queued.size, total)
            var t = start
            queued.forEachIndexed { i, q ->
                val min = q.estimateMin.takeIf { it > 0 } ?: input.defaultEstimateMin
                out += DayItem.Task(q.id, t, min, q.title, q.second, first = i == 0, fixed = false)
                t += min * MIN
            }
            queueEnd = t
            cursor = t
        }

        if (bedtime > from || queued.isNotEmpty()) {
            val overflow = if (queued.isEmpty()) 0 else ((queueEnd - bedtime) / MIN).toInt().coerceAtLeast(0)
            if (bedtime > cursor) gapTo(bedtime)
            out += DayItem.Sleep(bedtime, overflow)
        }
        return Result(out, score.roundToInt(), nowIndex, bedtime)
    }

    /** Окно короче — не «свободно»: 15 минут перед очередью дел — запас, а не время. */
    private const val FREE_MIN_MS = 15 * MIN

    /**
     * Вверх до :00 / :15 / :30 / :45 местного времени — от начала дня
     * [dayStart], а не от эпохи: у пояса со сдвигом не в четверть часа
     * четверти эпохи не совпадают с часами на стене.
     */
    fun roundUpQuarter(t: Long, dayStart: Long = 0L): Long {
        val q = 15 * MIN
        val r = Math.floorMod(t - dayStart, q)
        return if (r == 0L) t else t - r + q
    }

    /**
     * Голова записи, разрезанной по полуночи: та же категория, кончилась ровно
     * в начале дня — её начало и есть настоящее (`ZasechkaStore.splitMidnightLocked`).
     */
    private fun headOf(all: List<EntryIn>, e: EntryIn, dayStart: Long): Long {
        if (e.start < dayStart) return e.start
        val head = all.firstOrNull {
            it.id != e.id && it.end != 0L && kotlin.math.abs(it.end - dayStart) < MIN &&
                it.category.equals(e.category, ignoreCase = true) && it.start < dayStart
        }
        return head?.start ?: e.start
    }
}
