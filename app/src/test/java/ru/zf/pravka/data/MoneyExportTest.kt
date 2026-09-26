package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyRules

// Книга «Деньги»: три листа, в журнале — только то, что идёт в итоги.
class MoneyExportTest {

    @Test fun sheetsShape() {
        val e = { id: String, rub: Long, cat: String, draft: Boolean ->
            MoneyEntry(id = id, owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = 1_790_000_000_000L,
                rubKop = rub, what = id, category = cat, draft = draft)
        }
        val state = MoneyStore.State(
            entries = listOf(e("a", -100_00, "groceries", false), e("b", -50_00, "", false), e("c", -1_00, "cafe", true)),
            rules = MoneyRules.parseText("Иван П. = Кружки · Боря").rules,
        )
        val sheets = MoneyExport.sheets(state)
        assertEquals(listOf("Журнал", "По месяцам", "Справочник"), sheets.map { it.name })
        // Черновик без «ОК» в книгу не идёт.
        assertEquals(2, sheets[0].rows.size)
        assertEquals(1, sheets[2].rows.size)
    }
}
