package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Кнопка гарнитуры (владелец, 25.09.2026): «если с полем, то правка в поле;
// если без поля или на заблоченном экране, то засечка».
class HeadsetPressTest {

    private fun choose(
        takeRunning: Boolean = false,
        locked: Boolean = false,
        fieldFocused: Boolean = false,
        zasechkaOn: Boolean = true,
    ) = HeadsetPress.choose(takeRunning, locked, fieldFocused, zasechkaOn)

    @Test
    fun `поле в фокусе - Правка в поле`() {
        assertEquals(HeadsetPress.Action.PRAVKA, choose(fieldFocused = true))
    }

    @Test
    fun `поля нет - Засечка`() {
        assertEquals(HeadsetPress.Action.ZASECHKA, choose(fieldFocused = false))
    }

    @Test
    fun `заблокированный экран - Засечка, даже если поле в фокусе`() {
        // Поле с фокусом на локскрине — это ПИН-код: туда не диктуют.
        assertEquals(HeadsetPress.Action.ZASECHKA, choose(locked = true, fieldFocused = true))
        assertEquals(HeadsetPress.Action.ZASECHKA, choose(locked = true, fieldFocused = false))
    }

    @Test
    fun `тейк идёт - нажатие его заканчивает, что бы ни было вокруг`() {
        assertEquals(HeadsetPress.Action.STOP, choose(takeRunning = true))
        assertEquals(HeadsetPress.Action.STOP, choose(takeRunning = true, locked = true))
        assertEquals(HeadsetPress.Action.STOP, choose(takeRunning = true, fieldFocused = true))
        assertEquals(HeadsetPress.Action.STOP, choose(takeRunning = true, zasechkaOn = false))
    }

    @Test
    fun `без кнопки З всё уходит в Правку - писать в ленту нечем`() {
        assertEquals(HeadsetPress.Action.PRAVKA, choose(zasechkaOn = false))
        assertEquals(HeadsetPress.Action.PRAVKA, choose(zasechkaOn = false, locked = true))
        assertEquals(HeadsetPress.Action.PRAVKA, choose(zasechkaOn = false, fieldFocused = true))
    }

    @Test
    fun `чем нажал - тем и слушаем`() {
        // Кнопка гарнитуры — гарнитура, какой бы кружок ни стоял.
        assertFalse(HeadsetPress.phoneMic(ownerChosePhone = true, fromHeadset = true))
        assertFalse(HeadsetPress.phoneMic(ownerChosePhone = false, fromHeadset = true))
        // Касание телефона — как выбрал владелец.
        assertTrue(HeadsetPress.phoneMic(ownerChosePhone = true, fromHeadset = false))
        assertFalse(HeadsetPress.phoneMic(ownerChosePhone = false, fromHeadset = false))
    }
}
