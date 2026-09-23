package ru.zf.pravka.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Вторая проверка суммы кодом и перевод голоса в черновики журнала.
class MoneyVoiceTest {

    private fun item(minor: Long, heard: String, cat: String = "cafe", cur: String = "RUB", doubt: String = "") =
        MoneyVoice.Item("что-то", minor, cur, heard, false, cat, "", false, "", doubt)

    @Test fun sameDigitsNoDoubt() {
        assertEquals("", MoneyVoice.sanity(item(380_00, "380"), -380_00))
        assertEquals("", MoneyVoice.sanity(item(2_200_00, "2 200"), -2_200_00))
        assertEquals("", MoneyVoice.sanity(item(380_00, "триста восемьдесят"), -380_00))
        // С множителем цифры и не должны совпадать.
        assertEquals("", MoneyVoice.sanity(item(8_000_00, "8к", cat = "clothes"), -8_000_00))
        assertEquals("", MoneyVoice.sanity(item(24_25, "24.25", cur = "EUR", cat = "subs_work"), -2_425_00))
    }

    @Test fun digitsDisagree() {
        val d = MoneyVoice.sanity(item(2_200_00, "220"), -2_200_00)
        assertTrue(d, d.contains("услышано «220»"))
    }

    @Test fun tooMuchForCoffee() {
        val d = MoneyVoice.sanity(item(38_000_00, "тридцать восемь тысяч"), -38_000_00)
        assertTrue(d, d.contains("проверь нули"))
        // Сомнение модели сохраняется первым.
        val both = MoneyVoice.sanity(item(50_00, "полтинник", doubt = "50 ₽ или 50 000 ₽?"), -50_00)
        assertTrue(both.startsWith("50 ₽ или 50 000 ₽?"))
    }

    @Test fun draftsCarryCurrencyDateAndCash() {
        val today = LocalDate.of(2026, 9, 23)
        val items = listOf(
            MoneyVoice.Item("кофе", 380_00, "RUB", "триста восемьдесят", false, "cafe", "sasha", false, "", ""),
            MoneyVoice.Item("Claude", 20_00, "USD", "двадцать долларов", false, "subs_work", "", false, "2026-09-22", ""),
            MoneyVoice.Item("шаурма", 350_00, "RUB", "350", false, "cafe", "", true, "", ""),
            MoneyVoice.Item("вернули за билет", 3_000_00, "RUB", "три тысячи", true, "travel", "", false, "", ""),
            MoneyVoice.Item("пусто", 0, "RUB", "", false, "cafe", "", false, "", ""),
        )
        val d = MoneyVoice.toDrafts(
            items, takeId = 7, owner = "sasha", today = today, noonTs = { 1000L }, takeTs = 5000L,
            rates = { cur, _ -> if (cur == "USD") 81.5 else null },
        )
        assertEquals(4, d.size)
        assertTrue(d.all { it.draft && it.source == MoneyEntry.Source.VOICE })
        assertEquals(-380_00L, d[0].rubKop)
        assertEquals(5000L, d[0].ts)
        assertEquals(-1_630_00L, d[1].rubKop)
        assertEquals(MoneyEntry.RubBasis.CBR_PRELIM, d[1].rubBasis)
        assertEquals(1000L, d[1].ts)
        assertEquals(MoneyEntry.CASH, d[2].account)
        assertEquals(3_000_00L, d[3].rubKop)
        assertEquals("v:7:1", d[0].id)
    }
}
