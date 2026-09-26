package ru.zf.pravka.core

import java.io.OutputStream
import java.time.LocalDate
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Книга Excel (.xlsx) из ничего: без Apache POI и вообще без зависимостей.
 *
 * Зачем свой писатель. Владелец: «мне надоел этот Notion… в Excel мне как-то
 * удобнее», и выгрузка нужна одной книгой с листами по режимам — CSV этого не
 * умеет, у него один лист. POI тянет в APK мегабайты и на Android ставится
 * через боль, а формат xlsx — это zip с полудюжиной XML-файлов, и ровно
 * столько мы и пишем. Строки — inline (`t="inlineStr"`), без таблицы общих
 * строк: файл чуть больше, зато писатель на сотню строк и без состояния.
 *
 * Даты — настоящие даты Excel (число дней от 30.12.1899 плюс доля суток),
 * а не текст: по ним сортируют, фильтруют и считают разности. Часовой пояс
 * у Excel один — тот, в котором открыли, — поэтому пишется местное время
 * телефона, как на часах владельца.
 *
 * Порядок элементов в XML листа (sheetViews → cols → sheetData → autoFilter)
 * обязателен: Excel на перепутанный порядок отвечает «файл повреждён», а
 * LibreOffice молча читает — проверять надо Excel'ем или строгим читателем.
 *
 * Файл без Android: его проверяет JVM-тест.
 */
object Xlsx {

    /** Ячейка листа. Пустая ячейка не пишется вовсе. */
    sealed class Cell {
        data class Text(val s: String) : Cell()
        data class Num(val v: Double) : Cell()
        data class Bool(val b: Boolean) : Cell()
        /** Момент времени; в книгу ложится местным временем зоны писателя. */
        data class DateTime(val ms: Long) : Cell()
        /** Сутки без времени, `yyyy-MM-dd`. */
        data class Day(val date: String) : Cell()
        object Empty : Cell()
    }

    class Sheet(
        val name: String,
        val header: List<String>,
        val rows: List<List<Cell>>,
    )

    // Стили из styles.xml: индексы cellXfs.
    private const val S_HEADER = 1
    private const val S_DATETIME = 2
    private const val S_DAY = 3

    /** Дни от 30.12.1899 до 01.01.1970 — эпоха Excel против эпохи Unix. */
    private const val EPOCH_DAYS = 25569.0
    private const val DAY_MS = 86_400_000.0

    /** Excel не даёт листу имени длиннее 31 знака и с этими символами. */
    fun sheetName(raw: String): String =
        raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifBlank { "Лист" }.take(31)

