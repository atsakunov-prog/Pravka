package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защита от петли на сервере Дел — на экране «Claude: траты» телефона (аудит 15.10.2026, №12): что стоит (`guard.stopped`
 * вида `spend`) — строкой со словами, «Продолжать» — операция `claude.guard`. Старый сервер поля не шлёт — строки нет.
 */
class SpendGuardTest {

    @Test
    fun `суточный предел и защита — строка «Сервер остановлен» и операция Продолжать`() {
        val o = JSONObject().put("ok", true).put("periods", JSONObject()).put("guard", JSONObject()
            .put("stopped", JSONArray().put(JSONObject().put("task", "ask").put("title", "Правка словами").put("why", "31 за 10 минут (порог 30)")
                .put("at", "2026-10-15T14:05:00+03:00"))))
        val sc = SpendBook.screen(o)
        assertEquals("ask", sc.stopped.single().task)
        assertEquals("Сервер: остановлено защитой — Правка словами: 31 за 10 минут (порог 30) · с 14:05", SpendBook.guardLine(sc.stopped.single()))
        val op = SpendBook.guardOp("ask")
        assertEquals("claude.guard", op.getString("op"))
        assertEquals("resume", op.getString("action"))
        assertTrue(op.getString("op_id").isNotBlank())
        // Старый сервер поля guard не шлёт — строки нет.
        assertEquals(emptyList<SpendBook.GuardStop>(), SpendBook.screen(JSONObject().put("periods", JSONObject())).stopped)
    }
}
