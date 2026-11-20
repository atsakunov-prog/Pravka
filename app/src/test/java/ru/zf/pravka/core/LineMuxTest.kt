package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineMuxTest {

    @Test
    fun `выбор Google — строка Google, комп не трогает пилюлю`() {
        val m = LineMux(pcMode = false)
        assertEquals("привет", m.onGoogle("привет", 100))
        assertNull(m.onPc("привет как", 200))
        assertFalse(m.stuck())
    }

    @Test
    fun `комп в начале ждём две секунды после первого слова Google`() {
        val m = LineMux(pcMode = true)
        assertNull(m.onGoogle("привет", 1_000))
        assertNull(m.onGoogle("привет как", 2_500))
        assertEquals("привет как дела", m.onPc("привет как дела", 2_800))
        assertEquals(LineMux.Source.PC, m.source)
        // пока строка компа свежая, Google пилюлю не трогает
        assertNull(m.onGoogle("привет как дела у", 3_500))
    }

    @Test
    fun `комп молчит — после двух секунд Google, ожил — снова комп`() {
        val m = LineMux(pcMode = true)
        m.onGoogle("иван", 0)
        m.onPc("иван", 1_200)
        assertNull(m.onGoogle("иван привет", 2_000))
        // комп замолчал: строка старше двух секунд — показываем Google
        assertEquals("иван привет скажи", m.onGoogle("иван привет скажи", 3_300))
        assertEquals(LineMux.Source.GOOGLE, m.source)
        assertEquals(1, m.fallbacks)
        assertEquals("иван привет скажи пожалуйста", m.onPc("иван привет скажи пожалуйста", 4_300))
        assertEquals(LineMux.Source.PC, m.source)
        m.close(10_000)
        assertEquals(1_000L, m.googleShownMs)
        assertEquals(2_100L + 5_700L, m.pcShownMs)
        assertFalse(m.stuck())
    }

    @Test
    fun `застряла — ни разу не показалась или Google больше трети тейка`() {
        val never = LineMux(pcMode = true)
        never.onGoogle("иван", 0)
        never.onGoogle("иван привет", 2_100)
        never.close(6_000)
        assertTrue(never.stuck())

        val mostly = LineMux(pcMode = true)
        mostly.onGoogle("иван", 0)
        mostly.onPc("иван", 500)
        mostly.onGoogle("иван привет", 3_000)          // комп отстал на 2,5 с
        mostly.onPc("иван привет скажи", 6_000)
        mostly.close(7_000)
        assertTrue(mostly.stuck())
    }

    @Test
    fun `дорога домой оборвалась — Google сразу, без двух секунд ожидания`() {
        val m = LineMux(pcMode = true)
        // первое слово Google, а отправка уже бросила тейк (нет связи)
        assertEquals("иван", m.onGoogle("иван", 4_400, pcAlive = false))
        assertEquals(LineMux.Source.GOOGLE, m.source)
        // и строка компа, которая успела прийти раньше обрыва, Google не перебивает
        val n = LineMux(pcMode = true)
        n.onGoogle("иван", 0)
        n.onPc("иван", 1_000)
        assertEquals("иван привет", n.onGoogle("иван привет", 1_500, pcAlive = false))
    }
}
