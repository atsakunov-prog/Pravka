package ru.zf.pravka.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.prompts.PromptsRaznoska
import ru.zf.pravka.core.prompts.SharedPrompts
import ru.zf.pravka.provider.parseTasksDela

/**
 * Напоминания дел и общий промпт разбора против контракта части 3
 * (`server/contract/dela-remind.json`). Сервер держит ту же форму своими
 * тестами; разойдись телефон в имени поля — старый `_pick` сервера отверг бы
 * дело целиком, а новый не узнал бы напоминание.
 */
class DelaRemindTest {

    private val msk: ZoneId = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.parse("2026-10-06")

    private fun file(path: String): File = listOf(File("../$path"), File(path)).first { it.isFile }
    private fun contract(): JSONObject = JSONObject(file("server/contract/dela-remind.json").readText())
    private fun part1(): JSONObject = JSONObject(file("server/contract/dela.json").readText())

    private fun withRemind(resp: JSONObject): JSONObject = JSONObject(resp.toString()).put("features", org.json.JSONArray().put("remind"))

    // ------------------------------------------------------------ поля и флаг

    @Test
    fun `строка синка с напоминанием и флаг сервера`() {
        val row = contract().getJSONObject("sync_task_example")
        val resp = JSONObject().put("full", true).put("seq", 2210).put("tasks", org.json.JSONArray().put(row))
        val off = Dela.merge(Dela.Snapshot(), resp, 0L)
        assertFalse("без features сервер напоминаний не знает", off.remindOn)
        val on = Dela.merge(Dela.Snapshot(), withRemind(resp), 0L)
        assertTrue(on.remindOn)
        val t = on.task("61")!!
        assertEquals("2026-10-06T11:00:00+03:00", t.remindAt)
        assertEquals("", t.remindPlace)
        assertEquals("", t.remindedAt)
        // Кэш на диск и обратно — с флагом и полями.
        val back = Dela.fromJson(JSONObject(Dela.toJson(on).toString()))
        assertTrue(back.remindOn)
        assertEquals(t.remindAt, back.task("61")!!.remindAt)
        // Следующий синк без флага (сервер откатили) — флаг уходит.
        val rolled = Dela.merge(on, JSONObject().put("seq", 2211), 0L)
        assertFalse(rolled.remindOn)
    }

    @Test
    fun `операции телефона — ровно поля контракта`() {
        val allowed = contract().getJSONObject("task_fields").keys().asSequence().toSet() - "reminded_at"
        val t = Dela.Task(
            id = "5d1c2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", title = "Позвонить Ивану",
            dueDate = "2026-10-06", remindAt = "2026-10-06T11:00:00+03:00", source = "voice",
        )
        val task = Dela.createOp(t).getJSONObject("task")
        assertEquals("2026-10-06T11:00:00+03:00", task.getString("remind_at"))
        assertFalse(task.has("remind_place"))
        assertFalse("reminded_at ставит только сервер", task.has("reminded_at"))
        // Пример контракта: те же ключи у дела Разноски.
        val example = contract().getJSONArray("ops_examples").getJSONObject(0).getJSONObject("task")
        for (k in listOf("remind_at", "due_date")) assertTrue(k, example.has(k) && task.has(k))
        assertTrue(allowed.containsAll(listOf("remind_at", "remind_place", "due_time")))
        // Без напоминания — полей нет вовсе: старый сервер такое дело примет.
        val plain = Dela.createOp(t.copy(remindAt = "")).getJSONObject("task")
        assertFalse(plain.has("remind_at"))
        assertFalse(plain.has("remind_place"))
    }

