package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Чат «Плати по миру» — в том виде, в каком владелец его копирует (23.09.2026):
// старые покупки без даты, новые — с временем над сообщением, отказы,
// реклама, коды привязки и кусок, вставленный внахлёст. Суммы вымышленные.
class PlatiChatTest {

    private val chat = """
Плати по всему миру:
Заявка на пополнение карты *7000 принята, ожидаем оплаты: 10000.00 ₽

Информация об оплате принята. После успешного пополнения вы получите уведомление, и баланс вашей карты *7000 изменится

Пополнение карты *7000 на сумму 100.00 € прошло успешно

Покупка: 24.25 €. Карта: *7000. GAMMA.APP. Остаток: 75.75 €.

Покупка: 20.24 €. Карта: *7000. ZWIFT, INC.. Остаток: 55.51 €.

01.03.2026 14:28
Оплата: € 117.26
GOOGLE*WORKSPACE

❌ Операция отклонена: недостаточно средств

Карта: ****7000
Баланс: € 55.51

Карта: *7000.
Попытка оплаты: €9.99, GOOGLE*GOOGLE PLAY APP.
Оплата не прошла по причине: "Неверные данные карты"
Списана комиссия €0.25.
Остаток: €55.26.

Ваш код для привязки карты к
Apple Pay / Google Pay: 123456
Карта: *7000

💰 А ещё среди клиентов сейчас проходит розыгрыш на 100.000 рублей. Присоединяйтесь и вы!

Заявка на пополнение карты *7000 принята, ожидаем оплаты: 5000.00 ₽

Заявка на пополнение карты *7000 принята, ожидаем оплаты: 5000.00 ₽

Пополнение карты *7000 на сумму 50.00 € прошло успешно

Плати по всему миру:
Пополнение карты *7000 на сумму 50.00 € прошло успешно

Покупка: 20.24 €. Карта: *7000. ZWIFT, INC.. Остаток: 55.51 €.

Покупка: 70.51 €. Карта: *7000. ZOOM.COM 888-799-966. Остаток: 34.75 €.

Заявка на пополнение карты *7000 принята, ожидаем оплаты: 9999.99 ₽

04.09.2026 11:19
Покупка: 120.00 $. Карта: *2000. ANTHROPIC* CLAUDE SUB. Остаток: 142.03 $.
""".trimIndent()

    @Test fun keepsOnlyMoneyAndDropsNoise() {
        val ev = PlatiChat.parse(chat)
        val purchases = ev.filterIsInstance<PlatiChat.Event.Purchase>()
        // ZWIFT внахлёст с тем же остатком — одна покупка, не две.
        assertEquals(listOf("GAMMA.APP", "ZWIFT, INC.", "ZOOM.COM 888-799-966", "ANTHROPIC* CLAUDE SUB"), purchases.map { it.merchant })
        assertEquals(2425L, purchases[0].minor)
        assertEquals("EUR", purchases[0].currency)
        assertEquals("USD", purchases[3].currency)
        // Одна комиссия (отказ «недостаточно средств» — не деньги).
        val fees = ev.filterIsInstance<PlatiChat.Event.Fee>()
        assertEquals(1, fees.size)
        assertEquals(25L, fees[0].minor)
        // Повторённая заявка на 5000 — одна; пополнение на 50 внахлёст — одно.
        assertEquals(3, ev.filterIsInstance<PlatiChat.Event.TopUpRequest>().size)
        assertEquals(2, ev.filterIsInstance<PlatiChat.Event.TopUp>().size)
        // Код привязки нигде не всплыл.
        assertTrue(ev.none { it.toString().contains("123456") })
        // Время над сообщением взято у сентябрьской покупки.
        assertTrue(purchases[3].ts > 0)
        assertEquals(0L, purchases[0].ts)
    }

    @Test fun rateAndTimeComeFromTinkoff() {
        val ev = PlatiChat.parse(chat)
        val t1 = 1_771_300_000_000L
        val t2 = t1 + 86_400_000L * 10
        val debits = listOf(
            PlatiChat.BankDebit("t:1", t1, 1_000_000L), // 10 000,00 ₽ — первая заявка
            PlatiChat.BankDebit("t:2", t2, 500_000L),   // 5 000,00 ₽ — вторая
        )
        val b = PlatiChat.build(ev, debits, importedAt = 0L)
        // Обе строки Тинькова узнаны как пополнения, заявка на 9 999,99 — брошенная.
        assertEquals(mapOf("t:1" to "plati", "t:2" to "plati"), b.bankLinks)
        assertEquals(1, b.unpaidRequests)
        val gamma = b.entries.first { it.what == "GAMMA.APP" }
        // 10 000 ₽ за 100 € → 100 ₽ за евро → 24,25 € = 2 425 ₽.
        assertEquals(-242500L, gamma.rubKop)
        assertEquals(MoneyEntry.RubBasis.TOPUP, gamma.rubBasis)
        assertEquals(t1, gamma.ts)
        // Zoom — после второго пополнения: курс тот же 100 ₽/€, время — вторая оплата.
        val zoom = b.entries.first { it.what.startsWith("ZOOM") }
        assertEquals(-705100L, zoom.rubKop)
        assertEquals(t2, zoom.ts)
        // Комиссия — в «Банки и комиссии», сразу.
        assertEquals("fees", b.entries.first { it.what.startsWith("Комиссия") }.category)
        // Долларовая карта без своего пополнения — прикидкой и честно помечена.
        val claude = b.entries.first { it.what.startsWith("ANTHROPIC") }
        assertEquals(MoneyEntry.RubBasis.CBR_PRELIM, claude.rubBasis)
        assertTrue(claude.timeKnown)
    }

    @Test fun recognisesChat() {
        assertTrue(PlatiChat.looksLike(chat))
        assertTrue(!PlatiChat.looksLike("кофе триста"))
    }

    @Test fun canonicalKeepsMoneyOnlyAndRoundTrips() {
        val ev = PlatiChat.parse(chat)
        val text = PlatiChat.canonical(ev)
        // Ни кодов привязки, ни рекламы в том, что хранится.
        assertTrue(!text.contains("123456"))
        assertTrue(!text.contains("розыгрыш"))
        val again = PlatiChat.parse(text)
        fun strip(e: PlatiChat.Event) = when (e) {
            is PlatiChat.Event.Purchase -> e.copy(seq = 0)
            is PlatiChat.Event.TopUpRequest -> e.copy(seq = 0)
            is PlatiChat.Event.TopUp -> e.copy(seq = 0)
            is PlatiChat.Event.Fee -> e.copy(seq = 0)
            is PlatiChat.Event.IssueFee -> e.copy(seq = 0)
        }
        assertEquals(ev.map(::strip), again.map(::strip))
        // Склейка старого и нового чата внахлёст не удваивает ничего.
        val twice = PlatiChat.parse(text + "\n\n" + text)
        assertEquals(ev.size, twice.size)
    }
}
