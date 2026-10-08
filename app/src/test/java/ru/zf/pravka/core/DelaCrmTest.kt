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

/**
 * Телефон против второй части контракта (`server/contract/dela-crm.json`,
 * 05.10.2026): синк CRM, «Новое» с «уточнить» и «закрыто само», правка
 * словами (`/api/ask`) и её отмена, ответы всех видов CRM, видимость CRM и
 * денег по `users`. Сервер держит эту форму своими тестами, телефон — этими:
 * ключ, названный иначе, сервер отвергнет, а поле, прочитанное не тем
 * именем, тихо станет пустым на экране.
 */
class DelaCrmTest {

    private fun file(name: String): JSONObject {
        val f = listOf(File("../server/contract/$name"), File("server/contract/$name")).first { it.isFile }
        return JSONObject(f.readText())
    }

    private val crm by lazy { file("dela-crm.json") }
    private val part1 by lazy { file("dela.json") }

    private val nowIso = "2026-10-05T20:00:00+03:00"
    private val now = java.time.OffsetDateTime.parse(nowIso).toInstant().toEpochMilli()
    private val today = "2026-10-05"

    /** Синк части 1 целиком, поверх — добавки части 2 (payments, сделки, users с доступом, предложения). */
    private fun snap(): Dela.Snapshot {
        val base = Dela.merge(Dela.Snapshot(), part1.getJSONObject("sync_response"), now)
        val add = JSONObject(crm.getJSONObject("sync_additions").toString()).put("full", false).put("seq", 2000).put("today", today)
        return Dela.merge(base, add, now)
    }

    private fun view(name: String): JSONObject = crm.getJSONObject("views_crm").getJSONObject(name).getJSONObject("response")

    // ------------------------------------------------------------ синк

    @Test
    fun `синк несёт оплаты, сделки с гонораром и доступ пользователей`() {
        val s = snap()
        val pay = s.payments.values.single()
        assertEquals("de2f9b8f-eb9b-443a-bfc8-992c5534fa85", pay.dealId)
        assertEquals("advance", pay.kind)
        assertEquals(25_000_000L, pay.amountKop)
        assertEquals("2026-09-07", pay.invoicedOn)
        assertEquals("2026-09-15", pay.paidOn)
        assertEquals("", pay.title)  // JSON null — пусто, а не «null»
        val deal = s.deals.getValue("de2f9b8f-eb9b-443a-bfc8-992c5534fa85")
        assertEquals("active", deal.stage)
        assertEquals(50_000_000L, deal.feeKop)
        assertEquals(listOf("02ecd0f2-dd80-4a97-aa45-47126a266723"), deal.personIds)
        assertEquals(listOf(pay), s.paymentsOf(deal.id))
        val sasha = s.users.getValue("sasha")
        assertEquals("own", sasha.clients)
        assertFalse(sasha.seesMoney)
        assertTrue(sasha.owner)
        val kolya = s.users.getValue("member1")
        assertEquals("team", kolya.clients)
        assertFalse(kolya.owner)
    }

    @Test
    fun `кэш на диск хранит оплаты, сделки и доступ`() {
        val s = snap()
        val back = Dela.fromJson(JSONObject(Dela.toJson(s).toString()))
        assertEquals(s.payments, back.payments)
        assertEquals(s.deals, back.deals)
        assertEquals(s.users, back.users)
        assertEquals(s.suggestions, back.suggestions)
    }

    @Test
    fun `деньги, закрытые человеку, — null, а не ноль`() {
        val o = JSONObject(crm.getJSONObject("sync_additions").getJSONArray("deals").getJSONObject(0).toString()).put("fee_kop", JSONObject.NULL)
        assertNull(Dela.deal(o)!!.feeKop)
        assertNull(DelaCrm.deal(view("pipeline").getJSONArray("deals").getJSONObject(0))!!.paidKop)
    }

    // ------------------------------------------------------------ видимость

    @Test
    fun `CRM видна владельцу и тем, кому открыты клиенты, деньги — владельцу и sees_money`() {
        val s = snap()
        // Владелец: CRM и деньги по роли, хотя sees_money у него false.
        assertTrue(Dela.crmOn(s, "sasha"))
        assertTrue(Dela.moneyOn(s, "sasha"))
        // Коля: clients = team — CRM есть, денег нет.
        assertTrue(Dela.crmOn(s, "member1"))
        assertFalse(Dela.moneyOn(s, "member1"))
        // Член команды со своими проектами — ни CRM, ни денег.
        val own = s.copy(users = s.users + ("m2" to Dela.User("m2", "Мила", role = "member", clients = "own", seesMoney = true)))
        assertFalse(Dela.crmOn(own, "m2"))
        assertTrue(Dela.moneyOn(own, "m2"))
        // Себя в users нет (старая служба) — CRM не показываем.
        assertFalse(Dela.crmOn(s, "nobody"))
        assertFalse(Dela.moneyOn(s, "nobody"))
    }

