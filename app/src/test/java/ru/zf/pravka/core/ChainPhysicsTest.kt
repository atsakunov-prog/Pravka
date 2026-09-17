package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Пружина, на которой кнопки стопки едут друг за другом (docs/telo.md,
// «Бусы, а не строй»): доезжает, чуть проскакивает, успокаивается — и хвост
// цепочки приезжает позже головы.
class ChainPhysicsTest {

    private fun settleTime(link: Int, target: Float = 300f): Float {
        val s = ChainPhysics.spring(0f, link)
        var t = 0f
        while (t < 5f) {
            if (s.step(target, 1f / 60f)) return t
            t += 1f / 60f
        }
        return t
    }

    @Test
    fun `пружина доезжает до цели и встаёт на неё точно`() {
        val s = ChainPhysics.spring(0f, 1)
        var settled = false
        repeat(300) { if (!settled) settled = s.step(200f, 1f / 60f) }
        assertTrue(settled)
        assertEquals(200f, s.position, 0f)
        assertEquals(0f, s.velocity, 0f)
    }

    @Test
    fun `затухание ниже единицы - бусина чуть проскакивает цель`() {
        val s = ChainPhysics.spring(0f, 2)
        var maxPos = 0f
        repeat(120) {
            s.step(100f, 1f / 60f)
            if (s.position > maxPos) maxPos = s.position
        }
        assertTrue("перелёт должен быть, иначе это снова экспонента: $maxPos", maxPos > 100.5f)
        assertTrue("но небольшой, не пружина от матраса: $maxPos", maxPos < 115f)
    }

    @Test
    fun `хвост цепочки приезжает позже головы`() {
        val first = settleTime(1)
        val second = settleTime(2)
        val third = settleTime(3)
        assertTrue("1-е звено $first, 2-е $second", second > first)
        assertTrue("2-е звено $second, 3-е $third", third > second)
        // Но всё успокаивается меньше чем за секунду: кнопка — инструмент,
        // а не игрушка.
        assertTrue("хвост едет $third с", third < 1f)
    }

    @Test
    fun `пропущенный кадр не раскачивает пружину`() {
        val s = ChainPhysics.spring(0f, 1)
        // Полсекунды без кадров — считается как MAX_DT, без взрыва.
        s.step(100f, 0.5f)
        assertTrue(s.position in 0f..120f)
        var settled = false
        repeat(200) { if (!settled) settled = s.step(100f, 1f / 60f) }
        assertTrue(settled)
    }

    @Test
    fun `пока цель уезжает, покоя нет`() {
        val s = ChainPhysics.spring(0f, 1)
        var target = 0f
        var everSettled = false
        repeat(60) {
            target += 5f
            if (s.step(target, 1f / 60f)) everSettled = true
        }
        assertFalse(everSettled)
    }

    @Test
    fun `смена звена наследует позицию и скорость`() {
        val a = ChainPhysics.spring(0f, 1)
        repeat(5) { a.step(100f, 1f / 60f) }
        val b = ChainPhysics.spring(0f, 3)
        b.inherit(a)
        assertEquals(a.position, b.position, 0f)
        assertEquals(a.velocity, b.velocity, 0f)
    }
}
