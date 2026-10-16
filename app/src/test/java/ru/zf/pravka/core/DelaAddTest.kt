package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * «+» и микрофон в шапке Дел (отладка 16.10.2026 вечер, запись 32): дела из листа уходят одной операцией захвата
 * `capture.say` — как строка «сказать» и «Д» (правило 29 сервера); несколько дел, людей и проекты раскладывает сервер.
 * Форма операции — против примера контракта `dela-capture.json`. Данные выдуманные.
 */
class DelaAddTest {

    @Test
    fun `сервер с захватом и моделью — одна операция capture_say очередью`() {
        assertEquals(DelaAdd.Way.SERVER, DelaAdd.way(ready = true, modelOn = true))
        val op = DelaAdd.op("  позвонить Ивану про финмодель, отправить Ольге счёт до пятницы ", "ref-1", spoken = false, saidAt = "2026-10-16T22:40:00+03:00", opId = "op-1")
        assertEquals("capture.say", op.getString("op"))
        assertEquals("op-1", op.getString("op_id"))
        // Несколько дел — одной фразой: раскладывает сервер, телефон не режет.
        assertEquals("позвонить Ивану про финмодель, отправить Ольге счёт до пятницы", op.getString("text"))
        assertEquals("ref-1", op.getString("ref"))
        assertEquals(Capture.LANE_AUTO, op.getString("lane"))
        assertEquals("say", op.getString("from"))
        assertFalse(op.has("hint"))
        // Поля — ровно те, что знает контракт (`say.request`), — сервер не отвергнет незнакомое.
        val contract = java.io.File(listOf("../server/contract/dela-capture.json", "server/contract/dela-capture.json").first { java.io.File(it).isFile })
            .readText().let { org.json.JSONObject(it) }.getJSONObject("say").getJSONObject("request")
        val known = contract.keys().asSequence().toSet()
        val sent = DelaAdd.op("x", "r", spoken = true, saidAt = "2026-10-16T22:40:00+03:00", hint = "Ромашка").keys().asSequence().toSet()
        assertEquals(emptySet<String>(), sent - known)
    }

    @Test
    fun `наговорено микрофоном — как «Д», набрано — как «сказать»`() {
        assertEquals(Capture.FROM_D, DelaAdd.from(spoken = true))
        assertEquals(Capture.FROM_SAY, DelaAdd.from(spoken = false))
        assertEquals("d", DelaAdd.op("x", "r", spoken = true, saidAt = "").getString("from"))
        // Голос дописывает к набранному.
        assertEquals("купить магний позвонить маме", DelaAdd.joined(" купить магний ", "позвонить маме "))
        assertEquals("позвонить маме", DelaAdd.joined("  ", "позвонить маме"))
    }

    @Test
    fun `без захвата на сервере — прежняя дорога, без модели — только правило`() {
        assertEquals(DelaAdd.Way.OLD, DelaAdd.way(ready = false, modelOn = true))
        assertEquals(DelaAdd.Way.OLD, DelaAdd.way(ready = false, modelOn = false))
        assertEquals(DelaAdd.Way.RULE, DelaAdd.way(ready = true, modelOn = false))
    }
}
