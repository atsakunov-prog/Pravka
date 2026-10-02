package ru.zf.slushalka.text

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Серия книги из файла текста - для полки, без разбора всей книги.
 *
 * Полке серия нужна у каждой книги сразу, а разбирать ради неё сотню романов
 * целиком - минуты. Поэтому читается только голова файла: в fb2 серия лежит
 * в `<title-info>` тегом `<sequence name=… number=…>`, а описание идёт до
 * тела книги; в epub - в OPF, метками calibre (`calibre:series`,
 * `calibre:series_index`) или EPUB3 (`belongs-to-collection` и
 * `group-position`). Серия издательства (`<publish-info>`, «Звёзды мировой
 * фантастики») нарочно не берётся: это не та серия, по которой читают.
 */
object BookMeta {

    data class Series(val name: String, val number: String?)

    /** Серия из файла текста. null - серии нет или файл не открылся. */
    fun series(context: Context, uri: Uri, fileName: String): Series? = runCatching {
        val lower = fileName.lowercase()
        context.contentResolver.openInputStream(uri)?.use { input ->
            when {
                lower.endsWith(".fb2") -> fb2Series(decodeHead(readHead(input)))
                // .epub, .fb2.zip, .zip: внутри fb2 или OPF, что попадётся первым.
                else -> zipSeries(input)
            }
        }
    }.getOrNull()

    /** Сначала голова: описание fb2 и OPF умещаются в четверть мегабайта с запасом. */
    private fun readHead(input: InputStream, max: Int = HEAD_BYTES): ByteArray {
        val out = ByteArray(max)
        var got = 0
        while (got < max) {
            val n = input.read(out, got, max - got)
            if (n < 0) break
            got += n
        }
        return if (got == max) out else out.copyOf(got)
    }

    private fun zipSeries(input: InputStream): Series? {
        val zip = ZipInputStream(input)
        var seen = 0
        while (seen++ < MAX_ENTRIES) {
            val e = zip.nextEntry ?: return null
            val name = e.name.lowercase()
            when {
                name.endsWith(".fb2") -> return fb2Series(decodeHead(readHead(zip)))
                name.endsWith(".opf") -> return opfSeries(decodeHead(readHead(zip)))
            }
        }
        return null
    }

    /**
     * Байты головы - в текст. fb2 бывает в windows-1251: кодировка - в
     * прологе XML; BOM главнее пролога. Последний знак может оказаться
     * обрезанным посередине - для поиска тега это неважно.
     */
    fun decodeHead(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val prolog = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']").find(prolog)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** Серия из описания fb2: первый `<sequence>` в `<title-info>`. */
    fun fb2Series(text: String): Series? {
        val start = Regex("<(?:\\w+:)?title-info\\b[^>]*>").find(text) ?: return null
        // Пустой <title-info/> - описания нет, и серии в нём тоже.
        if (start.value.endsWith("/>")) return null
        // Конец - закрывающий тег, а если голова обрезана раньше, то хотя бы
        // начало издательских сведений: их серия - не серия книги.
        val end = listOfNotNull(
            Regex("</(?:\\w+:)?title-info>").find(text, start.range.last)?.range?.first,
            Regex("<(?:\\w+:)?(?:publish-info|src-title-info|document-info)\\b").find(text, start.range.last)?.range?.first,
        ).minOrNull() ?: text.length
        val info = text.substring(start.range.last + 1, end)
        val tag = Regex("<(?:\\w+:)?sequence\\b([^>]*)>").find(info) ?: return null
        val attrs = tag.groupValues[1]
        val name = attr(attrs, "name")?.let(TextExtract::decodeEntities)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return Series(name, number(attr(attrs, "number")))
    }

    /** Серия из OPF epub: сперва calibre (её ставят почти все), потом EPUB3. */
    fun opfSeries(text: String): Series? {
        val metas = Regex("<(?:\\w+:)?meta\\b([^>]*?)\\s*(?:/>|>([^<]*)</(?:\\w+:)?meta>)")
            .findAll(text)
            .map { m -> m.groupValues[1] to TextExtract.decodeEntities(m.groupValues[2]).trim() }
            .toList()
        fun byName(n: String) = metas.firstOrNull { attr(it.first, "name") == n }?.let { attr(it.first, "content") }
        byName("calibre:series")?.let(TextExtract::decodeEntities)?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            return Series(name, number(byName("calibre:series_index")))
        }
        val coll = metas.firstOrNull { attr(it.first, "property") == "belongs-to-collection" && it.second.isNotBlank() }
            ?: return null
        val id = attr(coll.first, "id")
        val position = id?.let { i ->
            metas.firstOrNull { attr(it.first, "refines") == "#$i" && attr(it.first, "property") == "group-position" }?.second
        }
        return Series(coll.second, number(position))
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1").find(attrs)?.groupValues?.get(2)

    /** «2.0» у calibre - это «2»; ноль и пустое - номера нет. */
    private fun number(raw: String?): String? {
        val t = raw?.trim()?.replace(',', '.')?.takeIf { it.isNotBlank() } ?: return null
        val d = t.toDoubleOrNull() ?: return t
        if (d <= 0.0) return null
        return if (d == Math.floor(d)) d.toLong().toString() else t
    }

    /** Ключ серии для сравнения: регистр и пробелы по краям не различают серии. */
    fun key(name: String): String = name.trim().lowercase()

    /** Порядок внутри серии: по номеру, книги без номера - в конце. */
    fun order(number: String?): Double = number?.replace(',', '.')?.toDoubleOrNull() ?: Double.MAX_VALUE

    private const val HEAD_BYTES = 256 * 1024
    private const val MAX_ENTRIES = 64
}
