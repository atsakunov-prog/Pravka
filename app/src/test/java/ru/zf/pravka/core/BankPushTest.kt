package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Пуши Т-Банка и бота «Плати по миру» — тексты с настоящих уведомлений
// владельца (23.09.2026), суммы и карты как были; склейка с выпиской и
// «потерянный» пуш — на вымышленных строках.
class BankPushTest {

    private fun money(title: String, text: String): BankPush.Parsed {
        val out = BankPush.parse(title, text)
        assertTrue("ждал операцию, вышло $out", out is BankPush.Outcome.Money)
        return (out as BankPush.Outcome.Money).p
    }

    @Test fun purchaseTitleIsMerchant() {
        val p = money("ВкусВилл", "Покупка на 1 781,84 ₽, счет карты *1519\nДоступно 13 630,02 ₽")
        assertEquals(-178_184L, p.rubKop)
        assertEquals("ВкусВилл", p.what)
        assertEquals("1519", p.card)
    }

    @Test fun roundAndOneDigitKopecks() {
        assertEquals(-250_000L, money("Мосспортобъект", "Покупка на 2 500 ₽, счет карты *1519\nДоступно 15 430,02 ₽").rubKop)
        assertEquals(-68_080L, money("Аптека 36,6", "Покупка на 680,8 ₽, счет карты *1519\nДоступно 84 130,02 ₽").rubKop)
        // Неразрывные пробелы в тысячах — как их ставит шторка.
        assertEquals(-112_000L, money("Buba Sushi", "Покупка на 1 120 ₽, счет карты *1519\nДоступно 80 180,02 ₽").rubKop)
    }

    @Test fun transferRecipientIsAfterLastDot() {
        val p = money("МКБ (Московский Кредитный Банк)", "Перевод на 38 000 ₽, от Марианна Ц., счет карты *0292. Марианна Ц.\nДоступно 22 630,02 ₽")
        assertEquals(-3_800_000L, p.rubKop)
        assertEquals("Марианна Ц.", p.what)
        assertEquals("0292", p.card)
        assertTrue(p.note, p.note.contains("в МКБ"))
        assertTrue(p.note, p.note.contains("картой: Марианна Ц."))

        val d = money("", "Перевод на 1 500 ₽, от Марианна Ц., счет карты *0292. Диана Т.\nДоступно 84 130,02 ₽")
        assertEquals("Диана Т.", d.what)
        assertEquals(-150_000L, d.rubKop)
    }

    @Test fun sbpToPlati() {
        val p = money("Плати по миру", "Оплата через СБП на 9 096,3 ₽, счет RUB\nДоступно 70 743,72 ₽")
        assertEquals(-909_630L, p.rubKop)
        assertEquals("Плати по миру", p.what)
        assertEquals("", p.card)
    }

    @Test fun oldStyleAndDecline() {
        // «овер-драфта» в шторке — мягкий перенос U+00AD, не дефис.
        val p = money("Покупка", "Карта *8958. 14.00 RUB. Остаток овер­драфта: 1176.26 RUB. YANDEX*HELP")
        assertEquals(-1_400L, p.rubKop)
        assertEquals("YANDEX*HELP", p.what)
        assertEquals("8958", p.card)
        assertTrue(BankPush.parse("Платежи", "Отказ YANDEX*4121*TAXI. Карта *8958. Недостаточно средств.") is BankPush.Outcome.Skip)
        assertTrue(BankPush.parse("Т-Банк", "Кэшбэк за сентябрь уже ждёт вас") is BankPush.Outcome.Skip)
    }

    @Test fun incomeIsPositive() {
        assertEquals(500_000L, money("Т-Банк", "Пополнение на 5 000 ₽, счет RUB\nДоступно 10 000 ₽").rubKop)
    }

    @Test fun atmCashInFromOwnersPhone() {
        // Настоящий пуш владельца (25.09.2026): заголовка нет, место — второй строкой.
        val text = "Пополнение на 195 000 ₽, счет RUB.\nБанкомат.\nДоступно 232 483,72 ₽"
        // Заголовок пуст, это банк или повторяет действие — место берётся со второй строки.
        for (title in listOf("", "Т-Банк", "Пополнение", "Пополнение счета")) {
            val p = money(title, text)
            assertEquals(19_500_000L, p.rubKop)
            assertEquals("Банкомат", p.what)
            assertEquals("", p.card)
            assertEquals(23_248_372L, p.balanceKop)
        }
        // Справочник узнаёт банкомат: сумма — из кошелька, не доход.
        val e = BankPush.entry(money("", text), ts = 1L, owner = "sasha", title = "", text = text)
        assertEquals("cash", MoneyRules.classify(e, emptyList())?.category)
    }

    @Test fun purchaseKeepsMerchantTitleEvenWithDetailLine() {
        val p = money("ВкусВилл", "Покупка на 300 ₽, счет карты *1519\nКутузовский 12\nДоступно 1 000 ₽")
        assertEquals("ВкусВилл", p.what)
        assertTrue(p.note, p.note.contains("Кутузовский 12"))
    }

    @Test fun keyIsStableAndBalanceSeparatesTwins() {
        val a = BankPush.key("ВкусВилл", "Покупка на 300 ₽, счет карты *1519\nДоступно 1 000 ₽")
        assertEquals(a, BankPush.key("ВкусВилл", "Покупка на 300 ₽, счет карты *1519\nДоступно 1 000 ₽"))
        assertNotEquals(a, BankPush.key("ВкусВилл", "Покупка на 300 ₽, счет карты *1519\nДоступно 700 ₽"))
    }

