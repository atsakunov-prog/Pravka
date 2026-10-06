package ru.zf.pravka.core

import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ДДС и баланс от якорей. Суммы и счета вымышленные.
class MoneyCashflowTest {

    private fun at(d: String, h: Int = 12) = MoneyStats.startOf(LocalDate.parse(d)) + h * 3_600_000L

    private fun e(id: String, d: String, rub: Long, cat: String, acc: String = "Black Premium *1519", src: MoneyEntry.Source = MoneyEntry.Source.TINKOFF) =
        MoneyEntry(id = id, owner = "sasha", source = src, ts = at(d), rubKop = rub * 100, what = id, category = cat, account = acc)

    private val entries = listOf(
        e("inc", "2026-09-05", 300_000, "inc_zf"),
        e("food", "2026-09-06", -20_000, "groceries"),
        e("club", "2026-09-07", -15_000, "clubs"),
        e("zf", "2026-09-08", -5_000, "zf"),
        e("loan", "2026-09-09", 100_000, "loan"),
        e("back", "2026-09-20", -40_000, "loan"),
        e("wife", "2026-09-10", -38_000, "spouse"),
        e("unk", "2026-09-11", -1_000, ""),
        e("aug", "2026-08-15", -10_000, "groceries"),
    )

    private fun row(rows: List<MoneyCashflow.Row>, title: String) = rows.first { it.title == title }

