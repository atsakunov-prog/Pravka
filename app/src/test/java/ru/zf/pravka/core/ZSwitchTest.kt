package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

// «Переключить на …» на долгом нажатии «З» (08.11.2026): все категории кнопками, чаще начатые — выше.
class ZSwitchTest {

    private val now = 1_794_160_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min
    private var nextId = 1L

    private fun e(category: String, startAgo: Long, len: Long = 30 * min, source: String = "voice", created: Long = -1L,
                  title: String = category): ZasechkaStore.Entry {
        val start = now - startAgo
        val id = nextId++
        return ZasechkaStore.Entry(
            id = id, start = start, end = start + len, raw = "", title = title, category = category, client = "",
            useful = 0, source = source, createdAt = if (created < 0) start else created,
        )
    }

    private val cats = listOf("Сон", "Еда", "Быт", "Семья", "Отдых", "Не размечено", "Звонки")

    @Test
    fun `чаще начатое — выше, неиспользованные — в конце порядком справочника`() {
        val list = listOf(
            e("Еда", 1 * day), e("Еда", 2 * day), e("Еда", 3 * day),
            e("Семья", 1 * day), e("Семья", 2 * day),
            e("Отдых", 5 * day),
        )
        assertEquals(listOf("Еда", "Семья", "Отдых", "Сон", "Быт", "Звонки"), ZSwitch.order(cats, list, now))
    }

    @Test
    fun `не размечено кнопкой не бывает, дыры не считаются`() {
        val list = listOf(e("Не размечено", 1 * day, source = "gap"), e("Не размечено", 2 * day), e("Быт", 3 * day))
        val got = ZSwitch.order(cats, list, now)
        assertFalse("Не размечено" in got)
        assertEquals("Быт", got.first())
    }

    @Test
    fun `куски одного дела — одно начало`() {
        // Сон через полночь и дело, разрезанное тренировкой с часов: кусков больше, начало одно.
        val created = now - 2 * day
        val list = listOf(
            e("Сон", 2 * day, created = created, title = "Сон"),
            e("Сон", 2 * day - 4 * 60 * min, created = created, title = "Сон"),
            e("Сон", 2 * day - 6 * 60 * min, created = created, title = "Сон"),
            e("Быт", 1 * day), e("Быт", 3 * day),
        )
        assertEquals(listOf("Быт", "Сон"), ZSwitch.order(cats, list, now).take(2))
    }

    @Test
    fun `при равенстве начал — больше минут, старее месяца не в счёт`() {
        val list = listOf(
            e("Семья", 1 * day, len = 20 * min), e("Отдых", 2 * day, len = 90 * min),
            e("Звонки", 40 * day), e("Звонки", 41 * day), e("Звонки", 42 * day),
        )
        assertEquals(listOf("Отдых", "Семья"), ZSwitch.order(cats, list, now).take(2))
    }

    @Test
    fun `категория ленты без справочника — не кнопка, регистр не мешает`() {
        val list = listOf(e("еда", 1 * day), e("Старая категория", 1 * day), e("Старая категория", 2 * day))
        val got = ZSwitch.order(cats, list, now)
        assertEquals("Еда", got.first())
        assertFalse("Старая категория" in got)
    }
}
