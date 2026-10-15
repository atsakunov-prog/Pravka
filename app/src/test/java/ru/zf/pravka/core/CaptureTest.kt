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
import ru.zf.pravka.core.prompts.PromptsZasechka
import ru.zf.pravka.core.prompts.SharedPrompts

/**
 * Захват на телефоне против контракта сервера (`server/contract/dela-capture.json`, шаг 0 петли): операции — ровно
 * поля сервера, ответы — его примерами; строку и кнопки телефон не сочиняет, только читает. Разойдись имя поля — сервер
 * ответил бы «ничего не узнал», а пилюля показала бы пустую строку.
 */
class CaptureTest {

    private fun file(path: String): File = listOf(File("../$path"), File(path)).first { it.isFile }
    private fun contract(): JSONObject = JSONObject(file("server/contract/dela-capture.json").readText())

    // ------------------------------------------------------------ операции

    @Test
    fun `capture_say — поля контракта`() {
        val example = contract().getJSONObject("say").getJSONObject("request")
        val op = Capture.sayOp("напомни в 12 в Контуре отправить счёт", "4f1e2d3c", Capture.LANE_AUTO, Capture.FROM_D,
            "2026-10-15T10:30:12+03:00", "Ромашка — так и пишется", opId = "8b3c")
        assertEquals(example.keys().asSequence().toSet(), op.keys().asSequence().toSet())
        assertEquals("capture.say", op.getString("op"))
        assertEquals("auto", op.getString("lane"))
        assertEquals("d", op.getString("from"))
        // Подсказки нет — поля нет: пустое сервер не ждёт.
        assertFalse(Capture.sayOp("купить магний", "r", Capture.LANE_RULE, Capture.FROM_SAY, "2026-10-15T10:30:12+03:00").has("hint"))
        // Длинная фраза — не длиннее того, что примет сервер.
        assertEquals(Capture.MAX_INPUT, Capture.sayOp("а".repeat(5000), "r", Capture.LANE_AUTO, Capture.FROM_D, "x").getString("text").length)
        // Описания полей контракта совпадают с ключами операции (кроме служебных op и op_id).
        val fields = contract().getJSONObject("say").getJSONObject("fields").keys().asSequence().toSet()
        assertEquals(fields, op.keys().asSequence().toSet() - setOf("op", "op_id"))
    }

    @Test
    fun `capture_move — поля контракта и выбор времени`() {
        val example = contract().getJSONObject("move").getJSONObject("request")
        val chip = Capture.Chip("Время", "time", listOf(Capture.Option("завтра 9:00", "tomorrow")))
        val op = Capture.moveFor("4f1e2d3c", 0, chip, chip.options[0], opId = "x")
        for (k in example.keys()) assertTrue("нет поля $k", op.has(k))
        assertEquals("time", op.getString("to"))
        assertEquals("tomorrow", op.getString("at"))
        // Тёзки заметки — кнопкой человека: person_id едет, at — нет.
        val twin = Capture.moveFor("r", 0, Capture.Chip("Иван Тестов", "person", personId = "p-1"))
        assertEquals("p-1", twin.getString("person_id"))
        assertFalse(twin.has("at"))
        // С 16.10.2026 тёзки — одна кнопка «Какой Иван ▾» со списком: человек — из выбранного варианта, вопрос — «Кто это?».
        val who = Capture.parse(JSONObject().put("ref", "r").put("status", "done").put("items", JSONArray().put(
            JSONObject().put("bucket", "note").put("line", "Заметка: x — у какого Ивана? · вопрос в «Подскажи»").put("chips", JSONArray()
                .put(JSONObject().put("label", "Какой Иван").put("move", "person").put("options", JSONArray()
                    .put(JSONObject().put("label", "Иван Тестов (ООО Ромашка)").put("person_id", "p-1"))
                    .put(JSONObject().put("label", "Иван Петров").put("person_id", "p-2"))))
                .put(JSONObject().put("label", "Отменить").put("move", "cancel"))))))!!
        val whoChip = who.items.single().chips.first()
        assertEquals(listOf("p-1", "p-2"), whoChip.options.map { it.personId })
        assertEquals("Кто это?", Capture.optionsQuestion(whoChip))
        assertEquals("Когда напомнить?", Capture.optionsQuestion(Capture.Chip("Время", "time", listOf(Capture.Option("через час", "hour")))))
        val picked = Capture.moveFor("r", 0, whoChip, whoChip.options[1])
        assertEquals("p-2", picked.getString("person_id"))
        assertEquals("person", picked.getString("to"))
        assertFalse(picked.has("at"))
        // Каждое «куда» кнопки — из словаря контракта.
        val tos = contract().getJSONObject("move").getJSONObject("to").keys().asSequence().toSet()
        for (to in listOf("cancel", "task", "time", "remind", "today", "now", "back", "idea", "note", "person", "keep_open", "retry", "back_task")) {
            assertTrue(to, to in tos)
        }
    }

