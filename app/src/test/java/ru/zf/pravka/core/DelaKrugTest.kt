package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Круг жизни и дерево жизни на телефоне — как веб (`app.js`: `upOf`, `dirsOf`, `goalPath`, `mineTasks`,
 * `treeModel`, `treeList`, `placeBlock`, `principleToday`) и сервер (`tasks.krug_dirs`, `krug.tree`,
 * `krug.mine_from`, `krug.principle_of_day`). Данные выдуманные: настоящий справочник и цели — только в Своде и
 * базе владельца.
 */
class DelaKrugTest {

    private val today = "2026-10-12"

    @Before
    fun svod() {
        Svod.current = mapOf(
            DelaKrug.DIRECTIONS to Svod.Entry(
                DelaKrug.DIRECTIONS,
                value = """[{"key":"work","pos":3,"name":"Дело","short":"Дело","formula":"Дело растёт.","zone":"Мир"},
                    {"key":"health","pos":0,"name":"Здоровье","short":"Здоровье","zone":"Я"},
                    {"key":"home","pos":8,"name":"Комфорт в доме","short":"Дом","zone":"Семья"},
                    {"key":"education","pos":7,"name":"Образование","zone":"Семья"}]""",
            ),
            DelaKrug.PRINCIPLES to Svod.Entry(DelaKrug.PRINCIPLES, value = """["Страшно — значит, делаю.", "Решил — делаю сразу."]"""),
        )
    }

    @After
    fun clear() {
        Svod.current = emptyMap()
    }

    private fun task(id: String, f: (Dela.Task) -> Dela.Task = { it }) =
        f(Dela.Task(id = id, title = id, ownerId = "me", createdBy = "me", createdAt = "2026-10-01T10:00:00+03:00"))

    private fun snap(vararg t: Dela.Task, krug: Map<String, Dela.KrugProject> = emptyMap()) = Dela.Snapshot(
        tasks = t.associateBy { it.id },
        projects = listOf(Dela.Project(id = "p-a", name = "Альфа", ownerId = "me"), Dela.Project(id = "fam", name = "Семья", ownerId = "me"))
            .associateBy { it.id },
        features = setOf(Dela.FEATURE_KRUG, Dela.FEATURE_TREE),
        krug = krug,
    )

    private val dirs get() = DelaKrug.directions()

    @Test
    fun `справочник по кругу — сверху Здоровье, зоны подряд, принцип дня по-питоновски`() {
        val d = DelaKrug.directions()
        assertEquals(listOf("health", "work", "education", "home"), d.map { it.key })
        assertEquals("Дом", d.last().label)
        assertEquals(listOf("Я", "Мир", "Семья"), DelaKrug.zones(d).map { it.first })
        assertEquals(listOf("education", "home"), DelaKrug.zones(d).last().second.map { it.key })
        // Девять направлений — ось по месту через 40°; иначе — по порядку ровно по кругу.
        assertEquals(120f, DelaKrug.angle(d[1], 1, 9), 0.01f)
        assertEquals(90f, DelaKrug.angle(d[1], 1, 4), 0.01f)
        // 12.10.2026: date.toordinal() = 739 901 — нечётный день, второй принцип из двух (как у сервера).
        assertEquals("Решил — делаю сразу.", DelaKrug.principleOfDay(DelaKrug.principles(), today))
        assertEquals("Страшно — значит, делаю.", DelaKrug.principleOfDay(DelaKrug.principles(), "2026-10-13"))
        assertNull(DelaKrug.principleOfDay(emptyList(), today))
    }