    @Test fun sectionsAndTotals() {
        val months = listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9))
        val rows = MoneyCashflow.build(entries, months, withZf = false)
        assertEquals(listOf(0L, 30_000_000L), row(rows, "Поступления").values)
        // Без «+ ЗФ» трата ЗФ в ДДС не идёт; без категории — идёт.
        assertEquals(listOf(-1_000_000L, -3_600_000L), row(rows, "Выплаты").values)
        assertEquals(26_400_000L, row(rows, "Операционный поток").values[1])
        assertEquals(6_000_000L, row(rows, "Финансовый поток").values[1])
        // Трата ЗФ с личной карты при кнопке «Личное» — ВГО «за ЗФ со своих»: деньги ушли, но не на семью.
        assertEquals(-500_000L, row(rows, "За ЗФ со своих карт").values[1])
        assertEquals(31_900_000L, row(rows, "Чистый денежный поток").values[1])
        assertEquals(-3_800_000L, row(rows, "Мимо журнала, нетто").values[1])
        val withZf = MoneyCashflow.build(entries, months, withZf = true)
        assertEquals(-4_100_000L, row(withZf, "Выплаты").values[1])
    }

    @Test fun balanceRollsFromAnchorBothWays() {
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", at("2026-09-10", 18), 5_000_000, "снимок")
        val now = at("2026-09-30")
        val acc = MoneyCashflow.balances(entries, listOf(anchor), now, at("2026-07-01")).first { it.name == "Т-Банк · Black Premium" }
        // После якоря: −1 000 (11.09) и −40 000 (20.09).
        assertEquals(5_000_000L - 100_000 - 4_000_000, acc.kop)
        // Назад к 1 сентября: минус всё, что случилось между ним и якорем.
        val sep1 = MoneyCashflow.balances(entries, listOf(anchor), at("2026-09-01", 0), at("2026-07-01")).first { it.name == "Т-Банк · Black Premium" }
        val between = 300_000L - 20_000 - 15_000 - 5_000 + 100_000 - 38_000
        assertEquals(5_000_000L - between * 100, sep1.kop)
    }

    @Test fun pushAnchorCoversItsStatementRow() {
        val push = e("push-x", "2026-09-10", -700, "sport", acc = "Т-Банк *1519", src = MoneyEntry.Source.PUSH).copy(replacedBy = "row")
        val row = e("row", "2026-09-10", -700, "sport").copy(ts = at("2026-09-10") + 30_000)
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", at("2026-09-10"), 1_000_000, "пуш", covers = setOf("push-x"))
        val acc = MoneyCashflow.balances(listOf(push, row), listOf(anchor), at("2026-09-11"), at("2026-09-01")).single()
        // Строка выписки на 30 секунд позже пуша — та же операция, уже в «Доступно».
        assertEquals(1_000_000L, acc.kop)
    }

    @Test fun unknownWithoutAnchorAndFileFormat() {
        // Трата ЗФ с личной карты двигает и долг Наташе (доля прибыли) — смотрим карту.
        val acc = MoneyCashflow.balances(entries, emptyList(), at("2026-09-30"), at("2026-07-01")).single { it.name == "Т-Банк · Black Premium" }
        assertNull(acc.kop)
        val parsed = MoneyCashflow.parseAnchors(
            "# комментарий\n23.09.2026 19:10 | Т-Банк · Платинум | -733000 | кредитка\nкривая строка\n"
        )
        assertEquals(1, parsed.size)
        assertEquals(-73_300_000L, parsed[0].kop)
        assertEquals("Т-Банк · Платинум", parsed[0].account)
    }

    @Test fun factoryBalancesFileParses() {
        val f = java.io.File("src/main/assets/money_balances.txt").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/money_balances.txt")
        val a = MoneyCashflow.parseAnchors(f.readText())
        assertEquals(15, a.size)
        assertTrue(a.any { it.account == MoneyCashflow.NATASHA_DEBT && it.kop == 0L })
        // Снимок 70 743,72 — на секунде пуша, где «Доступно» совпало со снимком.
        val bp = a.single { it.account == "Т-Банк · Black Premium" }
        assertEquals(7_074_372L, bp.kop)
        assertEquals(MoneyStats.startOf(LocalDate.parse("2026-09-23")) + (18 * 3600 + 50 * 60 + 32) * 1000L, bp.ts)
    }

    @Test fun pushCarriesAvailable() {
        val p = (BankPush.parse("ВкусВилл", "Покупка на 1 781,84 ₽, счет карты *1519\nДоступно 13 630,02 ₽") as BankPush.Outcome.Money).p
        assertEquals(1_363_002L, p.balanceKop)
    }

    private fun asset(name: String) =
        (java.io.File("src/main/assets/$name").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/$name")).readText()

    @Test fun ownerCashFactsAndWallet() {
        val manual = MoneyCashflow.parseManual(asset("money_manual.txt"), "sasha")
        assertEquals(10, manual.size)
        assertEquals(listOf("inc_zf", "zf_revenue", "zf_owner", "owed", "inc_zf", "zf_revenue", "zf_owner", "owed", "loan", "gifts"), manual.map { it.category })
        assertTrue(manual.filter { it.category in setOf("inc_zf", "loan", "gifts") }.all { it.account == MoneyEntry.CASH && it.categoryBy == MoneyEntry.CategoryBy.OWNER })
        // Касса ЗФ — транзит: сколько пришло от клиента, столько выдано Саше.
        assertEquals(0L, manual.filter { it.account == MoneyMatch.ZF_CASH }.sumOf { it.rubKop })
        assertTrue(manual.filter { it.category == "owed" }.all { it.account == MoneyCashflow.NATASHA_DEBT })
        // Номер постоянный: второе чтение даёт те же записи.
        assertEquals(manual.map { it.id }, MoneyCashflow.parseManual(asset("money_manual.txt"), "sasha").map { it.id })

        val deposit = e("dep", "2026-09-23", 480_000, "cash")
        val all = manual.filter { it.ts >= at("2026-09-01") } + deposit
        val rows = MoneyCashflow.build(all, listOf(YearMonth.of(2026, 9)), withZf = false)
        assertEquals(388_000_000L, row(rows, "Поступления").values[0])
        assertEquals(-20_000_000L, row(rows, "Займы возвращены").values[0])
        // Кошелёк: +3 880 000 от ЗФ, −400 000 папе, −480 000 внесено в банк.
        val wallet = MoneyCashflow.balances(all, emptyList(), at("2026-09-30"), at("2026-09-01")).first { it.name == MoneyCashflow.WALLET }
        assertNull(wallet.kop)
        assertEquals(300_000_000L, wallet.flowKop)
    }

    @Test fun papaTransfersAreLoan() {
        val rules = MoneyRules.parseText(asset("money_payees.txt")).rules
        val inPapa = MoneyEntry(id = "p", owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = at("2026-09-18"), rubKop = 10_000_000, what = "Сергей Ц.")
        val outSummer = inPapa.copy(id = "f", rubKop = -6_500_000)
        val r = MoneyMatch.run(listOf(inPapa, outSummer), rules, at("2026-09-24")).entries.associateBy { it.id }
        assertEquals("loan", r["p"]!!.category)
        assertEquals("summer", r["f"]!!.category)
    }

    @Test fun natashaShareCutsIncomeAndFlows() {
        val income = MoneyEntry(
            id = "zf", owner = "sasha", source = MoneyEntry.Source.MANUAL, ts = at("2026-09-22"), rubKop = 388_000_000,
            what = "ЗФ", account = MoneyEntry.CASH, category = "inc_zf", categoryBy = MoneyEntry.CategoryBy.OWNER,
        )
        val paid = e("nat", "2026-09-23", -400_000, "zf_share")
        val rows = MoneyCashflow.build(listOf(income, paid), listOf(YearMonth.of(2026, 9)), withZf = false)
        // Доля партнёра с личной карты — минус в доходах, не трата семьи.
        assertEquals(348_000_000L, row(rows, "Поступления").values[0])
        assertEquals(0L, row(rows, "Выплаты").values[0])
        val debt = MoneyCashflow.Anchor(MoneyCashflow.NATASHA_DEBT, at("2026-01-01", 0), 0, "с нуля")
        val flows = MoneyCashflow.accountFlows(listOf(income, paid), listOf(debt), at("2026-09-01", 0), at("2026-10-01", 0), at("2026-09-24"))
        assertEquals(MoneyCashflow.WALLET, flows.first().name)
        assertEquals(388_000_000L, flows.first().inKop)
        val bp = flows.first { it.name == "Т-Банк · Black Premium" }
        assertEquals(-40_000_000L, bp.outKop)
        assertEquals("Доля Наташи (ЗФ)", bp.byCategory.single().first)
    }

    // Наташа — партнёр на 30 % прибыли ЗФ, не на зарплате (владелец, 05.10.2026):
    // долг зафиксирован на этот день по прежнему правилу, дальше — 30 % прибыли.
    @Test fun natashaDebtIsFixedByOldRuleThenShareOfZfProfit() {
        val zf = MoneyEntry.Source.TBIZ
        fun owed(id: String, d: String, rub: Long) = MoneyEntry(id = id, owner = "sasha", source = MoneyEntry.Source.MANUAL,
            ts = at(d), rubKop = rub * 100, what = "доля", account = MoneyCashflow.NATASHA_DEBT, category = "owed")
        val all = listOf(
            // ---- До фиксации: прежнее правило, начисления минус переводы с личных карт.
            owed("owed1", "2026-08-20", -100_000),
            owed("owed2", "2026-09-22", -50_000),
            e("share0", "2026-09-23", -60_000, "zf_share"),
            // Надиктованный перевод, склеенный с банком, второй раз не гасит.
            e("share0v", "2026-09-23", -60_000, "zf_share", acc = "", src = MoneyEntry.Source.VOICE).copy(matchId = "share0"),
            // Прибыль ЗФ и платежи ей со счёта ЗФ до фиксации в долг не идут.
            e("rev0", "2026-09-10", 200_000, "zf_revenue", acc = "ЗФ", src = zf),
            e("team0", "2026-09-11", -5_000, "zf_team", acc = "ЗФ", src = zf),
            e("part0", "2026-04-01", -10_000, "zf_partner", acc = "ЗФ", src = zf),
            // ---- С дня фиксации: 30 % каждой операции прибыли минус выплаты.
            // Расход ЗФ по назначению — и с личной карты, в сам день фиксации.
            e("soft", "2026-10-05", -1_000, "subs_work"),
            e("rev", "2026-10-10", 10_000, "zf_revenue", acc = "ЗФ", src = zf),
            e("team", "2026-10-11", -2_000, "zf_team", acc = "ЗФ", src = zf),
            // Выплата семье из ЗФ — не расход (так и на сервере).
            e("owner", "2026-10-12", -5_000, "zf_owner", acc = "ЗФ", src = zf),
            // Не разложенное на счёте ЗФ — в прибыль, как на сервере; на личной карте — нет.
            e("unk", "2026-10-13", -1_000, "", acc = "ЗФ", src = zf),
            e("unkMine", "2026-10-13", -700, ""),
            // Прежних начислений после фиксации нет: если и есть — не в счёт.
            owed("owed3", "2026-10-14", -999),
            // Выплаты ей: со счёта ЗФ и с личной карты.
            e("part", "2026-10-15", -4_000, "zf_partner", acc = "ЗФ", src = zf),
            e("share", "2026-10-16", -500, "zf_share"),
        )
        val debt = MoneyCashflow.Anchor(MoneyCashflow.NATASHA_DEBT, at("2026-01-01", 0), 0, "с нуля")
        fun debtAt(t: Long) = MoneyCashflow.balances(all, listOf(debt), t, at("2026-01-01")).first { it.name == MoneyCashflow.NATASHA_DEBT }.kop
        // На день фиксации: −100 000 − 50 000 + 60 000 = −90 000 — из журнала, без константы.
        assertEquals(-9_000_000L, debtAt(at("2026-10-05", 0)))
        // В тот же день: 30 % расхода ЗФ его уменьшают.
        assertEquals(-9_000_000L + 30_000, debtAt(at("2026-10-06", 0)))
        // Конец месяца: +300 − 3 000 + 600 + 300 + 4 000 + 500.
        assertEquals(-9_000_000L + 30_000 - 300_000 + 60_000 + 30_000 + 400_000 + 50_000, debtAt(at("2026-10-31")))
        // До фиксации история не пересчитывается: на 1 сентября — одно начисление августа.
        assertEquals(-10_000_000L, debtAt(at("2026-09-01", 0)))

        assertEquals(LocalDate.of(2026, 10, 5), ZfPartner.START)
        assertTrue(!ZfPartner.FAMILY_SALARY_IS_EXPENSE)
        assertEquals(8L, ZfPartner.share(25L))   // 7,5 копейки — до копейки вверх
        assertEquals("зафиксирован на 05.10.2026, дальше 30 % прибыли ЗФ минус выплаты · точный — в «Деньгах» на компе", ZfPartner.HINT)
        // В ДДС ЗФ выплата доли — финансовая деятельность, не расход.
        val zfRows = MoneyCashflow.build(all, listOf(YearMonth.of(2026, 10)), MoneyScope.ZF.copy(zfAccounts = setOf(MoneyCashflow.TBIZ_NAME)))
        assertEquals(-400_000L, row(zfRows, "Выплата доли партнёру").values[0])
        assertEquals(-400_000L, row(zfRows, "Финансовый поток").values[0])
    }

    // Безымянная строка реестра на счёте ЗФ — зарплата владельца (05.10.2026).
    // Зарплату папы со счёта ЗФ с 06.10.2026 решает сервер: правило «Денег»
    // (`money.partner.payee_rules`) сильнее заводского справочника, и с 05.10
    // это расход из доли владельца, а не ЗФ (docs/svod-phone.md, 2.1).
    @Test fun unnamedRegistrySalaryIsOwnersPayout() {
        val partner = JSONObject()
            .put("pct", 30).put("fixed_on", "2026-10-05")
            .put("family_pay_patterns", JSONArray().put("ИВАНОВ ПЁТР"))
            .put("family_pay_is_cost", false)
            .put("payee_rules", JSONArray().put(JSONObject().put("side", "zf").put("re", "ИВАНОВ\\s+ПЁТР").put("line", "ЗФ: команда и подрядчики")))
        // Правила «Денег» идут впереди заводского слоя — как в MoneyEngine.
        val rules = MoneyRules.fromPartner(partner.getJSONArray("payee_rules")) + MoneyRules.parseText(asset("money_payees.txt")).rules
        assertTrue("строки папы в заводском справочнике больше нет", rules.none { it.comment.contains("папа") && it.category == "zf_team" })
        val zf = MoneyEntry.Source.TBIZ
        val salary = e("Зарплата согласно реестру №1 от 10.08.2026", "2026-08-10", -100_000, "", acc = "ЗФ", src = zf)
            .copy(note = "Зарплата согласно реестру №1 от 10.08.2026")
        val comp = e("Компенсация согласно реестру №2 от 17.08.2026", "2026-08-17", -3_000, "", acc = "ЗФ", src = zf)
        val papa = e("ИВАНОВ ПЁТР", "2026-08-10", -50_000, "", acc = "ЗФ", src = zf).copy(id = "papa", note = "Зарплата согласно реестру №3 от 10.08.2026")
        // Тот же рубль на карте Саши — «Доход от ЗФ».
        val got = e("got", "2026-08-11", 100_000, "inc_zf")
        val r = MoneyMatch.run(listOf(salary, comp, papa, got), rules, at("2026-09-24")).entries.associateBy { it.id }
        assertEquals("zf_owner", r[salary.id]!!.category)
        assertEquals("sasha", r[salary.id]!!.who)
        assertEquals("zf_owner", r[comp.id]!!.category)
        assertEquals("правило «Денег» по регэкспу", "zf_team", r["papa"]!!.category)
        // Выплата ЗФ владельцу клеится с его «Доходом от ЗФ» — ВГО, а не расход.
        assertEquals("inc_zf", r["got"]!!.category)
        assertEquals("got", r[salary.id]!!.matchId)
        // Правило — только счёт ЗФ: та же строка на личной карте его не берёт.
        val mine = MoneyMatch.run(listOf(salary.copy(id = "mine", source = MoneyEntry.Source.TINKOFF, account = "Black Premium *1519")), rules, at("2026-09-24"))
        assertTrue(mine.entries.single().category != "zf_owner")
        val minePapa = MoneyMatch.run(listOf(papa.copy(id = "mp", source = MoneyEntry.Source.TINKOFF, account = "Black Premium *1519")), rules, at("2026-09-24"))
        assertTrue("сторона zf: на личной карте правило «Денег» молчит", minePapa.entries.single().category != "zf_team")
        // В долге партнёру после фиксации: ни зарплата Саши, ни папина — не расход ЗФ.
        val after = r.values.map { it.copy(ts = it.ts + (at("2026-10-10") - at("2026-08-10"))) }
        val svodRules = ZfPartner.parse(partner)!!
        assertEquals(emptyList<String>(), ZfPartner.moves(after, svodRules).map { it.id })
        // Без правил «Денег» (запас сборки) строка папы — команда, расход ЗФ: уменьшает долг.
        assertEquals(listOf("papa~доля"), ZfPartner.moves(after, ZfPartner.FACTORY).map { it.id })
    }

    @Test fun cardlessPushNamesItsAccount() {
        val rub = BankPush.entry((BankPush.parse("Ozon", "Оплата через СБП на 100 ₽, счет RUB\nДоступно 1 000 ₽") as BankPush.Outcome.Money).p,
            at("2026-10-01"), "sasha", "Ozon", "Оплата через СБП на 100 ₽, счет RUB\nДоступно 1 000 ₽")
        assertEquals("Т-Банк, счет RUB", rub.account)
        assertEquals(MoneyCashflow.TBANK_MAIN, MoneyCashflow.accountOf(rub, emptyMap()))
        val text = "Перевод на 100 ₽, накоп. счет.\nБаланс 0 ₽"
        val sav = BankPush.entry((BankPush.parse("", text) as BankPush.Outcome.Money).p, at("2026-10-01"), "sasha", "", text)
        assertEquals(MoneyCashflow.TBANK_SAVINGS, MoneyCashflow.accountOf(sav, emptyMap()))
        // Получателя нет: «что» — сама копилка, а не «счет.»; «Баланс 0 ₽» — не место.
        assertEquals("Накопительный счет", sav.what)
        assertEquals("", sav.note)
        // Карта сильнее слова; не узнанная карта и пуш без слова — безымянный счёт.
        val card = rub.copy(account = "Т-Банк *1519")
        assertEquals("Т-Банк · Black Premium", MoneyCashflow.accountOf(card, mapOf("1519" to "Т-Банк · Black Premium")))
        assertEquals(MoneyCashflow.TBANK_UNNAMED, MoneyCashflow.accountOf(card, emptyMap()))
        assertEquals(MoneyCashflow.TBANK_UNNAMED, MoneyCashflow.accountOf(rub.copy(account = "Т-Банк"), emptyMap()))
        assertEquals(MoneyCashflow.TBANK_MAIN, MoneyCashflow.places(listOf(rub))[rub.id]!!.account)
    }

    // «Доступно» у кредитки — свободный лимит, а не остаток (пуш 04.10.2026 обнулял долг по ней).
    @Test fun pushAnchorOnlyOnAssetAccounts() {
        val cards = mapOf("7777" to "Т-Банк · Платинум", "1519" to "Т-Банк · Black Premium")
        val credit = "Перевод на 100 ₽, счет карты *7777\nДоступно 0 ₽"
        assertNull(MoneyCashflow.pushAnchor("Перевод", credit, at("2026-10-04"), BankPush.key("Перевод", credit), cards))
        val debit = "Покупка на 100 ₽, счет карты *1519\nДоступно 5 000 ₽"
        val a = MoneyCashflow.pushAnchor("Лавка", debit, at("2026-10-04"), BankPush.key("Лавка", debit), cards)!!
        assertEquals("Т-Банк · Black Premium", a.account)
        assertEquals(500_000L, a.kop)
        assertEquals(setOf("push-" + BankPush.key("Лавка", debit)), a.covers)
        assertEquals(MoneyCashflow.Origin.PUSH, a.origin)
        // Пуш без карты: «счет RUB» — якорь главного счёта; неузнанная карта — не гадаем.
        val sbp = "Оплата через СБП на 100 ₽, счет RUB\nДоступно 4 900 ₽"
        assertEquals(MoneyCashflow.TBANK_MAIN, MoneyCashflow.pushAnchor("Ozon", sbp, at("2026-10-04"), BankPush.key("Ozon", sbp), cards)!!.account)
        assertNull(MoneyCashflow.pushAnchor("Лавка", debit, at("2026-10-04"), BankPush.key("Лавка", debit), emptyMap()))
        // Долговой счёт стоит в балансе от снимка: пуш с «Доступно 0» его не обнуляет.
        val snap = MoneyCashflow.Anchor("Т-Банк · Платинум", at("2026-09-23"), -50_000_000, "снимок")
        val stmt = e("stmt", "2026-09-01", -1, "own", acc = "Платинум *7777")
        val push = BankPush.entry((BankPush.parse("Перевод", credit) as BankPush.Outcome.Money).p, at("2026-10-04"), "sasha", "Перевод", credit)
        val list = listOf(stmt, push)
        val fromList = MoneyCashflow.cardMap(list)
        assertEquals("Т-Банк · Платинум", fromList["7777"])
        val anchors = listOf(snap) + listOfNotNull(MoneyCashflow.pushAnchor("Перевод", credit, push.ts, BankPush.key("Перевод", credit), fromList))
        val b = MoneyCashflow.balances(list, anchors, at("2026-10-05"), at("2026-09-01")).single { it.name == "Т-Банк · Платинум" }
        assertEquals(-50_000_000L - 10_000, b.kop)
    }

    @Test fun natashaPaymentsOnZfAccountAreHerShare() {
        // Имён в тесте нет: шаблоны берутся из самого заводского справочника.
        val rules = MoneyRules.parseText(asset("money_payees.txt")).rules
        val partner = rules.filter { it.category == "zf_partner" }
        assertEquals(1, partner.size)
        assertEquals("tbiz", partner.single().source)
        val names = partner.single().pattern.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(names.size >= 2)
        for (n in names) {
            val pay = MoneyEntry(id = "z-$n", owner = "sasha", source = MoneyEntry.Source.TBIZ, ts = at("2026-09-11"), rubKop = -10_000_000, what = n, account = "ЗФ")
            assertEquals(n, "zf_partner", MoneyMatch.run(listOf(pay), rules, at("2026-09-24")).entries.single().category)
        }
        // С личной карты — по-прежнему «Доля Наташи (ЗФ)».
        assertTrue(rules.any { it.category == "zf_share" && it.source.isEmpty() })
        assertEquals("ЗФ: выплата доли Наташе", MoneyCategories.title("zf_partner"))
        assertEquals(MoneyCategories.Shelf.SERVICE, MoneyCategories.shelf("zf_partner"))
    }

    @Test fun zfLoanIsLoanOnBothSidesAndCancels() {
        val lent = MoneyEntry(id = "z1", owner = "sasha", source = MoneyEntry.Source.TBIZ, ts = at("2026-05-10"), rubKop = -20_000_000, what = "ЦАКУНОВ АЛЕКСАНДР", category = "zf_loan", account = "ЗФ")
        val back = lent.copy(id = "z2", ts = at("2026-06-10"), rubKop = 10_000_000)
        val got = e("t1", "2026-05-10", 200_000, "inc_zf")
        val repaid = e("t2", "2026-06-10", -100_000, "zf")
        val all = MoneyMatch.linkZf(listOf(lent, back, got, repaid)).associateBy { it.id }
        // Займ, а не доход и не расход ЗФ: у приходов и возвратов владельца — та же категория.
        assertEquals("zf_loan", all["t1"]!!.category)
        assertEquals("zf_loan", all["t2"]!!.category)
        val list = all.values.toList()
        val loans = listOf(
            MoneyCashflow.Anchor(MoneyCashflow.LOAN_DEBT, at("2026-01-01", 0), 0, "0"),
            MoneyCashflow.Anchor(MoneyCashflow.LOAN_ASSET, at("2026-01-01", 0), 0, "0"),
        )
        val b = MoneyCashflow.balances(list, loans, at("2026-07-01"), at("2026-01-01")).associateBy { it.name }
        assertEquals(-10_000_000L, b[MoneyCashflow.LOAN_DEBT]!!.kop)
        assertEquals(10_000_000L, b[MoneyCashflow.LOAN_ASSET]!!.kop)
        val zfAcc = setOf(MoneyCashflow.TBIZ_NAME)
        assertTrue(MoneyScope.of(true, false, list, zfAcc).showsAccount(MoneyCashflow.LOAN_DEBT))
        assertTrue(!MoneyScope.of(true, true, list, zfAcc).showsAccount(MoneyCashflow.LOAN_DEBT))
        // В ДДС личного — финансовая деятельность; при обеих кнопках пара исчезает.
        val personal = MoneyCashflow.build(list, listOf(YearMonth.of(2026, 5)), MoneyScope.of(true, false, list, zfAcc))
        assertEquals(20_000_000L, row(personal, "Займ от ЗФ получен").values[0])
        assertEquals(0L, row(personal, "Поступления").values[0])
        val both = MoneyCashflow.build(list, listOf(YearMonth.of(2026, 5)), MoneyScope.of(true, true, list, zfAcc))
        assertTrue(both.none { it.key == "zf_loan" })
    }

    @Test fun rublesEverywhere() {
        // «Надо в руб, а не в тыс. руб. И без копеек».
        assertEquals("1\u00A0782\u00A0345", MoneyFormat.k(178_234_500))
        assertEquals("380", MoneyFormat.k(38_000))
        assertEquals("−1\u00A0782", MoneyFormat.k(-178_184))
        assertEquals("+12\u00A0000", MoneyFormat.k(1_200_000, sign = true))
        assertEquals("руб", MoneyFormat.K)
    }

    @Test fun roundupsLeaveTheCardAccount() {
        val buy = e("buy", "2026-09-23", -1_782, "groceries").copy(rubKop = -178_184)
        val round = MoneyEntry(
            id = "r", owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = buy.ts + 1_000, rubKop = 1_816,
            what = "Перевод округлений", category = "roundup", account = "Накопительный счет *0110",
        )
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", buy.ts - 1, 1_000_000, "снимок")
        val bp = MoneyCashflow.balances(listOf(buy, round), listOf(anchor), buy.ts + 5_000, buy.ts - 86_400_000).first { it.name == "Т-Банк · Black Premium" }
        // Покупка 1 781,84 + округление 18,16 = 1 800 — ровно на столько меняется «Доступно».
        assertEquals(1_000_000L - 180_000L, bp.kop)
        // В ДДС и итогах округлений нет: это перемещение между своими.
        val rows = MoneyCashflow.build(listOf(buy, round), listOf(YearMonth.of(2026, 9)), false)
        assertEquals(0L, row(rows, "Поступления").values[0])
    }

    @Test fun zfCashDeskCountsOnce() {
        val manual = MoneyCashflow.parseManual(asset("money_manual.txt"), "sasha")
        val desk = MoneyCashflow.Anchor(MoneyMatch.ZF_CASH, at("2026-01-01", 0), 0, "0")
        val acc = MoneyCashflow.balances(manual, listOf(desk), at("2026-09-30"), at("2026-08-01")).first { it.name == MoneyMatch.ZF_CASH }
        // Транзит: остаток кассы всегда ноль, и в балансе её не показываем.
        assertEquals(0L, acc.kop)
        assertTrue(!MoneyScope.BOTH.showsAccount(MoneyMatch.ZF_CASH))
    }

    @Test fun loansGroupByLender() {
        fun l(id: String, d: String, rub: Long, what: String) =
            MoneyEntry(id = id, owner = "marianna", source = MoneyEntry.Source.ALFA, ts = at(d), rubKop = rub * 100, what = what, category = "loan")
        val list = listOf(
            l("a", "2026-02-08", -300_000, "Марианна Б."),
            l("b", "2026-02-08", 300_000, "Белоусова Марианна Евгеньевна"),
            l("c", "2026-03-31", 70_000, "Белоусова Марианна Евгеньевна"),
            l("d", "2026-09-18", 100_000, "Сергей Ц."),
            l("e", "2026-09-23", -100_000, "Папе: возврат долга"),
        )
        // Белоусова одна, как бы её ни писал банк; папин долг закрыт — строки нет.
        assertEquals(listOf("Марианна Белоусова" to 7_000_000L), MoneyCashflow.loansByLender(list, at("2026-09-30")))
        assertEquals(7_000_000L, MoneyCashflow.loanDebt(list, at("2026-09-30")))
        assertTrue(MoneyCashflow.isDebtAccount(MoneyCashflow.LOAN_DEBT) && MoneyCashflow.isDebtAccount("Т-Банк · Платинум"))
        assertTrue(!MoneyCashflow.isDebtAccount("Т-Банк · Black Premium"))
    }

    @Test fun belousovaIsSettled() {
        val rules = MoneyRules.parseText(asset("money_payees.txt")).rules
        fun alfa(id: String, d: String, rub: Long, what: String) =
            MoneyEntry(id = id, owner = "marianna", source = MoneyEntry.Source.ALFA, ts = at(d), rubKop = rub * 100, what = what)
        val list = listOf(
            alfa("a", "2026-03-31", 70_000, "Белоусова Марианна Евгеньевна"),
            alfa("b", "2026-06-16", 100_000, "Белоусова Марианна Евгеньевна"),
            alfa("c", "2026-07-02", 3_500, "Белоусова Марианна Евгеньевна"),
            e("s1", "2026-03-25", -70_000, "").copy(what = "Марианна Б."),
            e("s2", "2026-06-13", -100_000, "").copy(what = "Марианна Б."),
        )
        val r = MoneyMatch.run(list, rules, at("2026-09-24")).entries
        // «Марианне мы все долги отдали. Там ноль».
        assertEquals(0L, MoneyCashflow.loanDebt(r, at("2026-09-24")))
        assertEquals("inc_other", r.first { it.id == "c" }.category)
    }

    @Test fun editedManualLineReplacesOldVersion() {
        val v1 = MoneyCashflow.parseManual("22.09.2026 | 3750000 | zf_revenue | Клиент: оплата наличными | Касса ЗФ |", "sasha")
        val v2 = MoneyCashflow.parseManual("22.09.2026 | 3880000 | zf_revenue | Клиент: оплата наличными | Касса ЗФ |", "sasha")
        val (add, stale) = MoneyCashflow.syncManual(v1, v2)
        // Новая версия добавляется, прежняя вычёркивается — сентябрь не задваивается.
        assertEquals(v2.map { it.id }, add.map { it.id })
        assertEquals(v1.map { it.id }.toSet(), stale)
        // Тот же файл второй раз — ничего.
        val (add2, stale2) = MoneyCashflow.syncManual(v2, v2)
        assertTrue(add2.isEmpty() && stale2.isEmpty())
    }

    @Test fun everyCellExplainsItself() {
        val months = listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9))
        val rows = MoneyCashflow.build(entries, months, withZf = true)
        // Каждая цифра — сумма своих операций: окно «из чего состоит» сходится с таблицей до копейки.
        for (r in rows.filter { it.pick != null }) {
            months.forEachIndexed { i, m ->
                assertEquals("${r.title} ${m}", r.values[i], MoneyCashflow.cellItems(entries, m, MoneyScope.BOTH, r).sumOf { it.kop })
            }
        }
        val food = rows.first { it.title == "Продукты" }
        assertEquals(listOf("food"), MoneyCashflow.cellItems(entries, YearMonth.of(2026, 9), MoneyScope.BOTH, food).map { it.entry.id })
    }
}
