package ru.zf.pravka.core

import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Захват в копии Дел на телефоне (шаг 0 петли, 15.10.2026): поля `kind`, `back_on`, `back_said`, `reminded_via` и
 * идеи в синке; напоминание — не дело (его нет ни в одном списке дел, как и цели), оно на «Сегодня» в свой час — одним
 * правилом с сервером (`capture.REMIND_TODAY_IF`, `BACK_IF`) и вебом (`isRemindToday`, `isBack`); «Вернулось»;
 * своя отметка календаря — не встреча; запас прежней Разноской против общих случаев сервера.
 */
class CaptureDelaTest {

    private val msk: ZoneId = ZoneId.of("Europe/Moscow")
    private val today = "2026-10-15"
    private fun file(path: String): File = listOf(File("../$path"), File(path)).first { it.isFile }
    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun t(
        id: String, title: String = "Дело $id", kind: String = Dela.KIND_TASK, due: String = "", remindAt: String = "",
        place: String = "", backOn: String = "", focus: String = "", ball: String = Dela.MINE, owed: Boolean = false,
        status: String = Dela.OPEN, owner: String = "me", goal: Boolean = false,
    ) = Dela.Task(id = id, num = id.hashCode() and 0xfff, title = title, kind = kind, dueDate = due, remindAt = remindAt,
        remindPlace = place, backOn = backOn, focusOn = focus, ball = ball, owed = owed, status = status, ownerId = owner, goal = goal)

    private fun snap(vararg ts: Dela.Task, features: Set<String> = setOf(Dela.FEATURE_CAPTURE, Dela.FEATURE_REMIND)) =
        Dela.Snapshot(tasks = ts.associateBy { it.id }, features = features)

    // ------------------------------------------------------------ синк

    @Test
    fun `поля захвата и идеи — из синка и в кэш`() {
        val dela = JSONObject(file("server/contract/dela-capture.json").readText())
        val row = dela.getJSONObject("say").getJSONObject("response_rule").getJSONObject("capture").getJSONArray("items")
            .getJSONObject(0).getJSONObject("task").put("id", "5d1c2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f").put("owner_id", "me")
            .put("back_said", JSONObject.NULL).put("reminded_via", "phone").put("rev", 1).put("seq", 7)
        val idea = JSONObject().put("id", "i1").put("text", "предложить Ромашке косткаттинг").put("project_id", "p1")
            .put("status", "open").put("rev", 1).put("seq", 8).put("created_at", "2026-10-15T10:31:00+03:00")
        val resp = JSONObject().put("full", true).put("seq", 8).put("tasks", JSONArray().put(row)).put("ideas", JSONArray().put(idea))
            .put("features", JSONArray(dela.getJSONObject("features").getJSONArray("example").toString()))
        val s = Dela.merge(Dela.Snapshot(), resp, 0L)
        assertTrue(s.captureOn && s.captureModelOn && s.ideasOn && s.didOn && s.remindPhoneOn)
        val task = s.tasks.values.single()
        assertTrue(task.remind)
        assertFalse(task.deed)
        assertEquals("phone", task.remindedVia)
        assertEquals("предложить Ромашке косткаттинг", s.ideasFor(projectId = "p1").single().text)
        // Кэш на диск и обратно — те же поля.
        val back = Dela.fromJson(JSONObject(Dela.toJson(s).toString()))
        assertEquals(task.kind, back.tasks.values.single().kind)
        assertEquals(1, back.ideas.size)
        // Следующий синк: идея использована — уходит из живых.
        val used = Dela.merge(s, JSONObject().put("seq", 9).put("ideas", JSONArray().put(JSONObject(idea.toString()).put("status", "used").put("rev", 2))), 0L)
        assertTrue(used.ideasFor(projectId = "p1").isEmpty())
        // Старый сервер: флагов нет — телефон ведёт себя по-старому.
        val old = Dela.merge(Dela.Snapshot(), JSONObject().put("full", true).put("seq", 1).put("tasks", JSONArray().put(JSONObject().put("id", "x").put("title", "Дело"))), 0L)
        assertFalse(old.captureOn || old.remindPhoneOn || old.ideasOn)
        assertEquals(Dela.KIND_TASK, old.tasks["x"]!!.kind)
    }

