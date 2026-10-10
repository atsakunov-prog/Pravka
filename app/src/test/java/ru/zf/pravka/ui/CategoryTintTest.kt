package ru.zf.pravka.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Краска категорий — светофор цены часа (10.10.2026, владелец: «светофор с
// градацией… красный совсем плохо, жёлтый — обычные дела, зелёный — всякая
// работа»; «светофор нужно применить везде к категориям, а не только в
// линии»). Сторожим: потери красные, обычное жёлтое, работа зелёная; имя
// без цены под рукой красится по справочнику; группа — типичной ценой часа.
class CategoryTintTest {

    @After
    fun clear() {
        CategoryWorth.byName = emptyMap()
    }

    private fun hueOf(c: androidx.compose.ui.graphics.Color): Float {
        val r = c.red; val g = c.green; val b = c.blue
        val max = maxOf(r, g, b); val d = max - minOf(r, g, b)
        if (d == 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0) h + 360f else h
    }

    @Test
    fun `потери красные, обычные дела жёлтые, работа зелёная`() {
        val loss = hueOf(worthTint(-5))
        val food = hueOf(worthTint(1))
        val work = hueOf(worthTint(10))
        assertTrue("потери не красные: $loss", loss < 15f || loss > 345f)
        assertTrue("обычное не жёлтое: $food", food in 35f..60f)
        assertTrue("работа не зелёная: $work", work in 110f..150f)
        // Градация: чем дороже час, тем зеленее.
        assertTrue(hueOf(worthTint(6)) in food..work)
    }

    @Test
    fun `цена часа — своя, из справочника или по группе`() {
        assertEquals(worthTint(9), categoryFill("Спорт: бег", 9))
        CategoryWorth.byName = mapOf("чтение" to 6)
        assertEquals(worthTint(6), categoryFill("  Чтение "))
        // Справочника нет — типичная цена группы.
        CategoryWorth.byName = emptyMap()
        assertEquals(worthTint(ZGroup.LOSS.typical), categoryFill("Потери"))
        assertEquals(Ink.TextMeta, categoryFill(""))
    }

    @Test
    fun `группа в легенде — светофор своей типичной цены`() {
        assertEquals(worthTint(10), ZGroup.WORK.fill)
        assertEquals(worthTint(-5), ZGroup.LOSS.fill)
        assertEquals(Ink.Cream, ZGroup.AHEAD.fill)
    }
}
