package ru.zf.slushalka.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/**
 * Сводки из журнала подходов. Чистый счёт, без Андроида: сессии на входе,
 * числа на выходе, и всё это можно пересчитать задним числом по любому новому
 * правилу - журнал хранит подходы, а не готовые итоги.
 */
object Stats {

    /**
     * Читательские сутки начинаются в четыре утра. Книгу слушают, засыпая, и
     * полчаса после полуночи - это ещё вечер, а не завтрашний день: иначе «сегодня»
     * в семь утра показывало бы ночной хвост, а вчерашний вечер терял бы его.
     */
    const val DAY_START_HOUR = 4

    /** Меньше минуты за день - день не в счёт: серия и «дней с книгой» смотрят на это. */
    const val DAY_COUNTS_MS = 60_000L

    /** Итоги за что угодно: день, час суток, книгу, всё время. */
    class Totals {
        var listenMs = 0L
        var readMs = 0L
        var aloudMs = 0L
        /** Сколько записи прошло - с поправкой на скорость. */
        var coveredMs = 0L
        /** Сколько знаков прочитано глазами и озвучкой. */
        var chars = 0L
        var readChars = 0L

        val activeMs: Long get() = listenMs + readMs + aloudMs
        val textMs: Long get() = readMs + aloudMs
        val pages: Double get() = chars / Settings.PAGE_CHARS.toDouble()
        val isEmpty: Boolean get() = activeMs == 0L && chars == 0L && coveredMs == 0L

        fun add(s: Session, frac: Double = 1.0) {
            val a = (s.activeMs * frac).toLong()
            when (s.mode) {
                Mode.LISTEN -> {
                    listenMs += a
                    coveredMs += (s.coveredMs * frac).toLong()
                }
                Mode.READ -> {
                    readMs += a
                    chars += (s.chars * frac).toLong()
                    readChars += (s.chars * frac).toLong()
                }
                Mode.ALOUD -> {
                    aloudMs += a
                    chars += (s.chars * frac).toLong()
                }
            }
        }

        fun add(o: Totals) {
            listenMs += o.listenMs
            readMs += o.readMs
            aloudMs += o.aloudMs
            coveredMs += o.coveredMs
            chars += o.chars
            readChars += o.readChars
        }

        /** Знаков в минуту глазами; меньше десяти минут чтения - оценке верить нельзя. */
        fun readCpm(minMs: Long = 10 * 60_000L): Double? =
            if (readMs >= minMs && readChars > 0) readChars / (readMs / 60_000.0) else null

        /** Во сколько раз запись шла быстрее часов: 1,0 - как есть, 1,5 - полтора часа книги за час. */
        fun listenRate(minMs: Long = 10 * 60_000L): Double? =
            if (listenMs >= minMs && coveredMs > 0) coveredMs.toDouble() / listenMs else null
    }

    /** Столбик графика: подпись, итоги и признак «это текущий период». */
    data class Bucket(val label: String, val totals: Totals, val current: Boolean)

    data class BookStat(
        val bookId: String,
        val totals: Totals,
        val last30: Totals,
        /** Подходов всего. */
        val sessions: Int,
        /** Дней, когда книгу открывали, и сколько дней прошло от первого до последнего. */
        val days: Int,
        val spanDays: Int,
        val firstAt: Long,
        val lastAt: Long,
        /** Час суток, на который приходится больше всего времени с этой книгой. */
        val usualHour: Int?,
        val longestMs: Long,
    )

