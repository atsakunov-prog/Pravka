package ru.zf.pravka.ui

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Почерк значков (docs/agreements.md, «Третье издание»): владелец — «может,
// попробуем иконки ещё в стиле Gemini?». Каждый значок приложения в почерке
// Gemini — путь Material Symbols, а не наш штрих; своя пара не нашлась только
// у «диска», и там наш знак, только тоньше. Переключение — без перезапуска.
class GlyphsStyleTest {

    private val getters = Glyphs::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java && it.name.startsWith("get") }

    private fun vectors(): Map<String, ImageVector> =
        getters.associate { it.name.removePrefix("get") to it.invoke(Glyphs) as ImageVector }

    @After
    fun back() {
        Glyphs.gemini = true
    }

    @Test
    fun `у всех значков, кроме диска, есть пара Material`() {
        Glyphs.gemini = true
        val v = vectors()
        assertTrue("значков нашлось подозрительно мало: ${v.size}", v.size >= 60)
        val ours = v.filterValues { !it.name.startsWith("Gemini.") }.keys
        assertEquals(setOf("Disk"), ours)
    }

    @Test
    fun `наш почерк — наши значки, переключение сразу`() {
        Glyphs.gemini = false
        assertTrue(vectors().values.none { it.name.startsWith("Gemini.") })
        Glyphs.gemini = true
        assertTrue(Glyphs.Pravka.name.startsWith("Gemini."))
    }
}
