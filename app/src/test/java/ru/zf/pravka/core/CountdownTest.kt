package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Обратный отсчёт на занятой кнопке (docs/pravka.md, «Отсчёт на кнопке»):
// «9, 8, 7… с десятыми… и до нуля».
class CountdownTest {

    @Test
    fun `от десяти секунд - целые`() {
        assertEquals("12", Countdown.label(12_900))
        assertEquals("10", Countdown.label(10_000))
    }

    @Test
    fun `меньше десяти - с десятыми через запятую`() {
        assertEquals("9,9", Countdown.label(9_999))
        assertEquals("9,4", Countdown.label(9_450))
        assertEquals("0,1", Countdown.label(150))
    }

    @Test
    fun `доходит до нуля, а за сроком - колесо`() {
        assertEquals("0,0", Countdown.label(99))
        assertEquals("0,0", Countdown.label(0))
        assertNull("срок вышел — число не замирает", Countdown.label(-1))
    }

    @Test
    fun `число не обещает больше, чем осталось`() {
        // 4,99 с — это «4,9», а не «5,0».
        assertEquals("4,9", Countdown.label(4_999))
    }

    @Test
    fun `на кнопке - только её дороги`() {
        for (r in listOf("pravka", "pravka_strong", "zasechka", "zasechka_fork", "raznoska", "money", "food", "body")) {
            assertTrue(r, Countdown.onButton(r))
        }
        for (r in listOf("night_review", "money_ask", "money_patterns", "money_match", "pravka_learn", "")) {
            assertFalse(r, Countdown.onButton(r))
        }
    }
}
