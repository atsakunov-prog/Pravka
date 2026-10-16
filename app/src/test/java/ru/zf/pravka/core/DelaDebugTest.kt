package ru.zf.pravka.core

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режим отладки (core/DelaDebug.kt, контракт server/contract/dela-debug.json): снимок экрана данными — тот же состав,
 * что у веба; действия — операции очереди и строки журнала по времени; операции и ответы сервера. Данные выдуманные.
 */
class DelaDebugTest {

    private val today = "2026-10-16"

    @After
    fun clear() = DelaDebug.clearOps()

    private fun snap() = Dela.Snapshot(
        tasks = listOf(
            Dela.Task(id = "t-1", num = 57, title = "Иван Тестов: прислать модель", ownerId = "me", projectId = "p-r",
                personId = "h-1", ball = Dela.WAITING, dueDate = today, focusOn = today),
            Dela.Task(id = "t-2", num = 58, title = "Купить подарок", ownerId = "me", status = Dela.DONE),
        ).associateBy { it.id },
        projects = listOf(Dela.Project(id = "p-r", name = "Ромашка", ownerId = "me", kind = "client")).associateBy { it.id },
        people = listOf(Dela.Person(id = "h-1", name = "Иван Тестов", short = "Иван")).associateBy { it.id },
        suggestions = listOf(Dela.Suggestion(id = "s-1", kind = "create", source = "meeting", payload = "{\"title\":\"Позвонить Ромашке\"}"))
            .associateBy { it.id },
        seq = 42,
        features = setOf(DelaDebug.FEATURE),
    )

    @Test
    fun `снимок — экран, режим, открытая карточка, строки по порядку и что было в окне`() {
        val sc = DelaDebug.Screen(
            route = "nav:today", title = "Сегодня", sub = "четверг, 16 октября",
            mode = mapOf("sphere" to "work", "show_done" to false, "levels" to "группы", "pick" to null),
            rows = listOf(DelaDebug.Row("t-1", true, "Мониторить"), DelaDebug.Row("t-2", false), DelaDebug.Row("t-1", false)),
            openTaskId = "t-1",
            newIds = listOf("s-1"),
            talk = listOf(DelaDebug.Said("перенеси Ивана на пятницу", "Перенёс.", "Иван Тестов: прислать модель: срок 17.10")),
            claudeText = "ещё",
        )
        val o = DelaDebug.snapshot(sc, snap(), today, "999 test", events = listOf("10-16 10:00:00.000  дела: синк"),
            queue = listOf(JSONObject().put("op", "task.done").put("id", "t-1"), JSONObject().put("op", "debug.add")))
        assertEquals(1, o.getInt("v"))
        assertEquals("nav:today", o.getString("route"))
        assertEquals("Сегодня", o.getString("title"))
        val mode = o.getJSONObject("mode")
        assertEquals("work", mode.getString("sphere"))
        assertFalse("ложь и пустое — не шум", mode.has("show_done") || mode.has("pick"))
        val card = o.getJSONObject("card")
        assertEquals("task", card.getString("kind"))
        assertEquals(57, card.getInt("num"))
        assertEquals("Ромашка", card.getJSONObject("fields").getString("project_name"))
        assertEquals("Иван", card.getJSONObject("fields").getString("person_name"))
        val rows = o.getJSONArray("rows")
        assertEquals("повтор строки — один раз", 2, rows.length())
        assertEquals(2, o.getInt("rows_total"))
        val r0 = rows.getJSONObject(0)
        assertEquals("Иван Тестов: прислать модель", r0.getString("title"))
        assertEquals("Мониторить", r0.getString("group"))
        assertEquals("Иван", r0.getString("person"))
        assertEquals("Ромашка", r0.getString("project"))
        assertTrue(r0.getBoolean("now"))
        assertTrue(r0.getBoolean("in_view"))
        assertEquals("done", rows.getJSONObject(1).getString("status"))
        assertFalse(rows.getJSONObject(1).getBoolean("in_view"))
        assertEquals("П1", o.getJSONArray("new").getJSONObject(0).getString("n"))
        assertEquals("Позвонить Ромашке", o.getJSONArray("new").getJSONObject(0).getString("title"))
        assertEquals("перенеси Ивана на пятницу", o.getJSONArray("talk").getJSONObject(0).getString("said"))
        assertEquals("ещё", o.getString("quick_text"))
        assertEquals(1, o.getJSONArray("events").length())
        assertEquals("записка в своей очереди не едет", 1, o.getJSONArray("queue").length())
        assertEquals(42, o.getLong("seq"))
    }

