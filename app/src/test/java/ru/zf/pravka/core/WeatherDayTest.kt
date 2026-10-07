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
}
