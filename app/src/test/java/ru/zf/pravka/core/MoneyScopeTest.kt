package ru.zf.pravka.core

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// «Личное · ЗФ»: сторона (чьи деньги) и назначение (на что), ВГО между ними и
// общая картина без ВГО. Владелец, 23.09.2026: «я с бизнес-карты тоже трачу
// личное регулярно». Суммы и счета вымышленные.
class MoneyScopeTest {

    private fun at(d: String) = MoneyStats.startOf(LocalDate.parse(d)) + 12 * 3_600_000L
    private val biz = "Т-Банк · Счет для бизнеса"

    private fun e(id: String, rub: Long, cat: String, acc: String) = MoneyEntry(
        id = id, owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = at("2026-09-10"), rubKop = rub * 100,
        what = id, category = cat, account = acc,
    )

    private val entries = listOf(
        e("coffee_biz", -1_000, "cafe", "Счет для бизнеса *7008"),       // личное с бизнес-карты
        e("lawyer_own", -30_000, "zf", "Black Premium *1519"),           // ЗФ с личной карты
        e("client", 500_000, "zf_revenue", "Счет для бизнеса *7008"),    // выручка ЗФ
        e("soft_biz", -5_000, "subs_work", "Счет для бизнеса *7008"),    // ЗФ со своего счёта
        e("food", -20_000, "groceries", "Black Premium *1519"),          // личное с личной
        e("payout_zf", -200_000, "zf_owner", "Счет для бизнеса *7008"),  // ЗФ → владельцу
        e("payout_me", 200_000, "inc_zf", "Black Premium *1519"),        // та же выплата у владельца
    )

    private fun scope(p: Boolean, z: Boolean) = MoneyScope.of(p, z, entries, setOf(biz))

    private fun row(rows: List<MoneyCashflow.Row>, title: String) = rows.first { it.title == title }.values[0]
    private fun has(rows: List<MoneyCashflow.Row>, title: String) = rows.any { it.title == title }

    private val sep = listOf(YearMonth.of(2026, 9))

    @Test fun statsFollowPurpose() {
        val p = MoneyStats.of(MoneyStats.Kind.MONTH, LocalDate.parse("2026-09-10"))
        // Личное: кофе с бизнес-карты — личная трата; юрист с личной — нет.
        assertEquals(2_100_000L, MoneyStats.totals(entries, p, scope(true, false)).spentKop)
        // ЗФ: юрист и софт, откуда бы ни платили.
        assertEquals(3_500_000L, MoneyStats.totals(entries, p, scope(false, true)).spentKop)
        assertEquals(50_000_000L, MoneyStats.totals(entries, p, scope(false, true)).incomeKop)
        // Всё вместе: при книгах ЗФ выплата владельцу — ВГО, не доход.
        val all = MoneyStats.totals(entries, p, scope(true, true))
        assertEquals(5_600_000L, all.spentKop)
        assertEquals(50_000_000L, all.incomeKop)
    }

    @Test fun personalCashHasVgo() {
        val rows = MoneyCashflow.build(entries, sep, scope(true, false))
        assertEquals(20_000_000L, row(rows, "Поступления"))            // выплата от ЗФ — доход владельца
        assertEquals(-2_100_000L, row(rows, "Выплаты"))                // еда и кофе с бизнес-карты
        assertEquals(-3_000_000L, row(rows, "За ЗФ со своих карт"))
        assertEquals(100_000L, row(rows, "Личное, оплаченное с бизнес-карты"))
        // Личные деньги: +200 000 − 20 000 − 30 000 (кофе оплатила ЗФ).
        assertEquals(15_000_000L, row(rows, "Чистый денежный поток"))
    }

    @Test fun zfCashHasVgo() {
        val rows = MoneyCashflow.build(entries, sep, scope(false, true))
        assertEquals(50_000_000L, row(rows, "Поступления"))
        assertEquals(-3_500_000L, row(rows, "Выплаты"))                // софт и юрист
        assertEquals(-20_000_000L, row(rows, "Выплаты владельцу"))
        assertEquals(-100_000L, row(rows, "Личное владельца с бизнес-карты"))
        assertEquals(3_000_000L, row(rows, "Оплачено владельцем со своих карт"))
        // Деньги ЗФ: +500 000 − 5 000 − 1 000 − 200 000.
        assertEquals(29_400_000L, row(rows, "Чистый денежный поток"))
    }

    @Test fun bothHasNoVgo() {
        val rows = MoneyCashflow.build(entries, sep, scope(true, true))
        assertTrue(!has(rows, "ВГО, нетто"))
        assertEquals(50_000_000L, row(rows, "Поступления"))
        assertEquals(-5_600_000L, row(rows, "Выплаты"))
        // Всё вместе: снаружи пришло 500 000, ушло 56 000; выплата владельцу — внутри.
        assertEquals(44_400_000L, row(rows, "Чистый денежный поток"))
    }

    @Test fun lastButtonStays() {
        val none = MoneyScope.of(personal = false, zf = false, entries = entries, zfAccounts = setOf(biz))
        assertTrue(none.personal && !none.zf)
        assertTrue(scope(true, false).showsAccount(MoneyCashflow.NATASHA_DEBT) && scope(false, true).showsAccount(MoneyCashflow.NATASHA_DEBT))
        assertTrue(!scope(true, false).showsAccount(biz) && scope(false, true).showsAccount(biz))
    }
}
