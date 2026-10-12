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
 * Круг жизни на телефоне — как веб (`app.js`: `dirsOf`, `mineTasks`, `principleToday`, `krugTree`) и сервер
 * (`tasks.krug_dirs`, `krug.mine_from`, `krug.principle_of_day`). Данные выдуманные: настоящий справочник —
 * только в Своде владельца.
 */
class DelaKrugTest {

    private val today = "2026-10-12"

    @Before
    fun svod() {
        Svod.current = mapOf(
            DelaKrug.DIRECTIONS to Svod.Entry(
                DelaKrug.DIRECTIONS,
                value = """[{"key":"work","pos":3,"name":"Дело","short":"Дело","formula":"Дело растёт."},
                    {"key":"health","pos":12,"name":"Здоровье","short":"Здоровье"},
                    {"key":"home","pos":10,"name":"Комфорт в доме","short":"Дом"},
                    {"key":"education","pos":7,"name":"Образование"}]""",
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

    private fun snap(vararg t: Dela.Task, krug: Map<String, List<String>> = emptyMap()) = Dela.Snapshot(
        tasks = t.associateBy { it.id },
        projects = listOf(Dela.Project(id = "p-a", name = "Альфа", ownerId = "me"), Dela.Project(id = "fam", name = "Семья", ownerId = "me"))
            .associateBy { it.id },
        features = setOf(Dela.FEATURE_KRUG),
        krug = krug,
    )

    @Test
    fun `справочник по кругу — сверху 12, принцип дня по-питоновски`() {
        val d = DelaKrug.directions()
        assertEquals(listOf("health", "work", "education", "home"), d.map { it.key })
        assertEquals("Дом", d.last().label)
        // 12.10.2026: date.toordinal() = 739 901 — нечётный день, второй принцип из двух (как у сервера).
        assertEquals("Решил — делаю сразу.", DelaKrug.principleOfDay(DelaKrug.principles(), today))
        assertEquals("Страшно — значит, делаю.", DelaKrug.principleOfDay(DelaKrug.principles(), "2026-10-13"))
        assertNull(DelaKrug.principleOfDay(emptyList(), today))
    }

    @Test
    fun `наследование — свои, потом вверх по «Зачем», потом проект`() {
        val school = task("school") { it.copy(directions = listOf("education"), projectId = "fam") }
        val maths = task("maths") { it.copy(whyId = "school", projectId = "fam") }
        val step = task("step") { it.copy(whyId = "maths", projectId = "fam") }
        val own = task("own") { it.copy(whyId = "maths", directions = listOf("home")) }
        val client = task("client") { it.copy(projectId = "p-a") }
        val alien = task("alien") { it.copy(projectId = "p-a", ownerId = "natasha") }
        val s = snap(school, maths, step, own, client, alien, krug = mapOf("p-a" to listOf("work")))
        assertEquals(listOf("education"), DelaKrug.dirsOf(step, s, "me"))
        assertEquals(listOf("home"), DelaKrug.dirsOf(own, s, "me"))
        assertEquals(listOf("work"), DelaKrug.dirsOf(client, s, "me"))
        assertEquals(emptyList<String>(), DelaKrug.dirsOf(alien, s, "me"))
        assertEquals(listOf("maths", "school"), DelaKrug.goals(step, s).map { it.id })
        assertEquals(setOf("maths", "step", "own"), DelaKrug.below(s, "maths"))
        // Круг «Зачем» не вешает.
        val loopA = task("a") { it.copy(whyId = "b") }
        val loopB = task("b") { it.copy(whyId = "a") }
        assertEquals(emptyList<String>(), DelaKrug.dirsOf(loopA, snap(loopA, loopB), "me"))
    }

    @Test
    fun `«Моё» — хочу сам и шаги к целям, без отбить и мониторить, до трёх`() {
        val goal = task("goal") { it.copy(directions = listOf("education")) }
        val step = task("step") { it.copy(whyId = "goal", createdAt = "2026-10-02T10:00:00+03:00") }
        val owed = task("owed") { it.copy(whyId = "goal", owed = true) }
        val waiting = task("wait") { it.copy(whyId = "goal", ball = Dela.WAITING) }
        val want = task("want") { it.copy(want = true, createdAt = "2026-10-05T10:00:00+03:00") }
        val plain = task("plain")
        val s = snap(goal, step, owed, waiting, want, plain)
        assertEquals(listOf("want", "step"), DelaKrug.mine(s, "me", today, "all").map { it.id })
    }

    @Test
    fun `движение — дело 1, цель 3`() {
        val goal = task("goal") { it.copy(directions = listOf("health"), status = Dela.DONE, completedAt = "2026-10-11T12:00:00+03:00") }
        val step = task("step") { it.copy(whyId = "goal", status = Dela.DONE, completedAt = "2026-10-10T12:00:00+03:00") }
        val old = task("old") { it.copy(directions = listOf("health"), status = Dela.DONE, completedAt = "2026-09-01T12:00:00+03:00") }
        assertEquals(mapOf("health" to 4), DelaKrug.movement(snap(goal, step, old), "me", today))
        assertEquals(6.0, DelaKrug.radius(4), 0.001)
        assertEquals(10.0, DelaKrug.radius(30), 0.001)
    }

    @Test
    fun `дерево — цели лесенкой сверху, шаг под целью`() {
        val school = task("school") { it.copy(directions = listOf("education")) }
        val maths = task("maths") { it.copy(whyId = "school") }
        val step = task("step") { it.copy(whyId = "maths") }
        val loose = task("loose") { it.copy(directions = listOf("education")) }
        val s = snap(school, maths, step, loose)
        val lines = DelaKrug.tree(listOf(loose, step, maths, school), s, today)
        assertEquals(listOf("school" to 0, "maths" to 1, "step" to 2, "loose" to 0), lines.map { it.task.id to it.depth })
    }

    @Test
    fun `строка — направления и «зачем», без направления — вопросом`() {
        val goal = task("goal") { it.copy(title = "Уверенно считает до 20", directions = listOf("education")) }
        val step = task("step") { it.copy(whyId = "goal") }
        val bare = task("bare")
        val s = snap(goal, step, bare)
        val chips = DelaViews.chips(step, null, s, today)
        assertTrue(chips.toString(), "Образование" in chips && "зачем: Уверенно считает до 20" in chips)
        assertTrue(DelaViews.NO_DIRECTION in DelaViews.chips(bare, null, s, today))
        assertTrue("цель · шагов 1" in DelaViews.chips(goal, null, s, today))
        // Старый сервер без Круга — подписей нет.
        assertFalse(DelaViews.chips(step, null, s.copy(features = emptySet()), today).any { it.startsWith("зачем") })
    }

    @Test
    fun `поля Круга — в синке, на диске и в операциях`() {
        val o = JSONObject().put("id", "t1").put("title", "Шаг").put("directions", org.json.JSONArray(listOf("work")))
            .put("why_id", "g1").put("why_num", 7).put("why_title", "Цель")
        val t = Dela.task(o)!!
        assertEquals(listOf("work"), t.directions)
        assertEquals("g1", t.whyId)
        assertEquals(t, Dela.task(Dela.json(t)))
        val resp = JSONObject().put("full", true).put("seq", 5).put("features", org.json.JSONArray(listOf("krug")))
            .put("tasks", org.json.JSONArray()).put("krug", org.json.JSONArray().put(JSONObject().put("id", "p-a").put("kind", "project")
                .put("directions", org.json.JSONArray(listOf("work")))))
        val s = Dela.merge(Dela.Snapshot(), resp, 1L)
        assertTrue(s.krugOn)
        assertEquals(mapOf("p-a" to listOf("work")), s.krug)
        assertEquals(s.krug, Dela.fromJson(Dela.toJson(s)).krug)
        // Правка направления — только оно в set; новое дело без направлений их не шлёт.
        val (set, _) = Dela.diff(t, t.copy(directions = listOf("work", "health")))
        assertEquals(setOf("directions"), set.keys().asSequence().toSet())
        assertFalse(Dela.createOp(Dela.Task(id = "n", title = "Новое")).getJSONObject("task").has("directions"))
        assertEquals("g1", Dela.createOp(Dela.Task(id = "n", title = "Шаг", whyId = "g1")).getJSONObject("task").getString("why_id"))
    }
}
