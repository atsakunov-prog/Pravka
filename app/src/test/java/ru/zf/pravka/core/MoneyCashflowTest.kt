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
        // Трата ЗФ с личной карты при кнопке «Личное» — ВГО «за ЗФ со своих»: деньги ушли, но не на семью.
        assertEquals(-500_000L, row(rows, "За ЗФ со своих карт").values[1])
        assertEquals(31_900_000L, row(rows, "Чистый денежный поток").values[1])
        assertEquals(-3_800_000L, row(rows, "Мимо журнала, нетто").values[1])
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
        assertEquals(11, a.size)
        assertTrue(a.any { it.account == MoneyCashflow.NATASHA_DEBT && it.kop == 0L })
        // Снимок 70 743,72 плюс дневные операции после выписки — якорь на её конце, с секундами.
        val bp = a.single { it.account == "Т-Банк · Black Premium" }
        assertEquals(49_331_382L, bp.kop)
        assertEquals(MoneyStats.startOf(LocalDate.parse("2026-09-23")) + (11 * 3600 + 41 * 60 + 18) * 1000L, bp.ts)
    }

    @Test fun pushCarriesAvailable() {
        val p = (BankPush.parse("ВкусВилл", "Покупка на 1 781,84 ₽, счет карты *1519\nДоступно 13 630,02 ₽") as BankPush.Outcome.Money).p
        assertEquals(1_363_002L, p.balanceKop)
    }

    private fun asset(name: String) =
        (java.io.File("src/main/assets/$name").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/$name")).readText()

    @Test fun ownerCashFactsAndWallet() {
        val manual = MoneyCashflow.parseManual(asset("money_manual.txt"), "sasha")
        assertEquals(6, manual.size)
        assertEquals(listOf("inc_zf", "owed", "inc_zf", "owed", "loan", "gifts"), manual.map { it.category })
        assertTrue(manual.filter { it.category != "owed" }.all { it.account == MoneyEntry.CASH && it.categoryBy == MoneyEntry.CategoryBy.OWNER })
        assertTrue(manual.filter { it.category == "owed" }.all { it.account == MoneyCashflow.NATASHA_DEBT })
        // Номер постоянный: второе чтение даёт те же записи.
        assertEquals(manual.map { it.id }, MoneyCashflow.parseManual(asset("money_manual.txt"), "sasha").map { it.id })

        val deposit = e("dep", "2026-09-23", 480_000, "cash")
        val all = manual.filter { it.ts >= at("2026-09-01") } + deposit
        val rows = MoneyCashflow.build(all, listOf(YearMonth.of(2026, 9)), withZf = false)
        assertEquals(388_000_000L, row(rows, "Поступления").values[0])
        assertEquals(-20_000_000L, row(rows, "Займы возвращены").values[0])
        // Кошелёк: +3 880 000 от ЗФ, −400 000 папе, −480 000 внесено в банк.
        val wallet = MoneyCashflow.balances(all, emptyList(), at("2026-09-30"), at("2026-09-01")).first { it.name == MoneyCashflow.WALLET }
        assertNull(wallet.kop)
        assertEquals(300_000_000L, wallet.flowKop)
    }

    @Test fun papaTransfersAreLoan() {
        val rules = MoneyRules.parseText(asset("money_payees.txt")).rules
        val inPapa = MoneyEntry(id = "p", owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = at("2026-09-18"), rubKop = 10_000_000, what = "Сергей Ц.")
        val outSummer = inPapa.copy(id = "f", rubKop = -6_500_000)
        val r = MoneyMatch.run(listOf(inPapa, outSummer), rules, at("2026-09-24")).entries.associateBy { it.id }
        assertEquals("loan", r["p"]!!.category)
        assertEquals("summer", r["f"]!!.category)
    }

    @Test fun natashaShareCutsIncomeAndDebt() {
        val income = MoneyEntry(
            id = "zf", owner = "sasha", source = MoneyEntry.Source.MANUAL, ts = at("2026-09-22"), rubKop = 388_000_000,
            what = "ЗФ", account = MoneyEntry.CASH, category = "inc_zf", categoryBy = MoneyEntry.CategoryBy.OWNER,
        )
        val paid = e("nat", "2026-09-23", -400_000, "zf_share")
        val rows = MoneyCashflow.build(listOf(income, paid), listOf(YearMonth.of(2026, 9)), withZf = false)
        // Доля партнёра — минус в доходах, не трата семьи.
        assertEquals(348_000_000L, row(rows, "Поступления").values[0])
        assertEquals(0L, row(rows, "Выплаты").values[0])
        val owedAug = MoneyEntry(id = "oa", owner = "sasha", source = MoneyEntry.Source.MANUAL, ts = at("2026-08-20"), rubKop = -55_200_000, what = "доля", account = MoneyCashflow.NATASHA_DEBT, category = "owed")
        val paidAug = e("nat8", "2026-08-20", -552_000, "zf_share").copy(ts = at("2026-08-20", 21))
        val owedSep = owedAug.copy(id = "os", ts = at("2026-09-22"), rubKop = -112_500_000)
        val all = listOf(income, paid, owedAug, paidAug, owedSep)
        val debt = MoneyCashflow.Anchor(MoneyCashflow.NATASHA_DEBT, at("2026-01-01", 0), 0, "с нуля")
        // Август: начислено и выплачено — ноль; сентябрь: 1 125 000 − 400 000.
        assertEquals(0L, MoneyCashflow.balances(all, listOf(debt), at("2026-09-01", 0), at("2026-08-01")).first { it.name == MoneyCashflow.NATASHA_DEBT }.kop)
        // Начисление — не деньги: в ДДС его нет.
        assertEquals(0L, row(MoneyCashflow.build(all, listOf(YearMonth.of(2026, 9)), false), "Выплаты").values[0])
        val acc = MoneyCashflow.balances(all, listOf(debt), at("2026-09-24"), at("2026-09-01"))
            .first { it.name == MoneyCashflow.NATASHA_DEBT }
        assertEquals(-72_500_000L, acc.kop)
        val flows = MoneyCashflow.accountFlows(listOf(income, paid), listOf(debt), at("2026-09-01", 0), at("2026-10-01", 0), at("2026-09-24"))
        assertEquals(MoneyCashflow.WALLET, flows.first().name)
        assertEquals(388_000_000L, flows.first().inKop)
        val bp = flows.first { it.name == "Т-Банк · Black Premium" }
        assertEquals(-40_000_000L, bp.outKop)
        assertEquals("Доля Наташи (ЗФ)", bp.byCategory.single().first)
    }
}