    // ------------------------------------------------------------ «Новое»

    @Test
    fun `уточнить — что именно поменяется, подробности отдельно`() {
        val base = snap()
        val taskId = "a8a6d096-255c-4a82-8bf9-c0372dc68477"
        val s = Dela.derive(base.copy(tasks = base.tasks + (taskId to Dela.Task(id = taskId, num = 12, title = "Ольга: тизер для фондов", ownerId = "sasha", dueDate = "2026-10-08"))))
        val sg = s.suggestions.values.single { it.kind == "update" }
        val u = Dela.update(sg, s)
        assertEquals(12, u.task!!.num)
        assertEquals("Ольга: тизер для фондов с цифрами Q3", u.title)
        assertEquals("2026-10-08", u.dueFrom)
        assertEquals("2026-10-11", u.dueTo)
        assertEquals(Dela.WAITING, u.ball)
        assertEquals("Ольга", u.person)
        assertEquals("Нужны две версии: короткая на страницу и полная с цифрами Q3", u.note)
        assertFalse(u.empty)
        assertEquals("Тизер переносим на следующую неделю, Ольга доделает", sg.quote)
        // Принять — как прежде: decide accept, человека по имени и подробности сервер сделает сам.
        val op = Dela.decideOp(sg.id, accept = true)
        assertEquals("accept", op.getString("decision"))
        assertFalse(op.has("set"))
        // ✎ у уточнения: черновик — само дело с предложенным поверх, set — только поправленное руками.
        val draft = Dela.draftOf(sg, s, "sasha")
        assertEquals(taskId, draft.id)
        assertEquals("2026-10-11", draft.dueDate)
        assertEquals("Ольга: тизер для фондов с цифрами Q3", draft.title)
        val (set, _) = Dela.diff(draft, draft.copy(dueDate = "2026-10-12"))
        assertEquals(setOf("due_date"), set.keys().asSequence().toSet())
    }

    @Test
    fun `пачка — заголовок, источник и дата встречи, счётчик — только pending`() {
        val s = snap()
        val batches = Dela.newBatches(s, "sasha")
        assertEquals(1, batches.size)
        val b = batches.single()
        assertEquals("meeting:2", b.ref)
        assertEquals("Бета: статус · 23.09", b.title)
        assertEquals("meeting", b.source)
        assertEquals("2026-09-23", b.at)
        assertEquals(setOf("update", "close"), b.items.map { it.kind }.toSet())
        // «Закрыто само» (accepted) в счётчик «Нового» не входит.
        assertEquals(2, Dela.newOnes(s, "sasha").size)
        assertEquals(2, Dela.morning(s, "sasha", today, "all", now).newCount)
        // Свежая встреча — выше старой.
        val fresh = JSONObject(crm.getJSONObject("sync_additions").getJSONArray("suggestions").getJSONObject(0).toString())
            .put("id", "f1").put("batch_ref", "meeting:3").put("batch_title", "Гамма · 04.10")
            .put("payload", JSONObject().put("title", "x").put("meeting_at", "2026-10-04")).put("kind", "create").put("task_id", JSONObject.NULL)
        val s2 = Dela.merge(s, JSONObject().put("full", false).put("seq", 2001).put("suggestions", JSONArray().put(fresh)), now)
        assertEquals(listOf("meeting:3", "meeting:2"), Dela.newBatches(s2, "sasha").map { it.ref })
    }

    @Test
    fun `закрыто само — за неделю, с основанием и «Вернуть»`() {
        val base = snap()
        val taskId = "77f1248d-d409-48d8-968e-4e39997e6cc0"
        val s = Dela.derive(base.copy(tasks = base.tasks + (taskId to Dela.Task(id = taskId, num = 9, title = "Продлить Контур", ownerId = "sasha", status = Dela.DONE))))
        val auto = Dela.autoDone(s, "sasha", now)
        assertEquals(1, auto.size)
        val a = auto.single()
        assertTrue(a.autoDone)
        assertEquals("закрыто само", a.reason)
        assertEquals("Telegram · сегодня", a.batchTitle)
        assertEquals("Контур: «Лицензия продлена до 10.2027»", a.quote)
        assertEquals("telegram", a.source)
        // Неделя прошла — раздела нет.
        assertTrue(Dela.autoDone(s, "sasha", now + 8 * 86_400_000L).isEmpty())
        // Чужое — не моё.
        assertTrue(Dela.autoDone(s, "member1", now).isEmpty())
        // Обычное «закрыть», ещё не принятое, — не «само».
        assertFalse(s.suggestions.values.single { it.kind == "close" && it.pending }.autoDone)
        // «Вернуть» — task.reopen, и дело сразу видно открытым: «вернул в работу».
        val op = Dela.statusOp("task.reopen", a.taskId)
        assertEquals("task.reopen", op.getString("op"))
        val v = Dela.overlay(s, listOf(op), "sasha", today, nowIso)
        assertEquals(Dela.OPEN, v.tasks.getValue(taskId).status)
        assertTrue(op.getString("op") in opsList())
    }

