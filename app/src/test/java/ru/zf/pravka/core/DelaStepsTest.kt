package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Дела внутри дела (15.10.2026, docs/dela-phone-23.md) — то же правило, что сервер (`nest.nest_fill`, `step_due`,
 * `_nest_rise`, тест `test_dela_nest.py`) и веб (`stepDue`, `stepChips`, `stepsFirst`, `nestForest`). Данные выдуманные.
 */
class DelaStepsTest {

    private val today = "2026-10-15"

    @Before
    fun svod() {
        Svod.current = mapOf(
            DelaKrug.DIRECTIONS to Svod.Entry(DelaKrug.DIRECTIONS, value = """[{"key":"work","pos":3,"name":"Дело","short":"Дело"}]"""),
        )
    }

    @After
    fun clear() {
        Svod.current = emptyMap()
    }

    private fun task(id: String, f: (Dela.Task) -> Dela.Task = { it }) =
        f(Dela.Task(id = id, title = id, ownerId = "me", createdBy = "me", createdAt = "2026-10-01T10:00:00+03:00"))

    private fun snap(vararg t: Dela.Task, krug: Boolean = true) = Dela.Snapshot(
        tasks = t.associateBy { it.id },
        projects = listOf(Dela.Project(id = "p-a", name = "Ромашка", ownerId = "me")).associateBy { it.id },
        deals = listOf(Dela.Deal(id = "d-a", projectId = "p-a", name = "Ромашка: термшит")).associateBy { it.id },
        features = if (krug) setOf(Dela.FEATURE_KRUG, Dela.FEATURE_TREE, Dela.FEATURE_NEST) else emptySet(),
    )

    @Test
    fun `срок нового дела внутри — сегодня, если родитель в «Сейчас», просрочен или на сегодня, позже — тот же, без срока — без`() {
        assertEquals(today, DelaSteps.due(task("a") { it.copy(focusOn = today, dueDate = "2026-10-20") }, today))
        assertEquals(today, DelaSteps.due(task("a") { it.copy(dueDate = "2026-10-11") }, today))
        assertEquals(today, DelaSteps.due(task("a") { it.copy(dueDate = today) }, today))
        assertEquals("2026-10-20", DelaSteps.due(task("a") { it.copy(dueDate = "2026-10-20") }, today))
        assertEquals("", DelaSteps.due(task("a"), today))
        // Вчерашнее «Сейчас» — не сегодняшнее: без срока дело внутри остаётся без срока.
        assertEquals("", DelaSteps.due(task("a") { it.copy(focusOn = "2026-10-14") }, today))
    }

    @Test
    fun `черновик — «Зачем», проект, сделка, человек и срок от родителя, у цели — только «Зачем», у чужого — без связи`() {
        val parent = task("p") { it.copy(title = "Подготовить термшит и отправить Ивану", projectId = "p-a", dealId = "d-a", personId = "ivan", dueDate = today) }
        val s = snap(parent)
        val d = DelaSteps.draft(parent, s, "me", today, "Набросать драфт из расшифровки встречи")
        assertEquals(listOf("p", "p-a", "d-a", "ivan", today, "me"), listOf(d.whyId, d.projectId, d.dealId, d.personId, d.dueDate, d.ownerId))
        assertEquals("", d.focusOn) // «Сейчас» — только если сказал
        val op = Dela.createOp(d).getJSONObject("task")
        assertEquals("p", op.getString("why_id"))
        assertEquals(today, op.getString("due_date"))
        assertFalse(op.has("pos")) // место не задано — и не шлётся
        // Очищенное в листе «Для этого» — явным null: сервер (`nest.nest_fill`, 16.10.2026, docs/dela-phone-32.md §1)
        // дополняет новое дело внутри полями родителя, если их в операции нет, и вернул бы человека и срок, которые Саша
        // убрал.
        val cleared = Dela.createOp(d.copy(personId = "", dueDate = "")).getJSONObject("task")
        assertTrue(cleared.has("person_id") && cleared.isNull("person_id") && cleared.has("due_date") && cleared.isNull("due_date"))
        assertEquals(listOf("p-a", "d-a"), listOf(cleared.getString("project_id"), cleared.getString("deal_id")))
        // Без «Зачем» — как раньше: пустое не шлётся вовсе.
        assertFalse(Dela.createOp(d.copy(whyId = "", personId = "")).getJSONObject("task").has("person_id"))
        val goal = task("g") { it.copy(goal = true, dueDate = "2026-10-30") }
        val g = DelaSteps.draft(goal, snap(goal), "me", today)
        assertEquals(listOf("g", "", "", "", "2026-10-30"), listOf(g.whyId, g.projectId, g.dealId, g.personId, g.dueDate))
        // Чужое дело (проект клиента): «Зачем» — только своё дело (правило базы), остальное — как у вложенного.
        val alien = parent.copy(ownerId = "colleague")
        val a = DelaSteps.draft(alien, snap(alien), "me", today)
        assertEquals(listOf("", "p-a", "d-a"), listOf(a.whyId, a.projectId, a.dealId))
        assertFalse(DelaSteps.canLink(alien, snap(alien), "me"))
        // Старый сервер без Круга why_id не знает — без связи (иначе отверг бы операцию целиком).
        assertEquals("", DelaSteps.draft(parent, snap(parent, krug = false), "me", today).whyId)
        assertFalse(DelaSteps.canLink(parent.copy(noSteps = true), s, "me"))
        assertEquals("Завёл для «Подготовить термшит и отправить Ивану»: «Набросать драфт из расшифровки встречи», на сегодня",
            DelaSteps.madeWords(parent, d, today))
    }