    @Test
    fun `снимок страницы проекта без открытого дела — карточка клиента, строк не больше ROWS`() {
        val rows = (1..DelaDebug.ROWS + 30).map { DelaDebug.Row("x-$it", false) }
        val o = DelaDebug.snapshot(DelaDebug.Screen("page:project:p-r", "Проект: Ромашка", rows = rows, pageKind = "project", pageId = "p-r"),
            snap(), today, "")
        assertEquals("client", o.getJSONObject("card").getString("kind"))
        assertEquals(DelaDebug.ROWS, o.getJSONArray("rows").length())
        assertEquals(DelaDebug.ROWS + 30, o.getInt("rows_total"))
        assertEquals("(дела нет в копии)", o.getJSONArray("rows").getJSONObject(0).getString("title"))
    }

    @Test
    fun `кольцо операций — без записок, не длиннее OPS, длинное коротко`() {
        DelaDebug.noteOps(listOf(JSONObject().put("op", "debug.add").put("note", JSONObject())), now = 1L)
        assertTrue(DelaDebug.recentOps().isEmpty())
        repeat(DelaDebug.OPS + 5) { i -> DelaDebug.noteOps(listOf(JSONObject().put("op", "task.set").put("id", "t-$i")), now = 1000L + i) }
        val ops = DelaDebug.recentOps()
        assertEquals(DelaDebug.OPS, ops.size)
        assertEquals("t-5", ops.first().second.getString("id"))
        DelaDebug.clearOps()
        DelaDebug.noteOps(listOf(JSONObject().put("op", "task.create").put("task", JSONObject().put("title", "x".repeat(500)))))
        assertTrue(DelaDebug.recentOps()[0].second.getJSONObject("task").getString("title").length <= 300)
    }

    @Test
    fun `действия — операции и журнал по времени, ISO`() {
        val now = OffsetDateTime.of(2026, 10, 16, 10, 20, 0, 0, ZoneOffset.ofHours(3))
        val opAt = now.minusMinutes(5).toInstant().toEpochMilli()
        val a = DelaDebug.actions(
            listOf(opAt to JSONObject().put("op", "task.create").put("task", JSONObject().put("title", "Позвонить Ивану"))),
            listOf("10-16 10:10:00.000  дела: Claude ответил — edit, поправлено 1", "мусор без времени", "10-16 10:18:30.500  дела: отправлено 1"),
            now,
        )
        assertEquals(3, a.length())
        assertEquals("event", a.getJSONObject(0).getString("kind"))
        assertEquals("дела: Claude ответил — edit, поправлено 1", a.getJSONObject(0).getString("text"))
        assertEquals("op", a.getJSONObject(1).getString("kind"))
        assertEquals("task.create «Позвонить Ивану»", a.getJSONObject(1).getString("text"))
        assertTrue(a.getJSONObject(2).getString("at").startsWith("2026-10-16T10:18:30"))
        // Журнал с датой «из будущего» — прошлогодний (лог живёт неделями, год в нём не пишется).
        assertTrue(DelaDebug.logAt("12-31 23:00:00.000  x", now, ZoneOffset.ofHours(3))!!.startsWith("2025-12-31"))
        assertNull(DelaDebug.logAt("коротко", now))
    }

