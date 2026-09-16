package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Test

// Потолок блока правил: в промпт уезжают включённые и одобренные по порядку,
// пока хватает 2000 знаков; остальные помечаются во вкладке Обучение.
class RulesStoreTest {

    private fun rule(id: Long, len: Int, enabled: Boolean = true, pending: Boolean = false) =
        RulesStore.Rule(id = id, text = "п".repeat(len), enabled = enabled, pending = pending)

    @Test
    fun `в потолок входят первые, выключенные и непросуженные не считаются`() {
        val rules = listOf(
            rule(1, 900),
            rule(2, 900, enabled = false),
            rule(3, 900, pending = true),
            rule(4, 900),
            rule(5, 900),
            rule(6, 10),
        )
        // 1 и 4 — 1800 знаков; 5 не влезает, и на нём список обрывается: порядок владельца сохраняется.
        assertEquals(setOf(1L, 4L), RulesStore.fitsInPrompt(rules))
    }
}
