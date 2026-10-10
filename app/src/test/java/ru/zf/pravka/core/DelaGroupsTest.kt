package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три группы, «Сегодня», уровни и «только одно» — как веб (`app.js`: `grpOf`,
 * `inToday`, `whenDay`, `byTime`, `levelKey`, `nested`, `branches`) и сервер
 * (`store.group_of`, `TODAY_IF`). Данные выдуманные.
 */
class DelaGroupsTest {

    // Суббота, 10 октября 2026.
    private val today = "2026-10-10"
    private val tomorrow = "2026-10-11"

    private fun task(id: String, f: (Dela.Task) -> Dela.Task = { it }) =
        f(Dela.Task(id = id, title = id, ownerId = "me", createdBy = "me", createdAt = "2026-10-01T10:00:00+03:00"))

    private fun snap(vararg t: Dela.Task) = Dela.Snapshot(
        tasks = t.associateBy { it.id },
        projects = listOf(
            Dela.Project(id = "p-a", name = "Альфа", ownerId = "me"),
            Dela.Project(id = "p-b", name = "Бета", ownerId = "me"),
        ).associateBy { it.id },
        people = listOf(
            Dela.Person(id = "h-1", name = "Наталья Синицына", short = "Наташа", role = "финдиректор"),
            Dela.Person(id = "h-2", name = "Наталья Грачёва", short = "Наташа"),
            Dela.Person(id = "h-3", name = "Олег Ветров", short = "Олег"),
        ).associateBy { it.id },
    )

    @Test
    fun `группа — owed, иначе мяч у человека, иначе запустить`() {
        assertEquals(DelaGroups.Grp.OWED, DelaGroups.grpOf(task("a") { it.copy(owed = true) }))
        assertEquals(DelaGroups.Grp.CHECK, DelaGroups.grpOf(task("b") { it.copy(ball = Dela.WAITING) }))
        assertEquals(DelaGroups.Grp.SELF, DelaGroups.grpOf(task("c")))
        // Повестка — тоже «Запустить».
        assertEquals(DelaGroups.Grp.SELF, DelaGroups.grpOf(task("d") { it.copy(ball = Dela.AGENDA) }))
        assertEquals(listOf("Отбить", "Запустить", "Мониторить"), DelaGroups.Grp.entries.map { it.title })
    }

    @Test
    fun `мяч owed с сервера и из автоматики — mine и флаг`() {
        val t = Dela.task(JSONObject().put("id", "x").put("title", "x").put("ball", "owed"))!!
        assertEquals(Dela.MINE, t.ball)
        assertTrue(t.owed)
        val set = Dela.applyFields(task("y"), JSONObject().put("ball", "owed"), today, "")
        assertTrue(set.owed)
        assertEquals(Dela.MINE, set.ball)
        // Мяч ушёл к человеку — «отбить» гаснет, как у триггера сервера.
        val gone = Dela.applyFields(set, JSONObject().put("ball", Dela.WAITING), today, "")
        assertFalse(gone.owed)
        // Старый сервер поля не знает — дело своё, «Запустить».
        assertFalse(Dela.task(JSONObject().put("id", "z").put("title", "z"))!!.owed)
    }

    @Test
    fun `смена группы — как grpSet веба, повестка остаётся повесткой`() {
        val agenda = task("a") { it.copy(ball = Dela.AGENDA, owed = true) }
        assertEquals("{\"ball\":\"agenda\",\"owed\":false}", DelaGroups.grpSet(agenda, DelaGroups.Grp.SELF).toString())
        assertEquals("{\"ball\":\"mine\",\"owed\":true}", DelaGroups.grpSet(agenda, DelaGroups.Grp.OWED).toString())
        assertEquals("{\"ball\":\"waiting\",\"owed\":false}", DelaGroups.grpSet(agenda, DelaGroups.Grp.CHECK).toString())
    }