    @Test
    fun `идея с телефона поверх копии и слияние людей`() {
        val s = Dela.Snapshot(
            people = mapOf("dup" to Dela.Person("dup", "Иван Т."), "iv" to Dela.Person("iv", "Иван Тестов")),
            ideas = mapOf("i1" to Dela.Idea("i1", "позвать на семинар", personId = "dup")),
        )
        val ops = listOf(
            JSONObject().put("op", "idea.add").put("op_id", "o1").put("idea", JSONObject().put("id", "i2").put("text", "косткаттинг").put("project_id", "p1")),
            JSONObject().put("op", "idea.set").put("op_id", "o2").put("id", "i1").put("set", JSONObject().put("status", "dropped")),
            JSONObject().put("op", "person.merge").put("op_id", "o3").put("id", "dup").put("into", "iv"),
        )
        val v = Dela.overlay(s, ops, "me", today, "2026-10-15T10:00:00+03:00")
        assertEquals("косткаттинг", v.ideas["i2"]!!.text)
        assertTrue(v.ideas["i2"]!!.local)
        assertEquals("dropped", v.ideas["i1"]!!.status)
        assertEquals("идея дубля — у живой карточки, как переносит сервер", "iv", v.ideas["i1"]!!.personId)
        // «В дело»: дело «потом» с привязкой идеи, идея — использована; «Вернуть» — всё обратно.
        val x = Dela.Idea("i3", "предложить дашборд", projectId = "p1", dealId = "d1")
        val u = Ideas.toTask(x, today, taskId = "t-new")
        val create = u.ops[0].getJSONObject("task")
        assertEquals("Предложить дашборд", create.getString("title"))
        assertEquals("2026-10-16", create.getString("back_on"))
        assertEquals("d1", create.getString("deal_id"))
        assertEquals("used", u.ops[1].getJSONObject("set").getString("status"))
        assertEquals("t-new", u.ops[1].getJSONObject("set").getString("task_id"))
        assertEquals("task.cancel", u.back[0].getString("op"))
        assertEquals("open", u.back[1].getJSONObject("set").getString("status"))
    }

    // ------------------------------------------------------------ напоминание — не дело

    @Test
    fun `напоминания нет ни в одном списке дел`() {
        val rem = t("r", "Отправить счёт в Контуре", kind = Dela.KIND_REMIND, remindAt = "2026-10-15T12:00:00+03:00", focus = today)
        val deed = t("d", "Позвонить в банк", due = today, focus = today)
        val late = t("l", kind = Dela.KIND_REMIND, remindAt = "2026-10-13T12:00:00+03:00", due = "2026-10-13")
        val s = snap(rem, deed, late)
        assertEquals(listOf("d"), DelaGroups.todayTasks(s, "me", today, "all").map { it.id })
        assertEquals(listOf("d"), DelaGroups.nowOrdered(s, "me", today).map { it.id })
        assertTrue(DelaGroups.lateTasks(s, "me", today, "all").isEmpty())
        assertEquals(listOf("d"), DelaGroups.scopeItems(s, "me", today, "all", DelaGroups.Scope.ALL).map { it.id })
        assertEquals(setOf("d"), DelaViews.list(s, "me", "all", showDone = false).map { it.id }.toSet())
        assertEquals(setOf("d"), DelaViews.openMine(s, "me", "all").map { it.id }.toSet())
        assertEquals(listOf("d"), DelaViews.nowTasks(s, "me", today).map { it.id })
        assertFalse("в «Сейчас» напоминание не встаёт", DelaGroups.canNow(rem, "me"))
        // Сделанное напоминание — не очко «закрыто за день».
        val done = snap(rem.copy(status = Dela.DONE, completedAt = "2026-10-15T12:01:00+03:00"), deed.copy(status = Dela.DONE, completedAt = "2026-10-15T12:02:00+03:00"))
        assertEquals(1, DelaGroups.doneToday(done, "me", today))
        // Зато во «Все дела → Напоминания» — все свои открытые, по времени.
        assertEquals(listOf("l", "r"), DelaGroups.remindersAll(s, "me").map { it.id })
    }

    @Test
    fun `напоминание на Сегодня — одно правило с сервером и вебом`() {
        val noon = t("a", kind = Dela.KIND_REMIND, remindAt = "2026-10-15T12:00:00+03:00")
        val yesterday = t("y", kind = Dela.KIND_REMIND, remindAt = "2026-10-14T12:00:00+03:00")
        val tomorrow = t("t", kind = Dela.KIND_REMIND, remindAt = "2026-10-16T09:00:00+03:00")
        val place = t("p", kind = Dela.KIND_REMIND, place = "Дом")
        val deedFri = t("f", due = "2026-10-16", remindAt = "2026-10-15T18:00:00+03:00")
        val deedToday = t("g", due = today, remindAt = "2026-10-15T18:00:00+03:00")
        val alien = noon.copy(id = "n", ownerId = "natasha")
        val s = snap(noon, yesterday, tomorrow, place, deedFri, deedToday, alien)
        assertEquals(listOf("y", "a", "f", "p"), DelaGroups.remindersToday(s, "me", today).map { it.id })
        // Строки «Напоминаний» на «Сегодня»: время, «вчера … не отмечено», дело со сроком, по месту — в конце.
        val rows = TodayAgenda.reminderRows(s, "me", today, ms("2026-10-15T12:30:00+03:00"), zone = msk)
        assertEquals(listOf("вчера 12:00 · не отмечено", "12:00", "18:00", "дома"), rows.map { it.whenText })
        assertEquals(listOf(true, true, false, false), rows.map { it.due })
        assertEquals("дело на завтра", rows[2].sub)
        assertEquals("по месту", rows[3].sub)
    }

