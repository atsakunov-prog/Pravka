package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Обратная дорога багов: сессия пишет «№<TAB>что сделано» в
// assets/feedback_done.txt, телефон после обновления отмечает запись.
class FeedbackDoneTest {

    @Test
    fun `номер и что сделано, комментарии и пустые строки пропускаются`() {
        val m = FeedbackDone.parse(
            """
            # что сделано по багам и предложениям
            7	Подпись плитки ужимается, а не режется

            №12 Деньги: «+» в строке — трата
            """.trimIndent()
        )
        assertEquals(setOf(7, 12), m.keys)
        assertEquals("Подпись плитки ужимается, а не режется", m[7]!!.note)
        assertEquals("Деньги: «+» в строке — трата", m[12]!!.note)
        assertFalse(m[7]!!.skip)
    }

    @Test
    fun `минус в начале - отложено, а не сделано`() {
        val m = FeedbackDone.parse("3\t- не будем: Android не даёт")
        assertTrue(m[3]!!.skip)
        assertEquals("не будем: Android не даёт", m[3]!!.note)
    }

    @Test
    fun `повтор номера - последняя строка, мусор пропускается`() {
        val m = FeedbackDone.parse("5\tпервое\nпросто текст\n5\tвторое")
        assertEquals(1, m.size)
        assertEquals("второе", m[5]!!.note)
    }
}
