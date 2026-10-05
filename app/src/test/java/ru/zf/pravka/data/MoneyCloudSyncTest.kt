package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Общие Деньги: «отправлено» — значит, свой журнал лежит на сервере того же
 * размера (владелец, 05.10.2026: кусок телефона на сервере не менялся неделю,
 * а телефон молчал).
 */
class MoneyCloudSyncTest {

    @Test fun `всё легло — пусто`() {
        val own = listOf("sasha-aaaaaa.000001.jsonl" to 300L, "sasha-aaaaaa.000002.jsonl" to 12L)
        assertTrue(MoneyCloudSync.notLanded(own, mapOf("sasha-aaaaaa.000001.jsonl" to 300L, "sasha-aaaaaa.000002.jsonl" to 12L, "marianna-bbbbbb.000001.jsonl" to 5L)).isEmpty())
    }

    @Test fun `не тот размер и пропавший кусок — словами`() {
        val own = listOf("sasha-aaaaaa.000001.jsonl" to 300L, "sasha-aaaaaa.000002.jsonl" to 12L)
        val bad = MoneyCloudSync.notLanded(own, mapOf("sasha-aaaaaa.000001.jsonl" to 200L))
        assertEquals(
            listOf(
                "sasha-aaaaaa.000001.jsonl: у телефона 300 байт, на сервере 200 байт",
                "sasha-aaaaaa.000002.jsonl: у телефона 12 байт, на сервере нет файла",
            ),
            bad,
        )
    }
}