    @Test fun sources() {
        assertEquals(BankPush.From.TBANK, BankPush.from("com.idamob.tinkoff.android", "ВкусВилл"))
        assertEquals(BankPush.From.PLATI_CHAT, BankPush.from("org.telegram.messenger", "Плати по всему миру"))
        assertEquals(BankPush.From.OTHER, BankPush.from("org.telegram.messenger", "Мама"))
        assertEquals(BankPush.From.OTHER, BankPush.from("com.google.android.calendar", "Плати по миру"))
    }

    // ---- Чат бота из уведомлений: знак валюты ПЕРЕД числом ----

    @Test fun platiNotificationFormat() {
        val chat = """
            23.09.2026 18:50
            Пополнение карты *2389 на сумму ${'$'}100.00 прошло успешно

            23.09.2026 18:59
            Карта: *2389.
            Попытка оплаты: ${'$'}0.00, ANTHROPIC.
            Оплата не прошла по причине: "Неизвестная ошибка"
            Остаток: ${'$'}107.21.

            23.09.2026 18:59
            Покупка: ${'$'}87.20. Карта: *2389. ANTHROPIC. Остаток: ${'$'}20.01.
        """.trimIndent()
        val ev = PlatiChat.parse(chat)
        assertEquals(2, ev.size)
        val topUp = ev[0] as PlatiChat.Event.TopUp
        assertEquals(10_000L, topUp.minor)
        assertEquals("USD", topUp.currency)
        val buy = ev[1] as PlatiChat.Event.Purchase
        assertEquals(8_720L, buy.minor)
        assertEquals("ANTHROPIC", buy.merchant)
        assertEquals(2_001L, buy.balanceMinor)
        // Каноническая запись разбирается в те же события.
        assertEquals(ev.map { it.javaClass }, PlatiChat.parse(PlatiChat.canonical(ev)).map { it.javaClass })
    }

    // ---- Пуш ↔ выписка ----

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun push(id: String, what: String, rub: Long, ts: Long, card: String = "1519") = MoneyEntry(
        id = id, owner = "sasha", source = MoneyEntry.Source.PUSH, ts = ts, rubKop = rub, what = what,
        account = "Т-Банк *$card",
    )

    private fun bank(id: String, what: String, rub: Long, ts: Long, card: String = "1519") = MoneyEntry(
        id = id, owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = ts, rubKop = rub, what = what,
        account = "Black Premium *$card",
    )

    @Test fun statementReplacesPushAndInheritsOwnerAnswer() {
        val entries = listOf(
            push("p1", "Диана Т.", -150_000, t0).copy(category = "kids_stuff", categoryBy = MoneyEntry.CategoryBy.OWNER),
            push("p2", "ВкусВилл", -178_184, t0 + 3_600_000),
            // Та же сумма, но другая карта — не пара.
            bank("b0", "ВкусВилл", -178_184, t0 + 3_600_000, card = "0292"),
            bank("b1", "Диана Т.", -150_000, t0 + 60_000),
            bank("b2", "ВкусВилл", -178_184, t0 + 3_660_000),
        )
        val r = MoneyMatch.run(entries, emptyList(), t0 + 2 * day)
        assertEquals(2, r.pushLinked)
        val byId = r.entries.associateBy { it.id }
        assertEquals("b1", byId["p1"]!!.replacedBy)
        assertEquals("b2", byId["p2"]!!.replacedBy)
        assertEquals("kids_stuff", byId["b1"]!!.category)
        assertEquals(MoneyEntry.CategoryBy.OWNER, byId["b1"]!!.categoryBy)
        // Заменённый пуш в итоги не идёт: операция считается один раз.
        assertTrue(!byId["p1"]!!.live())
        assertEquals(3, r.entries.count { it.live() })
    }

    @Test fun voiceOnPushMovesToStatement() {
        val v = MoneyEntry(
            id = "v1", owner = "sasha", source = MoneyEntry.Source.VOICE, ts = t0, rubKop = -38_000, what = "кофе",
            category = "cafe", categoryBy = MoneyEntry.CategoryBy.OWNER,
        )
        val first = MoneyMatch.run(listOf(v, push("p1", "Surf Coffee", -38_000, t0 + 60_000)), emptyList(), t0 + day)
        assertEquals("p1", first.entries.first { it.id == "v1" }.matchId)
        val second = MoneyMatch.run(first.entries + bank("b1", "Surf Coffee", -38_000, t0 + 90_000), emptyList(), t0 + day)
        val byId = second.entries.associateBy { it.id }
        assertEquals("b1", byId["p1"]!!.replacedBy)
        assertEquals("b1", byId["v1"]!!.matchId)
        assertEquals("v1", byId["b1"]!!.matchId)
        assertEquals("cafe", byId["b1"]!!.category)
        assertEquals(1, second.entries.count { it.live() })
    }

    @Test fun pushWithoutStatementPairIsAskedOnlyWhenCovered() {
        val lost = push("p1", "Кофейня", -25_000, t0 + day)
        // Выписки ещё нет — пуш просто живёт, вопросов «отменили?» нет.
        val alone = MoneyMatch.run(listOf(lost), emptyList(), t0 + 10 * day)
        assertTrue(alone.entries.single().question != MoneyMatch.ORPHAN_PUSH)
        // Выписка покрыла тот день и ушла дальше — а пары нет.
        val covered = MoneyMatch.run(
            listOf(lost, bank("b1", "ВкусВилл", -10_000, t0), bank("b2", "ВкусВилл", -20_000, t0 + 5 * day)),
            emptyList(), t0 + 10 * day,
        )
        assertEquals(MoneyMatch.ORPHAN_PUSH, covered.entries.first { it.id == "p1" }.question)
    }
}
