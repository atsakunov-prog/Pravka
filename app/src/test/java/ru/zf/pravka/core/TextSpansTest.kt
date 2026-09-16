package ru.zf.pravka.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TextSpansTest {

    @Test
    fun `вставка в конец по курсору`() {
        assertEquals(6 until 12, TextSpans.insertedSpan("Привет", "Привет, мир!", 6, 6))
    }

    @Test
    fun `вставка в середину по курсору`() {
        // "ab|cd" + "XY" -> "abXYcd"
        assertEquals(2 until 4, TextSpans.insertedSpan("abcd", "abXYcd", 2, 2))
    }

    @Test
    fun `вставка поверх выделения`() {
        // "hello [world]" -> paste "X" -> "hello X": текст стал короче, но это вставка
        assertEquals(6 until 7, TextSpans.insertedSpan("hello world", "hello X", 6, 11))
    }

    @Test
    fun `курсор неизвестен — по префиксу и суффиксу`() {
        assertEquals(2 until 4, TextSpans.insertedSpan("ab", "abab", -1, -1))
        assertEquals(0 until 3, TextSpans.insertedSpan("", "три", -1, -1))
    }

    @Test
    fun `курсор врёт — разница выручает`() {
        // Поле сообщило курсор 0, а вставка легла в конец.
        assertEquals(2 until 5, TextSpans.insertedSpan("ab", "abXYZ", 0, 0))
    }

    @Test
    fun `ничего не вставилось`() {
        assertNull(TextSpans.insertedSpan("abc", "abc", 3, 3))
        assertNull(TextSpans.insertedSpan("abc", "ab", 3, 3))
    }

    @Test
    fun `поле поменялось не вставкой`() {
        assertNull(TextSpans.insertedSpan("abcd", "xbcdY", -1, -1))
    }
}