    @Test
    fun `главное — у корня цепочки, своё под целью — метка`() {
        val school = task("school") { it.copy(goal = true, directions = listOf("education")) }
        val maths = task("maths") { it.copy(goal = true, whyId = "school") }
        val step = task("step") { it.copy(whyId = "maths", projectId = "fam") }
        val own = task("own") { it.copy(whyId = "maths", directions = listOf("home", "health", "work")) }
        val client = task("client") { it.copy(projectId = "p-a") }
        val alien = task("alien") { it.copy(projectId = "p-a", ownerId = "colleague") }
        val s = snap(school, maths, step, own, client, alien, krug = mapOf("p-a" to Dela.KrugProject(listOf("work"))))
        // «Зачем» сильнее проекта: шаг к цели — в направлении корня.
        assertEquals(listOf("education"), DelaKrug.dirsOf(step, s, "me"))
        // В 7.1 своё было сильнее; теперь — метки после главного, всего не больше трёх.
        assertEquals(listOf("education", "home", "health"), DelaKrug.dirsOf(own, s, "me"))
        assertEquals(listOf("work"), DelaKrug.dirsOf(client, s, "me"))
        assertEquals(emptyList<String>(), DelaKrug.dirsOf(alien, s, "me"))
        assertEquals(listOf("step", "maths", "school"), DelaKrug.upOf(step, s).map { it.task?.id })
        assertEquals(setOf("maths", "step", "own"), DelaKrug.below(s, "maths"))
        assertEquals("Образование › school › maths", DelaKrug.goalPath(maths, s, dirs))
        // Круг «Зачем» не вешает.
        val loopA = task("a") { it.copy(whyId = "b") }
        val loopB = task("b") { it.copy(whyId = "a") }
        assertEquals(emptyList<String>(), DelaKrug.dirsOf(loopA, snap(loopA, loopB), "me"))
    }

    @Test
    fun `через проект — к его цели, проект висит на цели, дело наследует её направление`() {
        val health = task("health-goal") { it.copy(goal = true, directions = listOf("health")) }
        val client = task("client") { it.copy(projectId = "p-a", directions = listOf("home")) }
        val s = snap(health, client, krug = mapOf("p-a" to Dela.KrugProject(listOf("work"), whyId = "health-goal")))
        val up = DelaKrug.upOf(client, s)
        assertEquals(listOf(false, true, false), up.map { it.isProject })
        assertEquals("p-a", up[1].projectId)
        assertEquals(listOf("health", "home"), DelaKrug.dirsOf(client, s, "me"))
        // У цели проекта нет — цепочка цели через проект не идёт.
        val g = task("g") { it.copy(goal = true, projectId = "p-a") }
        assertEquals(1, DelaKrug.upOf(g, snap(g, krug = mapOf("p-a" to Dela.KrugProject(listOf("work"))))).size)
    }

    @Test
    fun `«Моё» — хочу сам и шаги к целям, без отбить, мониторить и целей, до трёх`() {
        val goal = task("goal") { it.copy(goal = true, directions = listOf("education")) }
        val step = task("step") { it.copy(whyId = "goal", createdAt = "2026-10-02T10:00:00+03:00") }
        val owed = task("owed") { it.copy(whyId = "goal", owed = true) }
        val waiting = task("wait") { it.copy(whyId = "goal", ball = Dela.WAITING) }
        val want = task("want") { it.copy(want = true, createdAt = "2026-10-05T10:00:00+03:00") }
        val wantGoal = task("want-goal") { it.copy(want = true, goal = true, whyId = "goal") }
        val plain = task("plain")
        val s = snap(goal, step, owed, waiting, want, wantGoal, plain)
        assertEquals(listOf("want", "step"), DelaKrug.mine(s, "me", today, "all").map { it.id })
    }

