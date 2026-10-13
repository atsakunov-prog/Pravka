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
        Look.v4 = false
    }

    @Test
    fun `у всех значков, кроме диска, есть пара Material`() {
        // Почерк Gemini — у «Вида 4.0»; v2 рисует набор Ковчега (тест ниже).
        Look.v4 = true
        Glyphs.gemini = true
        val v = vectors()
        assertTrue("значков нашлось подозрительно мало: ${v.size}", v.size >= 60)
        val ours = v.filterValues { !it.name.startsWith("Gemini.") }.keys
        assertEquals(setOf("Disk"), ours)
    }

    @Test
    fun `наш почерк — наши значки, переключение сразу`() {
        Look.v4 = true
        Glyphs.gemini = false
        assertTrue(vectors().values.none { it.name.startsWith("Gemini.") })
        Glyphs.gemini = true
        assertTrue(Glyphs.Pravka.name.startsWith("Gemini."))
    }

    // Вид v2 (Правка 5.0, план §2): значки — набор Ковчега линией 1,75, те же
    // пути, что у веба; выбор почерка его не трогает; пары нет — наш знак той
    // же толщины, а не Material.
    @Test
    fun `вид v2 — набор Ковчега, почерк не важен`() {
        Look.v4 = false
        for (g in listOf(true, false)) {
            Glyphs.gemini = g
            val v = vectors()
            assertTrue(v.values.none { it.name.startsWith("Gemini.") })
            assertEquals("Kv.edit", Glyphs.Pravka.name)
            assertEquals("Kv.dela", Glyphs.Delo.name)
            assertEquals("Kv.clock", Glyphs.Zasechka.name)
            // Большинство значков приложения нашли пару в наборе.
            val kv = v.values.count { it.name.startsWith("Kv.") }
            assertTrue("в наборе Ковчега мало пар: $kv из ${v.size}", kv * 2 > v.size)
            v.values.filter { !it.name.startsWith("Kv.") }.forEach { vec ->
                val stroke = (vec.root.first() as androidx.compose.ui.graphics.vector.VectorPath).strokeLineWidth
                assertEquals("толщина у ${vec.name}", KvGlyphs.STROKE, stroke, 0.001f)
            }
        }
    }

    @Test
    fun `набор Ковчега — пути веба`() {
        // Шестерёнка считается так же, как в вебе: восемь зубцов и ось.
        assertTrue(KvGlyphs.has("gear"))
        assertTrue(KvGlyphs.has("candles") && KvGlyphs.has("shield") && KvGlyphs.has("claude"))
        assertEquals(null, KvGlyphs.byKey("нет-такого"))
    }
}
