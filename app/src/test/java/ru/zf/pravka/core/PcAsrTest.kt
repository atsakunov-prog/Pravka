package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.PcAsr.Outcome

class PcAsrTest {

    private val google = "иван привет скажи пожалуйста что там с договором выставили нам счёт"

    @Test
    fun `комп ответил вовремя — его текст, Google рядом для сравнения`() {
        val p = PcAsr.choose("google-net", google, PcAsr.Result(Outcome.OK, "иван привет скажи пожалуйста что там с договором выставили нам счёт за март"))
        assertEquals(PcAsr.ENGINE, p.engine)
        assertTrue(p.text.endsWith("за март"))
        assertEquals(google, p.googleAlt)
    }

    @Test
    fun `застрял, нет компа, неполный звук — текст Google без альтернативы`() {
        for (o in listOf(Outcome.STUCK, Outcome.UNAVAILABLE, Outcome.INCOMPLETE)) {
            val p = PcAsr.choose("google-net", google, PcAsr.Result(o, note = "почему"))
            assertEquals("google-net", p.engine)
            assertEquals(google, p.text)
            assertNull(p.googleAlt)
            assertEquals("почему", p.note)
        }
    }

    @Test
    fun `комп явно потерял звук — Google`() {
        val empty = PcAsr.choose("google-net", google, PcAsr.Result(Outcome.OK, ""))
        assertEquals("google-net", empty.engine)
        val short = PcAsr.choose("google-net", google, PcAsr.Result(Outcome.OK, "иван привет"))
        assertEquals("google-net", short.engine)
        // Короткая фраза: разница в словах ничего не значит — верим компу.
        val few = PcAsr.choose("google-net", "привет как дела", PcAsr.Result(Outcome.OK, "привет"))
        assertEquals(PcAsr.ENGINE, few.engine)
    }

    @Test
    fun `Google ничего не дал, а комп дал — текст компа`() {
        val p = PcAsr.choose("google-net", "", PcAsr.Result(Outcome.OK, "середина фразы которую облако потеряло"))
        assertEquals(PcAsr.ENGINE, p.engine)
        assertEquals("", p.googleAlt)
    }

    @Test
    fun `два застревания подряд переключают, успех сбрасывает, «нет компа» не считается`() {
        val b = PcAsr.Breaker()
        assertFalse(b.note(Outcome.STUCK))
        assertFalse(b.note(Outcome.OK))
        assertFalse(b.note(Outcome.STUCK))
        assertFalse(b.note(Outcome.UNAVAILABLE))
        assertFalse(b.note(Outcome.INCOMPLETE))
        assertTrue(b.note(Outcome.STUCK))
        // после переключения счёт с нуля
        assertEquals(0, b.stuckInRow)
        assertFalse(b.note(Outcome.STUCK))
    }

    @Test
    fun `пауза сторожа — десять минут, выбор остаётся, потом снова комп`() {
        val t0 = 1_800_000_000_000L
        val p = PcAsr.Pause().next(t0)
        assertEquals(PcAsr.PAUSE_MS, p.len)
        assertFalse(PcAsr.onPc(PcAsr.FINAL_PC, p, t0 + 60_000L))          // идёт пауза — Google
        assertTrue(PcAsr.onPc(PcAsr.FINAL_PC, p, t0 + PcAsr.PAUSE_MS))     // кончилась — снова комп, сам
        assertFalse(PcAsr.onPc(PcAsr.FINAL_GOOGLE, PcAsr.Pause(), t0))    // выбран Google руками — Google
        assertTrue(PcAsr.onPc(PcAsr.FINAL_PC, PcAsr.Pause(), t0))         // паузы не было — комп
    }

    @Test
    fun `застревает снова вскоре после паузы — вдвое длиннее, не больше двух часов, через час — снова десять минут`() {
        val t0 = 1_800_000_000_000L
        var p = PcAsr.Pause().next(t0)
        p = p.next(p.until + 5 * 60_000L)                                  // через 5 мин после конца
        assertEquals(2 * PcAsr.PAUSE_MS, p.len)
        repeat(10) { p = p.next(p.until + 60_000L) }
        assertEquals(PcAsr.PAUSE_MAX_MS, p.len)
        val later = p.next(p.until + PcAsr.PAUSE_RECENT_MS + 1)            // спокойный час — с начала
        assertEquals(PcAsr.PAUSE_MS, later.len)
    }

    @Test
    fun `имя тейка — имя файла звука без wav`() {
        assertEquals("take_1795180000000", PcAsr.takeId("take_1795180000000.wav"))
        assertNull(PcAsr.takeId("../x.wav"))
        assertNull(PcAsr.takeId("take 1.wav"))
    }
}
