package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.BookState
import ru.zf.slushalka.data.PositionSync
import ru.zf.slushalka.data.WebPlace

/**
 * Веб «Книг» и Слушалка (14.10.2026): ссылка «Открыть в Слушалке», пересадка в Jellyfin и место из веба.
 * Место - самое главное: здесь проверяется, что место веба не перетирает своё молча и не спрашивается зря.
 */
class WebLinkTest {

    private val folder = "Борис Акунин - 4 - Между Европой и Азией. Семнадцатый век"
    private val id = "Books/$folder"

    // --- ссылки -------------------------------------------------------------------------------------------

    @Test
    fun bookLinkEncodedAsChromeSendsIt() {
        val enc = java.net.URLEncoder.encode(folder, "UTF-8").replace("+", "%20")
        assertEquals(DeepLink.Link.Book(folder), DeepLink.parse("slushalka://book?folder=$enc"))
    }

    @Test
    fun bookLinkRawCyrillicAndPlusInName() {
        assertEquals(DeepLink.Link.Book(folder), DeepLink.parse("slushalka://book?folder=$folder"))
        // «+» в имени папки страница кодирует как %2B - он остаётся плюсом, а не пробелом
        assertEquals(DeepLink.Link.Book("C++ - Книга"), DeepLink.parse("slushalka://book?folder=C%2B%2B%20-%20%D0%9A%D0%BD%D0%B8%D0%B3%D0%B0"))
    }

    @Test
    fun badLinksAreNotBooks() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse("https://book?folder=x"))
        assertNull(DeepLink.parse("slushalka://book"))
        assertNull(DeepLink.parse("slushalka://book?folder="))
        assertNull(DeepLink.parse("slushalka://book?folder=..%2F_%D0%A1%D0%BB%D1%83%D1%88%D0%B0%D0%BB%D0%BA%D0%B0"))
        assertNull(DeepLink.parse("slushalka://book?folder=a%5Cb"))
        assertNull(DeepLink.parse("slushalka://elsewhere?folder=x"))
        // Битый %-код - не падение: имя как есть
        assertEquals(DeepLink.Link.Book("50%"), DeepLink.parse("slushalka://book?folder=50%"))
    }

    @Test
    fun appLinkOnlyFromWhitelist() {
        val web = "https://media.example:8443"
        val l = DeepLink.parse("slushalka://app?id=jellyfin&url=" + java.net.URLEncoder.encode(web, "UTF-8"))
        assertEquals(DeepLink.Link.App("jellyfin", "org.jellyfin.mobile", web), l)
        assertNull(DeepLink.parse("slushalka://app?id=com.android.settings"))
        // Запасной адрес - только https: javascript:, file: и прочее не открываем
        assertEquals(DeepLink.Link.App("jellyfin", "org.jellyfin.mobile", null),
            DeepLink.parse("slushalka://app?id=JELLYFIN&url=javascript%3Aalert(1)"))
    }

    // --- место веба ---------------------------------------------------------------------------------------

    /** Ровно то, что пишет сервер (booksorter\reader.py, _publish). */
    private val serverFile = """
        {"v": 2, "profile": "Саша", "device": "веб", "at": 1791928303970, "by": "читалка в вебе «Книг»",
         "books": {"$folder": {"read": {"char": 90000, "of": 638628, "at": 1791928303969}, "finished": false, "at": 1791928303969}}}
    """.trimIndent()

    @Test
    fun serverFileReadsAsPlaces() {
        val r = PositionSync.parsePlaces(serverFile)!!
        assertEquals("Саша", r.profile)
        assertEquals("веб", r.device)
        val s = r.states.getValue(folder)
        assertEquals(90000, s.readChar)
        assertEquals(638628, s.textChars)
        assertEquals(0L, s.listenAt)
        assertEquals("_Веб/места-Саша.json", WebPlace.path("Саша"))
    }

    private fun web(char: Int, at: Long, of: Int = 638628) = BookState(folder, readChar = char, textChars = of, readAt = at, updatedAt = at)

    @Test
    fun newerAndFarIsAsked() {
        val here = BookState(id, readChar = 20000, textChars = 638628, readAt = 100, listenAt = 50, updatedAt = 100)
        assertTrue(WebPlace.worth(here, web(90000, 200), seenAt = 0))
    }

    @Test
    fun olderThanHereIsNotAsked() {
        // Здесь слушали позже, чем читали в вебе: своё свежее - вопроса нет
        val here = BookState(id, readChar = 20000, textChars = 638628, readAt = 100, listenAt = 300, updatedAt = 300)
        assertFalse(WebPlace.worth(here, web(90000, 200), seenAt = 0))
    }

    @Test
    fun nearOwnPageIsNotAsked() {
        val here = BookState(id, readChar = 89000, textChars = 638628, readAt = 100, updatedAt = 100)
        assertFalse(WebPlace.worth(here, web(90000, 200), seenAt = 0))
    }

    @Test
    fun answeredOnceIsNotAskedAgain() {
        val here = BookState(id, readChar = 20000, textChars = 638628, readAt = 100, updatedAt = 100)
        assertFalse(WebPlace.worth(here, web(90000, 200), seenAt = 200))
        // Новое место веба - новый вопрос
        assertTrue(WebPlace.worth(here, web(150000, 260), seenAt = 200))
    }

    @Test
    fun neverOpenedHereIsAsked() {
        assertTrue(WebPlace.worth(BookState(id), web(5000, 200), seenAt = 0))
        // Места здесь нет вовсе (-1) - сравнивать не с чем: спрашиваем, даже если в вебе начало
        assertTrue(WebPlace.worth(BookState(id), web(0, 200), seenAt = 0))
        // Без знака (-1) место веба - не место
        assertFalse(WebPlace.worth(BookState(id), web(-1, 200), seenAt = 0))
    }

    @Test
    fun otherEditionByShare() {
        // На телефоне свой fb2 вдвое длиннее: середина книги - середина и здесь
        assertEquals(500_000, WebPlace.charHere(web(250_000, 200, of = 500_000), localChars = 1_000_000))
        // Издание то же (разница меньше 2 %) - знак как есть
        assertEquals(250_000, WebPlace.charHere(web(250_000, 200, of = 500_000), localChars = 505_000))
    }

    @Test
    fun pickPrefersOpenBookThenNewest() {
        val other = "Другая - Книга"
        val places = mapOf(folder to web(90000, 200), other to BookState(other, readChar = 5000, textChars = 100000, readAt = 500))
        val canonical = { k: String -> "Books/$k" }
        val nothing = { k: String -> BookState(k) }
        val o = WebPlace.pick(places, canonical, nothing, { 0L }, { true }, current = id)!!
        assertEquals(id, o.bookId)
        assertEquals(90000, o.char)
        val newest = WebPlace.pick(places, canonical, nothing, { 0L }, { true }, current = null)!!
        assertEquals("Books/$other", newest.bookId)
        // Книги нет ни на полке, ни на сервере - не спрашиваем
        assertNull(WebPlace.pick(places, canonical, nothing, { 0L }, { false }, current = null))
    }
}