    class Report(
        val sessions: List<Session>,
        val zone: ZoneId,
        val now: Long,
        /** Итоги по читательским суткам - только дни, когда что-то было. */
        val days: TreeMap<LocalDate, Totals>,
        val today: LocalDate,
        val all: Totals,
        /** По часу суток и по дню недели (0 - понедельник). */
        val hours: List<Totals>,
        val weekdays: List<Totals>,
        val books: List<BookStat>,
        val streak: Int,
        val bestStreak: Int,
        val medianSessionMs: Long,
        val longest: Session?,
        val sinceAt: Long,
    ) {
        /** Итоги за [length] дней, кончая днём [back] дней назад: window(0, 7) - последняя неделя. */
        fun window(back: Int, length: Int): Totals {
            val out = Totals()
            val end = today.minusDays(back.toLong())
            val start = end.minusDays((length - 1).toLong())
            for ((d, t) in days.subMap(start, true, end, true)) out.add(t)
            return out
        }

        fun day(d: LocalDate): Totals = days[d] ?: Totals()

        val todayTotals: Totals get() = day(today)
        val yesterday: Totals get() = day(today.minusDays(1))
        val last7: Totals get() = window(0, 7)
        val prev7: Totals get() = window(7, 7)
        val last30: Totals get() = window(0, 30)
        val prev30: Totals get() = window(30, 30)

        /** Дней с книгой за всё время. */
        val activeDays: Int get() = days.values.count { it.activeMs >= DAY_COUNTS_MS }

        /** Темп чтения глазами: по последнему месяцу, а если его мало - по всему. */
        val readCpm: Double? get() = last30.readCpm() ?: all.readCpm()

        /** Средняя скорость слушания за месяц (или за всё время). */
        val listenRate: Double? get() = last30.listenRate() ?: all.listenRate()

        fun lastDays(n: Int): List<Bucket> = (n - 1 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            Bucket(d.dayOfMonth.toString(), day(d), i == 0)
        }

        fun lastWeeks(n: Int): List<Bucket> {
            val monday = today.with(DayOfWeek.MONDAY)
            return (n - 1 downTo 0).map { i ->
                val start = monday.minusWeeks(i.toLong())
                val t = Totals()
                for ((_, v) in days.subMap(start, true, start.plusDays(6), true)) t.add(v)
                Bucket("%d.%02d".format(start.dayOfMonth, start.monthValue), t, i == 0)
            }
        }

        fun lastMonths(n: Int): List<Bucket> {
            val thisMonth = YearMonth.from(today)
            return (n - 1 downTo 0).map { i ->
                val m = thisMonth.minusMonths(i.toLong())
                val t = Totals()
                for ((_, v) in days.subMap(m.atDay(1), true, m.atEndOfMonth(), true)) t.add(v)
                Bucket(MONTHS[m.monthValue - 1], t, i == 0)
            }
        }

        /** Час, на который приходится больше всего времени; null - данных мало. */
        val peakHour: Int? get() = peakOf(hours)

        fun book(bookId: String): BookStat? = books.firstOrNull { it.bookId == bookId }
    }

    private val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    // ------------------------------------------------------------------ счёт

    fun report(
        sessions: List<Session>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Report {
        val days = TreeMap<LocalDate, Totals>()
        val hours = List(24) { Totals() }
        val weekdays = List(7) { Totals() }
        val all = Totals()
        val byBook = HashMap<String, MutableList<Session>>()

        for (s in sessions) {
            all.add(s)
            byBook.getOrPut(s.bookId) { ArrayList() }.add(s)
            spread(s, zone) { z, frac ->
                val day = readingDay(z)
                days.getOrPut(day) { Totals() }.add(s, frac)
                hours[z.hour].add(s, frac)
                weekdays[day.dayOfWeek.value - 1].add(s, frac)
            }
        }

        val today = readingDay(Instant.ofEpochMilli(now).atZone(zone))
        val books = byBook.map { (id, list) -> bookStat(id, list, today, zone) }
            .sortedByDescending { it.lastAt }

        val closedActive = sessions.map { it.activeMs }.filter { it > 0 }.sorted()
        val median = if (closedActive.isEmpty()) 0L else closedActive[closedActive.size / 2]

        return Report(
            sessions = sessions,
            zone = zone,
            now = now,
            days = days,
            today = today,
            all = all,
            hours = hours,
            weekdays = weekdays,
            books = books,
            streak = streak(days, today),
            bestStreak = bestStreak(days),
            medianSessionMs = median,
            longest = sessions.maxByOrNull { it.activeMs },
            sinceAt = sessions.minOfOrNull { it.startAt } ?: 0L,
        )
    }

    private fun bookStat(id: String, list: List<Session>, today: LocalDate, zone: ZoneId): BookStat {
        val totals = Totals()
        val last30 = Totals()
        val hours = LongArray(24)
        val dayset = HashSet<LocalDate>()
        val from30 = today.minusDays(29)
        for (s in list) {
            totals.add(s)
            spread(s, zone) { z, frac ->
                val d = readingDay(z)
                dayset.add(d)
                hours[z.hour] += (s.activeMs * frac).toLong()
                if (!d.isBefore(from30)) last30.add(s, frac)
            }
        }
        val first = dayset.minOrNull()
        val last = dayset.maxOrNull()
        val span = if (first != null && last != null) ChronoUnit.DAYS.between(first, last).toInt() + 1 else 0
        val peak = hours.indices.maxByOrNull { hours[it] }
        return BookStat(
            bookId = id,
            totals = totals,
            last30 = last30,
            sessions = list.size,
            days = dayset.size,
            spanDays = span,
            firstAt = list.minOf { it.startAt },
            lastAt = list.maxOf { it.endAt },
            usualHour = peak?.takeIf { totals.activeMs >= 20 * 60_000L && hours[it] > 0 },
            longestMs = list.maxOf { it.activeMs },
        )
    }

    /**
     * Подход раскладывается по часам, через границы которых он прошёл:
     * вечер с 23:20 до 00:40 - это сорок минут одному часу и сорок другому, а
     * не всё в 23:00. Доля активного времени делится пропорционально стенкам.
     */
    private inline fun spread(s: Session, zone: ZoneId, sink: (ZonedDateTime, Double) -> Unit) {
        val span = s.spanMs
        if (span <= 0) {
            sink(Instant.ofEpochMilli(s.startAt).atZone(zone), 1.0)
            return
        }
        var t = s.startAt
        while (t < s.endAt) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val segEnd = minOf(hourEnd, s.endAt)
            sink(z, (segEnd - t).toDouble() / span)
            t = segEnd
        }
    }

