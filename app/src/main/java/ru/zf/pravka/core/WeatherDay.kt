package ru.zf.pravka.core

import kotlin.math.roundToInt

// Погода дня для «Сегодня» (Правка 4.0, DESIGN §11.2 WeatherRow): четыре
// ячейки — утро, день, вечер и осадки («дождь 17–21 / 3 мм · 80 %»).
// Источник — почасовой прогноз Open-Meteo (`data/WeatherStore.kt`); здесь
// только правило, под JVM-тестом: из часов дня — три температуры, окно
// осадков и строка для сжатой шапки («+7° · дождь с 17»).
//
// 07.10.2026 (баги №4, №9): под каждой температурой — серым «ощущ. +2°»
// («Марианна обожает именно feels like»), а тап по погоде открывает лист —
// сегодня по часам с вероятностью дождя и десять дней вперёд. Строки листа
// собираются здесь же, под тестом.

object WeatherDay {

    /**
     * Час прогноза: местный час суток, температура, осадки мм, вероятность %,
     * код погоды WMO и «ощущается как» ([feels], NaN — источник не дал).
     */
    data class Hour(
        val hour: Int,
        val temp: Double,
        val precipMm: Double,
        val prob: Int,
        val code: Int,
        val feels: Double = Double.NaN,
    )

    /**
     * День прогноза на десять дней: небо, минимум и максимум, как ощущается,
     * осадки и [parts] — утро, день, вечер (баг №16: «надо указывать справа не
     * „+8 до +15“, а утро, день, вечер»). Нет часов на день — частей нет, и лист
     * показывает диапазон.
     */
    data class Day(
        val date: java.time.LocalDate,
        val code: Int,
        val min: Double,
        val max: Double,
        val feelsMin: Double = Double.NaN,
        val feelsMax: Double = Double.NaN,
        val precipMm: Double = 0.0,
        val prob: Int = 0,
        val parts: List<Part> = emptyList(),
    )

    /** Часть дня: «утро» и температура с «как ощущается» в ближайший к 9, 14, 19 час. */
    data class Part(val label: String, val temp: Double, val feels: Double)

    /** Утро, день, вечер дня по его часам — те же часы, что у ряда «Сегодня». */
    fun parts(hours: List<Hour>): List<Part> {
        if (hours.isEmpty()) return emptyList()
        fun at(h: Int) = hours.minByOrNull { kotlin.math.abs(it.hour - h) }!!
        return listOf("утро" to 9, "день" to 14, "вечер" to 19).map { (label, h) ->
            val x = at(h)
            Part(label, x.temp, x.feels)
        }
    }

    /**
     * Осадки часа в мм — всегда, и ноль тоже (баг №17: «по часам количество
     * осадков и вероятность»): «0 мм», «0,4 мм», «12 мм».
     */
    fun mm(v: Double): String = when {
        v < 0.05 -> "0 мм"
        v < 10.0 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f мм", v).replace(",0 мм", " мм")
        else -> "${v.roundToInt()} мм"
    }

    enum class Sky { CLEAR, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM, NIGHT }

    /** Ячейка ряда; [feels] — вторая строка серым («ощущ. +2°»), у осадков её нет. */
    data class Cell(val label: String, val value: String, val sky: Sky, val dim: Boolean = false, val feels: String? = null)

    data class Summary(val cells: List<Cell>, val line: String)

    /** Код погоды WMO → небо. Ночью ясное — «ночь» (bedtime). */
    fun sky(code: Int, hour: Int): Sky = when (code) {
        0, 1 -> if (hour >= 21 || hour < 6) Sky.NIGHT else Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.CLOUD
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUD
    }

    /** «+4°», «−2°», «0°». */
    fun temp(t: Double): String {
        val r = t.roundToInt()
        return when {
            r > 0 -> "+$r°"
            r < 0 -> "${Fmt.MINUS}${-r}°"
            else -> "0°"
        }
    }

    private fun wet(h: Hour) = h.precipMm >= 0.1 || h.prob >= 50

    /** «ощущ. +2°» — или null, если источник не дал «как ощущается». */
    fun feels(t: Double): String? = if (t.isNaN()) null else "ощущ. ${temp(t)}"

    /** «−2…+4°» — диапазон дня одной строкой. */
    fun range(min: Double, max: Double): String {
        val lo = temp(min).removeSuffix("°")
        val hi = temp(max)
        return if (lo == hi.removeSuffix("°")) hi else "$lo…$hi"
    }

    /** «сегодня», «завтра», дальше — «пт 10». */
    fun dayLabel(date: java.time.LocalDate, today: java.time.LocalDate): String = when (date) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        else -> WEEKDAYS[date.dayOfWeek.value - 1] + " " + date.dayOfMonth
    }

    private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** Осадки строкой часа или дня: «70 % · 2 мм», «40 %», пусто — если сухо. */
    fun rainText(prob: Int, mm: Double): String {
        val parts = mutableListOf<String>()
        if (prob > 0) parts += "$prob %"
        if (mm >= 0.1) parts += if (mm < 1.0) "<1 мм" else "${mm.roundToInt()} мм"
        return parts.joinToString(" · ")
    }

    /** Часы листа: на сегодня — с текущего часа, на другой день — все. */
    fun hoursFrom(hours: List<Hour>, nowHour: Int?): List<Hour> =
        hours.sortedBy { it.hour }.filter { nowHour == null || it.hour >= nowHour }

    /**
     * Сводка дня из часов [hours] (местные, 0…23). Утро — 9:00, день — 14:00,
     * вечер — 19:00 (самый близкий час из есть). Осадки — первое сплошное окно
     * мокрых часов: «дождь 17–21», сумма мм и наибольшая вероятность; нет —
     * «без осадков» с бледным зонтом.
     */
    fun summary(hours: List<Hour>, nowHour: Int = 12): Summary? {
        if (hours.isEmpty()) return null
        fun at(h: Int) = hours.minByOrNull { kotlin.math.abs(it.hour - h) }!!
        val m = at(9)
        val d = at(14)
        val e = at(19)
        val cells = mutableListOf(
            Cell("утро", temp(m.temp), sky(m.code, 9), feels = feels(m.feels)),
            Cell("день", temp(d.temp), sky(d.code, 14), feels = feels(d.feels)),
            Cell("вечер", temp(e.temp), sky(e.code, 19), feels = feels(e.feels)),
        )
        val wetHours = hours.filter { wet(it) }.sortedBy { it.hour }
        var line = temp(at(nowHour).temp)
        if (wetHours.isEmpty()) {
            cells += Cell("осадки", "без осадков", Sky.RAIN, dim = true)
        } else {
            // Первое сплошное окно.
            val first = wetHours.first().hour
            var last = first
            for (h in wetHours.drop(1)) if (h.hour == last + 1) last = h.hour else break
            val window = wetHours.filter { it.hour in first..last }
            val snow = window.any { sky(it.code, it.hour) == Sky.SNOW }
            val word = if (snow) "снег" else "дождь"
            val mm = window.sumOf { it.precipMm }
            val prob = window.maxOf { it.prob }
            val mmText = if (mm < 1.0) "<1 мм" else "${mm.roundToInt()} мм"
            cells += Cell("$word $first–${last + 1}", "$mmText · $prob %", if (snow) Sky.SNOW else Sky.RAIN)
            line += if (first > nowHour) " · $word с $first" else " · $word до ${last + 1}"
        }
        return Summary(cells, line)
    }
}
