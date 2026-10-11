package ru.zf.pravka.core

import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.ZasechkaStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

// Общая статистика, издание 11.10.2026: «Куда уходит день». Владелец принёс
// разбор, который Claude собрал по архиву за 43 дня (средний будний день по
// часам, окно 10–17, шесть недель качелями, мастерская против работы, план
// тренировок против факта, еда на одном рецепте, деньги по месяцам, телефон),
// и сказал: «я никогда не заглядывал в нашу статистику… давай уберём нашу, а
// поставим ровно такую». Здесь — все числа того разбора, только живые: из
// ленты, телефона, intervals, еды и денег на этом телефоне, за выбранный
// отрезок. Модель к счёту не подходит; вкладка (`ReportTab.kt`) и разворот
// «Сегодня» (средний день под циферблатом) только рисуют.
//
// Чистый Kotlin, без Android: проверяет `LifeStatsTest`.
object LifeStats {

    const val DAY_MIN = 1440.0

    /** Сутки идут в счёт, если лента закрыла хотя бы 20 часов: иначе её в тот день не вели. */
    const val FULL_DAY_MIN = 1200.0

    /** Окно разбора: будни с 10 до 17 — там, по отчёту, живёт утечка. */
    const val WINDOW_FROM_H = 10
    const val WINDOW_TO_H = 17

    /** «Выход из дома» — поездка или проход, начатые с 10 до 16. */
    const val OUT_FROM_H = 10
    const val OUT_TO_H = 16

    // ---------------------------------------------------------------------
    // Группы — те же десять, что в отчёте, и в том же порядке стопки снизу вверх
    // ---------------------------------------------------------------------

    enum class Group(val label: String) {
        SLEEP("сон"),
        LIFE("быт, еда, дороги"),
        FAMILY("семья"),
        WORK("работа"),
        SYS("мастерская"),
        SPORT("спорт"),
        OTHER("прочее"),
        LOSS("потери и отдых"),
        GAP("не размечено"),
        SOLO("соло"),
    }

    /**
     * Группа по категории ленты. Имена — владельца (`ZasechkaStore.DEFAULT_CATEGORIES`):
     * «Систематизация» — мастерская (Правка, Засечка, VPN), «Секс: соло» — соло,
     * «Отдых» стоит рядом с «Потерями», как в отчёте. Свои категории без явного
     * слова («Чтение», «Звонки», «Социальное») — прочее.
     */
    fun groupOf(category: String): Group {
        val c = category.trim().lowercase()
        return when {
            c.isEmpty() -> Group.OTHER
            c == "не размечено" -> Group.GAP
            c.startsWith("сон") -> Group.SLEEP
            c.contains("соло") -> Group.SOLO
            c.startsWith("секс") -> Group.FAMILY
            c.startsWith("работа") || c.startsWith("зф") -> Group.WORK
            c.startsWith("систематиз") -> Group.SYS
            c.startsWith("спорт") -> Group.SPORT
            c.startsWith("семья") || c.startsWith("дети") -> Group.FAMILY
            c.startsWith("еда") || c.startsWith("быт") || c.startsWith("передвиж") -> Group.LIFE
            c.startsWith("потер") || c.startsWith("отдых") || c.contains("залип") -> Group.LOSS
            else -> Group.OTHER
        }
    }

    /** Время с Марианной: её категория секса и «Семья», где она названа в деле. */
    fun withMarianna(category: String, title: String): Boolean {
        val c = category.trim().lowercase()
        if (c.startsWith("секс") && c.contains("мариан")) return true
        return groupOf(category) == Group.FAMILY && title.lowercase().contains("мариан")
    }

    private fun isMove(category: String) = category.trim().lowercase().startsWith("передвиж")
    private fun isMeeting(category: String): Boolean {
        val c = category.trim().lowercase()
        return c.startsWith("работа: звон") || c.startsWith("работа: встреч")
    }

    // ---------------------------------------------------------------------
    // Лента по суткам
    // ---------------------------------------------------------------------

    /** Кусок записи внутри суток: минуты от полуночи, [from, to). */
    class Piece(
        val from: Double,
        val to: Double,
        val group: Group,
        val category: String,
        val title: String,
        val source: String,
        val entryId: Long,
        /** Запись началась в этих сутках (а не перешла из вчера). */
        val startsHere: Boolean,
        /** Вся длина записи, минуты, — для «среднего блока». */
        val fullMin: Double,
    ) {
        val minutes: Double get() = to - from
        fun overlap(a: Double, b: Double): Double = (min(to, b) - max(from, a)).coerceAtLeast(0.0)

        /** Тот же кусок, начатый позже: его начало заняла запись раньше. */
        fun from(a: Double): Piece = Piece(a, to, group, category, title, source, entryId, startsHere, fullMin)
    }

