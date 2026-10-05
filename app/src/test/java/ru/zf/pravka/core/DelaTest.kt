package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.DelaSync
import ru.zf.pravka.provider.parseTasksDela

/**
 * Дела на телефоне против контракта с сервером (`server/contract/dela.json`):
 * сервер держит эту форму своими тестами, телефон — этими. Ключ, названный
 * телефоном иначе, сервер отвергнет (`нет таких полей`) — и правка владельца
 * уйдёт в заметки отказом. Поэтому сверка — до каждого поля операции.
 */
class DelaTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/dela.json"), File("server/contract/dela.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val today = "2026-10-04"
    private val nowIso = "2026-10-04T09:00:00+03:00"
    // 2026-10-04 09:00 МСК
    private val now = java.time.OffsetDateTime.parse(nowIso).toInstant().toEpochMilli()

    private fun snap(): Dela.Snapshot = Dela.merge(Dela.Snapshot(), contract().getJSONObject("sync_response"), now)

    @Test
    fun `ответ синка из контракта читается целиком`() {
        val s = snap()
        assertEquals(1042L, s.seq)
        assertEquals("2026-10-04", s.today)
        val t = s.task("57")!!
        assertEquals("Иван: прислать модель", t.title)
        assertEquals(Dela.WAITING, t.ball)
        assertEquals("Бета Групп", t.projectName)
        assertEquals("work", t.sphere)
        assertEquals("paid", t.moneyEff)
        assertEquals("Иван", t.who)
        assertEquals(listOf("звонок"), t.labels)
        assertEquals(10, t.estimateMin)
        assertEquals("", t.notes)  // JSON null — пусто, а не «null»
        assertEquals(listOf("Бета"), s.projects.values.single().aliases)
        assertEquals(listOf("звонок"), s.labels)
        assertEquals("Саша", s.users["sasha"]!!.name)
        assertEquals(t, s.task("#57"))
        assertEquals(t, s.task(t.id))
    }

    @Test
    fun `кэш на диск и обратно — те же строки`() {
        val s = snap()
        val back = Dela.fromJson(JSONObject(Dela.toJson(s).toString()))
        assertEquals(s.tasks, back.tasks)
        assertEquals(s.projects, back.projects)
        assertEquals(s.people, back.people)
        assertEquals(s.labels, back.labels)
        assertEquals(s.seq, back.seq)
    }

    @Test
    fun `операции телефона — ровно форма контракта`() {
        val c = contract().getJSONObject("ops_request").getJSONArray("ops")
        val byOp = (0 until c.length()).map { c.getJSONObject(it) }.associateBy { it.getString("op") }
        val s = snap()
        val t = s.task("57")!!

        val create = Dela.createOp(t.copy(id = Dela.newId(), source = "voice"))
        assertKeys(byOp.getValue("task.create"), create)
        // Поля дела — подмножество полей контракта: сервер других не примет.
        val allowed = byOp.getValue("task.create").getJSONObject("task").keys().asSequence().toSet() +
            setOf("notes", "deal_id", "nudge_on", "requested_by", "due_time", "money", "want", "focus_on", "source_ref")
        assertTrue(create.getJSONObject("task").keys().asSequence().toSet().minus(allowed).isEmpty())

        val (set, was) = Dela.diff(t, t.copy(dueDate = "2026-10-12"))
        val setOp = Dela.setOp(t.id, set, was)
        assertKeys(byOp.getValue("task.set"), setOp)
        assertEquals("2026-10-12", set.getString("due_date"))
        assertEquals("2026-10-10", was.getString("due_date"))
        assertEquals(1, set.length())

        assertKeys(byOp.getValue("task.done"), Dela.statusOp("task.done", t.id))
        assertKeys(byOp.getValue("comment.add"), Dela.commentOp(t.id, "звонил"))
        val c1 = Dela.commentOp(t.id, "звонил").getJSONObject("comment")
        assertEquals(setOf("id", "task_id", "text"), c1.keys().asSequence().toSet())
        assertKeys(byOp.getValue("suggestion.decide"), Dela.decideOp("x", accept = false, reason = "это идея, не дело"))
        // Операции, которые шлёт телефон, — все из списка сервера.
        val list = contract().getJSONArray("ops_list").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        for (op in listOf("task.create", "task.set", "task.done", "task.reopen", "task.cancel", "comment.add", "suggestion.decide", "interaction.add")) {
            assertTrue(op, op in list)
        }
    }

    @Test
    fun `заметка Разноски — interaction add с полями сервера`() {
        val op = Dela.noteOp("n1", "комитет пройден", nowIso, "p1", listOf("pe1"), "raznoska:1:0", "op1")
        assertEquals("interaction.add", op.getString("op"))
        val d = op.getJSONObject("data")
        assertEquals("note", d.getString("kind"))
        assertEquals("raznoska", d.getString("source"))
        // INTERACTION_FIELDS сервера + id.
        val allowed = setOf("id", "at", "kind", "summary", "next_step", "project_id", "deal_id", "person_ids", "source", "source_ref")
        assertTrue(d.keys().asSequence().toSet().minus(allowed).isEmpty())
    }

    @Test
    fun `частичный синк — поверх по rev, полный — целиком`() {
        val s = snap()
        val t = s.task("57")!!
        val newer = Dela.json(t.copy(title = "Иван: прислать модель v2", rev = 2, seq = 1050))
        val older = Dela.json(t.copy(title = "старое", rev = 1, seq = 1040))
        val r1 = JSONObject().put("full", false).put("seq", 1050).put("today", today).put("tasks", JSONArray().put(newer))
        val s1 = Dela.merge(s, r1, now)
        assertEquals("Иван: прислать модель v2", s1.task("57")!!.title)
        assertEquals(1050L, s1.seq)
        // Окно перекрытия прислало старую строку — не откатываемся.
        val r2 = JSONObject().put("full", false).put("seq", 1050).put("tasks", JSONArray().put(older))
        assertEquals("Иван: прислать модель v2", Dela.merge(s1, r2, now).task("57")!!.title)
        // Справочник остался — частичный ответ без проектов его не стирает.
        assertEquals(1, Dela.merge(s1, r2, now).projects.size)
        // full — выбросить и взять целиком.
        val r3 = JSONObject().put("full", true).put("seq", 7).put("tasks", JSONArray())
        assertTrue(Dela.merge(s1, r3, now).tasks.isEmpty())
    }

    @Test
    fun `очередь поверх копии — своя правка видна до ответа сервера`() {
        val s = snap()
        val t = s.task("57")!!
        val newId = Dela.newId()
        val ops = listOf(
            Dela.createOp(Dela.Task(id = newId, title = "Наташа: сверка", ball = Dela.WAITING, personId = t.personId, projectId = t.projectId)),
            Dela.setOp(t.id, JSONObject().put("ball", Dela.MINE), JSONObject().put("ball", Dela.WAITING)),
            Dela.statusOp("task.done", newId),
            Dela.commentOp(t.id, "звонил", commentId = "c1"),
        )
        val v = Dela.overlay(s, ops, "sasha", today, nowIso)
        val created = v.tasks[newId]!!
        assertTrue(created.local)
        assertEquals("Бета Групп", created.projectName)  // имя — из справочника, до сервера
        assertEquals("Иван", created.who)
        assertEquals(Dela.DONE, created.status)
        assertEquals(0, created.num)
        val edited = v.task("57")!!
        assertEquals(Dela.MINE, edited.ball)
        assertEquals("", edited.waitingSince)  // не «жду» — и «жду с» пусто, как у триггера
        assertEquals(listOf("звонил"), v.commentsOf(t.id).map { it.text })
        // Сама копия не тронута.
        assertEquals(Dela.WAITING, s.task("57")!!.ball)
    }

    @Test
    fun `«жду» ставит дату само`() {
        val t = Dela.Task(id = "a", title = "x")
        val w = Dela.applyFields(t, JSONObject().put("ball", Dela.WAITING), today, nowIso)
        assertEquals(today, w.waitingSince)
    }

    @Test
    fun `принятое и отклонённое предложение уходит из «Нового» сразу`() {
        val sg = JSONObject().put("id", "s1").put("for_user", "sasha").put("kind", "create")
            .put("payload", JSONObject().put("title", "Пётр: прислать договор").put("project_name", "бета"))
            .put("source", "meeting").put("batch_ref", "m1").put("batch_title", "Встреча с Бетой").put("status", "pending").put("rev", 1).put("seq", 1043)
        val sg2 = JSONObject(sg.toString()).put("id", "s2").put("payload", JSONObject().put("title", "Саша: позвонить"))
        val s = Dela.merge(snap(), JSONObject().put("full", false).put("seq", 1043).put("suggestions", JSONArray().put(sg).put(sg2)), now)
        val batches = Dela.newBatches(s, "sasha")
        assertEquals(1, batches.size)
        assertEquals("Встреча с Бетой", batches[0].title)
        assertEquals(2, batches[0].items.size)
        assertEquals(2, Dela.morning(s, "sasha", today, "all", now).newCount)
        val v = Dela.overlay(s, listOf(Dela.decideOp("s1", accept = true), Dela.decideOp("s2", accept = false, reason = "дубль")), "sasha", today, nowIso)
        assertTrue(Dela.newOnes(v, "sasha").isEmpty())
        // Черновик предложения: имя проекта — в id по алиасу справочника.
        val draft = Dela.draftOf(s.suggestions["s1"]!!, s, "sasha")
        assertEquals(s.projects.values.single().id, draft.projectId)
        assertEquals("Пётр: прислать договор", draft.title)
    }

    @Test
    fun `виды — как store py`() {
        val s = snap()
        val t = s.task("57")!!
        val pid = t.projectId
        fun task(id: String, f: (Dela.Task) -> Dela.Task) = f(Dela.Task(id = id, title = id, ownerId = "sasha", createdBy = "sasha", projectId = pid, createdAt = nowIso, updatedAt = nowIso))
        val rows = listOf(
            task("focus") { it.copy(focusOn = today) },
            task("overdue") { it.copy(dueDate = "2026-10-01") },
            task("nudge") { it.copy(ball = Dela.WAITING, personId = t.personId, waitingSince = "2026-09-20", nudgeOn = today) },
            task("paid") { it.copy(money = "paid") },
            task("other") { it.copy(createdBy = "natasha", money = "none") },
            task("quick") { it.copy(estimateMin = 5, dueDate = "2026-11-01", money = "none") },
            task("home") { it.copy(projectId = "", dueDate = "2026-10-02") },
            task("done") { it.copy(status = Dela.DONE, dueDate = "2026-10-01") },
            task("foreign") { it.copy(ownerId = "natasha", dueDate = "2026-10-01") },
            task("stale") { it.copy(dueDate = "2026-09-01", money = "none") },
        )
        val s2 = Dela.derive(s.copy(tasks = s.tasks + rows.associateBy { it.id }))
        val m = Dela.morning(s2, "sasha", today, "all", now)
        assertEquals(listOf("focus"), m.now.map { it.id })
        // Входящие — и в Работе: «inbox» показывается в обеих сферах.
        assertEquals(listOf("stale", "overdue", "home"), m.today.map { it.id })
        assertEquals(listOf("nudge"), m.nudge.map { it.id })
        // Раздела «оплачено, без срока» нет с 05.10.2026 (деньги дела убраны
        // из интерфейса): оплаченное без срока в «Утро» не просится.
        assertTrue(m.now.plus(m.today).plus(m.nudge).plus(m.fromOthers).none { it.id == "paid" })
        assertEquals(listOf("other"), m.fromOthers.map { it.id })
        // Дело в первом подходящем разделе и больше нигде.
        val shown = (m.now + m.today + m.nudge + m.fromOthers).map { it.id }
        assertEquals(shown.size, shown.toSet().size)
        assertEquals(listOf("quick"), Dela.quick(s2, "sasha", "all").map { it.id })
        assertTrue(Dela.morning(s2, "sasha", today, "home", now).today.map { it.id } == listOf("home"))

        val w = Dela.waiting(s2, "sasha", "all")
        assertEquals("Иван", w.single().person)
        assertEquals(setOf("57", "nudge"), w.single().items.map { if (it.num > 0) it.num.toString() else it.id }.toSet())

        val wk = Dela.week(s2, "sasha", today, "all", now)
        assertEquals(listOf("stale"), wk.stale.map { it.id })
        // «Жду» с 20.09, но напомнить назначено на сегодня — это не молчание.
        assertTrue(wk.waitingStale.isEmpty())
        val silent = Dela.derive(s2.copy(tasks = s2.tasks + ("nudge" to s2.tasks.getValue("nudge").copy(nudgeOn = ""))))
        assertEquals(listOf("nudge"), Dela.week(silent, "sasha", today, "all", now).waitingStale.map { it.id })
        assertTrue(wk.noNextStep.isEmpty())  // у платной Беты есть моё открытое

        val p = Dela.project(s2, pid)
        assertTrue(p.open.none { it.id == "done" })
        assertEquals(listOf("done"), p.done.map { it.id })
        assertEquals(listOf("57"), Dela.person(s2, t.personId).waiting.filter { it.num > 0 }.map { it.num.toString() })
        assertEquals(listOf("57"), Dela.search(s2, "МОДЕЛЬ").map { it.num.toString() })
        assertEquals("Входящие первыми", null, Dela.byProject(s2, "sasha", "all").first().first)
    }

    @Test
    fun `проекты — как боковая панель веба`() {
        val s0 = snap()
        val beta = s0.projects.values.single()
        val ivan = s0.people.values.single()
        val zf = Dela.Project(id = "zf", name = "ЗФ", kind = "internal", sphere = "work", ownerId = "sasha")
        val family = Dela.Project(id = "fam", name = "Семья", kind = "personal", sphere = "home", ownerId = "sasha")
        val old = Dela.Project(id = "old", name = "Старый клиент", kind = "client", archivedAt = "2026-05-01T00:00:00+03:00")
        fun task(id: String, f: (Dela.Task) -> Dela.Task) = f(Dela.Task(id = id, title = id, ownerId = "sasha", createdBy = "sasha", createdAt = nowIso, updatedAt = nowIso))
        val tasks = listOf(
            task("late") { it.copy(projectId = beta.id, dueDate = "2026-10-01") },
            task("fam1") { it.copy(projectId = "fam", personId = ivan.id) },
            task("inbox") { it.copy(dueDate = "2026-10-02") },
            task("closed") { it.copy(projectId = "zf", status = Dela.DONE) },
        )
        val s = Dela.derive(s0.copy(
            projects = s0.projects + listOf(zf, family, old).associateBy { it.id },
            tasks = s0.tasks + tasks.associateBy { it.id },
        ))
        val nav = Dela.projectsNav(s, "sasha", today, "all", setOf("zf"))
        // Избранное сверху и в своих группах больше не повторяется.
        assertEquals(listOf("ЗФ"), nav.favorites.map { it.project.name })
        assertEquals(0, nav.favorites.single().open)  // закрытое не считается
        assertEquals(listOf("Клиенты", "Личное"), nav.groups.map { it.first })
        val betaRow = nav.groups.first { it.first == "Клиенты" }.second.single()
        assertEquals(2, betaRow.open)  // #57 и просроченное
        assertTrue(betaRow.late)
        assertFalse(nav.groups.first { it.first == "Личное" }.second.single().late)
        // Люди — с кем больше открытых дел; архив — отдельно; «Входящие» — моё без проекта.
        assertEquals(listOf(ivan.id to 2), nav.people.map { it.first.id to it.second })
        assertEquals(listOf("Старый клиент"), nav.archived.map { it.project.name })
        assertEquals(1, nav.inbox)
        assertTrue(nav.inboxLate)
        // Сфера «Работа» прячет домашний проект, но не архив и не людей.
        val work = Dela.projectsNav(s, "sasha", today, "work", emptySet())
        assertEquals(listOf("Клиенты", "Внутреннее"), work.groups.map { it.first })
        assertEquals(1, work.archived.size)
        // «Входящие» — страница проекта с пустым id: дела без проекта.
        assertEquals(listOf("inbox"), Dela.project(s, "").open.map { it.id })
    }

    @Test
    fun `справочник для промпта и поиск по алиасам`() {
        val s = snap()
        val block = Dela.promptCatalog(s)
        assertTrue(block.contains("Бета Групп (Бета) · работа · клиент"))
        assertTrue(block.contains("— Иван (Иван Петров, Ваня)"))
        assertTrue(block.contains("звонок"))
        assertEquals("Бета Групп", Dela.findProject(s, "бета")?.name)
        assertEquals("Бета Групп", Dela.findProject(s, "#Бета Групп / сделка")?.name)
        assertEquals("Иван", Dela.findPerson(s, "ваня")?.label)
        assertNull(Dela.findPerson(s, "Пётр"))
    }

    @Test
    fun `разбор Разноски для Дел — id справочника, мяч и заметки`() {
        val s = snap()
        val reply = """
            {"tasks": [
              {"title": "Иван: прислать модель", "project": "Бета", "person": "Ваня", "ball": "waiting", "due": "2026-10-10", "estimate_min": 10, "money": "", "want": false, "labels": ["звонок", "выдумка"]},
              {"title": "Позвонить Петру", "project": "", "person": "Пётр", "ball": "agenda", "due": "в пятницу"},
              {"title": "Жду кого-то", "ball": "waiting"}
            ],
            "notes": [{"text": "комитет пройден", "project": "Бета Групп", "person": "Иван"}, "выручка 2,6 млрд"]}
        """.trimIndent()
        val (tasks, notes) = parseTasksDela(reply, s)
        assertEquals(3, tasks.size)
        val a = tasks[0]
        assertEquals(s.projects.values.single().id, a.projectId)
        assertEquals("Бета Групп", a.projectName)
        assertEquals(s.people.values.single().id, a.personId)
        assertEquals(Dela.WAITING, a.ball)
        assertEquals(listOf("звонок"), a.labels)  // выдуманной метки нет
        assertEquals("2026-10-10", a.due)
        assertTrue(a.delaId.isNotBlank() && a.opId.isNotBlank() && a.delaId != a.opId)
        // Человека нет в справочнике — имя остаётся, id пуст, мяч как сказан.
        assertEquals("", tasks[1].personId)
        assertEquals("Пётр", tasks[1].personName)
        assertEquals(Dela.AGENDA, tasks[1].ball)
        assertEquals("", tasks[1].due)  // не дата — не срок
        // «Жду» без человека — дело моё.
        assertEquals(Dela.MINE, tasks[2].ball)
        assertEquals(2, notes.size)
        assertEquals(s.projects.values.single().id, notes[0].projectId)
        assertEquals(listOf(s.people.values.single().id), notes[0].personIds)
        assertEquals("выручка 2,6 млрд", notes[1].text)
    }

    @Test
    fun `QR сервера Дел`() {
        val qr = contract().getString("pair_qr")
        val pair = DelaSync.parsePairing(qr)
        assertNotNull(pair)
        assertEquals("https://dela.example.netcraze.pro:8443", pair!!.first)
        assertNull(DelaSync.parsePairing("pravka-archive:{\"url\":\"https://x\",\"token\":\"y\"}"))
        assertEquals("https://dela.example.netcraze.pro:8443/", DelaSync.normalize("dela.example.netcraze.pro:8443/"))
    }

    @Test
    fun `правка без изменений — пустой set`() {
        val t = snap().task("57")!!
        val (set, was) = Dela.diff(t, t.copy())
        assertEquals(0, set.length())
        assertEquals(0, was.length())
        val (set2, _) = Dela.diff(t, t.copy(labels = emptyList(), estimateMin = 0, personId = ""))
        assertTrue(set2.getJSONArray("labels").length() == 0)
        assertTrue(set2.isNull("estimate_min"))
        assertTrue(set2.isNull("person_id"))
        assertFalse(set2.has("title"))
    }

    @Test
    fun `ключи операций — uuid, выведенный ключ постоянен`() {
        assertTrue(Dela.isUuid(Dela.newId()))
        assertEquals(Dela.stableId("razn-undo-x"), Dela.stableId("razn-undo-x"))
        assertTrue(Dela.isUuid(Dela.stableId("razn-note-1-0")))
        assertFalse(Dela.isUuid("razn-undo-x"))
        // Все операции, которые собирает телефон, несут uuid в op_id.
        val t = Dela.Task(id = Dela.newId(), title = "x")
        for (op in listOf(Dela.createOp(t), Dela.statusOp("task.done", t.id), Dela.commentOp(t.id, "к"), Dela.decideOp("s", true),
            Dela.setOp(t.id, JSONObject(), JSONObject()))) {
            assertTrue(op.toString(), Dela.isUuid(op.getString("op_id")))
        }
    }

    /** Ключи операции верхнего уровня и вложенного объекта — как в контракте (вложенные — подмножество). */
    private fun assertKeys(expected: JSONObject, actual: JSONObject) {
        val e = expected.keys().asSequence().toSet()
        val a = actual.keys().asSequence().toSet()
        assertEquals("ключи ${expected.getString("op")}", e - setOf("was", "reason", "set"), a - setOf("was", "reason", "set"))
        for (k in e) {
            val ev = expected.opt(k)
            val av = actual.opt(k)
            if (ev is JSONObject && av is JSONObject && k != "set" && k != "was") {
                val extra = av.keys().asSequence().toSet() - ev.keys().asSequence().toSet() -
                    setOf("notes", "deal_id", "nudge_on", "requested_by", "due_time", "money", "want", "focus_on", "source_ref", "id")
                assertTrue("лишние поля в ${expected.getString("op")}.$k: $extra", extra.isEmpty())
            }
        }
    }
}