    // ------------------------------------------------------------ ответы

    @Test
    fun `ответ правила — строка, кнопки и дело`() {
        val r = Capture.reply(contract().getJSONObject("say").getJSONObject("response_rule"))
        assertTrue(r is Capture.Reply.Ok)
        val res = (r as Capture.Reply.Ok).result
        assertEquals(Capture.Status.DONE, res.status)
        assertTrue(res.done)
        assertEquals("rule", res.by)
        assertEquals("Напомню сегодня в 12:00: отправить счёт в Контуре", Capture.line(res))
        val item = res.items.single()
        assertEquals("remind", item.bucket)
        assertEquals("2026-10-15T12:00:00+03:00", item.remindAt)
        assertEquals(listOf("Время", "Это дело", "Отменить"), item.chips.map { it.label })
        assertEquals(4, item.chips[0].options.size)
        assertTrue(item.chips[2].cancel)
        // У одной записи кнопки — прямо под строкой; списка нет: запись и есть строка.
        assertEquals(3, res.lineChips.size)
        assertTrue(Capture.rows(res).isEmpty())
        assertEquals("напомню", Capture.spoken(res))
    }

    @Test
    fun `ждёт модели, не узнало, отказ`() {
        val say = contract().getJSONObject("say")
        val pending = (Capture.reply(say.getJSONObject("response_pending")) as Capture.Reply.Ok).result
        assertEquals(Capture.Status.PENDING, pending.status)
        assertTrue(Capture.line(pending).startsWith("Записал «"))
        assertTrue(pending.lineChips.isEmpty())
        val none = (Capture.reply(say.getJSONObject("response_none")) as Capture.Reply.Ok).result
        assertEquals(Capture.Status.NONE, none.status)
        assertFalse(none.done)
        // Отказ сервера — его словами, а не общей фразой (правило 6 карты).
        val refused = Capture.reply(JSONObject().put("op_id", "x").put("ok", false).put("error", "захват: пусто — нечего раскладывать"))
        assertEquals("захват: пусто — нечего раскладывать", (refused as Capture.Reply.Refused).error)
        assertNull(Capture.reply(null))
    }

    @Test
    fun `сбой модели — Повторить и Дело потом верхнего уровня`() {
        val o = JSONObject().put("ref", "r").put("status", "error").put("error", "Claude не ответил").put("items", JSONArray())
            .put("head", "Не разложил: Claude не ответил — текст в Делах")
            .put("chips", JSONArray().put(JSONObject().put("label", "Повторить").put("move", "retry"))
                .put(JSONObject().put("label", "Дело потом").put("move", "back_task")))
        val r = Capture.parse(o)!!
        assertEquals(Capture.Status.ERROR, r.status)
        assertEquals(listOf("Повторить", "Дело потом"), r.lineChips.map { it.second.label })
        assertEquals("Не разложил: Claude не ответил — текст в Делах", Capture.line(r))
    }

    @Test
    fun `несколько записей — голова и строки, отмена всех`() {
        fun item(b: String, line: String) = JSONObject().put("bucket", b).put("line", line)
            .put("chips", JSONArray().put(JSONObject().put("label", "Отменить").put("move", "cancel")))
        val o = JSONObject().put("ref", "r1").put("status", "done").put("by", "model")
            .put("head", "Разложил 2: напоминание завтра в 9:00 · напоминание сегодня в 19:00")
            .put("items", JSONArray().put(item("remind", "Напомню завтра в 9:00: отправить Ивану модель"))
                .put(item("remind", "Напомню сегодня в 19:00: позвонить маме")))
        val r = Capture.parse(o)!!
        assertEquals("Разложил 2: напоминание завтра в 9:00 · напоминание сегодня в 19:00", Capture.line(r))
        assertEquals(2, Capture.rows(r).size)
        assertTrue("у нескольких записей кнопок под строкой нет — у каждой своя", r.lineChips.isEmpty())
        val undo = Capture.cancelAll(r)
        assertEquals(listOf(0, 1), undo.map { it.getInt("item") })
        assertTrue(undo.all { it.getString("op") == "capture.move" && it.getString("to") == "cancel" && it.getString("ref") == "r1" })
    }

