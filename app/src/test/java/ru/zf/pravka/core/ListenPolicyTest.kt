package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {

    @Test
    fun `тишина — не поломка`() {
        assertTrue(ListenPolicy.isSilence(6))  // SPEECH_TIMEOUT
        assertTrue(ListenPolicy.isSilence(7))  // NO_MATCH
        assertFalse(ListenPolicy.isSilence(8)) // RECOGNIZER_BUSY
        assertFalse(ListenPolicy.isSilence(5)) // CLIENT
        assertFalse(ListenPolicy.isSilence(2)) // NETWORK
    }

    @Test
    fun `полминуты тишины — слушаем дальше`() {
        // Случай 23.09.2026: замолк на 30 с — и Правка ушла расшифровывать.
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = 30_000))
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = 9 * 60_000))
    }

    @Test
    fun `десять минут без слов — нажали случайно`() {
        assertFalse(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = ListenPolicy.IDLE_CAP_MS))
        assertTrue(ListenPolicy.idleExpired(1_000, 1_000 + ListenPolicy.IDLE_CAP_MS))
        assertFalse(ListenPolicy.idleExpired(1_000, ListenPolicy.IDLE_CAP_MS))
    }

    @Test
    fun `считается от последнего слова, а не от старта`() {
        // Двадцать минут диктовки, последнее слово минуту назад — живо.
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 19 * 60_000, nowMs = 20 * 60_000))
    }

    @Test
    fun `сорвавшаяся сессия считается, отработавшая обнуляет счёт`() {
        // Движок закрыл сессию, не дослушав своей тишины, — это срыв.
        assertEquals(1, ListenPolicy.countCollapse(0, ranMs = 50))
        assertEquals(3, ListenPolicy.countCollapse(2, ranMs = ListenPolicy.SESSION_MIN_MS - 1))
        // Сессия честно отстояла свою тишину — счёт срывов ни при чём.
        assertEquals(0, ListenPolicy.countCollapse(4, ranMs = 30_000))
        assertEquals(0, ListenPolicy.countCollapse(4, ranMs = ListenPolicy.SESSION_MIN_MS))
    }

    @Test
    fun `пять срывов подряд — поднимать больше нечего`() {
        assertFalse(ListenPolicy.giveUpOnCollapses(0))
        assertFalse(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS - 1))
        assertTrue(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS))
        assertTrue(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS + 1))
    }

    @Test
    fun `остановленную запись не поднимаем`() {
        assertFalse(ListenPolicy.resumeAfterSessionEnd(true, true, 0, 1_000))
        assertFalse(ListenPolicy.resumeAfterSessionEnd(false, false, 0, 1_000))
    }
}
