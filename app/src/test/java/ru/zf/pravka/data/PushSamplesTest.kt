package ru.zf.pravka.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Сбор образцов денежных уведомлений (25.09.2026): берём то, что похоже на
// деньги, — по пакету банка или по сумме в рублях, — и не копим прочее.
class PushSamplesTest {

    @Test
    fun `банк по пакету — даже без суммы в тексте`() {
        assertTrue(PushSamples.looksLikeMoney("ru.alfabank.mobile.android", "Альфа-Банк", "Вход в приложение"))
        assertTrue(PushSamples.looksLikeMoney("ru.mkb.mobile", "МКБ", "Код подтверждения"))
    }

    @Test
    fun `сумма в рублях — из любого приложения`() {
        assertTrue(PushSamples.looksLikeMoney("com.example.shop", "Покупка", "Списано 1 250,00 ₽"))
        assertTrue(PushSamples.looksLikeMoney("org.telegram.messenger", "Плати по миру", "Пополнение 5000 руб"))
    }

    @Test
    fun `обычное уведомление не копится`() {
        assertFalse(PushSamples.looksLikeMoney("org.telegram.messenger", "Марианна", "Буду в семь"))
        assertFalse(PushSamples.looksLikeMoney("com.whatsapp", "Серёжа", "У нас 3 урока"))
    }
}
