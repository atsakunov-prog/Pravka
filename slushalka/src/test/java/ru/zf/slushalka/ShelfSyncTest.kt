package ru.zf.slushalka

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.ShelfSync

/**
 * Полка и сверка - общий язык телефона и сервера (`полка-*.json`,
 * `сверка-*.json`, `слушалка-куда.json`). Разойдётся формат - сервер не
 * узнает книги, и всё снова поделится на «телефон» и «сервер».
 */
class ShelfSyncTest {

    @Test
    fun shelfIsWrittenAsAgreed() {
        val books = listOf(
            ShelfSync.ShelfBook(
                "Books/Перель Эстер - Всегда желанные. Как сохранить страсть в длительных отношениях",
                "Перель Эстер - Всегда желанные. Как сохранить страсть в длительных отношениях",
                listOf(ShelfSync.FileEntry("Перель.fb2", 1234567), ShelfSync.FileEntry("CD1/01.mp3", 2345678)),
            ),
        )
        val text = ShelfSync.shelfJson("Саша", "Pixel", 1791150000000, books)
        val o = JSONObject(text)
        assertEquals(1, o.getInt("v"))
        assertEquals("Саша", o.getString("profile"))
        assertEquals("Pixel", o.getString("device"))
        assertEquals(1791150000000, o.getLong("at"))
        val b = o.getJSONArray("books").getJSONObject(0)
        assertEquals(books[0].key, b.getString("key"))
        assertEquals(books[0].folder, b.getString("folder"))
        assertEquals("CD1/01.mp3", b.getJSONArray("files").getJSONObject(1).getString("path"))
        assertEquals(2345678, b.getJSONArray("files").getJSONObject(1).getLong("size"))
        assertEquals(books, ShelfSync.parseShelf(text))
    }

    @Test
    fun fileNamesOneWriterEach() {
        assertEquals("полка-Саша@Pixel 9.json", ShelfSync.shelfFileName("Саша", "Pixel 9"))
        assertEquals("сверка-Саша@Pixel 9.json", ShelfSync.answerFileName("Саша", "Pixel 9"))
        assertEquals("полка-без-имени@устройство.json", ShelfSync.shelfFileName("", "/"))
    }

    @Test
    fun serviceFilesStayOut() {
        assertTrue(ShelfSync.isService("слушалка-справочник.json"))
        assertTrue(ShelfSync.isService("слушалка-звук.json"))
        assertTrue(ShelfSync.isService("CD1/01.mp3.partial"))
        assertTrue(ShelfSync.isService("_Слушалка/позиции-Саша.json"))
        assertFalse(ShelfSync.isService("CD1/01.mp3"))
        assertFalse(ShelfSync.isService("cover.jpg"))
        assertFalse(ShelfSync.isService("слушалка.fb2"))
    }

    @Test
    fun answerIsReadWithShelfAtAndNeed() {
        val a = ShelfSync.parseAnswer(
            """
            {"v": 1, "at": 1791150090000, "shelf_at": 1791150000000,
             "books": {
               "Books/Перель Эстер - Всегда желанные. Как сохранить …": {"folder": "Эстер Перель - Всегда желанные",
                  "how": "прежнее имя", "sure": true, "need": [], "note": ""},
               "Books/Хокинг Стивен Уильям - Джордж": {"folder": "Стивен Хокинг и др - 1 - Джордж и тайны Вселенной",
                  "how": "название", "sure": true, "need": ["audio"], "note": "живая запись заменит машинную"},
               "Books/Воннегут Курт - Бойня №5 [litres]": {"folder": null, "how": "", "sure": true, "need": ["book"]},
               "Books/Непонятное": {"folder": null, "how": "Claude", "sure": false, "need": ["book"],
                  "maybe": "Кто-то - Что-то"},
               "Books/Непонятное 2": {"folder": null, "sure": false, "maybe": {"folder": "Другое - Что-то"}}
             }}
            """.trimIndent(),
        )!!
        assertEquals(1791150090000, a.at)
        assertEquals(1791150000000, a.shelfAt)
        val perel = a.books.getValue("Books/Перель Эстер - Всегда желанные. Как сохранить …")
        assertEquals("Эстер Перель - Всегда желанные", perel.folder)
        assertEquals("прежнее имя", perel.how)
        assertTrue(perel.sure && perel.need.isEmpty())
        assertEquals(setOf("audio"), a.books.getValue("Books/Хокинг Стивен Уильям - Джордж").need)
        val vonnegut = a.books.getValue("Books/Воннегут Курт - Бойня №5 [litres]")
        assertNull(vonnegut.folder)
        assertTrue(vonnegut.sure && "book" in vonnegut.need)
        assertFalse(vonnegut.unknown)
        val unsure = a.books.getValue("Books/Непонятное")
        assertTrue(unsure.unknown)
        assertEquals("Кто-то - Что-то", unsure.maybe)
        assertEquals("Другое - Что-то", a.books.getValue("Books/Непонятное 2").maybe)
        // Записанный у себя ответ читается тем же разбором.
        assertEquals(a, ShelfSync.parseAnswer(ShelfSync.answerJson(a)))
    }

    @Test
    fun brokenAnswerIsNoAnswer() {
        assertNull(ShelfSync.parseAnswer("{не json"))
    }

    @Test
    fun routePointsToTheBook() {
        val o = JSONObject(ShelfSync.routeJson("Стивен Хокинг и др - 1 - Джордж и тайны Вселенной", "Саша", "Pixel"))
        assertEquals("Стивен Хокинг и др - 1 - Джордж и тайны Вселенной", o.getString("folder"))
        assertEquals("Саша", o.getString("by"))
        assertEquals("Pixel", o.getString("device"))
        assertEquals("слушалка-куда.json", ShelfSync.ROUTE)
    }

    @Test
    fun audioAndTextAreTold() {
        assertTrue(ShelfSync.isAudio("CD1/01.MP3"))
        assertTrue(ShelfSync.isAudio("книга.m4b"))
        assertFalse(ShelfSync.isAudio("cover.jpg"))
        assertTrue(ShelfSync.isText("Перель.fb2"))
        assertTrue(ShelfSync.isText("x.fb2.zip"))
        assertTrue(ShelfSync.isText("x.epub"))
        assertFalse(ShelfSync.isText("x.zip"))
    }
}
