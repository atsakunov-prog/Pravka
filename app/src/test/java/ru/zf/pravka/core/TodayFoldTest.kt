package ru.zf.pravka.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Баг №2: шапка «Сегодня» прыгала у линии «сейчас» — сжатая открывалась,
// окно ленты сжималось, «сейчас» уходило за край, и снова по кругу.
class TodayFoldTest {

    private val grow = 600

    private fun view(nowTop: Int?, nowBottom: Int? = nowTop?.plus(70), first: Int = 10, last: Int = 30, viewEnd: Int = 1800) =
        TodayFold.View(first = first, firstOffset = 0, last = last, nowTop = nowTop, nowBottom = nowBottom, viewEnd = viewEnd)

    @Test
    fun `сейчас ушло ниже окна - смотрим прошлое, шапка сжата`() {
        assertTrue(TodayFold.next(false, nowIndex = 40, v = view(null), grow = grow, today = true))
        // Выложено, но за строкой «сказать» — тоже ниже окна.
        assertTrue(TodayFold.next(false, nowIndex = 25, v = view(1820), grow = grow, today = true))
    }

    @Test
    fun `сжатая у нижнего края не разворачивается - не прыгает`() {
        // «Сейчас» показалось внизу: развернись шапка — оно снова уйдёт за край.
        assertTrue(TodayFold.next(true, nowIndex = 25, v = view(1500), grow = grow, today = true))
        // Долистали так, что место есть и после разворота — разворачиваемся.
        assertFalse(TodayFold.next(true, nowIndex = 25, v = view(1000), grow = grow, today = true))
    }

    @Test
    fun `развёрнутая с видимым сейчас остаётся развёрнутой, сейчас выше окна - развёрнута`() {
        assertFalse(TodayFold.next(false, nowIndex = 25, v = view(1700), grow = grow, today = true))
        assertFalse(TodayFold.next(true, nowIndex = 5, v = view(null), grow = grow, today = true))
    }

    @Test
    fun `другой день не сжимается, день без сейчас - без дрожи`() {
        assertFalse(TodayFold.next(true, nowIndex = 25, v = view(null, last = 20), grow = grow, today = false))
        val top = TodayFold.View(0, 0, 10, null, null, 1800)
        val scrolled = TodayFold.View(0, 40, 10, null, null, 1800)
        assertFalse(TodayFold.next(true, nowIndex = -1, v = top, grow = grow, today = true))
        assertTrue(TodayFold.next(true, nowIndex = -1, v = scrolled, grow = grow, today = true))
        assertFalse(TodayFold.next(false, nowIndex = -1, v = scrolled, grow = grow, today = true))
    }
}
