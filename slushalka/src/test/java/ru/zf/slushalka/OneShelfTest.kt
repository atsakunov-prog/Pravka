package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.Ask
import ru.zf.slushalka.data.AskLog
import ru.zf.slushalka.data.BookState
import ru.zf.slushalka.data.Note
import ru.zf.slushalka.data.Notes
import ru.zf.slushalka.data.ServerLibrary
import ru.zf.slushalka.data.mergeCopies
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.BookFile
import ru.zf.slushalka.library.OneShelf

/**
 * Одна полка на полке Саши 05.10: Синдбад жил под тремя ключами, Джордж под
 * двумя, Акунин т. 4 - под тремя, и у каждой копии были свои места. Узнавание
 * копий, выбор главной, перенос ключа и слияние мест, вопросов и пометок
 * ломаются молча - книга просто начинается сначала, - поэтому проверяются.
 */
class OneShelfTest {

    private val index = ServerLibrary.parse(
        """
        {"books_dir": "Книги", "books": [
          {"folder": "Эстер Перель - Всегда желанные", "author": "Эстер Перель", "title": "Всегда желанные",
           "text": [{"path": "perel.fb2", "size": 1234567}], "audio": [], "other": [],
           "former": ["Перель Эстер - Всегда желанные. Как сохранить страсть в длительных отношениях"]},
          {"folder": "Стивен Хокинг и др - 1 - Джордж и тайны Вселенной", "author": "Стивен Хокинг",
           "title": "Джордж и тайны Вселенной", "series": "Джордж", "series_index": 1,
           "text": [{"path": "george.fb2", "size": 500}], "cover": "cover.jpg",
           "audio": [{"path": "01.mp3", "size": 10, "ms": 1000}], "other": [{"path": "озвучка.json", "size": 5}]},
          {"folder": "Приключения Синдбада-морехода", "author": "", "title": "Приключения Синдбада-морехода",
           "text": [{"path": "sindbad.fb2", "size": 700}], "audio": [], "other": [],
           "former": [{"key": "Books/Автор Неизвестен -- Народные сказки - Приключения Синдбада-морехода"}]},
          {"folder": "Шахразада - Похождения Синдбада-Морехода", "author": "Шахразада",
           "title": "Похождения Синдбада-Морехода", "text": [], "audio": [], "other": []},
          {"folder": "Борис Акунин - 4 - Между Европой и Азией. Семнадцатый век", "author": "Борис Акунин",
           "title": "Между Европой и Азией. Семнадцатый век", "text": [], "audio": [], "other": [],
           "former": ["история росс государства"]}
        ]}
        """.trimIndent(),
    )

    private fun book(key: String, audioBytes: Long = 0, text: Boolean = true) = Book(
        id = key,
        folderDocId = "doc:$key",
        title = key.substringAfterLast('/'),
        author = "",
        files = if (audioBytes > 0) listOf(BookFile("a:$key", "01.mp3", "01.mp3", audioBytes)) else emptyList(),
        textDocId = if (text) "t:$key" else null,
        textName = if (text) "book.fb2" else null,
    )

    private val perel = book("Books/Перель Эстер - Всегда желанные. Как сохранить страсть в длительных отношениях")
    private val georgeLive = book("Books/Хокинг Стивен Уильям - Джордж и тайны Вселенной [George's Secret Key to the Universe]", audioBytes = 900)
    private val georgeServer = book("Books/Стивен Хокинг и др - 1 - Джордж и тайны Вселенной")
    private val sindbadOld = book("Books/Автор Неизвестен -- Народные сказки - Приключения Синдбада-морехода")
    private val sindbad = book("Books/Приключения Синдбада-морехода")
    private val shahrazada = book("Books/Шахразада - Похождения Синдбада-Морехода", audioBytes = 300)
    private val akunin = book("Books/Акунин/история росс государства", audioBytes = 50)
    private val vonnegut = book("Books/Воннегут Курт - Бойня №5 [litres]")

    /** Что сказала сверка: Джордж узнан по названию - псевдонимом. */
    private val aliases = mapOf(georgeLive.id to "Стивен Хокинг и др - 1 - Джордж и тайны Вселенной")

    private val raw = listOf(perel, georgeLive, georgeServer, sindbadOld, sindbad, shahrazada, akunin, vonnegut)

