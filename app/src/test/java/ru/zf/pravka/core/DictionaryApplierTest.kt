package ru.zf.pravka.core

import org.junit.Assert.assertEquals
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

    // Ослышка словаря в другой форме — подсказкой модели (20.11.2026, «ебеда»).
    private fun hard(from: String, to: String) =
        DictEntry(id = from.hashCode().toLong(), from = from, to = to, mode = DictMode.HARD, createdAt = 0)

    private fun loose(entries: List<DictEntry>, text: String, people: List<String> = emptyList()) =
        DictionaryApplier.looseHints(entries, text, people).map { it.from to it.to }

    private val none = emptyList<Pair<String, String>>()

    @Test
    fun `ослышка в другой форме уходит подсказкой`() {
        val dict = listOf(hard("эбитда", "EBITDA"), hard("ебеде", "EBITDA"))
        assertEquals(listOf("ебедой" to "EBITDA"), loose(dict, "ну так с ебедой всё в порядке"))
        assertEquals(listOf("ебеда" to "EBITDA"), loose(dict, "у меня ебеда просчиталась"))
        val one = DictionaryApplier.looseHints(dict, "по ебедой", emptyList()).single()
        assertEquals(DictMode.HINT, one.mode)
        assertTrue(one.note.contains("ебеде"))
    }

    @Test
    fun `верное слово в другом падеже, верные слова и имена не подсказываются`() {
        val dict = listOf(
            hard("мариану", "Марианну"),
            hard("плечей", "плеч"),
            hard("Наталь", "Наташ"),
            DictEntry(from = "Мариаша", mode = DictMode.PROTECT, createdAt = 0),
        )
        assertEquals(none, loose(dict, "мы с Марианной и плечом"))
        assertEquals(none, loose(dict, "Наталья, добрый день", people = listOf("Наталья Петрова")))
        // Одна «н» — ослышка, а не падеж: подсказка нужна.
        assertEquals(listOf("мариане" to "Марианну"), loose(dict, "я мариане подарю"))
    }

    @Test
    fun `короткая основа, фраза и латиница не идут, одно слово — одна строка`() {
        val dict = listOf(
            hard("осу", "ОСУ"),
            hard("эм энд эй", "M&A"),
            hard("notion", "Notion"),
            hard("мариану", "Марианну"),
            hard("марианой", "Марианной"),
        )
        assertEquals(none, loose(dict, "осуди эм энд эя notions"))
        // «марианою» ближе к «марианой» (общее начало длиннее) — берётся она.
        assertEquals(listOf("марианою" to "Марианной"), loose(dict, "с марианою"))
    }
}
