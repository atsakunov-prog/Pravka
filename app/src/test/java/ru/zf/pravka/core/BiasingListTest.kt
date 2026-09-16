package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Подсказки распознавателю из словаря: слова владельца впереди семени,
// защищённые впереди правых частей, латиница наравне, дубли без регистра,
// потолок сорок (docs/agreements.md, «Подсказки распознавателю»).
class BiasingListTest {

    private fun seed(n: Int, hits: Int = 5) = (1..n).map {
        DictEntry(id = it.toLong(), from = "ослышка$it", to = "термин$it", mode = DictMode.HARD, hits = hits, createdAt = 0)
    }

    private fun owner(from: String, to: String, mode: DictMode = DictMode.HARD, hits: Int = 0) =
        DictEntry(id = 1000 + kotlin.math.abs(from.hashCode().toLong()), from = from, to = to, mode = mode, hits = hits, createdAt = 1)

    private val isSeed: (DictEntry) -> Boolean = { it.id < 1000 }

    @Test
    fun `слова владельца попадают в сорок даже с нулём срабатываний`() {
        val entries = seed(60) + listOf(
            owner("стаф джет", "Стаффджет"),
            owner("тейсти кофе", "Tasty Coffee"),
            owner("Шепелина", "", mode = DictMode.PROTECT),
        )
        val built = BiasingList.build(entries, isSeed)
        assertEquals(40, built.strings.size)
        assertTrue(built.strings.contains("Стаффджет"))
        assertTrue(built.strings.contains("Шепелина"))
        // Латиница владельца — тоже впереди семени, наравне с кириллицей.
        assertEquals(3, built.ownerCount)
        // Защищённое слово владельца — самое первое.
        assertEquals("Шепелина", built.strings.first())
        assertEquals("Стаффджет", built.strings[1])
        assertEquals("Tasty Coffee", built.strings[2])
    }

    @Test
    fun `латиница наравне с кириллицей и считается отдельно`() {
        val entries = listOf(
            owner("эбитда", "EBITDA", hits = 100),
            owner("эскро", "эскроу", hits = 1),
            owner("Strava", "", mode = DictMode.PROTECT, hits = 50),
        )
        val built = BiasingList.build(entries, isSeed)
        assertEquals(listOf("Strava", "EBITDA", "эскроу"), built.strings)
        assertEquals(2, built.latinCount)
        assertEquals(3, built.ownerCount)
    }

    @Test
    fun `дубли схлопываются без учёта регистра, выключенные и пустые не идут`() {
        val entries = listOf(
            owner("стаф джет", "Стаффджет", hits = 3),
            owner("стафджет", "стаффджет", hits = 9),
            owner("Стаффджет", "", mode = DictMode.PROTECT),
            owner("мусор", "Мусор").copy(enabled = false),
            owner("пусто", ""),
            owner("защита без слова", "", mode = DictMode.PROTECT).copy(from = " "),
        )
        val built = BiasingList.build(entries, isSeed)
        assertEquals(listOf("Стаффджет"), built.strings)
    }

    @Test
    fun `внутри группы порядок по срабатываниям, семя после владельца`() {
        val entries = seed(3, hits = 100) + listOf(
            owner("а", "Альфа", hits = 1),
            owner("б", "Бета", hits = 7),
        )
        val built = BiasingList.build(entries, isSeed, limit = 4)
        assertEquals(listOf("Бета", "Альфа", "термин1", "термин2"), built.strings)
        assertEquals(2, built.ownerCount)
        assertEquals("4 строк, владельца 2, латиницей 0", built.describe())
    }
}