    @Test
    fun `наговорка и «Понятно» — операции сервера 06_10, только когда он их знает`() {
        // dictation.add — форма store.op_dictation_add: id (= source_ref дел), text, at, source.
        val op = Dela.dictationOp("raznoska:1759650000000", "  позвонить Ивану  ", "2026-10-05T08:15:00+03:00", Dela.stableId("razn-dictation-1"))
        assertEquals("dictation.add", op.getString("op"))
        assertTrue(Dela.isUuid(op.getString("op_id")))
        val d = op.getJSONObject("dictation")
        assertEquals(setOf("id", "text", "at", "source"), d.keys().asSequence().toSet())
        assertEquals("raznoska:1759650000000", d.getString("id"))
        assertEquals("позвонить Ивану", d.getString("text"))
        assertEquals("phone", d.getString("source"))
        // suggestion.seen — ids списком.
        val seen = Dela.seenOp(listOf("a", "b"))
        assertEquals("suggestion.seen", seen.getString("op"))
        assertEquals(2, seen.getJSONArray("ids").length())
        assertTrue(Dela.isUuid(seen.getString("op_id")))
        // Флаг — состояние сервера сейчас: без «dictations» в синке телефон их не шлёт.
        val s = snap()
        assertFalse(s.dictationsOn)
        val on = Dela.merge(s, JSONObject().put("full", false).put("seq", 2001).put("features", JSONArray().put("remind").put("dictations")), now)
        assertTrue(on.dictationsOn)
        assertFalse(Dela.merge(on, JSONObject().put("full", false).put("seq", 2002).put("features", JSONArray().put("remind")), now).dictationsOn)
        // Обе операции не трогают копию (кроме seen_at) и называются словами в «сервер ответил».
        assertEquals("текст наговорки", Dela.describe(op, s))
        assertEquals("«понятно» у закрытого само", Dela.describe(seen, s))
        assertEquals(s.tasks, Dela.overlay(s, listOf(op), "sasha", today, nowIso).tasks)
    }

    @Test
    fun `seen_at из синка — закрытое само уходит из «Нового», в кэше живёт`() {
        val s = snap()
        val auto = s.suggestions.values.single { it.autoDone }
        assertEquals("", auto.seenAt)
        val row = Dela.json(auto).put("seen_at", "2026-10-05T19:00:00+03:00").put("rev", auto.rev + 1)
        val s2 = Dela.merge(s, JSONObject().put("full", false).put("seq", 2001).put("suggestions", JSONArray().put(row)), now)
        assertEquals("2026-10-05T19:00:00+03:00", s2.suggestions.getValue(auto.id).seenAt)
        assertTrue(Dela.autoDone(s2, "sasha", now).isEmpty())
        val back = Dela.fromJson(JSONObject(Dela.toJson(s2).toString()))
        assertEquals(s2.suggestions, back.suggestions)
    }

    @Test
    fun `правка карточки в ответе Claude — словами и в «Вернуть всё»`() {
        // Форма — ask.card_done: crm[] {what, undo[]}; у новой сделки отмены нет (undo пуст).
        val d = JSONObject(ask.getJSONObject("done_edit").toString())
            .put("crm", JSONArray()
                .put(JSONObject().put("what", "в хронологию: созвонились, ждут модель")
                    .put("undo", JSONArray().put(JSONObject().put("op", "interaction.delete").put("id", "i1"))))
                .put(JSONObject().put("what", "Иван: должность CFO")
                    .put("undo", JSONArray().put(JSONObject().put("op", "person.set").put("id", "p1").put("set", JSONObject().put("role", JSONObject.NULL)))))
                .put(JSONObject().put("what", "новая сделка: Бета: модель (лид)").put("undo", JSONArray())))
        val r = DelaAsk.parse(d)
        assertEquals(listOf("в хронологию: созвонились, ждут модель", "Иван: должность CFO", "новая сделка: Бета: модель (лид)"), r.crm.map { it.what })
        assertTrue(r.undoable)
        val undo = DelaAsk.undoOps(r)
        // Сначала дела (как раньше), потом карточка — как прислал сервер, у каждой операции свой op_id.
        assertEquals(listOf("task.set", "task.reopen", "interaction.delete", "person.set"), undo.map { it.getString("op") })
        assertTrue(undo.all { Dela.isUuid(it.getString("op_id")) })
        assertTrue(undo[3].getJSONObject("set").isNull("role"))
        // Ответ без дел, только карточка: вернуть есть что.
        val onlyCard = DelaAsk.parse(JSONObject().put("route", "edit").put("crm", d.getJSONArray("crm")))
        assertEquals(0, onlyCard.count)
        assertTrue(onlyCard.undoable)
        // Старый сервер поля не знает — пусто, не падает.
        assertTrue(DelaAsk.parse(ask.getJSONObject("done_edit")).crm.isEmpty())
        assertFalse(DelaAsk.parse(JSONObject().put("route", "edit")).undoable)
    }

