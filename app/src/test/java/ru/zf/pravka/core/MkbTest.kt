package ru.zf.pravka.core

import java.io.ByteArrayOutputStream
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// МКБ: у банка только .xlsx. Лист собирается нашим же писателем (`Xlsx`) в
// ТОЧНОМ формате выгрузки владельца (23.09.2026) — шапка, два вида дат, два
// вида описаний, строки итогов внизу, — но на вымышленных строках.
class MkbTest {

    private val header = listOf(
        "Дата транзакции", "Содержание операции", "Категория", "Сумма в валюте операции", "Валюта операции",
        "Сумма в валюте счета", "Валюта счета", "Код авторизации", "MCC",
    )

    private fun row(vararg v: String) = v.map { if (it.isEmpty()) Xlsx.Cell.Empty else Xlsx.Cell.Text(it) }

    private fun book(): ByteArray {
        val rows = listOf(
            row("22.09.2026 21:23:36", "Пополнение через СБП от Иван Иванович И", "Финансовые услуги", "38000", "RUB", "38000", "RUB", "", "4829"),
            row("22.09.2026 14:32:07", "MOSCOW\\\\MOSCOW*AB DAILY*MOSCOW*RUSSIAN FEDERATION\\\\Оплата товаров и услуг", "Супермаркет", "-59", "RUB", "-59", "RUB", "340465", "5411"),
            row("18.09.2026 13:22:49", "MOSCOW\\\\BYSTR BOLSHOY 3*BRATYA KARAVAEVY*MOSCOW*RUSSIAN FEDERATION\\\\Оплата товаров и услуг", "Еда и напитки", "-485", "RUB", "-485", "RUB", "", "5814"),
            row("11.09.2026, 20:09", "MOSKVA\\\\MCBA, ul. Bolshaya, d. 16, MOSKVA, RU", "Финансовые услуги", "-23000", "RUB", "-23000", "RUB", "", "6011"),
            row("11.09.2026, 12:48", "RUS\\\\MOSCOW\\\\AB DAILY,STR PRECHISTENKA 24/1,MOSCOW,RU", "Супермаркет", "-357", "RUB", "-357", "RUB", "", "5411"),
            row("31.08.2026, 11:33", "//ВЗС//0-00//Перечисление заработной платы резиденту", "Прочее", "4821.96", "RUB", "4821.96", "RUB", "", "9999"),
            row("27.06.2026 0:28:33", "Пополнение через СБП от Иван Иванович И", "Финансовые услуги", "15000", "RUB", "15000", "RUB", "", "4829"),
            row("", "", "", "", "", "", "", "", ""),
            row("Доход в RUB:", "57821.96"),
            row("Расход в RUB:", "-23901"),
        )
        val out = ByteArrayOutputStream()
        Xlsx.write(listOf(Xlsx.Sheet("Лист1", header, rows)), out, TimeZone.getTimeZone("Europe/Moscow"))
        return out.toByteArray()
    }

    @Test fun readsSheetAndParses() {
        val bytes = book()
        assertTrue(XlsxRead.isZip(bytes))
        val rows = XlsxRead.firstSheet(bytes)
        assertTrue(BankStatements.isMkb(rows))
        val p = BankStatements.mkb(rows)
        assertEquals(7, p.entries.size)
        assertEquals(0, p.skipped)
        assertEquals(listOf("Пополнение через СБП от Иван Иванович И", "AB DAILY", "BRATYA KARAVAEVY", "MCBA", "AB DAILY",
            "Перечисление заработной платы резиденту", "Пополнение через СБП от Иван Иванович И"), p.entries.map { it.what })
        assertEquals(-5_900L, p.entries[1].rubKop)
        assertEquals(482_196L, p.entries[5].rubKop)
        assertEquals("marianna", p.entries[0].owner)
        // Оба вида дат — со временем.
        assertTrue(p.entries.all { it.ts > 0 && it.timeKnown })
        // Итоги банка — не операции; сумма сходится с его «доходом» и «расходом».
        assertEquals(5_782_196L - 2_390_100L, p.entries.sumOf { it.rubKop })
        // Раскладка: магазин — продукты, банкомат — наличные, зарплата — доход Марианны.
        assertEquals("groceries", MoneyRules.classify(p.entries[1], emptyList())?.category)
        assertEquals("cash", MoneyRules.classify(p.entries[3], emptyList())?.category)
        assertEquals("inc_marianna", MoneyRules.classify(p.entries[5], emptyList())?.category)
    }

    @Test fun columnLetters() {
        assertEquals(0, XlsxRead.colIndex("A"))
        assertEquals(25, XlsxRead.colIndex("Z"))
        assertEquals(26, XlsxRead.colIndex("AA"))
    }
}
