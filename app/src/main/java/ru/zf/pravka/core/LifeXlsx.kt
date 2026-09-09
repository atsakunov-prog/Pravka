package ru.zf.pravka.core

import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Вся жизнь одной книгой Excel: лист на базу Notion, колонка в колонку.
 *
 * Владелец (09.09): «мне надоел этот Notion. Пускай он дальше синхронизируется,
 * я согласен, но в Excel мне как-то удобнее» — и «там должна быть целиком вся
 * моя жизнь, поделена по вкладкам, отсортирована по времени начала: сначала
 * последний день и по убыванию». Поэтому книга — не свой формат, а зеркало
 * структуры из `NotionLifeSchema`: те же восемь баз листами в том же порядке,
 * те же колонки в том же порядке, те же значения, построенные теми же
 * построителями строк. Что видно в Notion, то и в файле; расходиться им
 * нечем.
 *
 * Типы переводятся один к одному: title и rich_text — текст, number — число,
 * select — его название, checkbox — да/нет, date — настоящая дата Excel
 * (со временем или без: «День» и «Дата» без времени приезжают датой суток),
 * url — текст ссылки. Пустое свойство — пустая ячейка, не ноль и не «null».
 *
 * Файл без Android: собирает листы из JSON-строк, писать zip умеет `Xlsx`.
 */
object LifeXlsx {

    class Table(val db: NotionLifeSchema.Db, val rows: List<JSONObject>)

    /** Колонки-даты, по которым лист сортируется от свежего к старому. */
    private val TIME_COLUMNS = listOf("Начало", "Дата", "День")

    fun sheets(tables: List<Table>): List<Xlsx.Sheet> = tables.map { sheet(it.db, it.rows) }

    fun sheet(db: NotionLifeSchema.Db, rows: List<JSONObject>): Xlsx.Sheet {
        val ordered = sort(db, rows)
        return Xlsx.Sheet(
            name = db.name,
            header = db.columns.map { it.name },
            rows = ordered.map { row -> db.columns.map { cell(it, row) } },
        )
    }

    /**
     * Свежее сверху: по первой из колонок «Начало», «Дата», «День», что есть
     * у базы. У справочника категорий времени нет — он идёт по «Порядку», как
     * в приложении.
     */
    fun sort(db: NotionLifeSchema.Db, rows: List<JSONObject>): List<JSONObject> {
        val timeCol = TIME_COLUMNS.firstOrNull { db.has(it) }
        if (timeCol != null) return rows.sortedByDescending { dateMs(it, timeCol) ?: Long.MIN_VALUE }
        if (db.has("Порядок")) return rows.sortedBy { number(it, "Порядок") ?: Double.MAX_VALUE }
        return rows
    }

    fun cell(column: NotionLifeSchema.Column, row: JSONObject): Xlsx.Cell {
        val prop = row.optJSONObject(column.name) ?: return Xlsx.Cell.Empty
        return when (column.type) {
            "title", "rich_text" -> text(prop.optJSONArray(column.type)).let { if (it.isEmpty()) Xlsx.Cell.Empty else Xlsx.Cell.Text(it) }
            "number" -> (prop.opt("number") as? Number)?.let { Xlsx.Cell.Num(it.toDouble()) } ?: Xlsx.Cell.Empty
            "select" -> prop.optJSONObject("select")?.optString("name").orEmpty()
                .let { if (it.isBlank()) Xlsx.Cell.Empty else Xlsx.Cell.Text(it) }
            "checkbox" -> Xlsx.Cell.Bool(prop.optBoolean("checkbox", false))
            "date" -> {
                val start = prop.optJSONObject("date")?.optString("start").orEmpty()
                when {
                    start.isBlank() -> Xlsx.Cell.Empty
                    start.length <= 10 -> Xlsx.Cell.Day(start)
                    else -> parseMs(start)?.let { Xlsx.Cell.DateTime(it) } ?: Xlsx.Cell.Text(start)
                }
            }
            "url" -> (prop.opt("url") as? String)?.takeIf { it.isNotBlank() }?.let { Xlsx.Cell.Text(it) } ?: Xlsx.Cell.Empty
            else -> Xlsx.Cell.Empty
        }
    }

    /** Момент строки в колонке-дате: дата со временем или полночь суток. */
    fun dateMs(row: JSONObject, column: String): Long? {
        val start = row.optJSONObject(column)?.optJSONObject("date")?.optString("start").orEmpty()
        return when {
            start.isBlank() -> null
            start.length <= 10 -> NotionLifeSchema.dayStartOf(start)
            else -> parseMs(start)
        }
    }

    private fun number(row: JSONObject, column: String): Double? =
        (row.optJSONObject(column)?.opt("number") as? Number)?.toDouble()

    private fun parseMs(iso: String): Long? =
        runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    private fun text(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONObject(i)?.optJSONObject("text")?.optString("content").orEmpty())
        }
        return sb.toString()
    }
}
