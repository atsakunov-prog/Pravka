package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Захват сквозь сервер С НАСТОЯЩЕЙ МОДЕЛЬЮ — на записанных ответах сквозного теста шага 0 (15.10.2026, тестовая копия
 * Дел, выдуманные люди; `src/test/resources/capture-e2e-model.json`). Фразы, которых правило не узнало, «Д» шлёт
 * `capture.say lane: auto`: за секунду — строка «Записал «…» — раскладываю» (её телефон рисует и сам, до ответа), через
 * время модели — итог вида `capture?ref=`. Телефон должен показать ровно строку сервера (одна правда), у нескольких
 * записей — «Разложил N» и список по тапу, а голосом «З» — короткое слово по корзине.
 */
class CaptureE2eModelTest {

    private val run: JSONObject by lazy {
        JSONObject(CaptureE2eModelTest::class.java.getResourceAsStream("/capture-e2e-model.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }
    private val runs: List<JSONObject> by lazy { run.getJSONArray("runs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }

    private fun said(text: String): Capture.Result =
        (Capture.reply(runs.first { it.optString("say") == text }.getJSONObject("reply")) as Capture.Reply.Ok).result

    private fun buckets(r: Capture.Result) = r.items.map { it.bucket }

    @Test
    fun `каждый ответ модели — строка сервера без изменений, ожидание — та же строка, что у сервера`() {
        assertTrue(runs.size >= 10)
        for (r in runs) {
            val res = (Capture.reply(r.getJSONObject("reply")) as Capture.Reply.Ok).result
            val say = r.getString("say")
            assertTrue(say, res.done)
            assertEquals(say, "model", res.by)
            assertEquals(say, res.head, Capture.line(res))
            // Пока модель раскладывает, телефон пишет то же, что ответил сервер за секунду.
            assertEquals(say, r.getString("first_line"), Capture.pendingLine(say))
            if (res.items.size > 1) {
                assertTrue(say, res.head.startsWith("Разложил ${res.items.size}: "))
                assertEquals(say, res.items.map { it.line }, Capture.rows(res))
                assertTrue("у нескольких записей кнопки — у каждой своя", res.lineChips.isEmpty())
                assertEquals("разложил", Capture.spoken(res))
            } else {
                assertEquals(say, res.items.single().line, res.head)
                assertTrue(say, res.lineChips.size in 1..3)
            }
        }
    }

    @Test
    fun `корзины пяти фраз проекта и свободных фраз`() {
        assertEquals(listOf("remind", "remind"), buckets(said("напомни завтра утром отправить Ивану модель и позвонить маме вечером")))
        assertEquals(listOf("day", "waiting"), buckets(said("Наташе подготовить сверку до пятницы, жду от Ивана модель")))
        assertEquals(listOf("did", "day"), buckets(said("позвонил Ольге, договорились на пятницу подготовить договор")))
        // Без слова «идея» — дело «потом», не идея.
        assertEquals(listOf("back"), buckets(said("надо бы Ромашке предложить дашборд")))
        val olga = said("Ольга говорит, подпишут в среду, надо подготовить счёт")
        assertEquals(listOf("day", "note"), buckets(olga))
        // День в заметке — словами, как сказано, без даты в скобках (перепрогон моделью 16.10.2026).
        assertTrue(olga.items[1].line, olga.items[1].line.contains("в среду") && !olga.items[1].line.contains("(20"))
        // «в 12 … не забыть» — напоминание (один миг), а не дело в куче.
        val bill = said("короче в 12 Контур счёт отправить не забыть")
        assertEquals(listOf("remind"), buckets(bill))
        assertTrue(Capture.line(bill).startsWith("Напомню "))
        assertEquals("", bill.items.single().dueDate)
        // «Напомни Наташе про сверку» — напомнить ей самому, а не «жду» от неё.
        val office = said("как приеду в офис, напомни Наташе про сверку")
        assertEquals(listOf("place"), buckets(office))
        assertEquals("Напомню, когда приедешь в офис: напомнить Наташе про сверку", Capture.line(office))
        assertEquals("напомню", Capture.spoken(office))
        // Обещание человеку и обещание человека — чьё, видно в строке; человек не повторяется.
        val promised = said("сказал Ивану, что пришлём модель к среде")
        assertEquals(listOf("day"), buckets(promised))
        assertTrue(Capture.line(promised), Capture.line(promised).startsWith("Ждёт от тебя Иван Петров: прислать модель · "))
        assertTrue(Capture.line(said("Иван обещал прислать отчёт до пятницы")).startsWith("Жду от Ивана Петрова: прислать отчёт · "))
        assertTrue(Capture.line(said("завтра созвон с Иваном в 15:00 по бюджету")).endsWith(" · в 15:00"))
        // Проверка 16.10.2026: правило с ними молчит — раскладывает модель, глагол не теряется.
        assertEquals("Заметка у Ивана Петрова: Иван хочет скидку · в карточке", Capture.line(said("Иван хочет скидку")))
        assertEquals(listOf("waiting"), buckets(said("Иван обещал отчёт")))
        assertEquals(listOf("back"), buckets(said("когда буду свободен, позвонить Ивану")))
    }

    @Test
    fun `шесть живых фраз моделью мимо правила — те же корзины, что у правила`() {
        val control = run.getJSONArray("control").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val want = mapOf(
            "напомни в 12 в Контуре отправить счёт" to "remind",
            "напомни, когда приеду домой, позвонить маме" to "place",
            "позвонить Ивану на неделе" to "back",
            "идея: предложить Ромашке косткаттинг" to "idea",
            "Иван сказал, бюджет будет в январе" to "note",
            "позвонил Ольге" to "did",
        )
        assertEquals(want.keys, control.map { it.getString("say") }.toSet())
        for (c in control) {
            val res = Capture.parse(c.getJSONObject("capture"))!!
            assertEquals(c.getString("say"), listOf(want[c.getString("say")]), buckets(res))
            assertEquals(res.head, Capture.line(res))
        }
        val note = Capture.parse(control.first { it.getString("say").startsWith("Иван сказал") }.getJSONObject("capture"))!!
        assertEquals("Заметка у Ивана Петрова: бюджет будет в январе · в карточке", Capture.line(note))
    }
}