    @Test
    fun `«Моё» — без того, что и так на виду, отсеяно до отбора трёх`() {
        // 13.10.2026 (`krug.mine(skip=…)` сервера, `mineTasks` веба): срок сегодня — в «Сегодня», отмеченное — в «Сейчас».
        val wants = (1..3).map { i -> task("w$i") { it.copy(want = true, createdAt = "2026-10-0${i}T10:00:00+03:00") } }
        val dueToday = wants[0].copy(dueDate = today)
        val taken = wants[1].copy(focusOn = today)
        val more = task("w4") { it.copy(want = true, createdAt = "2026-10-08T10:00:00+03:00") }
        val late = task("w5") { it.copy(want = true, dueDate = "2026-10-01", createdAt = "2026-10-09T10:00:00+03:00") }
        val s = snap(dueToday, taken, wants[2], more, late)
        // Двое на виду ушли — на их место встали следующие, а не пустота; просроченное «Моё» не прячет.
        assertEquals(listOf("w3", "w4", "w5"), DelaKrug.mine(s, "me", today, "all").map { it.id })
    }

    @Test
    fun `движение — только главное направление, дело 1, цель 3`() {
        val goal = task("goal") { it.copy(goal = true, directions = listOf("health"), status = Dela.DONE, completedAt = "2026-10-11T12:00:00+03:00") }
        val step = task("step") { it.copy(whyId = "goal", directions = listOf("home"), status = Dela.DONE, completedAt = "2026-10-10T12:00:00+03:00") }
        val old = task("old") { it.copy(directions = listOf("health"), status = Dela.DONE, completedAt = "2026-09-01T12:00:00+03:00") }
        val mv = DelaKrug.movement(snap(goal, step, old), "me", today)
        assertEquals(mapOf("health" to DelaKrug.Move(2, 4)), mv)
        assertEquals(6.0, mv.getValue("health").radius, 0.001)
        assertEquals(10.0, DelaKrug.radius(30), 0.001)
    }

    @Test
    fun `цели — не дела, их нет ни в одном списке дел, а в поиске есть`() {
        val goal = task("goal") { it.copy(title = "Уверенно считает до 20", goal = true, dueDate = today, focusOn = today) }
        val deed = task("deed") { it.copy(dueDate = today) }
        val doneGoal = task("done-goal") { it.copy(goal = true, status = Dela.DONE, completedAt = "2026-10-12T12:00:00+03:00") }
        val s = snap(goal, deed, doneGoal)
        assertEquals(listOf("deed"), DelaGroups.todayTasks(s, "me", today, "all").map { it.id })
        assertEquals(listOf("deed"), DelaViews.list(s, "me", "all", showDone = true).map { it.id })
        assertEquals(listOf("deed"), DelaGroups.scopeItems(s, "me", today, "all", DelaGroups.Scope.ALL).map { it.id })
        assertEquals(emptyList<String>(), DelaViews.nowTasks(s, "me", today).map { it.id })
        assertEquals(listOf("deed"), DelaViews.openMine(s, "me", "all").map { it.id })
        assertEquals(1, DelaViews.counts(s, "me", today, "all", 0L).today)
        assertEquals(0, DelaGroups.doneToday(s, "me", today))
        assertEquals(listOf("deed"), Dela.morning(s, "me", today, "all", 0L).today.map { it.id })
        assertEquals(listOf("deed"), ZasechkaTasks.shortlist(s, "me", today, emptyList(), 0L).map { it.id })
        assertEquals(listOf("goal"), DelaViews.search(s, "считает", showDone = false).map { it.id })
    }

