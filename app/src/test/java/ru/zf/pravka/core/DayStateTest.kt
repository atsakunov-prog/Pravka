package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Баг №13: строка состояния под днём — сон, HRV, форма в порядке владельца.
class DayStateTest {

    private fun verdict(tone: Int, vararg n: TrafficLight.Number) =
        TrafficLight.Verdict("", "", tone, n.toList(), emptyList(), "")

    @Test
    fun `сон, HRV, форма - в порядке владельца, хуже нормы помечено`() {
        val s = DayState.of(
            verdict(
                -1,
                TrafficLight.Number("HRV", "48", "база 58", -1),
                TrafficLight.Number("Форма", "+4", "", 0),
                TrafficLight.Number("Сон", "7,2 ч", "", 0),
            )
        )!!
        assertEquals(listOf("сон 7,2 ч", "HRV 48", "форма +4"), s.parts.map { it.text })
        assertEquals(listOf(false, true, false), s.parts.map { it.worse })
        assertEquals(1, s.lit)
    }

    @Test
    fun `нет чисел - нет строки`() {
        assertNull(DayState.of(verdict(0)))
        assertEquals(3, DayState.of(verdict(2, TrafficLight.Number("Сон", "8 ч", "", 1)))!!.lit)
    }
}
