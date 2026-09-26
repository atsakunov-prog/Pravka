package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

// Свечение режима (docs/agreements.md, «Третье издание»): владелец — «сохраним
// цветность каждого направления… и там не примешивались бы другие цвета».
// Все тона свечения — того же оттенка, что кнопка режима на стекле.
class ModeGlowTest {

    // Цвета кнопок на стекле: «П», «З», «Д», «Т/Е», «₽» — и олива вкладки Еды.
    private val accents = listOf(
        0xFFEA580C.toInt(), 0xFFF78810.toInt(), 0xFF2A5D82.toInt(),
        0xFF2F6B4F.toInt(), 0xFF5B4A8C.toInt(), 0xFF5E7A1F.toInt(),
    )

    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    @Test
    fun `оттенок не уходит ни в одном тоне свечения`() {
        for (a in accents) {
            val h = ModeGlow.hsv(a).h
            for (t in listOf(ModeGlow.tone(a), ModeGlow.light(a), ModeGlow.deep(a), ModeGlow.card(a))) {
                // Восьмибитный канал округляет — градус-полтора, не больше.
                assertTrue(
                    "тон ${Integer.toHexString(t)} ушёл от оттенка ${Integer.toHexString(a)}",
                    hueGap(h, ModeGlow.hsv(t).h) < 2f,
                )
            }
        }
    }

    @Test
    fun `тёмные чернила кнопок зажигаются, яркие остаются собой`() {
        // «Д» — тёмно-синие чернила: без подъёма яркости свет на ночи не виден.
        assertTrue(ModeGlow.hsv(ModeGlow.tone(0xFF2A5D82.toInt())).v >= ModeGlow.MIN_VALUE - 0.01f)
        // «П» и так яркая — тон почти совпадает с кнопкой.
        val p = ModeGlow.hsv(0xFFEA580C.toInt())
        val t = ModeGlow.hsv(ModeGlow.tone(0xFFEA580C.toInt()))
        assertTrue(abs(p.v - t.v) < 0.02f && abs(p.s - t.s) < 0.02f)
    }

    @Test
    fun `перелив - светлее справа, глубже посередине`() {
        for (a in accents) {
            val tone = ModeGlow.hsv(ModeGlow.tone(a))
            assertTrue(ModeGlow.hsv(ModeGlow.deep(a)).v < tone.v)
            assertTrue(ModeGlow.hsv(ModeGlow.light(a)).s < tone.s)
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
    fun `плашка - тёмный тон своей краски, не серый`() {
        for (a in accents) {
            val c = ModeGlow.hsv(ModeGlow.card(a))
            assertTrue("плашка должна быть тёмной", c.v <= 0.3f)
            assertTrue("плашка не должна быть серой", c.s >= 0.4f)
        }
    }

    @Test
    fun `свет ярче прежнего - блёклого синего и изумрудного больше нет`() {
        for (a in listOf(0xFF2A5D82.toInt(), 0xFF2F6B4F.toInt())) {
            val t = ModeGlow.hsv(ModeGlow.tone(a))
            assertTrue(t.s >= ModeGlow.MIN_SAT - 0.01f && t.v >= ModeGlow.MIN_VALUE - 0.01f)
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