    @Test
    fun `owed уходит в создание, только когда он есть`() {
        assertFalse(Dela.createOp(task("a")).getJSONObject("task").has("owed"))
        assertTrue(Dela.createOp(task("b") { it.copy(owed = true) }).getJSONObject("task").getBoolean("owed"))
    }

    @Test
    fun `на сегодня — срок, Сейчас, отбить без срока, мониторить с напоминанием`() {
        val s = snap(
            task("due") { it.copy(dueDate = today) },
            task("late") { it.copy(dueDate = "2026-10-08") },
            task("now") { it.copy(focusOn = today) },
            task("owed") { it.copy(owed = true) },
            task("owedLater") { it.copy(owed = true, dueDate = "2026-10-14") },
            task("nudge") { it.copy(ball = Dela.WAITING, nudgeOn = today) },
            task("nudgeLater") { it.copy(ball = Dela.WAITING, nudgeOn = "2026-10-12") },
            task("self"),
            task("done") { it.copy(dueDate = today, status = Dela.DONE) },
            task("other") { it.copy(dueDate = today, ownerId = "lena") },
        )
        assertEquals(setOf("due", "late", "now", "owed", "nudge"), DelaGroups.todayTasks(s, "me", today, "all").map { it.id }.toSet())
    }

    @Test
    fun `когда делать — отбить без срока сегодня, мониторить к раннему из срока и напоминания`() {
        assertEquals(today, DelaGroups.whenDay(task("a") { it.copy(owed = true) }, today))
        assertEquals(today, DelaGroups.whenDay(task("b") { it.copy(focusOn = today, dueDate = "2026-10-20") }, today))
        assertEquals("2026-10-12", DelaGroups.whenDay(task("c") { it.copy(ball = Dela.WAITING, dueDate = "2026-10-15", nudgeOn = "2026-10-12") }, today))
        assertNull(DelaGroups.whenDay(task("d"), today))
        assertNull(DelaGroups.whenDay(task("e") { it.copy(dueDate = today, status = Dela.DONE) }, today))
    }

    @Test
    fun `порядок в группе — когда делать, час, кто дольше ждёт`() {
        val s = snap(
            task("fresh") { it.copy(owed = true, createdAt = "2026-10-09T10:00:00+03:00") },
            task("old") { it.copy(owed = true, createdAt = "2026-10-02T10:00:00+03:00") },
            task("late") { it.copy(owed = true, dueDate = "2026-10-07", createdAt = "2026-10-09T12:00:00+03:00") },
            task("at9") { it.copy(owed = true, dueDate = today, dueTime = "09:00", createdAt = "2026-10-09T12:00:00+03:00") },
        )
        val tree = DelaGroups.nested(DelaGroups.todayTasks(s, "me", today, "all"), listOf(DelaGroups.Level.GRP), s, today)
        assertEquals(listOf("Отбить"), tree.map { it.title })
        assertEquals(listOf("late", "at9", "old", "fresh"), tree.single().items.map { it.id })
    }

    @Test
    fun `группы по приоритету, пустая не показывается`() {
        val s = snap(task("c") { it.copy(ball = Dela.WAITING, nudgeOn = today) }, task("o") { it.copy(owed = true) })
        val tree = DelaGroups.nested(DelaGroups.todayTasks(s, "me", today, "all"), listOf(DelaGroups.Level.GRP), s, today)
        assertEquals(listOf("Отбить", "Мониторить"), tree.map { it.title })
    }

    @Test
    fun `солнце — на сегодня, на завтра снимает Сейчас и двигает напоминание`() {
        val (on, w1) = DelaGroups.toggleToday(task("a"), "me", today, tomorrow)
        assertEquals("На сегодня", w1)
        assertEquals("{\"due_date\":\"$today\"}", on.toString())
        val (off, w2) = DelaGroups.toggleToday(task("b") { it.copy(focusOn = today) }, "me", today, tomorrow)
        assertEquals("На завтра", w2)
        assertEquals(JSONObject.NULL, off.get("focus_on"))
        // Только «Сейчас» — оно и снимается: срока у дела не было, его и не появится (как в вебе).
        assertFalse(off.has("due_date"))
        val (nudge, _) = DelaGroups.toggleToday(task("c") { it.copy(ball = Dela.WAITING, nudgeOn = today) }, "me", today, tomorrow)
        assertEquals("{\"nudge_on\":\"$tomorrow\"}", nudge.toString())
        val (owed, _) = DelaGroups.toggleToday(task("d") { it.copy(owed = true) }, "me", today, tomorrow)
        assertEquals("{\"due_date\":\"$tomorrow\"}", owed.toString())
    }

