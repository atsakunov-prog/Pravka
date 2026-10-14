package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Подпись шапки v2 переносится по словам (14.10.2026): число не отрывается от своего слова, « · » остаётся в
// конце строки, а переносить можно только после разделителя или внутри длинного куска без чисел.
class HeadLineTest {

    private val n = HeadLine.NBSP

    @Test
    fun `число держится за своё слово, перенос - после точки`() {
        assertEquals("ср,${n}14${n}окт$n· 18:50$n· очки$n+84", HeadLine.wrap("ср, 14 окт · 18:50 · очки +84"))
        assertEquals("3${n}сегодня$n· 2${n}просрочено$n· 1${n}закрыто", HeadLine.wrap("3 сегодня · 2 просрочено · 1 закрыто"))
    }

    @Test
    fun `минус и длительность не рвутся`() {
        assertEquals("очки$n${Fmt.MINUS}3", HeadLine.wrap("очки ${Fmt.MINUS}3"))
        assertEquals("не размечено${n}2${n}ч${n}2${n}м", HeadLine.wrap("не размечено 2 ч 2 м"))
    }

    @Test
    fun `кусок без чисел переносится как обычно`() {
        assertEquals("телефон и компьютер", HeadLine.wrap("телефон и компьютер"))
        assertEquals("Встреча с Ольгой про бюджет", HeadLine.wrap("Встреча с Ольгой про бюджет"))
    }

    @Test
    fun `между кусками всегда есть место переноса`() {
        val s = HeadLine.wrap("среда, 14 октября · 18:50 · очки +84")
        // После каждой точки — обычный пробел: строка не склеивается в одно неразрывное слово.
        assertEquals(2, Regex("· ").findAll(s).count())
        assertFalse(s.contains(" ·"))
    }
}
