package ru.zf.pravka.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseCleanerTest {

    @Test
    fun `model preamble is stripped`() {
        assertEquals(
            "Готовый текст письма для отправки.",
            ResponseCleaner.clean("Вот исправленный текст: Готовый текст письма для отправки.", "Готовый текст письма для отправки"),
        )
    }

    @Test
    fun `quotes wrapping the whole reply are stripped`() {
        assertEquals(
            "Позвони мне завтра утром",
            ResponseCleaner.clean("«Позвони мне завтра утром»", "позвони мне завтра утром"),
        )
    }

    @Test
    fun `quotes stay when the original had them`() {
        val original = "«Позвони мне завтра утром»"
        assertEquals(original, ResponseCleaner.clean(original, original))
    }

    @Test
    fun `an eaten paragraph counts as corruption`() {
        val original = "а".repeat(400)
        assertNull(ResponseCleaner.clean("коротышка", original))
    }

    @Test
    fun `a directive rewrite skips the length gate`() {
        val original = "а".repeat(400)
        assertEquals("коротко", ResponseCleaner.clean("коротко", original, lenient = true))
    }

    @Test
    fun `an empty reply is corruption`() {
        assertNull(ResponseCleaner.clean("   ", "какой-то текст подлиннее"))
    }
}
