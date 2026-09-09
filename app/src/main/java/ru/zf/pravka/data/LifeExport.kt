package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.LifeXlsx
import ru.zf.pravka.core.Xlsx

/**
 * Единственная выгрузка приложения: вся жизнь одной книгой Excel.
 *
 * До 09.09 выгрузок было пять — CSV ленты, CSV еды, CSV всей жизни, сводка
 * дня и недели текстом, «запрос для чата» — и владелец не пользовался ни
 * одной: «уберём все эти экспорты csv и сводки, и сделаем один экспорт,
 * который будет экспортировать всё то, что в Notion». Файл собирается из тех
 * же строк, что уезжают в Notion (`LifeRows`), лист на базу (`LifeXlsx`), и
 * отдаётся системным «поделиться» — в Telegram себе, на диск, в Excel.
 */
class LifeExport(
    private val context: Context,
    private val rows: LifeRows,
) {
    companion object {
        const val FILE_NAME = "pravka-zhizn.xlsx"
        const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    /** Собирает книгу в кэш и возвращает интент «поделиться». Бросает, если не собралась. */
    suspend fun shareIntent(now: Long = System.currentTimeMillis()): Intent {
        val tables = rows.collect(now)
        return withContext(Dispatchers.IO) {
            val sheets = LifeXlsx.sheets(tables.map { t -> LifeXlsx.Table(t.db, t.rows.map { it.second }) })
            val out = File(context.cacheDir, FILE_NAME)
            // Через временный файл: полуписаная книга не должна уехать, если
            // запись оборвётся на середине.
            val tmp = File(context.cacheDir, "$FILE_NAME.tmp")
            tmp.outputStream().buffered().use { Xlsx.write(sheets, it) }
            if (!tmp.renameTo(out)) {
                out.delete()
                check(tmp.renameTo(out)) { "не удалось записать $FILE_NAME" }
            }
            shareFileIntent(context, out, MIME)
        }
    }
}
