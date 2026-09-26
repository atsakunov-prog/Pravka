package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.BankStatements
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.Xlsx

/**
 * Книга «Деньги» (.xlsx) — отдельная от «Всей жизни» (владелец, 23.09.2026:
 * «выгрузка должна быть в Google и в книге, „Вся жизнь“ пока не нужна»).
 * Три листа: журнал (строка на трату, свежее сверху), месяцы × категории
 * (семья и ЗФ отдельными колонками полки) и справочник получателей.
 */
class MoneyExport(private val context: Context, private val store: MoneyStore) {

    companion object {
        const val FILE_NAME = "pravka-dengi.xlsx"
        const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        fun sheets(state: MoneyStore.State): List<Xlsx.Sheet> {
            val live = state.entries.filter { it.live() }.sortedByDescending { it.ts }
            fun t(s: String) = if (s.isBlank()) Xlsx.Cell.Empty else Xlsx.Cell.Text(s)
            fun rub(kop: Long) = Xlsx.Cell.Num(kop / 100.0)
            val journal = Xlsx.Sheet(
                name = "Журнал",
                header = listOf(
                    "Когда", "Чей", "Откуда", "Что", "Сумма, ₽", "Валюта", "В валюте",
                    "Категория", "Группа", "Для кого", "Полка", "Счёт", "Сообщение", "Вопрос",
                ),
                rows = live.map { e ->
                    val cat = MoneyCategories.of(e.category)
                    listOf(
                        if (e.timeKnown) Xlsx.Cell.DateTime(e.ts)
                        else Xlsx.Cell.Day(Instant.ofEpochMilli(e.ts).atZone(BankStatements.MSK).toLocalDate().toString()),
                        t(MoneyCategories.whoTitle(e.owner)),
                        t(e.source.title),
                        t(e.what),
                        rub(e.rubKop),
                        t(e.currency),
                        if (e.currency == "RUB") Xlsx.Cell.Empty else Xlsx.Cell.Num(e.origMinor / 100.0),
                        t(cat?.title ?: "без категории"),
                        t(cat?.group.orEmpty()),
                        t(if (e.who.isBlank()) "" else MoneyCategories.whoTitle(e.who)),
                        t(when (cat?.shelf) {
                            MoneyCategories.Shelf.ZF -> "ЗФ"
                            MoneyCategories.Shelf.SERVICE -> "не трата"
                            else -> "семья"
                        }),
                        t(if (e.account == MoneyEntry.CASH) "наличные" else e.account),
                        t(e.note),
                        t(e.question),
                    )
                },
            )
            // Месяцы × категории: только то, что считается (семья и ЗФ), без
            // движения между своими.
            val counted = live.filter { MoneyCategories.shelf(it.category) != MoneyCategories.Shelf.SERVICE }
            val months = counted.map { Instant.ofEpochMilli(it.ts).atZone(BankStatements.MSK).toLocalDate().toString().take(7) }
                .distinct().sortedDescending()
            val cats = MoneyCategories.ALL.filter { it.shelf != MoneyCategories.Shelf.SERVICE }
            val byMonth = counted.groupBy { Instant.ofEpochMilli(it.ts).atZone(BankStatements.MSK).toLocalDate().toString().take(7) }
            val pivot = Xlsx.Sheet(
                name = "По месяцам",
                header = listOf("Категория", "Группа", "Полка") + months + listOf("Без категории"),
                rows = cats.map { c ->
                    listOf(t(c.title), t(c.group), t(if (c.shelf == MoneyCategories.Shelf.ZF) "ЗФ" else "семья")) +
                        months.map { m -> rub(byMonth[m].orEmpty().filter { it.category == c.key }.sumOf { it.rubKop }) } +
                        listOf(Xlsx.Cell.Empty)
                } + listOf(
                    listOf(t("Без категории"), Xlsx.Cell.Empty, Xlsx.Cell.Empty) +
                        months.map { m -> rub(byMonth[m].orEmpty().filter { it.category.isBlank() }.sumOf { it.rubKop }) } +
                        listOf(Xlsx.Cell.Empty)
                ),
            )
            val payees = Xlsx.Sheet(
                name = "Справочник",
                header = listOf("Шаблон", "Категория", "Для кого", "Только", "Чьи счета", "Заметка"),
                rows = state.rules.map { r ->
                    listOf(
                        t(r.pattern), t(MoneyCategories.title(r.category)), t(MoneyCategories.whoTitle(r.who)),
                        t(when (r.sign) { -1 -> "списания"; 1 -> "поступления"; else -> "" }),
                        t(MoneyCategories.whoTitle(r.owner)), t(r.comment),
                    )
                },
            )
            return listOf(journal, pivot, payees)
        }
    }

    /** Собирает книгу в кэш и возвращает интент «поделиться». Бросает, если не собралась. */
    suspend fun shareIntent(): Intent {
        val state = store.load()
        return withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, FILE_NAME)
            val tmp = File(context.cacheDir, "$FILE_NAME.tmp")
            tmp.outputStream().buffered().use { Xlsx.write(sheets(state), it) }
            if (!tmp.renameTo(out)) {
                out.delete()
                check(tmp.renameTo(out)) { "не удалось записать $FILE_NAME" }
            }
            shareFileIntent(context, out, MIME)
        }
    }
}