    @Test
    fun `строка — «для дело» у вложенного, «сначала» первое по месту у родителя, у цели — прежнее «зачем» и «цель»`() {
        val parent = task("p") { it.copy(title = "Подготовить простой термшит по сделке и отправить Ивану Петрову") }
        val step = task("s") { it.copy(title = "Набросать драфт", whyId = "p", dueDate = today, pos = 2) }
        val s = snap(parent, step)
        val stepChips = DelaViews.chips(step, null, s, today)
        assertTrue(stepChips.toString(), "для: Подготовить простой термшит по сделке и…" in stepChips)
        assertFalse(stepChips.any { it.startsWith("зачем") })
        val parentChips = DelaViews.chips(parent, null, s, today)
        assertTrue(parentChips.toString(), "сначала: Набросать драфт" in parentChips)
        assertFalse(parentChips.toString(), parentChips.any { it.startsWith("цель") })
        // Первое — по месту (pos), не по времени заведения: «Сверить» поставили раньше.
        val second = task("s2") { it.copy(title = "Сверить цифры", whyId = "p", pos = 1) }
        assertEquals("сначала: Сверить цифры (ещё 1)", DelaSteps.firstLine(parent, snap(parent, step, second)))
        // Сделанное внутри — уже не «сначала».
        assertNull(DelaSteps.firstLine(parent, snap(parent, step.copy(status = Dela.DONE))))
        // «Деревом» — вложенность видна отступом, подписи не нужны.
        assertFalse(DelaViews.chips(step, null, s, today, nested = true).any { it.startsWith("для:") })
        // Под целью — прежние подписи: «зачем: цель» у её дела, «цель · 1 шаг» у цели.
        val goal = task("g") { it.copy(title = "Сделки закрываются", goal = true) }
        val toGoal = task("t") { it.copy(whyId = "g") }
        val gs = snap(goal, toGoal)
        assertTrue("зачем: Сделки закрываются" in DelaViews.chips(toGoal, null, gs, today))
        assertNull(DelaSteps.forLine(toGoal, gs))
        assertTrue("цель · 1 шаг" in DelaViews.chips(goal, null, gs, today))
        // В дереве жизни место видно и так.
        assertFalse(DelaViews.chips(step, null, s, today, inTree = true).any { it.startsWith("для:") })
    }

    @Test
    fun `плоский список — дело внутри прямо под родителем, «Деревом» — родитель, под ним по месту, свёрнутое не раскрыто`() {
        val a = task("a")
        val parent = task("p")
        val b = task("b")
        val step = task("s") { it.copy(whyId = "p", pos = 2) }
        val first = task("f") { it.copy(whyId = "p", pos = 1) }
        val sub = task("ss") { it.copy(whyId = "s") }
        val alone = task("x") { it.copy(whyId = "not-here") }
        // Записка отладки 39 (18.10.2026): дело внутри над родителем читалось как «внутри дела выше» — теперь под ним.
        assertEquals(listOf("a", "p", "f", "s", "ss", "b", "x"), DelaSteps.stepsFirst(listOf(a, parent, b, step, alone, sub, first)).map { it.id })
        // Родителя в списке нет — дело на своём месте.
        assertEquals(listOf("a", "s", "b"), DelaSteps.stepsFirst(listOf(a, step, b)).map { it.id })
        // Кривой круг «Зачем» никого не теряет.
        val c1 = task("c1") { it.copy(whyId = "c2") }
        val c2 = task("c2") { it.copy(whyId = "c1") }
        assertEquals(setOf("c1", "c2", "a"), DelaSteps.stepsFirst(listOf(c1, a, c2)).map { it.id }.toSet())
        assertEquals(setOf("c1", "c2", "a"), DelaSteps.forest(listOf(c1, a, c2)).roots.map { it.id }.toSet())

        val f = DelaSteps.forest(listOf(a, parent, step, sub, first, alone))
        assertEquals(listOf("a", "p", "x"), f.roots.map { it.id })
        val lines = DelaSteps.lines(f.roots, f.kids, emptySet())
        assertEquals(listOf("a" to 0, "p" to 0, "f" to 1, "s" to 1, "ss" to 2, "x" to 0), lines.map { it.task.id to it.depth })
        assertEquals(listOf(null, true, null, true, null, null), lines.map { it.fold })
        val folded = DelaSteps.lines(f.roots, f.kids, setOf("p"))
        assertEquals(listOf("a", "p", "x"), folded.map { it.task.id })
        assertEquals(false, folded[1].fold)
    }

