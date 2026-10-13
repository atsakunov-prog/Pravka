package ru.zf.pravka.ui

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.zf.pravka.core.Kovcheg

// Вид из Свода (Правка 5.0, этап 7, 14.10.2026): акцент `ui.look`, выбранный
// владельцем в вебе Дел, ложится поверх заводской палитры v2 — в Kv, в краске
// режимов и в шкале графиков; без записи — ровно заводская, тем же объектом.
class LookSvodTest {

    @After
    fun back() {
        Look.tokens(Kovcheg.Tokens())
        Look.dark = false
        Look.v4 = false
    }

    @Test
    fun `акцент из Свода — поверх заводского, тёмная своя`() {
        Look.v4 = false
        Look.tokens(Kovcheg.Tokens(light = mapOf("accent" to 0xFF2F7D4FL, "accent-soft" to 0xFFEAF2EDL)))
        Look.dark = false
        assertEquals(Color(0xFF2F7D4F), Kv.accent)
        assertEquals(Color(0xFFEAF2ED), Kv.accentSoft)
        assertEquals(Color(0xFF2F7D4F), Modes.Dela.key)
        assertEquals(Color(0xFF2F7D4F), Kv.ramp[1])
        assertEquals(Color(0xFFF6F4EF), Kv.bg)   // незаписанное — заводское
        Look.dark = true
        assertEquals(Color(0xFF93ABF8), Kv.accent)
        assertEquals(KvPalette.DARK.ramp, Kv.ramp)
    }

    @Test
    fun `без записи — заводская палитра тем же объектом`() {
        Look.tokens(Kovcheg.Tokens())
        assertSame(KvPalette.LIGHT, Look.paletteLight)
        assertSame(KvPalette.DARK, Look.paletteDark)
        assertSame(KvPalette.LIGHT, KvPalette.LIGHT.with(emptyMap(), dark = false))
        // Режимы — те же объекты, пока палитра та же: экраны сравнивают их по ссылке.
        Look.dark = false
        assertSame(Modes.Today, Modes.Today)
    }
}
