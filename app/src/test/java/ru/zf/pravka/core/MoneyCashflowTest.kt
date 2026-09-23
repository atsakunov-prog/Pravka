package ru.zf.pravka.core

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ДДС и баланс от якорей. Суммы и счета вымышленные.
class MoneyCashflowTest {

    private fun at(d: String, h: Int = 12) = MoneyStats.startOf(LocalDate.parse(d)) + h * 3_600_000L

    private fun e(id: String, d: String, rub: Long, cat: String, acc: String = "Black Premium *1519", src: MoneyEntry.Source = MoneyEntry.Source.TINKOFF) =
        MoneyEntry(id = id, owner = "sasha", source = src, ts = at(d), rubKop = rub * 100, what = id, category = cat, account = acc)

    private val entries = listOf(
        e("inc", "2026-09-05", 300_000, "inc_zf"),
        e("food", "2026-09-06", -20_000, "groceries"),
        e("club", "2026-09-07", -15_000, "clubs"),
        e("zf", "2026-09-08", -5_000, "zf"),
        e("loan", "2026-09-09", 100_000, "loan"),
        e("back", "2026-09-20", -40_000, "loan"),
        e("wife", "2026-09-10", -38_000, "spouse"),
        e("unk", "2026-09-11", -1_000, ""),
        e("aug", "2026-08-15", -10_000, "groceries"),
    )

    private fun row(rows: List<MoneyCashflow.Row>, title: String) = rows.first { it.title == title }

    @Test fun sectionsAndTotals() {
        val months = listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9))
        val rows = MoneyCashflow.build(entries, months, withZf = false)
        assertEquals(listOf(0L, 30_000_000L), row(rows, "Поступления").values)
        // Без «+ ЗФ» трата ЗФ в ДДС не идёт; без категории — идёт.
        assertEquals(listOf(-1_000_000L, -3_600_000L), row(rows, "Выплаты").values)
        assertEquals(26_400_000L, row(rows, "Операционный поток").values[1])
        assertEquals(6_000_000L, row(rows, "Финансовый поток").values[1])
        assertEquals(32_400_000L, row(rows, "Чистый денежный поток").values[1])
        assertEquals(-3_800_000L, row(rows, "Перемещения, нетто").values[1])
        val withZf = MoneyCashflow.build(entries, months, withZf = true)
        assertEquals(-4_100_000L, row(withZf, "Выплаты").values[1])
    }

    @Test fun balanceRollsFromAnchorBothWays() {
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", at("2026-09-10", 18), 5_000_000, "снимок")
        val now = at("2026-09-30")
        val acc = MoneyCashflow.balances(entries, listOf(anchor), now, at("2026-07-01")).first { it.name == "Т-Банк · Black Premium" }
        // После якоря: −1 000 (11.09) и −40 000 (20.09).
        assertEquals(5_000_000L - 100_000 - 4_000_000, acc.kop)
        // Назад к 1 сентября: минус всё, что случилось между ним и якорем.
        val sep1 = MoneyCashflow.balances(entries, listOf(anchor), at("2026-09-01", 0), at("2026-07-01")).first { it.name == "Т-Банк · Black Premium" }
        val between = 300_000L - 20_000 - 15_000 - 5_000 + 100_000 - 38_000
        assertEquals(5_000_000L - between * 100, sep1.kop)
    }

    @Test fun pushAnchorCoversItsStatementRow() {
        val push = e("push-x", "2026-09-10", -700, "sport", acc = "Т-Банк *1519", src = MoneyEntry.Source.PUSH).copy(replacedBy = "row")
        val row = e("row", "2026-09-10", -700, "sport").copy(ts = at("2026-09-10") + 30_000)
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", at("2026-09-10"), 1_000_000, "пуш", covers = setOf("push-x"))
        val acc = MoneyCashflow.balances(listOf(push, row), listOf(anchor), at("2026-09-11"), at("2026-09-01")).single()
        // Строка выписки на 30 секунд позже пуша — та же операция, уже в «Доступно».
        assertEquals(1_000_000L, acc.kop)
    }

    @Test fun unknownWithoutAnchorAndFileFormat() {
        val acc = MoneyCashflow.balances(entries, emptyList(), at("2026-09-30"), at("2026-07-01")).single()
        assertNull(acc.kop)
        val parsed = MoneyCashflow.parseAnchors(
            "# комментарий\n23.09.2026 19:10 | Т-Банк · Платинум | -733000 | кредитка\nкривая строка\n"
        )
        assertEquals(1, parsed.size)
        assertEquals(-73_300_000L, parsed[0].kop)
        assertEquals("Т-Банк · Платинум", parsed[0].account)
    }

    @Test fun factoryBalancesFileParses() {
        val f = java.io.File("src/main/assets/money_balances.txt").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/money_balances.txt")
        val a = MoneyCashflow.parseAnchors(f.readText())
        assertEquals(8, a.size)
        // Снимок 70 743,72 плюс дневные операции после выписки — якорь на её конце, с секундами.
        val bp = a.single { it.account == "Т-Банк · Black Premium" }
        assertEquals(49_331_382L, bp.kop)
        assertEquals(MoneyStats.startOf(LocalDate.parse("2026-09-23")) + (11 * 3600 + 41 * 60 + 18) * 1000L, bp.ts)
    }

    @Test fun pushCarriesAvailable() {
        val p = (BankPush.parse("ВкусВилл", "Покупка на 1 781,84 ₽, счет карты *1519\nДоступно 13 630,02 ₽") as BankPush.Outcome.Money).p
        assertEquals(1_363_002L, p.balanceKop)
    }
}
