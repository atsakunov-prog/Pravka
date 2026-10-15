package ru.zf.pravka.core

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режим отладки (core/DelaDebug.kt, контракт server/contract/dela-debug.json): снимок экрана данными — тот же состав,
 * что у веба; действия — операции очереди и строки журнала по времени; операции и ответы сервера. Данные выдуманные.
 */
class DelaDebugTest {

    private val today = "2026-10-16"

    @After
    fun clear() = DelaDebug.clearOps()

    private fun snap() = Dela.Snapshot(
        tasks = listOf(
            Dela.Task(id = "t-1", num = 57, title = "Иван Тестов: прислать модель", ownerId = "me", projectId = "p-r",
                personId = "h-1", ball = Dela.WAITING, dueDate = today, focusOn = today),
            Dela.Task(id = "t-2", num = 58, title = "Купить подарок", ownerId = "me", status = Dela.DONE),
        ).associateBy { it.id },
        projects = listOf(Dela.Project(id = "p-r", name = "Ромашка", ownerId = "me", kind = "client")).associateBy { it.id },
        people = listOf(Dela.Person(id = "h-1", name = "Иван Тестов", short = "Иван")).associateBy { it.id },
        suggestions = listOf(Dela.Suggestion(id = "s-1", kind = "create", source = "meeting", payload = "{\"title\":\"Позвонить Ромашке\"}"))
            .associateBy { it.id },
        seq = 42,
        features = setOf(DelaDebug.FEATURE),
    )

    @Test
    fun `снимок — экран, режим, открытая карточка, строки по порядку и что было в окне`() {
        val sc = DelaDebug.Screen(
            route = "nav:today", title = "Сегодня", sub = "четверг, 16 октября",
            mode = mapOf("sphere" to "work", "show_done" to false, "levels" to "группы", "pick" to null),
            rows = listOf(DelaDebug.Row("t-1", true, "Мониторить"), DelaDebug.Row("t-2", false), DelaDebug.Row("t-1", false)),
            openTaskId = "t-1",
            newIds = listOf("s-1"),
            talk = listOf(DelaDebug.Said("перенеси Ивана на пятницу", "Перенёс.", "Иван Тестов: прислать модель: срок 17.10")),
            claudeText = "ещё",
        )
        val o = DelaDebug.snapshot(sc, snap(), today, "999 test", events = listOf("10-16 10:00:00.000  дела: синк"),
            queue = listOf(JSONObject().put("op", "task.done").put("id", "t-1"), JSONObject().put("op", "debug.add")))
        assertEquals(1, o.getInt("v"))
        assertEquals("nav:today", o.getString("route"))
        assertEquals("Сегодня", o.getString("title"))
        val mode = o.getJSONObject("mode")
        assertEquals("work", mode.getString("sphere"))
        assertFalse("ложь и пустое — не шум", mode.has("show_done") || mode.has("pick"))
        val card = o.getJSONObject("card")
        assertEquals("task", card.getString("kind"))
        assertEquals(57, card.getInt("num"))
        assertEquals("Ромашка", card.getJSONObject("fields").getString("project_name"))
        assertEquals("Иван", card.getJSONObject("fields").getString("person_name"))
        val rows = o.getJSONArray("rows")
        assertEquals("повтор строки — один раз", 2, rows.length())
        assertEquals(2, o.getInt("rows_total"))
        val r0 = rows.getJSONObject(0)
        assertEquals("Иван Тестов: прислать модель", r0.getString("title"))
        assertEquals("Мониторить", r0.getString("group"))
        assertEquals("Иван", r0.getString("person"))
        assertEquals("Ромашка", r0.getString("project"))
        assertTrue(r0.getBoolean("now"))
        assertTrue(r0.getBoolean("in_view"))
        assertEquals("done", rows.getJSONObject(1).getString("status"))
        assertFalse(rows.getJSONObject(1).getBoolean("in_view"))
        assertEquals("П1", o.getJSONArray("new").getJSONObject(0).getString("n"))
        assertEquals("Позвонить Ромашке", o.getJSONArray("new").getJSONObject(0).getString("title"))
        assertEquals("перенеси Ивана на пятницу", o.getJSONArray("talk").getJSONObject(0).getString("said"))
        assertEquals("ещё", o.getString("quick_text"))
        assertEquals(1, o.getJSONArray("events").length())
        assertEquals("записка в своей очереди не едет", 1, o.getJSONArray("queue").length())
        assertEquals(42, o.getLong("seq"))
    }

    @Test
    fun `снимок страницы проекта без открытого дела — карточка клиента, строк не больше ROWS`() {
        val rows = (1..DelaDebug.ROWS + 30).map { DelaDebug.Row("x-$it", false) }
        val o = DelaDebug.snapshot(DelaDebug.Screen("page:project:p-r", "Проект: Ромашка", rows = rows, pageKind = "project", pageId = "p-r"),
            snap(), today, "")
        assertEquals("client", o.getJSONObject("card").getString("kind"))
        assertEquals(DelaDebug.ROWS, o.getJSONArray("rows").length())
        assertEquals(DelaDebug.ROWS + 30, o.getInt("rows_total"))
        assertEquals("(дела нет в копии)", o.getJSONArray("rows").getJSONObject(0).getString("title"))
    }

