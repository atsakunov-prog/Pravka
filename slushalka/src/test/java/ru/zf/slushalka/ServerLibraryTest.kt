package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.slushalka.data.ServerLibrary

/**
 * Оглавление сервера: разбор `index.json`, порядок слушания и ключ книги.
 * Порядок и ключ ломаются молча - позиция уехала бы в другой файл или книга
 * с сервера начиналась бы сначала, - поэтому проверяются здесь.
 */
class ServerLibraryTest {

    private val sample = """
        {"version": 1, "generated": "2026-10-02T01:36:15+0300", "books_dir": "Книги",
         "books": [
          {"folder": "Борис Акунин - Семнадцатый век", "author": "Борис Акунин", "title": "Семнадцатый век",
           "modified": 1790893726,
           "text": [{"path": "Акунин.epub", "size": 100}, {"path": "Акунин.fb2", "size": 40228597}],
           "cover": "cover.jpg",
           "audio": [
             {"path": "CD2/01.mp3", "size": 5, "ms": 5000},
             {"path": "10 Глава.mp3", "size": 3, "ms": 3000},
             {"path": "CD10/01.mp3", "size": 6, "ms": 6000},
             {"path": "02 Глава.mp3", "size": 2, "ms": 2000},
             {"path": "01 Предисловие.mp3", "size": 1, "ms": 1000}
           ],
           "audio_ms": 17000,
           "other": [
             {"path": "слушалка-разметка.json", "size": 2300},
             {"path": "слушалка-звук.json", "size": 10},
             {"path": "_Слушалка/позиции-Саша.json", "size": 173},
             {"path": "readme.txt", "size": 5}
           ]},
          {"folder": "Без обложки", "author": "", "title": "", "modified": 1, "text": [], "cover": null,
           "audio": [], "audio_ms": 0, "other": []},
          {"folder": "без обложки", "title": "двойник по регистру"}
         ]}
    """.trimIndent()

    @Test
    fun parsesIndex() {
        val idx = ServerLibrary.parse(sample, fetchedAt = 42)
        assertEquals("Книги", idx.booksDir)
        assertEquals(42L, idx.fetchedAt)
        // Папка без учёта регистра - одна книга: второй «без обложки» отброшен.
        assertEquals(2, idx.books.size)
        val b = idx.books[0]
        assertEquals("cover.jpg", b.cover)
        assertNull(idx.books[1].cover)
        // Пустое название - по имени папки.
        assertEquals("Без обложки", idx.books[1].title)
        assertEquals(17000L, b.audioMs)
        assertEquals(17L, b.audioBytes)
    }

    @Test
    fun audioInScannerOrder() {
        val b = ServerLibrary.parse(sample).books[0]
        // Корень по-человечески, потом подпапки: CD2 раньше CD10.
        assertEquals(
            listOf("01 Предисловие.mp3", "02 Глава.mp3", "10 Глава.mp3", "CD2/01.mp3", "CD10/01.mp3"),
            b.audio.map { it.path },
        )
    }

    @Test
    fun textKitTakesFb2AndOurFiles() {
        val b = ServerLibrary.parse(sample).books[0]
        assertEquals("Акунин.fb2", b.mainText?.path)
        // Текст и файлы Слушалки из корня папки; метка звука и чужая подпапка - мимо.
        assertEquals(listOf("Акунин.fb2", "слушалка-разметка.json"), b.textKit.map { it.path })
    }

    @Test
    fun bookKeyIsTheDownloadedOne() {
        val idx = ServerLibrary.parse(sample)
        val book = ServerLibrary.toBook(idx, idx.books[0], "Books", "content://tree")
        assertEquals("Books/Борис Акунин - Семнадцатый век", book.id)
        assertEquals("Борис Акунин - Семнадцатый век", book.folderName)
        assertEquals("Книги/Борис Акунин - Семнадцатый век/CD2/01.mp3", book.files[3].remote)
        assertEquals("Книги/Борис Акунин - Семнадцатый век/Акунин.fb2", book.textRemote)
        assertEquals("Книги/Борис Акунин - Семнадцатый век/cover.jpg", book.coverRemote)
        assertEquals(17000L, book.totalMs)
        // Длительности от сервера: мерить на телефоне нечего.
        assertEquals(true, book.durationsReady)
        assertEquals(false, book.onPhone)
        assertEquals(true, book.streams)
        assertNotNull(idx.byFolder("БОРИС АКУНИН - СЕМНАДЦАТЫЙ ВЕК"))
    }
}
