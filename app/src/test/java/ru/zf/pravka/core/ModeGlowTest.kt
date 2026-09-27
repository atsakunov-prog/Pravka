package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

// Свечение режима (docs/agreements.md, «Третье издание»). Владелец, 27.09.2026:
// «главные цвета как на кнопках, они там прямо классные», «переливы
// динамические с чуть соседними цветами», «поярче… грустно как-то».
// Главный тон — ровно кнопка; соседи — полшага по кругу, не чужие цвета.
class ModeGlowTest {

    // Цвета кнопок на стекле: «П», «З», «Д», «Т/Е», «₽» — и олива вкладки Еды.
    private val accents = listOf(
        0xFFEA580C.toInt(), 0xFFF78810.toInt(), 0xFF2A5D82.toInt(),
        0xFF2F6B4F.toInt(), 0xFF5B4A8C.toInt(), 0xFF5E7A1F.toInt(),
    )

    /** Расстояние по кругу оттенков со знаком: куда повернули [b] от [a]. */
    private fun hueTurn(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun hueGap(a: Float, b: Float): Float = abs(hueTurn(a, b))

    @Test
    fun `главный тон - ровно цвет кнопки`() {
        for (a in accents) assertEquals(a, ModeGlow.tone(a))
    }

    @Test
    fun `соседи - полшага по кругу в разные стороны, не чужой цвет`() {
        for (a in accents) {
            val h = ModeGlow.hsv(a).h
            val w = hueTurn(h, ModeGlow.hsv(ModeGlow.warm(a)).h)
            val c = hueTurn(h, ModeGlow.hsv(ModeGlow.cool(a)).h)
            // Восьмибитный канал округляет — пара градусов туда-сюда.
            assertTrue("тёплый сосед ${Integer.toHexString(a)}: $w", w in 8f..20f)
            assertTrue("холодный сосед ${Integer.toHexString(a)}: $c", c in -20f..-8f)
        }
    }

    @Test
    fun `блик - тот же оттенок, светлее кнопки`() {
        for (a in accents) {
            val k = ModeGlow.hsv(a)
            val l = ModeGlow.hsv(ModeGlow.lit(a))
            assertTrue(hueGap(k.h, l.h) < 2f)
            assertTrue(l.v > k.v || k.v >= 0.99f)
        }
    }

    @Test
    fun `ярче - соседи не выцветают`() {
        // «Грустно»: бледный тон на ночи — серая дымка. Соседи не бледнее кнопки,
        // тёплый ещё и светлее её — он и держит яркость перелива.
        for (a in accents) {
            val k = ModeGlow.hsv(a)
            val w = ModeGlow.hsv(ModeGlow.warm(a))
            val c = ModeGlow.hsv(ModeGlow.cool(a))
            assertTrue(w.s >= k.s - 0.01f && c.s >= k.s - 0.01f)
            assertTrue(w.v > k.v || k.v >= 0.99f)
            assertTrue(ModeGlow.hsv(ModeGlow.lit(a)).s >= k.s * 0.85f)
        }
    }

    @Test
    fun `сила - ноль выключает, работа Claude ярче, потолок держится`() {
        assertEquals(0f, ModeGlow.alpha(0f, busy = true), 0f)
        val idle = ModeGlow.alpha(ModeGlow.DEFAULT, busy = false)
        val busy = ModeGlow.alpha(ModeGlow.DEFAULT, busy = true)
        assertTrue(busy > idle)
        assertTrue(ModeGlow.alpha(1f, busy = true) <= ModeGlow.PEAK)
    }

    @Test
    fun `плашка - чернила с цветом кнопки, тёмная под белым текстом и не серая`() {
        for (a in accents) {
            val top = ModeGlow.hsv(ModeGlow.cardTop(a))
            val bottom = ModeGlow.hsv(ModeGlow.cardBottom(a))
            assertTrue("плашка должна быть тёмной", top.v <= 0.32f && bottom.v <= top.v + 0.01f)
            assertTrue("плашка не должна быть серой", top.s >= 0.2f)
            // Чернила тёплые и чуть тянут оттенок к себе — но цвет узнаётся.
            assertTrue(hueGap(ModeGlow.hsv(a).h, top.h) < 20f)
        }
    }

    @Test
    fun `HSV туда и обратно - тот же цвет`() {
        for (a in accents) {
            val (h, s, v) = ModeGlow.hsv(a)
            assertEquals(a, ModeGlow.fromHsv(h, s, v))
        }
    }
}
