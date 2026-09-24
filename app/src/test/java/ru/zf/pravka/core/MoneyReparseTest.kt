package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Переразбор пойманных пушей новым разбором (шаг переразбора истории,
// 25.09.2026) — на настоящем пуше владельца о внесении через банкомат.
class MoneyReparseTest {

    private val pkg = "com.idamob.tinkoff.android"
    private val text = "Пополнение на 195 000 ₽, счет RUB.\nБанкомат.\nДоступно 232 483,72 ₽"
    private val raw = MoneyReparse.Raw(ts = 1_790_000_000_000L, pkg = pkg, title = "", text = text)
    private val id = "push-" + BankPush.key("", text)

    /** Как этот пуш лёг вчерашним разбором: «Пополнение», доход, догадка модели. */
    private fun yesterday(categoryBy: MoneyEntry.CategoryBy = MoneyEntry.CategoryBy.MODEL, category: String = "inc_other") =
        MoneyEntry(
            id = id, owner = "sasha", source = MoneyEntry.Source.PUSH, ts = raw.ts, rubKop = 19_500_000L,
            what = "Пополнение", category = category, categoryBy = categoryBy,
        )

    @Test fun oldAtmPushBecomesCashAfterReparse() {
        val out = MoneyReparse.pushes(listOf(yesterday()), listOf(raw), "sasha")
        assertEquals(1, out.looked)
        assertEquals(1, out.changed)
        val e = out.entries.single()
        assertEquals("Банкомат", e.what)
        assertEquals("", e.category)
        // Сверка ставит категорию заново по новому «что»: из кошелька, не доход.
        val r = MoneyMatch.run(out.entries, emptyList(), raw.ts + 86_400_000L)
        assertEquals("cash", r.entries.single().category)
        assertEquals(-19_500_000L, MoneyCashflow.walletMoves(r.entries).sumOf { it.rubKop })
    }

    @Test fun ownersDecisionIsKept() {
        val out = MoneyReparse.pushes(listOf(yesterday(MoneyEntry.CategoryBy.OWNER, "inc_zf")), listOf(raw), "sasha")
        val e = out.entries.single()
        assertEquals("Банкомат", e.what)
        assertEquals("inc_zf", e.category)
        assertEquals(MoneyEntry.CategoryBy.OWNER, e.categoryBy)
    }

    @Test fun sameParseTouchesNothingAndNothingIsDeleted() {
        val first = MoneyReparse.pushes(listOf(yesterday()), listOf(raw), "sasha")
        val again = MoneyReparse.pushes(first.entries, listOf(raw), "sasha")
        assertEquals(0, again.changed)
        assertEquals(first.entries, again.entries)
        // Чужое и не-пуш — не трогается, запись без сырья — остаётся.
        val other = MoneyEntry(id = "v:1:1", owner = "sasha", source = MoneyEntry.Source.VOICE, ts = 1L, rubKop = -100L, what = "кофе")
        val orphan = yesterday().copy(id = "push-нет-сырья")
        val out = MoneyReparse.pushes(listOf(other, orphan), emptyList(), "sasha")
        assertEquals(listOf(other, orphan), out.entries)
    }

    @Test fun pushThatNowParsesIsAdded() {
        val out = MoneyReparse.pushes(emptyList(), listOf(raw), "sasha")
        assertEquals(1, out.added)
        assertEquals(id, out.entries.single().id)
        assertTrue(out.nowMoney.contains(BankPush.key("", text)))
    }

    @Test fun historyStepsRunOnceAndFailedOnesAgain() {
        val steps = HistoryFixes.STEPS
        assertTrue(steps.isNotEmpty())
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
        val first = steps.first()
        val ok = HistoryFixes.Done(first.id, first.title, 1L, 10, 1, "", "")
        val failed = HistoryFixes.Done(first.id, first.title, 1L, 0, 0, "", "IOException: нет места")
        assertTrue(HistoryFixes.pending(steps, listOf(ok)).none { it.id == first.id })
        assertTrue(HistoryFixes.pending(steps, listOf(failed)).any { it.id == first.id })
    }
}
