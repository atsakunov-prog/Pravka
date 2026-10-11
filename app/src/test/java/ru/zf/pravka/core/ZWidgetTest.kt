package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.ZFace.Icon
import ru.zf.pravka.data.ZasechkaStore

// Виджет Засечки (docs/zasechka.md, «Виджет на главном экране»): «что идёт,
// кнопки записи еды, открыть „Сегодня“, поменять текущее».
class ZWidgetTest {

    private val now = 1_760_000_000_000L
    private val min = 60_000L

    private fun e(
        id: Long, startMinAgo: Long, title: String, category: String,
        open: Boolean = false, source: String = "voice", client: String = "",
    ) = ZasechkaStore.Entry(
        id = id,
        start = now - startMinAgo * min,
        end = if (open) 0L else now - (startMinAgo - 10) * min,
        raw = title, title = title, category = category, client = client,
        useful = 0, source = source, synced = false, createdAt = now,
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
    fun `ещё раз — свежие разные дела владельца, без идущего, дыр и автомата`() {
        val m = ZWidget.model(listOf(
            e(1, 300, "Почта", "Работа: текущая"),
            e(2, 240, "Обед", "Еда"),
            e(3, 200, "Сон", "Сон", source = "auto"),
            e(4, 150, "", "Не размечено", source = "gap"),
            e(5, 120, "обед", "Еда"),
            e(6, 90, "Без категории", ""),
            e(7, 60, "Прогулка", "Передвижение: пешком"),
            e(8, 30, "Созвон с Бетой", "Работа: звонки"),
            e(9, 5, "Созвон с Бетой", "Работа: звонки", open = true),
        ), now)
        // Идущий «Созвон» не повторяется, «обед» — одной кнопкой, самой свежей.
        assertEquals(listOf("Прогулка", "обед", "Почта"), m.again.map { it.title })
        assertEquals(listOf(7L, 5L, 1L), m.again.map { it.id })
        assertEquals(Icon.WALK, m.again[0].icon)
    }

    @Test
    fun `ещё раз — не старше двух недель и не больше трёх`() {
        val old = ZWidget.model(listOf(e(1, 15 * 24 * 60L, "Отпуск", "Отдых")), now)
        assertTrue(old.again.isEmpty())
        val many = ZWidget.model((1L..6L).map { e(it, 100 - it, "Дело $it", "Быт") }, now)
        assertEquals(3, many.again.size)
        assertEquals("Дело 6", many.again.first().title)
    }

    @Test
    fun `размер — строка, кнопки, всё`() {
        assertEquals(ZWidget.Size.ROW, ZWidget.size(250, 70))
        assertEquals(ZWidget.Size.ACTIONS, ZWidget.size(250, 130))
        assertEquals(ZWidget.Size.FULL, ZWidget.size(250, 200))
        assertTrue(ZWidget.wide(250))
        assertTrue(!ZWidget.wide(140))
    }

    @Test
    fun `подпись под минутами`() {
        assertEquals("мин", ZWidget.unit("25"))
        assertEquals("ч", ZWidget.unit("1:25"))
        assertEquals("", ZWidget.unit("2д"))
    }
}
