package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Экран Засечки вида v2 (Правка 5.0, этап 3): итог дня по верхнему уровню
// категории, свёрнутая лента, «записано голосом», таймер и подписи. Данные —
// выдуманные.
class ZasechkaDayTest {

    @Test
    fun `итог дня — подкатегории складываются в верхний уровень, длинное сверху`() {
        val t = ZasechkaDay.totals(
            listOf(
                ZasechkaDay.CatIn("Работа: текущая", 90, 15),
                ZasechkaDay.CatIn("Сон", 420, 0),
                ZasechkaDay.CatIn("Работа: звонки", 30, 4),
                ZasechkaDay.CatIn("Не размечено", 45, -4),
                ZasechkaDay.CatIn("Передвижение: транспорт", 40, -1),
                ZasechkaDay.CatIn("Еда", 0, 0),
                ZasechkaDay.CatIn("", 10, 0),
            ),
        )
        assertEquals(listOf("Сон", "Работа", "не размечено", "Передвижение"), t.map { it.label })
        val work = t[1]
        assertEquals(120, work.minutes)
        assertEquals(19, work.points)
        // Цвет — по самой длинной подкатегории; у дыры — свой флаг.
        assertEquals("Работа: текущая", work.category)
        assertFalse(work.gap)
        assertTrue(t[2].gap)
    }

    private fun seg(id: Long, current: Boolean = false) =
        Lenta.Row.Seg(id, id * 1000, id * 1000 + 500, "дело $id", "Быт", 1, "Быт · голос", 10, gap = false, current = current)

    private fun ev(id: Long) = Lenta.Row.Event(id, id * 1000 + 100, Lenta.Kind.FOOD, "Обед · 500 ккал", "meal:$id")

    @Test
    fun `свёрнутая лента — отрезки с их событиями, не больше заданного`() {
        val rows = listOf(seg(9, current = true), seg(8), ev(8), seg(7), seg(6), ev(6), ev(6))
        val r = ZasechkaDay.recent(rows, segs = 3)
        assertEquals(listOf("s9", "s8", rows[2].key, "s7"), r.map { it.key })
        assertTrue(ZasechkaDay.hasMore(rows, segs = 3))
        assertFalse(ZasechkaDay.hasMore(rows, segs = 4))
        // Всё влезает — то же самое.
        assertEquals(rows, ZasechkaDay.recent(rows, segs = 10))
    }

    @Test
    fun `откуда записано — фразой, как слово в ленте`() {
        assertEquals("записано голосом", ZasechkaDay.said("voice", "Быт"))
        assertEquals("записано текстом", ZasechkaDay.said("text", "Быт"))
        assertEquals("начато из дела", ZasechkaDay.said("task", "Работа: текущая"))
        assertEquals("с часов", ZasechkaDay.said("auto", "Сон"))
        assertEquals("записано само", ZasechkaDay.said("auto", "Работа: звонки"))
        assertEquals("", ZasechkaDay.said("gap", "Не размечено"))
    }

    @Test
    fun `таймер — часы и минуты`() {
        assertEquals("0:08", ZasechkaDay.timer(8 * 60_000L + 59_000L))
        assertEquals("2:15", ZasechkaDay.timer(135 * 60_000L))
        assertEquals("0:00", ZasechkaDay.timer(-5_000L))
    }

    @Test
    fun `подписи шапки и профиля`() {
        val day = LocalDate.of(2026, 10, 13)
        assertEquals("вторник, 13 октября · очки +36", ZasechkaDay.subtitle(day, 36, 122, wide = false))
        assertEquals("вторник, 13 октября · очки +36 · не размечено 2 ч 2 м", ZasechkaDay.subtitle(day, 36, 122, wide = true))
        assertEquals("вторник, 13 октября · очки ${Fmt.MINUS}3", ZasechkaDay.subtitle(day, -3, 0, wide = true))

        val base = 1_000_000_000_000L - 1_000_000_000_000L % 86_400_000L
        fun at(h: Int) = base + h * 3_600_000L
        val p = DayProfile.of(
            listOf(
                DayProfile.Span(at(9), at(11), "Работа: текущая", 10),
                DayProfile.Span(at(11), at(12), "Потери", -5),
            ),
            base, now = at(13),
        )
        assertEquals("очки +15 · пик +20 в 11:00", ZasechkaDay.profileNote(p))
        // Пик и есть «сейчас» — о нём ни слова.
        val up = DayProfile.of(listOf(DayProfile.Span(at(9), at(11), "Работа: текущая", 10)), base, now = at(13))
        assertEquals("очки +20", ZasechkaDay.profileNote(up))
    }
}
