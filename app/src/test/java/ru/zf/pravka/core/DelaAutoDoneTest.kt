package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Сделано само» (08.10.2026, docs/dela-phone-5.md): закрытие и уточнение,
 * которые сервер принял сам (`store._auto`), — по причине решения, с «как
 * было» и комментарием-основанием в `result`. Данные выдуманные.
 */
class DelaAutoDoneTest {
    private val nowIso = "2026-10-08T20:00:00+03:00"
    private val now = java.time.OffsetDateTime.parse(nowIso).toInstant().toEpochMilli()
    private val today = "2026-10-08"

    private val closeId = "1a9d4c4e-6b3a-4f0e-9d47-0c6d2b1f3a01"
    private val updId = "1a9d4c4e-6b3a-4f0e-9d47-0c6d2b1f3a02"
    private val commentId = "5c0e2a7d-1b44-4c0a-8f3e-6b2d9a1c7e10"

    /** Синк, как его отдаёт сервер 08.10: строки предложений с `result`. */
    private fun snap(): Dela.Snapshot {
        val tasks = JSONArray()
            .put(JSONObject().put("id", closeId).put("num", 11).put("title", "Продлить Контур").put("owner_id", "sasha").put("status", "done").put("seq", 1))
            .put(JSONObject().put("id", updId).put("num", 12).put("title", "Альфа: КП на управленку до 12.10").put("owner_id", "sasha")
                .put("status", "open").put("due_date", "2026-10-12").put("ball", "waiting").put("person_id", "pe-olga").put("seq", 2))
        fun sug(id: String, kind: String, task: String, reason: String, result: JSONObject, payload: JSONObject = JSONObject().put("auto", true)) =
            JSONObject().put("id", id).put("for_user", "sasha").put("kind", kind).put("task_id", task).put("payload", payload)
                .put("source", "meeting").put("quote", "Ольга: давайте КП к двенадцатому").put("batch_title", "Встреча с Альфой")
                .put("status", "accepted").put("reason", reason).put("decided_at", "2026-10-08T12:00:00+03:00")
                .put("created_at", "2026-10-08T11:00:00+03:00").put("result", result).put("seq", 10)
        val sugs = JSONArray()
            .put(sug("s-close", "close", closeId, "закрыто само", JSONObject().put("status", "done").put("was", JSONObject().put("status", "open")).put("comment_id", commentId)))
            .put(sug(
                "s-upd", "update", updId, "уточнено само",
                JSONObject().put("title", "Альфа: КП на управленку до 12.10").put("due_date", "2026-10-12").put("ball", "waiting")
                    .put("was", JSONObject().put("title", "Альфа: КП на управленку").put("due_date", "2026-10-17").put("ball", "mine"))
                    .put("comment_id", commentId),
                JSONObject().put("auto", true).put("person_name", "Ольга").put("note", "Ольга ждёт к пятнице две версии"),
            ))
            // Старая встреча — принято руками: payload.auto есть, но решал человек.
            .put(sug("s-hand", "close", closeId, "", JSONObject()).put("id", "s-hand"))
            // Старый сервер: «закрыто само» без was и comment_id.
            .put(sug("s-old", "close", closeId, "закрыто само", JSONObject().put("status", "done")).put("id", "s-old"))
        val people = JSONArray().put(JSONObject().put("id", "pe-olga").put("name", "Ольга Морозова").put("short", "Ольга").put("seq", 3))
        return Dela.merge(
            Dela.Snapshot(),
            JSONObject().put("full", true).put("seq", 100).put("tasks", tasks).put("suggestions", sugs).put("people", people),
            now,
        )
    }

    @Test
    fun `сделано само — по причине решения, а не по флагу auto`() {
        val s = snap()
        val ids = Dela.autoDone(s, "sasha", now).map { it.id }.toSet()
        assertEquals(setOf("s-close", "s-upd", "s-old"), ids)
        assertFalse(s.suggestions.getValue("s-hand").autoDone)
        // result доезжает до копии и обратно — «Вернуть» работает и после перезапуска.
        val back = Dela.fromJson(JSONObject(Dela.toJson(s).toString()))
        assertEquals(s.suggestions, back.suggestions)
        assertEquals(commentId, back.suggestions.getValue("s-upd").resultObj().getString("comment_id"))
    }

    @Test
    fun `уточнение само — что поменялось словами, как в вебе`() {
        val sg = snap().suggestions.getValue("s-upd")
        assertEquals("название: «Альфа: КП на управленку до 12.10» · срок 17.10 → 12.10 · мониторить Ольга", DelaViews.autoWords(sg, today))
        // Старый сервер — слов нет, строка без них.
        assertEquals("", DelaViews.autoWords(snap().suggestions.getValue("s-old"), today))
    }

    @Test
    fun `«Вернуть» уточнение — как было и без комментария, одной пачкой`() {
        val s = snap()
        val sg = s.suggestions.getValue("s-upd")
        val ops = Dela.autoUndoOps(sg, s.tasks[updId], seen = true)!!
        assertEquals(listOf("task.set", "comment.delete", "suggestion.seen"), ops.map { it.getString("op") })
        val set = ops[0].getJSONObject("set")
        assertEquals("Альфа: КП на управленку", set.getString("title"))
        assertEquals("2026-10-17", set.getString("due_date"))
        assertEquals("mine", set.getString("ball"))
        assertEquals(commentId, ops[1].getString("id"))
        assertEquals("s-upd", ops[2].getJSONArray("ids").getString(0))
        // Поверх копии — сразу: название и срок прежние, из «Нового» ушло.
        val v = Dela.overlay(s, ops, "sasha", today, nowIso)
        assertEquals("Альфа: КП на управленку", v.tasks.getValue(updId).title)
        assertEquals("2026-10-17", v.tasks.getValue(updId).dueDate)
        assertTrue(Dela.autoDone(v, "sasha", now).none { it.id == "s-upd" })
    }

    @Test
    fun `«Вернуть» закрытие — reopen и комментарий, у старого без комментария, нечего — нет кнопки`() {
        val s = snap()
        val ops = Dela.autoUndoOps(s.suggestions.getValue("s-close"), s.tasks[closeId], seen = true)!!
        assertEquals(listOf("task.reopen", "comment.delete", "suggestion.seen"), ops.map { it.getString("op") })
        val old = Dela.autoUndoOps(s.suggestions.getValue("s-old"), s.tasks[closeId], seen = false)!!
        assertEquals(listOf("task.reopen"), old.map { it.getString("op") })
        // Дело уже открыли — вернуть нечего.
        val reopened = s.tasks.getValue(closeId).copy(status = Dela.OPEN)
        assertNull(Dela.autoUndoOps(s.suggestions.getValue("s-close"), reopened, seen = true))
        // Уточнение без was и комментария — тоже.
        val bare = s.suggestions.getValue("s-upd").copy(result = "{}")
        assertNull(Dela.autoUndoOps(bare, s.tasks[updId], seen = true))
        assertNull(Dela.autoUndoOps(s.suggestions.getValue("s-upd"), null, seen = true))
    }

    @Test
    fun `наговорка — сделанное само подписано «само», а не «принято»`() {
        val s = snap()
        assertEquals("Встреча с Альфой: уточнение — само", DelaViews.touchWord(s.suggestions.getValue("s-upd"), s))
        assertEquals("Встреча с Альфой: закрыть — принято", DelaViews.touchWord(s.suggestions.getValue("s-hand"), s))
    }
}
