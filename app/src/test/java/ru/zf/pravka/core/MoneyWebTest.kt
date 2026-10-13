package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Разделы веба «Деньги» на телефоне (доводка 5.0, 14.10.2026): адрес — только из синка, раздел — фрагментом.
class MoneyWebTest {

    private val nav = listOf(
        Kovcheg.Nav("Дела", "dela", "/"),
        Kovcheg.Nav("Деньги", "wallet", "https://money.example.org/"),
        Kovcheg.Nav("Шаббат", "candles", "/shabbat/"),
    )

    @Test
    fun `адрес сервиса — по ключу из синка, относительный — от адреса Дел`() {
        assertEquals("dengi", nav[1].key)
        assertEquals("https://money.example.org/", MoneyWeb.service(nav, "https://dela.example.org"))
        val rel = listOf(Kovcheg.Nav("Деньги", "wallet", "/dengi/", key = "dengi"))
        assertEquals("https://dela.example.org/dengi/", MoneyWeb.service(rel, "https://dela.example.org/"))
        // Нет сервиса в синке (не владелец, старый сервер) — входов нет.
        assertNull(MoneyWeb.service(nav.filter { it.key != "dengi" }, "https://dela.example.org"))
        assertNull(MoneyWeb.service(rel, null))
    }

    @Test
    fun `раздел — фрагмент с именем вкладки веба, свой фрагмент адреса заменяется`() {
        assertEquals("https://money.example.org/#pnl", MoneyWeb.url("https://money.example.org/", "pnl"))
        assertEquals("https://money.example.org/#gaps", MoneyWeb.url("https://money.example.org/#balance", "gaps"))
        // Вкладки — те, что есть у веба (`data-tab`): P&L, статьи, правила с долей, «что не видим».
        assertEquals(listOf("pnl", "articles", "rules", "gaps"), MoneyWeb.SECTIONS.map { it.tab })
        assertTrue(MoneyWeb.SECTIONS.all { ru.zf.pravka.ui.KvGlyphs.has(it.icon) })
    }
}
