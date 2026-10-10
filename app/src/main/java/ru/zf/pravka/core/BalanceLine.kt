package ru.zf.pravka.core

import ru.zf.pravka.core.DayAssembler.DayItem
import kotlin.math.abs

/**
 * Линия баланса в хронике (владелец, 10.10.2026): «определять коридор… от
 * крайнего минус значения до крайнего плюс… день начинается с нуля, поэтому
 * точка должна стоять в середине коридора… когда я записываю следующее дело,
 * она считает общую стоимость сзади… сдвигается влево или вправо… моя задача —
 * вести эту линию как можно правее».
 *
 * Балл копится по записям ленты тем же счётом, что и балл дня
 * (`DayAssembler`: цена часа × часы, без округления), и рисуется по ширине
 * коридора: середина — ноль, правый край — +граница, левый — −граница.
 *
 * Граница — НЕ сегодняшний максимум: тогда любой день, закончившийся на своём
 * пике, упирался бы в правый край, и «правее» перестало бы значить «лучше».
 * Граница — медиана дневных крайностей за 28 прошлых дней («ты прав по поводу
 * средней медианы за 28 дней»), а вылез сегодня дальше — коридор расширяется
 * до сегодняшнего края («просто коридор чуть-чуть расширяется»); назавтра
 * медиана пересчитана уже с сегодняшним днём. Коридор зеркальный.
 */
object BalanceLine {

    /** Коридор не уже этого: у пустой истории и дня из одних нулей линия стоит в середине, а не делится на ноль. */
    const val FLOOR = 1.0
    const val HISTORY_DAYS = 28

    enum class Kind {
        /** Прошлая запись: балл сверху строки — до неё, снизу — после, точка — в конце. */
        ENTRY,
        /** Идущая запись: снизу — балл на «сейчас», точки нет — она у «сейчас». */
        CURRENT,
        /** Строка без изменения балла (отметка, ждущее, дыра без записи). */
        THROUGH,
        /** «Сейчас»: конец линии, пульсирующая точка. */
        NOW,
        /** Будущее: пунктир ровно под точкой «сейчас». */
        FUTURE,
    }

    /** Балл строки сверху и снизу (в очках, точно). */
    data class Seg(val top: Double, val bottom: Double, val kind: Kind)

    /** Очки записи — как в балле дня: цена часа × часы, без округления. */
    fun points(e: DayItem.Entry): Double = e.worth * (e.end - e.start).toDouble() / DayAssembler.HOUR

    /** Балл по строкам хроники: копится по записям, отметки его не двигают, после «сейчас» — будущее. */
    fun segments(items: List<DayItem>): List<Seg> {
        var bal = 0.0
        var past = true
        return items.map { item ->
            if (!past) return@map Seg(bal, bal, Kind.FUTURE)
            when (item) {
                is DayItem.Entry -> {
                    val top = bal
                    bal += points(item)
                    Seg(top, bal, if (item.current) Kind.CURRENT else Kind.ENTRY)
                }
                is DayItem.Mark, is DayItem.Pending -> Seg(bal, bal, Kind.THROUGH)
                is DayItem.Now -> {
                    past = false
                    Seg(bal, bal, Kind.NOW)
                }
                // День впереди: «сейчас» нет, всё — будущее.
                else -> {
                    past = false
                    Seg(bal, bal, Kind.FUTURE)
                }
            }
        }
    }

    /** Самый дальний от нуля балл дня по ходу — не только в конце: провал утром тоже край. */
    fun maxAbs(segs: List<Seg>): Double =
        segs.filter { it.kind != Kind.FUTURE }.maxOfOrNull { maxOf(abs(it.top), abs(it.bottom)) } ?: 0.0

    /** Запись прошлого дня для истории: начало, конец (у идущей — «сейчас») и цена часа. */
    data class Span(val start: Long, val end: Long, val worth: Int)

    /**
     * Самый дальний от нуля балл суток [[from], [to]) по ходу. null — записей в
     * сутках нет: такой день (до начала ведения, пропуск) медиану не тянет к нулю.
     */
    fun dayMaxAbs(spans: List<Span>, from: Long, to: Long): Double? {
        val inDay = spans.filter { it.start < to && it.end > from }.sortedBy { it.start }
        if (inDay.isEmpty()) return null
        var bal = 0.0
        var max = 0.0
        for (s in inDay) {
            val ms = (minOf(s.end, to) - maxOf(s.start, from)).coerceAtLeast(0L)
            bal += s.worth * ms.toDouble() / DayAssembler.HOUR
            max = maxOf(max, abs(bal))
        }
        return max
    }

    /** Крайности [days] прошлых суток до [dayStart] (только дни с записями). */
    fun history(spans: List<Span>, dayStart: Long, days: Int = HISTORY_DAYS, dayMs: Long = 86_400_000L): List<Double> =
        (1..days).mapNotNull { k ->
            val from = dayStart - k * dayMs
            dayMaxAbs(spans, from, from + dayMs)
        }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Половина коридора: медиана истории, а сегодня дальше — сегодняшний край. */
    fun corridor(today: Double, history: List<Double>): Double = maxOf(median(history) ?: 0.0, today, FLOOR)

    /** Место балла в коридоре: −1 — левый край, 0 — середина, +1 — правый. */
    fun frac(bal: Double, corridor: Double): Float = (bal / corridor).coerceIn(-1.0, 1.0).toFloat()
}