    @Test
    fun `дерево — родитель, открыто под узлом, движение за неделю, порядок детей`() {
        val g1 = task("g1") { it.copy(num = 2, goal = true, directions = listOf("health")) }
        val g0 = task("g0") { it.copy(num = 1, goal = true, directions = listOf("health")) }
        val sub = task("sub") { it.copy(num = 3, goal = true, whyId = "g1") }
        val a = task("a") { it.copy(whyId = "sub") }
        val b = task("b") { it.copy(whyId = "g1") }
        val inProj = task("in-proj") { it.copy(projectId = "p-a") }
        val loose = task("loose") { it.copy(directions = listOf("health")) }
        val bare = task("bare")
        val doneStep = task("done") { it.copy(whyId = "sub", status = Dela.DONE, completedAt = "2026-10-11T12:00:00+03:00") }
        val doneGoal = task("done-goal") { it.copy(goal = true, whyId = "g1", status = Dela.DONE, completedAt = "2026-10-10T12:00:00+03:00") }
        val s = snap(g1, g0, sub, a, b, inProj, loose, bare, doneStep, doneGoal,
            krug = mapOf("p-a" to Dela.KrugProject(listOf("work"), whyId = "g1")))
        val m = DelaKrug.treeModel(s, "me", today)
        assertEquals("t:sub", m.parentKey(a))
        assertEquals("p:p-a", m.parentKey(inProj))
        assertEquals("d:health", m.parentKey(loose))
        assertEquals(DelaKrug.NONE, m.parentKey(bare))
        // Под направлением: цели по номеру, потом дела; под целью — подцель, проект, дело.
        assertEquals(listOf("t:g0", "t:g1", "t:loose"), m.kids("d:health").map { it.key })
        assertEquals(listOf("t:sub", "p:p-a", "t:b"), m.kids("t:g1").map { it.key })
        // Открыто — дела на любой глубине, цели не в счёт.
        assertEquals(3, m.open("t:g1"))
        assertEquals(4, m.open("d:health"))
        assertEquals(0, m.open("t:g0"))
        // Движение — каждому узлу вверх: шаг 1, цель 3.
        assertEquals(1, m.moved("t:sub"))
        assertEquals(4, m.moved("t:g1"))
        assertEquals(4, m.moved("d:health"))
    }

    @Test
    fun `ветки — проекты свёрнуты с завода, пустой проект не виден, уровни сворачивают до своего`() {
        val g = task("g") { it.copy(goal = true, directions = listOf("health")) }
        val inProj = task("in-proj") { it.copy(projectId = "p-a") }
        val step = task("step") { it.copy(whyId = "g") }
        val s = snap(g, inProj, step, krug = mapOf("p-a" to Dela.KrugProject(whyId = "g"), "fam" to Dela.KrugProject(whyId = "g")))
        val m = DelaKrug.treeModel(s, "me", today)
        val rows = DelaKrug.outline(m, "d:health", DelaKrug::foldedByDefault)
        assertEquals(listOf("t:g" to 0, "p:p-a" to 1, "t:step" to 1), rows.map { it.item.key to it.depth })
        assertFalse(rows[1].expanded)
        val open = DelaKrug.outline(m, "d:health", { false })
        assertEquals(listOf("t:g", "p:p-a", "t:in-proj", "t:step"), open.map { it.item.key })
        assertEquals(listOf("t:g"), DelaKrug.outline(m, "d:health", { it == "t:g" }).map { it.item.key })
        // «1» — только направление; «2» — его цели свёрнуты; «3» — раскрыты цели, свёрнут проект под ними; «Всё» — всё.
        assertEquals(mapOf("d:health" to true, "t:g" to true, "p:p-a" to true, "p:fam" to true), DelaKrug.levelFolds(m, listOf("d:health"), 1))
        assertEquals(mapOf("d:health" to false, "t:g" to true, "p:p-a" to true, "p:fam" to true), DelaKrug.levelFolds(m, listOf("d:health"), 2))
        assertEquals(mapOf("d:health" to false, "t:g" to false, "p:p-a" to true, "p:fam" to true), DelaKrug.levelFolds(m, listOf("d:health"), 3))
        assertEquals(mapOf("d:health" to false, "t:g" to false, "p:p-a" to false, "p:fam" to false), DelaKrug.levelFolds(m, listOf("d:health"), 0))
    }