    /** Поезд 16.10.2026 (правило 27 сервера): родитель #12, внутри него #13 (место 2) и #14 (место 1), цель #20. */
    private fun nest() = Dela.Snapshot(
        tasks = listOf(
            Dela.Task(id = "p", num = 12, title = "Отправить термшит Ивану", ownerId = "me", dueDate = today),
            Dela.Task(id = "k1", num = 13, title = "Набросать драфт", ownerId = "me", whyId = "p", pos = 2),
            Dela.Task(id = "k2", num = 14, title = "Взять цифры из расшифровки", ownerId = "me", whyId = "p", pos = 1, dueDate = today),
            Dela.Task(id = "k3", num = 15, title = "Старое внутри", ownerId = "me", whyId = "p", status = Dela.DONE),
            Dela.Task(id = "g", num = 20, title = "Сделки закрываются", ownerId = "me", goal = true),
        ).associateBy { it.id },
        features = setOf(DelaDebug.FEATURE, "nest"),
    )

    @Test
    fun `вложенность в снимке — у строки родитель, место, отступ, у карточки «для» и «Внутри» по месту`() {
        val s = nest()
        val o = DelaDebug.snapshot(
            DelaDebug.Screen("nav:all", "Все дела", mode = mapOf("all_tree" to true),
                rows = listOf(DelaDebug.Row("p", true, "Ромашка", folded = false), DelaDebug.Row("k2", true, "Ромашка", depth = 1),
                    DelaDebug.Row("k1", false, "Ромашка", depth = 1)),
                openTaskId = "p"),
            s, today, "",
        )
        val rows = o.getJSONArray("rows")
        val parent = rows.getJSONObject(0)
        assertEquals("у родителя — сколько внутри открыто", 2, parent.getInt("inside"))
        assertFalse(parent.has("in") || parent.has("depth"))
        val k2 = rows.getJSONObject(1)
        assertEquals("#12 Отправить термшит Ивану", k2.getString("in"))
        assertEquals(1, k2.getInt("pos"))
        assertEquals(1, k2.getInt("depth"))
        assertTrue(o.getJSONObject("mode").getBoolean("all_tree"))
        val f = o.getJSONObject("card").getJSONObject("fields")
        assertEquals("1. #14 Взять цифры из расшифровки · срок $today · pos 1", f.getJSONArray("inside").getString(0))
        assertEquals("2. #13 Набросать драфт · pos 2", f.getJSONArray("inside").getString(1))
        assertEquals(1, f.getInt("inside_done"))
        val child = DelaDebug.snapshot(DelaDebug.Screen("nav:all", "Все дела", openTaskId = "k1"), s, today, "")
        assertEquals("#12 Отправить термшит Ивану", child.getJSONObject("card").getJSONObject("fields").getString("for"))
        // Свёрнутый родитель «Деревом» — помечен, дела внутри него в строках нет.
        val folded = DelaDebug.snapshot(DelaDebug.Screen("nav:all", "Все дела", rows = listOf(DelaDebug.Row("p", true, folded = true))), s, today, "")
        assertTrue(folded.getJSONArray("rows").getJSONObject(0).getBoolean("folded"))
        // Цель «Зачем» — не родитель: строки «внутри» у дела под целью нет.
        val underGoal = s.copy(tasks = s.tasks + ("x" to Dela.Task(id = "x", num = 30, title = "Шаг к цели", ownerId = "me", whyId = "g")))
        val r = DelaDebug.snapshot(DelaDebug.Screen("nav:all", "Все дела", rows = listOf(DelaDebug.Row("x", true))), underGoal, today, "")
        assertFalse(r.getJSONArray("rows").getJSONObject(0).has("in"))
    }

