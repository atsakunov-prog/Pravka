package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.OfflinePack.Info
import ru.zf.pravka.core.OfflinePack.State

class OfflinePackTest {

    @Test
    fun `ошибки 12 и 13 — пакета нет, остальные — нет`() {
        assertTrue(OfflinePack.missingCode(13))  // LANGUAGE_UNAVAILABLE — журнал 27.10–03.11
        assertTrue(OfflinePack.missingCode(12))  // LANGUAGE_NOT_SUPPORTED
        assertFalse(OfflinePack.missingCode(2))  // NETWORK — это облако, не пакет
        assertFalse(OfflinePack.missingCode(7))  // NO_MATCH — тишина
    }

    @Test
    fun `русский узнаётся по основному тегу`() {
        assertEquals(State.INSTALLED, OfflinePack.fromSupport("ru-RU", listOf("en-US", "ru-RU"), emptyList()))
        assertEquals(State.INSTALLED, OfflinePack.fromSupport("ru-RU", listOf("ru"), emptyList()))
        assertEquals(State.INSTALLED, OfflinePack.fromSupport("ru-RU", listOf("ru_RU"), emptyList()))
        assertEquals(State.PENDING, OfflinePack.fromSupport("ru-RU", listOf("en-US"), listOf("ru-RU")))
        assertEquals(State.MISSING, OfflinePack.fromSupport("ru-RU", listOf("en-US"), emptyList()))
        assertEquals(State.MISSING, OfflinePack.fromSupport("ru-RU", emptyList(), emptyList()))
    }

    @Test
    fun `без службы на устройстве запаса нет`() {
        assertFalse(OfflinePack.usable(service = false, Info(State.INSTALLED), nowMs = 1_000))
    }

    @Test
    fun `нет или качается — в пакет не уходим`() {
        assertFalse(OfflinePack.usable(true, Info(State.MISSING), 1_000))
        assertFalse(OfflinePack.usable(true, Info(State.PENDING), 1_000))
        assertTrue(OfflinePack.usable(true, Info(State.INSTALLED), 1_000))
    }

    @Test
    fun `не знаем — можно, как было до 04_11`() {
        assertTrue(OfflinePack.usable(true, Info(), 1_000))
    }

    @Test
    fun `ошибка тейка закрывает пакет на полчаса, потом пробуем снова`() {
        // Случай 27.10–03.11: служба «есть», проверки не было, пакет отвечал 13.
        val missedAt = 10_000L
        val info = OfflinePack.afterTakeMiss(Info(), missedAt)
        assertFalse(OfflinePack.usable(true, info, missedAt + 1))
        assertFalse(OfflinePack.usable(true, info, missedAt + OfflinePack.MISSING_TTL_MS - 1))
        assertTrue(OfflinePack.usable(true, info, missedAt + OfflinePack.MISSING_TTL_MS))
    }

    @Test
    fun `свежая ошибка тейка сильнее «установлен» от проверки`() {
        val info = OfflinePack.afterTakeMiss(Info(State.INSTALLED), 10_000)
        assertFalse(OfflinePack.usable(true, info, 20_000))
        // Повторная проверка говорит то же «установлен» — это не новость, тейку верим.
        val again = OfflinePack.afterProbe(info, State.INSTALLED)
        assertFalse(OfflinePack.usable(true, again, 20_000))
    }

    @Test
    fun `пакет появился — ошибка тейка снята сразу`() {
        // Владелец нажал «Подготовить русскую модель», пакет докачался.
        val missing = OfflinePack.afterTakeMiss(Info(State.MISSING), 10_000)
        val pending = OfflinePack.afterProbe(missing, State.PENDING)
        assertFalse(OfflinePack.usable(true, pending, 20_000))
        val installed = OfflinePack.afterProbe(pending, State.INSTALLED)
        assertEquals(Info(State.INSTALLED, 0L), installed)
        assertTrue(OfflinePack.usable(true, installed, 20_000))
    }

    @Test
    fun `проверка не чаще раза в полчаса, по просьбе — сразу`() {
        assertTrue(OfflinePack.probeDue(lastProbeMs = 0, nowMs = 5_000, force = false))
        assertFalse(OfflinePack.probeDue(lastProbeMs = 5_000, nowMs = 6_000, force = false))
        assertTrue(OfflinePack.probeDue(lastProbeMs = 5_000, nowMs = 6_000, force = true))
        assertTrue(OfflinePack.probeDue(lastProbeMs = 5_000, nowMs = 5_000 + OfflinePack.PROBE_EVERY_MS, force = false))
    }

    @Test
    fun `скачать сами просим не чаще раза в полдня`() {
        assertTrue(OfflinePack.downloadDue(lastAskMs = 0, nowMs = 1_000))
        assertFalse(OfflinePack.downloadDue(lastAskMs = 1_000, nowMs = 1_000 + 60_000))
        assertTrue(OfflinePack.downloadDue(lastAskMs = 1_000, nowMs = 1_000 + OfflinePack.AUTO_DOWNLOAD_EVERY_MS))
    }

    @Test
    fun `настройки говорят правду`() {
        assertTrue(OfflinePack.label(true, Info(State.MISSING), 1_000).contains("русского офлайн-пакета нет"))
        assertTrue(OfflinePack.label(true, OfflinePack.afterTakeMiss(Info(), 500), 1_000).contains("нет"))
        assertTrue(OfflinePack.label(true, Info(State.PENDING), 1_000).contains("качается"))
        assertTrue(OfflinePack.label(true, Info(State.INSTALLED), 1_000).contains("установлен"))
        assertTrue(OfflinePack.label(false, Info(State.INSTALLED), 1_000).contains("нет"))
    }
}