    @Test
    fun `вторая строка — кто ждёт и сколько`() {
        val owed = task("a") { it.copy(owed = true, createdAt = "2026-10-07T09:00:00+03:00") }
        assertEquals(DelaGroups.Wait("Олег ждёт 3 дн", true), DelaGroups.wait(owed, "Олег", today, false))
        assertEquals("ждут 3 дн", DelaGroups.wait(owed, "", today, false)!!.text)
        val check = task("b") { it.copy(ball = Dela.WAITING, waitingSince = "2026-10-05") }
        assertEquals("жду Олег 5 дн", DelaGroups.wait(check, "Олег", today, false)!!.text)
        assertEquals("при встрече с Олег", DelaGroups.wait(task("c") { it.copy(ball = Dela.AGENDA) }, "Олег", today, false)!!.text)
        assertEquals("@Олег", DelaGroups.wait(task("d"), "Олег", today, false)!!.text)
        assertNull(DelaGroups.wait(task("e"), "Олег", today, true))
    }

    @Test
    fun `уровни — проекты, группы, время, ветки людей по id`() {
        val s = snap(
            task("a1") { it.copy(projectId = "p-a", owed = true, personId = "h-1") },
            task("a2") { it.copy(projectId = "p-a", ball = Dela.WAITING, personId = "h-2", dueDate = "2026-10-13") },
            task("b1") { it.copy(projectId = "p-b", dueDate = "2026-10-08") },
            task("x"),
        )
        val all = s.tasks.values.toList()
        val tree = DelaGroups.nested(all, listOf(DelaGroups.Level.PROJECT, DelaGroups.Level.GRP, DelaGroups.Level.DATE), s, today)
        assertEquals(listOf("Альфа", "Бета", "Без проекта"), tree.map { it.title })
        assertEquals(listOf("Отбить", "Мониторить"), tree[0].children.map { it.title })
        assertEquals(listOf("Сегодня"), tree[0].children[0].children.map { it.title })
        assertEquals(listOf("Вторник, 13 октября"), tree[0].children[1].children.map { it.title })
        assertEquals(listOf("Просрочено"), tree[1].children.single().children.map { it.title })
        assertEquals(listOf("Без срока"), tree[2].children.single().children.map { it.title })
        assertEquals(listOf("a1", "a2", "b1", "x"), DelaGroups.flat(tree).map { it.id })
        // Две «Наташи» — две ветки, и в заголовках полные имена.
        val people = DelaGroups.nested(all, listOf(DelaGroups.Level.PERSON), s, today)
        assertEquals(listOf("Наталья Синицына", "Наталья Грачёва", "Без человека"), people.map { it.title })
        assertEquals(listOf("h-1", "h-2", null), people.map { it.personId })
    }

    @Test
    fun `уровень убирается со всем, что после него, повтор не встаёт`() {
        val lv = listOf(DelaGroups.Level.PROJECT, DelaGroups.Level.GRP, DelaGroups.Level.DATE)
        assertEquals(listOf(DelaGroups.Level.PROJECT), DelaGroups.setLevel(lv, 1, null))
        assertEquals(listOf(DelaGroups.Level.GRP, DelaGroups.Level.DATE), DelaGroups.setLevel(lv, 0, DelaGroups.Level.GRP))
        assertEquals(listOf(DelaGroups.Level.PROJECT, DelaGroups.Level.GRP, DelaGroups.Level.PERSON), DelaGroups.setLevel(lv, 2, DelaGroups.Level.PERSON))
        assertEquals(lv, DelaGroups.parseLevels(DelaGroups.levelsKey(lv)))
        assertEquals(emptyList<DelaGroups.Level>(), DelaGroups.parseLevels(DelaGroups.levelsKey(emptyList())))
        assertEquals("проекты › группы › время", DelaGroups.levelsName(lv))
    }