    @Test
    fun `список — плоско по пути, фильтры направления, цели и «без зачем»`() {
        val g = task("g") { it.copy(title = "Белок 140+", goal = true, directions = listOf("health")) }
        val step = task("step") { it.copy(whyId = "g", directions = listOf("home")) }
        val deep = task("deep") { it.copy(whyId = "step") }
        val loose = task("loose") { it.copy(directions = listOf("work")) }
        val s = snap(g, step, deep, loose)
        assertEquals(
            listOf("loose" to "Дело", "step" to "Здоровье › Белок 140+", "deep" to "Здоровье › Белок 140+ › step"),
            DelaKrug.treeList(s, "me", "all", dirs, today).map { it.task.id to it.path },
        )
        // Метка — тоже направление фильтра.
        assertEquals(listOf("step"), DelaKrug.treeList(s, "me", "all", dirs, today, dir = "home").map { it.task.id })
        assertEquals(setOf("step", "deep"), DelaKrug.treeList(s, "me", "all", dirs, today, goal = "g").map { it.task.id }.toSet())
        assertEquals(listOf("loose"), DelaKrug.treeList(s, "me", "all", dirs, today, onlyLoose = true).map { it.task.id })
        assertEquals(listOf("g"), DelaKrug.goalList(s, "me", dirs).map { it.task.id })
    }

    @Test
    fun `куда повесить — цели и дела с шагами, без ориентиров и того, что ниже`() {
        val g = task("g") { it.copy(goal = true, directions = listOf("health")) }
        val mark = task("mark") { it.copy(goal = true, noSteps = true, directions = listOf("work")) }
        val step = task("step") { it.copy(whyId = "g") }
        val sub = task("sub") { it.copy(whyId = "step") }
        val inProj = task("in-proj") { it.copy(projectId = "p-a", directions = listOf("work")) }
        val s = snap(g, mark, step, sub, inProj, krug = mapOf("p-a" to Dela.KrugProject(listOf("work"))))
        assertEquals(listOf("g", "step"), DelaKrug.goalPool(s, "me", dirs).map { it.task.id })
        assertEquals(listOf("g"), DelaKrug.goalPool(s, "me", dirs, DelaKrug.below(s, "step")).map { it.task.id })
        assertEquals(listOf("step"), DelaKrug.findGoals(DelaKrug.goalPool(s, "me", dirs), "здоровье step").map { it.task.id })
        // На цель — новое «Зачем»; ориентир и своё же — отказ словами; на направление — «без зачем».
        assertEquals("g", DelaKrug.hang(inProj, s, dirs, goal = g).set?.getString("why_id"))
        assertNull(DelaKrug.hang(inProj, s, dirs, goal = mark).set)
        assertEquals("Под этой целью дел не заводят — она ориентир", DelaKrug.hang(inProj, s, dirs, goal = mark).said)
        assertNull(DelaKrug.hang(g, s, dirs, goal = sub).set)
        val toDir = DelaKrug.hang(step, s, dirs, dir = "home").set!!
        assertTrue(toDir.isNull("why_id"))
        assertEquals("[\"home\"]", toDir.getJSONArray("directions").toString())
        // Дело проекта в Круге: направление и так от проекта — свои остаются.
        assertEquals("[\"work\"]", DelaKrug.hang(inProj, s, dirs, dir = "home").set!!.getJSONArray("directions").toString())
    }

