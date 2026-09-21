package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.ui.typograph

/**
 * Главное свойство типографа - длина текста не меняется.
 *
 * От неё зависит всё: позиции чтения, разметка, синк между устройствами и
 * карта «звук - текст». Замена пробела на неразрывный и дефиса на тире длину
 * сохраняют, а вот «--» на тире - уже нет, поэтому такой замены и нет.
 */
class TypographTest {

    private val samples = listOf(
        "Он шагал ровно, без ритма, как учила мать, и песок под ним молчал.",
        "Пыль над Арракисом - это не пыль, а мелкий песок, и он всюду.",
        "В начале было слово, и слово было у Бога.",
        "",
        " ",
        "а",
        "и и и и и",
        "Слово — тире уже стоит, и перед ним пробел.",
    )

    @Test
    fun `длина не меняется`() {
        for (t in samples) assertEquals(t, t.length, typograph(t).length)
    }

    @Test
    fun `после коротких слов неразрывный пробел`() {
        val out = typograph("и в лесу")
        assertEquals("и в лесу", out)
    }

    @Test
    fun `дефис между пробелами становится тире`() {
        val out = typograph("это - книга")
        assertTrue(out.contains('—'))
        assertTrue("перед тире неразрывный", out.contains(" —"))
    }

    @Test
    fun `длинные слова не трогаются`() {
        assertEquals("книга лежит", typograph("книга лежит"))
    }
}