    /**
     * Сутки без наложений: кто начался раньше, тот и занял время. В ленте
     * записи не накладываются (сумма суток — 1440), но врезка, сон с часов
     * поверх сна из ленты или правка руками иногда оставляют нахлёст — и
     * час набирал больше шестидесяти минут, столбик вылезал из графика.
     */
    private fun flatten(list: List<Piece>): List<Piece> {
        val out = ArrayList<Piece>(list.size)
        var cursor = 0.0
        for (p in list.sortedWith(compareBy<Piece>({ it.from }, { -it.to }))) {
            val a = max(p.from, cursor)
            if (p.to - a < 1e-6) continue
            out += if (a == p.from) p else p.from(a)
            cursor = max(cursor, p.to)
        }
        return out
    }

    class DayRibbon(val date: LocalDate, val pieces: List<Piece>) {
        val coveredMin: Double = pieces.sumOf { it.minutes }.coerceAtMost(DAY_MIN)
        val full: Boolean get() = coveredMin >= FULL_DAY_MIN

        /** Минуты группы за сутки; «не размечено» включает и время без записи. */
        fun minutes(g: Group): Double {
            val own = pieces.filter { it.group == g }.sumOf { it.minutes }
            return if (g == Group.GAP) own + (DAY_MIN - coveredMin).coerceAtLeast(0.0) else own
        }

        fun minutesIn(g: Group, a: Double, b: Double): Double {
            val own = pieces.filter { it.group == g }.sumOf { it.overlap(a, b) }
            if (g != Group.GAP) return own
            val covered = pieces.sumOf { it.overlap(a, b) }
            return own + ((b - a) - covered).coerceAtLeast(0.0)
        }

        fun marianna(): Double = pieces.filter { withMarianna(it.category, it.title) }.sumOf { it.minutes }
    }

