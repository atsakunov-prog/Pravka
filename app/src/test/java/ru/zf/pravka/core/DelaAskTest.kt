package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка Claude — разговор (16.10.2026, записи отладки 21–22; docs/dela-phone-29.md §2): память реплики несёт ids
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
}
