package ru.zf.slushalka

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.Docx
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.ui.themeAt

/**
 * Word открывает только корректный XML: один неэкранированный «&» из текста
 * книги - и документ «повреждён». Проверяем, что все части разбираются.
 */
class DocxTest {

    @Test
    fun `docx - zip из разбираемых XML, текст экранирован, мусорные знаки выкинуты`() {
        val out = ByteArrayOutputStream()
        Docx.write(
            out,
            listOf(
                Docx.Para("«Бессмертник» & <Лора>", Docx.Style.TITLE),
                Docx.Para("строка\u0007 с колокольчиком\nвторая строка", Docx.Style.QUOTE),
            ),
        )
        val parts = HashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                parts[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.forEach { (name, xml) ->
            runCatching { factory.newDocumentBuilder().parse(xml.byteInputStream()) }
                .onFailure { throw AssertionError("$name не разбирается: ${it.message}") }
        }
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("&amp; &lt;Лора&gt;"))
        assertTrue(!doc.contains('\u0007'))
        assertTrue(doc.contains("<w:br/>"))
    }

    @Test
    fun `бумага по времени суток`() {
        assertEquals(Settings.THEME_PAPER, themeAt(12))
        assertEquals(Settings.THEME_SEPIA, themeAt(20))
        assertEquals(Settings.THEME_WARM, themeAt(23))
        assertEquals(Settings.THEME_WARM, themeAt(3))
    }
}
