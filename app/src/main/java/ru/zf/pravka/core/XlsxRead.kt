package ru.zf.pravka.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// Чтение первого листа .xlsx — ровно столько, сколько нужно выписке МКБ
// (у банка нет CSV, только xlsx и pdf — владелец, 23.09.2026). Без Apache POI:
// тащить в APK десять мегабайт ради таблицы из девяти колонок незачем, а
// банковская выгрузка — машинная, ровная: общие строки (`t="s"`), строки на
// месте (`inlineStr`) и числа. Формулы, стили и объединения не нужны.
object XlsxRead {

    /** Строки первого листа; пустые ячейки — пустые строки, колонки по буквам адреса. */
    fun firstSheet(bytes: ByteArray): List<List<String>> {
        var shared = ""
        var sheet = ""
        var firstSheetName = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "xl/sharedStrings.xml" -> shared = String(zip.readBytes(), Charsets.UTF_8)
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                        // Первый по номеру лист: sheet1.xml раньше sheet2.xml.
                        if (firstSheetName.isEmpty() || name < firstSheetName) {
                            firstSheetName = name
                            sheet = String(zip.readBytes(), Charsets.UTF_8)
                        }
                    }
                }
            }
        }
        require(sheet.isNotEmpty()) { "В файле нет листа — это точно .xlsx?" }
        val strings = parseShared(shared)
        val out = mutableListOf<List<String>>()
        for (row in ROW.findAll(sheet)) {
            val cells = sortedMapOf<Int, String>()
            for (c in CELL.findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val col = REF.find(attrs)?.groupValues?.get(1)?.let(::colIndex) ?: cells.size
                val type = TYPE.find(attrs)?.groupValues?.get(1).orEmpty()
                val value = when (type) {
                    "s" -> V.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                    "inlineStr" -> T.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                    else -> V.find(body)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                }
                cells[col] = value
            }
            if (cells.isEmpty()) { out.add(emptyList()); continue }
            val width = cells.lastKey() + 1
            out.add(List(width) { cells[it].orEmpty() })
        }
        return out
    }

    private val ROW = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex("\\br=\"([A-Z]+)\\d+\"")
    private val TYPE = Regex("\\bt=\"([^\"]+)\"")
    private val V = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val SI = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)

    /** Общая строка бывает из нескольких кусков (`<r><t>…</t></r>`) — склеиваем. */
    private fun parseShared(xml: String): List<String> =
        SI.findAll(xml).map { si -> T.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()

    fun colIndex(letters: String): Int = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")

    /** Похоже на xlsx (zip): первые байты «PK». */
    fun isZip(bytes: ByteArray): Boolean = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}
