package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Chapter
import ru.zf.slushalka.ui.selectionFor
import ru.zf.slushalka.ui.wordAt

/**
 * Выделение тапами: два - слово, три - фраза, четыре - абзац.
 *
 * Конец диапазона - за последним знаком, как у подсветки и у цитаты: разойдись
 * они на знак - и в вопрос уехало бы слово без последней буквы.
 */
class SelectionTest {

    private val plain = "— По-моему, — продолжила Лора, — он чувствует. Анна промолчала.\nВторой абзац тут."

    private fun cut(r: IntRange?) = r?.let { plain.substring(it.first, it.last) }

    @Test
    fun `слово целиком с дефисом внутри`() {
        assertEquals("По-моему", cut(wordAt(plain, plain.indexOf("моему"))))
        assertEquals("Лора", cut(wordAt(plain, plain.indexOf("Лора") + 3)))
    }

    @Test
    fun `тап сразу за словом берёт слово, тап по тире - ничего`() {
        assertEquals("Лора", cut(wordAt(plain, plain.indexOf("Лора") + 4)))
        assertNull(wordAt(plain, 0))
    }

    @Test
    fun `фраза и абзац`() {
        val text = BookText(plain, listOf(Chapter("", 0, plain.length)))
        val at = plain.indexOf("чувствует")
        assertEquals(
            "— По-моему, — продолжила Лора, — он чувствует.",
            cut(selectionFor(3, text, text.blocks, at)),
        )
        assertEquals(plain.substringBefore('\n'), cut(selectionFor(4, text, text.blocks, at)))
        assertEquals("Второй абзац тут.", cut(selectionFor(4, text, text.blocks, plain.indexOf("абзац"))))
    }
}