    @Test
    fun `правка напоминания сбрасывает отметку «отправлено» и идёт в task-set`() {
        val sent = Dela.Task(id = "a", title = "x", remindAt = "2026-10-06T11:00:00+03:00", remindedAt = "2026-10-06T11:00:04+03:00")
        val later = Dela.applyFields(sent, JSONObject().put("remind_at", "2026-10-06T12:00:00+03:00"), "2026-10-06", "2026-10-06T11:05:00+03:00")
        assertEquals("", later.remindedAt)
        val same = Dela.applyFields(sent, JSONObject().put("title", "y"), "2026-10-06", "2026-10-06T11:05:00+03:00")
        assertEquals(sent.remindedAt, same.remindedAt)
        val (set, was) = Dela.diff(sent, sent.copy(remindAt = "", remindPlace = "дом"))
        assertEquals(JSONObject.NULL, set.get("remind_at"))
        assertEquals("дом", set.getString("remind_place"))
        assertEquals("2026-10-06T11:00:00+03:00", was.getString("remind_at"))
    }

    // ------------------------------------------------------------ время и места

    @Test
    fun `ответ модели — в ISO со смещением`() {
        assertEquals("2026-10-07T11:00:00+03:00", DelaRemind.iso("2026-10-07 11:00", msk))
        assertEquals("2026-10-07T09:05:00+03:00", DelaRemind.iso("2026-10-07T9:05", msk))
        assertEquals("2026-10-07T11:00:00+03:00", DelaRemind.iso("2026-10-07 11:00:00", msk))
        assertEquals("2026-10-07T11:00:00+03:00", DelaRemind.iso("2026-10-07T11:00:00+03:00", msk))
        assertEquals("", DelaRemind.iso("завтра в 11", msk))
        assertEquals("", DelaRemind.iso("2026-10-07 25:00", msk))
        assertEquals("", DelaRemind.iso("", msk))
        assertEquals("2026-10-07T18:30:00+03:00", DelaRemind.at(LocalDate.parse("2026-10-07"), "1830", msk))
        assertEquals("2026-10-07T09:00:00+03:00", DelaRemind.at(LocalDate.parse("2026-10-07"), "", msk))
    }

    @Test
    fun `место — по имени автопилота, без регистра и падежа`() {
        val places = listOf("дом", "Летово", "дача")
        assertEquals("дом", DelaRemind.matchPlace("дом", places))
        assertEquals("дом", DelaRemind.matchPlace("Дома", places))
        assertEquals("дом", DelaRemind.matchPlace("домой", places))
        assertEquals("Летово", DelaRemind.matchPlace("летово", places))
        assertEquals("дача", DelaRemind.matchPlace("на даче".removePrefix("на "), places))
        assertEquals("", DelaRemind.matchPlace("офис", places))
        assertEquals("", DelaRemind.matchPlace("", places))
        assertTrue(DelaRemind.placesBlock(places).endsWith("дом, Летово, дача"))
        assertTrue(DelaRemind.placesBlock(emptyList()).contains("ни одного места"))
    }

    @Test
    fun `подпись в строке дела`() {
        val base = Dela.Task(id = "a", title = "x")
        assertEquals("⏰ 11:00", DelaRemind.chip(base.copy(remindAt = "2026-10-06T11:00:00+03:00"), today, msk))
        assertEquals("⏰ завтра 09:00", DelaRemind.chip(base.copy(remindAt = "2026-10-07T09:00:00+03:00"), today, msk))
        assertEquals("⏰ пт 15:00", DelaRemind.chip(base.copy(remindAt = "2026-10-09T15:00:00+03:00"), today, msk))
        assertEquals("⏰ 20.10 09:00", DelaRemind.chip(base.copy(remindAt = "2026-10-20T09:00:00+03:00"), today, msk))
        assertEquals("⏰ дом", DelaRemind.chip(base.copy(remindPlace = "дом"), today, msk))
        // Отправленное и закрытое — без подписи.
        assertEquals("", DelaRemind.chip(base.copy(remindAt = "2026-10-06T11:00:00+03:00", remindedAt = "2026-10-06T11:00:04+03:00"), today, msk))
        assertEquals("", DelaRemind.chip(base.copy(remindPlace = "дом", status = Dela.DONE), today, msk))
        assertEquals("", DelaRemind.chip(base, today, msk))
        // Подпись встаёт в строку веба рядом со сроком.
        val s = Dela.Snapshot(tasks = mapOf("a" to base.copy(remindAt = "2026-10-06T11:00:00+03:00")))
        assertTrue(DelaViews.chips(s.tasks["a"]!!, null, s, "2026-10-06").any { it.startsWith("⏰ ") })
    }

