package ru.zf.slushalka.text

import java.io.File
import java.util.zip.ZipFile

/**
 * epub - zip с XHTML внутри. Порядок чтения задаёт spine в OPF, он же даёт
 * порядок глав: одна запись spine = одна глава.
 */
object EpubParser {

    private class Item(val id: String, val href: String, val mime: String, val props: String)

    fun parse(file: File): ParsedBook {
        ZipFile(file).use { zip ->
            val container = zip.textOf("META-INF/container.xml").orEmpty()
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf") }?.name
                ?: return ParsedBook(BookText("", emptyList()), null)
            val opf = zip.textOf(opfPath).orEmpty()
            val base = opfPath.substringBeforeLast('/', "")

            val items = Regex("(?is)<item\\s+([^>]*?)/?>").findAll(opf).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val id = attr(attrs, "id") ?: return@mapNotNull null
                val href = attr(attrs, "href") ?: return@mapNotNull null
                Item(id, href, attr(attrs, "media-type").orEmpty(), attr(attrs, "properties").orEmpty())
            }.associateBy { it.id }

            val spine = Regex("(?is)<itemref\\s+([^>]*?)/?>").findAll(opf)
                .mapNotNull { attr(it.groupValues[1], "idref") }
                .toList()

            val body = StringBuilder()
            val marks = ArrayList<Pair<String, Int>>()
            for (idref in spine) {
                val item = items[idref] ?: continue
                if (item.props.contains("nav")) continue          // оглавление - не глава
                if (item.mime.isNotBlank() && !item.mime.contains("html")) continue
                val html = zip.textOf(resolve(base, item.href)) ?: continue
                val text = TextExtract.stripHtml(html)
                if (text.isBlank()) continue
                val title = TextExtract.firstHeading(html)
                    ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                    ?: "Глава ${marks.size + 1}"
                marks.add(title.trim() to body.length)
                body.append(text).append("\n\n")
            }

            val coverHref = items.values.firstOrNull { it.props.contains("cover-image") }?.href
                ?: Regex("(?is)<meta\\s+[^>]*name=\"cover\"[^>]*>").find(opf)
                    ?.let { attr(it.value, "content") }
                    ?.let { items[it]?.href }
            val cover = coverHref?.let { zip.bytesOf(resolve(base, it)) }

            return ParsedBook(
                BookText.build(
                    body = body,
                    marks = marks,
                    title = tag(opf, "dc:title").orEmpty(),
                    author = tag(opf, "dc:creator").orEmpty(),
                ),
                cover,
            )
        }
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            ?: Regex("$name\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)

    private fun tag(xml: String, name: String): String? =
        Regex("(?is)<$name[^>]*>(.*?)</$name>").find(xml)
            ?.groupValues?.get(1)?.let { TextExtract.stripHtml(it) }?.trim()?.takeIf { it.isNotBlank() }

    /** Путь внутри архива относительно папки OPF, с «..» и %20. */
    private fun resolve(base: String, href: String): String {
        val clean = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        if (base.isNotBlank()) parts.addAll(base.split('/'))
        for (p in clean.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(p)
        }
        return parts.joinToString("/")
    }

    private fun ZipFile.entryOf(path: String) =
        getEntry(path) ?: entries().asSequence().firstOrNull { it.name.equals(path, true) }

    private fun ZipFile.textOf(path: String): String? =
        entryOf(path)?.let { e -> getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun ZipFile.bytesOf(path: String): ByteArray? =
        entryOf(path)?.let { e -> getInputStream(e).use { it.readBytes() } }
}
