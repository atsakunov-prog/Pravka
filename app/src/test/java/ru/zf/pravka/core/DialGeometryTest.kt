package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Круг дня — циферблат на 12 часов (владелец, 10.10.2026: «внутренняя часть
// будет от 0 ночью до 12:00, а внешняя часть будет с 12:00 до 24»).
class DialGeometryTest {

    private val g = DialGeometry
    private val eps = 0.001f

    @Test
    fun `двенадцать сверху и в полночь, и в полдень, три часа справа`() {
        assertEquals(0f, g.angle(0f), eps)
        assertEquals(0f, g.angle(720f), eps)
        assertEquals(90f, g.angle(180f), eps)
        assertEquals(90f, g.angle(900f), eps)
        assertEquals(195f, g.angle(18 * 60f + 30f), eps)
    }

    @Test
    fun `утро растёт внутрь, вечер наружу`() {
        val morning = g.arcs(8 * 60f, 9 * 60f, 9).single()
        assertFalse(morning.pm)
        assertEquals(g.R0 - g.GAP, morning.outer, eps)
        assertTrue(morning.inner < morning.outer)
        // Восемь утра — там же, где восьмёрка на часах.
        assertEquals(240f, morning.a1, eps)
        assertEquals(270f, morning.a2, eps)

        val evening = g.arcs(19 * 60f, 20 * 60f, 9).single()
        assertTrue(evening.pm)
        assertEquals(g.R0 + g.GAP, evening.inner, eps)
        assertEquals(210f, evening.a1, eps)
        // Одна цена часа — одна длина по обе стороны.
        assertEquals(morning.outer - morning.inner, evening.outer - evening.inner, eps)
    }

    @Test
    fun `запись через полдень — два столбика`() {
        val two = g.arcs(11 * 60f + 30f, 12 * 60f + 30f, 10)
        assertEquals(2, two.size)
        assertFalse(two[0].pm)
        assertEquals(345f, two[0].a1, eps)
        assertEquals(360f, two[0].a2, eps)
        assertTrue(two[1].pm)
        assertEquals(0f, two[1].a1, eps)
        assertEquals(15f, two[1].a2, eps)
    }

    @Test
    fun `длина — цена часа по модулю, сон тонкий, выше десяти не растёт`() {
        assertEquals(g.length(5), g.length(-5), eps)
        assertEquals(g.MIN_LEN, g.length(0), eps)
        assertEquals(g.length(10), g.length(15), eps)
        assertTrue(g.length(9) > g.length(1))
        // Самый длинный вечерний столбик не залезает на риски.
        assertTrue(g.R0 + g.GAP + g.length(10) < g.TICK_OUT - g.TICK_MAIN)
    }

    @Test
    fun `тап попадает в свою половину суток`() {
        val morning = g.arcs(2 * 60f, 4 * 60f, 9).single() // 02–04, справа сверху внутри
        val evening = g.arcs(14 * 60f, 16 * 60f, 9).single() // 14–16, там же снаружи
        // Три часа — ровно справа: dx > 0, dy = 0.
        val inside = g.R0 - 10f
        val outside = g.R0 + 10f
        assertTrue(g.contains(morning, inside, 0f))
        assertFalse(g.contains(evening, inside, 0f))
        assertTrue(g.contains(evening, outside, 0f))
        assertFalse(g.contains(morning, outside + 20f, 0f))
    }
}
