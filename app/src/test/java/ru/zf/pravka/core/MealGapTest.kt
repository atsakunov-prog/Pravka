package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

// «В ленте идёт приём пищи, а в дневнике пусто» (доводка 5.0, 14.10.2026): сверка ленты с дневником без Claude.
class MealGapTest {

    private val min = 60_000L
    private val now = 1_760_000_000_000L

    private fun e(id: Long, startAgoMin: Long, endAgoMin: Long?, category: String = "Еда", title: String = "Обед", source: String = "voice") =
        ZasechkaStore.Entry(
            id = id, start = now - startAgoMin * min, end = endAgoMin?.let { now - it * min } ?: 0L, raw = "обедаю",
            title = title, category = category, client = "", useful = 0, source = source, createdAt = now,
        )

    @Test
    fun `идёт обед, а приёма нет — подсказка про него`() {
        val g = MealGap.find(listOf(e(1, 20, null)), emptyList(), now)!!
        assertEquals(1L, g.entryId)
        assertTrue(g.open)
        assertEquals("Обед", g.title)
        assertTrue(MealGap.words(g).startsWith("Сейчас в ленте «Обед» (с "))
        assertTrue(MealGap.words(g).endsWith("а в дневнике еды пусто. Записать?"))
    }

    @Test
    fun `приём в окне с запасом — уже записано`() {
        val lunch = listOf(e(1, 40, 10))
        // Сфотографировал тарелку за 20 минут до «обедаю» — его.
        assertNull(MealGap.find(lunch, listOf(now - 60 * min), now))
        // Записал через полчаса после конца — тоже.
        assertNull(MealGap.find(lunch, listOf(now + 20 * min), now))
        // Вчерашний ужин — не этот обед.
        val g = MealGap.find(lunch, listOf(now - 20 * 60 * min), now)!!
        assertTrue(!g.open)
        assertTrue(MealGap.words(g).startsWith("В ленте был «Обед» ("))
    }

    @Test
    fun `давний, короткий, не еда, дыра и «не надо» — подсказки нет`() {
        assertNull(MealGap.find(listOf(e(1, 200, 120)), emptyList(), now))
        assertNull(MealGap.find(listOf(e(1, 12, 10)), emptyList(), now))
        assertNull(MealGap.find(listOf(e(1, 20, null, category = "Работа: текущая")), emptyList(), now))
        assertNull(MealGap.find(listOf(e(1, 20, null, source = "gap")), emptyList(), now))
        assertNull(MealGap.find(listOf(e(1, 20, null)), emptyList(), now, dismissed = setOf(1L)))
        // Верхний уровень «Еда: перекус» — еда.
        assertEquals(2L, MealGap.find(listOf(e(2, 20, null, category = "Еда: перекус")), emptyList(), now)?.entryId)
    }

    @Test
    fun `из двух — последний`() {
        val g = MealGap.find(listOf(e(1, 80, 60, title = "Завтрак"), e(2, 15, null)), emptyList(), now)
        assertEquals(2L, g?.entryId)
        // Последний записан — про прежний не спрашиваем: он уже давний или тоже в окне.
        assertNull(MealGap.find(listOf(e(1, 80, 60, title = "Завтрак"), e(2, 15, null)), listOf(now - 5 * min), now))
    }
}
