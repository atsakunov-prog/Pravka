package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run

// Локальные свидетельства ночного разбора: счётчики повторов по диффу и
// память решений владельца (docs/pravka.md, «Ночной разбор»).
class NightReviewEvidenceTest {

    @Test
    fun `повторы считаются по парам, заикание «не не» — не удаление отрицания`() {
        val pairs = listOf(
            "поли смотрела на меня" to "Полли смотрела на меня",
            "и поли сказала да" to "И Полли сказала: да.",
            "тётя поли пришла" to "Тётя Полли пришла.",
            "я не не могу собраться" to "Я не могу собраться.",
            "нам не подмигивало" to "Нам подмигивало.",
            "ноушене лежит база" to "В Notion лежит база.",
        )
        val a = NightReviewEvidence.aggregate(pairs)
        assertEquals(6, a.pairs)
        val polli = a.subs.first { it.from == "поли" }
        assertEquals("полли", polli.to)
        assertEquals(3, polli.count)
        // Одноразовая замена в счётчики не попадает.
        assertTrue(a.subs.none { it.from == "ноушене" })
        assertEquals(1, a.negationDeleted)
        assertTrue(NightReviewEvidence.render(a, "ПОВТОРЫ").contains("3 × «поли» → «полли»"))
    }

    @Test
    fun `память - возвращённое и отклонённое владельцем блокируется, проверкой — нет`() {
        val run = Run(
            id = 1, kind = "daily", startedAt = 1_000L, fromMs = 0, toMs = 1_000L, stage = "done",
            changes = listOf(
                Change(id = "dict-1", kind = "dict_add", mode = "HARD", from = "губ", to = "ютуб", status = "reverted", undo = """{"op":"delete","id":7}"""),
                Change(id = "dict-2", kind = "dict_add", mode = "PROTECT", from = "Полли", status = "rejected", statusNote = "отклонил владелец"),
                Change(id = "dict-3", kind = "dict_add", mode = "HINT", from = "поле", to = "Полли", status = "rejected", verdictWhy = "живое слово"),
                Change(id = "dict-4", kind = "dict_add", mode = "PROTECT", from = "Калипсо", status = "applied", undo = """{"op":"delete","id":9}"""),
            ),
        )
        val blocked = NightReviewEvidence.blockedKeys(listOf(run), 0L)
        assertEquals(setOf("dict_add|HARD|губ", "dict_add|PROTECT|полли"), blocked)
        val ledger = NightReviewEvidence.ledger(listOf(run), 0L, hitsSince = { id -> if (id == 9L) 4 else null })
        assertTrue(ledger.contains("ВЕРНУЛ ВЛАДЕЛЕЦ"))
        assertTrue(ledger.contains("отклонено проверкой: живое слово"))
        assertTrue(ledger.contains("PROTECT: Калипсо — применено (сработало 4 раз)"))
    }

    @Test
    fun `ответ согласования разбирается`() {
        val a = NightReviewPolicy.parseAudit("""{"assessment":"Набор скромный и в линии.","holds":[{"id":"dict-2","why":"владелец возвращал"}]}""")
        assertEquals("Набор скромный и в линии.", a.assessment)
        assertEquals(mapOf("dict-2" to "владелец возвращал"), a.holds)
    }
}
