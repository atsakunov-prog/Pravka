package ru.zf.slushalka.catalog

import android.util.Xml
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import ru.zf.slushalka.text.TextExtract

/**
 * OPDS 1.x - обычная Atom-лента. Каталог состоит из двух видов записей:
 * **разделы** (ссылка ведёт на другую ленту) и **книги** (ссылки ведут на
 * файлы). Флибуста отдаёт ровно это, без выдумок, поэтому и разбор простой:
 * XmlPullParser с выключенными пространствами имён - как у fb2.
 */
data class OpdsLink(val href: String, val rel: String, val type: String, val title: String) {
    val isFeed get() = type.contains("atom+xml")
    val isAcquisition get() = rel.startsWith(REL_ACQUISITION)
    val isImage get() = rel == REL_IMAGE || rel == REL_THUMB || rel == "x-stanza-cover-image"

    /** «fb2», «epub», «mobi» - из типа `application/fb2+zip`, `application/epub+zip`. */
    val format: String
        get() = type.substringAfter('/', "").substringBefore('+').lowercase()
            .replace("x-mobipocket-ebook", "mobi")

    companion object {
        const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
        const val REL_IMAGE = "http://opds-spec.org/image"
        const val REL_THUMB = "http://opds-spec.org/thumbnail"
    }
}

data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    /** Аннотация: HTML из `<content>` уже вычищен до обычного текста. */
    val summary: String,
    /** «Формат: fb2», «Размер: 408 Kb», «Серия: …» - Флибуста дописывает их в хвост аннотации. */
    val facts: Map<String, String>,
    val language: String,
    val issued: String,
    val categories: List<String>,
    val links: List<OpdsLink>,
) {
    val acquisitions: List<OpdsLink> get() = links.filter { it.isAcquisition }

    /**
     * Книга - это запись с файлом. Ссылка «читать на сайте» (`text/html`) тоже
     * помечена как acquisition - у «Об авторе», например, - но книгой её не делает.
     */
    val isBook get() = acquisitions.any { !it.type.startsWith("text/") }

    /**
     * Свой ключ записи. У Флибусты разные издания одной книги носят один и тот
     * же `<id>` (md5 текста), поэтому различать их приходится по номеру файла из
     * ссылки на скачивание: `/b/228606/fb2` → 228606.
     */
    val key: String
        get() {
            val href = acquisitions.firstOrNull()?.href.orEmpty()
            val m = BOOK_NUMBER.find(href)
            return if (m != null) "b" + m.groupValues[1] else id.ifBlank { title }
        }

    /** Куда ведёт раздел. У книги такой ссылки нет. */
    val feedLink: OpdsLink?
        get() = links.firstOrNull {
            it.isFeed && it.rel != "related" && it.rel != "alternate" && !it.rel.endsWith("/facet") &&
                !it.rel.endsWith("acquisition")
        }

    val cover: String? get() = links.firstOrNull { it.isImage }?.href

    /** «Все книги автора», «Все книги серии» - ссылки, по которым можно пойти дальше. */
    val related: List<OpdsLink> get() = links.filter { it.isFeed && (it.rel == "related" || it.rel.endsWith("/facet")) }

    fun acquisition(format: String): OpdsLink? = acquisitions.firstOrNull { it.format == format }

    val authorLine get() = authors.joinToString(", ")
    val series get() = facts["Серия"].orEmpty()
    val size get() = facts["Размер"].orEmpty()

    private companion object {
        val BOOK_NUMBER = Regex("/b/(\\d+)(?:/|$)")
    }
}

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    /** Следующая страница, если каталог режет список. */
    val next: String?,
)

object Opds {

    /** Строки вида «Формат: fb2», которые Флибуста дописывает в аннотацию. */
    private val FACT = Regex("^(Формат|Язык|Размер|Скачиваний|Год издания|Серия|Перевод|Издание)\\s*:\\s*(.+)$")

    fun parse(input: InputStream): OpdsFeed {
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)

        var feedTitle = ""
        var next: String? = null
        val entries = ArrayList<OpdsEntry>()

        // Текущая запись - накопитель; null, пока идут заголовки самой ленты.
        var inEntry = false
        var inAuthor = false
        var id = ""
        var title = ""
        var content = ""
        var language = ""
        var issued = ""
        val authors = ArrayList<String>()
        val categories = ArrayList<String>()
        val links = ArrayList<OpdsLink>()

        fun reset() {
            id = ""; title = ""; content = ""; language = ""; issued = ""
            authors.clear(); categories.clear(); links.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "entry" -> { inEntry = true; reset() }
                    "author" -> inAuthor = inEntry
                    "name" -> if (inAuthor) parser.nextText().trim().takeIf { it.isNotBlank() }?.let(authors::add)
                    "title" -> {
                        val t = parser.nextText().trim()
                        if (inEntry) title = t else if (feedTitle.isBlank()) feedTitle = t
                    }
                    "id" -> if (inEntry) id = parser.nextText().trim()
                    "content", "summary" -> if (inEntry && content.isBlank()) content = parser.nextText()
                    "dc:language" -> if (inEntry) language = parser.nextText().trim()
                    "dc:issued" -> if (inEntry) issued = parser.nextText().trim()
                    "category" -> if (inEntry) {
                        (parser.getAttributeValue(null, "label") ?: parser.getAttributeValue(null, "term"))
                            ?.trim()?.takeIf { it.isNotBlank() }?.let(categories::add)
                    }
                    "link" -> {
                        val link = OpdsLink(
                            href = parser.getAttributeValue(null, "href").orEmpty(),
                            rel = parser.getAttributeValue(null, "rel").orEmpty(),
                            type = parser.getAttributeValue(null, "type").orEmpty(),
                            title = parser.getAttributeValue(null, "title").orEmpty(),
                        )
                        if (inEntry) links.add(link)
                        else if (link.rel == "next" && link.href.isNotBlank()) next = link.href
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        val (summary, facts) = splitContent(content)
                        entries.add(
                            OpdsEntry(
                                id = id.ifBlank { title },
                                title = title,
                                authors = authors.toList(),
                                summary = summary,
                                facts = facts,
                                language = language,
                                issued = issued,
                                categories = categories.toList(),
                                links = links.toList(),
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }
        return OpdsFeed(feedTitle, entries, next)
    }

    /**
     * Аннотация приезжает HTML-ом с фактами в хвосте: «Формат: fb2», «Размер:
     * 408 Kb», «Серия: …». Факты уходят в свою таблицу, а в аннотации остаётся
     * только текст про книгу.
     */
    private fun splitContent(html: String): Pair<String, Map<String, String>> {
        if (html.isBlank()) return "" to emptyMap()
        val facts = LinkedHashMap<String, String>()
        val text = StringBuilder()
        for (raw in TextExtract.stripHtml(html).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { text.append('\n'); continue }
            val m = FACT.find(line)
            if (m != null) facts[m.groupValues[1]] = m.groupValues[2].trim()
            else text.append(line).append('\n')
        }
        return text.toString().replace(Regex("\n{3,}"), "\n\n").trim() to facts
    }
}
