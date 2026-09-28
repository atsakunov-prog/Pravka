package ru.zf.pravka.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Сколько тейк был глух (владелец, 28.09.2026: «много пропускает слов…
// хотя вроде бы говорил нормально»): окно, где тейк шёл, а распознаватель
// не слушал, должно быть видно, а не угадываться по пропавшим словам.
class TakeHealthTest {

    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun `старт считается отдельно от глухих окон`() {
        val h = TakeHealth(startedAtMs = 1_000)
        assertFalse(h.hearing)
        // До первого «готов» глухота — это и есть старт, окном не считается.
        h.deaf(1_200)
        h.ready(1_900)
        assertEquals(900L, h.startupMs)
        assertEquals(0L, h.deafMs)
        assertEquals(0, h.gaps)
        assertTrue(h.hearing)
    }

    @Test
    fun `ошибка посреди тейка - окно от ошибки до нового готов`() {
        val h = TakeHealth(0)
        h.ready(500)
        h.error(7)
        h.deaf(10_000)
        assertFalse(h.hearing)
        // Повторная глухота внутри того же окна его не сдвигает.
        h.error(7)
        h.deaf(10_400)
        h.ready(11_200)
        h.error(2)
        h.deaf(20_000)
        h.ready(21_000)
        assertEquals(2_200L, h.deafMs)
        assertEquals(2, h.gaps)
        assertEquals("2×1, 7×2", h.errorsLine())
    }

    @Test
    fun `стоп посреди глухого окна закрывает его этим мигом`() {
        val h = TakeHealth(0)
        h.ready(300)
        h.deaf(5_000)
        h.close(5_700)
        // После конца тейка ничего не считается: его ошибки — это сам стоп.
        h.error(7)
        h.deaf(6_000)
        h.ready(9_000)
        assertEquals(700L, h.deafMs)
        assertEquals(1, h.gaps)
        assertEquals("", h.errorsLine())
        assertFalse(h.hearing)
    }

    @Test
    fun `кто слушал - смена микрофона видна стрелкой, повтор не множится`() {
        val h = TakeHealth(0)
        h.heard("телефон")
        h.heard("телефон")
        h.heard("гарнитура «OpenComm2»")
        assertEquals("телефон → гарнитура «OpenComm2»", h.mic)
    }

    @Test
    fun `итог в журнал - одной строкой`() {
        val h = TakeHealth(0)
        h.ready(800)
        h.deaf(2_000)
        h.error(11)
        h.ready(3_200)
        h.heard("телефон")
        h.close(9_000)
        assertEquals("старт 800 мс · глухо 1200 мс (1 окно) · ошибки 11×1 · слушал телефон", h.summary())
        assertEquals("старт: движок не отозвался · глухо 0 мс", TakeHealth(0).summary())
    }

    @Test
    fun `карточка расшифровки - только то, что стоило слов`() {
        // Всё в порядке — карточка прежняя.
        assertNull(TakeHealth.cardLine(startupMs = 600, deafMs = 120, gaps = 1, mic = "телефон", locale = ru))
        assertEquals(
            "глухо 2,4 с (3 окна)",
            TakeHealth.cardLine(startupMs = 600, deafMs = 2_400, gaps = 3, mic = "телефон", locale = ru),
        )
        assertEquals(
            "старт 1,8 с · телефон → гарнитура «OpenComm2»",
            TakeHealth.cardLine(startupMs = 1_800, deafMs = 0, gaps = 0, mic = "телефон → гарнитура «OpenComm2»", locale = ru),
        )
        // Старые записи без полей: старт неизвестен — молчим.
        assertNull(TakeHealth.cardLine(startupMs = -1, deafMs = 0, gaps = 0, mic = null, locale = ru))
    }
}