    @Test
    fun `место в дереве — цепочка, шаги, соседи и «дальше»`() {
        val top = task("top") { it.copy(title = "Сильная школа", goal = true, directions = listOf("education")) }
        val g = task("g") { it.copy(title = "Знает математику", goal = true, whyId = "top", rhythm = "ежедневно") }
        val a = task("a") { it.copy(title = "Распечатать столбики", whyId = "g") }
        val b = task("b") { it.copy(title = "Купить тетрадь", whyId = "g") }
        val sub = task("sub") { it.copy(title = "Таблица умножения", goal = true, whyId = "g") }
        val s = snap(top, g, a, b, sub)
        val p = DelaKrug.place(a, s, today)
        assertEquals("education", p.main)
        assertEquals(listOf("top", "g"), p.chain.map { it.task?.id })
        assertEquals(listOf("sub", "b"), p.sibs.map { it.id })
        assertEquals("Сделаешь — к цели «Знает математику» останется 1 шаг", p.next)
        assertEquals(
            "Это последний открытый шаг к цели «Знает математику» — дальше «Сильная школа»",
            DelaKrug.place(a, snap(top, g, a, sub), today).next,
        )
        assertEquals("Под целью 1 подцель и 2 шага — а сама она ведёт к «Сильная школа»", DelaKrug.place(g, s, today).next)
        // Шаги цели — подцели первыми.
        assertEquals("sub", DelaKrug.place(g, s, today).kids.first().id)
        assertTrue(DelaKrug.place(sub, s, today).next.startsWith("Пустая цель"))
        val client = task("client") { it.copy(projectId = "p-a") }
        val viaProject = snap(top, client, krug = mapOf("p-a" to Dela.KrugProject(whyId = "top")))
        assertEquals("Двигает проект «Альфа» — а он ведёт к «Сильная школа»", DelaKrug.place(client, viaProject, today).next)
        val bare = task("bare") { it.copy(directions = listOf("home", "work")) }
        val pb = DelaKrug.place(bare, snap(bare), today)
        assertEquals(listOf("work"), pb.labels)
        assertTrue(pb.next.startsWith("Висит прямо на направлении"))
        assertTrue(DelaKrug.place(task("x"), snap(task("x")), today).next.startsWith("Ни цели, ни направления"))
    }

    @Test
    fun `строка — направления, «зачем», цель и практика, в дереве и у чужого — тише`() {
        val goal = task("goal") { it.copy(title = "Уверенно считает до 20", goal = true, directions = listOf("education")) }
        val practice = task("practice") { it.copy(goal = true, rhythm = "ежедневно", directions = listOf("health")) }
        val step = task("step") { it.copy(whyId = "goal") }
        val bare = task("bare")
        val alien = task("alien") { it.copy(ownerId = "colleague", directions = listOf("work")) }
        val s = snap(goal, practice, step, bare, alien)
        val chips = DelaViews.chips(step, null, s, today)
        assertTrue(chips.toString(), "Образование" in chips && "зачем: Уверенно считает до 20" in chips)
        assertTrue(DelaViews.NO_DIRECTION in DelaViews.chips(bare, null, s, today))
        assertTrue("цель · 1 шаг" in DelaViews.chips(goal, null, s, today))
        assertTrue("практика · ежедневно" in DelaViews.chips(practice, null, s, today))
        assertTrue("цель" in DelaViews.chips(goal.copy(id = "empty-goal"), null, s, today))
        // В дереве место дела видно и так: ни направления, ни «зачем».
        val inTree = DelaViews.chips(step, null, s, today, inTree = true)
        assertFalse(inTree.toString(), inTree.any { it == "Образование" || it.startsWith("зачем") })
        // Чужое дело — без Круга.
        assertFalse(DelaViews.chips(alien, null, s, today, me = "me").contains("Дело"))
        // Старый сервер без Круга — подписей нет.
        assertFalse(DelaViews.chips(step, null, s.copy(features = emptySet()), today).any { it.startsWith("зачем") })
    }