    @Test
    fun `напоминание без сервера — строкой в заметки`() {
        assertEquals(
            "⏰ Напомнить: завтра 09:00 (сервер Дел пока не напоминает)",
            DelaRemind.notesLine("2026-10-07T09:00:00+03:00", "", today, msk),
        )
        assertEquals(
            "⏰ Напомнить, когда приеду: дом (сервер Дел пока не напоминает)",
            DelaRemind.notesLine("", "дом", today, msk),
        )
        assertEquals("", DelaRemind.notesLine("", "", today, msk))
    }

    @Test
    fun `быстрые чипы карточки`() {
        val day = DelaRemind.quick(ZonedDateTime.of(2026, 10, 6, 14, 0, 0, 0, msk))
        assertEquals(listOf("через час", "вечером 19:00", "завтра 09:00"), day.map { it.label })
        assertEquals("2026-10-06T15:00:00+03:00", day[0].iso)
        assertEquals("2026-10-06T19:00:00+03:00", day[1].iso)
        assertEquals("2026-10-07T09:00:00+03:00", day[2].iso)
        // Вечер уже наступил — чипа «вечером» нет.
        val evening = DelaRemind.quick(ZonedDateTime.of(2026, 10, 6, 18, 45, 0, 0, msk))
        assertEquals(listOf("через час", "завтра 09:00"), evening.map { it.label })
    }

    // ------------------------------------------------------------ приезд

    @Test
    fun `настоящий приезд, а не мигнувший роутер`() {
        val now = 10_000_000_000L
        val min = 60_000L
        assertFalse("отъезда не было — служба поднялась дома", DelaRemind.realArrival("дом", now, "", 0L))
        assertFalse("роутер мигнул на пять минут", DelaRemind.realArrival("дом", now, "дом", now - 5 * min))
        assertTrue("не было дома 40 минут", DelaRemind.realArrival("дом", now, "дом", now - 40 * min))
        assertTrue("приехал из Летово", DelaRemind.realArrival("дом", now, "Летово", now - 5 * min))
        assertFalse("отъезд в будущем — часы сбились", DelaRemind.realArrival("дом", now, "Летово", now + min))
    }

    @Test
    fun `приезд взводит мои дела этого места — один раз`() {
        val me = "sasha"
        val tasks = listOf(
            Dela.Task(id = "1", num = 1, title = "Повесить полку", ownerId = me, remindPlace = "дом"),
            Dela.Task(id = "2", num = 2, title = "Чужое", ownerId = "natasha", remindPlace = "дом"),
            Dela.Task(id = "3", num = 3, title = "В Летово", ownerId = me, remindPlace = "Летово"),
            Dela.Task(id = "4", num = 4, title = "Уже взведено", ownerId = me, remindPlace = "дом", remindAt = "2026-10-06T19:00:00+03:00"),
            Dela.Task(id = "5", num = 5, title = "Уже ушло", ownerId = me, remindPlace = "дом", remindedAt = "2026-10-05T19:00:00+03:00"),
            Dela.Task(id = "6", num = 6, title = "Закрыто", ownerId = me, remindPlace = "дом", status = Dela.DONE),
            Dela.Task(id = "7", num = 7, title = "Сказано «дома»", ownerId = me, remindPlace = "Дома"),
        ).associateBy { it.id }
        val at = "2026-10-06T19:05:12+03:00"
        val off = Dela.Snapshot(tasks = tasks)
        assertTrue("сервер без напоминаний — ничего не шлём", DelaRemind.arrivalOps(off, me, "дом", at).isEmpty())
        val on = off.copy(features = setOf(Dela.FEATURE_REMIND))
        val ops = DelaRemind.arrivalOps(on, me, "дом", at)
        assertEquals(listOf("1", "7"), ops.map { it.getString("id") })
        val op = ops[0]
        assertEquals("task.set", op.getString("op"))
        assertEquals(at, op.getJSONObject("set").getString("remind_at"))
        assertEquals(JSONObject.NULL, op.getJSONObject("was").get("remind_at"))
        assertEquals("повтор того же приезда — тот же op_id", op.getString("op_id"), DelaRemind.arrivalOps(on, me, "дом", at)[0].getString("op_id"))
        assertTrue(Dela.isUuid(op.getString("op_id")))
        // Форма — как пример контракта.
        val example = contract().getJSONArray("ops_examples").getJSONObject(4)
        assertEquals(example.getJSONObject("set").keys().asSequence().toSet(), op.getJSONObject("set").keys().asSequence().toSet())
    }

