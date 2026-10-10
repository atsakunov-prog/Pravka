package ru.zf.pravka.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Радуга категорий вернулась краской (10.10.2026, владелец: «у меня полностью
// пропало понимание, чем я занимаюсь… давай радугу вернём»). Правка 4.0 красила
// категории пятью соседними янтарями, и лента, круг дня и полоса перестали
// различаться. Сторожим две вещи: категория красится своим местом на радуге,
// а квадратик группы в легенде — ровно цвет её главной категории.
class CategoryRainbowColorTest {

    @Test
    fun `категория красится своим местом на радуге`() {
        assertEquals(rainbowFill(8f), categoryFill("Работа: текущая"))
        assertEquals(rainbowFill(56f), categoryFill("Спорт: бег"))
        assertEquals(rainbowFill(292f), categoryFill("Потери"))
        // Имя без регистра и пробелов по краям — то же место.
        assertEquals(categoryFill("Семья"), categoryFill("  семья "))
        assertEquals(Ink.TextMeta, categoryFill(""))
    }

    @Test
    fun `группа в легенде — цвет своей главной категории`() {
        assertEquals(ZGroup.WORK.fill, categoryFill("Работа: текущая"))
        assertEquals(ZGroup.SPORT.fill, categoryFill("Спорт: бег"))
        assertEquals(ZGroup.FAMILY.fill, categoryFill("Семья"))
        assertEquals(ZGroup.LIFE.fill, categoryFill("Быт"))
        assertEquals(ZGroup.SLEEP.fill, categoryFill("Сон"))
        assertEquals(ZGroup.LOSS.fill, categoryFill("Потери"))
    }

    @Test
    fun `группы полосы разнесены по радуге, а не жмутся к янтарю`() {
        val groups = listOf(ZGroup.WORK, ZGroup.SPORT, ZGroup.FAMILY, ZGroup.LIFE, ZGroup.LOSS)
        val hues = groups.map { hueOf(it.fill) }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val d = abs(hues[i] - hues[j]).let { minOf(it, 360f - it) }
            assertTrue("${groups[i]} и ${groups[j]} слишком близко: $d°", d >= 30f)
        }
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
}
