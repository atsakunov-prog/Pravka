package ru.zf.pravka.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Виды телефона — как в вебе (`server/pravka_dela/static/app.js`): разделы и
 * их числа, группировки, подпись строки, «Сейчас», «Новое», поиск, человек.
 * Владелец открывает Дела то на ПК, то на телефоне — «Просрочено» и «жду
 * Иван · 3 дн.» должны значить одно и то же в обоих местах.
 */
class DelaViewsTest {

    private fun contract(): JSONObject {
        val f = listOf(File("../server/contract/dela.json"), File("server/contract/dela.json")).first { it.isFile }
        return JSONObject(f.readText())
    }

    // Понедельник, 5 октября 2026.
    private val today = "2026-10-05"
    private val nowIso = "2026-10-05T09:00:00+03:00"
    private val now = java.time.OffsetDateTime.parse(nowIso).toInstant().toEpochMilli()

    private fun snap(vararg extra: Dela.Task): Dela.Snapshot {
        val s = Dela.merge(Dela.Snapshot(), contract().getJSONObject("sync_response"), now)
        return Dela.derive(s.copy(tasks = s.tasks + extra.associateBy { it.id }))
    }

    private fun task(id: String, f: (Dela.Task) -> Dela.Task = { it }) =
        f(Dela.Task(id = id, title = id, ownerId = "sasha", createdBy = "sasha", createdAt = "2026-09-01T10:00:00+03:00", updatedAt = nowIso))

    @Test
    fun `по датам — те же группы и порядок, что в вебе`() {
        val s = snap(
            task("late") { it.copy(dueDate = "2026-10-01") },
            task("today") { it.copy(dueDate = today) },
            task("tomorrow") { it.copy(dueDate = "2026-10-06") },
            task("thu") { it.copy(dueDate = "2026-10-08") },
            task("next") { it.copy(dueDate = "2026-10-15") },
            task("later") { it.copy(dueDate = "2026-11-20") },
            task("nodate"),
            task("done") { it.copy(status = Dela.DONE, dueDate = "2026-10-01") },
        )
        val items = s.tasks.values.filter { it.ownerId == "sasha" && it.id != "2b1f0c9e-6a51-4f3e-9c3a-0d6f1b2a7e10" }
        val g = DelaViews.group(items, DelaViews.By.DATE, s, today)
        assertEquals(
            listOf("Просрочено", "Сегодня", "Завтра", "Четверг, 8 октября", "Следующая неделя", "Позже", "Без даты", "Сделано"),
            g.map { it.title },
        )
        assertTrue(g.first().late)
        assertEquals("Понедельник, 5 октября", g[1].sub)
        assertEquals("Вторник, 6 октября", g[2].sub)
        assertEquals(listOf("late"), g.first().items.map { it.id })
    }

    @Test
    fun `по проектам — без проекта первыми, по людям — без человека последним`() {
        val s = snap(task("inbox"), task("solo"))
        val all = s.tasks.values.toList()
        val byProject = DelaViews.group(all, DelaViews.By.PROJECT, s, today)
        assertEquals(listOf("Без проекта", "Бета Групп"), byProject.map { it.title })
        assertEquals("", byProject.first().projectId)
        val byPerson = DelaViews.group(all, DelaViews.By.PERSON, s, today)
        assertEquals(listOf("Иван", "Без человека"), byPerson.map { it.title })
        assertEquals(s.people.values.single().id, byPerson.first().personId)
        val byBall = DelaViews.group(all, DelaViews.By.BALL, s, today)
        assertEquals(listOf("Моё", "Жду"), byBall.map { it.title })
        assertEquals(listOf("Без сделки"), DelaViews.group(all, DelaViews.By.DEAL, s, today).map { it.title })
        assertEquals(1, DelaViews.group(all, DelaViews.By.NONE, s, today).size)
        assertTrue(DelaViews.group(emptyList(), DelaViews.By.NONE, s, today).isEmpty())
    }

