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
 * их числа, группировки, подпись строки, «Утро», поиск, человек. Владелец
 * открывает Дела то на ПК, то на телефоне — «Просрочено» и «жду Иван · 3 дн.»
 * должны значить одно и то же в обоих местах.
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
    fun `по проектам — Входящие первыми, по людям — без человека последним`() {
        val s = snap(task("inbox"), task("solo"))
        val all = s.tasks.values.toList()
        val byProject = DelaViews.group(all, DelaViews.By.PROJECT, s, today)
        assertEquals(listOf("Входящие", "Бета Групп"), byProject.map { it.title })
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
        assertEquals(listOf("сб 10.10", "Бета Групп", "жду Иван · 3 дн.", "10 мин", "звонок"), DelaViews.chips(t, null, s, today))
        // Группа по датам — срок молчит (он в заголовке).
        assertEquals(listOf("Бета Групп", "жду Иван · 3 дн.", "10 мин", "звонок"), DelaViews.chips(t, DelaViews.By.DATE, s, today))
        // Группа по мячу: мяч в заголовке, а человек остаётся — «@Иван» (как в вебе).
        assertEquals(listOf("сб 10.10", "Бета Групп", "@Иван", "10 мин", "звонок"), DelaViews.chips(t, DelaViews.By.BALL, s, today))
        assertEquals(listOf("сб 10.10", "жду Иван · 3 дн.", "10 мин", "звонок"), DelaViews.chips(t, null, s, today, onProjectPage = true))
        // Моё с человеком — «@Иван»; без проекта — «Входящие».
        val mine = t.copy(ball = Dela.MINE, projectId = "", labels = emptyList(), estimateMin = 0, dueDate = "")
        assertEquals(listOf("Входящие", "@Иван"), DelaViews.chips(mine, null, s, today))
        assertEquals(listOf("Входящие"), DelaViews.chips(mine, DelaViews.By.PERSON, s, today))
        // Просроченный срок виден и в группе по датам.
        assertEquals("01.10 · 4 дн. назад", DelaViews.chips(mine.copy(dueDate = "2026-10-01"), DelaViews.By.DATE, s, today).first())
    }

    @Test
    fun `утро — четыре раздела веба, числа в меню`() {
        val s = snap(
            task("now") { it.copy(focusOn = today, dueDate = "2026-10-01") },
            task("overdue") { it.copy(dueDate = "2026-10-03") },
            task("nudge") { it.copy(ball = Dela.WAITING, nudgeOn = today) },
            task("other") { it.copy(createdBy = "natasha", createdAt = "2026-10-04T10:00:00+03:00", dueDate = "2026-10-20") },
            task("soon") { it.copy(dueDate = "2026-10-09") },
            task("far") { it.copy(dueDate = "2026-12-01") },
        )
        val m = DelaViews.morning(s, "sasha", today, "all", now)
        assertEquals(listOf("Сейчас", "На сегодня и просроченное", "Пора напомнить", "Поставили другие"), m.map { it.title })
        assertEquals(listOf("now"), m[0].items.map { it.id })
        // «Сейчас» в «сегодня» не повторяется (как в вебе).
        assertEquals(listOf("overdue"), m[1].items.map { it.id })
        assertEquals(listOf("nudge"), m[2].items.map { it.id })
        assertEquals(listOf("other"), m[3].items.map { it.id })
        val c = DelaViews.counts(s, "sasha", today, "all")
        assertEquals(2, c.morning)  // моё со сроком сегодня и раньше или «Сейчас»
        assertEquals(2, c.upcoming)  // #57 (10.10) и soon (09.10): после сегодня и в неделю
        assertEquals(2, c.waiting)  // #57 и nudge
        assertEquals(6, c.inbox)  // всё новое — без проекта
        assertEquals(0, c.new)
        // «Предстоящее» — с датой; «И без даты» — все; «Только мяч у меня» — без «жду».
        assertEquals(6, DelaViews.upcoming(s, "sasha", "all", mineOnly = false, withUndated = false).size)
        assertEquals(5, DelaViews.upcoming(s, "sasha", "all", mineOnly = true, withUndated = true).size)
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
    fun `входящие и все дела — моё в сфере, сделанные по выбору`() {
        val s = snap(task("inbox"), task("done") { it.copy(status = Dela.DONE) }, task("foreign") { it.copy(ownerId = "natasha") })
        assertEquals(setOf("inbox"), DelaViews.list(s, "sasha", "all", inboxOnly = true, showDone = false).map { it.id }.toSet())
        assertEquals(setOf("inbox", "done"), DelaViews.list(s, "sasha", "all", inboxOnly = true, showDone = true).map { it.id }.toSet())
        assertEquals(2, DelaViews.list(s, "sasha", "all", inboxOnly = false, showDone = false).size)
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
