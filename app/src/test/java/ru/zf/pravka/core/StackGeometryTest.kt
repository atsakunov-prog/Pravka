package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// Стопка П · З · Д · Т с одинаковым просветом; шестерёнка над «П» и её веер
// (docs/telo.md, «Шестерёнка над «П»»). Плашки микрофона между «П» и «З»
// больше нет — переключатель уехал в веер.
class StackGeometryTest {

    @Test
    fun `П на нуле, дальше кнопка плюс просвет за каждый слот`() {
        val size = 48
        val gap = 8
        assertEquals(0, StackGeometry.slotOffset(0, size, gap))
        assertEquals(56, StackGeometry.slotOffset(1, size, gap))
        assertEquals(112, StackGeometry.slotOffset(2, size, gap))
        assertEquals(168, StackGeometry.slotOffset(3, size, gap))
    }

    @Test
    fun `отрицательный слот - это П`() {
        assertEquals(0, StackGeometry.slotOffset(-1, 48, 8))
    }

    @Test
    fun `шестерёнка и точка - доли кнопки`() {
        assertEquals(34, StackGeometry.gearSize(48))
        assertEquals(51, StackGeometry.gearSize(72))
        assertEquals(20, StackGeometry.dotSize(48))
        assertEquals(15, StackGeometry.dotSize(36))
    }

    @Test
    fun `веер раскрывается в сторону, где есть место`() {
        // Стопка у правого края экрана 1080 — веер уходит влево от шестерёнки.
        val left = StackGeometry.fanX(gearX = 1030, gearSize = 34, count = 4, bubble = 34, gap = 8, screenW = 1080)
        assertEquals(1030 - 8 - (4 * 34 + 3 * 8), left)
        // Стопка у левого края — веер вправо.
        val right = StackGeometry.fanX(gearX = 10, gearSize = 34, count = 4, bubble = 34, gap = 8, screenW = 1080)
        assertEquals(10 + 34 + 8, right)
    }

    @Test
    fun `веер не вылезает за экран`() {
        // Шестерёнка почти у левого края, но в правой половине узкого экрана:
        // влево не помещается — прижимается к нулю.
        val x = StackGeometry.fanX(gearX = 130, gearSize = 34, count = 4, bubble = 34, gap = 8, screenW = 240)
        assertEquals(0, x)
        // Вправо не помещается — прижимается к правому краю.
        val y = StackGeometry.fanX(gearX = 100, gearSize = 34, count = 4, bubble = 34, gap = 8, screenW = 300)
        assertEquals(300 - (4 * 34 + 3 * 8), y)
    }
}
