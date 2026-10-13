package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

// Профиль дня — «профиль горы» (Правка 5.0, этап 2): очки копятся по отрезкам
// ленты тем же счётом, что балл дня; ноль пунктиром — только если день уходил вниз.
class DayProfileTest {

    private val day = 1_000_000_000_000L - 1_000_000_000_000L % 86_400_000L
    private fun at(h: Int, m: Int = 0) = day + h * 3_600_000L + m * 60_000L

    @Test
    fun `очки копятся по отрезкам, итог равен баллу дня`() {
        val p = DayProfile.of(
            listOf(
                DayProfile.Span(at(0), at(8), "Сон", 0),
                DayProfile.Span(at(9), at(11), "Работа: текущая", 10),
                DayProfile.Span(at(11), at(12), "Не размечено", -5, gap = true),
                DayProfile.Span(at(12), 0L, "Спорт: бег", 9),
            ),
            day, now = at(12, 15),
        )
        assertEquals(4, p.segs.size)
        assertEquals(20.0, p.segs[1].after, 1e-9)
        assertEquals(15.0, p.segs[2].after, 1e-9)
        // Идущая запись — до «сейчас»: четверть часа бега.
        assertEquals(17.25, p.value, 1e-9)
        assertTrue(p.segs[3].open)
        assertEquals(735f, p.segs[3].toMin, 1e-3f)
        assertEquals(735f, p.nowMin!!, 1e-3f)
        assertEquals(60, p.unmarkedMin)
        // Пик — в конце работы, 20 очков в 11:00.
        assertEquals(20.0, p.peak!!, 1e-9)
        assertEquals(660f, p.peakMin!!, 1e-3f)
        assertFalse(p.dipped)
        assertEquals("пик +20 в 11:00 · не размечено 1 ч", DayProfile.caption(p))
    }

    @Test
    fun `день ниже нуля — ноль рисуется`() {
        val p = DayProfile.of(
            listOf(
                DayProfile.Span(at(9), at(11), "Потери", -5),
                DayProfile.Span(at(11), at(12), "Работа: текущая", 10),
            ),
            day, now = at(13),
        )
        assertTrue(p.dipped)
        assertEquals(-10.0, p.low, 1e-9)
        assertEquals(0.0, p.value, 1e-9)
        // Пика выше нуля не было — подписи о нём нет.
        assertNull(p.peak)
        assertEquals("", DayProfile.caption(p))
        // Ноль — внутри рисунка, а не у его низа.
        assertTrue(p.y(0.0) < p.y(-10.0))
        assertEquals(1 - DayProfile.PAD, p.y(-10.0).toDouble(), 1e-6)
    }

    @Test
    fun `сон с вечера режется полуночью, прошлый день — до конца суток`() {
        val p = DayProfile.of(
            listOf(
                DayProfile.Span(at(-2), at(7), "Сон", 0),
                DayProfile.Span(at(22), 0L, "Отдых", -2),
            ),
            day, now = at(26),
        )
        assertEquals(0f, p.segs[0].fromMin, 1e-3f)
        // Идущий отдых обрезан концом суток: два часа по −2.
        assertEquals(-4.0, p.value, 1e-9)
        assertNull(p.nowMin)
    }

    @Test
    fun `пустой день — шкала не схлопывается`() {
        val p = DayProfile.of(emptyList(), day, now = at(10))
        assertTrue(p.empty)
        assertEquals(1.0, p.high, 1e-9)
        assertEquals(0.0, p.low, 1e-9)
        assertEquals(1 - DayProfile.PAD, p.y(0.0).toDouble(), 1e-6)
    }

    @Test
    fun `из ленты — цена часа по справочнику, заполнитель — не размечено`() {
        fun e(id: Long, s: Long, en: Long, cat: String, src: String = "voice") =
            ZasechkaStore.Entry(id, s, en, "", cat, cat, "", 0, src, s)
        val p = DayProfile.ofEntries(
            listOf(
                e(1, at(9), at(10), "Работа: текущая"),
                e(2, at(10), at(11), "Не размечено", "gap"),
                e(3, at(-30), at(-29), "Работа: текущая"),
            ),
            listOf(ZasechkaStore.Category("Работа: текущая", "", value = 10), ZasechkaStore.Category("Не размечено", "", value = -5)),
            day, now = at(12),
        )
        assertEquals(2, p.segs.size)
        assertTrue(p.segs[1].gap)
        assertEquals(5.0, p.value, 1e-9)
        assertEquals(60, p.unmarkedMin)
    }
}
