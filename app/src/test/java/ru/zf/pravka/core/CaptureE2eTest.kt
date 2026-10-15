package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Захват сквозь сервер — дорогой телефона, на записанном прогоне тестовой копии Дел (15.10.2026, шаг 0 петли;
 * `src/test/resources/capture-e2e.json`, данные выдуманные, $0 — только правило): «сказать» — `capture.say lane: rule`
 * мимо очереди, «Д» — `lane: auto` очередью, кнопки — `capture.move`, потом синк. Телефон должен прочитать каждый ответ
 * сервера (строку и кнопки — не меняя ни слова), а его копия после синка — показать то же, что вид `today` сервера:
 * напоминание на «Сегодня» в свой час, «Вернулось» в свой день, идею у клиента, и ни одного напоминания в списках дел.
 */
class CaptureE2eTest {

    private val run: JSONObject by lazy {
        JSONObject(CaptureE2eTest::class.java.getResourceAsStream("/capture-e2e.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }
    private val runs: List<JSONObject> by lazy { run.getJSONArray("runs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    private val snap: Dela.Snapshot by lazy { Dela.merge(Dela.Snapshot(), run.getJSONObject("sync"), 0L) }
    private val me: String get() = run.getString("me")
    private val today: String get() = snap.today

    private fun said(text: String, lane: String): Capture.Result =
        (Capture.reply(runs.first { it.optString("say") == text && it.optString("lane") == lane }.getJSONObject("reply")) as Capture.Reply.Ok).result

    private fun moved(to: String): Capture.Result =
        (Capture.reply(runs.first { it.optString("move") == to }.getJSONObject("reply")) as Capture.Reply.Ok).result

    private fun titles(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("title") } }.orEmpty()

    @Test
    fun `каждый ответ сервера — строка и кнопки телефона`() {
        for (r in runs) {
            val reply = Capture.reply(r.optJSONObject("reply")) ?: Capture.parse(r.getJSONObject("reply").optJSONObject("capture"))?.let { Capture.Reply.Ok(it) }
            assertTrue(r.toString().take(200), reply is Capture.Reply.Ok)
            val res = (reply as Capture.Reply.Ok).result
            if (res.status == Capture.Status.NONE) continue
            assertTrue("строка пустая: ${r.optString("say")}", Capture.line(res).isNotBlank())
            for (x in res.items) assertTrue(x.chips.size <= 3)
        }
    }

    @Test
    fun `шесть живых фраз — куда легло и что видно`() {
        val remind = said("напомни в 12 в Контуре отправить счёт", "rule")
        assertTrue(remind.done)
        assertEquals("rule", remind.by)
        assertEquals("remind", remind.items.single().bucket)
        assertEquals(listOf("Это дело", "Время", "Отменить"), remind.lineChips.map { it.second.label })
        assertTrue(Capture.line(remind).startsWith("Напомню "))
        assertTrue(Capture.line(remind).endsWith(": отправить счёт в Контуре"))
        assertEquals("place", said("напомни, когда приеду домой, позвонить маме", "auto").items.single().bucket)
        assertEquals("Напомню, когда приедешь домой: позвонить маме", Capture.line(said("напомни, когда приеду домой, позвонить маме", "auto")))
        assertEquals("back", said("позвонить Ивану на неделе", "auto").items.single().bucket)
        assertEquals("idea", said("идея: предложить Ромашке косткаттинг", "auto").items.single().bucket)
        assertEquals("note", said("Иван сказал, бюджет будет в январе", "auto").items.single().bucket)
        val did = said("позвонил Ольге", "auto")
        assertEquals("did", did.items.single().bucket)
        assertEquals(listOf("Не закрывать", "Отменить"), did.lineChips.map { it.second.label })
        // Правило не узнало («созвон с Женей…» — существительное первым): ничего не записано, решает развилка телефона.
        assertEquals(Capture.Status.NONE, said("созвон с Женей завтра в 12:30", "rule").status)
    }

    @Test
    fun `повтор того же ref — прежний итог, второго дела нет`() {
        val first = said("напомни в 12 в Контуре отправить счёт", "rule")
        val again = (Capture.reply(runs.first { it.optString("say") == "повтор того же ref" }.getJSONObject("reply")) as Capture.Reply.Ok).result
        assertEquals(first.ref, again.ref)
        assertEquals(first.items.single().taskId, again.items.single().taskId)
        assertEquals(1, snap.tasks.values.count { it.title == "Отправить счёт в Контуре" })
    }

    @Test
    fun `кнопки поправки — новая строка на месте`() {
        val time = moved("time")
        assertTrue(Capture.line(time).startsWith("Переложил: Напомню завтра в 9:00"))
        assertEquals("remind", time.items.single().bucket)
        val now = moved("now")
        assertEquals("now", now.items.single().bucket)
        assertTrue(Capture.line(now).contains("В «Сейчас»"))
        val cancel = moved("cancel")
        assertEquals("cancelled", cancel.items.single().bucket)
        assertTrue("у отменённого кнопок нет", cancel.lineChips.isEmpty())
        val keep = moved("keep_open")
        assertEquals(listOf("Отменить"), keep.lineChips.map { it.second.label })
        // Вид capture?ref= (опрос телефона) — то же, что ответ последней кнопки.
        val view = Capture.parse(runs.first { it.has("view") }.getJSONObject("reply").getJSONObject("capture"))!!
        assertEquals(Capture.line(time), Capture.line(view))
    }

    @Test
    fun `копия после синка — то же, что вид today сервера`() {
        assertTrue(snap.captureOn && snap.captureModelOn && snap.ideasOn && snap.remindPhoneOn)
        val tv = run.getJSONObject("today_view")
        assertEquals(titles(tv.optJSONArray("reminders")), DelaGroups.remindersToday(snap, me, today).map { it.title })
        assertEquals(titles(tv.optJSONArray("back")), DelaGroups.backToday(snap, me, today).map { it.title })
        // Отменённая кнопкой духовка — нигде: ни в напоминаниях, ни в будильнике.
        assertTrue(DelaGroups.remindersAll(snap, me).none { it.title == "Выключить духовку" })
        // Напоминаний нет в списках дел; «потом» и «Сейчас» — дела.
        val inLists = DelaViews.list(snap, me, "all", showDone = false).map { it.title }.toSet()
        assertFalse("Отправить счёт в Контуре" in inLists)
        assertFalse("Позвонить маме" in inLists)
        assertTrue("Позвонить Ивану" in inLists)
        assertEquals(listOf("Купить магний"), DelaGroups.nowOrdered(snap, me, today).filter { it.title == "Купить магний" }.map { it.title })
        // «Позвонить Ивану на неделе» — метка «↩» и «Вернулось» в свой день.
        val ivan = snap.tasks.values.first { it.title == "Позвонить Ивану" }
        assertTrue(DelaViews.backChip(ivan, today)!!.startsWith("↩ "))
        assertEquals(listOf("Позвонить Ивану"), DelaGroups.backToday(snap, me, ivan.backOn).map { it.title })
        // «Позвонил Ольге» — «Не закрывать» вернул обещание открытым.
        assertTrue(snap.tasks.values.first { it.title == "Позвонить Ольге" }.open)
        // Идея — у клиента, у его единственной сделки.
        val idea = snap.ideas.values.single()
        assertEquals(idea, snap.ideasFor(projectId = idea.projectId).single())
        assertEquals(idea, snap.ideasFor(dealId = idea.dealId).single())
    }

    @Test
    fun `будильник — на миг напоминания, по месту — на приезде`() {
        val bill = snap.tasks.values.first { it.title == "Отправить счёт в Контуре" }
        val at = ReminderPlan.atMs(bill)!!
        assertEquals(at, ReminderPlan.next(snap, me, at - 60_000L, emptySet()))
        assertEquals(listOf(bill.id), ReminderPlan.due(snap, me, at + 1_000L, emptySet()).map { it.task.id })
        val mom = snap.tasks.values.first { it.title == "Позвонить маме" }
        assertTrue(mom.remind)
        assertTrue(ReminderPlan.due(snap, me, at + 1_000L, emptySet()).none { it.task.id == mom.id })
        val s = snap.copy(features = snap.features + Dela.FEATURE_REMIND)
        val ops = DelaRemind.arrivalOps(s, me, mom.remindPlace, DelaRemind.now(at, java.time.ZoneId.of("Europe/Moscow")))
        val arrived = Dela.overlay(s, ops, me, today, DelaRemind.now(at, java.time.ZoneId.of("Europe/Moscow")))
        assertTrue(ReminderPlan.due(arrived, me, at + 1_000L, emptySet()).any { it.task.id == mom.id })
    }
}
