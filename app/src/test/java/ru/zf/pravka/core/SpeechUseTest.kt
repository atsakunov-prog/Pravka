package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.SpeechUse.Kind

// Счёт службы речи (владелец, 01.10.2026, снимок батареи: «Распознавание и
// синтез речи» 57 %, Правка 2 % — «проверь, что правка не тратит батарею»).
class SpeechUseTest {

    private val m = 60_000L

    @Test
    fun `сутки копятся по видам, раз за разом`() {
        var days = emptyList<SpeechUse.Day>()
        days = SpeechUse.add(days, "2026-10-01", Kind.TAKE, 2 * m)
        days = SpeechUse.add(days, "2026-10-01", Kind.TAKE, 3 * m)
        days = SpeechUse.add(days, "2026-10-01", Kind.WARM, 2 * m)
        assertEquals(1, days.size)
        assertEquals(5 * m, days[0].ms(Kind.TAKE))
        assertEquals(2, days[0].n(Kind.TAKE))
        assertEquals(1, days[0].n(Kind.WARM))
        assertEquals(0L, days[0].ms(Kind.REPLAY))
    }

    @Test
    fun `держим две недели, свежие в конце`() {
        var days = emptyList<SpeechUse.Day>()
        for (d in 1..20) days = SpeechUse.add(days, "2026-09-%02d".format(d), Kind.TAKE, m)
        assertEquals(SpeechUse.KEEP_DAYS, days.size)
        assertEquals("2026-09-20", days.last().day)
        assertEquals("2026-09-07", days.first().day)
    }

    @Test
    fun `отрицательное и больше суток не портят счёт`() {
        var days = SpeechUse.add(emptyList(), "2026-10-01", Kind.WARM, -5_000L)
        assertEquals(0L, days[0].ms(Kind.WARM))
        days = SpeechUse.add(days, "2026-10-01", Kind.VOICE, 40 * 60 * m, count = 0)
        assertEquals(24 * 60 * m, days[0].ms(Kind.VOICE))
        assertEquals(0, days[0].n(Kind.VOICE))
    }

    @Test
    fun `строки суток словами`() {
        assertEquals(listOf("служба речи Правке не понадобилась"), SpeechUse.lines(null))
        var days = SpeechUse.add(emptyList(), "2026-10-01", Kind.TAKE, 43 * m + 10_000, count = 55)
        days = SpeechUse.add(days, "2026-10-01", Kind.WARM, 112 * m, count = 21)
        val lines = SpeechUse.lines(days[0])
        assertEquals("слушала 43 мин — 55 тейков", lines[0])
        assertEquals("держалась прогревом 1 ч 52 мин — 21 раз", lines[1])
        // Переразбора и голоса не было — строк нет.
        assertEquals(2, lines.size)
        days = SpeechUse.add(days, "2026-10-01", Kind.REPLAY, 3 * m, count = 4)
        assertTrue(SpeechUse.lines(days[0]).contains("разбирала заново 3 мин — 4 записи"))
    }

    @Test
    fun `длительность`() {
        assertEquals("0 мин", SpeechUse.dur(0))
        assertEquals("меньше минуты", SpeechUse.dur(30_000))
        assertEquals("2 ч", SpeechUse.dur(120 * m))
        assertEquals("слушала 1 мин — 1 тейк", SpeechUse.lines(SpeechUse.add(emptyList(), "d", Kind.TAKE, m)[0])[0])
    }
}
