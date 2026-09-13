package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// Стопка П · значок · З · Д · Т: смещения слотов от «П». Правило из
// docs/telo.md («Значок микрофона между «П» и «З»»): значок — треть кнопки в
// своём слоте, остальные кнопки идут через обычный просвет.
class StackGeometryTest {

    @Test
    fun `значок — треть кнопки, слот — кружок плюс поля`() {
        assertEquals(16, StackGeometry.toggleSize(48))
        assertEquals(26, StackGeometry.toggleSlot(48, pad = 5))
        assertEquals(12, StackGeometry.toggleSize(36))
        assertEquals(24, StackGeometry.toggleSize(72))
    }

    @Test
    fun `П на нуле, З через слот значка, дальше обычный просвет`() {
        val size = 48
        val gap = 8
        val zGap = 26
        assertEquals(0, StackGeometry.slotOffset(0, size, gap, zGap))
        assertEquals(74, StackGeometry.slotOffset(1, size, gap, zGap))        // 48 + 26
        assertEquals(130, StackGeometry.slotOffset(2, size, gap, zGap))       // 74 + 56
        assertEquals(186, StackGeometry.slotOffset(3, size, gap, zGap))       // 130 + 56
    }

    @Test
    fun `без значка стопка та же, что была - просвет везде одинаковый`() {
        val size = 48
        val gap = 8
        assertEquals(56, StackGeometry.slotOffset(1, size, gap, gap))
        assertEquals(112, StackGeometry.slotOffset(2, size, gap, gap))
        assertEquals(168, StackGeometry.slotOffset(3, size, gap, gap))
    }

    @Test
    fun `отрицательный слот - это П`() {
        assertEquals(0, StackGeometry.slotOffset(-1, 48, 8, 26))
    }
}
