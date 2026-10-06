package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свод — одна правда на сервере (06.10.2026, docs/svod-phone.md, контракт —
 * server/contract/svod.json): разбор синка, «полный синк без строк не
 * ложится», первое знакомство кладёт только отсутствующее, словарь сливается
 * при отказе base_rev, и счётчики не уходят на сервер.
 */
class SvodTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/svod.json"), File("server/contract/svod.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    @Test
    fun `пример синка контракта разбирается целиком`() {
        val svod = contract().getJSONObject("svod")
        val got = Svod.fromSync(JSONObject().put("svod", svod.getJSONArray("sync_example")))
        assertEquals(listOf("prompt.food", "money.partner"), got.map { it.key })
        val food = got[0]
        assertEquals("Ты разбираешь сказанное про еду…", food.body)
        assertNull(food.value)
        assertEquals(1, food.rev)
        assertEquals(9001L, food.seq)
        assertEquals("phone", food.author)
        assertEquals("заводской текст сборки 812", food.reason)
        assertEquals("2026-10-06T21:10:00+03:00", food.updatedAt)
        val partner = got[1]
        assertNull(partner.body)
        val v = partner.json() as JSONObject
        assertEquals(30, v.getInt("pct"))
        assertEquals("2026-10-05", v.getString("fixed_on"))
        assertEquals(10_000_000L, v.getLong("fixed_kop"))
        assertEquals("dengi", partner.author)
        // Каждый ключ контракта — допустимый ключ Свода (кроме шаблонов с «<имя>»).
        for (k in svod.getJSONObject("keys").keys()) {
            if (k == "_" || k.contains('<')) continue
            assertTrue(k, Svod.validKey(k))
        }
        for (k in listOf(Svod.DICT, Svod.RULES, Svod.MODELS, Svod.PRICES, Svod.TEAM, Svod.CATEGORIES,
            Svod.MONEY_PARTNER, Svod.MONEY_SUMMARY, Svod.MONEY_PAYEES, Svod.RAZNOSKA)) {
            assertTrue("ключ $k есть в контракте", svod.getJSONObject("keys").has(k))
        }
    }

    @Test
    fun `полный синк без записей поверх непустого кэша не ложится`() {
        val have = Svod.merge(emptyMap(), listOf(Svod.Entry("prompt.food", body = "еда", rev = 2, seq = 10)))
        val full = Svod.merge(have, Svod.fromSync(JSONObject().put("full", true).put("svod", JSONArray())))
        assertEquals(have, full)
        // Нет ключа svod вовсе (старый сервер) — тоже ничего не стирает.
        assertEquals(have, Svod.merge(have, Svod.fromSync(JSONObject().put("full", true))))
        // Старая версия из окна перекрытия не откатывает свежую.
        val old = Svod.merge(have, listOf(Svod.Entry("prompt.food", body = "старое", rev = 1, seq = 5)))
        assertEquals("еда", old.getValue("prompt.food").body)
        val newer = Svod.merge(have, listOf(Svod.Entry("prompt.food", body = "новое", rev = 3, seq = 11)))
        assertEquals("новое", newer.getValue("prompt.food").body)
    }

    @Test
    fun `первое знакомство кладёт только то, чего в Своде нет`() {
        val mine = mapOf<String, Pair<String?, String?>>(
            "prompt.food" to ("моя еда" to null),
            "prompt.body" to ("моё тело" to null),
            "dict.main" to (null to "{\"entries\":[]}"),
            "Плохой ключ" to ("x" to null),
            "prompt.empty" to (null to null),
        )
        val plan = Svod.seedPlan(setOf("prompt.food", "money.partner"), mine)
        assertEquals(listOf("dict.main", "prompt.body"), plan)
        val op = Svod.op(Svod.Pending("prompt.body", body = "моё тело", author = "seed", reason = "сборка 900", baseRev = 0, opId = "o1"))
        assertEquals("svod.set", op.getString("op"))
        assertEquals(0, op.getInt("base_rev"))
        assertEquals("seed", op.getString("author"))
        assertFalse(op.has("value"))
        val jop = Svod.op(Svod.Pending("dict.main", value = "{\"entries\":[]}", author = "phone", opId = "o2"))
        assertTrue(jop.get("value") is JSONObject)
        assertFalse("без base_rev — последний пишущий побеждает", jop.has("base_rev"))
    }

    @Test
    fun `отказ «на сервере уже версии» узнаётся по тексту контракта`() {
        val stale = contract().getJSONObject("svod").getJSONObject("set").getJSONObject("answer_stale").getString("error")
        assertTrue(Svod.isStale(stale))
        assertFalse(Svod.isStale("свод: ключ — только латиница"))
    }

    private fun e(id: Long, from: String, to: String = "", hits: Int = 0, at: Long = 1L, mode: DictMode = DictMode.HARD) =
        DictEntry(id = id, from = from, to = to, mode = mode, hits = hits, createdAt = at)

    @Test
    fun `словарь в Своде без счётчиков`() {
        val v = Svod.dictValue(listOf(e(1, "ромашка", "Ромашка", hits = 42)), seedVersion = 7)
        assertEquals("pravka-dictionary", v.getString("format"))
        assertEquals(7, v.getInt("seedVersion"))
        val row = v.getJSONArray("entries").getJSONObject(0)
        assertFalse("hits — местная статистика", row.has("hits"))
        assertEquals("ромашка", row.getString("from"))
        // И назад: файл словаря и голый массив служб компа.
        assertEquals("Ромашка", Svod.dictEntries(v).single().to)
        val bare = Svod.dictEntries(JSONArray().put(JSONObject().put("from", "ромашка").put("to", "Ромашка").put("mode", "PROTECT")))
        assertEquals(DictMode.PROTECT, bare.single().mode)
        assertEquals(1L, bare.single().id)
    }

    @Test
    fun `словарь — слияние при отказе base_rev`() {
        val base = listOf(e(1, "ромашка", "Ромашка"), e(2, "лютик", "Лютик"), e(3, "фиалка", "Фиалка"))
        // Сервер (ночной разбор) добавил «соколов» и поправил «фиалку».
        val server = listOf(e(1, "ромашка", "Ромашка"), e(2, "лютик", "Лютик"), e(3, "фиалка", "ФИАЛКА"), e(4, "соколов", "Соколов"))
        // Телефон выучил «пион», поправил «ромашку», удалил «лютик»; счётчики свои.
        val local = listOf(e(1, "ромашка", "Ромашка-банк", hits = 9), e(3, "фиалка", "Фиалка", hits = 2), e(5, "пион", "Пион", at = 5))
        val merged = Svod.mergeDict(base, server, local).associateBy { it.id }
        assertEquals("Ромашка-банк", merged.getValue(1).to)
        assertEquals(9, merged.getValue(1).hits)
        assertFalse("своё удалённое не воскресает", 2L in merged)
        assertEquals("не трогал — серверное", "ФИАЛКА", merged.getValue(3).to)
        assertEquals(2, merged.getValue(3).hits)
        assertEquals("Соколов", merged.getValue(4).to)
        assertEquals("Пион", merged.getValue(5).to)
    }

    @Test
    fun `словарь — первое знакомство объединяет, одно слово из двух мест — одна запись`() {
        val server = listOf(e(1, "ромашка", "Ромашка", at = 10), e(2, "соколов", "Соколов"))
        val local = listOf(e(7, "ромашка", "Ромашка", hits = 3, at = 20), e(8, "пион", "Пион"))
        val merged = Svod.mergeDict(null, server, local)
        assertEquals(listOf("ромашка", "пион", "соколов"), merged.map { it.from })
        assertEquals("позже заведённая", 7L, merged.first().id)
        assertEquals(3, merged.first().hits)
    }

    @Test
    fun `команда в промпте — из Свода, без него — заводская строка`() {
        val was = Svod.current
        try {
            Svod.current = emptyMap()
            assertEquals("команда (Аня, Боря)", Svod.withTeam("команда ({TEAM})", "Аня, Боря"))
            Svod.current = mapOf(Svod.TEAM to Svod.Entry(Svod.TEAM, body = "Вера — юрист"))
            assertEquals("команда (Вера — юрист)", Svod.withTeam("команда ({TEAM})", "Аня, Боря"))
            assertEquals("без метки не трогает", "текст", Svod.withTeam("текст", "Аня"))
        } finally {
            Svod.current = was
        }
    }

    @Test
    fun `одинаковый JSON с другим порядком ключей — не правка`() {
        assertTrue(Svod.sameJson("{\"a\":1,\"b\":[1,2]}", "{\"b\":[1,2],\"a\":1}"))
        assertFalse(Svod.sameJson("{\"a\":1}", "{\"a\":2}"))
    }
}
