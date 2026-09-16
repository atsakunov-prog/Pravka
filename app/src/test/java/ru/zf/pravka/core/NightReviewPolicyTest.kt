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
    fun `само применяется только high + approve, и не HARD на короткое слово`() {
        val ok = Change(id = "d-1", kind = "dict_add", mode = "PROTECT", from = "Полли", confidence = "high", verdict = "approve")
        assertTrue(NightReviewPolicy.autoApply(ok))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(confidence = "low")))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(verdict = "unsure")))
        assertFalse(NightReviewPolicy.autoApply(ok.copy(kind = "note")))
        // «губ → ютуб» как HARD — только предложением, даже с двумя одобрениями.
        assertFalse(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HARD", from = "губ", to = "ютуб")))
        assertTrue(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HARD", from = "телепромутр", to = "телепромптер")))
        assertTrue(NightReviewPolicy.autoApply(ok.copy(kind = "dict_add", mode = "HINT", from = "губ", to = "ютуб")))
        assertTrue(NightReviewPolicy.shortCyrillicWord("прод"))
        assertFalse(NightReviewPolicy.shortCyrillicWord("lifans"))
    }
}
