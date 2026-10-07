package ru.zf.pravka.core

import kotlin.math.roundToInt

// Погода дня для «Сегодня» (Правка 4.0, DESIGN §11.2 WeatherRow): четыре
// ячейки — утро, день, вечер и осадки («дождь 17–21 / 3 мм · 80 %»).
// Источник — почасовой прогноз Open-Meteo (`data/WeatherStore.kt`); здесь
// только правило, под JVM-тестом: из часов дня — три температуры, окно
// осадков и строка для сжатой шапки («+7° · дождь с 17»).

object WeatherDay {

    /** Час прогноза: местный час суток, температура, осадки мм, вероятность %, код погоды WMO. */
    data class Hour(val hour: Int, val temp: Double, val precipMm: Double, val prob: Int, val code: Int)

    enum class Sky { CLEAR, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM, NIGHT }

    data class Cell(val label: String, val value: String, val sky: Sky, val dim: Boolean = false)

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
            Cell("утро", temp(m.temp), sky(m.code, 9)),
            Cell("день", temp(d.temp), sky(d.code, 14)),
            Cell("вечер", temp(e.temp), sky(e.code, 19)),
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