    @Test
    fun `срок строкой — как dueLabel веба`() {
        fun d(due: String, time: String = "", open: Boolean = true) =
            DelaViews.due(task("x") { it.copy(dueDate = due, dueTime = time, status = if (open) Dela.OPEN else Dela.DONE) }, today)
        assertEquals("02.10 · 3 дн. назад", d("2026-10-02")!!.text)
        assertTrue(d("2026-10-02")!!.late)
        assertFalse(d("2026-10-02", open = false)!!.late)
        assertEquals("сегодня 10:00", d(today, "10:00:00")!!.text)
        assertEquals("завтра", d("2026-10-06")!!.text)
        assertEquals("чт 08.10", d("2026-10-08")!!.text)
        assertEquals("12.10", d("2026-10-12")!!.text)
        assertEquals("05.01.27", d("2027-01-05")!!.text)
        assertNull(DelaViews.due(task("x"), today))
    }

    @Test
    fun `подписи строки — мяч с давностью, человек, проект, минуты, метки`() {
        val s = snap()
        val t = s.task("57")!!.copy(waitingSince = "2026-10-02")
        // Не группа по датам: срок виден; не страница проекта: проект виден.
        assertEquals(listOf("сб 10.10", "Бета Групп", "жду Иван · 3 дн.", "10 м", "звонок"), DelaViews.chips(t, null, s, today))
        // Группа по датам — срок молчит (он в заголовке).
        assertEquals(listOf("Бета Групп", "жду Иван · 3 дн.", "10 м", "звонок"), DelaViews.chips(t, DelaViews.By.DATE, s, today))
        // Группа по мячу: мяч в заголовке, а человек остаётся — «@Иван» (как в вебе).
        assertEquals(listOf("сб 10.10", "Бета Групп", "@Иван", "10 м", "звонок"), DelaViews.chips(t, DelaViews.By.BALL, s, today))
        assertEquals(listOf("сб 10.10", "жду Иван · 3 дн.", "10 м", "звонок"), DelaViews.chips(t, null, s, today, onProjectPage = true))
        // Моё с человеком — «@Иван»; без проекта — «без проекта» (строка рисует его кнопкой выбора проекта).
        val mine = t.copy(ball = Dela.MINE, projectId = "", labels = emptyList(), estimateMin = 0, dueDate = "")
        assertEquals(listOf(DelaViews.NO_PROJECT, "@Иван"), DelaViews.chips(mine, null, s, today))
        assertEquals(listOf("без проекта"), DelaViews.chips(mine, DelaViews.By.PERSON, s, today))
        // Просроченный срок виден и в группе по датам.
        assertEquals("01.10 · 4 дн. назад", DelaViews.chips(mine.copy(dueDate = "2026-10-01"), DelaViews.By.DATE, s, today).first())
    }

