package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Личная запись ленты не наследует клиента из прошлой (задание 9, 09.10.2026). */
class ZasechkaClientTest {

    @Test
    fun `личная категория без клиента в словах — клиента нет`() {
        assertTrue(ZasechkaClient.personal("Семья"))
        assertTrue(ZasechkaClient.personal("Спорт: силовая"))
        assertTrue(ZasechkaClient.personal("Не размечено"))
        assertFalse(ZasechkaClient.personal("Работа"))
        // «Время с семьёй» после работы на Бету — Бета не переносится.
        assertEquals("", ZasechkaClient.keep("Семья", "Бета Групп", "время с семьёй"))
        // Названа — остаётся: обед с клиентом — личная еда, но про него.
        assertEquals("Бета Групп", ZasechkaClient.keep("Еда", "Бета Групп", "обед с Бетой, обсуждали модель"))
        // Рабочая запись — как сказала модель.
        assertEquals("Бета Групп", ZasechkaClient.keep("Работа", "Бета Групп", "дальше модель"))
        assertEquals("", ZasechkaClient.keep("Семья", "", "ужин"))
    }
}
