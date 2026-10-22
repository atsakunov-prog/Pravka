package ru.zf.pravka.data

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.provider.ClaudeProvider

/**
 * Очередь сказанного без связи (22.10.2026): что считается обрывом (в очередь) и что — ответом модели (мимо очереди),
 * и что файл очереди переживает запись и чтение без потерь — фраза, время, снимок, запись ленты.
 */
class PendingTakesTest {

    @Test
    fun `обрыв связи — в очередь`() {
        assertTrue(PendingTakes.isNetwork(IOException("Не достучались до Claude: соединение не установилось.")))
        assertTrue(PendingTakes.isNetwork(SocketTimeoutException("timeout")))
        assertTrue(PendingTakes.isNetwork(ClaudeProvider.ApiException("529 overloaded", retryable = true)))
    }

    @Test
    fun `ответ модели — не в очередь`() {
        assertFalse(PendingTakes.isNetwork(IllegalStateException("Еды в сказанном не нашлось")))
        assertFalse(PendingTakes.isNetwork(ClaudeProvider.ApiException("400 bad request")))
        assertFalse(PendingTakes.isNetwork(IllegalArgumentException("Ни слов, ни снимка — разбирать нечего")))
    }

    @Test
    fun `файл очереди — туда и обратно без потерь`() {
        val list = listOf(
            PendingTakes.Take(1L, PendingTakes.Kind.FOOD, "с утра моя стандартная каша", 1_761_118_700_000L),
            PendingTakes.Take(2L, PendingTakes.Kind.FOOD, "", 1_761_118_800_000L, source = "photo", photo = "2.jpg"),
            PendingTakes.Take(3L, PendingTakes.Kind.MONEY, "кофе 380", 1_761_118_900_000L, tries = 2, error = "timeout"),
            PendingTakes.Take(4L, PendingTakes.Kind.ZASECHKA, "сел за отчёт", 1_761_119_000_000L, entryId = 77L),
        )
        assertEquals(list, PendingTakes.parse(PendingTakes.toJson(list)))
    }

    @Test
    fun `незнакомый вид в файле пропускается, остальное читается`() {
        val text = """[{"id": 1, "kind": "body", "text": "подтянулся 5 раз", "at": 1},
                       {"id": 2, "kind": "money", "text": "такси 600", "at": 2}]"""
        val got = PendingTakes.parse(text)
        assertEquals(listOf(2L), got.map { it.id })
        assertEquals("voice", got.single().source)
    }
}