    @Test
    fun `поля Круга и дерева — в синке, на диске, в очереди и в операциях`() {
        val o = JSONObject().put("id", "t1").put("title", "Шаг").put("directions", JSONArray(listOf("work")))
            .put("why_id", "g1").put("why_num", 7).put("why_title", "Цель").put("goal", true).put("rhythm", "2 раза в неделю")
            .put("no_steps", true)
        val t = Dela.task(o)!!
        assertEquals(listOf("work"), t.directions)
        assertEquals("g1", t.whyId)
        assertTrue(t.goal && t.noSteps)
        assertEquals("2 раза в неделю", t.rhythm)
        assertEquals(t, Dela.task(Dela.json(t)))
        val resp = JSONObject().put("full", true).put("seq", 5).put("features", JSONArray(listOf("krug", "tree")))
            .put("tasks", JSONArray()).put("krug", JSONArray().put(JSONObject().put("id", "p-a").put("kind", "project")
                .put("directions", JSONArray(listOf("work"))).put("why_id", "g1")))
        val s = Dela.merge(Dela.Snapshot(), resp, 1L)
        assertTrue(s.krugOn && s.treeOn)
        assertEquals(mapOf("p-a" to Dela.KrugProject(listOf("work"), "g1")), s.krug)
        assertEquals(s.krug, Dela.fromJson(Dela.toJson(s)).krug)
        // Проект на цель — видно сразу, до ответа сервера.
        val withProject = s.copy(projects = mapOf("p-a" to Dela.Project(id = "p-a", name = "Альфа")))
        val ov = Dela.overlay(withProject, listOf(DelaCrm.projectSetOp("p-a", JSONObject().put("why_id", "g2"))), "me", today, "2026-10-12T10:00:00+03:00")
        assertEquals(Dela.KrugProject(listOf("work"), "g2"), ov.krug["p-a"])
        // Правка направления — только оно в set; новое дело без направлений их не шлёт.
        val plain = t.copy(goal = false, rhythm = "", noSteps = false)
        val (set, _) = Dela.diff(plain, plain.copy(directions = listOf("work", "health")))
        assertEquals(setOf("directions"), set.keys().asSequence().toSet())
        assertFalse(Dela.createOp(Dela.Task(id = "n", title = "Новое")).getJSONObject("task").has("directions"))
        assertEquals("g1", Dela.createOp(Dela.Task(id = "n", title = "Шаг", whyId = "g1")).getJSONObject("task").getString("why_id"))
    }

    @Test
    fun `цель, ритм и ориентир уходят только серверу с деревом`() {
        val t = Dela.Task(id = "t", title = "Знает математику", projectId = "fam", dealId = "d1")
        val goal = t.copy(goal = true, projectId = "")
        // Без фичи `tree` — ни в правке, ни в новом деле: старый сервер отверг бы операцию целиком.
        assertEquals(setOf("project_id"), Dela.diff(t, goal).first.keys().asSequence().toSet())
        assertFalse(Dela.createOp(goal).getJSONObject("task").has("goal"))
        val (set, was) = Dela.diff(t, goal, tree = true)
        assertTrue(set.getBoolean("goal"))
        assertFalse(was.getBoolean("goal"))
        val create = Dela.createOp(goal.copy(rhythm = "ежедневно"), tree = true).getJSONObject("task")
        assertTrue(create.getBoolean("goal"))
        assertEquals("ежедневно", create.getString("rhythm"))
        assertFalse(create.has("no_steps"))
        assertFalse(Dela.createOp(t, tree = true).getJSONObject("task").has("goal"))
        // Поверх копии — как сервер: цель без проекта и сделки, ритм сам делает целью.
        val now = "2026-10-12T10:00:00+03:00"
        val made = Dela.applyFields(t, JSONObject().put("goal", true), today, now)
        assertTrue(made.goal)
        assertEquals("" to "", made.projectId to made.dealId)
        val practice = Dela.applyFields(t, JSONObject().put("rhythm", " ежедневно "), today, now)
        assertTrue(practice.goal)
        assertEquals("ежедневно", practice.rhythm)
        assertFalse(Dela.applyFields(made, JSONObject().put("goal", false), today, now).goal)
    }

    @Test
    fun `«Подскажи» разборщика — путь предложенной цели`() {
        val t = task("t") { it.copy(title = "Купить гантели", projectId = "fam") }
        val sg = Dela.Suggestion(
            id = "s1", kind = "update", taskId = "t", source = "krug",
            payload = """{"directions":["health"],"why_id":"g","goal_path":"Здоровье › Сила"}""",
        )
        assertEquals(DelaViews.SugText("Купить гантели", "цель: Здоровье › Сила"), DelaViews.sugText(sg, snap(t), today))
        assertEquals("Семья", DelaViews.sugText(sg.copy(payload = """{"directions":["health"]}"""), snap(t), today).hint)
    }
}
