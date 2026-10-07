package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Числа и даты по-русски — Правка 4.0 (07.10.2026, DESIGN §3.9). Одно место
// на всё приложение, чтобы «1 ч 40 м» не жило рядом с «100 мин», а «−3 577»
// с дефисом и обычным пробелом: в моноширинных цифрах обычный пробел рвёт
// число на строке, дефис короче цифры и читается тире. Поэтому тысячи — через
// узкий неразрывный пробел U+202F, минус — U+2212, длительности — «45 м»,
// «1 ч 40 м» (никогда «мин»), диапазон времени — через короткое тире.

object Fmt {

    /** Узкий неразрывный пробел между тысячами: «1 074 898». */
    const val THIN = ' '

    /** Настоящий минус, ширины цифры. */
    const val MINUS = '−'

    /** Короткое тире диапазона: «09:50–10:40». */
    const val DASH = '–'

    /** Целое с тысячами через [THIN] и минусом [MINUS]: «1 074 898», «−3 577». */
    fun num(n: Long): String {
        val digits = abs(n).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(THIN)
            sb.append(c)
        }
        return if (n < 0) "$MINUS$sb" else sb.toString()
    }

    fun num(n: Int): String = num(n.toLong())

    /** Со знаком всегда: «+325 000», «−3 577», ноль — «0». */
    fun signed(n: Long): String = when {
        n > 0 -> "+" + num(n)
        else -> num(n)
    }

    fun signed(n: Int): String = signed(n.toLong())

    /** Очки и балл: «+84», «−1», «0». */
    fun points(n: Int): String = signed(n)

    /** Минус в любом тексте — настоящим знаком (для строк, собранных до Fmt). */
    fun minus(s: String): String = s.replace('-', MINUS)

    /**
     * Длительность: «45 м», «1 ч 40 м», «7 ч», «0 м». Минуты округлены
     * вниз до целой: прошедшее показывается точно, без «почти».
     */
    fun dur(min: Int): String {
        val m = min.coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "$r м"
            r == 0 -> "$h ч"
            else -> "$h ч $r м"
        }
    }

    fun durMs(ms: Long): String = dur((ms / 60_000L).toInt())

    /**
     * Оставшееся и свободное — до 5 минут (DESIGN §3.10): «до сна 4 ч 40 м»,
     * «свободно 1 ч 10 м». Будущее не знает точности до минуты, и «4 ч 37 м»
     * обещало бы её.
     */
    fun durFuture(min: Int): String = dur(round5(min))

    /** Минуты до ближайших пяти: 37 → 35, 38 → 40. */
    fun round5(min: Int): Int = ((min.coerceAtLeast(0) / 5.0).roundToInt()) * 5

    /** «09:05» по часам телефона. */
    fun hm(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** «09:05» из минут от полуночи. */
    fun hmOfMin(minOfDay: Int): String {
        val m = ((minOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** «09:50–10:40». */
    fun range(fromMs: Long, toMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        hm(fromMs, zone) + DASH + hm(toMs, zone)

    // ---- даты ----

    private val WEEKDAY = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val WD = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val MONTH_GEN = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Сокращения месяцев — DESIGN §3.9: «сент» и «нояб», а не «сен» и «ноя». */
    private val MONTH_SHORT = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сент", "окт", "нояб", "дек")

    /** «Понедельник» — заголовок дня. */
    fun weekdayTitle(d: LocalDate): String = WEEKDAY[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }

    /** «понедельник». */
    fun weekday(d: LocalDate): String = WEEKDAY[d.dayOfWeek.value - 1]

    /** «пн». */
    fun wd(d: LocalDate): String = WD[d.dayOfWeek.value - 1]

    /** «5 октября». */
    fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${MONTH_GEN[d.monthValue - 1]}"

    /** «5 окт». */
    fun dayMon(d: LocalDate): String = "${d.dayOfMonth} ${MONTH_SHORT[d.monthValue - 1]}"

    /** Навигатор дня: «понедельник, 5 октября». */
    fun dayLong(d: LocalDate): String = "${weekday(d)}, ${dayMonth(d)}"

    /** Списки: «пн, 5 октября». */
    fun dayList(d: LocalDate): String = "${wd(d)}, ${dayMonth(d)}"

    /** Сжатая шапка и сравнения: «пн, 5 окт», «пн, 28 сент». */
    fun dayShort(d: LocalDate): String = "${wd(d)}, ${dayMon(d)}"

    /**
     * Надстрочник над днём: «5 октября · сегодня», «4 октября · вчера»,
     * «6 октября · завтра»; дальше — только дата.
     */
    fun overline(d: LocalDate, today: LocalDate): String {
        val tail = when (d) {
            today -> " · сегодня"
            today.minusDays(1) -> " · вчера"
            today.plusDays(1) -> " · завтра"
            else -> ""
        }
        return dayMonth(d) + tail
    }

    /** Название месяца в именительном с большой: «Октябрь». */
    fun monthTitle(month: Int): String = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )[month - 1]

    /** Рубли из копеек целыми: −357 700 коп → «−3 577». */
    fun rub(kop: Long): String = num((kop / 100.0).roundToLong())
}
