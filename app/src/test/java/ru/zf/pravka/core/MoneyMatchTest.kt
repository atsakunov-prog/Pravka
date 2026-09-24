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

    // ---- Наличные ↔ карта (владелец, 25.09.2026: «наговорить "взял 200 000 из
    // наличных и положил на Тинькофф", а когда приедет пуш — он подтвердит») ----

    private fun push(id: String, what: String, rub: Long, ts: Long, note: String = "") =
        MoneyEntry(id = id, owner = "sasha", source = MoneyEntry.Source.PUSH, ts = ts, rubKop = rub, what = what, note = note, account = "*1519")

    private fun walletKop(entries: List<MoneyEntry>) = MoneyCashflow.walletMoves(entries).sumOf { it.rubKop }

    @Test fun cashDepositSaidWaitsForPushThenStatementAndWalletMovesOnce() {
        val said = voice("v1", "на Тинькофф из наличных", -200_000_00, t0, "cash", cash = true)
        // Сказал — кошелёк похудел сразу, банка ещё нет.
        assertEquals(-200_000_00L, walletKop(MoneyMatch.run(listOf(said), emptyList(), t0 + day).entries))

        // Приехал пуш — подтвердил.
        val r1 = MoneyMatch.run(listOf(said, push("p1", "Банкомат Т-Банка", 200_000_00, t0 + 3_600_000L)), emptyList(), t0 + day)
        val b1 = r1.entries.associateBy { it.id }
        assertEquals("p1", b1["v1"]!!.matchId)
        assertEquals("v1", b1["p1"]!!.matchId)
        assertEquals("cash", b1["p1"]!!.category)
        assertEquals(-200_000_00L, walletKop(r1.entries))

        // Пришла выписка — строка заменила пуш, связь голоса переехала на неё.
        val row = bank("t1", "Внесение наличных", 200_000_00, t0 + 2 * 3_600_000L).copy(account = "*1519")
        val r2 = MoneyMatch.run(r1.entries + row, emptyList(), t0 + 2 * day)
        val b2 = r2.entries.associateBy { it.id }
        assertEquals("t1", b2["p1"]!!.replacedBy)
        assertEquals("t1", b2["v1"]!!.matchId)
        assertEquals("cash", b2["t1"]!!.category)
        assertEquals(-200_000_00L, walletKop(r2.entries))
        // Перемещение — ни доход, ни трата.
        val s = MoneyMatch.summary(r2.entries, t0 - day, t0 + 30 * day, withZf = true)
        assertEquals(0L, s.incomeKop)
        assertEquals(0L, s.expenseKop)
    }

    @Test fun atmWithdrawalSaidLinksToBankAndWalletGrowsOnce() {
        val said = voice("v1", "снял в банкомате", 50_000_00, t0, "cash", cash = true)
        val row = bank("t1", "Снятие в банкомате", -50_000_00, t0 + day, mcc = "6011")
        val r = MoneyMatch.run(listOf(said, row), emptyList(), t0 + 2 * day)
        assertEquals("t1", r.entries.first { it.id == "v1" }.matchId)
        assertEquals(50_000_00L, walletKop(r.entries))
    }

    @Test fun cashDepositDoesNotTakeSomeonesTransferOfTheSameAmount() {
        val said = voice("v1", "на Тинькофф из наличных", -200_000_00, t0, "cash", cash = true)
        val transfer = push("p1", "Иван П.", 200_000_00, t0 + 3_600_000L, note = "СБП")
        // Строка — через двое суток: пуш перевода с ней не склеивается (другое время).
        val row = bank("t2", "Внесение наличных", 200_000_00, t0 + 2 * day)
        val r = MoneyMatch.run(listOf(said, transfer, row), emptyList(), t0 + 2 * day)
        val byId = r.entries.associateBy { it.id }
        assertEquals("t2", byId["v1"]!!.matchId)
        assertEquals("", byId["p1"]!!.matchId)
    }

    @Test fun cashDepositPushWithAtmWordIsCashEvenWithoutVoice() {
        // Автоматически: внесение через банкомат — всегда из кошелька, не доход.
        val r = MoneyMatch.run(listOf(push("p1", "Пополнение через банкомат", 200_000_00, t0)), emptyList(), t0 + day)
        assertEquals("cash", r.entries.single().category)
        assertEquals(-200_000_00L, walletKop(r.entries))
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
