package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.CalendarRules.End
import ru.zf.pravka.core.CalendarRules.Start

// Встречи из календаря — как работа (владелец, 27.09.2026); второй заход —
// встреча узнаёт себя в словах владельца, БЖЖ — спорт (05.10.2026).
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

    private fun seen(
        title: String,
        start: Long,
        category: String = "Работа: текущая",
        client: String = "",
        source: String = "voice",
        id: Long = 5L,
    ) = CalSeen(id, start, title, category, client, source)

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
        val v = CalendarRules.startVerdict(event(), t14 + 3 * m, seen("Работа: ЗФ", t14 - 2 * h), t14 - 2 * h)
        assertEquals(Start.START, v)
    }

    @Test
    fun `ещё не началась - ждём`() {
        assertEquals(Start.WAIT, CalendarRules.startVerdict(event(), t14 - 3 * m, null, 0L))
    }

    @Test
    fun `служба спала, событие началось час назад - не подхватываем`() {
        assertEquals(Start.SKIP, CalendarRules.startVerdict(event(), t14 + 50 * m, null, 0L))
    }

    @Test
    fun `владелец сказал что-то другое в 13-58 - не дублируем`() {
        val v = CalendarRules.startVerdict(event(), t14 + 2 * m, seen("Созвон с Ильёй", t14 - 2 * m), t14 - 2 * m)
        assertEquals(Start.SKIP, v)
    }

    @Test
    fun `в дороге - созвон из машины остаётся поездкой`() {
        val road = seen("Поездка из «дом»", t14 - h, category = "Передвижение: транспорт")
        assertEquals(Start.SKIP, CalendarRules.startVerdict(event(), t14 + 2 * m, road, t14 - h))
    }

    // ---- встреча узнаёт себя в словах владельца ----

    @Test
    fun `сказал «встречи в Птиц» до начала ПТИЦ - второй записи нет, его запись и есть встреча`() {
        // 02.10: «с 14:30 по 17:30 встречи в птиц»; 28.09: «звонок с птицам» в 11:35.
        val ptic = event("ПТИЦ - ЗФ (регулярный синк)")
        val said = seen("Встречи в Птиц", t14 - 5 * m, category = "Работа: звонки")
        assertEquals(Start.ADOPT, CalendarRules.startVerdict(ptic, t14 + 2 * m, said, said.start))
        val call = seen("Звонок с Птицами", t14 - 20 * m, category = "Звонки")
        assertEquals(Start.ADOPT, CalendarRules.startVerdict(ptic, t14 + 2 * m, call, call.start))
    }

    @Test
    fun `та же встреча в других падежах и с клиентом`() {
        val dodo = event("(ДоДо — Знакомый Финансист) Регулярная по сделке.")
        assertTrue(CalendarRules.sameMeeting(dodo, seen("Звонок с Додо Пиццей", t14)))
        assertTrue(CalendarRules.sameMeeting(event("Рубрик - ЗФ Kick-off"), seen("Разговор с Рубриком", t14)))
        assertTrue(CalendarRules.sameMeeting(event("Встреча_ДоДо_Экспо"), seen("Подготовка", t14, client = "Додо")))
        assertTrue(CalendarRules.sameMeeting(event("Ольга"), seen("Сессия с Ольгой", t14)))
    }

    @Test
    fun `общие слова встреч и своя сторона ничего не связывают`() {
        // «Встреча», «созвон», «ЗФ», «регулярный» есть почти везде.
        assertFalse(CalendarRules.sameMeeting(event("ПТИЦ - ЗФ (регулярный синк)"), seen("Встреча с Ильёй", t14)))
        assertFalse(CalendarRules.sameMeeting(event("Встреча"), seen("Встреча с Ильёй", t14)))
        assertFalse(CalendarRules.sameMeeting(event("Рубрик - ЗФ Kick-off"), seen("Разбор ЗФ", t14)))
        assertFalse(CalendarRules.sameMeeting(event("Семинар по налогам"), seen("Время с семьёй", t14)))
        assertFalse(CalendarRules.sameMeeting(event("Ольга"), seen("Приём у терапевта", t14)))
    }

    @Test
    fun `работа про того же клиента с утра - встреча внутри неё не пишется`() {
        val ptic = event("ПТИЦ - ЗФ (регулярный синк)")
        val block = seen("ПТИЦ: управленка", t14 - 3 * h)
        assertEquals(Start.SKIP, CalendarRules.startVerdict(ptic, t14 + 2 * m, block, block.start))
    }

    @Test
    fun `сказал про ПТИЦ, а едет - это дорога, а не встреча`() {
        // 02.10: «еду на встречу в птиц» в 14:06 — поездка, к ней встречу не пристёгиваем.
        val road = seen("Поездка навстречу в Птиц", t14 - 20 * m, category = "Передвижение: транспорт")
        assertEquals(Start.SKIP, CalendarRules.startVerdict(event("ПТИЦ - ЗФ (регулярный синк)"), t14 + 2 * m, road, road.start))
    }

    // ---- БЖЖ: спорт, три слоя в одну запись ----

    @Test
    fun `БЖЖ в календаре - BJJ спортом, а не созвон`() {
        val bjj = event("БЖЖ")
        assertEquals(CalendarRules.Kind.SPORT, CalendarRules.kind(bjj))
        assertEquals(IcuFixes.BJJ_NAME, CalendarRules.entryTitle(bjj))
        assertEquals(IcuFixes.BJJ_CATEGORY, CalendarRules.entryCategory(bjj, "Работа: звонки"))
        assertEquals("Работа: звонки", CalendarRules.entryCategory(event(), "Работа: звонки"))
    }

    @Test
    fun `сказал «бжж и занимаюсь борьбой» - календарь узнаёт и не пишет второе`() {
        // 01.10: «Занятие борьбой» с 13:16 к «БЖЖ» в 13:30.
        val said = seen("Занятие борьбой", t14 - 14 * m, category = "Спорт: прочее")
        assertEquals(Start.ADOPT, CalendarRules.startVerdict(event("БЖЖ"), t14 + 2 * m, said, said.start))
    }

    @Test
    fun `едет на борьбу и не переключился - БЖЖ начинается, дорога закрывается`() {
        // 22.09: «сейчас поехал на бразильское джиу» в 12:25, БЖЖ по календарю в 12:45.
        val road = seen("Поездка на бразильское джиу-джитсу", t14 - 20 * m, category = "Передвижение: транспорт")
        assertEquals(Start.START, CalendarRules.startVerdict(event("БЖЖ"), t14 + 2 * m, road, road.start))
    }

    // ---- конец ----

    @Test
    fun `конец с запасом - закрываем, если открыта наша встреча`() {
        val e = event()
        val ours = seen(e.title, t14, source = "calendar", id = 77L)
        assertEquals(End.WAIT, CalendarRules.endVerdict(e, e.end, t14 + h + 2 * m, ours, 77L))
        assertEquals(End.CLOSE, CalendarRules.endVerdict(e, e.end, t14 + h + 6 * m, ours, 77L))
    }

    @Test
    fun `владелец переключил дело сам - не трогаем`() {
        val e = event()
        assertEquals(End.NONE, CalendarRules.endVerdict(e, e.end, t14 + h + 6 * m, seen("Обед", t14 + 50 * m, id = 78L), 77L))
        assertEquals(End.NONE, CalendarRules.endVerdict(e, e.end, t14 + h + 6 * m, null, 77L))
    }

    @Test
    fun `забыл переключиться со своей записи о встрече - закончилась`() {
        // Сказал «созвон с птицами» в 14:07, сам не закрыл: по концу события закрываем.
        val e = event("ПТИЦ - ЗФ (регулярный синк)")
        val said = seen("Созвон с птицами", t14 + 7 * m, id = 90L)
        assertEquals(End.CLOSE, CalendarRules.endVerdict(e, e.end, t14 + h + 6 * m, said, 77L))
        // Но сказанное ПОСЛЕ конца встречи — уже другое дело с тем же клиентом.
        val after = seen("Письмо Птицам по итогам", t14 + h + 2 * m, id = 91L)
        assertEquals(End.NONE, CalendarRules.endVerdict(e, e.end, t14 + h + 6 * m, after, 77L))
    }

    @Test
    fun `событие перенесли раньше начала записи - не закрываем`() {
        val e = event(start = t14 - 2 * h, end = t14 - h)
        val ours = seen("Созвон Tasty Coffee", t14, source = "calendar", id = 77L)
        assertEquals(End.NONE, CalendarRules.endVerdict(e, e.end, t14 + 10 * m, ours, 77L))
    }

    // ---- слова владельца забирают догадку ----

    @Test
    fun `сказал то же самое после начала - его запись забирает начало`() {
        val e = event("ПТИЦ - ЗФ (регулярный синк)")
        val guess = seen(CalendarRules.entryTitle(e), t14, source = "calendar", id = 1L)
        val said = seen("Созвон с птицами", t14 + 7 * m, id = 2L)
        assertTrue(CalendarRules.absorbs(e, guess, t14 + 7 * m, said, e.end))
    }

    @Test
    fun `сказал другое, правил догадку или сказал не встык - догадка остаётся`() {
        val e = event("ПТИЦ - ЗФ (регулярный синк)")
        val guess = seen(CalendarRules.entryTitle(e), t14, source = "calendar", id = 1L)
        assertFalse(CalendarRules.absorbs(e, guess, t14 + 7 * m, seen("Готовлю завтрак", t14 + 7 * m, id = 2L), e.end))
        assertFalse(CalendarRules.absorbs(e, guess.copy(title = "Синк с Тимофеем"), t14 + 7 * m, seen("Созвон с птицами", t14 + 7 * m), e.end))
        assertFalse(CalendarRules.absorbs(e, guess, t14 + 7 * m, seen("Созвон с птицами", t14 + 20 * m), e.end))
        assertFalse(CalendarRules.absorbs(e, guess, t14 + 7 * m, seen("Созвон с птицами", t14 + 7 * m, source = "calendar"), e.end))
    }

    // ---- название и клиент ----

    @Test
    fun `название без своей стороны`() {
        assertEquals("ДоДо: Регулярная по сделке", CalendarRules.cleanTitle("(ДоДо — Знакомый Финансист) Регулярная по сделке."))
        assertEquals(
            "Николай Евченко: Знакомство, обсудим активы",
            CalendarRules.cleanTitle("(Александр Цакунов — Николай Евченко) Знакомство, обсудим активы"),
        )
        assertEquals(
            "Роман Чустузян: Знакомство, обсудим активы",
            CalendarRules.cleanTitle("(Александр Цакунов - Роман Чустузян) Знакомство, обсудим активы"),
        )
        assertEquals("Типография и поиск инвестиций", CalendarRules.cleanTitle("(Цакунов) Типография и поиск инвестиций"))
        assertEquals("ПТИЦ (регулярный синк)", CalendarRules.cleanTitle("ПТИЦ - ЗФ (регулярный синк)"))
        assertEquals("Рубрик Kick-off", CalendarRules.cleanTitle("Рубрик - ЗФ Kick-off"))
        assertEquals("Встреча ДоДо Экспо", CalendarRules.cleanTitle("Встреча_ДоДо_Экспо"))
        assertEquals("Модель санатория \"Верба Майер\"", CalendarRules.cleanTitle("Модель санатория \"Верба Майер\""))
        assertEquals("Ольга", CalendarRules.cleanTitle("Ольга"))
    }

    @Test
    fun `клиент - проект Дел по имени или алиасу`() {
        val projects = listOf(
            CalProject("p1", "ПТИЦ", listOf("Птиц", "ПТИЦА")),
            CalProject("p2", "Додо", listOf("Додо Пицца", "Dodo")),
            CalProject("p3", "Рубрик", listOf("Rubric")),
            CalProject("p4", "Q Robotics", listOf("Q-Rabotix", "Кью Роботикс")),
            CalProject("p5", "Доставка морем", listOf("ДМ")),
        )
        assertEquals("p1", CalendarRules.projectOf("ПТИЦ - ЗФ (регулярный синк)", projects)?.id)
        assertEquals("p2", CalendarRules.projectOf("Встреча_ДоДо_Вим Капитал", projects)?.id)
        assertEquals("p3", CalendarRules.projectOf("Рубрик - ЗФ Kick-off", projects)?.id)
        assertNull(CalendarRules.projectOf("Лонг-лист инвесторов IQnergy", projects))
        assertNull(CalendarRules.projectOf("Ольга", projects))
        // Два клиента в одном названии — не угадываем.
        assertNull(CalendarRules.projectOf("ПТИЦ и Додо: общий звонок", projects))
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
    fun `к заполнителю, потерям, авто-факту, дороге и чужой встрече не возвращаемся`() {
        assertFalse(CalendarRules.resumable("не размечено", "Не размечено", "gap", t14, t14, "Созвон"))
        assertFalse(CalendarRules.resumable("потери", "Потери", "auto", t14, t14, "Созвон"))
        assertFalse(CalendarRules.resumable("сон", "Сон", "auto", t14, t14, "Созвон"))
        assertFalse(CalendarRules.resumable("Поездка на борьбу", "Передвижение: вело", "voice", t14, t14, "BJJ: борьба"))
        assertFalse(CalendarRules.resumable("ПТИЦ (регулярный синк)", "Работа: звонки", "calendar", t14, t14, "ДоДо"))
    }

    @Test
    fun `дело кончилось задолго до встречи - не оно`() {
        assertFalse(CalendarRules.resumable("Обед", "Еда", "voice", t14 - 20 * m, t14, "Созвон"))
    }

    // ---- слова ----

    @Test
    fun `одно слово в разных падежах`() {
        assertTrue(CalendarRules.stemEq("птиц", "птицами"))
        assertTrue(CalendarRules.stemEq("рубрик", "рубриком"))
        assertTrue(CalendarRules.stemEq("ольга", "ольгой"))
        assertFalse(CalendarRules.stemEq("семья", "семинар"))
        assertFalse(CalendarRules.stemEq("додо", "доставка"))
    }
}