    // ------------------------------------------------------------ общий промпт

    @Test
    fun `общий промпт — один файл на телефон и сервер`() {
        val disk = file("server/contract/prompts/raznoska.txt").readText().replace("\r\n", "\n").trim()
        assertEquals("в APK ровно файл контракта", disk, SharedPrompts.raznoska)
        val full = PromptsRaznoska.TASKS_DELA
        assertTrue(full.startsWith(SharedPrompts.raznoska))
        for (p in listOf("{CATALOG}", "{PLACES}", "{TODAY}", "{NOW}", "{DICT}", "{INPUT}")) assertTrue(p, full.contains(p))
        // Кэш (askSplit): голова — до {TODAY}; справочник и места стабильны и должны быть в ней.
        assertTrue(full.indexOf("{CATALOG}") < full.indexOf("{TODAY}"))
        assertTrue(full.indexOf("{PLACES}") < full.indexOf("{TODAY}"))
        assertTrue(full.indexOf("{DICT}") > full.indexOf("{TODAY}"))
    }

    @Test
    fun `пример ответа в промпте — ровно поля схемы`() {
        val schema = JSONObject(file("server/contract/prompts/raznoska.schema.json").readText())
        val taskKeys = schema.getJSONObject("properties").getJSONObject("tasks").getJSONObject("items")
            .getJSONObject("properties").keys().asSequence().toSet()
        val text = SharedPrompts.raznoska
        val start = text.indexOf("{\"tasks\"")
        val end = text.indexOf("]}", text.indexOf("\"notes\": [{", start)) + 2
        val example = JSONObject(text.substring(start, end))
        assertEquals(taskKeys, example.getJSONArray("tasks").getJSONObject(0).keys().asSequence().toSet())
        // Каждое поле схемы телефон читает: ответ со всеми полями разбирается без потерь.
        assertTrue(taskKeys.containsAll(listOf("due_time", "remind_at", "remind_place")))
    }

    @Test
    fun `ответ модели из контракта — дела с временем и напоминаниями`() {
        val c = contract().getJSONObject("prompt")
        val snap = Dela.merge(Dela.Snapshot(), part1().getJSONObject("sync_response"), 0L)
        val (tasks, _) = parseTasksDela(c.getJSONObject("answer_example").toString(), snap, listOf("дом", "Летово"), msk)
        val expected = c.getJSONArray("answer_expected")
        assertEquals(expected.length(), tasks.size)
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val t = tasks[i]
            assertEquals(e.getString("title"), t.content)
            assertEquals(t.content, e.getString("due_date"), t.due)
            assertEquals(t.content, e.getString("due_time"), t.dueTime)
            assertEquals(t.content, e.getString("remind_at"), t.remindAt)
            assertEquals(t.content, e.getString("remind_place"), t.remindPlace)
            if (e.has("notes_has")) assertTrue(t.description, t.description.contains(e.getString("notes_has")))
        }
        // Человек из справочника — как и раньше.
        assertEquals("Иван", tasks[0].personName)
    }

    @Test
    fun `время срока без срока не ставится`() {
        val snap = Dela.Snapshot()
        val raw = """{"tasks": [{"title": "x", "due": "", "due_time": "15:00", "remind_at": "", "remind_place": ""}], "notes": []}"""
        assertEquals("", parseTasksDela(raw, snap, emptyList(), msk).first.single().dueTime)
        val raw2 = """{"tasks": [{"title": "x", "due": "2026-10-09", "due_time": "9.30"}], "notes": []}"""
        assertEquals("09:30", parseTasksDela(raw2, snap, emptyList(), msk).first.single().dueTime)
    }
}
