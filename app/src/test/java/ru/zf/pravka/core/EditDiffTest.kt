package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Правка одного слова руками должна уезжать в словарь без модели; всё, что
// сложнее одной замены, — нет: иначе словарь наполнится мусором из переписок.
class EditDiffTest {

    @Test
    fun `одно слово заменено — замена найдена и похожа`() {
        val s = EditDiff.singleSubstitution(
            "Созвонился со стаф джетом по отчёту, договорились до пятницы.",
            "Созвонился со Стаффджетом по отчёту, договорились до пятницы.",
        )
        // «стаф джетом» — два слова у нас, одно у владельца.
        assertEquals("стаф джетом", s!!.from)
        assertEquals("Стаффджетом", s.to)
        assertTrue(s.similar)
    }

    @Test
    fun `слово заменено на непохожее — подсказка, не замена`() {
        val s = EditDiff.singleSubstitution(
            "Надо сделать отчёт к вечеру и отправить.",
            "Надо выполнить отчёт к вечеру и отправить.",
        )
        assertEquals("сделать", s!!.from)
        assertEquals("выполнить", s.to)
        assertFalse(s.similar)
    }

    @Test
    fun `текст вокруг нашего — контекст, не правка`() {
        val s = EditDiff.singleSubstitution(
            "Встреча с Ивановым перенесена на четверг.",
            "Привет! Встреча с Ивановым перенесена на пятницу. Напиши, если не подходит.",
        )
        assertEquals("четверг", s!!.from)
        assertEquals("пятницу", s.to)
    }

    @Test
    fun `чужая голова с точкой отрезается от замены первого слова`() {
        val s = EditDiff.singleSubstitution(
            "Четверг — встреча с Ивановым и Петровым.",
            "Привет! Пятница — встреча с Ивановым и Петровым.",
        )
        assertEquals("Четверг", s!!.from)
        assertEquals("Пятница", s.to)
    }

    @Test
    fun `слово внутри двухсловной правки — одна замена`() {
        val s = EditDiff.singleSubstitution(
            "Созвон с Петей завтра в десять утра.",
            "Созвон с Пётром завтра в десять утра.",
        )
        assertEquals("Петей", s!!.from)
        assertEquals("Пётром", s.to)
    }

    @Test
    fun `две замены — не словарный случай`() {
        assertNull(
            EditDiff.singleSubstitution(
                "Созвон с Петей завтра в десять утра.",
                "Созвон с Пётром послезавтра в десять утра.",
            )
        )
    }

    @Test
    fun `только пунктуация и регистр — правки нет`() {
        assertNull(
            EditDiff.singleSubstitution(
                "созвон с петей завтра в десять",
                "Созвон с Петей завтра, в десять.",
            )
        )
    }

    @Test
    fun `вставленное слово без удаления — не замена`() {
        assertNull(
            EditDiff.singleSubstitution(
                "Созвон с Петей завтра в десять утра.",
                "Созвон с Петей завтра в десять утра точно.",
            )
        )
    }

    @Test
    fun `совсем другой текст — ничего`() {
        assertNull(EditDiff.singleSubstitution("Один текст про работу.", "Совсем иная фраза про бег."))
    }
}