    fun write(sheets: List<Sheet>, out: OutputStream, zone: TimeZone = TimeZone.getDefault()) {
        val names = LinkedHashMap<String, Int>()
        val unique = sheets.map { s ->
            var n = sheetName(s.name)
            var i = 2
            while (names.containsKey(n)) n = sheetName(s.name).take(28) + " " + (i++)
            names[n] = 1
            n
        }
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes(sheets.size))
            put(zip, "_rels/.rels", ROOT_RELS)
            put(zip, "xl/workbook.xml", workbook(unique))
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put(zip, "xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s ->
                put(zip, "xl/worksheets/sheet${i + 1}.xml", worksheet(s, selected = i == 0, zone))
            }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ---- Части книги ----

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(XML_HEAD)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbook(names: List<String>): String = buildString {
        append(XML_HEAD)
        append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>")
        names.forEachIndexed { i, n ->
            append("<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append(XML_HEAD)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            append("<Relationship Id=\"rId$i\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    // Два первых fill обязаны быть none и gray125 — Excel так устроен.
    private val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"$NS_MAIN\">" +
        "<numFmts count=\"2\">" +
        "<numFmt numFmtId=\"164\" formatCode=\"dd.mm.yyyy hh:mm\"/>" +
        "<numFmt numFmtId=\"165\" formatCode=\"dd.mm.yyyy\"/>" +
        "</numFmts>" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"4\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    private fun worksheet(sheet: Sheet, selected: Boolean, zone: TimeZone): String = buildString {
        val cols = sheet.header.size
        append(XML_HEAD)
        append("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">")
        // Шапка закреплена: лист листают вниз, а названия колонок должны
        // оставаться на глазах.
        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (selected) append(" tabSelected=\"1\"")
        append("><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        append("</sheetView></sheetViews>")
        if (cols > 0) {
            append("<cols>")
            for (c in 0 until cols) {
                append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${width(sheet, c)}\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        append("<row r=\"1\">")
        sheet.header.forEachIndexed { c, h ->
            append("<c r=\"${ref(c, 1)}\" s=\"$S_HEADER\" t=\"inlineStr\"><is><t>${esc(h)}</t></is></c>")
        }
        append("</row>")
        sheet.rows.forEachIndexed { i, row ->
            val r = i + 2
            append("<row r=\"$r\">")
            row.forEachIndexed { c, cell -> appendCell(this, ref(c, r), cell, zone) }
            append("</row>")
        }
        append("</sheetData>")
        if (cols > 0) {
            append("<autoFilter ref=\"A1:${col(cols - 1)}${sheet.rows.size + 1}\"/>")
        }
        append("</worksheet>")
    }

    private fun appendCell(sb: StringBuilder, ref: String, cell: Cell, zone: TimeZone) {
        when (cell) {
            is Cell.Empty -> Unit
            is Cell.Text -> {
                val t = clean(cell.s)
                if (t.isEmpty()) return
                val preserve = t.first().isWhitespace() || t.last().isWhitespace() || t.contains('\n')
                sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t")
                if (preserve) sb.append(" xml:space=\"preserve\"")
                sb.append(">").append(esc(t)).append("</t></is></c>")
            }
            is Cell.Num -> {
                if (cell.v.isNaN() || cell.v.isInfinite()) return
                sb.append("<c r=\"$ref\"><v>").append(num(cell.v)).append("</v></c>")
            }
            is Cell.Bool -> sb.append("<c r=\"$ref\" t=\"b\"><v>").append(if (cell.b) 1 else 0).append("</v></c>")
            is Cell.DateTime -> sb.append("<c r=\"$ref\" s=\"$S_DATETIME\"><v>")
                .append(num(serial(cell.ms, zone))).append("</v></c>")
            is Cell.Day -> {
                val d = daySerial(cell.date) ?: return
                sb.append("<c r=\"$ref\" s=\"$S_DAY\"><v>").append(num(d)).append("</v></c>")
            }
        }
    }

    /** Момент времени → число Excel в местном времени [zone]. */
    fun serial(ms: Long, zone: TimeZone): Double = (ms + zone.getOffset(ms)) / DAY_MS + EPOCH_DAYS

    /** «2026-09-07» → число Excel; мусор вместо даты — null, ячейка не пишется. */
    fun daySerial(date: String): Double? = runCatching {
        LocalDate.parse(date.take(10)).toEpochDay() + EPOCH_DAYS
    }.getOrNull()

    private fun num(v: Double): String =
        if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** Ширина колонки: по самому длинному значению, но в разумных пределах. */
    private fun width(sheet: Sheet, c: Int): Int {
        var longest = sheet.header[c].length
        for (row in sheet.rows) {
            val cell = row.getOrNull(c) ?: continue
            val len = when (cell) {
                is Cell.Text -> cell.s.lineSequence().maxOfOrNull { it.length } ?: 0
                is Cell.DateTime -> 16
                is Cell.Day -> 10
                is Cell.Num -> 8
                is Cell.Bool -> 6
                is Cell.Empty -> 0
            }
            if (len > longest) longest = len
        }
        return (longest + 2).coerceIn(8, 60)
    }

    /** A, B, …, Z, AA, AB… */
    fun col(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun ref(c: Int, r: Int): String = col(c) + r

    /**
     * XML 1.0 запрещает управляющие символы, кроме табуляции и переводов
     * строки; Excel ограничивает ячейку 32767 знаками.
     */
    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ') sb.append(ch)
        }
        return if (sb.length > 32767) sb.substring(0, 32767) else sb.toString()
    }

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