    @Test
    fun `Вернулось — дело потом, чей день настал`() {
        val back = t("b", "Позвонить Ивану", backOn = "2026-10-15").copy(backSaid = "на неделе", createdAt = "2026-10-13T10:00:00+03:00")
        val future = t("f", backOn = "2026-10-19")
        val withDue = t("d", backOn = "2026-10-14", due = "2026-10-20")
        val taken = t("n", backOn = "2026-10-14", focus = today)
        val waiting = t("w", backOn = "2026-10-14", ball = Dela.WAITING)
        val s = snap(back, future, withDue, taken, waiting)
        assertEquals(listOf("b"), DelaGroups.backToday(s, "me", today).map { it.id })
        val row = TodayAgenda.backRows(s, "me", today).single()
        assertEquals("ты сказал «на неделе» 13.10", row.sub)
        assertEquals("2026-10-19", TodayAgenda.backLater(today))
        // Метка в списках: «↩ пн 19.10»; у дела со сроком и у напоминания — нет.
        assertEquals("↩ пн 19.10", DelaViews.backChip(future, today))
        assertEquals(null, DelaViews.backChip(withDue, today))
        assertTrue("↩ пн 19.10" in DelaViews.chips(future, null, s, today))
    }

    @Test
    fun `напоминание без времени и места — дело потом, получило срок — дело`() {
        val r = t("r", kind = Dela.KIND_REMIND, remindAt = "2026-10-15T12:00:00+03:00")
        val s = snap(r)
        fun set(o: JSONObject) = Dela.overlay(s, listOf(Dela.setOp("r", o, JSONObject(), "op")), "me", today, "2026-10-15T10:00:00+03:00").tasks["r"]!!
        val noTime = set(JSONObject().put("remind_at", JSONObject.NULL))
        assertEquals(Dela.KIND_TASK, noTime.kind)
        assertEquals("2026-10-16", noTime.backOn)
        val dated = set(JSONObject().put("due_date", "2026-10-16"))
        assertEquals(Dela.KIND_TASK, dated.kind)
        assertEquals("напоминание остаётся у дела", "2026-10-15T12:00:00+03:00", dated.remindAt)
        // Пт–вс → пн.
        assertEquals("2026-10-19", Dela.nextWorkday("2026-10-16"))
        assertEquals("2026-10-19", Dela.nextWorkday("2026-10-17"))
    }

    // ------------------------------------------------------------ календарь

    @Test
    fun `своя отметка Свободен — не встреча, а строка отметка`() {
        val start = ms("2026-10-15T12:00:00+03:00")
        fun ev(title: String, free: Boolean, allDay: Boolean = false, primary: Boolean = true) =
            CalEvent(1, 1, title, start, start + 1_800_000L, allDay, false, free, "a@b", primary)
        val mark = ev("Отправить счёт", free = true)
        val meeting = ev("Созвон с Ромашкой", free = false)
        assertFalse("Засечка отметку встречей не считает", CalendarRules.eligible(mark, null))
        assertTrue(CalendarRules.mark(mark, null))
        assertFalse(CalendarRules.mark(meeting, null))
        assertFalse("весь день — не отметка часа", CalendarRules.mark(ev("Отпуск", free = true, allDay = true), null))
        assertFalse("чужой календарь не смотрим", CalendarRules.mark(ev("Боря — бассейн", free = true, primary = false), null))
        val rows = TodayAgenda.reminderRows(Dela.Snapshot(), "", today, start - 1, listOf(mark), msk)
        assertEquals("отметка", rows.single().sub)
        assertTrue(rows.single().mark)
        assertEquals("12:00", rows.single().whenText)
    }

    @Test
    fun `дело-созвон и его событие — одна строка`() {
        val dayStart = ms("2026-10-15T00:00:00+03:00")
        val call = t("c", "Созвон с Женей", due = today).copy(dueTime = "12:30")
        val other = t("o", "Позвонить в банк", due = today).copy(dueTime = "12:30")
        val ev = CalEvent(1, 1, "Созвон с Женей", ms("2026-10-15T12:30:00+03:00"), ms("2026-10-15T13:00:00+03:00"), false, false, false, "a@b", true)
        assertEquals(listOf("o"), TodayAgenda.dropTwinCalls(listOf(call, other), listOf(ev), dayStart).map { it.id })
        assertEquals(2, TodayAgenda.dropTwinCalls(listOf(call, other), emptyList(), dayStart).size)
    }