    @Test
    fun `общие случаи правила — строки и кнопки в форме телефона`() {
        // Случаи сервера (capture-cases.json): каждый — строка и кнопки, которые телефон покажет, не меняя ни слова.
        val cases = JSONObject(file("server/contract/capture-cases.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val chips = c.optJSONArray("chips")?.let { a -> JSONArray().apply { for (k in 0 until a.length()) put(JSONObject().put("label", a.getString(k)).put("move", "m$k")) } }
            val o = JSONObject().put("ref", "r$i").put("status", "done").put("by", "rule").put("form", c.getString("form"))
                .put("head", c.getString("line"))
                .put("items", JSONArray().put(JSONObject().put("bucket", c.getString("bucket")).put("line", c.getString("line")).put("chips", chips ?: JSONArray())))
            val r = Capture.parse(o)!!
            assertEquals(c.getString("say"), c.getString("line"), Capture.line(r))
            assertEquals(c.getString("bucket"), r.items.single().bucket)
            if (chips != null) assertEquals(c.getString("say"), chips.length(), r.lineChips.size)
        }
    }

    @Test
    fun `медленная сеть — «Отправляю…», а не «нет связи»`() {
        // Проверка 16.10.2026: LTE с VPN и первый запрос после простоя давали «Нет связи с Делами» за 2,5 с, хотя запрос
        // был в пути. «Нет связи» — только когда проход синка упал; медленно — «строка придёт уведомлением».
        assertEquals("Отправляю «купить магний»…", Capture.sendingLine(" купить   магний "))
        assertEquals(Capture.OFFLINE_LINE, ru.zf.pravka.data.CaptureClient.Outcome(offline = true).line)
        assertEquals(Capture.SLOW_LINE, ru.zf.pravka.data.CaptureClient.Outcome(offline = true, slow = true).line)
        assertTrue(ru.zf.pravka.data.CaptureClient.PUSH_MAX_MS > ru.zf.pravka.data.CaptureClient.PUSH_WAIT_MS)
        val pending = Capture.Result("r", Capture.Status.PENDING, head = Capture.sendingLine("купить магний"), text = "купить магний")
        assertEquals("Отправляю «купить магний»…", Capture.line(pending))
    }

    // ------------------------------------------------------------ развилка и промпт

    @Test
    fun `правило сильнее Сонета`() {
        val tasks = SayFork.Route(SayFork.Kind.TASKS, "x")
        val entry = SayFork.Route(SayFork.Kind.ENTRY, "x")
        // Правило узнало — что бы ни сказал Сонет.
        assertEquals(SayFork.Way.RULE, SayFork.captureWay(true, entry, modelOn = true))
        assertEquals(SayFork.Way.RULE, SayFork.captureWay(true, null, modelOn = false))
        // Не узнало: «дела» — к серверу (модель там) или прежней Разноской; лента — лентой.
        assertEquals(SayFork.Way.SERVER, SayFork.captureWay(false, tasks, modelOn = true))
        assertEquals(SayFork.Way.PHONE, SayFork.captureWay(false, tasks, modelOn = false))
        assertEquals(SayFork.Way.ROUTE, SayFork.captureWay(false, entry, modelOn = true))
    }

    @Test
    fun `дорога захвата — в развилку один раз, перед временем`() {
        val say = PromptsZasechka.withCapture(PromptsZasechka.SAY_FORK)
        assertTrue(say.contains(PromptsZasechka.CAPTURE_MARK))
        assertTrue("блок — в голове под кэшем, до {NOW}", say.indexOf(PromptsZasechka.CAPTURE_MARK) < say.indexOf("{NOW}"))
        assertEquals("второй раз не вставляется", say, PromptsZasechka.withCapture(say))
        val fork = PromptsZasechka.withCapture(PromptsZasechka.FORK)
        assertTrue(fork.indexOf(PromptsZasechka.CAPTURE_MARK) in 0 until fork.indexOf("{NOW}"))
        // Свод владельца без {NOW} — блок в конце, не теряется.
        assertTrue(PromptsZasechka.withCapture("правила").endsWith(PromptsZasechka.CAPTURE_ROAD))
    }

    @Test
    fun `страница куда что — из APK, все живые фразы`() {
        val text = SharedPrompts.read(KudaChto.FILE)
        val blocks = KudaChto.blocks(text)
        val rows = blocks.filterIsInstance<KudaChto.Block.Row>()
        val says = rows.map { it.cells.first() }
        for (phrase in listOf("напомни в 12 в Контуре отправить счёт", "напомни, когда приеду домой, позвонить маме",
            "позвонить Ивану на неделе", "идея: предложить Ромашке косткаттинг", "Иван сказал, бюджет будет в январе", "позвонил Ольге")) {
            assertTrue(phrase, says.any { it.contains(phrase) })
        }
        assertTrue(rows.all { it.names.size == it.cells.size })
        // Одна страница (16.10): что сказать — куда ляжет — что увидишь — когда вернётся — как поправить.
        assertEquals(listOf("Что сказать", "Куда ляжет", "Что увидишь", "Когда вернётся", "Как поправить"), rows.first().names)
        assertEquals("Куда что", (blocks.first() as KudaChto.Block.Head).text)
        assertEquals(listOf("а " to false, "жирно" to true, " б" to false), KudaChto.spans("а **жирно** б"))
        assertNotNull(blocks.filterIsInstance<KudaChto.Block.Para>().firstOrNull { it.text.contains("Календарь — только встречи") })
    }
}
