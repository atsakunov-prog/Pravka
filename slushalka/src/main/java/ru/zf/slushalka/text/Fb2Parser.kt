package ru.zf.slushalka.text

import android.util.Base64
import android.util.Xml
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

/**
 * fb2 - обычный XML, и это лучший из форматов для нашей задачи: главы уже
 * размечены тегами, значит и привязка аудио к тексту получается по главам, а
 * не «в среднем по книге». Обложка и картинки (карты, планы, портреты) лежат
 * тут же, в `<binary>`, и достаются вместе с текстом.
 */
object Fb2Parser {

    // Теги, чей текст - это абзац книги. Всё остальное (описание, служебное)
    // в текст не попадает.
    private val PARAGRAPH = setOf("p", "v", "subtitle", "text-author")

    private const val MAX_PICTURES = 60
    private const val MAX_PICTURE_BYTES = 8 * 1024 * 1024

    fun parse(
        input: InputStream,
        onImage: (ref: String, bytes: ByteArray) -> Unit = { _, _ -> },
    ): ParsedBook {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)

        val body = StringBuilder()
        val marks = ArrayList<Pair<String, Int>>()
        val pictures = ArrayList<Picture>()
        val path = ArrayList<String>()

        var bodyDepth = 0          // >0 - мы внутри основного <body>
        var skipUntilDepth = -1    // пропускаем поддерево (сноски)
        var para: StringBuilder? = null
        var titleBuf: StringBuilder? = null
        var titleStart = -1

        var bookTitle = ""
        val authorParts = ArrayList<String>()
        var coverHref: String? = null
        var coverBytes: ByteArray? = null

        // Разбор <binary>: base64 приезжает кусками, и держать в памяти
        // хочется только те картинки, на которые в тексте есть ссылка.
        var binaryId: String? = null
        var binaryIsCover = false
        var binaryBuf: StringBuilder? = null

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
                            val wantedCover = coverHref?.removePrefix("#")
                            binaryIsCover = when {
                                wantedCover != null -> id.equals(wantedCover, true)
                                // Обложка не объявлена: годится первая картинка.
                                else -> type.startsWith("image/") && coverBytes == null
                            }
                            val referenced = pictures.any { it.ref.equals(id, true) }
                            binaryId = id
                            binaryBuf = if (binaryIsCover || referenced) StringBuilder() else null
                            if (binaryBuf == null) skipUntilDepth = path.size
                        }
                        "image" -> {
                            val href = parser.getAttributeValue(null, "l:href")
                                ?: parser.getAttributeValue(null, "href")
                                ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            when {
                                path.contains("coverpage") && coverHref == null -> coverHref = href
                                bodyDepth > 0 && href != null && pictures.size < MAX_PICTURES -> {
                                    flushParagraph()
                                    pictures.add(Picture(body.length, href.removePrefix("#")))
                                }
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
                        binaryBuf?.append(raw)
                        para?.append(raw.replace('\n', ' '))
                        if (para == null && binaryBuf == null && raw.isNotBlank()) {
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
                            val buf = binaryBuf
                            val id = binaryId
                            if (buf != null && id != null && buf.length < MAX_PICTURE_BYTES * 2) {
                                val bytes = runCatching {
                                    Base64.decode(
                                        buf.toString().filterNot { it.isWhitespace() },
                                        Base64.DEFAULT,
                                    )
                                }.getOrNull()
                                if (bytes != null && bytes.size in 1..MAX_PICTURE_BYTES) {
                                    if (binaryIsCover && coverBytes == null) coverBytes = bytes
                                    if (pictures.any { it.ref.equals(id, true) }) onImage(id, bytes)
                                }
                            }
                            binaryBuf = null
                            binaryId = null
                            binaryIsCover = false
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
            pictures = pictures,
        )
        return ParsedBook(text, coverBytes)
    }
}
