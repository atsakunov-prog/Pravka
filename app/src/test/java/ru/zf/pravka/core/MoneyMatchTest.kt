package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Сверка пакета: голос ↔ выписка, Саша ↔ Марианна, вопросы, сводка. Имена и
// суммы вымышленные.
class MoneyMatchTest {

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun bank(id: String, what: String, rub: Long, ts: Long, owner: String = "sasha", src: MoneyEntry.Source = MoneyEntry.Source.TINKOFF, mcc: String = "") =
        MoneyEntry(id = id, owner = owner, source = src, ts = ts, rubKop = rub, what = what, mcc = mcc)

    private fun voice(id: String, what: String, rub: Long, ts: Long, cat: String, who: String = "", cash: Boolean = false) =
        MoneyEntry(
            id = id, owner = "sasha", source = MoneyEntry.Source.VOICE, ts = ts, rubKop = rub, what = what,
            category = cat, who = who, categoryBy = MoneyEntry.CategoryBy.OWNER,
            account = if (cash) MoneyEntry.CASH else "",
        )

    @Test fun voiceLinksToBankAndCountsOnce() {
        val entries = listOf(
            bank("b1", "Surf Coffee", -380_00, t0 + day, mcc = "5814"),
            bank("b2", "Детский мир", -8_000_00, t0 + 2 * day),
            voice("v1", "кофе", -380_00, t0, "cafe", "sasha"),
            voice("v2", "куртка Роме", -8_000_00, t0 + day, "clothes", "roma"),
            voice("v3", "шаурма налом", -350_00, t0, "cafe", cash = true),
        )
        val r = MoneyMatch.run(entries, emptyList(), t0 + 10 * day)
        assertEquals(2, r.voiceLinked)
        val byId = r.entries.associateBy { it.id }
        assertEquals("v1", byId["b1"]!!.matchId)
        // Голос отдал выписке своё решение: куртка — одежда Роме, а не «детские вещи» по названию.
        assertEquals("clothes", byId["b2"]!!.category)
        assertEquals("roma", byId["b2"]!!.who)
        // В итоги: две выписки и наличные — без двойного счёта.
        val s = MoneyMatch.summary(r.entries, t0 - day, t0 + 30 * day, withZf = false)
        assertEquals(-(380_00 + 8_000_00 + 350_00).toLong(), s.expenseKop)
        assertEquals(-350_00L, s.cashSpentKop)
    }

    @Test fun spouseTransfersStitchBothSides() {
        val rules = MoneyRules.parseText("Жена Ж. = Между нами\nМарианна: Муж М. = Между нами").rules
        val entries = listOf(
            bank("t1", "Жена Ж.", -20_000_00, t0),
            bank("a1", "Муж М.", 20_000_00, t0 + day / 2, owner = "marianna", src = MoneyEntry.Source.ALFA),
            bank("t2", "Жена Ж.", 50_000_00, t0 + 3 * day),
        )
        val r = MoneyMatch.run(entries, rules, t0 + 10 * day)
        assertEquals(1, r.spouseLinked)
        // Перевод между нами — не трата и не доход.
        val s = MoneyMatch.summary(r.entries, t0 - day, t0 + 30 * day, withZf = true)
        assertEquals(0L, s.expenseKop)
        assertEquals(0L, s.incomeKop)
    }

    @Test fun unknownPayeesAskedAsGroup() {
        val entries = listOf(
            bank("x1", "Иван П.", -6_000_00, t0),
            bank("x2", "Иван П.", -6_000_00, t0 + 7 * day),
            bank("x3", "ВкусВилл", -1_000_00, t0),
        )
        val r = MoneyMatch.run(entries, emptyList(), t0 + 10 * day)
        val q = r.entries.first { it.id == "x1" }.question
        assertTrue(q, q.startsWith("Иван П., 2 раз"))
        assertEquals("", r.entries.first { it.id == "x3" }.question)
        // Ответ владельца — правило, и вопрос уходит у всей группы.
        val after = MoneyMatch.run(r.entries, MoneyRules.parseText("Иван П. = Помощь по дому").rules, t0 + 10 * day)
        assertTrue(after.entries.all { it.question.isEmpty() })
        assertEquals("help", after.entries.first { it.id == "x2" }.category)
    }

    @Test fun voiceWithoutBankAskedOnlyWhenStatementCoversIt() {
        val entries = listOf(
            bank("b1", "ВкусВилл", -1_000_00, t0 - 5 * day),
            bank("b2", "ВкусВилл", -1_000_00, t0 + 5 * day),
            voice("v1", "цветы", -2_500_00, t0, "gifts"),
            voice("v2", "цветы потом", -2_500_00, t0 + 20 * day, "gifts"),
        )
        val r = MoneyMatch.run(entries, emptyList(), t0 + 30 * day)
        assertTrue(r.entries.first { it.id == "v1" }.question.contains("наличные"))
        // За этот день выписки ещё нет — рано спрашивать.
        assertEquals("", r.entries.first { it.id == "v2" }.question)
    }

    @Test fun zfToggle() {
        val rules = MoneyRules.parseText("Партнёр П. = ЗФ: расходы").rules
        val entries = listOf(
            bank("z1", "Партнёр П.", -100_000_00, t0),
            bank("g1", "ВкусВилл", -1_000_00, t0),
        )
        val r = MoneyMatch.run(entries, rules, t0)
        assertEquals(-1_000_00L, MoneyMatch.summary(r.entries, t0 - day, t0 + day, withZf = false).expenseKop)
        assertEquals(-101_000_00L, MoneyMatch.summary(r.entries, t0 - day, t0 + day, withZf = true).expenseKop)
    }
}
