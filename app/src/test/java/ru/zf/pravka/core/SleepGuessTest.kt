package ru.zf.pravka.core

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.pravka.core.SleepGuess.Span

// Ночь по экрану — на случаях владельца (27.09.2026: «сон почему-то перестал
// синхронизироваться»). Времена — в UTC, чтобы тест не зависел от машины.
class SleepGuessTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val h = 3_600_000L
    private val m = 60_000L

    /** Момент «день D, HH:MM» в UTC; день 0 — вчера, день 1 — сегодня. */
    private fun at(day: Int, hh: Int, mm: Int = 0): Long {
        val c = Calendar.getInstance(zone)
        c.set(2026, Calendar.SEPTEMBER, 26 + day, hh, mm, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private val windowStart = at(0, 18)

    @Test
    fun `цельная ночь - как раньше`() {
        val on = listOf(
            Span(at(0, 21), at(0, 23, 30)),
            Span(at(1, 7, 10), at(1, 7, 20)),
        )
        val v = SleepGuess.guess(on, windowStart, at(1, 7, 30), zone)
        val n = v.night
        assertNotNull(n)
        assertEquals(at(0, 23, 30), n!!.start)
        assertEquals(at(1, 7, 10), n.end)
        assertEquals(1, n.pieces)
    }

    @Test
    fun `взгляд в три ночи сшивается - ночь одна`() {
        // Лёг в 23:30, в 3:05 посмотрел на часы две минуты, встал в 7:10.
        // Раньше выходило два куска по 3,5 часа и «сон 23:30–03:05».
        val on = listOf(
            Span(at(0, 21), at(0, 23, 30)),
            Span(at(1, 3, 5), at(1, 3, 7)),
            Span(at(1, 7, 10), at(1, 7, 20)),
        )
        val n = SleepGuess.guess(on, windowStart, at(1, 7, 30), zone).night
        assertNotNull(n)
        assertEquals(at(0, 23, 30), n!!.start)
        assertEquals(at(1, 7, 10), n.end)
        assertEquals(2, n.pieces)
        assertEquals(2 * m, n.stitchedMs)
    }

    @Test
    fun `уведомления засвечивают экран на секунды - ночь не рассыпается`() {
        // Три уведомления за ночь, каждое зажгло экран на 10 секунд: до
        // сшивания ни один кусок не дотягивал до трёх часов — сна «не было».
        val on = listOf(
            Span(at(0, 22), at(0, 23, 40)),
            Span(at(1, 1, 20), at(1, 1, 20) + 10_000L),
            Span(at(1, 2, 50), at(1, 2, 50) + 10_000L),
            Span(at(1, 5, 30), at(1, 5, 30) + 10_000L),
            Span(at(1, 7, 0), at(1, 7, 5)),
        )
        val n = SleepGuess.guess(on, windowStart, at(1, 7, 6), zone).night
        assertNotNull(n)
        assertEquals(at(0, 23, 40), n!!.start)
        assertEquals(at(1, 7, 0), n.end)
        assertEquals(4, n.pieces)
    }

    @Test
    fun `вечерний перерыв в семь минут не сшивается - сон не с восьми вечера`() {
        // Телефон лежал 20:00–21:50, взял на семь минут, лёг в 22:00... нет:
        // в 21:57 миг ещё вечерний (до 22:00), сшивать нельзя.
        val on = listOf(
            Span(at(0, 19), at(0, 20)),
            Span(at(0, 21, 50), at(0, 21, 57)),
            Span(at(1, 7, 0), at(1, 7, 5)),
        )
        val n = SleepGuess.guess(on, windowStart, at(1, 7, 6), zone).night
        assertNotNull(n)
        assertEquals(at(0, 21, 57), n!!.start)
        assertEquals(1, n.pieces)
    }

    @Test
    fun `долгий подъём ночью - двадцать минут телеграма - режет, берётся длинный кусок`() {
        val on = listOf(
            Span(at(0, 21), at(0, 23)),
            Span(at(1, 2, 0), at(1, 2, 20)),
            Span(at(1, 7, 0), at(1, 7, 5)),
        )
        val n = SleepGuess.guess(on, windowStart, at(1, 7, 6), zone).night
        assertNotNull(n)
        assertEquals(at(1, 2, 20), n!!.start)
        assertEquals(at(1, 7, 0), n.end)
    }

    @Test
    fun `экран погашен прямо сейчас - хвост не ночь, ещё спит`() {
        // Последнее событие — «погас» в 23:30, включения не было: тишина идёт.
        val on = listOf(Span(at(0, 21), at(0, 23, 30)))
        val v = SleepGuess.guess(on, windowStart, at(1, 5, 30), zone)
        assertNull(v.night)
    }

    @Test
    fun `дневной простой телефона - не сон`() {
        // Телефон лежал в ящике 13:00–17:30, ночь рассыпалась совсем.
        val on = listOf(
            Span(at(0, 18), at(0, 19)),
            Span(at(0, 23, 50), at(1, 0, 20)),
            Span(at(1, 2, 30), at(1, 2, 45)),
            Span(at(1, 5, 0), at(1, 5, 15)),
            Span(at(1, 7, 0), at(1, 13)),
            Span(at(1, 17, 30), at(1, 18)),
        )
        val v = SleepGuess.guess(on, windowStart, at(1, 18, 5), zone)
        assertNull(v.night)
        // Самый длинный разрыв — вечерний, 19:00–23:50: кончается не утром.
        assertEquals(4 * h + 50 * m, v.longestMs)
    }

    @Test
    fun `нет событий экрана вовсе - ночи нет, но и не падаем`() {
        val v = SleepGuess.guess(emptyList(), windowStart, at(1, 9), zone)
        assertNull(v.night)
        assertEquals(0, v.gaps)
    }

    @Test
    fun `подсказка подъёма - последнее утреннее включение после долгой тишины`() {
        val on = listOf(
            Span(at(0, 21), at(0, 23, 30)),
            Span(at(1, 3, 5), at(1, 3, 7)),
            Span(at(1, 7, 10), at(1, 7, 20)),
            Span(at(1, 7, 25), at(1, 8, 0)),
        )
        assertEquals(at(1, 7, 10), SleepGuess.wakeHint(on, windowStart, zone))
    }

    @Test
    fun `подсказки подъёма нет без утренней тишины`() {
        val on = listOf(Span(at(0, 21), at(0, 23, 30)), Span(at(0, 23, 40), at(1, 0, 10)))
        assertEquals(0L, SleepGuess.wakeHint(on, windowStart, zone))
    }
}