    @Test
    fun `сделано последнее дело внутри — родитель не закрывается, а поднимается на сегодня, и поверх очереди`() {
        val parent = task("p") { it.copy(title = "Отправить термшит", dueDate = "2026-10-22") }
        val a = task("a") { it.copy(whyId = "p") }
        val b = task("b") { it.copy(whyId = "p") }
        val s = snap(parent, a, b)
        assertNull(DelaSteps.rise(a, s, today)) // внутри ещё b
        val r = DelaSteps.rise(a, snap(parent, a, b.copy(status = Dela.DONE)), today)!!
        assertEquals(listOf("p", today, Dela.OPEN), listOf(r.parent.id, r.parent.dueDate, r.parent.status))
        assertTrue(r.moved)
        assertEquals("всё внутри сделано — теперь: «Отправить термшит», на сегодня", DelaSteps.riseWords(r))
        // Просроченный родитель — срок не трогаем.
        val late = parent.copy(dueDate = "2026-10-10")
        val r2 = DelaSteps.rise(a, snap(late, a), today)!!
        assertFalse(r2.moved)
        assertEquals("2026-10-10", r2.parent.dueDate)
        // Цель — живёт в дереве: не поднимается.
        assertNull(DelaSteps.rise(a, snap(parent.copy(goal = true), a), today))
        // Очередь поверх копии (офлайн): «Сделано» последнего — родитель уже на сегодня.
        val over = Dela.overlay(snap(parent, a), listOf(Dela.statusOp("task.done", "a")), "me", today, "2026-10-15T12:00:00+03:00")
        assertEquals(today, over.tasks.getValue("p").dueDate)
        assertEquals(Dela.OPEN, over.tasks.getValue("p").status)
        // Старый сервер без nest — сам не поднимет, и телефон не выдумывает.
        val old = Dela.overlay(snap(parent, a).copy(features = setOf(Dela.FEATURE_KRUG)), listOf(Dela.statusOp("task.done", "a")), "me", today, "x")
        assertEquals("2026-10-22", old.tasks.getValue("p").dueDate)
    }

    @Test
    fun `вложить, вынуть и место среди соседей — операциями, петлю не предлагаем`() {
        val parent = task("p")
        val a = task("a") { it.copy(whyId = "p", pos = 1) }
        val b = task("b") { it.copy(whyId = "p") }
        val c = task("c") { it.copy(whyId = "p") }
        val other = task("o")
        val s = snap(parent, a, b, c, other)
        // b — позже a (без места — по времени), c — последний; «c раньше» — нумерация 1, 2, 3 с переставленными.
        val ops = DelaSteps.moveOps(c, -1, s)
        val set = ops.associate { it.getString("id") to it.getJSONObject("set").getInt("pos") }
        assertEquals(mapOf("b" to 3, "c" to 2), set)
        assertTrue(DelaSteps.moveOps(a, -1, s).isEmpty()) // первое выше некуда
        assertTrue(DelaSteps.moveOps(other, 1, s).isEmpty()) // не внутри дела
        // Поверх очереди — новый порядок сразу.
        val after = Dela.overlay(s, ops, "me", today, "x")
        assertEquals(listOf("a", "c", "b"), DelaSteps.insideOf("p", after).map { it.id })
        val nest = DelaSteps.nestOp(other, "p")
        assertEquals("p", nest.getJSONObject("set").getString("why_id"))
        assertTrue(nest.getJSONObject("was").isNull("why_id"))
        assertTrue(DelaSteps.nestOp(a, "").getJSONObject("set").isNull("why_id"))
        // Куда вложить p: не в себя и не в то, что внутри него.
        val pool = DelaSteps.pool(s, "me", listOf("p")).map { it.id }
        assertEquals(listOf("o"), pool)
        assertTrue(Dela.applyFields(a, JSONObject().put("pos", JSONObject.NULL), today, "x").pos == 0)
    }

