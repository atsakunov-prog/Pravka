package ru.zf.pravka.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Границы слов для словаря: хвост русских окончаний ловит формы («логи»,
// «Полли» → «Полли»), но не превращает двухбуквенное слово в префикс — «та»
// не должно ловить «так» (753 ложных срабатывания подсказки, 16.09.2026).
class DictionaryApplierTest {

    private fun hit(from: String, text: String, endings: Boolean = true) =
        DictionaryApplier.boundaryRegex(from, endings)!!.containsMatchIn(text)

    @Test
    fun `короткое слово — только точное совпадение`() {
        assertTrue(hit("та", "та самая"))
        assertFalse(hit("та", "так и там"))
        assertTrue(hit("Ви", "и Ви посмотрела"))
        assertFalse(hit("Ви", "я вижу вид"))
    }

    @Test
    fun `от трёх букв — с окончаниями, но в границах слова`() {
        assertTrue(hit("лог", "посмотри логи"))
        assertTrue(hit("Полли", "сказала Полли"))
        // Хвост до трёх букв — намеренно широкий: «логика» и «осуди» для
        // ПОДСКАЗКИ считаются формами, жёсткая замена таким хвостом не пользуется.
        assertTrue(hit("лог", "логика"))
        assertFalse(hit("лог", "логистика"))
        assertTrue(hit("осу", "осуди"))
    }

    @Test
    fun `жёсткая замена — без окончаний`() {
        assertTrue(hit("поли", "тётя поли", endings = false))
        assertFalse(hit("поли", "полис", endings = false))
        assertFalse(hit("осу", "осуди", endings = false))
    }
}