    @Test
    fun `встреча с идеями — подпись с числом идей`() {
        val s = Dela.Snapshot(
            projects = mapOf("p1" to Dela.Project("p1", "ООО Ромашка", aliases = listOf("Ромашка"))),
            people = mapOf("iv" to Dela.Person("iv", "Иван Тестов", short = "Иван"), "iv2" to Dela.Person("iv2", "Иван Петров", short = "Иван"),
                "ol" to Dela.Person("ol", "Ольга Тестова", short = "Ольга")),
            ideas = mapOf(
                "i1" to Dela.Idea("i1", "косткаттинг", projectId = "p1"),
                "i2" to Dela.Idea("i2", "позвать на семинар", personId = "ol"),
                "i3" to Dela.Idea("i3", "про Ивана", personId = "iv"),
            ),
        )
        fun ev(title: String) = CalEvent(1, 1, title, 0L, 1L, false, false, false, "a@b", true)
        assertEquals(listOf("i1"), TodayAgenda.meetingIdeas(ev("Ромашка: квартальный"), s).map { it.id })
        assertEquals(listOf("i2"), TodayAgenda.meetingIdeas(ev("Встреча с Ольгой"), s).map { it.id })
        assertTrue("два Ивана — ничьих идей", TodayAgenda.meetingIdeas(ev("Созвон с Иваном"), s).isEmpty())
        val rows = TodayAgenda.rows(emptyList(), listOf(ev("Ромашка: квартальный").copy(start = 10, end = 20)), 0L, 5L) { e -> TodayAgenda.meetingIdeas(e, s).size }
        assertEquals("встреча · 💡 1", rows.single().sub)
    }

    // ------------------------------------------------------------ запас прежней Разноской

    @Test
    fun `запас без модели захвата — против общих случаев сервера`() {
        val cases = JSONObject(file("server/contract/capture-cases.json").readText()).getJSONArray("cases")
        val nowA = JSONObject(file("server/contract/capture-cases.json").readText()).getJSONObject("now").getString("a")
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("now") != "a") continue
            val want = c.optJSONObject("task") ?: continue
            val bucket = c.getString("bucket")
            // Как вернула бы дело Разноска телефона: напоминание с тем же мигом (и, бывает, со сроком того же дня).
            val parsed = when (bucket) {
                "remind" -> t("x", want.getString("title"), remindAt = want.getString("remind_at"), due = DelaGroups.mskDay(want.getString("remind_at")))
                "place" -> t("x", want.getString("title"), place = want.getString("remind_place"))
                else -> if (c.getString("form") == "Г") t("x", want.getString("title")) else continue
            }
            val got = CaptureFallback.apply(parsed, nowA.take(10))
            when (bucket) {
                "remind", "place" -> {
                    assertEquals(c.getString("say"), Dela.KIND_REMIND, got.kind)
                    assertEquals(c.getString("say"), "", got.dueDate)
                }
                else -> assertEquals(c.getString("say"), want.getString("back_on"), got.backOn)
            }
            checked++
        }
        assertTrue("проверено $checked", checked >= 8)
        // Дело со своим сроком и часом — дело с напоминанием, не трогаем; «жду» и «отбить» — не «потом».
        val withTime = t("x", due = "2026-10-16", remindAt = "2026-10-15T09:00:00+03:00").copy(dueTime = "15:00")
        assertEquals(withTime, CaptureFallback.apply(withTime, today))
        assertEquals("", CaptureFallback.apply(t("w", ball = Dela.WAITING), today).backOn)
        assertEquals("", CaptureFallback.apply(t("o", owed = true), today).backOn)
        assertEquals("с плашки чужого текста — без «потом»", "", CaptureFallback.apply(t("p"), today, back = false).backOn)
        // В операцию — вид и день возврата (сервер с `capture` их знает).
        val op = Dela.createOp(CaptureFallback.apply(t("x", "Купить магний"), today), "op")
        assertEquals("2026-10-16", op.getJSONObject("task").getString("back_on"))
        assertFalse(op.getJSONObject("task").has("kind"))
        val rem = Dela.createOp(CaptureFallback.apply(t("y", remindAt = "2026-10-15T12:00:00+03:00", due = today), today), "op2")
        assertEquals("remind", rem.getJSONObject("task").getString("kind"))
        assertFalse(rem.getJSONObject("task").has("due_date"))
        // Обычное дело с телефона — без полей захвата: старый сервер их не увидит.
        val plain = Dela.createOp(t("z", "Позвонить"), "op3").getJSONObject("task")
        assertFalse(plain.has("kind") || plain.has("back_on") || plain.has("back_said"))
    }
}
