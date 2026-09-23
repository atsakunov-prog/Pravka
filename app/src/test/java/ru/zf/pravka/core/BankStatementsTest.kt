package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Выписки банков — в ТОЧНОМ формате тех, что прислал владелец 23.09.2026
// (шапки, разделители, кавычки, BOM, дроби), но на вымышленных строках:
// репозиторий публичный, настоящие суммы и имена сюда не попадают.
class BankStatementsTest {

    private val tinkoff = "\uFEFF" + """
"Имя счёта";"Номер карты";"Дата операции";"Сумма операции";"Валюта операции";"Сумма в валюте счёта";"Валюта счёта";"Статус";"Категория по-умолчанию";"Ваша категория";"MCC";"Описание";"Сообщение";"Округление";"Сумма операции с округлением";"Бонусы (включая кэшбэк)";"Учёт в аналитике"
"Black Premium";"*1111";"23.09.2026 10:03:12";"-1781,84";"RUB";"-1781,84";"RUB";"Ок";"Супермаркеты";"";"5411";"ВкусВилл";"";"18,16";"-1800,00";"0,00";"Да"
"Black Premium";"";"22.09.2026 18:00:00";"-12000,00";"RUB";"-12000,00";"RUB";"Ок";"Переводы";"";"";"Иван П.";"За Борю";"0,00";"-12000,00";"0,00";"Да"
"Black Premium";"*1111";"21.09.2026 12:00:00";"-5000,00";"AMD";"-1234,56";"RUB";"Ок";"Рестораны";"";"5812";"Kafe Yerevan";"";"0,00";"-1234,56";"0,00";"Да"
"Black Premium";"*1111";"20.09.2026 09:00:00";"-100,00";"RUB";"-100,00";"RUB";"Ошибка";"Такси";"";"4121";"Яндекс Такси";"";"0,00";"-100,00";"0,00";"Да"
"Black Premium";"";"19.09.2026 15:15:07";"150000,00";"RUB";"150000,00";"RUB";"Ок";"Зарплата";"";"";"Пополнение. ООО ""РОМАШКА"". Зарплата";"";"0,00";"150000,00";"0,00";"Да"
""".trimStart().replace("\n", "\r\n")

    private val alfa = """
operationDate,transactionDate,accountName,accountNumber,cardName,cardNumber,merchant,amount,currency,status,category,mcc,type,comment,bonusValue,bonusTitle
21.09.2026,23.09.2026,"Текущий зарплатный счёт",40817810000000000001,"MC World PP",555949******0001,"MOSCOW\MOSCOW PHILARMONIC",8000,RUR,Выполнен,"Культура и искусство",7922,Списание,,,
18.09.2026,21.09.2026,"Текущий зарплатный счёт",40817810000000000001,"MC World PP",555949******0001,MOSKVA\GPN,4345.20,RUR,Выполнен,АЗС,5541,Списание,,,
15.09.2026,15.09.2026,"Текущий зарплатный счёт",40817810000000000001,,,"ЗАРАБОТНАЯ ПЛАТА. Основание 0000 Платёжное поручение №1 от 15.09.2026.",192351.88,RUR,,Зарплата,,Пополнение,,,
13.06.2026,13.06.2026,"Текущий зарплатный счёт",40817810000000000001,,,"ООО НКО ""МОБИ.ДЕНЬГИ""${'"'},15300,RUR,Выполнен,"Медицинские услуги",8021,Списание,,,
12.06.2026,12.06.2026,"Текущий зарплатный счёт",40817810000000000001,,,"Пётр К.",3000,RUR,Выполнен,Переводы,,Списание,"Перевод денежных средств",,
12.06.2026,12.06.2026,"Текущий зарплатный счёт",40817810000000000001,,,"Пётр К.",3000,RUR,Выполнен,Переводы,,Списание,"Перевод денежных средств",,
11.06.2026,11.06.2026,"Текущий зарплатный счёт",40817810000000000001,,,"Что-то",100,RUR,Отменён,Переводы,,Списание,,,
""".trimStart()

