package ru.zf.pravka.core

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.NightReviewStore.Change

// Ночной разбор: расписание, разбор ответов модели и правило «применять
// само» (docs/agreements.md, «Ночной разбор правит словарь сам»).
class NightReviewPolicyTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis

    @Test
    fun `дневной раз в сутки после часа запуска, в пятницу — только недельный`() {
        val fri = at(2026, 9, 18, 4)  // пятница
        val prevFri = at(2026, 9, 11, 3)  // прошлый недельный
        // В пятницу дневного нет: недельный смотрит те же сутки в составе недели.
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(fri, 3, 0L, prevFri))
        // До часа запуска — ничего (прошлый недельный был неделю назад, вовремя).
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(at(2026, 9, 18, 2), 3, 0L, prevFri))
        // Недельный сегодня уже был (в том числе вручную) — ничего.
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(fri, 3, 0L, at(2026, 9, 18, 3)))
        // Четверг — дневной, недельного нет.
        assertEquals(listOf("daily"), NightReviewPolicy.dueKinds(at(2026, 9, 17, 5), 3, at(2026, 9, 16, 3), prevFri))
        // Сегодняшний дневной уже был (в том числе вручную) — ночью не повторяем.
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(at(2026, 9, 17, 5), 3, at(2026, 9, 17, 3), prevFri))
        // Тумблер суток выключен (заводское): в четверг ничего, в пятницу недельный как обычно.
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(at(2026, 9, 17, 5), 3, 0L, prevFri, dailyEnabled = false))
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(fri, 3, 0L, prevFri, dailyEnabled = false))
    }

    @Test
    fun `пропущенная пятница догоняется при первом тике, а не через неделю`() {
        // 25.09 потолок трат не пустил недельный весь день — в субботу он идёт сам.
        val lastWeekly = at(2026, 9, 18, 3)
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(at(2026, 9, 26, 0), 3, 0L, lastWeekly, dailyEnabled = false))
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(at(2026, 9, 28, 14), 3, 0L, lastWeekly, dailyEnabled = false))
        // Догнали в субботу — до следующей пятницы тихо.
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(at(2026, 9, 30, 14), 3, 0L, at(2026, 9, 26, 0), dailyEnabled = false))
        // Свежая установка: недельного не было никогда — идёт сразу.
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(at(2026, 9, 30, 14), 3, 0L, 0L, dailyEnabled = false))
        // Последний срок — ровно пятница в час запуска.
        assertEquals(at(2026, 9, 25, 3), NightReviewPolicy.lastMark(at(2026, 9, 28, 14), 3, Calendar.FRIDAY))
        assertEquals(at(2026, 9, 18, 3), NightReviewPolicy.lastMark(at(2026, 9, 25, 2), 3, Calendar.FRIDAY))
    }

    @Test
    fun `окно «разобрать сейчас» — от прошлого разбора, от суток до недели`() {
        val now = at(2026, 10, 12, 22)
        val day = 86_400_000L
        assertEquals(now - 3 * day to now, NightReviewPolicy.windowNow(now, now - 3 * day))
        assertEquals(now - day to now, NightReviewPolicy.windowNow(now, now - 2 * 3_600_000L))
        assertEquals(now - 7 * day to now, NightReviewPolicy.windowNow(now, 0L))
        assertTrue(NightReviewPolicy.weekLike("now", now - 3 * day, now))
        assertFalse(NightReviewPolicy.weekLike("now", now - day, now))
        assertTrue(NightReviewPolicy.weekLike("weekly", now - day, now))
    }

    @Test
    fun `сеть и перегрузка не закрывают прогон, остальное — закрывает`() {
        assertTrue(NightReviewPolicy.isTransient(java.net.UnknownHostException("Unable to resolve host \"api.anthropic.com\"")))
        assertTrue(NightReviewPolicy.isTransient(RuntimeException("обёртка", java.net.SocketTimeoutException("timeout"))))
        assertTrue(NightReviewPolicy.isTransient(java.io.IOException("close failed: EIO (I/O error)")))
        assertTrue(NightReviewPolicy.isTransient(Exception("Anthropic ответил 529: Overloaded")))
        assertTrue(NightReviewPolicy.isTransient(Exception("Anthropic ответил 429: rate limit")))
        assertFalse(NightReviewPolicy.isTransient(Exception("Anthropic ответил 400: max_tokens too large")))
        assertFalse(NightReviewPolicy.isTransient(IllegalStateException("батч не завершился за сутки")))
    }

    @Test
    fun `ответ первого прохода — изменения с id, удаление превращается в выключение`() {
        val raw = """Вот: {"summary": "Полли 12 раз.", "changes": [
            {"kind": "dict_add", "mode": "protect", "from": "Полли", "why": "12 раз", "confidence": "HIGH"},
            {"kind": "dict_delete", "mode": "HARD", "from": "губ", "to": "ютуб", "why": "живое слово", "confidence": "high"},
            {"kind": "prompt_edit", "why": "нельзя"},
            {"kind": "dict_add", "mode": "HARD", "from": "", "to": "x"},
            {"kind": "note", "why": "модель удалила «не» 3 раза", "confidence": "low"}
        ]}"""
        val a = NightReviewPolicy.parseAnalysis(raw, "dict")
        assertEquals("Полли 12 раз.", a.summary)
        assertEquals(listOf("dict-1", "dict-2", "dict-3"), a.changes.map { it.id })
        assertEquals("PROTECT", a.changes[0].mode)
        assertEquals("high", a.changes[0].confidence)
        assertEquals("dict_disable", a.changes[1].kind)
        assertEquals("note", a.changes[2].status)
    }

    @Test
    fun `вердикты разбираются терпимо`() {
        val v = NightReviewPolicy.parseVerdicts("""{"verdicts":[{"id":"dict-1","verdict":"Approved","why":"есть повтор"},{"id":"dict-2","verdict":"maybe"}]}""")
        assertEquals("approve" to "есть повтор", v["dict-1"])
        assertEquals("unsure", v["dict-2"]!!.first)
    }

    @Test
    fun `раз в неделю в свой день недели, не раньше чем через шесть дней, с догоном`() {
        val sat = at(2026, 10, 10, 4)  // суббота
        val prev = at(2026, 10, 3, 3)  // прошлая правка
        assertTrue(NightReviewPolicy.dueOnWeekday(sat, 3, prev, Calendar.SATURDAY))
        // До часа запуска — ещё прошлая неделя, она уже отработана.
        assertFalse(NightReviewPolicy.dueOnWeekday(sat, 5, at(2026, 10, 3, 5), Calendar.SATURDAY))
        // Пропустили субботу — воскресенье догоняет.
        assertTrue(NightReviewPolicy.dueOnWeekday(sat + 86_400_000L, 3, prev, Calendar.SATURDAY))
        // Запускали вручную в четверг — в субботу рано.
        assertFalse(NightReviewPolicy.dueOnWeekday(sat, 3, sat - 2 * 86_400_000L, Calendar.SATURDAY))
        // Сегодня уже была — ничего.
        assertFalse(NightReviewPolicy.dueOnWeekday(sat + 3_600_000L, 3, sat, Calendar.SATURDAY))
    }

    @Test
    fun `совет заметки читается из advice, слово ищется целиком`() {
        val a = NightReviewPolicy.parseAnalysis(
            """{"summary": "s", "changes": [
              {"kind": "note", "why": "модель теряет «не»", "advice": "усилить правило 6 промпта CLEAN", "confidence": "low"},
              {"kind": "dict_add", "mode": "HINT", "from": "поле", "to": "Полли", "note": "в рассказах", "why": "w", "confidence": "high"}]}""",
            "n",
        )
        assertEquals("усилить правило 6 промпта CLEAN", a.changes[0].note)
        assertEquals("в рассказах", a.changes[1].note)

        val texts = "<d>сказал Мора и ушёл</d>\n<m>Сказал Мора и ушёл.</m>"
        assertTrue(NightReviewPolicy.mentioned("мора", texts))
        assertFalse(NightReviewPolicy.mentioned("Мор", texts))
        assertFalse(NightReviewPolicy.mentioned("Ви", texts))
        assertFalse(NightReviewPolicy.mentioned("", texts))
    }

    @Test
    fun `лог для Claude Code — идеи с советами, применённое, предложенное, возвращённое`() {
        val run = ru.zf.pravka.data.NightReviewStore.Run(
            id = 1L, kind = NightReviewPolicy.DAILY, startedAt = 1000L, fromMs = 0L, toMs = 1000L, stage = "done", costUsd = 0.5,
            summary = "Применено 1, предложено 1.\n\nМодель путает род.",
            changes = listOf(
                Change(id = "n-1", kind = "note", why = "теряет «не»", note = "усилить правило 6", status = "note"),
                Change(id = "n-2", kind = "dict_add", mode = "HARD", from = "ебитда", to = "EBITDA", why = "5 раз", status = "applied"),
                Change(id = "n-3", kind = "dict_mode", mode = "HARD", toMode = "HINT", from = "губ", why = "живое слово", verdictWhy = "спорно", status = "proposed"),
                Change(id = "n-4", kind = "dict_add", mode = "HINT", from = "поле", to = "Полли", why = "w", status = "reverted"),
                Change(id = "n-5", kind = "dict_add", mode = "HINT", from = "x", to = "y", why = "w", status = "dropped"),
            ),
        )
        val log = NightReviewPolicy.exportLog(listOf(run), { "д$it" }, nowMs = 2000L)
        assertTrue(log, log.contains("### Идеи по приложению\n- теряет «не»\n  - Совет: усилить правило 6"))
        assertTrue(log, log.contains("### Применено\n- HARD: ебитда → EBITDA — 5 раз"))
        assertTrue(log, log.contains("### Предложено (владелец не решил)\n- губ: HARD → HINT — живое слово [спорно]"))
        assertTrue(log, log.contains("### Вернул владелец\n- HINT: поле → Полли"))
        assertTrue(log, log.contains("### Сводка разбора\nМодель путает род."))
        assertFalse(log, log.contains("x → y"))
        assertTrue(log, log.contains("отброшено низких 1"))
    }

    @Test
    fun `само применяется всё high, кроме отклонённого и HARD на короткое слово`() {
        val ok = Change(id = "d-1", kind = "dict_add", mode = "PROTECT", from = "Полли", confidence = "high", verdict = "approve")
        assertTrue(NightReviewPolicy.autoApply(ok))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(confidence = "low")))
        // Владелец: «применял всё, за исключением низковероятного» — unsure проверки не мешает.
        assertTrue(NightReviewPolicy.autoApply(ok.copy(verdict = "unsure")))
        assertTrue(NightReviewPolicy.autoApply(ok.copy(verdict = "")))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(verdict = "reject")))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(verdict = "hold")))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(kind = "note")))
        // «губ → ютуб» как HARD — только предложением, даже с двумя одобрениями.
        assertFalse(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HARD", from = "губ", to = "ютуб")))
        assertTrue(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HARD", from = "телепромутр", to = "телепромптер")))
        assertTrue(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HINT", from = "губ", to = "ютуб")))
        assertTrue(NightReviewPolicy.shortCyrillicWord("прод"))
        assertFalse(NightReviewPolicy.shortCyrillicWord("lifans"))
    }
}