    @Test
    fun `сейчас — до пяти, выбрать на сегодня и завтра, числа меню как count веба`() {
        val s = snap(
            task("now") { it.copy(focusOn = today, dueDate = "2026-10-01") },
            task("overdue") { it.copy(dueDate = "2026-10-03") },
            task("nudge") { it.copy(ball = Dela.WAITING, nudgeOn = today) },
            task("other") { it.copy(createdBy = "natasha", createdAt = "2026-10-04T10:00:00+03:00", dueDate = "2026-10-20") },
            task("soon") { it.copy(dueDate = "2026-10-09") },
            task("far") { it.copy(dueDate = "2026-12-01") },
            task("tomorrow") { it.copy(dueDate = "2026-10-06") },
            task("agenda") { it.copy(ball = Dela.AGENDA, dueDate = "2026-10-02") },
        )
        val n = DelaViews.now(s, "sasha", today, "all")
        assertEquals(listOf("now"), n.now.map { it.id })
        // «Выбрать на сегодня» — только моё (мяч у меня) и не то, что уже в «Сейчас».
        assertEquals(listOf("overdue"), n.pick.map { it.id })
        assertEquals(listOf("tomorrow"), n.next.map { it.id })
        assertEquals(4, n.left)
        assertEquals("Выбрать на сегодня: просрочено и на сегодня", n.pickTitle)
        val c = DelaViews.counts(s, "sasha", today, "all", now)
        assertEquals(1, c.now)
        assertEquals("1 из 5", c.label(DelaViews.View.NOW))
        // «Предстоящее» — со сроком до недели вперёд, просроченное тоже (оно теперь наверху «Предстоящего»).
        assertEquals(6, c.upcoming)  // now, overdue, agenda, tomorrow, soon (09.10) и #57 (10.10)
        assertTrue(c.upcomingHot)
        assertEquals(2, c.waiting)  // #57 и nudge
        assertTrue(c.waitingHot)
        // «Новое» — как newCount веба: восемь без проекта (#57 — в проекте) и поставленное другими;
        // «other» и то и другое — веб считает его дважды, и телефон так же.
        assertEquals(9, c.new)
        assertEquals("9", c.label(DelaViews.View.NEW))
        assertEquals("", c.label(DelaViews.View.WEEK))
        // «Предстоящее» — с датой; «И без даты» — все; «Только мяч у меня» — без «жду».
        assertEquals(8, DelaViews.upcoming(s, "sasha", "all", mineOnly = false, withUndated = false).size)
        assertEquals(6, DelaViews.upcoming(s, "sasha", "all", mineOnly = true, withUndated = true).size)
    }

    @Test
    fun `сейчас — шестое не встаёт, уже стоящее не считается дважды`() {
        val five = (1..5).map { i -> task("n$i") { it.copy(focusOn = today) } }
        val s = snap(*five.toTypedArray(), task("sixth"), task("old") { it.copy(focusOn = "2026-10-04") },
            task("closed") { it.copy(focusOn = today, status = Dela.DONE) }, task("foreign") { it.copy(focusOn = today, ownerId = "natasha") })
        // Вчерашняя отметка, закрытое и чужое — не в «Сейчас».
        assertEquals(5, DelaViews.nowTasks(s, "sasha", today).size)
        assertFalse(DelaViews.nowRoom(s, "sasha", today, listOf("sixth")))
        assertTrue(DelaViews.nowRoom(s, "sasha", today, listOf("n1")))
        assertEquals("В «Сейчас» уже 5 — сначала убери одно", DelaViews.NOW_FULL)
        val n = DelaViews.now(s, "sasha", today, "all")
        assertEquals(0, n.left)
        assertEquals("Ещё на сегодня и просрочено", n.pickTitle)
        // Места нет — «Завтра» не предлагается.
        assertTrue(DelaViews.now(snap(*five.toTypedArray(), task("t") { it.copy(dueDate = "2026-10-06") }), "sasha", today, "all").next.isEmpty())
        // «Сейчас» — на день, а не на сферу: дом и работа делят те же пять.
        val home = s.copy(tasks = s.tasks.mapValues { (_, t) -> if (t.id == "n1") t.copy(sphere = "home") else t })
        assertEquals(5, DelaViews.nowTasks(home, "sasha", today).size)
        assertEquals(5, DelaViews.counts(home, "sasha", today, "work", now).now)
    }

