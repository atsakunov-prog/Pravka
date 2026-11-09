package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.ZFace.Icon
import ru.zf.pravka.data.ZasechkaStore

// Виджет Засечки (docs/zasechka.md, «Виджет на главном экране»): «что идёт,
// кнопки записи еды, открыть „Сегодня“, поменять текущее»; 08.11.2026 — «чтобы
// там были и кнопки, и ещё была лента» («Пульт», вширь — две колонки).
class ZWidgetTest {

    private val now = 1_760_000_000_000L
    private val min = 60_000L

    private fun e(
        id: Long, startMinAgo: Long, title: String, category: String,
        open: Boolean = false, source: String = "voice", client: String = "", lenMin: Long = 10,
    ) = ZasechkaStore.Entry(
        id = id,
        start = now - startMinAgo * min,
        end = if (open) 0L else now - (startMinAgo - lenMin) * min,
        raw = title, title = title, category = category, client = client,
        useful = 0, source = source, createdAt = now - startMinAgo * min,
    )

    private fun hm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    @Test
    fun `что идёт — название, подпись с категорией, клиентом и началом, значок`() {
        val m = ZWidget.model(listOf(e(1, 25, "Созвон с Бетой", "Работа: звонки", open = true, client = "Бета Групп")), now)
        val n = m.now!!
        assertEquals("Созвон с Бетой", n.title)
        assertEquals("Работа: звонки · Бета Групп · с ${hm(now - 25 * min)}", n.sub)
        assertEquals(Icon.CALL, n.icon)
        assertEquals("25", ZFace.label(now - n.start))
    }

    @Test
    fun `ничего не идёт — пусто, дыра — со своим значком`() {
        assertNull(ZWidget.model(listOf(e(1, 60, "Обед", "Еда")), now).now)
        val gap = ZWidget.model(listOf(e(1, 8, "", "Не размечено", open = true, source = "gap")), now).now!!
        assertEquals("не размечено", gap.title)
        assertEquals(Icon.UNMARKED, gap.icon)
    }

    @Test
    fun `кнопки — все категории, частые сверху, идущая отмечена, без «Не размечено»`() {
        val cats = listOf("Работа: текущая", "Еда", "Семья", "Не размечено", "Чтение")
        val m = ZWidget.model(listOf(
            e(1, 300, "Обед", "Еда"),
            e(2, 200, "Ужин", "Еда"),
            e(3, 100, "Почта", "Работа: текущая"),
            e(4, 30, "С детьми", "Семья", open = true),
        ), now, cats)
        // Еда — два начала; Семья и Работа — по одному, Семья идёт уже 30 минут против 10 — выше; Чтения не было.
        assertEquals(listOf("Еда", "Семья", "Работа: текущая", "Чтение"), m.cats.map { it.name })
        assertEquals(listOf("Семья"), m.cats.filter { it.current }.map { it.name })
        assertEquals(Icon.FOOD, m.cats.first().icon)
        // Идёт дыра — отмечать нечего: «не размечено» — не категория.
        val gap = ZWidget.model(listOf(e(1, 10, "", "Не размечено", open = true, source = "gap")), now, cats)
        assertTrue(gap.cats.none { it.current })
    }

    @Test
    fun `лента сегодня — с полуночи, свежее сверху, идущее без конца, вчерашнее и пустое — прочь`() {
        val midnight = ZWidget.midnight(now)
        val sinceMidnight = (now - midnight) / min
        val m = ZWidget.model(listOf(
            e(1, sinceMidnight + 120, "Вчерашнее", "Отдых", lenMin = 60),
            e(2, sinceMidnight + 30, "Сон", "Сон", source = "auto", lenMin = sinceMidnight - 20), // до «50 минут назад»
            e(3, 50, "Мгновение", "Быт", lenMin = 0),
            e(4, 40, "", "Не размечено", source = "gap", lenMin = 15),
            e(5, 25, "Созвон", "Работа: звонки", open = true),
        ), now)
        assertEquals(listOf(5L, 4L, 2L), m.day.map { it.id })
        assertEquals(0L, m.day[0].to)
        assertTrue(m.day[1].gap)
        assertEquals("не размечено", m.day[1].title)
        // Сон с вечера обрезан полуночью: на виджете он с 00:00.
        assertEquals(midnight, m.day[2].from)
        assertEquals(now - 50 * min, m.day[2].to)
    }

    @Test
    fun `длительность строки ленты`() {
        assertEquals("12 мин", ZWidget.dur(12 * min))
        assertEquals("1 ч 05", ZWidget.dur(65 * min))
        assertEquals("8 ч", ZWidget.dur(480 * min))
        assertEquals("0 мин", ZWidget.dur(-5 * min))
    }

    @Test
    fun `раскладка — строка, пульт по высоте, узкий, вширь — две колонки`() {
        // Строка ячеек — только «что идёт».
        val row = ZWidget.plan(320, 76)
        assertEquals(0, row.cats + row.rows)
        assertFalse(row.strip || row.actions)
        // 4×4 «Пульт»: три в ряд, три ряда категорий, полоса, три строки ленты, кнопки.
        assertEquals(ZWidget.Plan(false, false, 3, 3, 3, strip = true, actions = true), ZWidget.plan(360, 476))
        // 4×2: сначала категории и полоса, кнопкам записи места нет; в 320 dp — три плитки в ряд.
        assertEquals(ZWidget.Plan(false, false, 2, 1, 0, strip = true, actions = false), ZWidget.plan(250, 180))
        assertEquals(3, ZWidget.plan(320, 180).cols)
        // Внутренний экран складного (снимок владельца 09.11.2026, ~397×608): четыре ряда категорий, полоса, пять
        // строк ленты и кнопки — и всё это в высоту виджета с запасом под крупный шрифт.
        assertEquals(ZWidget.Plan(false, false, 3, 4, 5, strip = true, actions = true), ZWidget.plan(397, 608))
        // Две ячейки шириной: минуты в кружке, плитки всё равно по две.
        val narrow = ZWidget.plan(180, 300)
        assertTrue(narrow.narrow)
        assertEquals(2, narrow.cols)
        // Широкий и низкий — две колонки: слева категории и кнопки, справа полоса и лента.
        assertEquals(ZWidget.Plan(true, false, 2, 4, 10, strip = true, actions = true), ZWidget.plan(660, 340))
        // Широкий, но квадратный — всё ещё «Пульт», плиток в ряд больше.
        val square = ZWidget.plan(600, 600)
        assertFalse(square.wide)
        assertEquals(4, square.cols)
    }

    @Test
    fun `подпись под минутами`() {
        assertEquals("мин", ZWidget.unit("25"))
        assertEquals("ч", ZWidget.unit("1:25"))
        assertEquals("", ZWidget.unit("2д"))
    }
}
