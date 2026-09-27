package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.CalendarRules.End
import ru.zf.pravka.core.CalendarRules.Start

// Встречи из календаря — как работа (владелец, 27.09.2026).
class CalendarRulesTest {

    private val h = 3_600_000L
    private val m = 60_000L
    private val t14 = 200 * h

    private fun event(
        title: String = "Созвон Tasty Coffee",
        start: Long = t14,
        end: Long = t14 + h,
        allDay: Boolean = false,
        declined: Boolean = false,
        free: Boolean = false,
        calendar: String = "a.tsakunov@znakomiy.pro",
        primary: Boolean = true,
    ) = CalEvent(1L, 10L, title, start, end, allDay, declined, free, calendar, primary)

    // ---- что вообще смотрим ----

    @Test
    fun `обычная встреча в основном календаре - смотрим`() {
        assertTrue(CalendarRules.eligible(event(), null))
    }

    @Test
    fun `весь день, отклонённое и свободное - мимо`() {
        assertFalse(CalendarRules.eligible(event(allDay = true), null))
        assertFalse(CalendarRules.eligible(event(declined = true), null))
        assertFalse(CalendarRules.eligible(event(free = true), null))
    }

    @Test
    fun `семейный календарь с завода не смотрится, выбранный - смотрится`() {
        val fam = event(title = "Боря — бассейн", calendar = "Семья", primary = false)
        assertFalse(CalendarRules.eligible(fam, null))
        // Выключили все календари — не смотрим ничего, даже основной.
        assertFalse(CalendarRules.eligible(event(), emptySet()))
        assertTrue(CalendarRules.eligible(fam, setOf("Семья")))
        // Выбрали только семейный — основной больше не смотрится.
        assertFalse(CalendarRules.eligible(event(), setOf("Семья")))
    }

    @Test
    fun `конференция на восемь часов - не встреча`() {
        assertFalse(CalendarRules.eligible(event(end = t14 + 8 * h), null))
    }

    // ---- начало ----

    @Test
    fun `началась - начинаем с момента начала`() {
        val v = CalendarRules.startVerdict(event(), t14 + 3 * m, "Работа: ЗФ", "Работа: текущая", t14 - 2 * h)
        assertEquals(Start.START, v)
    }

    @Test
    fun `ещё не началась - ждём`() {
        assertEquals(Start.WAIT, CalendarRules.startVerdict(event(), t14 - 3 * m, null, null, 0L))
    }

    @Test
    fun `служба спала, событие началось час назад - не подхватываем`() {
        assertEquals(Start.SKIP, CalendarRules.startVerdict(event(), t14 + 50 * m, null, null, 0L))
    }

    @Test
    fun `владелец сказал созвон в 13-58 - не дублируем`() {
        val v = CalendarRules.startVerdict(event(), t14 + 2 * m, "Созвон с Ильёй", "Работа: звонки", t14 - 2 * m)
        assertEquals(Start.SKIP, v)
    }

    @Test
    fun `в дороге - созвон из машины остаётся поездкой`() {
        val v = CalendarRules.startVerdict(event(), t14 + 2 * m, "Поездка из «дом»", "Передвижение: транспорт", t14 - h)
        assertEquals(Start.SKIP, v)
    }

    // ---- конец ----

    @Test
    fun `конец с запасом - закрываем, если открыта наша встреча`() {
        assertEquals(End.WAIT, CalendarRules.endVerdict(event().end, t14 + h + 2 * m, 77L, 77L))
        assertEquals(End.CLOSE, CalendarRules.endVerdict(event().end, t14 + h + 6 * m, 77L, 77L))
    }

    @Test
    fun `владелец переключил дело сам - не трогаем`() {
        assertEquals(End.NONE, CalendarRules.endVerdict(event().end, t14 + h + 6 * m, 78L, 77L))
        assertEquals(End.NONE, CalendarRules.endVerdict(event().end, t14 + h + 6 * m, null, 77L))
    }

    // ---- к чему вернуться ----

    @Test
    fun `возвращаемся к работе, которую встреча прервала`() {
        assertTrue(
            CalendarRules.resumable(
                "Работа: ЗФ", "Работа: текущая", "voice", t14 - 10_000L, t14, "Созвон Tasty Coffee",
            )
        )
    }

    @Test
    fun `к заполнителю, потерям и авто-факту не возвращаемся`() {
        assertFalse(CalendarRules.resumable("не размечено", "Не размечено", "gap", t14, t14, "Созвон"))
        assertFalse(CalendarRules.resumable("потери", "Потери", "auto", t14, t14, "Созвон"))
        assertFalse(CalendarRules.resumable("сон", "Сон", "auto", t14, t14, "Созвон"))
    }

    @Test
    fun `дело кончилось задолго до встречи - не оно`() {
        assertFalse(CalendarRules.resumable("Обед", "Еда", "voice", t14 - 20 * m, t14, "Созвон"))
    }
}
