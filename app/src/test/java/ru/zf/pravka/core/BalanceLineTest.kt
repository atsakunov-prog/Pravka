package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.pravka.core.BalanceLine.Kind
import ru.zf.pravka.core.DayAssembler.DayItem

// Дорога жизни в хронике (владелец, 10.10.2026): день с нуля посередине,
// каждая запись сдвигает балл; разметка — свои 28 дней к тому же часу:
// медиана — полоса, 75 % — стенка, за ней обочина.
class BalanceLineTest {

    private val h = DayAssembler.HOUR
    private val day = 86_400_000L
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
        assertEquals(20.0, s[1].bottom, eps)
        assertEquals(20.0, s[2].top, eps)
        assertEquals(20.0, s[2].bottom, eps)
        assertEquals(17.5, s[3].bottom, eps)
        assertEquals(20.5, s[4].bottom, eps)
        assertEquals(20.5, s[6].top, eps)
        assertEquals(20.5, BalanceLine.maxAbs(s), eps)
    }

    @Test
    fun `время строки — для разметки к тому же часу, низ — начало следующей`() {
        val items = listOf(
            entry(1, 8.0, 10.0, 10),
            // Отметка в середине записи стоит после неё — и время у неё конец записи.
            DayItem.Mark(1, (9 * h), DayAssembler.Source.FOOD, "419 ккал", ""),
            // Дыра 10:00–10:30 без записи.
            entry(2, 10.5, 11.0, 1, current = true),
            DayItem.Now((11 * h)),
            DayItem.Free((11 * h), (12 * h)),
            DayItem.Sleep((23 * h), 0),
        )
        val s = BalanceLine.segments(items)
        assertEquals(8 * h, s[0].tTop)
        assertEquals(10 * h, s[0].tBottom)
        assertEquals(10 * h, s[1].tTop)
        // Через дыру разметка тянется до начала следующей записи.
        assertEquals((10.5 * h).toLong(), s[1].tBottom)
        assertEquals(11 * h, s[3].tTop)
        assertEquals(11 * h, s[4].tTop)
        assertEquals(23 * h, s[4].tBottom)
        assertEquals(23 * h, s[5].tBottom)
    }

    @Test
    fun `кривая дня — балл к любому часу, вчерашний хвост — только своей частью`() {
        val c = BalanceLine.curve(
            listOf(
                BalanceLine.Span(-h, 2 * h, 0), // сон с вечера
                BalanceLine.Span(8 * h, 10 * h, 10), // +20
                BalanceLine.Span(12 * h, 13 * h, -5), // −5
            ),
            0, day,
        )!!
        assertEquals(0.0, c.at(7 * h), eps)
        assertEquals(10.0, c.at(9 * h), eps)
        // В дыре между записями балл стоит.
        assertEquals(20.0, c.at(11 * h), eps)
        assertEquals(17.5, c.at((12.5 * h).toLong()), eps)
        assertEquals(15.0, c.at(23 * h), eps)
        assertNull(BalanceLine.curve(listOf(BalanceLine.Span(0, h, 5)), day, 2 * day))
    }

    @Test
    fun `запись поверх других не съедает их — счёт как у балла дня`() {
        // Длинная запись с ценой 0 на весь день (дубль, авто-факт) и работа внутри.
        val c = BalanceLine.curve(
            listOf(
                BalanceLine.Span(-h, 24 * h, 0),
                BalanceLine.Span(8 * h, 10 * h, 10),
            ),
            0, day,
        )!!
        assertEquals(20.0, c.at(12 * h), eps)
    }

    @Test
    fun `квантиль — как percentile_cont в базе`() {
        val xs = listOf(10.0, 40.0, 20.0, 30.0)
        assertEquals(25.0, BalanceLine.quantile(xs, 0.5)!!, eps)
        assertEquals(32.5, BalanceLine.quantile(xs, 0.75)!!, eps)
        assertNull(BalanceLine.quantile(emptyList(), 0.5))
    }

    @Test
    fun `мера — свой медианный день к тому же часу, пустые дни не тянут к нулю`() {
        val today = 30 * day
        // Четыре прошлых дня: к 12:00 набрали 8, 20, 28, 40, к концу — вдвое больше.
        val spans = (1..4).flatMap { k ->
            val d = today - k * day
            listOf(BalanceLine.Span(d + 8 * h, d + 12 * h, 10 * k / 4), BalanceLine.Span(d + 14 * h, d + 18 * h, 10 * k / 4))
        } + BalanceLine.Span(today, today + h, 10) // сегодняшняя — не в истории
        val road = BalanceLine.Road.of(BalanceLine.history(spans, today, dayMs = day))!!
        assertEquals(24.0, road.at(12 * h, 0.5), eps)
        assertEquals(48.0, BalanceLine.denom(road, 20 * h, 0.0), eps)
        // Ночью медианный день около нуля — мера не мельче пола, завтрак за стенку не улетает.
        assertEquals(BalanceLine.DENOM_FLOOR, BalanceLine.denom(road, 6 * h, 0.0), eps)
        // Истории нет — одна мера на день, по крайнему баллу.
        assertEquals(30.0, BalanceLine.denom(null, 12 * h, 30.0), eps)
        assertEquals(BalanceLine.FLOOR, BalanceLine.denom(null, 12 * h, 0.0), eps)
        assertNull(BalanceLine.Road.of(emptyList()))
    }

    @Test
    fun `место в трубе — медиана ровно на стенке, дальше мягкая обочина`() {
        val w = BalanceLine.WALL_FRAC.toFloat()
        assertEquals(0f, BalanceLine.place(0.0, 40.0), 1e-6f)
        assertEquals(w / 2, BalanceLine.place(20.0, 40.0), 1e-6f)
        assertEquals(w, BalanceLine.place(40.0, 40.0), 1e-6f)
        assertEquals(-w, BalanceLine.place(-40.0, 40.0), 1e-6f)
        // Вдвое лучше медианы — на полпути от стенки к краю, к краю не доходит.
        assertEquals(w + (1 - w) / 2, BalanceLine.place(80.0, 40.0), 1e-6f)
        val far = BalanceLine.place(4000.0, 40.0)
        assert(far < 1f && far > 0.99f)
    }
}