    // ------------------------------------------------------------ деньги дела

    @Test
    fun `деньги дела — не в task set, а Разноска их по-прежнему пишет`() {
        val t = snap().task("57")!!
        val (set, was) = Dela.diff(t, t.copy(money = "paid"))
        assertEquals(0, set.length())
        assertEquals(0, was.length())
        assertFalse("money" in Dela.EDITABLE)
        val create = Dela.createOp(t.copy(id = Dela.newId(), money = "potential")).getJSONObject("task")
        assertEquals("potential", create.getString("money"))
        // Порядок без денег: оплаченное без срока не обгоняет давнее.
        val a = Dela.Task(id = "a", title = "a", createdAt = "2026-10-01T10:00:00+03:00", moneyEff = "none")
        val b = Dela.Task(id = "b", title = "b", createdAt = "2026-10-02T10:00:00+03:00", moneyEff = "paid")
        assertEquals(listOf("a", "b"), listOf(b, a).sortedWith(Dela.ORDER).map { it.id })
    }

    @Test
    fun `время срока правится словами человека — в ЧЧ ММ`() {
        assertEquals("09:30", Dela.normTime("9:30"))
        assertEquals("09:30", Dela.normTime("930"))
        assertEquals("18:00", Dela.normTime("18.00"))
        assertEquals("07:05", Dela.normTime(" 07:05 "))
        assertNull(Dela.normTime("25:00"))
        assertNull(Dela.normTime("9:7"))
        assertNull(Dela.normTime("утром"))
        // Время уходит в task.set как поле карточки; «10:00:00» сервера без правки — не правка.
        val t = Dela.Task(id = "a", title = "x", dueDate = "2026-10-07", dueTime = "10:00:00")
        assertEquals(0, Dela.diff(t, t.copy()).first.length())
        assertEquals("09:30", Dela.diff(t, t.copy(dueTime = "09:30")).first.getString("due_time"))
        // Без срока — и без времени (как у триггера).
        assertEquals("", Dela.applyFields(t, JSONObject().put("due_date", JSONObject.NULL), today, nowIso).dueTime)
    }

    // ------------------------------------------------------------ правка словами

    private val ask by lazy { crm.getJSONObject("ask") }

    @Test
    fun `в окне — от строки видно хотя бы 60 процентов, под строкой Claude не в счёт`() {
        fun row(top: Float, h: Float = 100f, left: Float = 0f, w: Float = 400f, fullW: Float = 400f) =
            DelaAsk.Seen(top, top + h, left, left + w, 100f, fullW)
        val rows = mapOf(
            "a" to row(0f, h = 70f),        // сверху обрезана, видно 70 %
            "b" to row(100f),
            "c" to row(850f),               // строка Claude с 900: видно 50 — не в счёт
            "d" to row(780f),               // видно 100 из 100 до 880
            "e" to row(300f, w = 200f),     // колонка доски за краем: видно половину ширины
            "f" to row(300f, left = 400f),
        )
        assertEquals(listOf("a", "b", "f", "d"), DelaAsk.visible(rows, top = 0f, bottom = 900f))
    }

    @Test
    fun `scope у дела и на экране — форма контракта`() {
        val req = ask.getJSONObject("request").getJSONObject("POST /api/ask")
        val keys = req.getJSONObject("scope").keys().asSequence().toSet()
        val id = Dela.newId()
        // Микрофона у дела больше нет (задание 10): открытое дело — `open`, что в окне — `visible_ids`.
        val other = Dela.newId()
        val one = DelaAsk.scope("Сейчас", listOf(id, other), visibleIds = listOf(other, "x"), selectedIds = listOf(id), open = id)
        assertEquals(id, one.getString("open"))
        assertEquals(listOf(other), (0 until one.getJSONArray("visible_ids").length()).map { one.getJSONArray("visible_ids").getString(it) })
        assertEquals(id, one.getJSONArray("selected_ids").getString(0))
        assertFalse(one.has("focus"))
        assertTrue(one.keys().asSequence().toSet().minus(keys).isEmpty())
        // Сделки экрана (задание 8): все по порядку и те, что в окне; нет сделок — полей нет.
        val d1 = Dela.newId()
        val d2 = Dela.newId()
        val pipe = DelaAsk.see(DelaAsk.scope("Воронка", emptyList()), visibleIds = emptyList(), dealIds = listOf(d1, d2, "x"), visibleDealIds = listOf(d2, Dela.newId()))
        assertEquals(listOf(d1, d2), (0 until pipe.getJSONArray("deal_ids").length()).map { pipe.getJSONArray("deal_ids").getString(it) })
        assertEquals(listOf(d2), (0 until pipe.getJSONArray("visible_deal_ids").length()).map { pipe.getJSONArray("visible_deal_ids").getString(it) })
        assertTrue(pipe.keys().asSequence().toSet().minus(keys).isEmpty())
        assertFalse(one.has("deal_ids"))
        // Неизвестно, что в окне, — поля нет совсем (сервер возьмёт весь экран), а не пустой список.
        assertFalse(DelaAsk.scope("Сейчас", listOf(id)).has("visible_ids"))

        val p = Dela.newId()
        val ids = listOf(Dela.newId(), Dela.newId())
        val screen = DelaAsk.scope("Проект: Бета Групп", ids + ids.first() + "suggestion:x", projectId = p)
        assertEquals("Проект: Бета Групп", screen.getString("title"))
        // Порядок показа, повтор — один раз, не-uuid — мимо: сервер иначе отверг бы весь запрос.
        assertEquals(ids, (0 until screen.getJSONArray("task_ids").length()).map { screen.getJSONArray("task_ids").getString(it) })
        assertEquals(p, screen.getString("project_id"))
        assertFalse(screen.has("focus"))
        assertTrue(screen.keys().asSequence().toSet().minus(keys).isEmpty())
        // Потолок сервера — 300 дел.
        assertEquals(DelaAsk.MAX_TASKS, DelaAsk.scope("Все", (1..400).map { Dela.newId() }).getJSONArray("task_ids").length())
        val body = DelaAsk.request("  на пятницу ", one)
        assertEquals(req.keys().asSequence().toSet(), body.keys().asSequence().toSet())
        assertEquals("на пятницу", body.getString("text"))
    }

