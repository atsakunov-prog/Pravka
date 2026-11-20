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
    fun `имя тейка — имя файла звука без wav`() {
        assertEquals("take_1795180000000", PcAsr.takeId("take_1795180000000.wav"))
        assertNull(PcAsr.takeId("../x.wav"))
        assertNull(PcAsr.takeId("take 1.wav"))
    }
}
