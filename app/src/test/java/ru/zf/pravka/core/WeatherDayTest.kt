package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Погода «Сегодня» (DESIGN §11.2 WeatherRow): утро, день, вечер и окно осадков.
class WeatherDayTest {

    private fun day(rainFrom: Int? = null, rainTo: Int? = null) = (0..23).map { h ->
        val wet = rainFrom != null && h in rainFrom..rainTo!!
        WeatherDay.Hour(
            hour = h,
            temp = when (h) { 9 -> 4.2; 14 -> 9.6; 19 -> 7.4; else -> 5.0 },
            precipMm = if (wet) 0.75 else 0.0,
            prob = if (wet) 80 else 10,
            code = if (wet) 61 else 3,
        )
    }

    @Test
    fun `три температуры и окно дождя как в макете`() {
        val s = WeatherDay.summary(day(17, 20), nowHour = 18)!!
        assertEquals(listOf("+4°", "+10°", "+7°"), s.cells.take(3).map { it.value })
        val rain = s.cells[3]
        assertEquals("дождь 17–21", rain.label)
        assertEquals("3 мм · 80 %", rain.value)
        assertEquals(WeatherDay.Sky.RAIN, rain.sky)
    }

    @Test
    fun `строка сжатой шапки - дождь с часа`() {
        assertEquals("+5° · дождь с 17", WeatherDay.summary(day(17, 20), nowHour = 12)!!.line)
    }

    @Test
    fun `без осадков - бледный зонт`() {
        val s = WeatherDay.summary(day(), nowHour = 12)!!
        assertEquals("без осадков", s.cells[3].value)
        assertTrue(s.cells[3].dim)
    }

    @Test
    fun `мороз - настоящим минусом, пустой прогноз - нет ряда`() {
        assertEquals("−3°", WeatherDay.temp(-2.6))
        assertNull(WeatherDay.summary(emptyList()))
    }

    @Test
    fun `ощущается как - серой строкой под температурой, нет данных - нет строки`() {
        val hours = day().map { it.copy(feels = it.temp - 3.4) }
        val s = WeatherDay.summary(hours, nowHour = 12)!!
        assertEquals(listOf("ощущ. +1°", "ощущ. +6°", "ощущ. +4°"), s.cells.take(3).map { it.feels })
        assertNull(s.cells[3].feels)
        assertNull(WeatherDay.summary(day(), nowHour = 12)!!.cells[0].feels)
    }

    @Test
    fun `лист погоды - подписи дней, диапазон, осадки, часы с текущего`() {
        val today = java.time.LocalDate.of(2026, 10, 7)
        assertEquals("сегодня", WeatherDay.dayLabel(today, today))
        assertEquals("завтра", WeatherDay.dayLabel(today.plusDays(1), today))
        assertEquals("пт 9", WeatherDay.dayLabel(today.plusDays(2), today))
        assertEquals("−2…+4°", WeatherDay.range(-2.4, 3.6))
        assertEquals("+5°", WeatherDay.range(4.8, 5.1))
        assertEquals("70 % · 2 мм", WeatherDay.rainText(70, 2.2))
        assertEquals("40 %", WeatherDay.rainText(40, 0.0))
        assertEquals("", WeatherDay.rainText(0, 0.0))
        assertEquals(18, WeatherDay.hoursFrom(day(), nowHour = 18).first().hour)
        assertEquals(24, WeatherDay.hoursFrom(day(), nowHour = null).size)
    }

    @Test
    fun `десять дней - утро, день, вечер с ощущается, осадки по часам всегда`() {
        val p = WeatherDay.parts(day().map { it.copy(feels = it.temp - 2.0) })
        assertEquals(listOf("утро", "день", "вечер"), p.map { it.label })
        assertEquals(listOf("+4°", "+10°", "+7°"), p.map { WeatherDay.temp(it.temp) })
        assertEquals("+2°", WeatherDay.temp(p[0].feels))
        assertTrue(WeatherDay.parts(emptyList()).isEmpty())
        assertEquals("0 мм", WeatherDay.mm(0.0))
        assertEquals("0,4 мм", WeatherDay.mm(0.42))
        assertEquals("2 мм", WeatherDay.mm(2.0))
        assertEquals("12 мм", WeatherDay.mm(12.4))
    }

    // ---- Правка 5.0, этап 2: строка над «Сейчас» и сутки вперёд ----

    private val d0 = java.time.LocalDate.of(2026, 10, 13)

    /** Два дня часов: температура — номер часа, чтобы видеть, какой час взят. */
    private fun twoDays() = (0..1).flatMap { k ->
        (0..23).map { h -> WeatherDay.Hour(h, h.toDouble() + k * 100, 0.0, 0, 3, feels = h - 2.0, date = d0.plusDays(k.toLong())) }
    }

    @Test
    fun `сутки вперёд — с текущего часа и через полночь`() {
        val a = WeatherDay.ahead(twoDays(), d0.atTime(21, 15))
        assertEquals(24, a.size)
        assertEquals(21, a.first().hour)
        assertEquals(d0, a.first().date)
        assertEquals(20, a.last().hour)
        assertEquals(d0.plusDays(1), a.last().date)
    }

    @Test
    fun `в 21 15 — вечер сейчас, потом утро и день завтра`() {
        val parts = WeatherDay.nextParts(WeatherDay.ahead(twoDays(), d0.atTime(21, 15)))
        assertEquals(listOf("вечер", "утро", "день"), parts.map { it.label })
        // Вечер — ближайший оставшийся час (21), утро и день — обычные 9 и 14 завтрашнего дня.
        assertEquals(listOf(21.0, 109.0, 114.0), parts.map { it.temp })
        assertEquals(19.0, parts[0].feels, 1e-9)
    }

    @Test
    fun `утром — утро, день, вечер того же дня, ночью — с утра`() {
        assertEquals(listOf(10.0, 14.0, 19.0), WeatherDay.nextParts(WeatherDay.ahead(twoDays(), d0.atTime(10, 5))).map { it.temp })
        assertEquals(listOf(9.0, 14.0, 19.0), WeatherDay.nextParts(WeatherDay.ahead(twoDays(), d0.atTime(2, 0))).map { it.temp })
        assertEquals(listOf("утро", "день", "вечер"), WeatherDay.nextParts(WeatherDay.ahead(twoDays(), d0.atTime(23, 30))).map { it.label })
    }

    @Test
    fun `неделя — подпись дня и осадки`() {
        assertEquals("Вт 13", WeatherDay.weekLabel(d0))
        assertEquals("5,5 мм · 73%" to true, WeatherDay.weekRain(73, 5.5))
        assertEquals("сухо" to false, WeatherDay.weekRain(6, 0.0))
        assertEquals("0,6 мм" to false, WeatherDay.weekRain(8, 0.6))
        assertEquals("35%", WeatherDay.hourRain(35))
        assertEquals("", WeatherDay.hourRain(15))
        assertEquals("+10", WeatherDay.tempShort(9.6))
    }
}
