package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Плашка с «ОК» молчанием соглашается (владелец, 26.09.2026: «не нажал
// ничего = подтвердил»).
class AutoConfirmTest {

    @Test
    fun `одна-две строки - пятнадцать секунд`() {
        assertEquals(15_000L, AutoConfirm.holdMs(1))
        assertEquals(15_000L, AutoConfirm.holdMs(2))
        assertEquals(15_000L, AutoConfirm.holdMs(0))
    }

    @Test
    fun `длиннее список - дольше, но не бесконечно`() {
        assertTrue(AutoConfirm.holdMs(6) > AutoConfirm.holdMs(3))
        assertTrue(AutoConfirm.holdMs(3) > AutoConfirm.holdMs(2))
        assertEquals(AutoConfirm.MAX_HOLD_MS, AutoConfirm.holdMs(100))
    }

    @Test
    fun `секунды - вверх, ноль не показывается`() {
        assertEquals(15, AutoConfirm.secondsLeft(15_000))
        assertEquals(15, AutoConfirm.secondsLeft(14_001))
        assertEquals(14, AutoConfirm.secondsLeft(14_000))
        assertEquals(1, AutoConfirm.secondsLeft(1))
        assertEquals(0, AutoConfirm.secondsLeft(0))
        assertEquals(0, AutoConfirm.secondsLeft(-300))
    }

    @Test
    fun `строка на плашке`() {
        assertEquals("Запишу сам через 12 с", AutoConfirm.line("Запишу сам", 11_200))
        assertEquals("Отправлю сам через 1 с", AutoConfirm.line("Отправлю сам", 400))
    }
}
