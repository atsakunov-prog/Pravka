package ru.zf.pravka.ui

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

// Вид Правки 5.0 (13.10.2026, docs/redesign-5-plan.md §2): с завода — v2 Ковчега,
// цвета — ровно токены общего слоя (`kovcheg.css`); «Вид 4.0» — прежние числа
// целиком. Сторожим, что имена 4.0 (`Ink`, `Modes`) честно ходят за видом и
// темой, и что у режимов в v2 один акцент, но разные объекты.
class LookTest {

    @After
    fun back() {
        Look.v4 = false
        Look.dark = false
    }

    @Test
    fun `v2 — токены Ковчега обеих тем`() {
        Look.v4 = false
        Look.dark = false
        assertEquals(Color(0xFFF6F4EF), Ink.Bg)
        assertEquals(Color(0xFF1C1B19), Ink.Text)
        assertEquals(Color(0xFF77736A), Ink.TextMeta)
        assertEquals(Color(0xFF2747C9), Modes.Dela.key)
        Look.dark = true
        assertEquals(Color(0xFF161513), Ink.Bg)
        assertEquals(Color(0xFFECEAE5), Ink.Text)
        assertEquals(Color(0xFF93ABF8), Modes.Dela.key)
        assertEquals(Color(0xFF93ABF8), Kv.accent)
    }

    @Test
    fun `v2 — один акцент, но режимы разные объекты`() {
        Look.v4 = false
        assertEquals(Modes.Dela.key, Modes.Money.key)
        assertEquals(Modes.Today.key, Modes.Zasechka.key)
        assertNotSame(Modes.Today, Modes.Zasechka)
        assertSame(Modes.Today, Modes.of(ModeDecor.TODAY))
        // Света режима нет, стекло — панель с линией.
        assertEquals(0f, Modes.Pravka.light.opacity)
        assertEquals(Kv.panel, Modes.Pravka.glass.base)
        assertEquals(Kv.line, Modes.Pravka.glass.rimColor)
    }

    @Test
    fun `вид 40 — прежние числа, всегда ночь`() {
        Look.v4 = true
        Look.dark = false
        assertEquals(Color(0xFF100F0D), Ink.Bg)
        assertEquals(Color(0xFFF0EADF), Ink.Text)
        assertEquals(Color(0xFF2A5D82), Modes.Dela.key)
        assertNotEquals(Modes.Dela.key, Modes.Money.key)
        assertEquals(Color(0xFFFFC261), PointsPlus)
    }

    @Test
    fun `тема из настроек — по ключу, незнакомое — как в системе`() {
        assertEquals(Look.Theme.DARK, Look.Theme.of("dark"))
        assertEquals(Look.Theme.LIGHT, Look.Theme.of("light"))
        assertEquals(Look.Theme.SYSTEM, Look.Theme.of(null))
        assertEquals(Look.Theme.SYSTEM, Look.Theme.of("кривое"))
    }
}
