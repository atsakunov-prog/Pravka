package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.groupShelf
import ru.zf.slushalka.library.matchesShelfQuery
import ru.zf.slushalka.text.BookMeta

/**
 * Полка 08.10: порядок «Добавленные», а серии и авторы - группами поверх
 * него. Группа встаёт туда, где её верхняя книга; внутри серии - по номерам;
 * одна книга серии - не группа; книга из «Продолжить» вне групп не
 * повторяется. Плюс поиск по полке.
 */
class ShelfGroupsTest {

    private fun book(title: String, author: String = "", series: String? = null, num: String? = null) = Book(
        id = "Books/$title",
        folderDocId = "doc:$title",
        title = title,
        author = author,
        files = emptyList(),
        series = series,
        seriesNum = num,
    )

    private val series: (Book) -> BookMeta.Series? = { b -> b.series?.takeIf { it.isNotBlank() }?.let { BookMeta.Series(it, b.seriesNum) } }
    private val surname: (Book) -> String = { b -> b.author.substringAfterLast(' ') }

    // Уже в порядке «добавленные»: сверху свежее.
    private val george3 = book("Джордж и большой взрыв", "Стивен Хокинг", "Джордж", "3")
    private val perel = book("Всегда желанные", "Эстер Перель")
    private val george1 = book("Джордж и тайны Вселенной", "Стивен Хокинг", "Джордж", "1")
    private val azazel = book("Азазель", "Борис Акунин", "Фандорин", "1")
    private val brief = book("Краткая история времени", "Стивен Хокинг")
    private val shelf = listOf(george3, perel, george1, azazel, brief)

    @Test
    fun seriesStandWhereTheirNewestBookIsAndInsideByNumber() {
        val g = groupShelf(shelf, Settings.GROUP_SERIES, series, surname)
        assertEquals(listOf("Джордж", null), g.map { it.title })
        assertEquals(listOf(george1, george3), g[0].books)
        // Вне групп - как стояли; Фандорин с одной книгой на полке - не группа.
        assertEquals(listOf(perel, azazel, brief), g[1].books)
    }

    @Test
    fun continueBookIsNotRepeatedAmongLooseButStaysInItsSeries() {
        val g = groupShelf(shelf, Settings.GROUP_SERIES, series, surname, skipLoose = perel.id)
        assertEquals(listOf(azazel, brief), g[1].books)
        val inSeries = groupShelf(shelf, Settings.GROUP_SERIES, series, surname, skipLoose = george1.id)
        assertEquals(listOf(george1, george3), inSeries[0].books)
    }

    @Test
    fun authorsAreGroupsEvenWithOneBookSeriesFirst() {
        val g = groupShelf(shelf, Settings.GROUP_AUTHOR, series, surname)
        assertEquals(listOf("Стивен Хокинг", "Эстер Перель", "Борис Акунин"), g.map { it.title })
        assertEquals(listOf(george1, george3, brief), g[0].books)
    }

    @Test
    fun noGroupsKeepsOrderAndSkipsContinue() {
        val g = groupShelf(shelf, Settings.GROUP_NONE, series, surname, skipLoose = george3.id)
        assertEquals(1, g.size)
        assertNull(g[0].title)
        assertEquals(listOf(perel, george1, azazel, brief), g[0].books)
    }

    @Test
    fun searchByEveryWordAnywhereWithYo() {
        assertTrue(matchesShelfQuery(george1, "Джордж", "хокинг вселенн"))
        assertTrue(matchesShelfQuery(azazel, "Фандорин", "фандорин"))
        assertTrue(matchesShelfQuery(book("Ёжик в тумане", "Сергей Козлов"), null, "ежик"))
        assertFalse(matchesShelfQuery(perel, null, "хокинг"))
        assertFalse(matchesShelfQuery(perel, null, "  "))
    }
}