    @Test
    fun `фильтры — заводские без поля, свои из settings`() {
        assertEquals(listOf("Сегодня по проектам", "Отбить — по людям", "Мониторить — по людям"), DelaGroups.filters(null).map { it.name })
        val mine = DelaGroups.Filter("f-1", "Альфа сегодня", DelaGroups.Scope.TODAY, listOf(DelaGroups.Level.PROJECT),
            DelaGroups.Pick(DelaGroups.Level.PROJECT, "1Альфа\u0001p-a", "Альфа", "p-a"))
        val back = DelaGroups.filters(DelaGroups.json(listOf(mine)))
        assertEquals(listOf(mine), back)
        // Пустой список — свой выбор «без фильтров», а не заводские.
        assertEquals(emptyList<DelaGroups.Filter>(), DelaGroups.filters(JSONArray()))
        val op = DelaGroups.saveOp(listOf(mine))
        assertEquals("user.settings", op.getString("op"))
        assertEquals("f-1", op.getJSONObject("settings").getJSONArray("filters").getJSONObject(0).getString("id"))
        assertEquals("Сегодня: проекты › группы", DelaGroups.suggestName(DelaGroups.Scope.TODAY, listOf(DelaGroups.Level.PROJECT, DelaGroups.Level.GRP), null))
    }

    @Test
    fun `фильтр одной группы — только её открытые`() {
        val s = snap(task("o") { it.copy(owed = true) }, task("c") { it.copy(ball = Dela.WAITING) }, task("s"))
        assertEquals(listOf("o"), DelaGroups.scopeItems(s, "me", today, "all", DelaGroups.Scope.OWED).map { it.id })
        assertEquals(3, DelaGroups.scopeItems(s, "me", today, "all", DelaGroups.Scope.ALL).size)
    }

    @Test
    fun `только одно — ветки с поиском, выбор по id`() {
        val s = snap(
            task("n1") { it.copy(personId = "h-1") },
            task("n1b") { it.copy(personId = "h-1") },
            task("n2") { it.copy(personId = "h-2") },
            task("o") { it.copy(personId = "h-3") },
        )
        val items = s.tasks.values.toList()
        val br = DelaGroups.branches(items, DelaGroups.Level.PERSON, s, today)
        // Больше дел — выше; у одинаковых коротких — полные имена.
        assertEquals(listOf("Наталья Синицына", "Наталья Грачёва", "Олег"), br.map { it.title })
        assertEquals(listOf(2, 1, 1), br.map { it.n })
        assertEquals(listOf("Наталья Синицына", "Наталья Грачёва"), DelaGroups.findBranches(br, "наташ").map { it.title })
        assertEquals(listOf("Наталья Синицына"), DelaGroups.findBranches(br, "финдир").map { it.title })
        val pick = DelaGroups.pickOf(br[1], DelaGroups.Level.PERSON)
        val lv = listOf(DelaGroups.Level.PERSON, DelaGroups.Level.GRP)
        assertEquals(listOf("n2"), DelaGroups.picked(items, lv, pick, s, today).map { it.id })
        // Первый уровень сменился — выбор молча не действует.
        assertEquals(4, DelaGroups.picked(items, listOf(DelaGroups.Level.GRP), pick, s, today).size)
        // Человека переименовали — выбор держится за id.
        val renamed = s.copy(people = s.people + ("h-2" to s.people.getValue("h-2").copy(short = "Ната")))
        assertEquals(listOf("n2"), DelaGroups.picked(items, lv, pick, renamed, today).map { it.id })
        assertEquals(pick, DelaGroups.pick(DelaGroups.pickJson(pick)))
    }
}