    /**
     * Поезд 16.10.2026 (правило 29 сервера, проверка вагона 4): «Все дела → Напоминания» и «Вернулось» — строки снимка.
     * У напоминания — «напоминание <миг или по месту>», у дела «потом» без срока — «потом · вернётся <день> («как сказано»)»;
     * дело со сроком — не «потом». Словами в «Отладке» — то же.
     */
    @Test
    fun `захват в снимке — напоминания и «потом» строками remind и back`() {
        val s = Dela.Snapshot(
            tasks = listOf(
                Dela.Task(id = "r1", num = 70, title = "Отправить счёт в Контуре", ownerId = "me", kind = Dela.KIND_REMIND,
                    remindAt = "2026-10-16T12:00:00+03:00"),
                Dela.Task(id = "r2", num = 71, title = "Позвонить маме", ownerId = "me", kind = Dela.KIND_REMIND, remindPlace = "Дом"),
                Dela.Task(id = "b1", num = 72, title = "Позвонить Ивану", ownerId = "me", backOn = today, backSaid = "на неделе"),
                Dela.Task(id = "b2", num = 73, title = "Купить магний", ownerId = "me", backOn = "2026-10-19"),
                Dela.Task(id = "d1", num = 74, title = "Сверка за сентябрь", ownerId = "me", backOn = today, dueDate = today),
            ).associateBy { it.id },
            features = setOf(DelaDebug.FEATURE, "capture"),
        )
        val o = DelaDebug.snapshot(
            DelaDebug.Screen("nav:all", "Все дела",
                rows = listOf(DelaDebug.Row("r1", true, "Напоминания"), DelaDebug.Row("r2", false, "Напоминания"),
                    DelaDebug.Row("b1", true, "Вернулось"), DelaDebug.Row("b2", true, "Запустить"), DelaDebug.Row("d1", true, "Запустить"))),
            s, today, "",
        )
        val rows = o.getJSONArray("rows")
        val r1 = rows.getJSONObject(0)
        assertEquals("напоминание 2026-10-16T12:00:00+03:00", r1.getString("remind"))
        assertEquals("Напоминания", r1.getString("group"))
        assertTrue(r1.getBoolean("in_view"))
        assertFalse("у напоминания нет ни срока, ни «потом»", r1.has("due") || r1.has("back"))
        val r2 = rows.getJSONObject(1)
        assertEquals("напоминание по месту «Дом»", r2.getString("remind"))
        assertFalse(r2.getBoolean("in_view"))
        val b1 = rows.getJSONObject(2)
        assertEquals("потом · вернётся $today («на неделе»)", b1.getString("back"))
        assertEquals("Вернулось", b1.getString("group"))
        assertFalse(b1.has("remind"))
        assertEquals("потом · вернётся 2026-10-19", rows.getJSONObject(3).getString("back"))
        val d1 = rows.getJSONObject(4)
        assertFalse("со сроком — не «потом»", d1.has("back") || d1.has("remind"))
        assertEquals(today, d1.getString("due"))
        val words = DelaDebug.snapshotLines(o).joinToString("\n")
        assertTrue(words, words.contains("#70 Отправить счёт в Контуре — ") && words.contains("напоминание 2026-10-16T12:00:00+03:00"))
        assertTrue(words, words.contains("напоминание по месту «Дом»") && words.contains("потом · вернётся $today («на неделе»)"))
    }

    @Test
    fun `«Сейчас на завтра» в снимке — строка плана, отмеченное и кандидаты со «почему · откуда»`() {
        // Поезд 16.10.2026 (вагоны 3 и 6, правила 28 и 30): пачка plan:<день> — кандидаты П-номерами, строка плана и
        // отмеченное заранее; Claude читает их словами, а не хвостом JSON.
        val day = "2026-10-19"
        val p = JSONObject().put("basket", "owed").put("for_date", day).put("focus_on", day).put("order", 1)
            .put("why", "Пётр ждёт 6 дней").put("from", "руками 10.10")
            .put("options", JSONArray().put(JSONObject().put("label", "☆ На понедельник").put("focus_on", day)))
        val s = Dela.Snapshot(
            tasks = listOf(
                Dela.Task(id = "a", num = 60, title = "Ответить Петру", ownerId = "me"),
                Dela.Task(id = "m", num = 61, title = "Иван Тестов: прислать договор", ownerId = "me", focusOn = day),
            ).associateBy { it.id },
            suggestions = listOf(
                Dela.Suggestion(id = "c1", forUser = "me", kind = "update", taskId = "a", payload = p.toString(), source = "plan",
                    batchRef = "plan:$day", batchTitle = "Сейчас на пн 19.10 · встреч ~3 — возьми 3", status = "pending",
                    expiresAt = "${day}T12:00:00+03:00"),
            ).associateBy { it.id },
            features = setOf(DelaDebug.FEATURE, Dela.FEATURE_OPTIONS, Dela.FEATURE_PLAN),
        )
        val now = OffsetDateTime.parse("2026-10-16T14:05:00+03:00").toInstant().toEpochMilli()
        val plan = DelaViews.plan(s, "me", today, now, day = day)!!
        val o = DelaDebug.snapshot(
            DelaDebug.Screen("nav:new/plan:$day", "Сейчас на понедельник — кандидаты, отметить до пяти", newIds = listOf("c1"),
                plan = plan, planBatch = true),
            s, today, "",
        )
        val j = o.getJSONObject("plan")
        assertEquals("Сейчас на понедельник: предложено 1 · отмечено 1 из 5", j.getString("line"))
        assertTrue(j.getBoolean("batch"))
        assertEquals("#61 Иван Тестов: прислать договор", j.getJSONArray("marked").getString(0))
        val c = o.getJSONArray("new").getJSONObject(0)
        assertEquals("Ответить Петру", c.getString("title"))
        assertEquals("Пётр ждёт 6 дней · руками 10.10", c.getString("hint"))
        assertEquals(day, c.getString("day"))
        val words = DelaDebug.snapshotLines(o).joinToString("\n")
        assertTrue(words, words.contains("«Сейчас на завтра» (пачка): Сейчас на понедельник: предложено 1 · отмечено 1 из 5"))
        assertTrue(words, words.contains("  отмечено: #61 Иван Тестов: прислать договор"))
        assertFalse(words, words.contains("plan: {"))
        // Без блока на экране — ключа нет.
        assertFalse(DelaDebug.snapshot(DelaDebug.Screen("nav:now", "Сейчас"), s, today, "").has("plan"))
    }

