package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три группы, «Сейчас», «Сегодня» и просроченное врозь, уровни и «только одно» — как веб (`app.js`:
 * `grpOf`, `isNow`, `inToday`, `isLate`, `whenDay`, `byTime`, `toggleNow`, `levelKey`,
 * `nested`, `branches`) и сервер (`store.group_of`, `NOW_IF`, `TODAY_IF`, `LATE_IF`). Данные выдуманные.
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

    /**
     * Те же дела, что в `test_now_today_and_overdue_apart` сервера (13.10.2026, владелец: «В делах давай
     * разделим: Сейчас (то, что отметил) и Сегодня (с датой сегодня)»; просроченное само ни туда, ни туда —
     * оно во «Всех делах», сверху). Чужое, закрытое и цель — нигде.
     */
    @Test
    fun `сейчас, сегодня и просроченное врозь — как сервер`() {
        val yday = "2026-10-09"
        val s = snap(
            task("Взял сам") { it.copy(focusOn = today) },
            task("Взял и срок сегодня") { it.copy(focusOn = today, dueDate = today, createdAt = "2026-10-01T10:01:00+03:00") },
            task("Срок сегодня") { it.copy(dueDate = today, dueTime = "15:00", createdAt = "2026-10-01T10:02:00+03:00") },
            task("Сдать отчёт за сентябрь") { it.copy(dueDate = yday) },
            task("Вчера брал в Сейчас") { it.copy(focusOn = yday) },
            task("Ждут ответа без срока") { it.copy(owed = true) },
            task("Пора напомнить") { it.copy(ball = Dela.WAITING, nudgeOn = today) },
            task("На завтра") { it.copy(dueDate = tomorrow) },
            task("Сделал сегодняшнее") { it.copy(dueDate = today, status = Dela.DONE, completedAt = "2026-10-10T12:00:00+03:00") },
            task("Наташино на сегодня") { it.copy(dueDate = today, focusOn = today, ownerId = "natasha") },
            task("Цель на сегодня") { it.copy(dueDate = today, focusOn = today, goal = true) },
        )
        val ids = { l: List<Dela.Task> -> l.map { it.id } }
        // «Сейчас» — только отмеченное владельцем; срок ни при чём.
        assertEquals(setOf("Взял сам", "Взял и срок сегодня"), ids(DelaGroups.nowOrdered(s, "me", today)).toSet())
        assertEquals(2, DelaViews.nowTasks(s, "me", today).size)
        // «Сегодня» — срок ровно сегодня: со временем — выше; «Сейчас» со сроком сегодня тоже здесь.
        assertEquals(listOf("Срок сегодня", "Взял и срок сегодня"), ids(DelaGroups.todayByGroups(s, "me", today, "all")))
        // На главной «Сегодня» — без уже взятого в «Сейчас».
        val taken = DelaGroups.nowOrdered(s, "me", today).map { it.id }.toSet()
        assertEquals(listOf("Срок сегодня"), ids(DelaGroups.todayByGroups(s, "me", today, "all", taken)))
        // Просроченное — только во «Всех делах», сверху.
        assertEquals(listOf("Сдать отчёт за сентябрь"), ids(DelaGroups.lateTasks(s, "me", today, "all")))
        for (id in listOf("Сдать отчёт за сентябрь", "Вчера брал в Сейчас", "Ждут ответа без срока", "Пора напомнить", "На завтра")) {
            val t = s.tasks.getValue(id)
            assertFalse(id, DelaGroups.inToday(t, "me", today))
            assertFalse(id, DelaGroups.isNow(t, today) && t.open)
        }
        assertEquals(1, DelaGroups.doneToday(s, "me", today))
        // Числа разделов: «Сейчас» 2, «Сегодня» 2, у «Всех дел» — только просроченное.
        val c = DelaViews.counts(s, "me", today, "all", 0L)
        assertEquals(listOf(2, 2, 1), listOf(c.now, c.today, c.late))
        assertEquals("1", c.label(DelaViews.View.ALL))
        assertTrue(c.bad(DelaViews.View.ALL))
        // Дела дня (Засечка, разворот): сначала «Сейчас», потом «Сегодня» без повторов, просроченного нет.
        assertEquals(listOf("Взял сам", "Взял и срок сегодня", "Срок сегодня"), ids(DelaGroups.dayOrdered(s, "me", today, "all")))
        assertEquals(DelaGroups.dayOrdered(s, "me", today, "all"), DelaGroups.todayOrdered(s, "me", today, "all"))
        // У Наташи — своё «Сейчас» и «Сегодня», Сашиного она там не видит.
        assertEquals(listOf("Наташино на сегодня"), ids(DelaGroups.nowOrdered(s, "natasha", today)))
        assertEquals(listOf("Наташино на сегодня"), ids(DelaGroups.todayByGroups(s, "natasha", today, "all")))
    }

    @Test
    fun `когда делать — Сейчас сегодня, отбить без срока без срока, мониторить к раннему из срока и напоминания`() {
        // С 13.10.2026 «отбить» без срока само больше не «сегодня».
        assertNull(DelaGroups.whenDay(task("a") { it.copy(owed = true) }, today))
        assertEquals(today, DelaGroups.whenDay(task("b") { it.copy(focusOn = today, dueDate = "2026-10-20") }, today))
        assertEquals("2026-10-12", DelaGroups.whenDay(task("c") { it.copy(ball = Dela.WAITING, dueDate = "2026-10-15", nudgeOn = "2026-10-12") }, today))
        assertNull(DelaGroups.whenDay(task("d"), today))
        assertNull(DelaGroups.whenDay(task("e") { it.copy(dueDate = today, status = Dela.DONE) }, today))
    }

    @Test
    fun `порядок в группе — когда делать, час, кто дольше ждёт`() {
        val s = snap(
            task("fresh") { it.copy(owed = true, dueDate = today, createdAt = "2026-10-09T10:00:00+03:00") },
            task("old") { it.copy(owed = true, dueDate = today, createdAt = "2026-10-02T10:00:00+03:00") },
            task("late") { it.copy(owed = true, dueDate = "2026-10-07", createdAt = "2026-10-09T12:00:00+03:00") },
            task("at9") { it.copy(owed = true, dueDate = today, dueTime = "09:00", createdAt = "2026-10-09T12:00:00+03:00") },
        )
        val tree = DelaGroups.nested(DelaGroups.todayTasks(s, "me", today, "all"), listOf(DelaGroups.Level.GRP), s, today)
        assertEquals(listOf("Отбить"), tree.map { it.title })
        // Просроченного в «Сегодня» нет; со временем — выше, потом кто дольше ждёт.
        assertEquals(listOf("at9", "old", "fresh"), tree.single().items.map { it.id })
    }

    @Test
    fun `группы по приоритету, пустая не показывается`() {
        val s = snap(
            task("c") { it.copy(ball = Dela.WAITING, dueDate = today) },
            task("o") { it.copy(owed = true, dueDate = today) },
            task("o2") { it.copy(owed = true) },
        )
        val tree = DelaGroups.nested(DelaGroups.todayTasks(s, "me", today, "all"), listOf(DelaGroups.Level.GRP), s, today)
        assertEquals(listOf("Отбить", "Мониторить"), tree.map { it.title })
        assertEquals(listOf("o", "c"), DelaGroups.todayByGroups(s, "me", today, "all").map { it.id })
    }

    /**
     * 14.10.2026, владелец: «Если нажимаю на „сейчас“, то дело пропадает и не ставится в сейчас… Надо оставить
     * только сейчас». Солнце у строки (`toggleToday`) он принимал за «Сейчас», а оно у дела со сроком сегодня
     * ставило срок на завтра — дело уезжало. Теперь у строки и в карточке одна молния, и ни взятие, ни снятие не
     * трогают ни срок, ни время, ни напоминание: в операции — только `focus_on`. Сроки — какие угодно.
     */
    @Test
    fun `молния не трогает срок — ни взять, ни снять`() {
        val yday = "2026-10-09"
        val cases = listOf(
            task("без срока"),
            task("срок сегодня") { it.copy(dueDate = today, dueTime = "15:00") },
            task("просрочено") { it.copy(dueDate = yday) },
            task("на завтра") { it.copy(dueDate = tomorrow) },
            task("мониторить с напоминанием") { it.copy(ball = Dela.WAITING, dueDate = today, nudgeOn = today) },
            task("отбить") { it.copy(owed = true, dueDate = today) },
        )
        for (t in cases) {
            val s = snap(t)
            val (on, said) = DelaGroups.toggleNow(t, s, "me", today)
            assertEquals(t.id, "В «Сейчас»", said)
            assertEquals(t.id, listOf("focus_on"), on!!.keys().asSequence().toList())
            assertEquals(t.id, today, on.getString("focus_on"))
            // Легло в копию — срок, время и напоминание те же, дело в «Сейчас».
            val taken = Dela.applyFields(t, on, today, "")
            assertEquals(t.id, Triple(t.dueDate, t.dueTime, t.nudgeOn), Triple(taken.dueDate, taken.dueTime, taken.nudgeOn))
            assertTrue(t.id, DelaGroups.isNow(taken, today))
            // Повторное нажатие — «Из «Сейчас»»: снова только `focus_on`, срок на месте.
            val (off, said2) = DelaGroups.toggleNow(taken, snap(taken), "me", today)
            assertEquals(t.id, "Убрал из «Сейчас»", said2)
            assertEquals(t.id, listOf("focus_on"), off!!.keys().asSequence().toList())
            val back = Dela.applyFields(taken, off, today, "")
            assertEquals(t.id, Triple(t.dueDate, t.dueTime, t.nudgeOn), Triple(back.dueDate, back.dueTime, back.nudgeOn))
            assertFalse(t.id, DelaGroups.isNow(back, today))
            // «Вернуть» — та же операция наоборот, тоже без срока.
            assertEquals(t.id, listOf("focus_on"), DelaGroups.was(t, on).keys().asSequence().toList())
        }
    }

    @Test
    fun `молния есть только там, где её можно нажать — своё открытое, не цель`() {
        assertTrue(DelaGroups.canNow(task("a"), "me"))
        assertTrue(DelaGroups.canNow(task("b") { it.copy(focusOn = today) }, "me"))
        // Кто смотрит — не знаем (страница без входа): всё своё.
        assertTrue(DelaGroups.canNow(task("c") { it.copy(ownerId = "lena") }, ""))
        assertFalse(DelaGroups.canNow(task("d") { it.copy(ownerId = "lena") }, "me"))
        assertFalse(DelaGroups.canNow(task("e") { it.copy(goal = true) }, "me"))
        assertFalse(DelaGroups.canNow(task("f") { it.copy(status = Dela.DONE) }, "me"))
    }

    @Test
    fun `молния — своё открытое в Сейчас, шестое тоже встаёт, снять — срок не трогать`() {
        // Записка отладки 8 (18.10.2026): предела «Сейчас» нет — шестое встаёт, как первое.
        val five = (1..5).map { i -> task("n$i") { it.copy(focusOn = today) } }
        val s = snap(*five.toTypedArray(), task("sixth"), task("foreign") { it.copy(ownerId = "lena") }, task("goal") { it.copy(goal = true) })
        val (sixth, _) = DelaGroups.toggleNow(s.tasks.getValue("sixth"), s, "me", today)
        assertEquals(today, sixth!!.optString("focus_on"))
        assertNull(DelaGroups.toggleNow(s.tasks.getValue("foreign"), s, "me", today).first)
        assertNull(DelaGroups.toggleNow(s.tasks.getValue("goal"), s, "me", today).first)
        val (off, said) = DelaGroups.toggleNow(s.tasks.getValue("n1") , s, "me", today)
        assertEquals("{\"focus_on\":null}", off.toString())
        assertEquals("Убрал из «Сейчас»", said)
        // Место есть — встаёт на сегодня.
        val roomy = snap(task("x"))
        assertEquals("{\"focus_on\":\"$today\"}", DelaGroups.toggleNow(roomy.tasks.getValue("x"), roomy, "me", today).first.toString())
    }

    @Test
    fun `В повестку — мяч agenda в той же группе, ещё раз — своё`() {
        // Правило 32 карты Дел, контракт dela.json «Повестка»: переключатель карточки — task.set {ball: agenda}.
        val owed = task("a") { it.copy(owed = true, personId = "p-1") }
        val on = DelaGroups.toggleAgenda(owed)
        assertEquals(Dela.AGENDA, on.ball)
        assertFalse(on.owed)
        assertEquals(DelaGroups.Grp.SELF, DelaGroups.grpOf(on))
        assertEquals(Dela.MINE, DelaGroups.toggleAgenda(on).ball)
        // Группа и человек одной строкой — как grpWho веба.
        assertEquals("повестка · Иван", DelaGroups.withWho("повестка", Dela.AGENDA, "Иван"))
        assertEquals("мониторить Иван", DelaGroups.withWho("мониторить", Dela.WAITING, "Иван"))
        assertEquals("повестка", DelaGroups.withWho("повестка", Dela.AGENDA, ""))
    }

    @Test
    fun `вторая строка — кто ждёт и сколько`() {
        val owed = task("a") { it.copy(owed = true, createdAt = "2026-10-07T09:00:00+03:00") }
        assertEquals(DelaGroups.Wait("Олег ждёт 3 дн", true), DelaGroups.wait(owed, "Олег", today, false))
        assertEquals("ждут 3 дн", DelaGroups.wait(owed, "", today, false)!!.text)
        val check = task("b") { it.copy(ball = Dela.WAITING, waitingSince = "2026-10-05") }
        assertEquals("жду Олег 5 дн", DelaGroups.wait(check, "Олег", today, false)!!.text)
        // Повестка — словом Саши (16.10.2026, запись отладки 19): «повестка · Олег»; список уже по людям — одно слово.
        assertEquals("повестка · Олег", DelaGroups.wait(task("c") { it.copy(ball = Dela.AGENDA) }, "Олег", today, false)!!.text)
        assertEquals("повестка", DelaGroups.wait(task("c") { it.copy(ball = Dela.AGENDA) }, "Олег", today, true)!!.text)
        assertEquals("@Олег", DelaGroups.wait(task("d"), "Олег", today, false)!!.text)
        assertNull(DelaGroups.wait(task("e"), "Олег", today, true))
    }

    @Test
    fun `сейчас — в порядке, как тянул Саша, остальное по времени, перетаскивание ставит на место`() {
        // Записка отладки 8 (18.10.2026): порядок «Сейчас» — settings.now_order, веб и телефон одним полем.
        val a = task("a") { it.copy(focusOn = today, dueTime = "09:00") }
        val b = task("b") { it.copy(focusOn = today, dueTime = "10:00") }
        val c = task("c") { it.copy(focusOn = today, dueTime = "11:00") }
        val s = snap(a, b, c)
        assertEquals(listOf("a", "b", "c"), DelaGroups.nowOrdered(s, "me", today).map { it.id })
        assertEquals(listOf("c", "a", "b"), DelaGroups.nowOrdered(s, "me", today, listOf("c")).map { it.id })
        assertEquals(listOf("b", "c", "a"), DelaGroups.nowOrdered(s, "me", today, listOf("b", "gone", "c", "a")).map { it.id })
        assertEquals(listOf("c", "a", "b"), DelaGroups.moveIn(listOf("a", "b", "c"), "c", 0))
        assertEquals(listOf("b", "c", "a"), DelaGroups.moveIn(listOf("a", "b", "c"), "a", 2))
        val op = DelaGroups.nowOrderOp(listOf("c", "a"), "op1")
        assertEquals("user.settings", op.getString("op"))
        assertEquals("[\"c\",\"a\"]", op.getJSONObject("settings").getJSONArray("now_order").toString())
    }

    @Test
    fun `уровни — проекты, группы, время, ветки людей по id`() {
        val s = snap(
            task("a1") { it.copy(projectId = "p-a", owed = true, personId = "h-1", dueDate = today) },
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
        // Две «Наташи» — две ветки, и в заголовках полные имена; порядок — по фамилии (записка отладки 36).
        val people = DelaGroups.nested(all, listOf(DelaGroups.Level.PERSON), s, today)
        assertEquals(listOf("Наталья Грачёва", "Наталья Синицына", "Без человека"), people.map { it.title })
        assertEquals(listOf("h-2", "h-1", null), people.map { it.personId })
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