    @Test
    fun `ответ правки — что поменялось словами и «Вернуть всё»`() {
        val r = DelaAsk.parse(ask.getJSONObject("done_edit"))
        assertEquals("edit", r.route)
        assertEquals("Перенёс два просроченных дела на завтра, 6 октября.", r.reply)
        assertEquals("claude-sonnet-5-5", r.model)
        assertEquals(2, r.changed.size)
        assertEquals(2, r.count)
        val (a, b) = r.changed
        assertEquals(5, a.num)
        assertEquals("2026-09-25", a.before.getString("due_date"))
        assertEquals("", a.statusFrom)
        assertEquals(Dela.OPEN, b.statusFrom)
        assertEquals(Dela.DONE, b.statusTo)
        val s = snap()
        assertEquals("срок 06.10", DelaAsk.describe(a, s, today))
        assertEquals("сделано", DelaAsk.describe(b, s, today))

        val undo = DelaAsk.undoOps(r)
        assertEquals(listOf("task.set", "task.reopen"), undo.map { it.getString("op") })
        assertEquals("2026-09-25", undo[0].getJSONObject("set").getString("due_date"))
        // В was — то, что поставил Claude: если поле с тех пор меняли руками, сервер скажет о споре.
        assertEquals("2026-10-06", undo[0].getJSONObject("was").getString("due_date"))
        assertTrue(undo.all { Dela.isUuid(it.getString("op_id")) })
        assertTrue(undo.all { it.getString("op") in opsList() })
    }

    @Test
    fun `route new — новые дела как итог Разноски, отмена — task cancel`() {
        // Пример снят с сервера (05.10): tasks — строки дел как в синке, notes — записи хронологии.
        val d = ask.getJSONObject("done_new")
        val r = DelaAsk.parse(d)
        assertTrue(r.isNew)
        assertTrue(r.changed.isEmpty())
        val t = r.tasks.single()
        assertEquals(60, t.num)
        assertEquals("Заведи позвонить Ивану завтра", t.title)
        assertEquals("2026-10-06", t.dueDate)
        assertEquals("Иван", t.who)
        assertEquals(listOf("звонок"), t.labels)
        assertEquals("Итог: комитет пройден", r.notes.single().summary)
        assertEquals("", r.notes.single().projectId)
        assertEquals(1, r.count)
        val undo = DelaAsk.undoOps(r)
        assertEquals("task.cancel", undo.single().getString("op"))
        assertEquals(t.id, undo.single().getString("id"))
    }

    @Test
    fun `опрос задания — раз в секунду, потом реже, не дольше пяти минут`() {
        assertEquals(1_000L, DelaAsk.pollDelayMs(0))
        assertEquals(1_000L, DelaAsk.pollDelayMs(14))
        assertTrue(DelaAsk.pollDelayMs(15) > 1_000L)
        val total = (0 until DelaAsk.MAX_POLLS).sumOf { DelaAsk.pollDelayMs(it) }
        assertTrue(total in 240_000L..330_000L)
    }

    // ------------------------------------------------------------ виды CRM