    @Test
    fun `«Подскажи» в снимке — фаза, свёрнутая строка или «раскрыто», группы`() {
        // Поезд 16.10.2026 (вагоны 3 и 7, шаг 2 петли): свёрнуто — вопросов в «new» нет, поэтому фаза, строка и группы —
        // ключом asks, той же формой, что у веба (contract/dela-debug.json); Claude читает их словами.
        val day = "2026-10-15"
        fun sg(id: String, created: String, payload: String, source: String = "meeting") =
            Dela.Suggestion(id = id, forUser = "me", source = source, createdAt = created, payload = payload)
        val s = Dela.Snapshot(
            suggestions = listOf(
                sg("h", "${day}T09:00:00+03:00", """{"urgency":"today","urgent_on":"$day","about":{"kind":"client","id":"k1","title":"ООО Ромашка"}}"""),
                sg("c", "${day}T08:00:00+03:00", """{"urgency":"evening","about":{"kind":"person","title":"Иван Тестов"}}"""),
                sg("p", "${day}T08:30:00+03:00", "{}", source = Dela.PLAN_SOURCE),
            ).associateBy { it.id },
            features = setOf(Dela.FEATURE_ASKS),
        )
        val at = OffsetDateTime.parse("${day}T10:00:00+03:00").toInstant().toEpochMilli()
        val q = Asks.queue(s, "me", at, Asks.FACTORY, Asks.QUIET_FACTORY)
        val folded = Asks.homeCard(q, null)!!
        val o = DelaDebug.snapshot(
            DelaDebug.Screen("nav:now", "Сейчас", newIds = folded.shown.map { it.id }, asks = q, asksCard = folded), s, day, "")
        val a = o.getJSONObject("asks")
        assertEquals("before", a.getString("phase"))
        assertEquals("15:00", a.getString("next"))
        assertEquals("Подскажи · к 15:00: 1 · к вечеру: 1", a.getString("line"))
        assertFalse(a.has("open"))
        assertEquals(listOf(2, 1, 1, 1), listOf(a.getInt("total"), a.getInt("urgent"), a.getInt("rest"), a.getInt("plan")))
        val g = a.getJSONArray("groups")
        assertEquals("ООО Ромашка", g.getJSONObject(0).getString("title"))
        assertEquals(1, g.getJSONObject(0).getInt("urgent"))
        assertFalse("свёрнуто — вопросов на экране нет", o.has("new"))
        val words = DelaDebug.snapshotLines(o).joinToString("\n")
        assertTrue(words, words.contains("«Подскажи»: фаза before, следующий заход 15:00 · Подскажи · к 15:00: 1 · к вечеру: 1 · " +
            "всего 2, сгорает 1, к вечеру 1, кандидатов плана 1"))
        assertTrue(words, words.contains("  ООО Ромашка · 1 (сгорает 1)"))
        assertTrue(words, words.contains("  Иван Тестов · 1"))
        assertFalse(words, words.contains("asks: {"))
        // Раскрыто тапом — «open», строки нет; вопросы на экране — в «new» П-номерами.
        val opened = Asks.homeCard(q, q.openKey)!!
        val o2 = DelaDebug.snapshot(
            DelaDebug.Screen("nav:now", "Сейчас", newIds = opened.shown.map { it.id }, asks = q, asksCard = opened), s, day, "")
        assertTrue(o2.getJSONObject("asks").getBoolean("open"))
        assertFalse(o2.getJSONObject("asks").has("line"))
        assertEquals(2, o2.getJSONArray("new").length())
        // Без флага у сервера — блок по-старому, ключа нет.
        val legacy = Asks.queue(s.copy(features = emptySet()), "me", at, Asks.FACTORY, Asks.QUIET_FACTORY)
        assertFalse(DelaDebug.snapshot(
            DelaDebug.Screen("nav:now", "Сейчас", asks = legacy, asksCard = Asks.homeCard(legacy, null)), s, day, "").has("asks"))
    }

