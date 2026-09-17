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
    fun `дневной раз в сутки после часа запуска, недельный — по пятницам`() {
        val fri = at(2026, 9, 18, 4)  // пятница
        assertEquals(listOf("daily", "weekly"), NightReviewPolicy.dueKinds(fri, 3, 0L, 0L))
        // До часа запуска — ничего.
        assertEquals(emptyList<String>(), NightReviewPolicy.dueKinds(at(2026, 9, 18, 2), 3, 0L, 0L))
        // Сегодняшний дневной уже был (в том числе вручную) — только недельный.
        assertEquals(listOf("weekly"), NightReviewPolicy.dueKinds(fri, 3, at(2026, 9, 18, 3), 0L))
        // Четверг — недельного нет.
        assertEquals(listOf("daily"), NightReviewPolicy.dueKinds(at(2026, 9, 17, 5), 3, at(2026, 9, 16, 3), 0L))
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
    fun `вердикты и ответ владельца разбираются терпимо`() {
        val v = NightReviewPolicy.parseVerdicts("""{"verdicts":[{"id":"dict-1","verdict":"Approved","why":"есть повтор"},{"id":"dict-2","verdict":"maybe"}]}""")
        assertEquals("approve" to "есть повтор", v["dict-1"])
        assertEquals("unsure", v["dict-2"]!!.first)
        val plan = NightReviewPolicy.parseReply("""{"actions":[{"id":"dict-1","action":"revert"},{"id":"dict-2","action":"keep"},{"id":"dict-3","action":"apply"}],"answer":"Вернул губ."}""")
        assertEquals(listOf("dict-1" to "revert", "dict-3" to "apply"), plan.actions)
        assertEquals("Вернул губ.", plan.answer)
    }

    @Test
    fun `раз в неделю в свой день недели, не раньше чем через шесть дней`() {
        val cal = java.util.Calendar.getInstance()
        // Ближайшая суббота, 04:00.
        cal.set(java.util.Calendar.HOUR_OF_DAY, 4); cal.set(java.util.Calendar.MINUTE, 0)
        while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.SATURDAY) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val sat = cal.timeInMillis
        assertTrue(NightReviewPolicy.dueOnWeekday(sat, 3, 0L, java.util.Calendar.SATURDAY))
        assertFalse(NightReviewPolicy.dueOnWeekday(sat, 5, 0L, java.util.Calendar.SATURDAY))
        assertFalse(NightReviewPolicy.dueOnWeekday(sat + 86_400_000L, 3, 0L, java.util.Calendar.SATURDAY))
        // Запускали вручную в четверг — в субботу рано.
        assertFalse(NightReviewPolicy.dueOnWeekday(sat, 3, sat - 2 * 86_400_000L, java.util.Calendar.SATURDAY))
        assertTrue(NightReviewPolicy.dueOnWeekday(sat, 3, sat - 7 * 86_400_000L, java.util.Calendar.SATURDAY))
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