    @Test
    fun copiesTakeServerKeysAndLooks() {
        val a = OneShelf.adopt(raw, index, "Books", aliases)
        val ids = a.books.map { it.id }.toSet()
        assertEquals(
            setOf(
                "Books/Эстер Перель - Всегда желанные",
                "Books/Стивен Хокинг и др - 1 - Джордж и тайны Вселенной",
                "Books/Приключения Синдбада-морехода",
                "Books/Шахразада - Похождения Синдбада-Морехода",
                "Books/Борис Акунин - 4 - Между Европой и Азией. Семнадцатый век",
                // Нет на сервере - под своим ключом.
                "Books/Воннегут Курт - Бойня №5 [litres]",
            ),
            ids,
        )
        val perelShelf = a.books.first { it.id == "Books/Эстер Перель - Всегда желанные" }
        // Название и автор - серверные; своя папка помнится для сверки.
        assertEquals("Всегда желанные", perelShelf.title)
        assertEquals("Эстер Перель", perelShelf.author)
        assertEquals(perel.id, perelShelf.phoneKey)
        assertEquals("Книги/Эстер Перель - Всегда желанные", perelShelf.remoteDir)
        // Своя книга с ключом, совпавшим с сервером, отдельного пути телефона не носит.
        assertEquals("", a.books.first { it.id == sindbad.id }.phoneId)
        // Серия и обложка - с сервера.
        val george = a.books.first { it.id.endsWith("Джордж и тайны Вселенной") }
        assertEquals("Джордж", george.series)
        assertEquals("1", george.seriesNum)
        assertEquals("Книги/Стивен Хокинг и др - 1 - Джордж и тайны Вселенной/cover.jpg", george.coverRemote)
    }

    @Test
    fun liveRecordingIsThePrimaryCopy() {
        val a = OneShelf.adopt(raw, index, "Books", aliases)
        // У Джорджа главная - живая запись с телефона, а не копия с серверным именем.
        val george = a.books.first { it.id.endsWith("Джордж и тайны Вселенной") }
        assertEquals(georgeLive.id, george.phoneKey)
        // Синдбад: звука нет ни у кого - главная та, что уже с серверным именем.
        assertEquals(sindbad.id, a.books.first { it.id == sindbad.id }.phoneKey)
        assertEquals(setOf(georgeServer.id, sindbadOld.id), a.extras.map { it.phoneKey }.toSet())
        // Копия без своего звука играет звук сервера потоком; свой звук главнее.
        val georgeText = a.extras.first { it.phoneKey == georgeServer.id }
        assertEquals(listOf("Книги/Стивен Хокинг и др - 1 - Джордж и тайны Вселенной/01.mp3"), georgeText.files.map { it.remote })
        assertTrue(george.files.none { it.isRemote })
        assertNull(george.textRemote)
        // Лишние живут под тем же ключом, что главная: их данные туда и сливаются.
        assertEquals(george.id, a.keyOf[georgeServer.id])
        assertEquals(sindbad.id, a.keyOf[sindbadOld.id])
        // Другое издание Синдбада - своя книга сервера, не лишняя копия.
        assertEquals(shahrazada.id, a.keyOf[shahrazada.id])
        assertEquals("Books/Борис Акунин - 4 - Между Европой и Азией. Семнадцатый век", a.keyOf[akunin.id])
    }

    @Test
    fun withoutIndexTheShelfIsThePhone() {
        val a = OneShelf.adopt(raw, null, "Books", emptyMap())
        assertEquals(raw, a.books)
        assertTrue(a.extras.isEmpty())
    }

    @Test
    fun placesWithoutFolderFindTheirBook() {
        val a = OneShelf.adopt(raw, index, "Books", aliases)
        // Ключ мест без папки на телефоне: прежнее имя - в former оглавления.
        assertEquals(
            "Books/Борис Акунин - 4 - Между Европой и Азией. Семнадцатый век",
            OneShelf.canonical("Books/история росс государства", a.keyOf, index, "Books", aliases),
        )
        // Место с другого устройства, где главная папка зовётся иначе, - тот же ключ.
        assertEquals(
            "Books/Приключения Синдбада-морехода",
            OneShelf.canonical("Книги/Приключения Синдбада-морехода", a.keyOf, index, "Books", aliases),
        )
        // Книга только с телефона - как была.
        assertEquals(vonnegut.id, OneShelf.canonical(vonnegut.id, a.keyOf, index, "Books", aliases))
        assertEquals("Books/Неизвестно что", OneShelf.canonical("Books/Неизвестно что", a.keyOf, index, "Books", aliases))
    }