    /**
     * Лента, разложенная по суткам [from]..[to] включительно. Идущая запись
     * тянется до [now]; кусок за полночью отходит своим суткам.
     */
    fun ribbon(
        entries: List<ZasechkaStore.Entry>,
        from: LocalDate,
        to: LocalDate,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, DayRibbon> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pieces = HashMap<LocalDate, MutableList<Piece>>()
        for (e in entries) {
            val eEnd = if (e.open) now else e.end
            if (eEnd <= start || e.start >= end || eEnd <= e.start) continue
            val group = groupOf(e.category)
            val fullMin = (eEnd - e.start) / 60_000.0
            var day = Instant.ofEpochMilli(maxOf(e.start, start)).atZone(zone).toLocalDate()
            while (!day.isAfter(to)) {
                val ds = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val de = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (ds >= eEnd) break
                val a = maxOf(e.start, ds)
                val b = minOf(eEnd, de)
                if (b > a) {
                    pieces.getOrPut(day) { ArrayList() } += Piece(
                        from = (a - ds) / 60_000.0,
                        to = ((b - ds) / 60_000.0).coerceAtMost(DAY_MIN),
                        group = group,
                        category = e.category,
                        title = e.title,
                        source = e.source,
                        entryId = e.id,
                        startsHere = e.start >= ds,
                        fullMin = fullMin,
                    )
                }
                day = day.plusDays(1)
            }
        }
        val out = LinkedHashMap<LocalDate, DayRibbon>()
        var d = from
        while (!d.isAfter(to)) {
            out[d] = DayRibbon(d, flatten(pieces[d].orEmpty()))
            d = d.plusDays(1)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Какие дни: все, будни, выходные или один день недели
    // ---------------------------------------------------------------------

    enum class DayPick(val chip: String, val many: String) {
        ALL("Все", "все дни"),
        WEEKDAYS("Будни", "будни"),
        WEEKENDS("Выходные", "выходные"),
        MON("Пн", "понедельники"),
        TUE("Вт", "вторники"),
        WED("Ср", "среды"),
        THU("Чт", "четверги"),
        FRI("Пт", "пятницы"),
        SAT("Сб", "субботы"),
        SUN("Вс", "воскресенья"),
        ;

        fun accepts(d: LocalDate): Boolean = when (this) {
            ALL -> true
            WEEKDAYS -> d.dayOfWeek.value <= 5
            WEEKENDS -> d.dayOfWeek.value >= 6
            else -> d.dayOfWeek == DayOfWeek.of(ordinal - MON.ordinal + 1)
        }

        companion object {
            /** День недели [d] — тем же выбором («только понедельники»). */
            fun of(d: LocalDate): DayPick = entries[MON.ordinal + d.dayOfWeek.value - 1]
        }
    }

    // ---------------------------------------------------------------------
    // Средний день по часам
    // ---------------------------------------------------------------------

    /** Средние минуты каждой группы в каждом из 24 часов; [days] — сколько суток в среднем. */
    class HourProfile(val days: Int, val hours: List<Map<Group, Double>>) {
        val empty: Boolean get() = days == 0
    }

    fun hourProfile(days: Map<LocalDate, DayRibbon>, pick: DayPick): HourProfile {
        val chosen = days.values.filter { it.full && pick.accepts(it.date) }
        if (chosen.isEmpty()) return HourProfile(0, List(24) { emptyMap() })
        val sums = Array(24) { DoubleArray(Group.entries.size) }
        for (d in chosen) {
            for (h in 0 until 24) {
                val a = h * 60.0
                val b = a + 60.0
                var covered = 0.0
                for (p in d.pieces) {
                    val o = p.overlap(a, b)
                    if (o <= 0.0) continue
                    sums[h][p.group.ordinal] += o
                    covered += o
                }
                if (covered < 60.0) sums[h][Group.GAP.ordinal] += 60.0 - covered
            }
        }
        val n = chosen.size.toDouble()
        val hours = sums.map { row ->
            Group.entries.mapNotNull { g -> (row[g.ordinal] / n).takeIf { it > 0.0 }?.let { g to it } }.toMap()
        }
        return HourProfile(chosen.size, hours)
    }

    // ---------------------------------------------------------------------
    // Средние сутки
    // ---------------------------------------------------------------------

    /** Строка «средних суток»: подпись, часы в среднем, группа для цвета. */
    class DayLine(val label: String, val hours: Double, val color: Group)

    fun averageDay(days: Map<LocalDate, DayRibbon>, pick: DayPick = DayPick.ALL): List<DayLine> {
        val full = days.values.filter { it.full && pick.accepts(it.date) }
        if (full.isEmpty()) return emptyList()
        val n = full.size.toDouble()
        fun avg(vararg gs: Group) = full.sumOf { d -> gs.sumOf { d.minutes(it) } } / n / 60.0
        return listOf(
            DayLine("Сон", avg(Group.SLEEP), Group.SLEEP),
            DayLine("Быт, еда, дороги", avg(Group.LIFE), Group.LIFE),
            DayLine("Семья", avg(Group.FAMILY), Group.FAMILY),
            DayLine("Работа", avg(Group.WORK), Group.WORK),
            DayLine("Мастерская", avg(Group.SYS), Group.SYS),
            DayLine("Утечка: потери, отдых, соло, дыры", avg(Group.LOSS, Group.SOLO, Group.GAP), Group.LOSS),
            DayLine("Спорт и прочее", avg(Group.SPORT, Group.OTHER), Group.SPORT),
        )
    }

    // ---------------------------------------------------------------------
    // Окно будней 10–17 и календарь будних дней
    // ---------------------------------------------------------------------

    class WindowRow(val label: String, val minutes: Double, val color: Group)

    class WeekdayMark(
        val date: LocalDate,
        val solo: Boolean,
        /** Выходил из дома: поездка или проход, начатые с 10 до 16. */
        val out: Boolean,
        val meetings: Int,
        val bjj: Boolean,
        val workMin: Int,
    )

    class WindowStats(
        val days: Int,
        /** Средние минуты окна по статьям, крупные сверху. */
        val rows: List<WindowRow>,
        val marks: List<WeekdayMark>,
    ) {
        val home: List<WeekdayMark> get() = marks.filter { !it.out }
        val outside: List<WeekdayMark> get() = marks.filter { it.out }
        val bjjDays: List<WeekdayMark> get() = marks.filter { it.bjj }
    }

    fun window(days: Map<LocalDate, DayRibbon>, bjjDates: Set<LocalDate>): WindowStats {
        val weekdays = days.values.filter { it.full && it.date.dayOfWeek.value <= 5 }
        val a = WINDOW_FROM_H * 60.0
        val b = WINDOW_TO_H * 60.0
        val n = weekdays.size.toDouble().coerceAtLeast(1.0)
        // Быт делится на дороги, еду и быт — в окне это разные истории.
        val sums = LinkedHashMap<String, Pair<Double, Group>>()
        fun add(label: String, g: Group, v: Double) {
            val old = sums[label]?.first ?: 0.0
            sums[label] = (old + v) to g
        }
        for (d in weekdays) {
            var covered = 0.0
            for (p in d.pieces) {
                val o = p.overlap(a, b)
                if (o <= 0.0) continue
                covered += o
                val c = p.category.trim().lowercase()
                when {
                    p.group == Group.LIFE && c.startsWith("передвиж") -> add("дороги", Group.LIFE, o)
                    p.group == Group.LIFE && c.startsWith("еда") -> add("еда", Group.LIFE, o)
                    p.group == Group.LIFE -> add("быт", Group.LIFE, o)
                    else -> add(p.group.label, p.group, o)
                }
            }
            if (covered < b - a) add(Group.GAP.label, Group.GAP, (b - a) - covered)
        }
        val rows = sums.map { (label, v) -> WindowRow(label, v.first / n, v.second) }
            .filter { it.minutes >= 0.5 }
            .sortedByDescending { it.minutes }
        val marks = weekdays.map { d ->
            WeekdayMark(
                date = d.date,
                solo = d.pieces.any { it.group == Group.SOLO },
                out = d.pieces.any { isMove(it.category) && it.startsHere && it.from >= OUT_FROM_H * 60.0 && it.from < OUT_TO_H * 60.0 },
                meetings = meetingBlocks(d).size,
                bjj = d.date in bjjDates,
                workMin = d.minutes(Group.WORK).toInt(),
            )
        }
        return WindowStats(weekdays.size, rows, marks)
    }

    /** Встречи дня: куски «Работа: звонки» одним блоком (то же дело с разрывом до пяти минут), от 10 минут. */
    fun meetingBlocks(d: DayRibbon): List<Pair<Double, Double>> {
        val out = ArrayList<Triple<Double, Double, String>>()
        for (p in d.pieces.filter { isMeeting(it.category) }.sortedBy { it.from }) {
            val last = out.lastOrNull()
            if (last != null && last.third == p.title && p.from - last.second <= 5.0) {
                out[out.lastIndex] = Triple(last.first, max(last.second, p.to), last.third)
            } else {
                out += Triple(p.from, p.to, p.title)
            }
        }
        return out.filter { it.second - it.first >= 10.0 }.map { it.first to it.second }
    }

    // ---------------------------------------------------------------------
    // Недели
    // ---------------------------------------------------------------------

    /** Сутки здоровья с часов (intervals). */
    class HealthIn(val date: LocalDate, val sleepH: Double, val rhr: Int, val steps: Int, val weightKg: Double)

    enum class Line(val label: String, val color: Group) {
        WORK("Работа", Group.WORK),
        SYS("Мастерская", Group.SYS),
        SOLO("Соло", Group.SOLO),
        LOSS("Потери и отдых", Group.LOSS),
        FAMILY("Семья", Group.FAMILY),
        MARIANNA("С Марианной", Group.OTHER),
    }

    class Week(
        val monday: LocalDate,
        /** Полных суток ленты в этой неделе внутри отрезка. */
        val days: Int,
        /** Минут в день по линиям. */
        val perDay: Map<Line, Double>,
        val meetings: Int,
        val entriesPerDay: Double,
        val avgBlockMin: Double,
        /** Доля «не размечено» в сутках, 0..1. */
        val gapShare: Double,
        val sleepH: Double?,
        val shortNights: Int,
        val nights: Int,
        val rhr: Double?,
        val steps: Double?,
    )

    fun weeks(days: Map<LocalDate, DayRibbon>, health: List<HealthIn>, from: LocalDate, to: LocalDate): List<Week> {
        val healthBy = health.associateBy { it.date }
        val out = ArrayList<Week>()
        var monday = from.minusDays((from.dayOfWeek.value - 1).toLong())
        while (!monday.isAfter(to)) {
            val range = (0L..6L).map { monday.plusDays(it) }.filter { !it.isBefore(from) && !it.isAfter(to) }
            val full = range.mapNotNull { days[it] }.filter { it.full }
            val n = full.size.toDouble()
            val perDay = Line.entries.associateWith { line ->
                if (n == 0.0) 0.0 else full.sumOf { d ->
                    when (line) {
                        Line.WORK -> d.minutes(Group.WORK)
                        Line.SYS -> d.minutes(Group.SYS)
                        Line.SOLO -> d.minutes(Group.SOLO)
                        Line.LOSS -> d.minutes(Group.LOSS)
                        Line.FAMILY -> d.minutes(Group.FAMILY)
                        Line.MARIANNA -> d.marianna()
                    }
                } / n
            }
            val own = full.flatMap { d -> d.pieces.filter { it.startsHere && isOwn(it) } }.distinctBy { it.entryId }
            val hs = range.mapNotNull { healthBy[it] }
            val sleeps = hs.map { it.sleepH }.filter { it > 0.0 }
            val rhrs = hs.map { it.rhr }.filter { it > 0 }
            val steps = hs.map { it.steps }.filter { it > 0 }
            out += Week(
                monday = monday,
                days = full.size,
                perDay = perDay,
                meetings = full.sumOf { meetingBlocks(it).size },
                entriesPerDay = if (n == 0.0) 0.0 else own.size / n,
                avgBlockMin = if (own.isEmpty()) 0.0 else own.sumOf { it.fullMin } / own.size,
                gapShare = if (n == 0.0) 0.0 else full.sumOf { it.minutes(Group.GAP) } / (n * DAY_MIN),
                sleepH = sleeps.takeIf { it.isNotEmpty() }?.average(),
                shortNights = sleeps.count { it < 6.0 },
                nights = sleeps.size,
                rhr = rhrs.takeIf { it.isNotEmpty() }?.average(),
                steps = steps.takeIf { it.isNotEmpty() }?.average(),
            )
            monday = monday.plusDays(7)
        }
        return out
    }

    /** Запись владельца: сказанная или поправленная, не сон и не заполнитель. */
    private fun isOwn(p: Piece): Boolean =
        p.source != "gap" && p.source != "auto" && p.group != Group.SLEEP && p.group != Group.GAP

    // ---------------------------------------------------------------------
    // Работа и мастерская накопительно
    // ---------------------------------------------------------------------

    class Cumulative(val dates: List<LocalDate>, val workDay: List<Double>, val sysDay: List<Double>) {
        val workHours: List<Double> get() = running(workDay)
        val sysHours: List<Double> get() = running(sysDay)
        private fun running(v: List<Double>): List<Double> {
            var acc = 0.0
            return v.map { acc += it; acc / 60.0 }
        }
    }

    fun cumulative(days: Map<LocalDate, DayRibbon>): Cumulative {
        val list = days.values.sortedBy { it.date }
        return Cumulative(list.map { it.date }, list.map { it.minutes(Group.WORK) }, list.map { it.minutes(Group.SYS) })
    }

    /** Минуты категорий за отрезок, по категориям ленты (для «привлечение — 1 ч 43 м»). */
    fun categoryMinutes(days: Map<LocalDate, DayRibbon>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (d in days.values) for (p in d.pieces) out[p.category] = (out[p.category] ?: 0.0) + p.minutes
        return out
    }

    // ---------------------------------------------------------------------
    // Спорт: план против факта
    // ---------------------------------------------------------------------

    class PlanIn(val date: LocalDate, val name: String, val type: String, val charger: Boolean, val strength: Boolean)

    class WorkoutIn(
        val date: LocalDate,
        val type: String,
        val name: String,
        val minutes: Int,
        val load: Int,
        val hr: Int,
        val km: Double,
        val paceSec: Int,
        val rpe: Int,
    ) {
        val bjj: Boolean get() = isBjj(name) || type.contains("martial", true)
    }

    class GtgIn(val date: LocalDate, val charged: Boolean, val hangSec: Int)
    class StrengthIn(val date: LocalDate, val block: String)

    enum class SportKind(val label: String) {
        CHARGE("Зарядка"),
        STRENGTH("Силовая и турник"),
        RIDE("Zwift и вело"),
        RUN("Бег"),
        BJJ("BJJ по плану"),
        OTHER("Прочее"),
    }

    fun isBjj(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("bjj") || n.contains("борьб") || n.contains("джиу")
    }

    fun kindOf(p: PlanIn): SportKind? {
        val t = p.type.lowercase()
        return when {
            t == "note" -> null
            isBjj(p.name) -> SportKind.BJJ
            p.charger -> SportKind.CHARGE
            p.strength -> SportKind.STRENGTH
            t.contains("run") -> SportKind.RUN
            t.contains("ride") -> SportKind.RIDE
            else -> SportKind.OTHER
        }
    }

    class PlanRow(val kind: SportKind, val planned: Int, val done: Int)

    class PlanFact(val rows: List<PlanRow>, val bjjUnplanned: Int) {
        val planned: Int get() = rows.sumOf { it.planned }
        val done: Int get() = rows.sumOf { it.done }
        val share: Double? get() = if (planned == 0) null else done.toDouble() / planned
    }

    fun planFact(
        plan: List<PlanIn>,
        workouts: List<WorkoutIn>,
        gtg: List<GtgIn>,
        strength: List<StrengthIn>,
        from: LocalDate,
        to: LocalDate,
    ): PlanFact {
        val inRange = plan.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val wByDay = workouts.groupBy { it.date }
        val charged = gtg.filter { it.charged }.map { it.date }.toSet()
        val strengthDays = strength.filter { !it.block.contains("заряд", true) }.map { it.date }.toSet()
        fun done(k: SportKind, d: LocalDate): Boolean {
            val ws = wByDay[d].orEmpty()
            return when (k) {
                SportKind.CHARGE -> d in charged || ws.any { it.type.contains("weight", true) && it.name.contains("заряд", true) }
                SportKind.STRENGTH -> d in strengthDays ||
                    ws.any { it.type.contains("weight", true) && !it.name.contains("заряд", true) && it.minutes >= 10 }
                SportKind.RUN -> ws.any { it.type.contains("run", true) }
                SportKind.RIDE -> ws.any { it.type.contains("virtual", true) || it.name.contains("zwift", true) }
                SportKind.BJJ -> ws.any { it.bjj }
                SportKind.OTHER -> ws.isNotEmpty()
            }
        }
        val counts = LinkedHashMap<SportKind, IntArray>()
        for (k in SportKind.entries) counts[k] = IntArray(2)
        // Дважды в один день одно и то же — сделано столько раз, сколько было.
        for ((key, list) in inRange.groupBy { kindOf(it) to it.date }) {
            val k = key.first ?: continue
            val c = counts.getValue(k)
            c[0] += list.size
            if (done(k, key.second)) c[1] += list.size
        }
        val bjjPlanned = inRange.filter { kindOf(it) == SportKind.BJJ }.map { it.date }.toSet()
        val bjjUnplanned = workouts.count {
            it.bjj && !it.date.isBefore(from) && !it.date.isAfter(to) && it.date !in bjjPlanned
        }
        return PlanFact(
            counts.filter { it.value[0] > 0 }.map { (k, c) -> PlanRow(k, c[0], c[1]) },
            bjjUnplanned,
        )
    }

    // ---------------------------------------------------------------------
    // Еда
    // ---------------------------------------------------------------------

    class FoodDay(val date: LocalDate, val kcal: Int, val protein: Int, val fat: Int, val carbs: Int, val fiber: Int, val recipeKcal: Int, val recipeProtein: Int)

    class RecipeItem(val name: String, val grams: Int)

    class Recipe(
        val items: List<RecipeItem>,
        /** В скольких днях с записями он был. */
        val days: Int,
        val kcal: Int,
        val protein: Int,
        /** Его доля в калориях и белке отрезка, 0..1. */
        val kcalShare: Double,
        val proteinShare: Double,
    )

    class Food(
        val days: List<FoodDay>,
        val recordedDays: Int,
        val avgKcal: Double,
        val avgProtein: Double,
        val avgFat: Double,
        val avgCarbs: Double,
        val avgFiber: Double,
        val proteinGoalDays: Int,
        val fiberDays30: Int,
        val weekdayKcal: Double?,
        val weekendKcal: Double?,
        val recipe: Recipe?,
        /** Средний процент нормы по веществам за сутки, где вещества считались; по возрастанию. */
        val micro: List<Pair<Micronutrients.Nutrient, Double>>,
        val microDays: Int,
    )

    private val NAME_JUNK = Regex("""[\d%.,()+×/-]+""")

    /** Имя позиции без чисел: «Творог 5% 200 г» и «творог 5%» — одно. */
    fun itemKey(name: String): String =
        name.lowercase().replace(NAME_JUNK, " ").replace(Regex("""\b(г|мл|шт|гр)\b"""), " ")
            .split(' ').filter { it.isNotBlank() }.joinToString(" ")

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * Главный рецепт — приём, который повторяется в большинстве дней (у
     * владельца — чаша: протеин, два творога, геркулес, ягоды). Ищется по
     * набору позиций: сходство от половины — тот же рецепт. Меньше пяти дней
     * или меньше трети записанных — рецепта нет.
     */
    fun recipe(meals: List<Pair<LocalDate, FoodStore.Meal>>, recordedDays: Int): Pair<Recipe?, Set<Long>> {
        val sets = meals.map { (d, m) -> Triple(d, m, m.items.map { itemKey(it.name) }.filter { it.isNotBlank() }.toSet()) }
            .filter { it.third.size >= 3 }
        if (sets.isEmpty() || recordedDays == 0) return null to emptySet()
        var best: Triple<LocalDate, FoodStore.Meal, Set<String>>? = null
        var bestDays = 0
        for (s in sets) {
            val ds = sets.filter { jaccard(it.third, s.third) >= 0.5 }.map { it.first }.toSet().size
            if (ds > bestDays || (ds == bestDays && best != null && s.third.size > best.third.size)) {
                best = s
                bestDays = ds
            }
        }
        val seed = best ?: return null to emptySet()
        if (bestDays < 5 || bestDays * 3 < recordedDays) return null to emptySet()
        val cluster = sets.filter { jaccard(it.third, seed.third) >= 0.5 }
        val ids = cluster.map { it.second.id }.toSet()
        // Позиции — те, что есть хотя бы в половине повторов, с медианным весом.
        val byKey = cluster.flatMap { it.second.items }.groupBy { itemKey(it.name) }
        val items = byKey.filter { (k, list) -> k.isNotBlank() && list.size * 2 >= cluster.size }
            .map { (_, list) ->
                val grams = list.map { it.grams }.sorted()
                RecipeItem(list.first().name.trim(), grams[grams.size / 2])
            }
            .sortedByDescending { it.grams }
        val all = meals.map { it.second }
        val kcalAll = all.sumOf { it.kcal }.toDouble()
        val protAll = all.sumOf { it.protein }.toDouble()
        val rk = cluster.sumOf { it.second.kcal }
        val rp = cluster.sumOf { it.second.protein }
        return Recipe(
            items = items,
            days = bestDays,
            kcal = rk / cluster.size,
            protein = rp / cluster.size,
            kcalShare = if (kcalAll > 0) rk / kcalAll else 0.0,
            proteinShare = if (protAll > 0) rp / protAll else 0.0,
        ) to ids
    }

    fun food(
        meals: List<FoodStore.Meal>,
        from: LocalDate,
        to: LocalDate,
        proteinGoal: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Food {
        fun dateOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val confirmed = meals.filter { it.confirmed }
            .map { dateOf(it.ts) to it }
            .filter { (d, _) -> !d.isBefore(from) && !d.isAfter(to) }
        val food = confirmed.filter { !it.second.supplement }
        val recorded = food.map { it.first }.toSet()
        val (recipe, recipeIds) = recipe(food, recorded.size)
        val days = ArrayList<FoodDay>()
        var d = from
        while (!d.isAfter(to)) {
            val ms = food.filter { it.first == d }.map { it.second }
            val r = ms.filter { it.id in recipeIds }
            days += FoodDay(d, ms.sumOf { it.kcal }, ms.sumOf { it.protein }, ms.sumOf { it.fat }, ms.sumOf { it.carbs }, ms.sumOf { it.fiber }, r.sumOf { it.kcal }, r.sumOf { it.protein })
            d = d.plusDays(1)
        }
        val rec = days.filter { it.date in recorded }
        fun avg(f: (FoodDay) -> Int) = if (rec.isEmpty()) 0.0 else rec.sumOf(f).toDouble() / rec.size
        val wk = rec.filter { it.date.dayOfWeek.value <= 5 }
        val we = rec.filter { it.date.dayOfWeek.value >= 6 }
        // Вещества — еда и таблетки вместе: сколько тело получило. Сутки без
        // посчитанных веществ не в счёт: «не ел» и «не считали» — разное.
        val microByDay = confirmed.groupBy { it.first }
            .mapValues { (_, l) -> Micronutrients.sum(l.map { it.second.micro }) }
            .filter { it.value.isNotEmpty() }
        val micro = if (microByDay.isEmpty()) emptyList() else Micronutrients.ALL.map { n ->
            n to microByDay.values.sumOf { it[n.id] ?: 0.0 } / microByDay.size / n.norm
        }.sortedBy { it.second }
        return Food(
            days = days,
            recordedDays = rec.size,
            avgKcal = avg { it.kcal },
            avgProtein = avg { it.protein },
            avgFat = avg { it.fat },
            avgCarbs = avg { it.carbs },
            avgFiber = avg { it.fiber },
            proteinGoalDays = if (proteinGoal > 0) rec.count { it.protein >= proteinGoal } else 0,
            fiberDays30 = rec.count { it.fiber >= 30 },
            weekdayKcal = wk.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kcal }.toDouble() / l.size },
            weekendKcal = we.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kcal }.toDouble() / l.size },
            recipe = recipe,
            micro = micro,
            microDays = microByDay.size,
        )
    }

    // ---------------------------------------------------------------------
    // Телефон
    // ---------------------------------------------------------------------

    class PhoneWeek(val monday: LocalDate, val days: Int, val screenMin: Double, val pickups: Double, val apps: Map<String, Double>)

    class Phone(
        val days: Int,
        val screenMin: Double,
        val pickups: Double,
        /** Главные приложения отрезка (пакеты), по убыванию минут. */
        val top: List<String>,
        /** Минут в день по каждому из [top] за отрезок. */
        val topMin: Map<String, Double>,
        val weeks: List<PhoneWeek>,
    )

    fun phone(
        phoneDays: Map<String, PhoneStore.Day>,
        from: LocalDate,
        to: LocalDate,
        furniture: (String) -> Boolean,
        topN: Int = 4,
    ): Phone {
        val list = phoneDays.mapNotNull { (k, v) -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { it to v } }
            .filter { (d, v) -> !d.isBefore(from) && !d.isAfter(to) && v.screenMs > 0 }
        val n = list.size.toDouble()
        val totals = HashMap<String, Long>()
        for ((_, v) in list) for ((pkg, ms) in v.apps) if (!furniture(pkg)) totals[pkg] = (totals[pkg] ?: 0L) + ms
        val top = totals.entries.sortedByDescending { it.value }.take(topN).map { it.key }
        val weeks = ArrayList<PhoneWeek>()
        var monday = from.minusDays((from.dayOfWeek.value - 1).toLong())
        while (!monday.isAfter(to)) {
            val end = monday.plusDays(6)
            val w = list.filter { (d, _) -> !d.isBefore(monday) && !d.isAfter(end) }
            val wn = w.size.toDouble()
            weeks += PhoneWeek(
                monday, w.size,
                if (wn == 0.0) 0.0 else w.sumOf { it.second.screenMs } / 60_000.0 / wn,
                if (wn == 0.0) 0.0 else w.sumOf { it.second.pickups } / wn,
                top.associateWith { pkg -> if (wn == 0.0) 0.0 else w.sumOf { it.second.apps[pkg] ?: 0L } / 60_000.0 / wn },
            )
            monday = monday.plusDays(7)
        }
        return Phone(
            days = list.size,
            screenMin = if (n == 0.0) 0.0 else list.sumOf { it.second.screenMs } / 60_000.0 / n,
            pickups = if (n == 0.0) 0.0 else list.sumOf { it.second.pickups } / n,
            top = top,
            topMin = top.associateWith { pkg -> if (n == 0.0) 0.0 else (totals[pkg] ?: 0L) / 60_000.0 / n },
            weeks = weeks,
        )
    }

    // ---------------------------------------------------------------------
    // Деньги по месяцам
    // ---------------------------------------------------------------------

    class MoneyMonth(
        val month: java.time.YearMonth,
        /** Траты по группам (Дом, Дети, Жизнь, Здоровье…), копейки, положительные. */
        val groups: Map<String, Long>,
        val incomeZf: Long,
        val incomeMarianna: Long,
        val incomeOther: Long,
        /** ЗФ: выручка и команда с подрядчиками, копейки. */
        val zfRevenue: Long,
        val zfTeam: Long,
        val partial: Boolean,
    ) {
        val spent: Long get() = groups.values.sum()
        val income: Long get() = incomeZf + incomeMarianna + incomeOther
    }

    class Money(val months: List<MoneyMonth>, val categories: List<Pair<String, Long>>) {
        /** Доход минус траты накопительно, копейки. */
        val running: List<Long> get() {
            var acc = 0L
            return months.map { acc += it.income - it.spent; acc }
        }
        val empty: Boolean get() = months.all { it.spent == 0L && it.income == 0L }
    }

    /** Группы трат в порядке легенды отчёта. */
    val MONEY_GROUPS = listOf(MoneyCategories.G_HOME, MoneyCategories.G_KIDS, MoneyCategories.G_LIFE, MoneyCategories.G_HEALTH)

    fun money(
        entries: List<MoneyEntry>,
        today: LocalDate,
        months: Int,
        family: MoneyScope,
        zf: MoneyScope,
    ): Money {
        val list = ArrayList<MoneyMonth>()
        for (back in months - 1 downTo 0) {
            val first = today.withDayOfMonth(1).minusMonths(back.toLong())
            val p = MoneyStats.of(MoneyStats.Kind.MONTH, first)
            val cats = MoneyStats.categories(entries, p, family)
            // Без категории — в «Жизнь»: ушло, а куда, не сказано.
            val groups = LinkedHashMap<String, Long>()
            for (g in MONEY_GROUPS) groups[g] = 0L
            for ((g, kop) in MoneyStats.groups(cats)) {
                val key = if (g in MONEY_GROUPS) g else MoneyCategories.G_LIFE
                groups[key] = (groups[key] ?: 0L) + kop
            }
            val income = MoneyStats.income(entries, p, family)
            val zfIn = MoneyStats.income(entries, p, zf).filter { it.category == "zf_revenue" }.sumOf { it.rubKop }
            val zfTeam = -MoneyStats.spending(entries, p, zf).filter { it.category == "zf_team" }.sumOf { it.rubKop }
            list += MoneyMonth(
                month = java.time.YearMonth.from(first),
                groups = groups,
                incomeZf = income.filter { it.category == "inc_zf" }.sumOf { it.rubKop },
                incomeMarianna = income.filter { it.category == "inc_marianna" }.sumOf { it.rubKop },
                incomeOther = income.filter { it.category != "inc_zf" && it.category != "inc_marianna" }.sumOf { it.rubKop },
                zfRevenue = zfIn,
                zfTeam = zfTeam,
                partial = back == 0,
            )
        }
        // Месяцы до первой выписки — не «ноль трат», а «не знаем»: срезаются, и
        // среднее по категориям делится только на месяцы с данными.
        val known = list.dropWhile { it.spent == 0L && it.income == 0L && it.zfRevenue == 0L }
        val n = known.size.coerceAtLeast(1)
        val firstKnown = known.firstOrNull()?.month
        val sums = HashMap<String, Long>()
        if (firstKnown != null) {
            for (back in months - 1 downTo 0) {
                val first = today.withDayOfMonth(1).minusMonths(back.toLong())
                if (java.time.YearMonth.from(first) < firstKnown) continue
                for (c in MoneyStats.categories(entries, MoneyStats.of(MoneyStats.Kind.MONTH, first), family)) sums[c.key] = (sums[c.key] ?: 0L) + c.kop
            }
        }
        val categories = sums.entries.sortedByDescending { it.value }.map { it.key to it.value / n }
        return Money(known, categories)
    }

    // ---------------------------------------------------------------------
    // Мелочи
    // ---------------------------------------------------------------------

    /** Отрезок [weeks] недель, кончающийся вчера: сегодняшний неполный день не в счёт. */
    fun period(today: LocalDate, weeks: Int): Pair<LocalDate, LocalDate> =
        today.minusDays(weeks * 7L) to today.minusDays(1)

    fun daysBetween(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()
}