    @Test
    fun `главная — «Сегодня» подгруппами, дело внутри прямо перед родителем, «Сейчас» с делами внутри над родителем`() {
        // Поезд 16.10.2026, проверка вагона 3: дело внутри получило срок «сегодня» от родителя — на экране оно в подгруппе
        // родителя прямо перед ним (DelaHomeUi: DelaGroups.nested по группе, внутри — TaskRows со stepsFirst).
        val s0 = nest()
        val s = s0.copy(tasks = s0.tasks + listOf(
            Dela.Task(id = "o", num = 40, title = "Ответить Ивану", ownerId = "me", owed = true, personId = "h", dueDate = today),
            Dela.Task(id = "n", num = 41, title = "Позвонить в банк", ownerId = "me", focusOn = today),
        ).associateBy { it.id })
        val p = s.tasks.getValue("p")
        val k2 = s.tasks.getValue("k2")
        val rows = DelaDebug.homeRows(listOf(s.tasks.getValue("n")), emptyList(), listOf(p, s.tasks.getValue("o"), k2), s, today, setOf("p"))
        assertEquals(listOf("n", "o", "k2", "p"), rows.map { it.id })
        assertEquals(listOf("Сейчас", "Сегодня › Отбить", "Сегодня › Запустить", "Сегодня › Запустить"), rows.map { it.group })
        assertTrue(rows.last().inView)
        assertFalse(rows[2].inView)
        // «Сейчас»: дела внутри дел из «Сейчас» — над родителем отступом (DelaSteps.inner, как DelaHomeUi).
        val now = DelaDebug.homeRows(listOf(p), listOf(k2 to 1), emptyList(), s, today)
        assertEquals(listOf("k2" to 1, "p" to 0), now.map { it.id to it.depth })
        // В снимке — тем же порядком, с подгруппой и вложенностью.
        val o = DelaDebug.snapshot(DelaDebug.Screen("nav:home", "Сейчас", rows = rows), s, today, "")
        val r = o.getJSONArray("rows")
        assertEquals("Сегодня › Запустить", r.getJSONObject(2).getString("group"))
        assertEquals("#12 Отправить термшит Ивану", r.getJSONObject(2).getString("in"))
        assertEquals(2, r.getJSONObject(3).getInt("inside"))
    }

