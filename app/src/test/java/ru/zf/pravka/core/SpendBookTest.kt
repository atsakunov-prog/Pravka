package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Книга трат Claude телефона (14.10.2026): свёртка, что уходит на сервер, значок и экран. Числа выдуманные. */
class SpendBookTest {

    private fun contract(): JSONObject {
        val f = listOf(java.io.File("../server/contract/dela-spend.json"), java.io.File("server/contract/dela-spend.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    @Test
    fun `add sums one day route and model and keeps batch apart in price`() {
        var b = SpendBook.add(emptyMap(), "2026-10-14", "ZASECHKA", "claude-opus-5-5", 1000, 2000, 500, 40000, 0.06, batch = false)
        b = SpendBook.add(b, "2026-10-14", "zasechka", "claude-opus-5-5", 1000, 1000, 0, 40000, 0.04, batch = false)
        b = SpendBook.add(b, "2026-10-14", "night_review", "claude-opus-5-5", 50000, 18000, 0, 0, 0.284, batch = true)
        b = SpendBook.add(b, "2026-10-14", "", "claude-sonnet-5-5", 10, 10, 0, 0, 0.001, batch = false)
        assertEquals(3, b.size)
        val z = b.getValue("2026-10-14|zasechka|claude-opus-5-5")
        assertEquals(2, z.calls)
        assertEquals(80000L, z.cacheRead)
        assertEquals(100_000L, z.usdMicros)
        val n = b.getValue("2026-10-14|night_review|claude-opus-5-5")
        assertEquals(1, n.batchCalls)
        assertEquals(568_000L, n.usdListMicros)   // без скидки пачки — вдвое
        assertTrue("2026-10-14|other|claude-sonnet-5-5" in b)  // дорога без тега — «прочее»
        // Файл туда и обратно — то же самое.
        assertEquals(b, SpendBook.fromJson(SpendBook.toJson(b)))
    }

    @Test
    fun `prune keeps only recent days`() {
        val b = SpendBook.add(emptyMap(), "2026-08-01", "pravka", "m", 1, 1, 0, 0, 0.01, false)
        val c = SpendBook.add(b, "2026-10-14", "pravka", "m", 1, 1, 0, 0, 0.01, false)
        assertEquals(listOf("2026-10-14"), SpendBook.prune(c, "2026-08-14").values.map { it.day })
    }

    @Test
    fun `wire adds what only stats knew and nothing when the book knows more`() {
        val book = SpendBook.add(emptyMap(), "2026-10-14", "zasechka", "claude-opus-5-5", 1000, 2000, 0, 0, 0.30, false)
        val legacy = listOf(
            SpendBook.Legacy("2026-10-14", "zasechka", 0.25),  // книга знает больше — разницы нет
            SpendBook.Legacy("2026-10-14", "food", 0.08),      // только Stats — неточной строкой без модели
            SpendBook.Legacy("2026-10-01", "pravka", 0.50),    // день до книги
        )
        val totals = mapOf("2026-10-01" to 0.70, "2026-10-14" to 0.40)
        val rows = SpendBook.wire(book.values, legacy, totals)
        val keys = rows.map { it.optString("day") + " " + SpendBook.keyOf(it) }
        assertEquals(listOf("2026-10-14 zasechka|claude-opus-5-5", "2026-10-01 pravka|", "2026-10-14 food|", "2026-10-01 other|legacy"), keys)
        val exact = rows.first()
        assertTrue(exact.getBoolean("exact"))
        assertEquals("phone", exact.getString("service"))
        assertEquals(0.3, exact.getDouble("usd"), 1e-9)
        val rest = rows.last()
        assertFalse(rest.getBoolean("exact"))
        assertEquals(0.2, rest.getDouble("usd"), 1e-9)   // день без дороги: 0,70 − 0,50
        // Каждая строка — по форме контракта (поля row).
        val allowed = contract().getJSONObject("op_put").getJSONObject("row").keys().asSequence().toSet()
        for (r in rows) assertTrue(r.toString(), r.keys().asSequence().all { it in allowed })
    }

    @Test
    fun `vanished keys go out as zeros and hash moves with content`() {
        val r1 = listOf(JSONObject().put("day", "2026-10-14").put("task", "food").put("model", "").put("usd", 0.08))
        val out = SpendBook.dayRows("2026-10-14", emptyList(), setOf("food|"))
        assertEquals(1, out.size)
        assertEquals(0.0, out[0].getDouble("usd"), 0.0)
        assertEquals("food|", SpendBook.keyOf(out[0]))
        assertEquals(SpendBook.hash(r1), SpendBook.hash(listOf(JSONObject(r1[0].toString()))))
        assertFalse(SpendBook.hash(r1) == SpendBook.hash(listOf(JSONObject(r1[0].toString()).put("usd", 0.09))))
        val op = SpendBook.putOp("Fold · ab12cd", r1)
        assertEquals("spend.put", op.getString("op"))
        assertFalse(op.has("op_id"))   // повтор безвреден, отказ старого сервера не должен запомниться
    }

    @Test
    fun `contract example parses into badge and dot`() {
        val ex = contract().getJSONObject("sync_field").getJSONObject("example")
        val b = SpendBook.badge(ex)!!
        assertEquals(21.0418, b.week, 1e-9)
        assertEquals(15.2, b.weekPhone, 1e-9)
        assertTrue(b.unseen)
        assertTrue(SpendBook.dot(b, ""))
        assertFalse(SpendBook.dot(b, b.tipsId))       // открыл на телефоне — точка гаснет сразу
        assertFalse(SpendBook.dot(b.copy(unseen = false), ""))
        assertNull(SpendBook.badge(null))
        assertEquals(b, SpendBook.badge(SpendBook.badgeJson(b)))
        assertTrue(SpendBook.hint(b).contains("телефон \$15,20"))
    }

    @Test
    fun `money is russian and short`() {
        assertEquals("\$4,20", SpendBook.money(4.2))
        assertEquals("\$128", SpendBook.money(128.4))
        assertEquals("\$0,35", SpendBook.money(0.35))
        assertEquals("\$0,004", SpendBook.money(0.004))
        assertEquals("\$0,00", SpendBook.money(0.0))
        assertEquals("Opus 5.5", SpendBook.modelTitle("claude-opus-5-5"))
        assertEquals("Haiku 4.5", SpendBook.modelTitle("claude-haiku-4-5"))
        assertEquals("модель не записана", SpendBook.modelTitle(""))
        assertEquals("other", SpendBook.task("Засечка"))
        assertEquals("zasechka_fork", SpendBook.task("ZASECHKA_FORK"))
    }

    @Test
    fun `screen reads server view with phone and server blocks`() {
        val view = JSONObject(
            """
            {"ok": true, "today": "2026-10-14", "prev_week": 18.5,
             "periods": {"week": {"usd": 21.04, "calls": 300, "batch_calls": 40, "cache_read": 2000000, "cache_write": 90000,
                                  "cache_saved": 7.6, "batch_saved": 1.9, "inexact_usd": 0.4,
                                  "models": [{"model": "claude-opus-5-5", "usd": 18.0, "calls": 200}],
                                  "blocks": [{"block": "phone", "title": "Телефон", "usd": 15.2,
                                              "services": [{"service": "phone", "title": "Правка (телефон)", "usd": 15.2, "calls": 250, "models": ["claude-opus-5-5"]}],
                                              "tasks": [{"service": "phone", "task": "zasechka", "title": "Засечка: разбор фразы", "service_title": "Правка (телефон)", "usd": 9.1, "calls": 90, "models": ["claude-opus-5-5"]}]},
                                             {"block": "server", "title": "Сервер", "usd": 5.84,
                                              "services": [{"service": "meetings", "title": "Встречи", "usd": 3.0, "calls": 20, "models": []}],
                                              "tasks": [{"service": "meetings", "task": "clean", "title": "Чистка расшифровки", "service_title": "Встречи", "usd": 2.0, "calls": 10, "models": ["claude-sonnet-5-5"]}]}]}},
             "trend": [{"day": "2026-10-13", "usd": 2.5}, {"day": "2026-10-14", "usd": 2.9}],
             "sources": [{"src": "pc", "titles": ["Встречи", "Шаббат"], "day": "2026-10-14"}],
             "gaps": ["подписка Claude Code"],
             "tips": {"id": "t1", "week": "2026-10-12", "note": "Засечка — половина недели.", "seen_at": null,
                      "tips": [{"where": "phone", "title": "Засечка: xhigh → high", "why": "…", "how": "Модели", "save_usd_month": 6.5, "risk": "…"}]}}
            """.trimIndent(),
        )
        val s = SpendBook.screen(view)
        val w = s.periods.getValue("week")
        assertEquals(21.04, w.usd, 1e-9)
        assertEquals(listOf("phone", "server"), w.blocks.map { it.key })
        assertEquals("Засечка: разбор фразы", w.blocks[0].tasks[0].title)       // у телефона — без имени службы
        assertEquals("Встречи · Чистка расшифровки", w.blocks[1].tasks[0].title) // у сервера — со службой
        assertEquals("Opus 5.5", w.models[0].title)
        assertEquals(0.0, s.periods.getValue("today").usd, 0.0)                 // нет периода — пусто, не падает
        assertEquals("t1", s.tipsId)
        assertFalse(s.tipsSeen)
        assertEquals(6.5, s.tips[0].saveMonth, 1e-9)
        assertEquals("Встречи, Шаббат · по 14.10", s.sources[0].sub)
        assertEquals(listOf("подписка Claude Code"), s.gaps)
    }
}
