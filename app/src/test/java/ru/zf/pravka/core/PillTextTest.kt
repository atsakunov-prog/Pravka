package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// Текст итога в пилюле (docs/pravka.md, «Итог в пилюле»): без эмодзи в начале строк.
class PillTextTest {

    @Test
    fun `значки в начале строк снимаются`() {
        assertEquals("Созвон с Ильёй с 11:00 · Работа", PillText.plain("⏱ Созвон с Ильёй с 11:00 · Работа"))
        assertEquals("Геркулес, кофе\n540 ккал — в дневнике", PillText.plain("🍽 Геркулес, кофе\n540 ккал — в дневнике"))
        assertEquals("3 дела в Todoist", PillText.plain("✓ 3 дела в Todoist"))
        assertEquals("Не записал — дела уже нет в ленте", PillText.plain("💬 Не записал — дела уже нет в ленте"))
    }

    @Test
    fun `кавычки, скобки и знаки сумм остаются`() {
        assertEquals("«Созвон» → «Встреча»", PillText.plain("«Созвон» → «Встреча»"))
        assertEquals("(пусто)", PillText.plain("(пусто)"))
        assertEquals("−350 ₽ кофе", PillText.plain("−350 ₽ кофе"))
    }

    @Test
    fun `пустые строки после снятия пропадают`() {
        assertEquals("Записано", PillText.plain("✓\nЗаписано"))
        assertEquals("", PillText.plain("🤷"))
    }

    @Test
    fun `текст без значков не меняется`() {
        assertEquals("Ещё раз — и пишу", PillText.plain("Ещё раз — и пишу"))
    }
}
