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
        val byGrp = DelaViews.group(all, DelaViews.By.GRP, s, today)
        assertEquals(listOf("Запустить", "Мониторить"), byGrp.map { it.title })
        assertEquals(DelaViews.By.GRP, DelaViews.By.of("ball", DelaViews.By.DATE))
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
    fun `подписи строки — кто ждёт с давностью, человек, проект, минуты, метки`() {
        val s = snap()
        val t = s.task("57")!!.copy(waitingSince = "2026-10-02")
        // Не группа по датам: срок виден; не страница проекта: проект виден.
        assertEquals(listOf("сб 10.10", "Бета Групп", "жду Иван 3 дн", "10 м", "звонок"), DelaViews.chips(t, null, s, today))
        // Группа по датам — срок молчит (он в заголовке).
        assertEquals(listOf("Бета Групп", "жду Иван 3 дн", "10 м", "звонок"), DelaViews.chips(t, DelaViews.By.DATE, s, today))
        // По группам — «жду Иван» остаётся: сколько ждём, заголовок не скажет (как в вебе).
        assertEquals(listOf("сб 10.10", "Бета Групп", "жду Иван 3 дн", "10 м", "звонок"), DelaViews.chips(t, DelaViews.By.GRP, s, today))
        assertEquals(listOf("сб 10.10", "жду Иван 3 дн", "10 м", "звонок"), DelaViews.chips(t, null, s, today, onProjectPage = true))
        // Моё с человеком — «@Иван»; без проекта — «без проекта» (строка рисует его кнопкой выбора проекта).
        val mine = t.copy(ball = Dela.MINE, projectId = "", labels = emptyList(), estimateMin = 0, dueDate = "")
        assertEquals(listOf(DelaViews.NO_PROJECT, "@Иван"), DelaViews.chips(mine, null, s, today))
        assertEquals(listOf("без проекта"), DelaViews.chips(mine, DelaViews.By.PERSON, s, today))
        // Просроченный срок виден и в группе по датам.
        assertEquals("01.10 · 4 дн. назад", DelaViews.chips(mine.copy(dueDate = "2026-10-01"), DelaViews.By.DATE, s, today).first())
    }

    @Test
    fun `числа меню — сейчас, сегодня, вопросы и просроченное у всех дел, отбить горит`() {
        val s = snap(
            task("now") { it.copy(focusOn = today, dueDate = "2026-10-20") },
            task("overdue") { it.copy(dueDate = "2026-10-03") },
            task("nudge") { it.copy(ball = Dela.WAITING, nudgeOn = today) },
            task("due") { it.copy(dueDate = today) },
            task("far") { it.copy(dueDate = "2026-12-01") },
        )
        // 13.10.2026 (`count()` у `VIEWS` веба): «Сейчас» — отмеченное, «Сегодня» — срок ровно сегодня (без
        // просроченного, «Сейчас» и напоминаний), «Все дела» — число просроченного цветом беды.
        val c = DelaViews.counts(s, "sasha", today, "all", now)
        assertEquals(1, c.now)
        assertEquals(1, c.today)
        assertEquals(1, c.late)
        assertFalse(c.todayHot)
        assertEquals("1", c.label(DelaViews.View.NOW))
        assertEquals("1", c.label(DelaViews.View.TODAY))
        assertEquals("1", c.label(DelaViews.View.ALL))
        assertTrue(c.bad(DelaViews.View.ALL))
        assertTrue(c.hot(DelaViews.View.NOW))
        // «Новое» — как newCount веба (08.10.2026): только вопросы.
        assertEquals(0, c.new)
        assertEquals("", c.label(DelaViews.View.NEW))
        assertTrue(DelaViews.counts(snap(task("o") { it.copy(owed = true, dueDate = today) }), "sasha", today, "all", now).todayHot)
        // «Отбить» без срока — уже не «Сегодня»: число не горит.
        assertFalse(DelaViews.counts(snap(task("o") { it.copy(owed = true) }), "sasha", today, "all", now).todayHot)
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
        // «Сейчас» — на день, а не на сферу: дом и работа делят те же пять.
        val home = s.copy(tasks = s.tasks.mapValues { (_, t) -> if (t.id == "n1") t.copy(sphere = "home") else t })
        assertEquals(5, DelaViews.nowTasks(home, "sasha", today).size)
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
        // Сделанное само ответа не ждёт — число «Нового» его не считает (08.10.2026).
        assertEquals(DelaViews.newCount(s, "sasha", "all", now), DelaViews.newCount(withAuto, "sasha", "all", now))
        assertEquals(1, Dela.autoDone(withAuto, "sasha", now).size)
        // «Понятно» — seen_at: из «Нового» уходит, и сразу, до ответа сервера.
        val seen = Dela.overlay(withAuto, listOf(Dela.seenOp(listOf("a1"))), "sasha", today, nowIso)
        assertTrue(Dela.autoDone(seen, "sasha", now).isEmpty())
    }

    @Test
    fun `новое — поставил — открытые, мои, за три дня, свежие сверху, по дням`() {
        val s = snap(
            task("fresh") { it.copy(createdAt = "2026-10-05T08:30:00+03:00") },
            task("yesterday") { it.copy(createdAt = "2026-10-04T18:00:00+03:00") },
            task("two-days") { it.copy(createdAt = "2026-10-03T12:00:00+03:00") },
            task("old") { it.copy(createdAt = "2026-10-01T12:00:00+03:00") },
            task("done") { it.copy(createdAt = "2026-10-05T07:00:00+03:00", status = Dela.DONE) },
            task("foreign") { it.copy(createdAt = "2026-10-05T07:00:00+03:00", ownerId = "natasha") },
            task("by-lena") { it.copy(createdAt = "2026-10-05T07:30:00+03:00", createdBy = "lena") },
        )
        val feed = DelaViews.feed(s, "sasha", "all", now).map { it.id }
        // Контрактное дело #57 заведено 03.10 21:10 — тоже за три дня.
        assertEquals(listOf("fresh", "by-lena", "yesterday", "2b1f0c9e-6a51-4f3e-9c3a-0d6f1b2a7e10", "two-days"), feed)
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val days = DelaViews.feedDays(DelaViews.feed(s, "sasha", "all", now), today, zone)
        assertEquals(listOf("Сегодня", "Вчера", "Суббота, 3 октября"), days.map { it.first })
        // Значок — без номера: откуда и когда; поставил другой — кто.
        val lena = s.tasks.getValue("by-lena")
        assertEquals("поставил(а) lena · сегодня 07:30", DelaViews.markWords(lena, "", s, today, zone))
        val voice = s.tasks.getValue("fresh").copy(source = "meeting")
        assertEquals("встреча: Бета: тизер · сегодня 08:30", DelaViews.markWords(voice, "Бета: тизер", s, today, zone))
        assertEquals(DelaViews.Mark("mic", true, "наговорка"), DelaViews.mark("voice"))
        assertFalse(DelaViews.mark("import").plan)
    }

    @Test
    fun `новое — бейдж только у вопросов, вопрос — ask, проект, человек`() {
        val s0 = snap()
        fun sug(id: String, payload: String, status: String = "pending") = Dela.Suggestion(
            id = id, forUser = "sasha", kind = "create", payload = payload, status = status, createdAt = "2026-10-05T08:00:00+03:00",
        )
        val s = s0.copy(suggestions = mapOf(
            "q1" to sug("q1", "{\"title\": \"Звонок\", \"ask\": \"Это про Бету или про Альфу?\"}"),
            "q2" to sug("q2", "{\"title\": \"КП\", \"project_name\": \"Гамма\", \"person_name\": \"Пётр\"}"),
            "q3" to sug("q3", "{\"title\": \"КП\", \"project_name\": \"бета\", \"person_name\": \"Пётр\"}"),
            "q4" to sug("q4", "{\"title\": \"КП\", \"project_name\": \"Бета Групп\", \"person_name\": \"ваня\"}"),
            "a1" to sug("a1", "{\"title\": \"Само\", \"auto\": true}", status = "accepted"),
        ))
        assertEquals(4, DelaViews.newCount(s, "sasha", "all", now))
        assertEquals("Это про Бету или про Альфу?", DelaViews.askWhy(s.suggestions.getValue("q1"), s))
        assertEquals("Не знаю проект «Гамма» — куда?", DelaViews.askWhy(s.suggestions.getValue("q2"), s))
        // Проект узнан по другому имени без регистра — спрашивается человек.
        assertEquals("Кто это — «Пётр»?", DelaViews.askWhy(s.suggestions.getValue("q3"), s))
        assertNull(DelaViews.askWhy(s.suggestions.getValue("q4"), s))
        assertEquals("Поставить", DelaViews.askYes("create"))
        assertEquals("Закрыть", DelaViews.askYes("close"))
        // Новый человек (dela_0019): кто и откуда; «это он?» — «Да, это он» и с кем похож.
        val someone = s.people.values.first()
        val pNew = Dela.Suggestion(id = "p1", forUser = "sasha", kind = "person",
            payload = "{\"title\": \"Пётр Сидоров (Ромашка)\", \"where\": \"Telegram: 24 сообщения\", \"person\": {\"name\": \"Пётр Сидоров (Ромашка)\", \"role\": \"партнёр\"}}")
        assertEquals(DelaViews.SugText("Пётр Сидоров (Ромашка)", "Telegram: 24 сообщения · партнёр"), DelaViews.sugText(pNew, s, today))
        assertEquals("Завести", DelaViews.askYes(pNew))
        val pSame = pNew.copy(id = "p2", payload = "{\"person\": {\"name\": \"Petr S\"}, \"person_id\": \"${someone.id}\"}")
        assertEquals(DelaViews.SugText("Petr S", "похоже: ${someone.name}"), DelaViews.sugText(pSame, s, today))
        assertEquals("Да, это он", DelaViews.askYes(pSame))
        // Свёрнутое «Сделано само» одной строкой.
        val c = Dela.Suggestion(id = "c", kind = "close")
        val u = Dela.Suggestion(id = "u", kind = "update")
        assertEquals("закрыл 2, уточнил 1", DelaViews.autoSummary(listOf(c, u, c)))
        // «Похоже, это уже есть» — началами слов, хотя бы 60 % меньшего.
        val twin = sug("tw", "{\"title\": \"Ивану прислать модель бюджета\"}")
        assertEquals("Иван: прислать модель", DelaViews.twinOf(twin, s, "sasha")?.title)
    }

    @Test
    fun `новое одной пачкой — вопросы, поставленное и сделанное само этой сводки, как в вебе`() {
        val batch = "digest:2026-10-05T0800"
        val s0 = snap(
            task("from-batch") { it.copy(createdAt = "2026-09-20T10:00:00+03:00") },
            task("from-other") { it.copy(createdAt = "2026-10-05T07:00:00+03:00") },
            task("closed-batch") { it.copy(status = Dela.DONE) },
        )
        fun sug(id: String, b: String, status: String = "pending", kind: String = "create", task: String = "", result: String = "", reason: String = "") =
            Dela.Suggestion(
                id = id, forUser = "sasha", kind = kind, payload = "{\"title\": \"$id\"}", status = status, batchRef = b,
                batchTitle = if (b == batch) "Дайджест 08:00" else "", createdAt = "2026-10-05T08:00:00+03:00", taskId = task,
                resultTaskId = result, reason = reason, decidedAt = if (status == "pending") "" else "2026-10-05T08:10:00+03:00",
            )
        val s = s0.copy(suggestions = listOf(
            sug("ask-1", batch),
            sug("ask-other", "meeting:42"),
            sug("made", batch, status = "accepted", result = "from-batch"),
            sug("made-other", "meeting:42", status = "accepted", result = "from-other"),
            sug("made-closed", batch, status = "accepted", result = "closed-batch"),
            sug("auto-close", batch, status = "accepted", kind = "close", task = "from-other", reason = "закрыто само"),
            sug("auto-other", "", status = "accepted", kind = "close", task = "from-other", reason = "закрыто само"),
        ).associateBy { it.id })
        assertEquals(listOf("ask-1"), DelaViews.asksOfBatch(s, "sasha", batch).map { it.id })
        // Поставленное из пачки — без окна в три дня, только открытое.
        assertEquals(listOf("from-batch"), DelaViews.feedOfBatch(s, batch).map { it.id })
        assertEquals(listOf("auto-close"), DelaViews.autoOfBatch(s, "sasha", batch, now).map { it.id })
        assertEquals("Дайджест 08:00", DelaViews.batchTitle(s, batch))
        assertEquals("digest:нет-такой", DelaViews.batchTitle(s, "digest:нет-такой"))
        assertTrue(DelaViews.asksOfBatch(s, "sasha", "digest:нет-такой").isEmpty())
    }

    @Test
    fun `карточка — откуда дело — наговорка дословно или встреча с цитатой`() {
        assertNull(DelaViews.origin(null))
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val d = DelaViews.origin(JSONObject("{\"kind\": \"dictation\", \"at\": \"2026-10-05T12:52:00+03:00\", \"text\": \" Ивану позвонить \", \"source\": \"phone\"}"))!!
        assertEquals("Наговорка · сегодня 12:52", DelaViews.originTop(d, today, zone))
        assertEquals("Ивану позвонить", d.text)
        val m = DelaViews.origin(JSONObject("{\"kind\": \"suggestion\", \"source\": \"meeting\", \"batch_title\": \"Бета: тизер · 05.10\", " +
            "\"quote\": \"пришлю модель\", \"url\": \"http://evil\", \"auto\": true}"))!!
        assertEquals("Бета: тизер · 05.10 · поставлено само", DelaViews.originTop(m, today, zone))
        assertEquals("", m.url)  // только https://
    }

    @Test
    fun `разделы — старые ключи ведут туда, где их дела теперь`() {
        // 13.10.2026: первым — «Сейчас» (главная, как `#/now` веба), за ним «Сегодня», «Новое», «Все дела»;
        // «Круг жизни» и «Дерево жизни» — следом, видны, только когда сервер их знает.
        assertEquals(listOf("now", "today", "new", "all", "krug", "tree"), DelaViews.View.entries.map { it.key })
        // Как `route` веба: незнакомое и старые «Утро», «Предстоящее», «Жду» — на главную; «Неделя» — во «Все дела».
        for (old in listOf("now", "morning", "upcoming", "waiting", "непонятное")) assertEquals(DelaViews.View.NOW, DelaViews.View.of(old))
        assertEquals(DelaViews.View.ALL, DelaViews.View.of("week"))
        // Старый ключ «Сегодня» остаётся «Сегодня».
        assertEquals(DelaViews.View.TODAY, DelaViews.View.of("today"))
        assertEquals(DelaViews.View.NEW, DelaViews.View.of("inbox"))
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
    fun `все дела — просрочено сверху своей группой, в уровнях ниже его нет`() {
        // 13.10.2026 (`view_all` сервера, «Все дела» веба): просроченное само ни в «Сейчас», ни в «Сегодня» не
        // попадает — оно здесь, сверху; вчерашняя отметка «Сейчас» и «отбить» без срока — в обычных группах.
        val s = snap(
            task("late") { it.copy(dueDate = "2026-10-01") },
            task("today") { it.copy(dueDate = today) },
            task("yNow") { it.copy(focusOn = "2026-10-04") },
            task("owed") { it.copy(owed = true) },
        )
        val every = DelaViews.list(s, "sasha", "all", showDone = false)
        val late = every.filter { DelaViews.isLate(it, today) }
        assertEquals(listOf("late"), late.map { it.id })
        assertEquals(listOf("late"), DelaGroups.lateTasks(s, "sasha", today, "all").map { it.id })
        val rest = every.filter { !DelaViews.isLate(it, today) }
        val tree = DelaGroups.nested(rest, listOf(DelaGroups.Level.DATE), s, today)
        assertFalse(tree.any { it.late })
        assertTrue(rest.map { it.id }.containsAll(listOf("today", "yNow", "owed")))
        assertFalse(rest.any { it.id == "late" })
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
        val sec = DelaViews.personSections(s, p.id)
        assertEquals(listOf("Отбить: ждёт от меня", "Запустить: о нём и с ним", "Мониторить: жду от него"), sec.map { it.title })
        assertEquals(1, sec[2].items.size)
    }

    @Test
    fun `человек с полями веба — в кэш и обратно`() {
        val s = snap()
        val p = s.people.values.single().copy(emails = listOf("a@b.c"), telegram = "x", birthYear = 1990, seeks = "s", offers = "o", traits = "t", source = "src", note = "n")
        val back = Dela.person(JSONObject(Dela.json(p).toString()))
        assertEquals(p, back)
    }

    @Test
    fun `подпись шапки - число впереди слова, просрочка раньше закрытого`() {
        assertEquals("3 сегодня · 2 просрочено · 1 закрыто", DelaViews.headLine(null, today = 3, late = 2, done = 1))
        assertEquals("0 сегодня", DelaViews.headLine(null, today = 0, late = 0, done = 0))
        assertEquals("Работа · 4 сегодня · 5 закрыто", DelaViews.headLine("Работа", today = 4, late = 0, done = 5))
    }

    // ------------------------------------------------------------ задание 21 (аудит 15.10.2026): вопросы кнопками

    private val pA = Dela.Person("pa", "Иван Альфин", short = "Ваня")
    private val pB = Dela.Person("pb", "Иван Бетов", aliases = listOf("Ваня"))

    private fun people(vararg list: Dela.Person, features: Set<String> = emptySet()) =
        Dela.Snapshot(people = linkedMapOf(*list.map { it.id to it }.toTypedArray()), features = features)

    private fun ask(payload: JSONObject, kind: String = "create", source: String = "meeting", id: String = "q") = Dela.Suggestion(
        id = id, forUser = "sasha", kind = kind, payload = payload.toString(), source = source, createdAt = "2026-10-05T08:00:00+03:00",
    )

    @Test
    fun `кто это — ярусами, как сервер — своё имя сильнее чужого другого имени, двое — вопрос`() {
        val q = ask(JSONObject().put("title", "Позвонить").put("person_name", "Ваня"))
        // Короткое имя у А, другое имя у Б — узнан А, вопроса нет (раньше телефон не узнавал никого).
        assertNull(DelaViews.askWhy(q, people(pA, pB)))
        assertEquals("pa", DelaViews.personMatch(people(pA, pB), "ваня").hit?.id)
        assertEquals("pa", Dela.findPerson(people(pA, pB), "Ваня")?.id)
        // Короткое имя у обоих — «Кто это — «Ваня»: А или Б?», к другим именам не идём.
        val b2 = pB.copy(short = "Ваня", aliases = emptyList())
        assertEquals("Кто это — «Ваня»: Иван Альфин или Иван Бетов?", DelaViews.askWhy(q, people(pA, b2)))
        assertNull(Dela.findPerson(people(pA, b2), "Ваня"))
        // Архивная и слитая карточки не в счёт.
        val gone = b2.copy(id = "pc", archivedAt = "2026-10-01T00:00:00+03:00")
        val merged = b2.copy(id = "pd", mergedInto = "pa")
        assertNull(DelaViews.askWhy(q, people(pA, gone, merged)))
        // Никого — «Кто это — «Петя»?»; человек уже стоит id — имя не спрашиваем.
        assertEquals("Кто это — «Петя»?", DelaViews.askWhy(ask(JSONObject().put("person_name", "Петя")), people(pA)))
        assertNull(DelaViews.askWhy(ask(JSONObject().put("person_name", "Петя").put("person_id", "pa")), people(pA)))
        // Сервер уже посмотрел имена (held есть, хоть пустой) — его вердикт сильнее своего точного сравнения.
        assertNull(DelaViews.askWhy(ask(JSONObject().put("person_name", "Женя Соколов").put("held", "")), people(pA)))
        assertEquals("Кто это — «Петя»?", DelaViews.askWhy(ask(JSONObject().put("person_name", "Петя").put("held", "не знаю человека «Петя»")), people(pA)))
        // Сервер записал «не знаю человека» раньше, а с тех пор карточку завели — вопрос снят (как heldResolved веба).
        val later = Dela.Person("pp", "Пётр Тестов", short = "Петя")
        assertNull(DelaViews.askWhy(ask(JSONObject().put("person_name", "Петя").put("held", "не знаю человека «Петя»")), people(pA, later)))
        assertEquals("Какой проект — «Альфа»?",
            DelaViews.askWhy(ask(JSONObject().put("project_name", "Альфа").put("held", "не один проект с именем «Альфа»: Альфа Банк, Альфа Групп")), people(pA)))
        // Проекты — имя и другие имена одним ярусом: двое — «Какой проект — …: A или B?».
        val s = Dela.Snapshot(projects = linkedMapOf(
            "x" to Dela.Project("x", "Альфа Банк", aliases = listOf("Альфа")), "y" to Dela.Project("y", "Альфа Групп", aliases = listOf("Альфа")),
        ))
        assertEquals("Какой проект — «Альфа»: Альфа Банк или Альфа Групп?", DelaViews.askWhy(ask(JSONObject().put("project_name", "Альфа")), s))
    }

    @Test
    fun `ждёт — строка held без того, что уже сказал вопрос`() {
        val held = "двое с именем «Ваня»: Иван Альфин, Иван Бетов; встреча старше 3 дней"
        val p = JSONObject().put("title", "Позвонить").put("person_name", "Ваня").put("held", held).put("pick", true)
            .put("ask", "Кто это — «Ваня»: Иван Альфин или Иван Бетов?")
        val sg = ask(p)
        assertEquals("Ждёт: встреча старше 3 дней", DelaViews.heldLine(sg, DelaViews.askWhy(sg, people(pA))))
        // Только про имя при pick — строки нет.
        assertNull(DelaViews.heldLine(ask(JSONObject(p.toString()).put("held", "двое с именем «Ваня»: Иван Альфин, Иван Бетов")), p.getString("ask")))
        // Без held (старый сервер) — строки нет; «не знаю человека «Петя»» при вопросе «Кто это — «Петя»?» — повтор.
        assertNull(DelaViews.heldLine(ask(JSONObject().put("title", "x")), null))
        assertNull(DelaViews.heldLine(ask(JSONObject().put("person_name", "Петя").put("held", "не знаю человека «Петя»")), "Кто это — «Петя»?"))
        assertEquals("Ждёт: само не встало: в «Сейчас» уже пять дел",
            DelaViews.heldLine(ask(JSONObject().put("held", "само не встало: в «Сейчас» уже пять дел")), null))
    }

    @Test
    fun `вопрос с вариантами и pick — ответ кнопкой, Да и Поставить все не показываются`() {
        val opts = JSONArray()
            .put(JSONObject().put("label", "Иван Альфин (Альфа)").put("person_id", "pa"))
            .put(JSONObject().put("label", "Иван Бетов (Бета)").put("person_id", "pb"))
            .put(JSONObject().put("label", "Без человека").put("person_id", JSONObject.NULL).put("person_name", JSONObject.NULL))
        val sg = ask(JSONObject().put("title", "Позвонить").put("person_name", "Ваня").put("options", opts).put("pick", true)
            .put("held", "двое с именем «Ваня»: Иван Альфин (Альфа), Иван Бетов (Бета)"))
        val on = people(pA, pB, features = setOf(Dela.FEATURE_OPTIONS))
        val o = DelaViews.options(sg, on)!!
        assertEquals(listOf("Иван Альфин (Альфа)", "Иван Бетов (Бета)", "Без человека"), o.map { it.label })
        assertTrue(DelaViews.pick(sg))
        assertTrue(DelaViews.choiceOnly(sg))
        assertFalse(DelaViews.plainYes(sg, on))
        val plain = ask(JSONObject().put("title", "Купить бумагу"), id = "q2")
        assertTrue(DelaViews.plainYes(plain, on))
        assertFalse("при вариантах «все» нет", DelaViews.bulkOk(listOf(sg, plain), on))
        assertTrue(DelaViews.bulkOk(listOf(plain, plain.copy(id = "q3")), on))
        // Что уходит: номер варианта, без decision — решает сам вариант.
        val op = Dela.answerOp(sg.id, o[1].index)
        assertEquals("suggestion.decide", op.getString("op"))
        assertEquals(1, op.getInt("option"))
        assertFalse(op.has("decision"))
        assertEquals("Ответ: Иван Бетов (Бета)", DelaViews.answerSaid(o[1]))
        // Поверх копии — сразу решено, как решит сервер.
        val s = on.copy(suggestions = mapOf(sg.id to sg))
        val after = Dela.overlay(s, listOf(op), "sasha", today, nowIso).suggestions.getValue(sg.id)
        assertEquals("accepted", after.status)
        assertEquals("ответ: Иван Бетов (Бета)", after.reason)
        // Без фичи options — кнопок нет, но и «Да» нет: pick всё равно ждёт выбора.
        assertNull(DelaViews.options(sg, people(pA, pB)))
        assertTrue(DelaViews.choiceOnly(sg))
        // Двусмысленное имя без pick (модель спросила своё, вариантов кнопками нет) — «Да» сервер не примет.
        val many = ask(JSONObject().put("person_name", "Маша").put("held", "четверо с именем «Маша»: А, Б, В и ещё 1"))
        assertTrue(DelaViews.choiceOnly(many))
        assertTrue(DelaViews.choiceOnly(ask(JSONObject().put("person_name", "Маша").put("held", "на «Маша» похожи: А, Б"))))
        assertFalse(DelaViews.choiceOnly(ask(JSONObject().put("person_name", "Петя").put("held", "не знаю человека «Петя»"))))
        assertFalse("старый сервер — как раньше", DelaViews.choiceOnly(ask(JSONObject().put("person_name", "Ваня"))))
    }

    @Test
    fun `вопросы о людях со встречи и «это он в Telegram» — кнопки, Не для CRM, ссылка на встречу`() {
        val on = people(pA, pB, features = setOf(Dela.FEATURE_OPTIONS))
        // «Мария» — имя неизвестно: «Завести «Мария»» и «Не для CRM», почему не завёл сам — строкой.
        val maria = ask(JSONObject().put("title", "Мария").put("where", "встреча «Альфа: фонды» 14.10 · 7 мин речи · по голосу")
            .put("ask", "Кто это на встрече «Альфа: фонды» 14.10 — «Мария»?").put("held", "имя из одного слова")
            .put("person", JSONObject().put("name", "Мария"))
            .put("speaker", JSONObject().put("url", "https://meetings.example/m/101").put("label", "SPEAKER_02"))
            .put("options", JSONArray().put(JSONObject().put("label", "Завести «Мария»").put("new", true))
                .put(JSONObject().put("label", "Не для CRM").put("reject", true).put("reason", "не для CRM"))), kind = "person")
        val mo = DelaViews.options(maria, on)!!
        assertEquals(listOf("Завести «Мария»", "Не для CRM"), mo.map { it.label })
        assertEquals("Не для CRM", DelaViews.rejectOption(maria, on)?.label)
        assertEquals("Ждёт: имя из одного слова", DelaViews.heldLine(maria, DelaViews.askWhy(maria, on)))
        assertEquals("https://meetings.example/m/101", DelaViews.sugLink(maria))
        // «▶ послушать» (добавка 15.10): ссылки на голос нет — кнопки нет; есть — только https без пробелов.
        assertNull(DelaViews.clipLink(maria))
        val heard = ask(JSONObject().put("title", "Мария").put("speaker", JSONObject().put("url", "https://meetings.example/m/101")
            .put("clip_url", "https://meetings.example/m/101/clip/SPEAKER_02")), kind = "person")
        assertEquals("https://meetings.example/m/101/clip/SPEAKER_02", DelaViews.clipLink(heard))
        for (bad in listOf("javascript:alert(1)", "http://meetings.example/m/101/clip/S", "https://x.example/a b", "https://"))
            assertNull(bad, DelaViews.clipLink(ask(JSONObject().put("speaker", JSONObject().put("clip_url", bad)), kind = "person")))
        assertEquals(DelaViews.SugText("Мария", "встреча «Альфа: фонды» 14.10 · 7 мин речи · по голосу"), DelaViews.sugText(maria, on, today))
        assertFalse(DelaViews.choiceOnly(maria))
        val rej = Dela.overlay(on.copy(suggestions = mapOf(maria.id to maria)), listOf(Dela.answerOp(maria.id, 1)), "sasha", today, nowIso)
        assertEquals("rejected", rej.suggestions.getValue(maria.id).status)
        assertEquals("не для CRM", rej.suggestions.getValue(maria.id).reason)
        // «Иван» — похожие есть: четыре кнопки, «Завести» среди них нет, pick, строка «похожие уже есть» не повторяет вопрос.
        val ivan = ask(JSONObject().put("title", "Иван").put("pick", true).put("held", "похожие уже есть: Иван Альфин, Иван Бетов")
            .put("ask", "«Иван» на встрече «Альфа: фонды» 14.10 — это Иван Альфин или Иван Бетов?")
            .put("options", JSONArray().put(JSONObject().put("label", "Это Иван Альфин").put("person_id", "pa"))
                .put(JSONObject().put("label", "Это Иван Бетов").put("person_id", "pb"))
                .put(JSONObject().put("label", "Новый человек").put("new", true))
                .put(JSONObject().put("label", "Не для CRM").put("reject", true).put("reason", "не для CRM"))), kind = "person", id = "q-ivan")
        val io = DelaViews.options(ivan, on)!!
        assertEquals(4, io.size)
        assertTrue(io.none { it.label.startsWith("Завести") })
        assertTrue(DelaViews.choiceOnly(ivan))
        assertNull(DelaViews.heldLine(ivan, DelaViews.askWhy(ivan, on)))
        // «Это он в Telegram?» с двумя контактами — pick, «Никто» — reject.
        val tg = ask(JSONObject().put("title", "Мария Котова").put("person_id", "pa").put("pick", true)
            .put("ask", "Мария Котова в Telegram — это «Мария Котова» (@mkot) или «Мария Белова»?")
            .put("options", JSONArray().put(JSONObject().put("label", "Это «Мария Котова» (@mkot)").put("person_id", "pa")
                .put("person", JSONObject().put("telegram_id", 900102)))
                .put(JSONObject().put("label", "Это «Мария Белова»").put("person_id", "pa"))
                .put(JSONObject().put("label", "Никто").put("reject", true).put("reason", "не он в Telegram"))), kind = "person", source = "telegram", id = "tgfind")
        assertEquals("Никто", DelaViews.rejectOption(tg, on)?.label)
        assertTrue(DelaViews.choiceOnly(tg))
        // Лист «Отклонить» у вопроса о человеке — причина его варианта «нет», без вариантов — «не для CRM».
        assertEquals("не он в Telegram", DelaViews.personNoReason(tg))
        assertEquals("не для CRM", DelaViews.personNoReason(ask(JSONObject().put("title", "+7 900"), kind = "person")))
        // Человек без вариантов («Новый номер — завести?») — «Не для CRM» причиной, без вопроса.
        assertEquals("не для CRM", DelaViews.NOT_CRM)
        assertTrue(DelaViews.plainYes(ask(JSONObject().put("title", "+7 900 000-00-00"), kind = "person", source = "contacts"), on))
        assertEquals(DelaViews.Mark("phone", true, "телефонная книжка"), DelaViews.mark("contacts"))
    }

    @Test
    fun `поправить — человек не выбран — без человека явно, выбран — он`() {
        val s = people(pA, pB.copy(short = "Ваня", aliases = emptyList()))
        val sg = ask(JSONObject().put("title", "Позвонить").put("person_name", "Ваня"))
        val proposed = Dela.draftOf(sg, s, "sasha")
        assertEquals("", proposed.personId)
        val none = Dela.acceptEditSet(sg, proposed, proposed.copy(title = "Позвонить завтра"))
        assertTrue(none.has("person_id") && none.isNull("person_id"))
        val picked = Dela.acceptEditSet(sg, proposed, proposed.copy(personId = "pa"))
        assertEquals("pa", picked.getString("person_id"))
        // Человека не называли — и ключа нет.
        val plain = ask(JSONObject().put("title", "Купить бумагу"))
        val p2 = Dela.draftOf(plain, s, "sasha")
        assertFalse(Dela.acceptEditSet(plain, p2, p2.copy(title = "Бумага")).has("person_id"))
    }

    @Test
    fun `поправить — человек из листа уходит всегда, даже нетронутый`() {
        // «Уточнить» дело с человеком X, предложение называет двусмысленное «Ваня»: владелец меняет только срок — лист
        // показывал X, его и ставим (без этого сервер отказал бы «Кто это — «Ваня»…», и правка срока потерялась бы).
        val px = Dela.Person("px", "Пётр Иксов")
        val base = task("t1") { it.copy(personId = "px") }
        val s = people(pA, pB, px).copy(tasks = mapOf("t1" to base))
        val upd = ask(JSONObject().put("title", "t1").put("person_name", "Ваня"), kind = "update").copy(taskId = "t1")
        val before = Dela.draftOf(upd, s, "sasha")
        assertEquals("px", before.personId)
        val set = Dela.acceptEditSet(upd, before, before.copy(dueDate = "2026-10-09"))
        assertEquals("2026-10-09", set.getString("due_date"))
        assertEquals("px", set.getString("person_id"))
        // «Завести»: лист подставил догадку телефона по началу имени («Пётр» → «Пётр Иксов») — она и уходит, а не
        // решение сервера по имени (старый match_name такого имени не знает — дело встало бы без человека).
        val cr = ask(JSONObject().put("title", "Позвонить").put("person_name", "Пётр"))
        val draft = Dela.draftOf(cr, s, "sasha")
        assertEquals("px", draft.personId)
        val set2 = Dela.acceptEditSet(cr, draft, draft.copy(title = "Позвонить завтра"))
        assertEquals("px", set2.getString("person_id"))
        assertEquals("Позвонить завтра", set2.getString("title"))
    }

    @Test
    fun `сделано само — человек заведён сам — строка, Вернуть в архив с компанией`() {
        val nowMs = now
        val decided = "2026-10-05T08:30:00+03:00"
        fun auto(id: String, source: String, result: JSONObject) = Dela.Suggestion(
            id = id, forUser = "sasha", kind = "person", source = source, status = "accepted", reason = Dela.AUTO_PERSON_REASON,
            decidedAt = decided, createdAt = decided, payload = JSONObject().put("title", "Пётр Новиков").put("where", "встреча «Альфа: фонды» 04.10 · 5 мин речи · имя дал ты").toString(),
            result = result.toString(),
        )
        val p = Dela.Person("pn", "Пётр Новиков", orgId = "org1")
        val sg = auto("s1", "meeting", JSONObject().put("person_id", "pn").put("created", true).put("org_id", "org1").put("org_created", true))
        val contacts = auto("s2", "contacts", JSONObject().put("person_id", "pz").put("created", true))
        val s = Dela.Snapshot(people = mapOf("pn" to p), suggestions = mapOf("s1" to sg, "s2" to contacts))
        val list = Dela.autoDone(s, "sasha", nowMs)
        assertEquals(setOf("s1", "s2"), list.map { it.id }.toSet())
        assertEquals("завёл людей: 2", DelaViews.autoSummary(list))
        assertEquals("по встречам и телефонной книжке", DelaViews.autoFrom(list))
        assertEquals("закрыл 1, уточнил 1, завёл людей: 1",
            DelaViews.autoSummary(listOf(Dela.Suggestion("c", kind = "close"), Dela.Suggestion("u", kind = "update"), sg)))
        val row = DelaViews.autoPerson(sg, s)
        assertEquals("Пётр Новиков", row.name)
        assertEquals("pn", row.personId)
        assertTrue(row.live)
        // «Вернуть»: карточку и компанию, заведённую ради него, — в архив, потом «понятно».
        val ops = Dela.autoPersonUndoOps(sg, s, seen = true, nowIso = nowIso)!!
        assertEquals(listOf("person.set", "org.set", "suggestion.seen"), ops.map { it.getString("op") })
        assertEquals(nowIso, ops[0].getJSONObject("set").getString("archived_at"))
        assertEquals("org1", ops[1].getString("id"))
        // В компании есть ещё живой — её не трогаем.
        val busy = s.copy(people = s.people + ("px" to Dela.Person("px", "Ольга Тестова", orgId = "org1")))
        assertEquals(listOf("person.set"), Dela.autoPersonUndoOps(sg, busy, seen = false, nowIso = nowIso)!!.map { it.getString("op") })
        // Уже в архиве или не видна — вернуть нечего.
        assertNull(Dela.autoPersonUndoOps(sg, s.copy(people = mapOf("pn" to p.copy(archivedAt = nowIso))), seen = true, nowIso = nowIso))
        assertNull(Dela.autoPersonUndoOps(contacts, s, seen = true, nowIso = nowIso))
        assertEquals("Пётр Новиков", DelaViews.autoPerson(contacts, s).name)
        assertFalse("карточки в копии нет — «Вернуть» не показываем", DelaViews.autoPerson(contacts, s).live)
        // Понятно — уходит.
        assertTrue(Dela.autoDone(s.copy(suggestions = mapOf("s1" to sg.copy(seenAt = nowIso))), "sasha", nowMs).isEmpty())
        // created: false — сервер не заводил карточку, а дописал номер в живую («человек заведён сам» с ключом, который
        // уже был у Ольги): строка «Дописано в карточку», «Вернуть» нет — архив убрал бы настоящую карточку.
        val olga = Dela.Person("po", "Ольга Тестова")
        val added = auto("s3", "contacts", JSONObject().put("person_id", "po").put("created", false).put("changed", true))
        val s2 = s.copy(people = s.people + ("po" to olga))
        val ar = DelaViews.autoPerson(added, s2)
        assertEquals("Ольга Тестова", ar.name)
        assertFalse(ar.made)
        assertFalse("дописано в живую — не «Вернуть»", ar.live)
        assertNull(Dela.autoPersonUndoOps(added, s2, seen = true, nowIso = nowIso))
        assertEquals("завёл людей: 1, дописал в карточки: 1", DelaViews.autoSummary(listOf(sg, added)))
    }

    @Test
    fun `шапка — ждут от тебя без срока и жду больше недели — правило сервера`() {
        fun t(id: String, f: (Dela.Task) -> Dela.Task) = task(id, f)
        val s = snap(
            t("owed") { it.copy(owed = true) },
            t("owed-due") { it.copy(owed = true, dueDate = "2026-10-09") },
            t("owed-goal") { it.copy(owed = true, goal = true) },
            t("w10") { it.copy(ball = Dela.WAITING, waitingSince = "2026-09-25") },
            t("w10-past") { it.copy(ball = Dela.WAITING, waitingSince = "2026-09-25", nudgeOn = "2026-10-01") },
            t("w10-future") { it.copy(ball = Dela.WAITING, waitingSince = "2026-09-25", nudgeOn = "2026-10-07") },
            t("w3") { it.copy(ball = Dela.WAITING, waitingSince = "2026-10-02") },
            t("w7") { it.copy(ball = Dela.WAITING, waitingSince = "2026-09-28") },
        )
        val w = DelaViews.waits(s, "sasha", today, "all", now)
        assertEquals(listOf("owed"), DelaViews.owedNoDue(s, "sasha", "all").map { it.id }.filter { it.startsWith("owed") })
        // Какие именно: напоминание прошло (w10-past) — ждёт; назначено вперёд (w10-future) и ждёт меньше недели — нет.
        val stale = Dela.week(s, "sasha", today, "all", now).waitingStale.map { it.id }.filter { it.startsWith("w") }.toSet()
        assertEquals(setOf("w10", "w10-past"), stale)
        // Шапка считает тем же правилом и по тем же делам (контракт синка несёт свои — их добавка одна и та же).
        val extraOwed = DelaViews.owedNoDue(s, "sasha", "all").count { !it.id.startsWith("owed") }
        val extraWait = Dela.week(s, "sasha", today, "all", now).waitingStale.count { !it.id.startsWith("w") }
        assertEquals(1 + extraOwed, w.owedNoDue)
        assertEquals(2 + extraWait, w.waitingStale)
        val words = DelaViews.waitWords(DelaViews.Waits(3, 2))
        assertEquals(listOf("ждут от тебя без срока: 3", "жду больше недели: 2"), words)
        assertEquals(listOf("жду больше недели: 1"), DelaViews.waitWords(DelaViews.Waits(0, 1)))
        assertFalse(DelaViews.Waits(0, 0).any)
        // Куда ведёт тап: заводской фильтр по людям, нет его — свой с той же группой.
        assertEquals("f-owed-people", DelaGroups.scopeFilter(DelaGroups.FILTERS_DEF, "f-owed-people", DelaGroups.Scope.OWED)?.id)
        val mine = listOf(DelaGroups.Filter("my", "Мои отбить", DelaGroups.Scope.OWED, listOf(DelaGroups.Level.PROJECT)))
        assertEquals("my", DelaGroups.scopeFilter(mine, "f-owed-people", DelaGroups.Scope.OWED)?.id)
        assertNull(DelaGroups.scopeFilter(mine, "f-check-people", DelaGroups.Scope.CHECK))
    }

    @Test
    fun `счётчик автоматики за неделю — строка как в вебе, нули пропускаются`() {
        val all = JSONObject().put("created", 23).put("created_undone", 1).put("closed", 9).put("closed_undone", 0)
            .put("updated", 5).put("updated_undone", 0).put("people", 2).put("people_undone", 0)
            .put("asked", 12).put("yes", 9).put("edited", 0).put("no", 2).put("expired", 1)
            .put("no_reasons", JSONArray().put(JSONObject().put("reason", "не дело").put("n", 2)))
        val week = JSONObject().put("automation", JSONObject().put("days", 7).put("all", all))
        assertEquals(
            "за неделю: поставлено само 23 (отменил 1) · закрыто 9 (вернул 0) · уточнено 5 (0) · людей 2 (0) · вопросов 12: да 9, нет 2, погасло 1",
            DelaViews.automationLine(week),
        )
        assertEquals("«не дело» — 2", DelaViews.automationNo(week))
        all.put("edited", 1)
        assertTrue(DelaViews.automationLine(week)!!.contains("да 9, с правкой 1, нет 2"))
        assertNull("старый сервер — строки нет", DelaViews.automationLine(JSONObject().put("stale", JSONArray())))
        assertNull(DelaViews.automationLine(null))
        assertNull("всё по нулям — строки нет", DelaViews.automationLine(JSONObject().put("automation", JSONObject().put("all", JSONObject()))))
    }

    // ------------------------------------------------------------ задание 22 (шаг 1 петли): «Сейчас на завтра»

    private val tomorrow = "2026-10-06"
    private val planOn = setOf(Dela.FEATURE_OPTIONS, Dela.FEATURE_PLAN)

    /** Кандидат плана — как его кладёт сервер (`plan.py`, пример — `server/contract/dela-plan.json`). */
    private fun cand(
        id: String,
        order: Int,
        task: String = "",
        day: String = tomorrow,
        basket: String = "owed",
        status: String = "pending",
        forUser: String = "sasha",
        expires: String = day + "T12:00:00+03:00",
        title: String = "",
    ): Dela.Suggestion {
        val opts = JSONArray().put(JSONObject().put("label", "☆ На завтра").put("focus_on", day))
        if (basket == "owed" || basket == "carry") opts.put(JSONObject().put("label", "✓ Уже сделал").put("done", true))
        opts.put(JSONObject().put("label", "Не надо").put("reject", true).put("reason", "план: не надо"))
        val p = JSONObject().put("basket", basket).put("for_date", day).put("focus_on", day).put("order", order)
            .put("why", "Иван ждёт 4 дня · срок завтра").put("from", "встреча «ООО Ромашка: модель» 01.10")
            .put("ask", "В «Сейчас» на вт 06.10? Иван ждёт 4 дня").put("options", opts).put("held", "")
        if (title.isNotBlank()) p.put("title", title).put("ball", "mine")
        return Dela.Suggestion(
            id = id, forUser = forUser, kind = if (task.isBlank()) "create" else "update", taskId = task, payload = p.toString(),
            source = "plan", sourceRef = "plan:$day:$basket:$id", batchRef = "plan:$day", batchTitle = "Сейчас на вт 06.10 · встреч ~3 — возьми 3",
            status = status, expiresAt = expires, createdAt = "2026-10-04T18:00:04+03:00",
        )
    }

    private fun planSnap(features: Set<String>, tasks: List<Dela.Task>, sugs: List<Dela.Suggestion>) =
        Dela.Snapshot(tasks = tasks.associateBy { it.id }, suggestions = sugs.associateBy { it.id }, features = features)

    @Test
    fun `сейчас на завтра — кандидаты по order, отмеченное по дню, не в Новом и не в числе`() {
        val tasks = listOf(
            task("a") { it.copy(title = "Иван Тестов: прислать договор") },
            task("b") { it.copy(title = "Собрать отчёт", focusOn = today) },
            task("m") { it.copy(title = "Отмеченное заранее", focusOn = tomorrow) },
            task("m-foreign") { it.copy(focusOn = tomorrow, ownerId = "natasha") },
            task("m-done") { it.copy(focusOn = tomorrow, status = Dela.DONE) },
            task("m-goal") { it.copy(focusOn = tomorrow, goal = true) },
        )
        val q = ask(JSONObject().put("title", "Купить бумагу"), id = "q1")
        val sugs = listOf(
            cand("c1", 3, task = "a"),
            cand("c2", 1, task = "b", basket = "carry"),
            cand("c3", 2, basket = "deal", title = "Решить следующий шаг: ООО Ромашка — продажа доли"),
            cand("c4", 4, task = "a", status = "rejected"),
            cand("c5", 5, task = "a", forUser = "natasha"),
            cand("old", 1, task = "a", day = "2026-10-04"),
            q,
        )
        val s = planSnap(planOn, tasks, sugs)
        val p = DelaViews.plan(s, "sasha", today, now)!!
        assertEquals(tomorrow, p.forDate)
        assertEquals("plan:$tomorrow", p.batch)
        assertEquals("на завтра", p.label)
        assertEquals("Сейчас на вт 06.10 · встреч ~3 — возьми 3", p.title)
        // Порядок — payload.order сервера; решённые, чужие и вчерашние не ждут ответа.
        assertEquals(listOf("c2", "c3", "c1"), p.items.map { it.id })
        // Предложено — вся пачка дня, с решёнными (как строка дайджеста 20:03); чужого и вчерашнего нет.
        assertEquals(4, p.offered)
        // Отмечено — только своё открытое не-цель с focus_on этого дня.
        assertEquals(listOf("m"), p.marked.map { it.id })
        assertTrue(p.visible)
        assertTrue(p.ahead)
        assertTrue(p.room)
        assertEquals("Сейчас на завтра: предложено 4 · отмечено 1 из 5", DelaViews.planLine(p))
        // Не вопросы автоматики: в «Подскажи», числе «Нового» и «Поставить все» их нет (`store.NEW_COUNT_IF`).
        assertEquals(listOf("q1"), DelaViews.asks(s, "sasha").map { it.id })
        assertEquals(1, DelaViews.newCount(s, "sasha", "all", now))
        assertEquals(1, Dela.newOnes(s, "sasha").size)
        // Кандидат словами: само дело (у create — название из payload) и «почему · откуда».
        assertEquals(
            DelaViews.SugText("Иван Тестов: прислать договор", "Иван ждёт 4 дня · срок завтра · встреча «ООО Ромашка: модель» 01.10"),
            DelaViews.sugText(s.suggestions.getValue("c1"), s, today),
        )
        assertEquals("Решить следующий шаг: ООО Ромашка — продажа доли", DelaViews.sugText(s.suggestions.getValue("c3"), s, today).title)
        assertEquals("В «Сейчас»", DelaViews.askYes(s.suggestions.getValue("c1")))
        // Кнопки сервера: «☆ На завтра» несёт день, «✓ Уже сделал» — только у обещаний и несделанного.
        val o = DelaViews.options(s.suggestions.getValue("c1"), s)!!
        assertEquals(listOf("☆ На завтра", "✓ Уже сделал", "Не надо"), o.map { it.label })
        assertEquals(tomorrow, o[0].focusOn)
        assertTrue(o[1].done)
        assertTrue(o[2].reject)
        assertEquals("Не надо", DelaViews.answerSaid(o[2]))
        assertEquals("Не надо: Не для CRM", DelaViews.answerSaid(DelaViews.Option(0, "Не для CRM", reject = true)))
        // Вечером день плана — завтра: подпись сервера как есть. После полуночи он уже сегодня — «☆ На сегодня» (кнопка и
        // тост), иначе в 8 утра «на завтра» прочтётся следующим днём; «✓ Уже сделал» и «Не надо» не меняются.
        assertEquals(o, DelaViews.planOptions(o, today))
        val morning = DelaViews.planOptions(o, tomorrow)
        assertEquals(listOf("☆ На сегодня", "✓ Уже сделал", "Не надо"), morning.map { it.label })
        assertEquals(tomorrow, morning[0].focusOn)
        assertEquals("Ответ: ☆ На сегодня", DelaViews.answerSaid(morning[0]))
        assertEquals(listOf("☆ На завтра", "Не надо"), DelaViews.options(s.suggestions.getValue("c3"), s)!!.map { it.label })
        assertEquals(DelaViews.Mark("bolt", true, "план на завтра"), DelaViews.mark("plan"))
        // Пачка `plan:<день>` — та же: «Сегодня» и дайджест открывают её по batch_ref.
        assertEquals(p, DelaViews.plan(s, "sasha", today, now, day = tomorrow))
        // Старый сервер (флага plan нет) — по-старому: блока нет, ждущее (если вдруг есть) — в «Новом» вопросами.
        val old = s.copy(features = setOf(Dela.FEATURE_OPTIONS))
        assertNull(DelaViews.plan(old, "sasha", today, now))
        assertEquals(setOf("q1", "c1", "c2", "c3", "old"), Dela.newOnes(old, "sasha").map { it.id }.toSet())
    }

    @Test
    fun `сейчас на завтра — пятница на понедельник, после полуночи до 12, погасшее по сроку`() {
        assertEquals("на завтра", DelaViews.planWhen("2026-10-06", today))
        assertEquals("на понедельник", DelaViews.planWhen("2026-10-12", "2026-10-09"))
        assertEquals("на среду", DelaViews.planWhen("2026-10-07", today))
        assertEquals("на сегодня, до 12:00", DelaViews.planWhen(today, today))
        val a = task("a")
        // После полуночи вчерашние кандидаты — «на сегодня, до 12:00»; погасшее по expires_at (синк ещё не привёз) не ждёт.
        val s = planSnap(planOn, listOf(a), listOf(
            cand("live", 1, task = "a", day = today),
            cand("gone", 2, task = "a", day = today, expires = "2026-10-05T08:00:00+03:00"),
        ))
        val p = DelaViews.plan(s, "sasha", today, now)!!
        assertEquals(listOf("live"), p.items.map { it.id })
        assertEquals("на сегодня, до 12:00", p.label)
        assertFalse(p.ahead)
        // В 12:00 погасли все — блока нет: на прошедший час нечего отмечать, «Сейчас» сегодня — у молнии.
        val noon = java.time.OffsetDateTime.parse("2026-10-05T12:00:01+03:00").toInstant().toEpochMilli()
        assertNull(DelaViews.plan(s, "sasha", today, noon))
        // Ответил на всех, отмеченное заранее стоит — блок виден до своего дня: «Убрать» есть только здесь.
        val marked = task("m") { it.copy(focusOn = tomorrow) }
        val answered = planSnap(planOn, listOf(a, marked), listOf(cand("c1", 1, task = "a", status = "rejected")))
        val q = DelaViews.plan(answered, "sasha", today, now)!!
        assertTrue(q.items.isEmpty())
        assertTrue(q.visible)
        assertEquals("Сейчас на завтра: отмечено 1 из 5", DelaViews.planLine(q))
        // Ничего не отмечено и ждать нечего — не виден.
        assertFalse(DelaViews.plan(planSnap(planOn, listOf(a), listOf(cand("c1", 1, task = "a", status = "rejected"))), "sasha", today, now)!!.visible)
        assertNull(DelaViews.plan(planSnap(planOn, listOf(a), emptyList()), "sasha", today, now))
    }

    @Test
    fun `сейчас на завтра — ответ поверх копии, уже сделал закрывает только у плана, шестое — до запроса`() {
        val five = (1..5).map { i -> task("t$i") { it.copy(focusOn = tomorrow) } }
        val a = task("a")
        val s = planSnap(planOn, listOf(a), listOf(cand("c1", 1, task = "a"), cand("c2", 2, task = "a")))
        // «☆ На завтра» — дело сразу на свой день: «отмечено k из 5» растёт до синка.
        val yes = Dela.overlay(s, listOf(Dela.answerOp("c1", 0)), "sasha", today, nowIso)
        assertEquals(tomorrow, yes.tasks.getValue("a").focusOn)
        assertEquals("accepted", yes.suggestions.getValue("c1").status)
        assertEquals("ответ: ☆ На завтра", yes.suggestions.getValue("c1").reason)
        assertEquals(listOf("a"), DelaViews.plan(yes, "sasha", today, now)!!.marked.map { it.id })
        // Сегодняшнее «Сейчас» это не трогает: дело встанет туда в свой день.
        assertTrue(DelaViews.nowTasks(yes, "sasha", today).isEmpty())
        // «✓ Уже сделал» — дело закрыто, «Сейчас» завтра не ставится.
        val done = Dela.overlay(s, listOf(Dela.answerOp("c1", 1)), "sasha", today, nowIso).tasks.getValue("a")
        assertEquals(Dela.DONE, done.status)
        assertEquals("", done.focusOn)
        // Тот же done у вопроса автоматики ничего не закрывает — его слушает сервер только у плана.
        val other = cand("m1", 1, task = "a").copy(source = "meeting")
        val notPlan = Dela.overlay(planSnap(planOn, listOf(a), listOf(other)), listOf(Dela.answerOp("m1", 1)), "sasha", today, nowIso)
        assertTrue(notPlan.tasks.getValue("a").open)
        // «Не надо» дело не трогает; старое «Да» без варианта — день из payload, как решит сервер.
        assertEquals("", Dela.overlay(s, listOf(Dela.answerOp("c1", 2)), "sasha", today, nowIso).tasks.getValue("a").focusOn)
        val oldYes = Dela.overlay(s, listOf(Dela.decideOp("c1", accept = true)), "sasha", today, nowIso)
        assertEquals(tomorrow, oldYes.tasks.getValue("a").focusOn)
        // Пять на завтра — шестое не встаёт, тост до запроса (сервер держит то же: store._now_guard).
        val full = DelaViews.plan(planSnap(planOn, five + a, listOf(cand("c1", 1, task = "a"))), "sasha", today, now)!!
        assertFalse(full.room)
        assertEquals("На завтра уже пять — сначала убери одно", DelaViews.planFull(full))
        val todayFull = DelaViews.plan(planSnap(planOn, listOf(a), listOf(cand("c1", 1, task = "a", day = today))), "sasha", today, now)!!
        assertEquals(DelaViews.NOW_FULL, DelaViews.planFull(todayFull))
    }

    @Test
    fun `сейчас на завтра — пример контракта и шапка из вида plan`() {
        val f = listOf(File("../server/contract/dela-plan.json"), File("server/contract/dela-plan.json")).first { it.isFile }
        val c = JSONObject(f.readText())
        assertEquals(Dela.FEATURE_PLAN, c.getJSONObject("features").getString("flag"))
        val sg = Dela.suggestion(c.getJSONObject("suggestion").getJSONObject("example_update"))!!
        assertTrue(DelaViews.isPlan(sg))
        assertEquals("2026-10-16", DelaViews.planDay(sg))
        val t = Dela.Task(id = sg.taskId, title = "Иван Тестов: прислать договор", ownerId = "sasha")
        val s = planSnap(planOn, listOf(t), listOf(sg))
        val day = "2026-10-15"
        val nowThu = java.time.OffsetDateTime.parse("2026-10-15T18:05:00+03:00").toInstant().toEpochMilli()
        val p = DelaViews.plan(s, "sasha", day, nowThu)!!
        assertEquals("plan:2026-10-16", p.batch)
        assertEquals(listOf(sg.id), p.items.map { it.id })
        assertEquals("Иван ждёт 4 дня · срок завтра · сделка живая · встреча «ООО Ромашка: модель» 13.10", DelaViews.sugText(sg, s, day).hint)
        val o = DelaViews.options(sg, s)!!
        assertEquals("2026-10-16", o.first().focusOn)
        assertTrue(o.any { it.done })
        // Шапка пачки — из вида plan того же дня: «сегодня сделано 2 из 4» и «третий день в «Сейчас»».
        val view = c.getJSONObject("view").getJSONObject("example")
        assertEquals("сегодня сделано 2 из 4", DelaViews.planToday(view, p))
        assertEquals("", DelaViews.planToday(JSONObject(view.toString()).put("for_date", "2026-10-19"), p))
        assertEquals("", DelaViews.planToday(null, p))
        val stuck = DelaViews.planStuck(view)
        assertEquals(listOf(DelaViews.Stuck("6e7f8a9b-0c1d-4e2f-8a3b-4c5d6e7f8a9b", "Собрать отчёт", 3)), stuck)
        assertEquals("3 дня в «Сейчас»: Собрать отчёт — разбить, отдать или убрать?", DelaViews.stuckLine(stuck.single()))
        assertTrue(DelaViews.planStuck(null).isEmpty())
    }
}
