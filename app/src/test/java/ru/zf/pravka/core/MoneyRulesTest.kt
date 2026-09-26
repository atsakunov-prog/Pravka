package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Раскладка по справочнику — на вымышленных именах; настоящий заводской
// справочник проверяет FactoryPayeesTest.
class MoneyRulesTest {

    private fun e(what: String, rub: Long = -100_00, owner: String = "sasha", mcc: String = "", bank: String = "") =
        MoneyEntry(
            id = what, owner = owner, source = MoneyEntry.Source.TINKOFF, ts = 0, rubKop = rub,
            what = what, mcc = mcc, bankCategory = bank,
        )

    @Test fun bankCategoryIsTheLastResort() {
        // Салон красоты с медицинским MCC — это красота: название сильнее MCC.
        assertEquals("beauty", MoneyRules.classify(e("Салон красоты", mcc = "8011", bank = "Медицина"), emptyList())?.category)
        // Золотое яблоко у Альфы в «Продуктах».
        assertEquals("beauty", MoneyRules.classify(e("GOLD APPLE", mcc = "5311", bank = "Продукты"), emptyList())?.category)
        assertEquals("groceries", MoneyRules.classify(e("Неизвестный магазин", mcc = "5411"), emptyList())?.category)
        assertEquals("cafe", MoneyRules.classify(e("Кафе у дома", bank = "Фастфуд"), emptyList())?.category)
        // Перевод человеку без справочника — не угадываем, спросим.
        assertNull(MoneyRules.classify(e("Иван П.", bank = "Переводы"), emptyList()))
    }

    @Test fun servicesAndMovement() {
        assertEquals("subs_work", MoneyRules.classify(e("ANTHROPIC* CLAUDE TEAM"), emptyList())?.category)
        assertEquals("plati", MoneyRules.classify(e("Плати по миру"), emptyList())?.category)
        assertEquals("own", MoneyRules.classify(e("Между своими счетами"), emptyList())?.category)
        assertEquals("cash", MoneyRules.classify(e("Снятие в банкомате Т-Банк"), emptyList())?.category)
        assertEquals("inc_marianna", MoneyRules.classify(
            e("ЗАРАБОТНАЯ ПЛАТА. Основание", rub = 100_000_00, owner = "marianna"), emptyList(),
        )?.category)
    }

    @Test fun ownerRulesWinAndLongestWins() {
        val rules = MoneyRules.parseText(
            """
            # справочник
            Иван П. = Кружки · Боря
            − Пётр = Помощь по дому
            + Пётр = Займы
            Марианна: Сидор С. = Между нами
            Пётр Великий = Досуг # длинный шаблон точнее
            """.trimIndent()
        )
        assertTrue(rules.errors.isEmpty())
        val r = rules.rules
        assertEquals("clubs", MoneyRules.classify(e("Иван П."), r)?.category)
        assertEquals("borya", MoneyRules.classify(e("Иван П."), r)?.who)
        assertEquals("help", MoneyRules.classify(e("Пётр К.", rub = -5_00), r)?.category)
        assertEquals("loan", MoneyRules.classify(e("Пётр К.", rub = 5_00), r)?.category)
        assertEquals("leisure", MoneyRules.classify(e("Пётр Великий", rub = -5_00), r)?.category)
        // Правило только для счетов Марианны на Сашином не срабатывает.
        assertNull(MoneyRules.classify(e("Сидор С.", owner = "sasha", bank = "Переводы"), r))
        assertEquals("spouse", MoneyRules.classify(e("Сидор С.", owner = "marianna"), r)?.category)
        // Справочник владельца сильнее безличных правил.
        val over = MoneyRules.parseText("ВкусВилл = Дача").rules
        assertEquals("dacha", MoneyRules.classify(e("ВкусВилл"), over)?.category)
    }

    @Test fun textRoundTripAndErrors() {
        val src = "− Пётр = Помощь по дому · Рома\nМарианна: Сидор = Между нами"
        val parsed = MoneyRules.parseText(src).rules
        assertEquals(parsed, MoneyRules.parseText(MoneyRules.toText(parsed)).rules)
        val bad = MoneyRules.parseText("без знака равно\nКто-то = Непонятная категория")
        assertEquals(2, bad.errors.size)
    }

    @Test fun categoriesCatalog() {
        val keys = MoneyCategories.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(MoneyCategories.counts("groceries", withZf = false))
        assertTrue(!MoneyCategories.counts("zf", withZf = false))
        assertTrue(MoneyCategories.counts("zf", withZf = true))
        assertTrue(!MoneyCategories.counts("spouse", withZf = true))
        assertEquals("clubs", MoneyCategories.find("кружки")?.key)
        assertEquals("seryozha", MoneyCategories.findWho("Серёжа"))
    }
}