    @Test
    fun `кольцо операций — без записок, не длиннее OPS, длинное коротко`() {
        DelaDebug.noteOps(listOf(JSONObject().put("op", "debug.add").put("note", JSONObject())), now = 1L)
        assertTrue(DelaDebug.recentOps().isEmpty())
        repeat(DelaDebug.OPS + 5) { i -> DelaDebug.noteOps(listOf(JSONObject().put("op", "task.set").put("id", "t-$i")), now = 1000L + i) }
        val ops = DelaDebug.recentOps()
        assertEquals(DelaDebug.OPS, ops.size)
        assertEquals("t-5", ops.first().second.getString("id"))
        DelaDebug.clearOps()
        DelaDebug.noteOps(listOf(JSONObject().put("op", "task.create").put("task", JSONObject().put("title", "x".repeat(500)))))
        assertTrue(DelaDebug.recentOps()[0].second.getJSONObject("task").getString("title").length <= 300)
    }

    @Test
    fun `действия — операции и журнал по времени, ISO`() {
        val now = OffsetDateTime.of(2026, 10, 16, 10, 20, 0, 0, ZoneOffset.ofHours(3))
        val opAt = now.minusMinutes(5).toInstant().toEpochMilli()
        val a = DelaDebug.actions(
            listOf(opAt to JSONObject().put("op", "task.create").put("task", JSONObject().put("title", "Позвонить Ивану"))),
            listOf("10-16 10:10:00.000  дела: Claude ответил — edit, поправлено 1", "мусор без времени", "10-16 10:18:30.500  дела: отправлено 1"),
            now,
        )
        assertEquals(3, a.length())
        assertEquals("event", a.getJSONObject(0).getString("kind"))
        assertEquals("дела: Claude ответил — edit, поправлено 1", a.getJSONObject(0).getString("text"))
        assertEquals("op", a.getJSONObject(1).getString("kind"))
        assertEquals("task.create «Позвонить Ивану»", a.getJSONObject(1).getString("text"))
        assertTrue(a.getJSONObject(2).getString("at").startsWith("2026-10-16T10:18:30"))
        // Журнал с датой «из будущего» — прошлогодний (лог живёт неделями, год в нём не пишется).
        assertTrue(DelaDebug.logAt("12-31 23:00:00.000  x", now, ZoneOffset.ofHours(3))!!.startsWith("2025-12-31"))
        assertNull(DelaDebug.logAt("коротко", now))
    }

    @Test
    fun `операции записки и статуса — как в контракте`() {
        val op = DelaDebug.addOp("op-1", "n-1", "  Сказал X — сделал Y  ", "999", "411×914 dp сложен", "2026-10-16T10:15:00+03:00",
            JSONObject().put("v", 1), JSONArray(), shot = "")
        assertEquals("debug.add", op.getString("op"))
        val note = op.getJSONObject("note")
        assertEquals("Сказал X — сделал Y", note.getString("text"))
        assertEquals("phone", note.getString("source"))
        assertFalse("пустая картинка не едет", note.has("shot"))
        val set = DelaDebug.setOp("op-2", "n-1", status = "fixed")
        assertEquals("fixed", set.getString("status"))
        assertFalse(set.has("answer"))
    }

    @Test
    fun `ответы сервера — поле синка и список`() {
        assertNull(DelaDebug.info(JSONObject()))
        val i = DelaDebug.info(JSONObject().put("debug", JSONObject().put("on", true).put("new", 3).put("last", 12).put("answered", JSONObject.NULL)))!!
        assertEquals(DelaDebug.Info(true, 3, 12, ""), i)
        assertEquals(i, DelaDebug.info(JSONObject().put("debug", DelaDebug.infoJson(i))))
        val list = DelaDebug.notes(JSONObject().put("items", JSONArray().put(JSONObject()
            .put("id", "n-1").put("num", 12).put("said_at", "2026-10-16T10:15:00+03:00").put("text", "Сказал X — сделал Y")
            .put("source", "web").put("status", "not_bug").put("answer", "Так и задумано.").put("status_by", "claude")
            .put("title", "Сегодня").put("has_shot", true))))
        assertEquals(1, list.size)
        assertEquals("не баг", list[0].statusWord)
        assertEquals("claude", list[0].statusBy)
        assertTrue(list[0].hasShot)
        assertEquals("сегодня 10:15", DelaDebug.whenWords(list[0].at, today))
        assertEquals("15.10 23:59", DelaDebug.whenWords("2026-10-15T20:59:00Z", today))
    }
}