    /** Читательские сутки: до четырёх утра - ещё вчера. */
    fun readingDay(z: ZonedDateTime): LocalDate = z.minusHours(DAY_START_HOUR.toLong()).toLocalDate()

    fun readingDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        readingDay(Instant.ofEpochMilli(at).atZone(zone))

    /**
     * Дней подряд с книгой, считая назад от сегодня. Если сегодня ещё ничего не
     * было, серия не сгорела - день не кончился; считаем от вчера.
     */
    private fun streak(days: TreeMap<LocalDate, Totals>, today: LocalDate): Int {
        var d = today
        if ((days[d]?.activeMs ?: 0L) < DAY_COUNTS_MS) d = d.minusDays(1)
        var n = 0
        while ((days[d]?.activeMs ?: 0L) >= DAY_COUNTS_MS) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    private fun bestStreak(days: TreeMap<LocalDate, Totals>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for ((d, t) in days) {
            if (t.activeMs < DAY_COUNTS_MS) continue
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    private fun peakOf(list: List<Totals>): Int? {
        val total = list.sumOf { it.activeMs }
        if (total < 30 * 60_000L) return null
        return list.indices.maxByOrNull { list[it].activeMs }
    }

    // --------------------------------------------------------------- прогноз

    /** Когда книга кончится при таком темпе. [days] - null, если темпа ещё нет. */
    data class Forecast(val realLeftMs: Long, val days: Int?, val date: LocalDate?)

    /**
     * Аудиокнига: остаток записи делится на скорость слушания (средняя за месяц,
     * а пока её нет - выставленная в плеере) - это часы у наушников; часы делятся
     * на средний дневной расход за две недели - это дни.
     */
    fun forecastAudio(
        leftMs: Long,
        rate: Double?,
        fallbackSpeed: Float,
        dailyListenMs: Long,
        today: LocalDate,
    ): Forecast {
        val speed = rate ?: fallbackSpeed.toDouble().coerceAtLeast(0.5)
        val real = (leftMs / speed).toLong().coerceAtLeast(0)
        return finish(real, dailyListenMs, today)
    }

    /** Книга без записи: остаток знаков через темп чтения глазами. */
    fun forecastText(leftChars: Long, cpm: Double?, dailyReadMs: Long, today: LocalDate): Forecast? {
        val speed = cpm ?: return null
        val real = (leftChars / speed * 60_000).toLong().coerceAtLeast(0)
        return finish(real, dailyReadMs, today)
    }

    private fun finish(realLeftMs: Long, dailyMs: Long, today: LocalDate): Forecast {
        // Меньше пяти минут в день в среднем - это не темп, а случайность.
        if (dailyMs < 5 * 60_000L) return Forecast(realLeftMs, null, null)
        val d = Math.ceil(realLeftMs.toDouble() / dailyMs).toInt().coerceAtLeast(if (realLeftMs > 0) 1 else 0)
        return Forecast(realLeftMs, d, today.plusDays(d.toLong()))
    }
}
