package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Баг №5: тап по куску доната — какой кусок под пальцем.
class DonutHitTest {

    private val values = listOf(50f, 0f, 25f, 25f)

    @Test
    fun `по часовой от двенадцати, пустая доля пропускается`() {
        // Справа (3 часа) — ещё первая половина.
        assertEquals(0, DonutHit.index(values, dx = 80f, dy = 0f, inner = 50f, outer = 100f))
        // Внизу слева (между 6 и 9) — третья доля (индекс 2), пустой индекс 1 не ловится.
        assertEquals(2, DonutHit.index(values, dx = -40f, dy = 70f, inner = 50f, outer = 100f))
        // Слева вверху (между 9 и 12) — последняя.
        assertEquals(3, DonutHit.index(values, dx = -60f, dy = -60f, inner = 50f, outer = 100f))
    }

    @Test
    fun `дырка и за кольцом - ничего, пустой донат - ничего`() {
        assertNull(DonutHit.index(values, dx = 10f, dy = 10f, inner = 50f, outer = 100f))
        assertNull(DonutHit.index(values, dx = 120f, dy = 0f, inner = 50f, outer = 100f))
        assertNull(DonutHit.index(listOf(0f, 0f), dx = 80f, dy = 0f, inner = 50f, outer = 100f))
    }
}
