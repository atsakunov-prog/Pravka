package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// Стопка П · плашка микрофона · З · Д · Т: смещения слотов от «П». Правило из
// docs/telo.md («Плашка микрофона между «П» и «З»»): плашка в половину высоты
// кнопки в своём слоте, остальные кнопки идут через обычный просвет.
class StackGeometryTest {

    @Test
    fun `плашка — половина кнопки, слот — плашка плюс поля`() {
        assertEquals(24, StackGeometry.toggleHeight(48))
        assertEquals(30, StackGeometry.toggleSlot(48, pad = 3))
        assertEquals(18, StackGeometry.toggleHeight(36))
        assertEquals(36, StackGeometry.toggleHeight(72))
    }

    @Test
    fun `П на нуле, З через слот плашки, дальше обычный просвет`() {
        val size = 48
        val gap = 8
        val zGap = 30
        assertEquals(0, StackGeometry.slotOffset(0, size, gap, zGap))
        assertEquals(78, StackGeometry.slotOffset(1, size, gap, zGap))        // 48 + 30
        assertEquals(134, StackGeometry.slotOffset(2, size, gap, zGap))       // 78 + 56
        assertEquals(190, StackGeometry.slotOffset(3, size, gap, zGap))       // 134 + 56
    }

    @Test
    fun `без плашки стопка та же, что была - просвет везде одинаковый`() {
        val size = 48
        val gap = 8
        assertEquals(56, StackGeometry.slotOffset(1, size, gap, gap))
        assertEquals(112, StackGeometry.slotOffset(2, size, gap, gap))
        assertEquals(168, StackGeometry.slotOffset(3, size, gap, gap))
    }

    @Test
    fun `отрицательный слот - это П`() {
        assertEquals(0, StackGeometry.slotOffset(-1, 48, 8, 30))
    }
}
