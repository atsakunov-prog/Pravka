package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Переразбор истории: каждый шаг — один раз, и ни один не застревает в круге
// (владелец, 25.09.2026: «чтобы новенькие не застревали и не перебирали
// каждый раз, а только один раз»).
class HistoryFixesTest {

    private val step = HistoryFixes.Step("2099-01-01-test", "проверочный", emptyList()) { HistoryFixes.Result(0, 0) }
    private val steps = listOf(step)

    private fun rec(error: String = "", attempts: Int = 1, running: Boolean = false) =
        HistoryFixes.Done(step.id, step.title, 1L, 0, 0, "", error, attempts, running)

    private fun pending(vararg d: HistoryFixes.Done) = HistoryFixes.pending(steps, d.toList()).map { it.id }
    private fun gaveUp(vararg d: HistoryFixes.Done) = HistoryFixes.gaveUp(steps, d.toList()).map { it.id }

    @Test fun `новый шаг — в работу`() {
        assertEquals(listOf(step.id), pending())
    }

    @Test fun `пройденный — больше никогда`() {
        assertEquals(emptyList<String>(), pending(rec()))
        assertEquals(emptyList<String>(), gaveUp(rec()))
    }

    @Test fun `упавший повторяется, но не больше трёх раз`() {
        assertEquals(listOf(step.id), pending(rec("IOException", attempts = 1)))
        assertEquals(listOf(step.id), pending(rec("IOException", attempts = 2)))
        assertEquals(emptyList<String>(), pending(rec("IOException", attempts = HistoryFixes.MAX_ATTEMPTS)))
        assertEquals(listOf(step.id), gaveUp(rec("IOException", attempts = HistoryFixes.MAX_ATTEMPTS)))
    }

    @Test fun `шаг, трижды уронивший приложение, откладывается, а не крутится`() {
        // Отметка «начал» пишется до шага: процесс умер — запись осталась «идёт».
        assertEquals(listOf(step.id), pending(rec(attempts = 1, running = true)))
        assertEquals(emptyList<String>(), pending(rec(attempts = HistoryFixes.MAX_ATTEMPTS, running = true)))
        assertTrue(rec(attempts = HistoryFixes.MAX_ATTEMPTS, running = true).gaveUp)
        assertFalse(rec(attempts = 1, running = true).ok)
    }

    @Test fun `«Повторить» у отложенного — снова в работу`() {
        val stuck = rec("IOException", attempts = HistoryFixes.MAX_ATTEMPTS)
        assertEquals(listOf(step.id), pending(stuck.copy(attempts = 0, running = false)))
    }

    @Test fun `номера заводских шагов не повторяются`() {
        val ids = HistoryFixes.STEPS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
