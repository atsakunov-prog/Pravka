package ru.zf.pravka.core

import ru.zf.pravka.core.DayAssembler.DayItem
import kotlin.math.abs

/**
 * Линия баланса в хронике — «дорога жизни» (владелец, 10.10.2026): «день
 * начинается с нуля… когда я записываю следующее дело, она считает общую
 * стоимость сзади… сдвигается влево или вправо… моя задача — вести эту линию
 * как можно правее».
 *
 * Балл копится по записям ленты тем же счётом, что и балл дня
 * (`DayAssembler`: цена часа × часы, без округления), и рисуется в трубе:
 * середина — ноль (сплошная), вправо — плюс.
 *
 * Разметка дороги — СВОИ дни к ТОМУ ЖЕ часу («каждую из полос поделим…
 * чтобы я понимал, что я лучше 50% своих дней… а 75% — край туннеля»): за 28
 * прошлых дней с записями берётся, сколько каждый набрал к этому часу, и
 * линия полосы — медиана, стенка — 75%. Не сигмы: σ предполагает нормальное
 * распределение, а дни владельца — от −47 до +103 при средней +39 (архив,
 * 10.10.2026); +1σ было бы около 84 % дней, не 75. И не итог дня: утром
 * любой день проигрывал бы чужим вечерам — к полудню медиана владельца +11,
 * а к концу дня +37. Поэтому дорога расширяется по ходу дня: ночью все дни
 * около нуля, к вечеру стенки расходятся. Обе полосы зеркальны.
 *
 * Масштаб трубы — на весь день один: стенка к концу дня встаёт на
 * [WALL_FRAC] от края, за ней — обочина, куда можно «выехать». Вылез сегодня
 * дальше обочины — масштаб расширяется до сегодняшнего края.
 */
object BalanceLine {

    /** Масштаб не мельче этого: у пустой истории и дня из одних нулей линия стоит в середине. */
    const val FLOOR = 1.0
    const val HISTORY_DAYS = 28
    /** Где стенка (75 % дней) встаёт к концу дня — доля от края трубы; дальше — обочина. */
    const val WALL_FRAC = 0.72
    const val MID_Q = 0.5
    const val WALL_Q = 0.75

    enum class Kind {
        /** Прошлая запись: балл сверху строки — до неё, снизу — после. */
        ENTRY,
        /** Идущая запись: снизу — балл на «сейчас», конец линии — у «сейчас». */
        CURRENT,
        /** Строка без изменения балла (отметка, ждущее, дыра без записи). */
        THROUGH,
        /** «Сейчас»: конец линии, пульсирующая точка. */
        NOW,
        /** Будущее: пунктир ровно под точкой «сейчас». */
        FUTURE,
    }

    /** Балл строки сверху и снизу (в очках, точно) и время этих краёв — для разметки к тому же часу. */
    data class Seg(val top: Double, val bottom: Double, val kind: Kind, val tTop: Long, val tBottom: Long)

    /** Очки записи — как в балле дня: цена часа × часы, без округления. */
    fun points(e: DayItem.Entry): Double = e.worth * (e.end - e.start).toDouble() / DayAssembler.HOUR

    /** Начало строки будущего во времени; null — у строки своего времени нет (берётся прошлое). */
    private fun startOf(item: DayItem): Long? = when (item) {
        is DayItem.Free -> item.from
        is DayItem.Planned -> item.start
        is DayItem.Task -> item.start
        is DayItem.Sleep -> item.at
        is DayItem.Now -> item.at
        is DayItem.Entry -> item.start
        is DayItem.Mark -> item.at
        is DayItem.Pending -> item.at
        else -> null
    }

    /**
     * Балл по строкам хроники: копится по записям, отметки его не двигают (и
     * время у них — конец записи, к которой они прикреплены: линия и разметка
     * в одной строке говорят про один и тот же миг), после «сейчас» — будущее.
     */
    fun segments(items: List<DayItem>): List<Seg> {
        var bal = 0.0
        var past = true
        var t = items.firstNotNullOfOrNull { startOf(it) } ?: 0L
        val raw = items.map { item ->
            if (!past) {
                t = startOf(item) ?: t
                return@map Seg(bal, bal, Kind.FUTURE, t, t)
            }
            when (item) {
                is DayItem.Entry -> {
                    val top = bal
                    bal += points(item)
                    t = item.end
                    Seg(top, bal, if (item.current) Kind.CURRENT else Kind.ENTRY, item.start, item.end)
                }
                is DayItem.Mark, is DayItem.Pending -> Seg(bal, bal, Kind.THROUGH, t, t)
                is DayItem.Now -> {
                    past = false
                    t = item.at
                    Seg(bal, bal, Kind.NOW, t, t)
                }
                // День впереди: «сейчас» нет, всё — будущее.
                else -> {
                    past = false
                    t = startOf(item) ?: t
                    Seg(bal, bal, Kind.FUTURE, t, t)
                }
            }
        }
        // Низ строки — начало следующей: разметка непрерывна и через дыру между
        // записями, и через отметки, и впереди. У последней — свой конец.
        return raw.mapIndexed { i, s -> s.copy(tBottom = raw.getOrNull(i + 1)?.tTop ?: s.tBottom) }
    }

