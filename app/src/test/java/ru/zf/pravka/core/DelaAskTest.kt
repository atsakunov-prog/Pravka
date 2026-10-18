package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка Claude — разговор (16.10.2026, записи отладки 21–22; docs/dela-phone-32.md §2): память реплики несёт ids
 * заведённого и поправленного (сервер показывает их Claude свежими — «да, ставь» находит заведённое прошлой репликой),
 * под свежей репликой — кнопки ответа сервера, а вопрос без них — «Да» и «Нет» (одно правило с `talkAnswers` веба).
 * Данные выдуманные.
 */
class DelaAskTest {

    private val s = Dela.Snapshot()

    @Test
    fun `память реплики — ids заведённого и поправленного, только uuid и без повторов`() {
        val made = Dela.newId()
        val moved = Dela.newId()
        val r = DelaAsk.parse(JSONObject(
            "{\"route\": \"edit\", \"reply\": \"Завёл целью «Учу испанский» внутри «Расту в профессии». Переложить «Читаю по главе в день» внутрь?\", " +
                "\"tasks\": [{\"id\": \"$made\", \"num\": 61, \"title\": \"Учу испанский\", \"status\": \"open\"}], " +
                "\"changed\": [{\"id\": \"$moved\", \"num\": 5, \"title\": \"Читаю по главе в день\", \"before\": {}, \"after\": {}}, " +
                "{\"id\": \"$moved\", \"num\": 5, \"title\": \"Читаю по главе в день\", \"before\": {}, \"after\": {}}, " +
                "{\"id\": \"не-uuid\", \"num\": 6, \"title\": \"x\", \"before\": {}, \"after\": {}}]}",
        ))
        val memo = DelaAsk.memo(DelaAsk.Turn("в «Расту в профессии» поставь ещё «Учу испанский»", r), s)
        val ids = memo.getJSONArray("ids")
        assertEquals(listOf(made, moved), (0 until ids.length()).map { ids.getString(it) })
        // Ничего не завёл и не поправил — без ключа: старый сервер его и не ждёт.
        val talk = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"Сначала сверка.\"}"))
        assertFalse(DelaAsk.memo(DelaAsk.Turn("что важнее?", talk), s).has("ids"))
        // Не больше двадцати — как у сервера.
        val many = DelaAsk.Result("edit", "", emptyList(), (1..25).mapNotNull { Dela.task(JSONObject("{\"id\": \"${Dela.newId()}\", \"title\": \"Дело $it\"}")) },
            emptyList(), emptyList(), "")
        assertEquals(DelaAsk.TALK_IDS, DelaAsk.memo(DelaAsk.Turn("заведи", many), s).getJSONArray("ids").length())
    }

    @Test
    fun `кнопки ответа — с сервера, а вопрос без них — Да и Нет`() {
        val withAnswers = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"Переложить «Читаю по главе в день» внутрь?\", " +
            "\"answers\": [\"Да, переложи\", \"  Нет  \", \"\", 5, \"Только его\", \"Лишнее\"]}"))
        assertEquals(listOf("Да, переложи", "Нет", "Только его"), withAnswers.answers)
        assertEquals(listOf("Да, переложи", "Нет", "Только его"), DelaAsk.answersOf(DelaAsk.Turn("x", withAnswers)))
        val question = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"Перенести и сверку тоже? \"}"))
        assertEquals(listOf("Да", "Нет"), DelaAsk.answersOf(DelaAsk.Turn("x", question)))
        val plain = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"Перенёс на пятницу.\"}"))
        assertTrue(DelaAsk.answersOf(DelaAsk.Turn("x", plain)).isEmpty())
        // «Вернуть» нажато — кнопок нет; разговора нет — тоже.
        assertTrue(DelaAsk.answersOf(DelaAsk.Turn("x", question, undone = true)).isEmpty())
        assertTrue(DelaAsk.answersOf(null).isEmpty())
    }

    /**
     * «Это отдельное дело» (16.10.2026, запись отладки 23; docs/dela-phone-32.md §3; `splitNoteOps` веба — одно правило):
     * Claude дописал «Проговорить с ним Бету.» в заметку дела — кнопка заводит дело рядом (дописанное названием, «Зачем»,
     * клиент, сделка и человек дела, без срока) и убирает дописанное из заметки, с `was`; «Вернуть» — наоборот.
     */
    @Test
    fun `это отдельное дело — пачка create и notes с was, вернуть — наоборот`() {
        val tid = Dela.newId()
        val goal = Dela.newId()
        val p = Dela.newId()
        val d = Dela.newId()
        val ivan = Dela.newId()
        val r = DelaAsk.parse(JSONObject(
            "{\"route\": \"edit\", \"reply\": \"Переименовал, в заметку дописал.\", \"changed\": [{\"id\": \"$tid\", \"num\": 283, " +
                "\"title\": \"Дожать Ивана\", \"before\": {\"title\": \"Дожать Ивана\", \"notes\": \"После встречи не ответил.\"}, " +
                "\"after\": {\"title\": \"Проверить интерес Ивана\", \"notes\": \"После встречи не ответил.\\nПроговорить с ним Бету.\"}, " +
                "\"noted\": \"Проговорить с ним Бету.\"}]}",
        ))
        val ch = r.changed.single()
        assertEquals("Проговорить с ним Бету.", DelaAsk.notedOf(ch))
        assertTrue(DelaAsk.describe(ch, s).endsWith("дописал заметку: «Проговорить с ним Бету.»"))
        // Старый сервер без noted — хвост заметки за прежней; заметку не трогал — пусто.
        assertEquals("Проговорить с ним Бету.", DelaAsk.notedOf(ch.copy(noted = "")))
        assertEquals("", DelaAsk.notedOf(ch.copy(after = JSONObject().put("title", "x"), noted = "")))

        val t = Dela.Task(id = tid, title = "Проверить интерес Ивана", notes = "После встречи не ответил.\nПроговорить с ним Бету.",
            projectId = p, dealId = d, personId = ivan, whyId = goal, dueDate = "2026-10-19")
        val sp = Dela.splitNoteOps(DelaAsk.notedOf(ch), t)!!
        assertEquals("Проговорить с ним Бету", sp.title)
        val (create, set) = sp.ops
        val task = create.getJSONObject("task")
        assertEquals("task.create", create.getString("op"))
        assertEquals(listOf(sp.id, "Проговорить с ним Бету", goal, p, d, ivan),
            listOf("id", "title", "why_id", "project_id", "deal_id", "person_id").map { task.getString(it) })
        assertFalse(task.has("due_date"))  // срок сказан для самого дела
        assertEquals(listOf("task.set", tid), listOf(set.getString("op"), set.getString("id")))
        assertEquals("После встречи не ответил.", set.getJSONObject("set").getString("notes"))
        assertEquals(t.notes, set.getJSONObject("was").getString("notes"))
        val (cancel, back) = sp.back
        assertEquals(listOf("task.cancel", sp.id), listOf(cancel.getString("op"), cancel.getString("id")))
        assertEquals(t.notes, back.getJSONObject("set").getString("notes"))

        // Заметка — только дописанное: обратно пустая (null); дописанного в ней уже нет — заметку не трогаем.
        val only = Dela.splitNoteOps("Проговорить с ним Бету.", t.copy(notes = "Проговорить с ним Бету."))!!
        assertTrue(only.ops[1].getJSONObject("set").isNull("notes"))
        assertEquals(1, Dela.splitNoteOps("Проговорить с ним Бету.", t.copy(notes = "Поправил руками."))!!.ops.size)
        // У цели — её шагом; сервер без дерева — без why_id.
        assertEquals(tid, Dela.splitNoteOps("позвонить Ольге", t.copy(goal = true, whyId = ""))!!.ops[0].getJSONObject("task").getString("why_id"))
        assertFalse(Dela.splitNoteOps("позвонить Ольге", t, tree = false)!!.ops[0].getJSONObject("task").has("why_id"))
        assertEquals("Позвонить Ольге", Dela.splitTitle("  позвонить   Ольге ;. "))
        assertEquals(null, Dela.splitNoteOps(" . ", t))
        assertEquals("а\nв", Dela.noteCut("а\nб\nв", "б"))

        // Отделено — «Вернуть» реплики отменяет и его, память реплики говорит о нём Claude.
        val turn = DelaAsk.Turn("…и плюс Бету проговорить с ним", r, split = mapOf(tid to sp))
        assertTrue(DelaAsk.undoOps(turn).any { it.getString("op") == "task.cancel" && it.getString("id") == sp.id })
        val memo = DelaAsk.memo(turn, s)
        assertTrue(memo.getString("done").contains("отдельным делом: Проговорить с ним Бету"))
        assertTrue(sp.id in (0 until memo.getJSONArray("ids").length()).map { memo.getJSONArray("ids").getString(it) })
    }
}