    @Test
    fun `срок словами для тоста`() {
        assertEquals("на сегодня", DelaSteps.dueWords(today, today))
        assertEquals("на завтра", DelaSteps.dueWords("2026-10-16", today))
        assertEquals("на 20.10", DelaSteps.dueWords("2026-10-20", today))
        assertEquals("без срока", DelaSteps.dueWords("", today))
    }

    @Test
    fun `плоско — соседи без родителя по месту, уровни кладут дело внутри к родителю, кроме времени`() {
        // Хвост 3 (15.10.2026, `stepsFirst` и `nested` веба): в «Сегодня» родитель стоял в «Отбить», а его дело внутри —
        // в «Запустить», не рядом; без родителя в списке соседи шли по времени, а не по месту.
        val p = task("p") { it.copy(owed = true) }
        val a = task("a") { it.copy(whyId = "p", pos = 2, createdAt = "2026-10-01T10:00:01+03:00") }
        val b = task("b") { it.copy(whyId = "p", pos = 1, createdAt = "2026-10-01T10:00:02+03:00") }
        val x = task("x")
        val s = snap(p, a, b, x)
        assertEquals(listOf("b", "a", "x"), DelaSteps.stepsFirst(listOf(a, x, b), s).map { it.id })
        assertEquals(listOf("a", "x", "b"), DelaSteps.stepsFirst(listOf(a, x, b)).map { it.id }) // без копии — как было
        val g = task("g") { it.copy(goal = true) }
        val ga = a.copy(whyId = "g")
        val gb = b.copy(whyId = "g")
        assertEquals(listOf("a", "x", "b"), DelaSteps.stepsFirst(listOf(ga, x, gb), snap(g, ga, gb, x)).map { it.id }) // под целью
        assertEquals("p", DelaSteps.topIn(listOf(p, a, x))(a).id)
        assertEquals("a", DelaSteps.topIn(listOf(a, x))(a).id)
        val nodes = DelaGroups.nested(listOf(p, a, b, x), listOf(DelaGroups.Level.GRP), s, today)
        assertEquals(listOf(setOf("p", "a", "b"), setOf("x")), nodes.map { n -> n.items.map { it.id }.toSet() })
        assertEquals(listOf("p", "b", "a"), DelaSteps.stepsFirst(nodes[0].items, s).map { it.id })
        // «Время» — по своему сроку: дело внутри на сегодня, родитель — в пятницу.
        val pd = p.copy(dueDate = "2026-10-20")
        val ad = a.copy(dueDate = today)
        val byDate = DelaGroups.nested(listOf(pd, ad), listOf(DelaGroups.Level.DATE), snap(pd, ad), today)
        assertEquals(listOf(listOf("a"), listOf("p")), byDate.map { n -> n.items.map { it.id } })
    }

    @Test
    fun `главная «Сейчас» — дела внутри дела из «Сейчас» под ним, по месту, с глубиной`() {
        val now1 = task("now1") { it.copy(focusOn = today) }
        val in1 = task("in1") { it.copy(whyId = "now1", pos = 2) }
        val in2 = task("in2") { it.copy(whyId = "now1", pos = 1) }
        val deep = task("deep") { it.copy(whyId = "in2") }
        val done = task("done") { it.copy(whyId = "now1", status = Dela.DONE) }
        val s = snap(now1, in1, in2, deep, done)
        assertEquals(listOf("in2" to 1, "deep" to 2, "in1" to 1), DelaSteps.inner(listOf(now1), s).map { it.first.id to it.second })
        assertEquals(listOf("now1", "in2", "deep", "in1"), DelaSteps.stepsFirst(listOf(now1, in2, deep, in1), s).map { it.id })
    }

