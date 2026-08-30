package ru.zf.slushalka.text

import android.util.Base64
import android.util.Xml
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

/**
 * fb2 - обычный XML, и это лучший из форматов для нашей задачи: главы уже
 * размечены тегами, значит и привязка аудио к тексту получается по главам, а
 * не «в среднем по книге». Заодно из fb2 достаётся обложка.
 */
object Fb2Parser {

    // Теги, чей текст - это абзац книги. Всё остальное (описание, служебное)
    // в текст не попадает.
    private val PARAGRAPH = setOf("p", "v", "subtitle", "text-author")

    fun parse(input: InputStream): ParsedBook {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)

        val body = StringBuilder()
        val marks = ArrayList<Pair<String, Int>>()
        val path = ArrayList<String>()

        var bodyDepth = 0          // >0 - мы внутри основного <body>
        var skipUntilDepth = -1    // пропускаем поддерево (сноски, чужой binary)
        var para: StringBuilder? = null
        var titleBuf: StringBuilder? = null
        var titleStart = -1

        var bookTitle = ""
        val authorParts = ArrayList<String>()
        var coverHref: String? = null
        var coverBase64: StringBuilder? = null
        var coverBytes: ByteArray? = null

        fun flushParagraph() {
            val p = para ?: return
            para = null
            val text = TextExtract.decodeEntities(p.toString()).trim()
            if (text.isEmpty()) return
            body.append(text).append('\n')
            titleBuf?.append(if (titleBuf!!.isEmpty()) "" else " ")?.append(text)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    path.add(name)
                    if (skipUntilDepth < 0) when (name) {
                        "body" -> {
                            // <body name="notes"> - это сноски, не текст книги.
                            if (parser.getAttributeValue(null, "name")
                                    ?.contains("note", true) == true
                            ) skipUntilDepth = path.size else bodyDepth++
                        }
                        "binary" -> {
                            val id = parser.getAttributeValue(null, "id").orEmpty()
                            val type = parser.getAttributeValue(null, "content-type").orEmpty()
                            val wanted = coverHref?.removePrefix("#")
                            val isCover =
                                if (wanted != null) id.equals(wanted, true)
                                else type.startsWith("image/") && coverBytes == null
                            if (isCover) coverBase64 = StringBuilder()
                            else skipUntilDepth = path.size   // чужие картинки - мимо
                        }
                        "image" -> {
                            if (path.contains("coverpage") && coverHref == null) {
                                coverHref = parser.getAttributeValue(null, "l:href")
                                    ?: parser.getAttributeValue(null, "href")
                                    ?: parser.getAttributeValue(
                                        "http://www.w3.org/1999/xlink", "href"
                                    )
                            }
                        }
                        "title" -> {
                            if (bodyDepth > 0) {
                                // Каждый заголовок начинает главу - и у частей, и
                                // у глав внутри них. Оглавление получается чуть
                                // подробнее, чем в книге, и это ровно то, что
                                // нужно привязке.
                                titleBuf = StringBuilder()
                                titleStart = body.length
                            }
                        }
                        in PARAGRAPH -> if (bodyDepth > 0) para = StringBuilder()
                        "empty-line" -> if (bodyDepth > 0) body.append('\n')
                    }
                }

                XmlPullParser.TEXT -> {
                    if (skipUntilDepth < 0) {
                        val raw = parser.text ?: ""
                        coverBase64?.append(raw)
                        para?.append(raw.replace('\n', ' '))
                        if (para == null && coverBase64 == null && raw.isNotBlank()) {
                            when (path.lastOrNull()) {
                                "book-title" -> if (bookTitle.isBlank()) bookTitle = raw.trim()
                                "first-name", "middle-name", "last-name" ->
                                    if (path.contains("title-info")) authorParts.add(raw.trim())
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (skipUntilDepth < 0) when (name) {
                        "body" -> if (bodyDepth > 0) bodyDepth--
                        "binary" -> {
                            coverBase64?.let { b64 ->
                                coverBytes = runCatching {
                                    Base64.decode(b64.toString().filterNot { it.isWhitespace() }, Base64.DEFAULT)
                                }.getOrNull()
                            }
                            coverBase64 = null
                        }
                        "title" -> {
                            flushParagraph()
                            val t = titleBuf?.toString()?.trim().orEmpty()
                            if (bodyDepth > 0 && titleStart >= 0) {
                                marks.add((if (t.isBlank()) "Глава ${marks.size + 1}" else t) to titleStart)
                            }
                            titleBuf = null
                            titleStart = -1
                        }
                        in PARAGRAPH -> flushParagraph()
                    }
                    if (skipUntilDepth == path.size) skipUntilDepth = -1
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                }
            }
            event = parser.next()
        }

        // Текст до первого заголовка (предисловие, посвящение) - тоже глава,
        // иначе привязка потеряет её начало.
        if (marks.isEmpty() || marks.first().second > 0) {
            marks.add(0, "Начало" to 0)
        }
        val text = BookText.build(
            body = body,
            marks = marks,
            title = bookTitle,
            author = authorParts.joinToString(" ").trim(),
        )
        return ParsedBook(text, coverBytes)
    }
}