    @Test
    fun `новое — без проекта, поставленное другими, закрытое само до «Понятно»`() {
        val s0 = snap(
            task("loose"),
            task("other") { it.copy(createdBy = "natasha", createdAt = "2026-10-04T10:00:00+03:00") },
            task("old-other") { it.copy(createdBy = "natasha", createdAt = "2026-09-20T10:00:00+03:00") },
        )
        val inBeta = s0.projects.values.single().id
        val s = s0.copy(tasks = s0.tasks.mapValues { (_, t) -> if (t.id == "other" || t.id == "old-other") t.copy(projectId = inBeta) else t })
        assertEquals(listOf("other"), DelaViews.fromOthers(s, "sasha", "all", now).map { it.id })
        assertEquals(listOf("loose"), DelaViews.noProject(s, "sasha", "all").map { it.id })
        val auto = Dela.Suggestion(id = "a1", forUser = "sasha", kind = "close", taskId = "loose", status = "accepted",
            payload = "{\"auto\": true}", reason = "закрыто само", decidedAt = "2026-10-05T08:00:00+03:00")
        val withAuto = s.copy(suggestions = s.suggestions + ("a1" to auto))
        val base = DelaViews.newCount(s, "sasha", "all", now)
        assertEquals(base + 1, DelaViews.newCount(withAuto, "sasha", "all", now))
        // «Понятно» — seen_at: из «Нового» уходит, и сразу, до ответа сервера.
        val seen = Dela.overlay(withAuto, listOf(Dela.seenOp(listOf("a1"))), "sasha", today, nowIso)
        assertTrue(Dela.autoDone(seen, "sasha", now).isEmpty())
        assertEquals(base, DelaViews.newCount(seen, "sasha", "all", now))
    }

    @Test
    fun `жду — пора напомнить первой группой, остальное по людям`() {
        val s = snap(
            task("nudge") { it.copy(ball = Dela.WAITING, nudgeOn = "2026-10-04") },
            task("due") { it.copy(ball = Dela.WAITING, dueDate = today) },
            task("later") { it.copy(ball = Dela.WAITING, nudgeOn = "2026-10-09") },
        )
        val g = DelaViews.waitingGroups(s, "sasha", today, "all")
        assertEquals("Пора напомнить", g.first().title)
        assertTrue(g.first().late)
        assertEquals(setOf("nudge", "due"), g.first().items.map { it.id }.toSet())
        // #57 у Ивана — по людям; без человека — последним.
        assertEquals(listOf("Иван", "Без человека"), g.drop(1).map { it.title })
    }

    @Test
    fun `разделы — старые ключи ведут туда, где их дела теперь`() {
        assertEquals(listOf("now", "new", "upcoming", "waiting", "week", "all"), DelaViews.View.entries.map { it.key })
        assertEquals(DelaViews.View.NOW, DelaViews.View.of("morning"))
        assertEquals(DelaViews.View.NEW, DelaViews.View.of("inbox"))
        assertEquals(DelaViews.View.WAITING, DelaViews.View.of("waiting"))
        assertEquals(DelaViews.View.NOW, DelaViews.View.of("непонятное"))
    }

    @Test
    fun `наговорки — ответ вида dictations, дела из своей копии, что сказала автоматика`() {
        val s = snap(task("t1") { it.copy(title = "Позвонить Ивану", status = Dela.DONE) })
        // Форма — store.view_dictations: items[] с ref, at, text, source, tasks (строки v_tasks), notes, touches.
        val v = JSONObject(
            """{"items": [
              {"ref": "raznoska:1759650000000", "at": "2026-10-05T08:15:00+03:00", "text": "позвонить Ивану, и комитет пройден",
               "source": "phone",
               "tasks": [{"id": "t1", "num": 61, "title": "Позвонить Ивану", "status": "open", "owner_id": "sasha",
                          "source": "voice", "source_ref": "raznoska:1759650000000"}],
               "notes": [{"id": "n1", "at": "2026-10-05T08:15:00+03:00", "summary": "Комитет пройден", "project_id": null,
                          "person_ids": [], "source_ref": "raznoska:1759650000000:0"}],
               "touches": [{"id": "s1", "task_id": "t1", "kind": "close", "status": "pending", "source": "telegram",
                            "batch_title": "Telegram · сегодня", "quote": "созвонились", "payload": {}, "created_at": "2026-10-05T09:00:00+03:00",
                            "decided_at": null, "reason": null}]},
              {"ref": "parse:abc", "at": "2026-10-04T19:05:00+03:00", "text": null, "source": "web", "tasks": [], "notes": [], "touches": []}
            ]}""",
        )
        val d = DelaViews.dictations(v)
        assertEquals(2, d.size)
        val a = d.first()
        assertEquals("raznoska:1759650000000", a.ref)
        assertEquals("позвонить Ивану, и комитет пройден", a.text)
        assertEquals("Комитет пройден", a.notes.single().summary)
        // Дело — из своей копии: там оно уже закрыто.
        assertEquals(Dela.DONE, DelaViews.dictTasks(a, s).single().status)
        assertEquals("Telegram · сегодня: закрыть — ждёт решения ниже", DelaViews.touchWord(a.touches.single(), s))
        // Текст не дошёл (старый телефон) — наговорка всё равно видна, без слов.
        assertEquals("", d[1].text)
        assertEquals("в вебе", DelaViews.dictSource(d[1].source))
        val msk = java.time.ZoneId.of("Europe/Moscow")
        assertEquals("сегодня 08:15", DelaViews.whenWords(a.at, today, msk))
        assertEquals("вчера 19:05", DelaViews.whenWords(d[1].at, today, msk))
        assertEquals("01.10 07:00", DelaViews.whenWords("2026-10-01T04:00:00Z", today, msk))
        assertTrue(DelaViews.dictations(JSONObject()).isEmpty())
        assertTrue(DelaViews.dictations(null).isEmpty())
    }

