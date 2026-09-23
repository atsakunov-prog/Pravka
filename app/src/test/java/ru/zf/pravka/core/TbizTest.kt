package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Выписка Т-Бизнеса (счёт ООО «Знакомый финансист») — шапка и виды строк как в
// настоящей выгрузке 23.09.2026; счета, суммы и контрагенты вымышленные.
class TbizTest {

    private val head = "﻿Номер счёта;Тип операции (пополнение/списание);Дата проведения;Номер платежа;Валюта операции;Сумма в валюте счёта;Валюта счёта;Описание операции;Назначение платежа;Счет плательщика;ИНН плательщика;КПП плательщика;Наименование плательщика;БИК банка плательщика;Корр. счет плательщика;Счет получателя;Договор получателя;ИНН получателя;КПП получателя;Наименование получателя;БИК банка получателя;Корр. счет получателя;Счет контрагента;ИНН контрагента;Наименование контрагента;БИК банка контрагента"

    private fun row(type: String, date: String, n: String, sum: String, desc: String, purpose: String, counterparty: String) =
        "40702000000000000001;$type;$date;$n;643;$sum;643;\"$desc\";\"$purpose\";;;;;;;;;;;;;;;;\"$counterparty\";"

    private val csv = listOf(
        head,
        row("Дебет", "08.01.2026", "1", "6635,0", "Оплата в YANDEX*4121*TAXI Moscow RUS", "Отражение операции оплаты по карте номер 2200...8958 YANDEX*4121*TAXI Moscow RUS. Договор 1", "АО \"\"ТБанк\"\""),
        row("Кредит", "10.02.2026", "2", "500000,0", "Оплата по счету № 5 от 01.02.2026 за Консультационные услуги", "Оплата по счету № 5", "ООО \"\"КЛИЕНТ\"\""),
        row("Дебет", "11.02.2026", "3", "200000,0", "Перевод по договору займа №1 от 01.01.2026г. НДС не облагается", "Перевод по договору займа", "ЦАКУНОВ АЛЕКСАНДР СЕРГЕЕВИЧ"),
        row("Дебет", "12.02.2026", "4", "1000,0", "Внутренний перевод на депозит \"\"Овернайт\"\"", "Внутренний перевод", "ОБЩЕСТВО С ОГРАНИЧЕННОЙ ОТВЕТСТВЕННОСТЬЮ \"\"ЗНАКОМЫЙ ФИНАНСИСТ\"\""),
        row("Дебет", "13.02.2026", "5", "8000,0", "Оплата по договору №ZF/C/1 от 01.01.2026. НДС не облагается", "Оплата по договору", "ИП Савицкая Ольга Владимировна"),
    ).joinToString("\r\n")

    @Test fun parsesSignCardAndNames() {
        assertEquals(BankStatements.Kind.TBIZ, BankStatements.detect(csv))
        val e = BankStatements.tbiz(csv).entries
        assertEquals(5, e.size)
        assertEquals(-663_500L, e[0].rubKop)
        assertEquals("YANDEX*4121*TAXI", e[0].what)
        assertEquals("ЗФ *8958", e[0].account)
        assertEquals(50_000_000L, e[1].rubKop)
        assertEquals("ООО \"КЛИЕНТ\"", e[1].what)
        // Внутреннее — описанием: контрагент там сама ЗФ.
        assertTrue(e[3].what.startsWith("Внутренний перевод на депозит"))
        // Номера постоянны: та же выписка второй раз ничего не удвоит.
        assertEquals(e.map { it.id }, BankStatements.tbiz(csv).entries.map { it.id })
    }

    @Test fun factoryRulesSplitZfAndPersonal() {
        val rules = MoneyRules.parseText(
            (java.io.File("src/main/assets/money_payees.txt").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/money_payees.txt")).readText()
        ).also { assertTrue(it.errors.toString(), it.errors.isEmpty()) }.rules
        val cats = MoneyMatch.run(BankStatements.tbiz(csv).entries, rules, System.currentTimeMillis()).entries.map { it.category }
        // Такси с бизнес-карты — личное; клиент — выручка; займ владельцу — выплата; овернайт — свои; терапия — личное.
        assertEquals(listOf("transport", "zf_revenue", "zf_owner", "own", "therapy"), cats)
    }

    @Test fun payoutPairsWithPersonalIncome() {
        val zf = BankStatements.tbiz(csv).entries[2].copy(category = "zf_owner")
        val mine = MoneyEntry(
            id = "t1", owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = zf.ts + 3_600_000, rubKop = 20_000_000,
            what = "Пополнение. ООО ЗНАКОМЫЙ ФИНАНСИСТ", category = "inc_zf", account = "Black Premium",
        )
        val r = MoneyMatch.linkZf(listOf(zf, mine)).associateBy { it.id }
        assertEquals("t1", r[zf.id]!!.matchId)
        assertEquals(zf.id, r["t1"]!!.matchId)
    }

    @Test fun businessCardPushFindsZfAccount() {
        val e = BankStatements.tbiz(csv).entries
        assertEquals(MoneyCashflow.TBIZ_NAME, MoneyCashflow.cardMap(e)["8958"])
    }
}
