package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Строка Claude внизу вкладки Дел — мелочи отладки 16.10.2026. */
class DelaSayTest {

    @Test
    fun fieldHintSaysItIsForDeeds() {
        // Запись 24: «„Саша, что-то хочешь спросить“ сбивает — это просто поле для дел».
        assertEquals("Добавь дело или скажи, что сделать", DelaAsk.fieldHint(card = false))
        assertEquals("Добавь дело или заметку", DelaAsk.fieldHint(card = true))
        for (h in listOf(DelaAsk.fieldHint(false), DelaAsk.fieldHint(true))) {
            assertTrue(h.startsWith("Добавь дело"))
            // Без обращения по имени и без «спросить»: поле — для дел, а не вопрос Claude.
            assertFalse(h.substringBefore(" ").endsWith(","))
            assertFalse(h.contains("спрос"))
        }
    }
}
