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

    @Test fun ownersRealAtmPushWithNonBreakingSpaces() {
        // Сырьё с телефона владельца (25.09.2026, «Пойманный пуш»): заголовок пуст,
        // место — на ТОЙ ЖЕ строке, после точки и НЕРАЗРЫВНОГО пробела.
        for (sp in listOf("\u00A0", "\u202F", " ")) {
            val text = "Пополнение на 195${sp}000 ₽, счет RUB.${sp}Банкомат.\nДоступно${sp}232${sp}483,72 ₽"
            val p = money("", text)
            assertEquals(19_500_000L, p.rubKop)
            assertEquals("Банкомат", p.what)
            assertEquals(23_248_372L, p.balanceKop)
            val e = BankPush.entry(p, ts = 1L, owner = "sasha", title = "", text = text)
            assertEquals("cash", MoneyRules.classify(e, emptyList())?.category)
        }
    }

    @Test fun transferRecipientAfterNonBreakingSpace() {
        // Те же неразрывные пробелы у перевода — получатель терялся, в журнале «Перевод».
        val text = "Перевод на 1\u00A0500 ₽, от Марианна Ц., счет карты *0292.\u00A0Диана Т.\nДоступно\u00A084\u00A0130,02 ₽"
        val p = money("", text)
        assertEquals("Диана Т.", p.what)
        assertEquals("0292", p.card)
        assertEquals(-150_000L, p.rubKop)
        assertEquals(8_413_002L, p.balanceKop)
    }

    @Test fun zeroWidthAndInvisiblesAreNamed() {
        val p = money("", "Пополнение на 195 000 ₽, счет RUB.\u200B Банкомат.\nДоступно 1 000 ₽")
        assertEquals("Банкомат", p.what)
        assertEquals("U+00A0 ×1", BankPush.invisibles("RUB.\u00A0Банкомат."))
        assertEquals("", BankPush.invisibles("обычный текст\nвторая строка"))
    }

    @Test fun keyStaysOnOriginalTextSoSavedEntriesKeepTheirIds() {
        // Пробелы нормализуются только для разбора: номер пуша — по исходнику.
        val nbsp = "Пополнение на 195\u00A0000 ₽, счет RUB.\u00A0Банкомат."
        assertNotEquals(BankPush.key("", nbsp), BankPush.key("", nbsp.replace('\u00A0', ' ')))
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

    // ---- Альфа и МКБ (08.10.2026): общий осторожный разбор ----
    //
    // Настоящих пушей Альфы и МКБ в репозитории ещё нет. Все тексты ниже —
    // ФОРМА ПРЕДПОЛАГАЕМАЯ, НЕ С НАСТОЯЩЕГО ПУША: проверяют правила разбора
    // (действие → сумма в рублях, отсев кодов, отказов, остатков и валюты), а
    // не формат банка. Точный разбор напишется по сырью из архива
    // (`money.push`), и шаг переразбора истории выведет записи заново.

    private fun generic(from: BankPush.From, title: String, text: String): BankPush.Parsed {
        val out = BankPush.parseGeneric(from, title, text)
        assertTrue("ждал операцию, вышло $out", out is BankPush.Outcome.Money)
        return (out as BankPush.Outcome.Money).p
    }

    private fun skipped(from: BankPush.From, title: String, text: String): BankPush.Outcome.Skip {
        val out = BankPush.parseGeneric(from, title, text)
        assertTrue("ждал отсев, вышло $out", out is BankPush.Outcome.Skip)
        return out as BankPush.Outcome.Skip
    }

    @Test fun alfaAndMkbSourcesByPackageAndSmsSender() {
        assertEquals(BankPush.From.ALFA, BankPush.from("ru.alfabank.mobile.android", "Покупка"))
        assertEquals(BankPush.From.MKB, BankPush.from("ru.mkb.mobile", "МКБ"))
        // SMS: заголовок уведомления — отправитель.
        assertEquals(BankPush.From.ALFA, BankPush.from("com.google.android.apps.messaging", "Alfa-Bank"))
        assertEquals(BankPush.From.ALFA, BankPush.from("com.google.android.apps.messaging", "AlfaBank"))
        assertEquals(BankPush.From.ALFA, BankPush.from("com.samsung.android.messaging", "Альфа-Банк"))
        assertEquals(BankPush.From.MKB, BankPush.from("com.google.android.apps.messaging", "MKB"))
        assertEquals(BankPush.From.MKB, BankPush.from("com.android.mms", "Московский кредитный банк"))
        assertEquals(BankPush.From.OTHER, BankPush.from("com.google.android.apps.messaging", "Мама"))
        // Пакет решает первым: пуш Т-Банка о переводе в МКБ — Т-Банк.
        assertEquals(BankPush.From.TBANK, BankPush.from("com.idamob.tinkoff.android", "МКБ (Московский Кредитный Банк)"))
        // «МКБ» в заголовке не SMS-приложения — не банк.
        assertEquals(BankPush.From.OTHER, BankPush.from("org.telegram.messenger", "МКБ"))
        assertEquals(BankPush.From.OTHER, BankPush.from("com.whatsapp", "Alfa-Bank"))
    }

    @Test fun alfaPurchaseWithDottedCard() {
        // Форма предполагаемая, не с настоящего пуша.
        val text = "Покупка 1 299,90 ₽ в ВкусВилл. Карта ··8625. Баланс: 12 345,67 ₽"
        val p = generic(BankPush.From.ALFA, "Альфа-Банк", text)
        assertEquals(-129_990L, p.rubKop)
        assertEquals("ВкусВилл", p.what)
        assertEquals("8625", p.card)
        val e = BankPush.entry(p, ts = 1L, owner = "marianna", title = "Альфа-Банк", text = text, from = BankPush.From.ALFA)
        assertEquals("Альфа-Банк *8625", e.account)
        assertEquals("push-" + BankPush.key("Альфа-Банк", text), e.id)
        assertEquals(MoneyEntry.Source.PUSH, e.source)
        assertEquals("marianna", e.owner)
        assertEquals(BankPush.From.ALFA, BankPush.fromAccount(e.account))
    }

    @Test fun alfaSmsWithNbspAbbreviatedRublesAndMerchantInNextSentence() {
        // Форма предполагаемая, не с настоящего пуша: неразрывные пробелы, «р.», магазин — следующим предложением.
        val p = generic(BankPush.From.ALFA, "Alfa-Bank", "Списание 1\u00A0500р. с карты *8625. Яндекс Такси. Остаток 10\u00A0000р.")
        assertEquals(-150_000L, p.rubKop)
        assertEquals("Яндекс Такси", p.what)
        assertEquals("8625", p.card)
    }

    @Test fun titleIsVerbOrMerchant() {
        // Форма предполагаемая, не с настоящего пуша: действие — заголовком.
        val a = generic(BankPush.From.ALFA, "Покупка", "1 500 ₽ в MAGNIT, карта ··8625")
        assertEquals(-150_000L, a.rubKop)
        assertEquals("MAGNIT", a.what)
        // Магазин — заголовком, в тексте только действие и карта.
        val b = generic(BankPush.From.ALFA, "Пятёрочка", "Покупка 350 ₽ ··8625")
        assertEquals("Пятёрочка", b.what)
        assertEquals("8625", b.card)
        // Ни магазина, ни заголовка, кроме банка, — «что» словом действия, СБП — в заметке.
        val c = generic(BankPush.From.MKB, "МКБ", "Оплата СБП 2 500,00 RUB. Доступно 3 000,00 RUB")
        assertEquals(-250_000L, c.rubKop)
        assertEquals("Оплата СБП", c.what)
        assertTrue(c.note, c.note.contains("СБП"))
    }

    @Test fun mkbIncomeFromPerson() {
        // Форма предполагаемая, не с настоящего пуша.
        val text = "Поступление 15 000,00 RUB на карту *4321 от Иван И. Доступно 20 000,00 RUB"
        val p = generic(BankPush.From.MKB, "MKB", text)
        assertEquals(1_500_000L, p.rubKop)
        assertEquals("Иван И.", p.what)
        // «на карту» у поступления — своя карта.
        assertEquals("4321", p.card)
        val e = BankPush.entry(p, ts = 1L, owner = "marianna", title = "MKB", text = text, from = BankPush.From.MKB)
        assertEquals("МКБ *4321", e.account)
        assertEquals(BankPush.From.MKB, BankPush.fromAccount(e.account))
        assertEquals("МКБ", BankPush.entry(p.copy(card = ""), 1L, "marianna", "MKB", text, BankPush.From.MKB).account)
    }

    @Test fun transfersAreDirectedOrSkipped() {
        // Формы предполагаемые, не с настоящих пушей.
        // Исходящий: карта получателя («на карту ··1111») — не своя карта.
        val out = generic(BankPush.From.ALFA, "Альфа-Банк", "Перевод 5 000 ₽ на карту ··1111 Иван И. Баланс 100 ₽")
        assertEquals(-500_000L, out.rubKop)
        assertEquals("", out.card)
        assertEquals("Иван И.", out.what)
        // Входящий — названный прямо.
        val inc = generic(BankPush.From.ALFA, "Альфа-Банк", "Входящий перевод 3 000 ₽ от Иван И.")
        assertEquals(300_000L, inc.rubKop)
        assertEquals("Иван И.", inc.what)
        // «Перевод … от» и «перевод … зачислен» — ушло или пришло, не ясно: записи нет.
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Перевод 5 000 ₽ от Иван И.")
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Перевод 5 000 ₽ зачислен на карту ··8625")
        // Пополнение телефона — трата, а не доход: не гадаем.
        skipped(BankPush.From.MKB, "МКБ", "Пополнение телефона 500 ₽")
    }

    @Test fun genericSkipsCodesDeclinesBalancesForeignAndAds() {
        // Формы предполагаемые, не с настоящих пушей.
        val code = skipped(BankPush.From.ALFA, "Альфа-Банк", "Код для входа в Альфа-Онлайн: 4829. Никому не сообщайте")
        assertTrue(code.secret)
        // Код с суммой — всё равно не операция.
        val code3ds = skipped(BankPush.From.MKB, "MKB", "Код подтверждения покупки на 1 500 ₽ в OZON: 482913")
        assertTrue(code3ds.secret)
        assertTrue(!BankPush.mask("Код подтверждения покупки на 1 500 ₽ в OZON: 482913").contains("482913"))
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Вход в приложение с нового устройства")
        // Отказ.
        assertTrue(!skipped(BankPush.From.ALFA, "Альфа-Банк", "Отказ. Покупка 500 ₽ MAGNIT. Недостаточно средств").secret)
        // Остаток — не сумма операции.
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Баланс карты ··8625: 12 345 ₽")
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Покупка в MAGNIT. Баланс 1 000 ₽")
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Покупка MAGNIT, баланс 1 000 ₽")
        // Не рубли — рубли скажет выписка.
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Покупка 25,00 USD в AMAZON. Карта ··8625")
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Покупка 25 $ (2 400 ₽) AMAZON")
        // Реклама с суммой.
        skipped(BankPush.From.ALFA, "Альфа-Банк", "Оплата покупок частями до 50 000 ₽ — оформите в приложении")
        // «Код авторизации» операции — не код входа: запись есть.
        assertEquals(-50_000L, generic(BankPush.From.MKB, "МКБ", "Покупка 500 ₽ MAGNIT. Код авторизации: 123456").rubKop)
        // Номер карты рядом с суммой не склеивается с ней в одно число.
        assertEquals(-50_000L, generic(BankPush.From.ALFA, "Альфа-Банк", "Покупка ··8625 500 ₽ MAGNIT").rubKop)
    }

    @Test fun smsTwinOfAppPushIsTheSameOperation() {
        // Альфа и МКБ шлют и пуш, и SMS об одной покупке: вторая запись — повтор.
        val app = MoneyEntry(id = "push-a", owner = "marianna", source = MoneyEntry.Source.PUSH, ts = t0, rubKop = -150_000, what = "MAGNIT", account = "Альфа-Банк *8625")
        val sms = app.copy(id = "push-b", ts = t0 + 40_000, what = "Магнит")
        val caught = listOf("ru.alfabank.mobile.android" to app)
        assertEquals(app, BankPush.twin(sms, "com.google.android.apps.messaging", caught))
        // Из того же приложения — две покупки подряд, не повтор.
        assertEquals(null, BankPush.twin(sms, "ru.alfabank.mobile.android", caught))
        // Другая сумма, другая карта, другой банк, далеко по времени — не повтор.
        assertEquals(null, BankPush.twin(sms.copy(rubKop = -150_001), "com.google.android.apps.messaging", caught))
        assertEquals(null, BankPush.twin(sms.copy(account = "Альфа-Банк *1111"), "com.google.android.apps.messaging", caught))
        assertEquals(null, BankPush.twin(sms.copy(account = "МКБ *8625"), "com.google.android.apps.messaging", caught))
        assertEquals(null, BankPush.twin(sms.copy(ts = t0 + 30 * 60_000), "com.google.android.apps.messaging", caught))
        // Без карты у одного из двух — карты не спорят.
        assertEquals(app, BankPush.twin(sms.copy(account = "Альфа-Банк"), "com.google.android.apps.messaging", caught))
    }

    @Test fun tbankOnThisPhoneByDefaultOnlyOnOwners() {
        assertTrue(BankPush.catchTbank(setting = null, ownerPhone = true))
        assertTrue(!BankPush.catchTbank(setting = null, ownerPhone = false))
        assertTrue(BankPush.catchTbank(setting = true, ownerPhone = false))
        assertTrue(!BankPush.catchTbank(setting = false, ownerPhone = true))
    }

    @Test fun rawOfAlfaAndMkbOperationsIsKeptBeyondTheSkippedCap() {
        assertTrue(BankPush.keepRaw("ru.alfabank.mobile.android", "Альфа-Банк", "Что-то новое на 1 500 ₽"))
        assertTrue(BankPush.keepRaw("com.google.android.apps.messaging", "MKB", "Оплата 500р."))
        assertTrue(!BankPush.keepRaw("ru.alfabank.mobile.android", "Альфа-Банк", "Обновите приложение"))
        assertTrue(!BankPush.keepRaw("com.idamob.tinkoff.android", "Т-Банк", "Кэшбэк 100 ₽"))
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
