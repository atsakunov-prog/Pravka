package ru.zf.pravka.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Заводской справочник (assets/money_payees.txt) — тем же разбором, что на
// телефоне: ни одной строки с ошибкой, и главные случаи раскладываются так,
// как владелец сказал 23.09.2026.
class FactoryPayeesTest {

    private val rules = MoneyRules.parseText(File("src/main/assets/money_payees.txt").readText())

    private fun e(what: String, rub: Long, owner: String, src: MoneyEntry.Source, mcc: String = "") =
        MoneyEntry(id = what, owner = owner, source = src, ts = 0, rubKop = rub, what = what, mcc = mcc)

    @Test fun parsesClean() {
        assertTrue(rules.errors.toString(), rules.errors.isEmpty())
        assertTrue(rules.rules.size >= 40)
    }

    @Test fun nannyCashOnMkb() {
        val r = rules.rules
        // 23 000 наличными с МКБ — зарплата няни; другое снятие там же — быт.
        val salary = MoneyRules.classify(e("MCBA", -23_000_00, "marianna", MoneyEntry.Source.MKB, "6011"), r)
        assertEquals("help", salary?.category)
        assertEquals("kids", salary?.who)
        assertEquals("household", MoneyRules.classify(e("ATM 8631", -30_000_00, "marianna", MoneyEntry.Source.MKB, "6011"), r)?.category)
        // Наличные с Альфы — не карта няни: остаются снятыми наличными.
        assertEquals("cash", MoneyRules.classify(e("SHEREMETEVSKOE", -100_000_00, "marianna", MoneyEntry.Source.ALFA, "6011"), r)?.category)
    }

    @Test fun spouseBothSides() {
        val r = rules.rules
        assertEquals("spouse", MoneyRules.classify(e("Марианна Ц.", -20_000_00, "sasha", MoneyEntry.Source.TINKOFF), r)?.category)
        assertEquals("spouse", MoneyRules.classify(e("Пополнение через СБП от Александр Сергеевич Ц", 20_000_00, "marianna", MoneyEntry.Source.MKB), r)?.category)
        // Агентские со счёта Саши — ЗФ; тот же человек на счетах Марианны — займы.
        assertEquals("zf", MoneyRules.classify(e("Марианна Б.", -170_000_00, "sasha", MoneyEntry.Source.TINKOFF), r)?.category)
        assertEquals("loan", MoneyRules.classify(e("Белоусова Марианна Евгеньевна", 300_000_00, "marianna", MoneyEntry.Source.ALFA), r)?.category)
    }

    @Test fun ownerRuleBeatsFactory() {
        val mine = MoneyRules.parseText("Марианна: МКБ: MCC 6011, сумма 23000 = Прочее").rules
        val hit = MoneyRules.classify(e("MCBA", -23_000_00, "marianna", MoneyEntry.Source.MKB, "6011"), mine + rules.rules)
        assertEquals("other", hit?.category)
    }

    @Test fun conditionsRoundTrip() {
        val src = "Марианна: МКБ: MCC 6011, сумма 23000 = Помощь по дому · дети\nАльфа: Сидор = Займы\nMCC 5411, сумма 99.50 = Продукты"
        val parsed = MoneyRules.parseText(src)
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals(parsed.rules, MoneyRules.parseText(MoneyRules.toText(parsed.rules)).rules)
        assertEquals(9950L, parsed.rules[2].amountKop)
        assertEquals("alfa", parsed.rules[1].source)
    }
}