    @Test
    fun twoSindbadsMergeFreshestPlaces() {
        val id = "Books/Приключения Синдбада-морехода"
        // Под серверным именем - слушал вчера, читал неделю назад.
        val main = BookState(id, fileIndex = 2, posMs = 10_000, absMs = 610_000, listenAt = 2_000, readChar = 100, readAt = 500,
            listenedMs = 3_000, updatedAt = 2_000)
        // Под старым - читал сегодня, слушал давно.
        val old = BookState(sindbadOld.id, fileIndex = 0, posMs = 5_000, absMs = 5_000, listenAt = 100, readChar = 9_000, readAt = 3_000,
            textChars = 50_000, listenedMs = 4_000, updatedAt = 3_000)
        val m = mergeCopies(main, old.copy(bookId = id), fromAnchors = false)
        assertEquals(id, m.bookId)
        assertEquals(610_000L, m.absMs)
        assertEquals(2, m.fileIndex)
        assertEquals(2_000L, m.listenAt)
        assertEquals(9_000, m.readChar)
        assertEquals(3_000L, m.readAt)
        assertEquals(50_000, m.textChars)
        // Прослушанное - разные часы, складывается.
        assertEquals(7_000L, m.listenedMs)
        assertEquals(3_000L, m.updatedAt)
    }

    @Test
    fun twoGeorgesKeepAnchorsOfThePrimary() {
        val id = "Books/Стивен Хокинг и др - 1 - Джордж и тайны Вселенной"
        val serverCopy = BookState(id, absMs = 1_000, listenAt = 10, anchors = listOf(ru.zf.slushalka.text.Anchor(1_000, 10, manual = true)))
        val live = BookState(id, absMs = 90_000, listenAt = 20, anchors = listOf(
            ru.zf.slushalka.text.Anchor(5_000, 50, manual = true), ru.zf.slushalka.text.Anchor(9_000, 90, manual = true),
        ))
        val m = mergeCopies(serverCopy, live, fromAnchors = true)
        assertEquals(90_000L, m.absMs)
        // Отметки «я тут» - главной копии: её запись и играет.
        assertEquals(2, m.anchors.size)
        val other = mergeCopies(serverCopy, live, fromAnchors = false)
        assertEquals(1, other.anchors.size)
    }

    @Test
    fun asksAndNotesAllStay() {
        val a = listOf(Ask(1, 0, "Кто такой Синдбад?", "Мореход"), Ask(3, 0, "Сколько путешествий?", "Семь"))
        val b = listOf(Ask(2, 0, "Где Багдад?", "Ирак"), Ask(3, 0, "Сколько путешествий?", "Семь"))
        val asks = AskLog.union(a, b)
        assertEquals(listOf(1L, 2L, 3L), asks.map { it.at })

        val n1 = listOf(Note(10, 0, 5, "Багдад", "город", updatedAt = 10), Note(11, 6, 9, "рух", "птица", updatedAt = 11))
        val n2 = listOf(Note(11, 6, 9, "рух", "огромная птица", updatedAt = 20), Note(12, 1, 2, "x", "y", updatedAt = 12))
        val notes = Notes.union(n1, n2).associateBy { it.id }
        assertEquals(setOf(10L, 11L, 12L), notes.keys)
        assertEquals("огромная птица", notes.getValue(11).text)
    }

    @Test
    fun formerIsReadFromStringsAndObjects() {
        assertEquals("Приключения Синдбада-морехода", index.byFormer(sindbadOld.id)?.folder)
        assertEquals("Приключения Синдбада-морехода", index.byFormer(sindbadOld.phoneFolder)?.folder)
        assertEquals("Эстер Перель - Всегда желанные", index.byFormer(perel.phoneFolder)?.folder)
        assertNull(index.byFormer("Воннегут Курт - Бойня №5 [litres]"))
        assertFalse(index.books.first { it.folder.startsWith("Шахразада") }.former.isNotEmpty())
    }
}
