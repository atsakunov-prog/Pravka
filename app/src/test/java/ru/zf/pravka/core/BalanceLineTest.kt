package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.pravka.core.BalanceLine.Kind
import ru.zf.pravka.core.DayAssembler.DayItem

// Линия баланса в хронике (владелец, 10.10.2026): день с нуля в середине
// коридора, каждая запись сдвигает балл, граница — медиана крайностей за 28
// дней, а сегодня дальше — коридор расширяется.
class BalanceLineTest {

    private val h = DayAssembler.HOUR
    private val eps = 1e-9

    private fun entry(id: Long, fromH: Double, toH: Double, worth: Int, current: Boolean = false) = DayItem.Entry(
        id = id, start = (fromH * h).toLong(), end = (toH * h).toLong(), fullStart = (fromH * h).toLong(),
        title = "", category = "", worth = worth, client = "", useful = 0, comment = "",
        points = null, current = current, gap = false,
    )

    @Test
    fun `балл копится по записям, отметки его не двигают, после сейчас — будущее`() {
        val items = listOf(
            entry(1, 0.0, 8.0, 0), // сон
            entry(2, 8.0, 10.0, 10), // работа +20
            DayItem.Mark(2, (9 * h), DayAssembler.Source.FOOD, "419 ккал", ""),
            entry(3, 10.0, 10.5, -5), // потери −2.5
            entry(4, 10.5, 11.0, 6, current = true), // идёт: +3
            DayItem.Now((11 * h)),
            DayItem.Free((11 * h), (12 * h)),
        )
        val s = BalanceLine.segments(items)
        assertEquals(listOf(Kind.ENTRY, Kind.ENTRY, Kind.THROUGH, Kind.ENTRY, Kind.CURRENT, Kind.NOW, Kind.FUTURE), s.map { it.kind })
        assertEquals(0.0, s[0].bottom, eps)
        assertEquals(0.0, s[1].top, eps)
        assertEquals(20.0, s[1].bottom, eps)
        assertEquals(20.0, s[2].top, eps)
        assertEquals(20.0, s[2].bottom, eps)
        assertEquals(17.5, s[3].bottom, eps)
        assertEquals(20.5, s[4].bottom, eps)
        assertEquals(20.5, s[5].top, eps)
        assertEquals(20.5, s[6].top, eps)
        assertEquals(20.5, BalanceLine.maxAbs(s), eps)
    }

    @Test
    fun `крайность дня — по ходу, а не только в конце`() {
        val spans = listOf(
            BalanceLine.Span(0, 2 * h, -5), // −10 утром
            BalanceLine.Span(2 * h, 3 * h, 4), // −6 к концу
        )
        assertEquals(10.0, BalanceLine.dayMaxAbs(spans, 0, 24 * h)!!, eps)
        // Запись, переходящая из вчера, считается только своей частью внутри суток.
        assertEquals(5.0, BalanceLine.dayMaxAbs(listOf(BalanceLine.Span(-h, h, 5)), 0, 24 * h)!!, eps)
        assertNull(BalanceLine.dayMaxAbs(spans, 24 * h, 48 * h))
    }

    @Test
    fun `история — 28 прошлых дней, пустые дни медиану к нулю не тянут`() {
        val day = 86_400_000L
        val today = 30 * day
        // Записи только в двух прошлых днях: вчера +40, позавчера +60.
        val spans = listOf(
            BalanceLine.Span(today - day, today - day + 4 * h, 10),
            BalanceLine.Span(today - 2 * day, today - 2 * day + 6 * h, 10),
            // Сегодняшняя запись в историю не попадает.
            BalanceLine.Span(today, today + h, 10),
        )
        val hist = BalanceLine.history(spans, today, dayMs = day)
        assertEquals(listOf(40.0, 60.0), hist)
        assertEquals(50.0, BalanceLine.median(hist)!!, eps)
    }

    @Test
    fun `коридор — медиана, а сегодня дальше — по сегодняшнему краю`() {
        val hist = listOf(30.0, 50.0, 40.0)
        assertEquals(40.0, BalanceLine.corridor(12.0, hist), eps)
        assertEquals(55.0, BalanceLine.corridor(55.0, hist), eps)
        // Истории нет — коридор по самому дню, и не уже единицы.
        assertEquals(3.0, BalanceLine.corridor(3.0, emptyList()), eps)
        assertEquals(BalanceLine.FLOOR, BalanceLine.corridor(0.0, emptyList()), eps)
    }

    @Test
    fun `место в коридоре — от минус до плюс единицы`() {
        assertEquals(0f, BalanceLine.frac(0.0, 40.0), 1e-6f)
        assertEquals(0.5f, BalanceLine.frac(20.0, 40.0), 1e-6f)
        assertEquals(-1f, BalanceLine.frac(-90.0, 40.0), 1e-6f)
    }
}