    @Test
    fun `«Вернуть» после «Сделано» поверх очереди — срок родителя прежний, правленый не трогаем, место при вложении уходит`() {
        val parent = task("p") { it.copy(title = "Отправить термшит", dueDate = "2026-10-22") }
        val a = task("a") { it.copy(whyId = "p") }
        val back = Dela.overlay(snap(parent, a), listOf(Dela.statusOp("task.done", "a"), Dela.statusOp("task.reopen", "a")), "me", today, "x")
        assertEquals("2026-10-22", back.tasks.getValue("p").dueDate)
        val moved = Dela.overlay(snap(parent, a), listOf(
            Dela.statusOp("task.done", "a"),
            Dela.setOp("p", JSONObject().put("due_date", "2026-10-18"), JSONObject().put("due_date", today)),
            Dela.statusOp("task.reopen", "a"),
        ), "me", today, "x")
        assertEquals("2026-10-18", moved.tasks.getValue("p").dueDate)
        // «Вложить в…» и «Вынуть» без места — прежнее место уходит (сервер — так же: `op_task_set`).
        val placed = a.copy(pos = 1)
        val other = task("o")
        val nested = Dela.overlay(snap(parent, placed, other), listOf(DelaSteps.nestOp(placed, "o")), "me", today, "x")
        assertEquals(0, nested.tasks.getValue("a").pos)
        // Другая правка (название, тот же «Зачем») место не трогает.
        val same = Dela.overlay(snap(parent, placed), listOf(Dela.setOp("a", JSONObject().put("title", "драфт").put("why_id", "p"), JSONObject())),
            "me", today, "x")
        assertEquals(1, same.tasks.getValue("a").pos)
    }

    @Test
    fun `напоминание — не дело внутри — не вкладывается, не держит и не поднимает родителя, в «Вложить в…» его нет`() {
        // Поезд 16.10.2026 (вагоны 2 и 4): как сервер — tasks.nest_rise / nest_fall и steps_open только по делам (dela_0032),
        // store._krug_own не вкладывает напоминание и не кладёт в него; веб — insideOf, canNest, parentOf.
        val parent = task("p") { it.copy(title = "Подготовить термшит", dueDate = "2026-10-20") }
        val a = task("a") { it.copy(whyId = "p") }
        val r = task("r") { it.copy(whyId = "p", kind = Dela.KIND_REMIND, remindAt = "2026-10-15T12:00:00+03:00") }
        val loose = task("l") { it.copy(kind = Dela.KIND_REMIND, remindAt = "2026-10-15T19:00:00+03:00") }
        val s = snap(parent, a, r, loose)
        assertEquals(listOf("a"), DelaSteps.insideOf("p", s).map { it.id })
        assertFalse("напоминание не вкладывают и не вынимают", DelaSteps.canNest(loose, s, "me"))
        assertTrue(DelaSteps.canNest(a, s, "me"))
        assertTrue("в «Вложить в…» напоминаний нет", DelaSteps.pool(s, "me", listOf("a")).none { it.remind })
        assertNull("в напоминание ничего не кладут — оно не родитель", DelaSteps.parentOf(task("x") { it.copy(whyId = "l") }, s))
        assertNull("«сначала: …» у напоминания нет", DelaSteps.firstLine(loose, s))
        // Сделано напоминание — родитель как был; последнее ДЕЛО внутри — поднимает, хоть напоминание и открыто.
        assertNull(DelaSteps.rise(r, s, today))
        val up = DelaSteps.rise(a, s, today)
        assertTrue(up != null && up.moved && up.parent.dueDate == today)
    }

    @Test
    fun `строка Claude над открытым делом — «Для этого» запросом карточки дела`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val sc = DelaAsk.stepScope(id)
        assertEquals(listOf("task", id, true), listOf(sc.getString("card"), sc.getString("open"), sc.getBoolean("step")))
    }

    @Test
    fun `«+» у строки — у открытого дела, своего и чужого, у цели, напоминания и сделанного нет, молния — только у своего`() {
        // 16.10.2026: «+» и молния у каждой строки, как `.lead` веба (`canStep`, `canNow`). Дела выдуманные.
        val mine = task("a") { it.copy(title = "Отправить Ивану Тестову договор") }
        val alien = mine.copy(id = "b", ownerId = "colleague")
        val goal = task("g") { it.copy(goal = true, title = "Альфа растёт") }
        val remind = task("r") { it.copy(kind = Dela.KIND_REMIND, remindAt = "2026-10-15T12:00:00+03:00") }
        val done = mine.copy(id = "d", status = Dela.DONE)
        val cancelled = mine.copy(id = "c", status = Dela.CANCELLED)
        assertEquals(listOf(true, true, false, false, false, false), listOf(mine, alien, goal, remind, done, cancelled).map(DelaSteps::canStep))
        // Молния — по-прежнему только у своего открытого дела: у чужого на её месте пусто той же ширины.
        assertEquals(listOf(true, false), listOf(mine, alien).map { DelaGroups.canNow(it, "me") })
        // «+» у чужого дела ведёт в тот же лист: дело внутри получает проект и сделку, но не «Зачем» (правило базы).
        assertEquals("", DelaSteps.draft(alien, snap(alien), "me", today).whyId)
        assertEquals("a", DelaSteps.draft(mine, snap(mine), "me", today).whyId)
    }
}