    @Test
    fun `поиск — каждое слово где угодно, и в имени проекта и человека`() {
        val s = snap(task("Сверка с банком"), task("Отчёт Бете") { it.copy(status = Dela.DONE) })
        assertEquals(listOf("57"), DelaViews.search(s, "модель БЕТА", showDone = false).map { it.num.toString() })
        assertEquals(listOf("57"), DelaViews.search(s, "иван прислать", showDone = false).map { it.num.toString() })
        assertTrue(DelaViews.search(s, "отчет", showDone = false).isEmpty())
        assertEquals(listOf("Отчёт Бете"), DelaViews.search(s, "отчет", showDone = true).map { it.title })
        assertTrue(DelaViews.search(s, "  ", showDone = true).isEmpty())
    }

    @Test
    fun `все дела — моё в сфере, сделанные по выбору`() {
        val s = snap(task("inbox"), task("done") { it.copy(status = Dela.DONE) }, task("foreign") { it.copy(ownerId = "natasha") })
        assertEquals(2, DelaViews.list(s, "sasha", "all", showDone = false).size)
        assertEquals(3, DelaViews.list(s, "sasha", "all", showDone = true).size)
    }

    @Test
    fun `человек — разделы и строки карточки веба`() {
        val s = snap()
        val p = s.people.values.single().copy(role = "CFO", emails = listOf("ivan@beta.example"), telegram = "ivanp", birthDay = 10, birthMonth = 10, birthYear = 1980, cadence = "quarter", seeks = "фонды")
        val info = DelaViews.personInfo(p, s).toMap()
        assertEquals("CFO", info["Роль"])
        assertEquals("+79001112233", info["Телефон"])
        assertEquals("@ivanp", info["Telegram"])
        assertEquals("10 октября 1980", info["День рождения"])
        assertEquals("раз в 2–3 месяца", info["Как часто"])
        assertEquals("фонды", info["Ищет"])
        assertFalse(info.containsKey("Заметка"))
        val sec = DelaViews.personSections(Dela.person(s, p.id))
        assertEquals(listOf("Повестка с ним", "Жду от него", "Его просьбы ко мне", "Моё о нём"), sec.map { it.title })
        assertEquals(1, sec[1].items.size)
    }

    @Test
    fun `человек с полями веба — в кэш и обратно`() {
        val s = snap()
        val p = s.people.values.single().copy(emails = listOf("a@b.c"), telegram = "x", birthYear = 1990, seeks = "s", offers = "o", traits = "t", source = "src", note = "n")
        val back = Dela.person(JSONObject(Dela.json(p).toString()))
        assertEquals(p, back)
    }
}