    /** Самый дальний от нуля балл дня по ходу — не только в конце: провал утром тоже край. */
    fun maxAbs(segs: List<Seg>): Double =
        segs.filter { it.kind != Kind.FUTURE }.maxOfOrNull { maxOf(abs(it.top), abs(it.bottom)) } ?: 0.0

    /** Запись прошлого дня для истории: начало, конец (у идущей — «сейчас») и цена часа. */
    data class Span(val start: Long, val end: Long, val worth: Int)

    /** Балл прошлого дня по часам: ломаная от полуночи, [at] — балл к смещению от начала суток. */
    class Curve internal constructor(private val t: LongArray, private val v: DoubleArray) {
        fun at(offset: Long): Double {
            if (offset <= t[0]) return v[0]
            if (offset >= t[t.size - 1]) return v[v.size - 1]
            var lo = 0
            var hi = t.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (t[mid] <= offset) lo = mid else hi = mid
            }
            val span = t[hi] - t[lo]
            return if (span <= 0) v[hi] else v[lo] + (v[hi] - v[lo]) * (offset - t[lo]).toDouble() / span
        }
    }

    /**
     * Балл суток [[from], [to]) по часам. Каждая запись вносит своё
     * независимо — тем же счётом, что балл дня (`DayReport.balance`): запись,
     * накрывшая другие (дубль, авто-факт поверх), не съедает их. Первая версия
     * шла по записям подряд и пропускала всё под длинной записью — у снимков
     * половина дней обнулилась, и медиана легла на ноль. null — записей в
     * сутках нет: такой день разметку не тянет к нулю.
     */
    fun curve(spans: List<Span>, from: Long, to: Long): Curve? {
        val inDay = spans.filter { it.start < to && it.end > from }
        if (inDay.isEmpty()) return null
        val cuts = (inDay.flatMap { listOf(maxOf(it.start, from) - from, minOf(it.end, to) - from) } + 0L).distinct().sorted()
        // Между соседними краями каждая запись копит линейно — ломаная по краям точна.
        val vs = cuts.map { tau ->
            inDay.sumOf { s ->
                val a = maxOf(s.start, from)
                val b = minOf(s.end, to, from + tau)
                if (b > a) s.worth * (b - a).toDouble() / DayAssembler.HOUR else 0.0
            }
        }
        return Curve(cuts.toLongArray(), vs.toDoubleArray())
    }

    /** Кривые [days] прошлых суток до [dayStart] (только дни с записями). */
    fun history(spans: List<Span>, dayStart: Long, days: Int = HISTORY_DAYS, dayMs: Long = 86_400_000L): List<Curve> =
        (1..days).mapNotNull { k ->
            val from = dayStart - k * dayMs
            curve(spans, from, from + dayMs)
        }

    /** Квантиль с линейной интерполяцией (как percentile_cont в SQL). */
    fun quantile(xs: List<Double>, q: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** Разметка дороги: свои дни к тому же часу. */
    class Road(private val curves: List<Curve>, private val dayMs: Long = 86_400_000L) {
        /** Сколько набрала доля [q] прошлых дней к смещению [offset] от начала суток. */
        fun at(offset: Long, q: Double): Double = quantile(curves.map { it.at(offset) }, q) ?: 0.0

        /** Самая широкая стенка за сутки (по модулю) — по ней масштаб трубы. */
        val wallMax: Double by lazy {
            (0..96).maxOf { i -> abs(at(i * dayMs / 96, WALL_Q)) }
        }

        companion object {
            fun of(curves: List<Curve>, dayMs: Long = 86_400_000L): Road? = if (curves.isEmpty()) null else Road(curves, dayMs)
        }
    }

    /** Балл на краю трубы: стенка к концу дня — на [WALL_FRAC], а сегодня дальше — по сегодняшнему краю. */
    fun scale(todayMax: Double, road: Road?): Double = maxOf((road?.wallMax ?: 0.0) / WALL_FRAC, todayMax, FLOOR)

    /** Место балла в трубе: −1 — левый край, 0 — середина, +1 — правый. */
    fun frac(bal: Double, scale: Double): Float = (bal / scale).coerceIn(-1.0, 1.0).toFloat()
}