    @Test
    fun `карточка — комментарии строками, приватный с пометкой «только тебе», удалённого нет`() {
        // Поезд 16.10.2026, проверка вагона 3: «Журнал» карточки показывает комментарии и «только тебе · » у приватных
        // (правило 26) — в снимке они тоже есть, иначе записка о багах приватности их не покажет.
        val s0 = nest()
        val s = s0.copy(comments = listOf(
            Dela.Comment(id = "c1", taskId = "p", authorId = "me", text = "Основание из Telegram: Иван просил без цифр",
                createdAt = "2026-10-16T09:30:00+03:00", private = true),
            Dela.Comment(id = "c0", taskId = "p", authorId = "me", text = "  Общий  ", createdAt = "2026-10-16T09:00:00+03:00"),
            Dela.Comment(id = "c2", taskId = "p", authorId = "me", text = "убранный", createdAt = "2026-10-16T09:40:00+03:00",
                deletedAt = "2026-10-16T09:41:00+03:00"),
            Dela.Comment(id = "c3", taskId = "k1", authorId = "me", text = "чужое дело", createdAt = "2026-10-16T09:50:00+03:00"),
            Dela.Comment(id = "c4", taskId = "p", authorId = "me", text = "д".repeat(DelaDebug.COMMENT_MAX + 50), createdAt = "2026-10-16T10:00:00+03:00"),
        ).associateBy { it.id })
        val o = DelaDebug.snapshot(DelaDebug.Screen("nav:all", "Все дела", openTaskId = "p"), s, today, "")
        val c = o.getJSONObject("card").getJSONObject("fields").getJSONArray("comments")
        assertEquals(3, c.length())
        assertEquals("2026-10-16 09:00 · me · Общий", c.getString(0))
        assertEquals("2026-10-16 09:30 · me · только тебе · Основание из Telegram: Иван просил без цифр", c.getString(1))
        assertTrue("длинный — коротко", c.getString(2).endsWith("…") && c.getString(2).length < DelaDebug.COMMENT_MAX + 30)
        // Словами (лист «Отладка», `debug show`) — строкой на комментарий.
        assertTrue(DelaDebug.snapshotLines(o).any { it.trim() == "2026-10-16 09:30 · me · только тебе · Основание из Telegram: Иван просил без цифр" })
        // У дела без комментариев ключа нет.
        val bare = DelaDebug.snapshot(DelaDebug.Screen("nav:all", "Все дела", openTaskId = "k2"), s, today, "")
        assertFalse(bare.getJSONObject("card").getJSONObject("fields").has("comments"))
    }

    @Test
    fun `действия — вложить и вынуть словами, Claude, тосты и ответы сервера по времени`() {
        DelaDebug.clearOps() // кольца — на весь процесс: тосты других тестов здесь ни к чему
        val s = nest()
        val now = OffsetDateTime.of(2026, 10, 16, 10, 20, 0, 0, ZoneOffset.ofHours(3))
        val t0 = now.minusMinutes(3).toInstant().toEpochMilli()
        DelaDebug.noteOps(listOf(
            JSONObject().put("op", "task.set").put("id", "k1").put("set", JSONObject().put("why_id", "p").put("pos", 2)),
            JSONObject().put("op", "task.set").put("id", "k2").put("set", JSONObject().put("why_id", JSONObject.NULL)),
            JSONObject().put("op", "task.set").put("id", "k2").put("set", JSONObject().put("why_id", "g")),
        ), now = t0)
        DelaDebug.note("ask", "положи драфт в термшит", now = t0 + 1000)
        DelaDebug.note("reply", "Вложил.", JSONObject().put("changed", JSONArray().put("Набросать драфт: внутрь")), now = t0 + 2000)
        DelaDebug.note("toast", "🐞 №3 записал", now = t0 + 3000)
        DelaDebug.note("toast", "Сделано: Набросать драфт", now = t0 + 4000)
        DelaDebug.note("op_result", "всё внутри сделано — поднят «#12 Отправить термшит Ивану»", now = t0 + 5000)
        val a = DelaDebug.actions(DelaDebug.recentOps(), emptyList(), now, DelaDebug.recentNotes(), s)
        val texts = (0 until a.length()).map { a.getJSONObject(it).getString("text") }
        assertEquals("task.set «Набросать драфт» — внутрь «#12 Отправить термшит Ивану», место 2", texts[0])
        assertEquals("task.set «Взять цифры из расшифровки» — вынуть из дела", texts[1])
        assertEquals("task.set «Взять цифры из расшифровки» — Зачем — цель «Сделки закрываются»", texts[2])
        assertEquals(listOf("ask", "reply", "toast", "op_result"), (3 until a.length()).map { a.getJSONObject(it).getString("kind") })
        assertFalse("тост самой записки в кольцо не идёт", texts.any { it.startsWith("🐞") })
        assertEquals("Набросать драфт: внутрь", a.getJSONObject(4).getJSONObject("data").getJSONArray("changed").getString(0))
        repeat(DelaDebug.NOTES + 3) { DelaDebug.note("toast", "т$it", now = t0 + 10_000L + it) }
        assertEquals(DelaDebug.NOTES, DelaDebug.recentNotes().size)
    }

