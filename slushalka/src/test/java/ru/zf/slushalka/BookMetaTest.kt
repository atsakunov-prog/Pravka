package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.zf.slushalka.text.BookMeta

/** Серия из головы файла: fb2 в любой кодировке, epub от calibre и по EPUB3. */
class BookMetaTest {

    @Test
    fun fb2SeriesFromTitleInfoOnly() {
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook><description>
              <title-info><book-title>Азазель</book-title>
                <sequence name="Приключения Эраста Фандорина" number="1"/>
              </title-info>
              <publish-info><sequence name="Издательская серия" number="7"/></publish-info>
            </description><body>…</body></FictionBook>"""
        assertEquals(BookMeta.Series("Приключения Эраста Фандорина", "1"), BookMeta.fb2Series(fb2))
        // Серия только у издательства - это не серия книги.
        assertNull(BookMeta.fb2Series(fb2.replace(Regex("<title-info>.*?</title-info>", RegexOption.DOT_MATCHES_ALL), "<title-info/>")))
    }

    @Test
    fun fb2InWindows1251() {
        val text = """<?xml version="1.0" encoding="windows-1251"?><FictionBook><description><title-info>""" +
            """<sequence number="3" name="Ведьмак &amp; Ко"/></title-info></description></FictionBook>"""
        val bytes = text.toByteArray(charset("windows-1251"))
        assertEquals(BookMeta.Series("Ведьмак & Ко", "3"), BookMeta.fb2Series(BookMeta.decodeHead(bytes)))
    }

    @Test
    fun epubCalibreAndEpub3() {
        val calibre = """<package><metadata>
            <meta content="2.0" name="calibre:series_index"/>
            <meta name="calibre:series" content="Дюна"/>
            </metadata></package>"""
        assertEquals(BookMeta.Series("Дюна", "2"), BookMeta.opfSeries(calibre))
        val epub3 = """<package><metadata>
            <meta property="belongs-to-collection" id="c01">Основание</meta>
            <meta refines="#c01" property="collection-type">series</meta>
            <meta refines="#c01" property="group-position">1.5</meta>
            </metadata></package>"""
        assertEquals(BookMeta.Series("Основание", "1.5"), BookMeta.opfSeries(epub3))
        assertNull(BookMeta.opfSeries("<package><metadata><meta name=\"cover\" content=\"img\"/></metadata></package>"))
    }

    @Test
    fun orderPutsUnnumberedLast() {
        val sorted = listOf(null, "10", "2", "1.5").sortedBy { BookMeta.order(it) }
        assertEquals(listOf("1.5", "2", "10", null), sorted)
    }
}