    @Test
    fun `воронка`() {
        val v = DelaCrm.pipeline(view("pipeline"))
        assertTrue(v.money)
        assertEquals(5, v.live)
        assertEquals(27_000_000L, v.pipelineKop)
        assertEquals(90_000_000L, v.toGetKop)
        assertEquals(3, v.stale)
        assertEquals(StageCheck("lead", 1, 150_000_000L), v.stages.single().let { StageCheck(it.stage, it.count, it.feeKop) })
        val d = v.deals.single()
        assertEquals("Альфа: модель", d.name)
        assertEquals("Альфа Групп", d.projectName)
        assertTrue(d.closed)
        assertEquals("выиграли", d.stageWord)
        assertEquals("2026-10-05", d.closedOn)
        assertNull(d.nextTask)
        assertFalse(d.quiet)
        // Закрытая — в разделе закрытых, не в стадиях.
        assertTrue(v.byStage().isEmpty())
        assertEquals(listOf(d), v.closed)
    }

    private data class StageCheck(val stage: String, val count: Int, val fee: Long?)

    @Test
    fun `клиенты — последний контакт, следующее дело, стадии, поиск`() {
        val v = DelaCrm.clients(view("clients"))
        val c = v.clients.single()
        assertEquals("Альфа Групп", c.name)
        assertEquals(listOf("active"), c.stages)
        assertEquals(2, c.liveDeals)
        assertEquals("2026-10-04T19:10:25+03:00", c.lastTouch)
        assertEquals(1, c.nextTask!!.num)
        assertEquals("Иван: прислать модель", c.nextTask!!.title)
        assertEquals(25_000_000L, c.paidYearKop)
        assertEquals(15_000_000L, c.invoicedKop)
        assertEquals(listOf(c), v.find("альфа"))
        assertTrue(v.find("гамма").isEmpty())
        assertEquals("вчера", DelaCrm.ago(c.lastTouch, today))
    }

    @Test
    fun `клиент — проект, сделки, люди, хронология`() {
        val v = DelaCrm.client(view("client"))
        assertEquals("Альфа Групп", v.project!!.name)
        assertEquals("client", v.project!!.kind)
        val i = v.timeline.single()
        assertEquals("zoom", i.kind)
        assertEquals("Zoom", i.kindWord)
        assertEquals("2026-10-04", i.day)
        assertEquals("Альфа: фонды", i.dealName)
        assertEquals("Тизер к пятнице", i.nextStep)
        assertEquals(listOf("02ecd0f2-dd80-4a97-aa45-47126a266723"), i.personIds)
        assertEquals("archive", v.deals.single().stage)
        val p = v.people.single()
        assertEquals("Иван Петров", p.name)
        assertEquals("month", p.cadence)
        assertTrue(p.hub)
        assertTrue(v.money)
    }

    @Test
    fun `сделка — стадия, следующее дело, оплаты, хронология, журнал`() {
        val v = DelaCrm.dealView(view("deal"))
        val d = v.deal!!
        assertEquals("Альфа: фонды", d.name)
        assertEquals("active", d.stage)
        assertEquals("в работе", d.stageWord)
        assertEquals(50_000_000L, d.feeKop)
        assertEquals(25_000_000L, d.toGetKop)
        assertEquals(90, d.pEff)
        assertEquals(1, d.nextTask!!.num)
        assertEquals("Клиент живой, платит вовремя", d.myView)
        val t = v.open.single()
        assertEquals("Иван: прислать модель", t.title)
        assertEquals(d.id, t.dealId)
        assertEquals(Dela.WAITING, t.ball)
        assertTrue(v.done.isEmpty())
        val pay = v.payments.single()
        assertEquals("2026-09-15", pay.paidOn)
        assertEquals(1, v.timeline.size)
        assertTrue(v.history.single().contains("заведена"))
        assertNull(v.minutesAll)
    }

    @Test
    fun `связи — кому пора, дни рождения`() {
        val v = DelaCrm.ties(view("ties"))
        assertEquals("2026-10-05", v.today)
        val p = v.people.single()
        assertEquals("Ольга Смирнова", p.name)
        assertEquals("quarter", p.cadence)
        assertEquals("раз в квартал", DelaCrm.CADENCE[p.cadence])
        assertEquals(40, p.sinceDays)
        assertFalse(p.due)
        assertEquals(-50, p.overdueDays)
        assertEquals(1, p.agenda)
        assertEquals(5, p.birthdayIn)
        assertTrue(v.due.isEmpty())
        assertEquals(listOf(p), v.rest)
        assertEquals(listOf(p), v.birthdays)
        // birthday_in есть только у тех, у кого записан день рождения: нет поля — не в «днях рождения».
        val noBd = JSONObject(view("ties").toString())
        noBd.getJSONArray("people").getJSONObject(0).remove("birthday_in")
        noBd.getJSONArray("people").getJSONObject(0).put("due", true)
        val v2 = DelaCrm.ties(noBd)
        assertNull(v2.people.single().birthdayIn)
        assertTrue(v2.birthdays.isEmpty())
        assertEquals(1, v2.due.size)
    }