    @Test
    fun `записка словами — экран, строки с отступом и вложенностью, действия по-русски`() {
        val snapJson = DelaDebug.snapshot(
            DelaDebug.Screen("nav:all", "Все дела", sub = "открытых 3",
                rows = listOf(DelaDebug.Row("p", true, "Ромашка"), DelaDebug.Row("k2", false, "Ромашка", depth = 1)), openTaskId = "p"),
            nest(), today, "")
        val lines = DelaDebug.snapshotLines(snapJson)
        assertEquals("экран: Все дела · nav:all · открытых 3", lines[0])
        assertTrue(lines.any { it.trim() == "1. #14 Взять цифры из расшифровки · срок $today · pos 1" })
        assertTrue(lines.any { it.startsWith("  ★ #12 Отправить термшит Ивану") && "внутри открыто 2" in it })
        assertTrue(lines.any { "↳ #14 Взять цифры из расшифровки" in it && "внутри #12 Отправить термшит Ивану" in it && "место 1" in it })
        assertEquals(listOf("снимка нет"), DelaDebug.snapshotLines(JSONObject()))
        val acts = DelaDebug.actionLines(JSONArray().put(JSONObject().put("at", "2026-10-16T07:14:52Z").put("kind", "reply").put("text", "Перенёс  два\nдела")))
        assertEquals(listOf("10:14:52 Claude ответил: Перенёс два дела"), acts)
    }

    @Test
    fun `операции записки и статуса — как в контракте`() {
        val op = DelaDebug.addOp("op-1", "n-1", "  Сказал X — сделал Y  ", "999", "411×914 dp сложен", "2026-10-16T10:15:00+03:00",
            JSONObject().put("v", 1), JSONArray(), shot = "")
        assertEquals("debug.add", op.getString("op"))
        val note = op.getJSONObject("note")
        assertEquals("Сказал X — сделал Y", note.getString("text"))
        assertEquals("phone", note.getString("source"))
        assertFalse("пустая картинка не едет", note.has("shot"))
        val set = DelaDebug.setOp("op-2", "n-1", status = "fixed")
        assertEquals("fixed", set.getString("status"))
        assertFalse(set.has("answer"))
    }

    @Test
    fun `ответы сервера — поле синка и список`() {
        assertNull(DelaDebug.info(JSONObject()))
        val i = DelaDebug.info(JSONObject().put("debug", JSONObject().put("on", true).put("new", 3).put("last", 12).put("answered", JSONObject.NULL)))!!
        assertEquals(DelaDebug.Info(true, 3, 12, ""), i)
        assertEquals(i, DelaDebug.info(JSONObject().put("debug", DelaDebug.infoJson(i))))
        val list = DelaDebug.notes(JSONObject().put("items", JSONArray().put(JSONObject()
            .put("id", "n-1").put("num", 12).put("said_at", "2026-10-16T10:15:00+03:00").put("text", "Сказал X — сделал Y")
            .put("source", "web").put("status", "not_bug").put("answer", "Так и задумано.").put("status_by", "claude")
            .put("title", "Сегодня").put("has_shot", true))))
        assertEquals(1, list.size)
        assertEquals("не баг", list[0].statusWord)
        // Слова статусов — владельца (приёмка 16.10.2026), как веб и сервер.
        assertEquals(listOf("новое", "разбираю", "починено", "не баг", "потом"), DelaDebug.STATUS.values.toList())
        assertEquals("claude", list[0].statusBy)
        assertTrue(list[0].hasShot)
        assertEquals("сегодня 10:15", DelaDebug.whenWords(list[0].at, today))
        assertEquals("15.10 23:59", DelaDebug.whenWords("2026-10-15T20:59:00Z", today))
    }
}