    @Test fun detectsByHeader() {
        assertEquals(BankStatements.Kind.TINKOFF, BankStatements.detect(tinkoff))
        assertEquals(BankStatements.Kind.ALFA, BankStatements.detect(alfa))
        assertEquals(BankStatements.Kind.UNKNOWN, BankStatements.detect("a,b,c\n1,2,3"))
    }

    @Test fun tinkoffRowsSignsAndCurrencies() {
        val p = BankStatements.tinkoff(tinkoff)
        // «Ошибка» — не деньги: пропущена и посчитана.
        assertEquals(4, p.entries.size)
        assertEquals(1, p.skipped)
        val vv = p.entries[0]
        assertEquals(-178184L, vv.rubKop)
        assertEquals("ВкусВилл", vv.what)
        assertEquals("5411", vv.mcc)
        assertEquals("Black Premium *1111", vv.account)
        assertEquals("sasha", vv.owner)
        assertTrue(vv.timeKnown)
        // Перевод человеку: сообщение к переводу — в note, это подсказка сверке.
        assertEquals("За Борю", p.entries[1].note)
        // Драмы: рубли — от банка, валюта — своя, знак у обеих.
        val amd = p.entries[2]
        assertEquals(-123456L, amd.rubKop)
        assertEquals(-500000L, amd.origMinor)
        assertEquals("AMD", amd.currency)
        // Кавычки внутри поля.
        assertEquals("Пополнение. ООО \"РОМАШКА\". Зарплата", p.entries[3].what)
        assertEquals(15000000L, p.entries[3].rubKop)
    }

    @Test fun sameFileTwiceGivesSameIds() {
        val a = BankStatements.tinkoff(tinkoff).entries.map { it.id }
        val b = BankStatements.tinkoff(tinkoff).entries.map { it.id }
        assertEquals(a, b)
        assertEquals(a.size, a.toSet().size)
    }

    @Test fun alfaSignFromTypeAndCityStripped() {
        val p = BankStatements.alfa(alfa)
        assertEquals(6, p.entries.size)
        assertEquals(1, p.skipped) // «Отменён»
        val phil = p.entries[0]
        assertEquals(-800000L, phil.rubKop)
        assertEquals("MOSCOW PHILARMONIC", phil.what)
        assertEquals("marianna", phil.owner)
        assertFalse(phil.timeKnown)
        assertEquals("MC World PP *0001", phil.account)
        assertEquals(-434520L, p.entries[1].rubKop)
        assertEquals("GPN", p.entries[1].what)
        // Пустой статус у поступления — норма, а сумма — в плюс.
        assertEquals(19235188L, p.entries[2].rubKop)
        assertEquals("ООО НКО \"МОБИ.ДЕНЬГИ\"", p.entries[3].what)
    }

    @Test fun identicalRowsStayTwoRows() {
        val p = BankStatements.alfa(alfa)
        val two = p.entries.filter { it.what == "Пётр К." }
        assertEquals(2, two.size)
        assertNotEquals(two[0].id, two[1].id)
    }

    @Test fun kopecksWithoutDouble() {
        assertEquals(-178184L, MoneyFormat.parseKop("-1781,84"))
        assertEquals(434520L, MoneyFormat.parseKop("4345.20"))
        assertEquals(43452L, MoneyFormat.parseKop("434.52"))
        assertEquals(800000L, MoneyFormat.parseKop("8000"))
        assertEquals(180000L, MoneyFormat.parseKop("1 800,00"))
        assertEquals(50L, MoneyFormat.parseKop("0.5"))
        assertNull(MoneyFormat.parseKop(""))
        assertNull(MoneyFormat.parseKop("abc"))
        assertNull(MoneyFormat.parseKop("1.234.5"))
        assertEquals("1\u00A0781,84\u00A0₽", MoneyFormat.rub(178184))
        assertEquals("−380\u00A0₽", MoneyFormat.rub(-38000))
    }
}