    @Test
    fun `досье человека — его сделки и хронология`() {
        val v = DelaCrm.dossier(view("dossier"))
        assertEquals("Альфа: фонды", v.deals.single().name)
        assertEquals("Созвон с Иваном: фонды готовы смотреть, нужен тизер к пятнице", v.timeline.single().summary)
    }

    @Test
    fun `убранная запись хронологии не показывается`() {
        val o = JSONObject(view("client").toString())
        o.getJSONArray("timeline").getJSONObject(0).put("deleted_at", "2026-10-05T10:00:00+03:00")
        assertTrue(DelaCrm.client(o).timeline.isEmpty())
    }

    // ------------------------------------------------------------ операции CRM

    private fun opsList(): Set<String> {
        val a = part1.getJSONArray("ops_list")
        val b = crm.getJSONArray("ops_list_additions")
        return (0 until a.length()).map { a.getString(it) }.toSet() + (0 until b.length()).map { b.getString(it) }
    }

    private fun examples(): List<JSONObject> {
        val a = crm.getJSONArray("ops_examples")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun keysOf(o: JSONObject) = o.keys().asSequence().toSet()

    @Test
    fun `операции CRM телефона — ровно форма контракта`() {
        val ex = examples()
        val list = opsList()
        val dealId = Dela.newId()

        val stage = DelaCrm.stageOp(dealId, "mandate")
        assertEquals(keysOf(ex[0]), keysOf(stage))
        assertEquals(keysOf(ex[0].getJSONObject("set")), keysOf(stage.getJSONObject("set")))

        val close = DelaCrm.closeOp(dealId, "lost", " выбрали других ")
        assertEquals(keysOf(ex[1].getJSONObject("set")), keysOf(close.getJSONObject("set")))
        assertEquals("выбрали других", close.getJSONObject("set").getString("lost_reason"))
        assertTrue(DelaCrm.closeOp(dealId, "won").getJSONObject("set").isNull("lost_reason"))
        assertEquals("active", DelaCrm.reopenOp(dealId).getJSONObject("set").getString("stage"))

        val paid = DelaCrm.paidOp(Dela.newId(), today)
        assertEquals(keysOf(ex[2]), keysOf(paid))
        assertEquals(keysOf(ex[2].getJSONObject("set")), keysOf(paid.getJSONObject("set")))
        assertEquals(setOf("invoiced_on"), keysOf(DelaCrm.invoicedOp(Dela.newId(), today).getJSONObject("set")))

        val add = DelaCrm.interactionOp("call", "Обсудили тизер", nowIso, projectId = Dela.newId(), dealId = dealId, personIds = listOf("p1", "p1"))
        assertEquals(keysOf(ex[4]), keysOf(add))
        // Поля записи — ровно поля контракта, с id телефона (запись видна до ответа и не удваивается).
        assertEquals(keysOf(ex[4].getJSONObject("data")), keysOf(add.getJSONObject("data")))
        assertEquals("phone", add.getJSONObject("data").getString("source"))
        assertEquals(1, add.getJSONObject("data").getJSONArray("person_ids").length())
        // Неизвестный вид — заметка, а не отказ сервера.
        assertEquals("note", DelaCrm.interactionOp("sms", "x", nowIso).getJSONObject("data").getString("kind"))

        assertEquals(keysOf(ex[5]), keysOf(DelaCrm.interactionDeleteOp("i1")))

        val person = DelaCrm.personOp("p1", cadence = "month", hub = true)
        assertEquals(keysOf(ex[6]), keysOf(person))
        assertEquals(keysOf(ex[6].getJSONObject("set")), keysOf(person.getJSONObject("set")))
        assertTrue(DelaCrm.personOp("p1", cadence = "").getJSONObject("set").isNull("cadence"))

        val next = DelaCrm.nextTaskOp("Иван: прислать список фондов", "pr1", dealId, "sasha")
        assertEquals(keysOf(ex[7]), keysOf(next))
        val task = next.getJSONObject("task")
        assertEquals(dealId, task.getString("deal_id"))
        assertEquals("pr1", task.getString("project_id"))
        // Источник дела — manual: phone база у дела не примет.
        assertEquals("manual", task.getString("source"))
        assertTrue(keysOf(task).minus(keysOf(ex[7].getJSONObject("task"))).isEmpty())

        for (o in listOf(stage, close, paid, add, person, next, DelaCrm.interactionDeleteOp("i1"))) {
            assertTrue(o.getString("op"), o.getString("op") in list)
            assertTrue(Dela.isUuid(o.getString("op_id")))
        }
    }

    @Test
    fun `запись хронологии — сегодня сейчас, другой день — полдень по Москве`() {
        val n = java.time.OffsetDateTime.parse(nowIso)
        assertEquals("2026-10-05T20:00+03:00", DelaCrm.atIso(today, today, n))
        assertEquals("2026-10-01T12:00:00+03:00", DelaCrm.atIso("2026-10-01", today, n))
    }

    // ------------------------------------------------------------ своё поверх кэша

    @Test
    fun `стадия, итог и возврат сделки видны сразу — как у триггера сервера`() {
        val d = DelaCrm.dealView(view("deal")).deal!!
        val mandate = DelaCrm.overlayDeal(d, listOf(DelaCrm.stageOp(d.id, "mandate")), today)
        assertEquals("mandate", mandate.stage)
        assertTrue(mandate.local)
        val lost = DelaCrm.overlayDeal(d, listOf(DelaCrm.closeOp(d.id, "lost", "выбрали других")), today)
        assertEquals("archive", lost.stage)
        assertEquals("lost", lost.outcome)
        assertEquals(today, lost.closedOn)
        assertEquals("проиграли", lost.stageWord)
        val back = DelaCrm.overlayDeal(lost, listOf(DelaCrm.reopenOp(d.id)), today)
        assertEquals("active", back.stage)
        assertEquals("", back.outcome)
        assertEquals("", back.lostReason)
        assertEquals("", back.closedOn)
        // Чужая сделка не трогается.
        assertEquals(d, DelaCrm.overlayDeal(d, listOf(DelaCrm.stageOp("other", "lead")), today))
        // Та же правка — и в копии синка.
        val s = snap()
        val v = Dela.overlay(s, listOf(DelaCrm.closeOp(d.id, "won")), "sasha", today, nowIso)
        assertEquals("archive", v.deals.getValue(d.id).stage)
        assertTrue(s.dealsOf(d.projectId).any { it.id == d.id })
        assertTrue(v.dealsOf(d.projectId).none { it.id == d.id })
    }

    @Test
    fun `оплата и человек — своя правка поверх`() {
        val s = snap()
        val pay = s.payments.values.single().copy(paidOn = "", invoicedOn = "")
        val list = DelaCrm.overlayPayments(listOf(pay), listOf(DelaCrm.invoicedOp(pay.id, today)))
        assertEquals(today, list.single().invoicedOn)
        assertTrue(list.single().local)
        val paid = DelaCrm.overlayPayments(list, listOf(DelaCrm.paidOp(pay.id, today)))
        assertEquals(today, paid.single().paidOn)

        val pid = s.people.keys.first()
        val v = Dela.overlay(s, listOf(DelaCrm.personOp(pid, cadence = "quarter", hub = true)), "sasha", today, nowIso)
        assertEquals("quarter", v.people.getValue(pid).cadence)
        assertTrue(v.people.getValue(pid).hub)
        val v2 = Dela.overlay(s.copy(payments = mapOf(pay.id to pay)), listOf(DelaCrm.paidOp(pay.id, today)), "sasha", today, nowIso)
        assertEquals(today, v2.payments.getValue(pay.id).paidOn)
    }

    @Test
    fun `хронология — своя запись сразу, убранная — сразу нет`() {
        val s = snap()
        val v = DelaCrm.client(view("client"))
        val pid = v.project!!.id
        val add = DelaCrm.interactionOp("call", "Позвонил Ивану", nowIso, projectId = pid)
        val other = DelaCrm.interactionOp("call", "Про другого клиента", nowIso, projectId = Dela.newId())
        val list = DelaCrm.overlayTimeline(v.timeline, listOf(add, other), s) { it.projectId == pid }
        assertEquals(listOf("Позвонил Ивану", "Созвон с Иваном: фонды готовы смотреть, нужен тизер к пятнице"), list.map { it.summary })
        assertTrue(list.first().local)
        // Сервер уже прислал ту же запись — не удваиваем.
        val echoed = v.timeline + DelaCrm.interaction(add.getJSONObject("data"))!!
        assertEquals(2, DelaCrm.overlayTimeline(echoed, listOf(add), s) { true }.size)
        val gone = DelaCrm.overlayTimeline(v.timeline, listOf(DelaCrm.interactionDeleteOp(v.timeline.single().id)), s) { true }
        assertTrue(gone.isEmpty())
    }

    @Test
    fun `слова CRM — деньги, давность, часы`() {
        assertEquals("250 000 ₽", DelaCrm.rub(25_000_000L))
        assertEquals("", DelaCrm.rub(null))
        assertEquals("2,7 млн", DelaCrm.rubShort(270_000_000L))
        assertEquals("15 млн", DelaCrm.rubShort(1_500_000_000L))
        assertEquals("350 тыс", DelaCrm.rubShort(35_000_000L))
        assertEquals("сегодня", DelaCrm.ago("2026-10-05T09:00:00+03:00", today))
        assertEquals("12 дн. назад", DelaCrm.ago("2026-09-23", today))
        assertEquals("01.08", DelaCrm.ago("2026-08-01", today))
        assertEquals("1 ч 20 мин", DelaCrm.hours(80))
        assertEquals("05.10.27", DelaAsk.ddmm("2027-10-05", today))
        assertNotNull(DelaCrm.STAGE["proposal"])
        assertEquals("КП", DelaCrm.STAGE["proposal"])
    }
}
