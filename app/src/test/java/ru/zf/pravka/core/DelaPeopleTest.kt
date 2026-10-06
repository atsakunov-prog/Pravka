package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одна карточка человека на телефоне (docs/svod-phone.md, 3.1 и 3.6):
 * `merged_into` в синке, `person.add` дописывает без дублей, `person.merge`
 * видно до ответа сервера. Имена выдуманные.
 */
class DelaPeopleTest {

    private fun person(id: String, name: String, vararg extra: Pair<String, Any>) = JSONObject().put("id", id).put("name", name)
        .put("aliases", JSONArray()).put("phones", JSONArray()).put("rev", 1).put("seq", 1)
        .apply { extra.forEach { (k, v) -> put(k, v) } }

    private val sync = JSONObject().put("full", true).put("seq", 10).put("tasks", JSONArray())
        .put("people", JSONArray()
            .put(person("live", "Евгений Соколов", "aliases" to JSONArray().put("Евгений Соколов VP банка"), "phones" to JSONArray().put("+7 916 123-45-67")))
            .put(person("dup", "Женя Соколов (ромашка)", "archived_at" to "2026-10-06T10:00:00+03:00", "merged_into" to "live")))

    @Test
    fun `слитый дубль ведёт к живой карточке`() {
        val s = Dela.merge(Dela.Snapshot(), sync, 0L)
        assertEquals("live", s.people.getValue("dup").mergedInto)
        assertEquals("Евгений Соколов", s.person("dup")?.name)
        assertEquals("live", s.person("live")?.id)
        // Кэш на диск и обратно — поле не теряется.
        assertEquals("live", Dela.fromJson(Dela.toJson(s)).people.getValue("dup").mergedInto)
        assertEquals(listOf("live"), s.livePeople().map { it.id })
    }

    @Test
    fun `person add дописывает, не дублируя`() {
        val s = Dela.merge(Dela.Snapshot(), sync, 0L)
        val op = JSONObject().put("op", "person.add").put("op_id", "a").put("id", "live").put("add", JSONObject()
            .put("aliases", JSONArray().put("Женя Соколов").put("евгений соколов").put("ЕВГЕНИЙ СОКОЛОВ VP банка"))
            .put("phones", JSONArray().put("8 (916) 123-45-67").put("+7 999 000-11-22"))
            .put("telegram_username", "@sokolov_e"))
        val p = Dela.overlay(s, listOf(op), "me", "2026-10-06", "2026-10-06T12:00:00+03:00").people.getValue("live")
        assertEquals(listOf("Евгений Соколов VP банка", "Женя Соколов"), p.aliases)
        assertEquals(listOf("+7 916 123-45-67", "+7 999 000-11-22"), p.phones)
        assertEquals("sokolov_e", p.telegram)
        assertTrue(Dela.describe(op, s).contains("Евгений Соколов"))
    }

    @Test
    fun `person merge видно сразу`() {
        val base = Dela.merge(Dela.Snapshot(), JSONObject().put("full", true).put("seq", 1).put("tasks", JSONArray())
            .put("people", JSONArray().put(person("live", "Евгений Соколов")).put(person("d2", "Женя С.", "phones" to JSONArray().put("+7 905 111-22-33")))), 0L)
        val op = JSONObject().put("op", "person.merge").put("op_id", "m").put("id", "d2").put("into", "live")
        val s = Dela.overlay(base, listOf(op), "me", "2026-10-06", "2026-10-06T12:00:00+03:00")
        assertEquals("live", s.people.getValue("d2").mergedInto)
        assertEquals(listOf("Женя С."), s.people.getValue("live").aliases)
        assertEquals(listOf("+7 905 111-22-33"), s.people.getValue("live").phones)
        assertEquals(listOf("live"), s.livePeople().map { it.id })
    }
}
