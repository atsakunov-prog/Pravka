package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Пауза в речи — где мост через телефон уступает наушникам, не разрезав слово.
class PauseDetectorTest {

    private fun PauseDetector.run(db: Double, chunks: Int): Boolean {
        var last = false
        repeat(chunks) { last = feed(db, 50) }
        return last
    }

    @Test
    fun `тишина комнаты - пауза, голос поверх неё - нет`() {
        val p = PauseDetector()
        assertTrue(p.run(-62.0, 10))
        assertEquals(500L, p.quietMs)
        // Голос на 25 дБ громче — не пауза, счёт тишины с нуля.
        assertFalse(p.run(-37.0, 4))
        assertEquals(0L, p.quietMs)
        assertTrue(p.run(-61.0, 6))
        assertEquals(300L, p.quietMs)
    }

    @Test
    fun `шумное место - порог по полу, а не по числу`() {
        // Машина: пол −40 дБ — это ещё тишина, голос −25 — нет.
        val p = PauseDetector()
        assertTrue(p.run(-40.0, 20))
        assertFalse(p.run(-25.0, 2))
    }

    @Test
    fun `долгий голос полом не становится`() {
        val p = PauseDetector()
        p.run(-60.0, 20)
        // Восемь секунд сплошной речи (весь мост через телефон) — и всё ещё речь.
        assertFalse(p.run(-35.0, 160))
    }

    @Test
    fun `ровные нули - тишина`() {
        val p = PauseDetector()
        assertTrue(p.run(Double.NEGATIVE_INFINITY, 8))
        assertEquals(400L, p.quietMs)
        assertTrue(p.run(Double.NaN, 1))
    }
}
