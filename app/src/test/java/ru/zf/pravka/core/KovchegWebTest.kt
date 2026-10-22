package ru.zf.pravka.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Меню Ковчега без своих экранов (22.10.2026, Правка на диете): всё — страницами сервера. Большие кнопки — страницы
 * веба Дел, Дела — `/t`, Спорт, Еда и Деньги — свои сайты, а не экраны приложения. Адреса — выдуманные (`example.org`).
 */
class KovchegWebTest {

    private val base = "https://dela.example.org"

    private val nav = Kovcheg.navFrom(
        JSONArray(
            """[{"name": "Дела", "icon": "dela", "url": "/", "key": "dela"},
               {"name": "Спорт", "icon": "activity", "url": "https://sport.example.org/", "key": "sport"},
               {"name": "Еда", "icon": "food", "url": "https://eda.example.org/", "key": "eda"},
               {"name": "Деньги", "icon": "wallet", "url": "https://dengi.example.org/", "key": "dengi"},
               {"name": "Дом", "icon": "home", "url": "https://home.example.org/", "key": "dom"}]""",
        ),
    )

    private fun all(b: Kovcheg.Built) = b.big + b.directions.flatMap { it.items } + (b.create?.items ?: emptyList())

    @Test
    fun `без своих экранов — только страницы и Claude`() {
        val built = Kovcheg.build(Kovcheg.FACTORY, emptyList(), nav, base, webScreens = true)
        val targets = all(built).map { it.target }
        assertTrue("экранов приложения быть не должно: $targets", targets.none { it is Kovcheg.Target.Screen })
    }

    @Test
    fun `большие кнопки — страницы веба Дел`() {
        val built = Kovcheg.build(Kovcheg.FACTORY, emptyList(), nav, base, webScreens = true)
        assertEquals(
            listOf("today" to "$base/#/day", "zasechka" to "$base/#/zasechka", "pravka" to "$base/#/pravka"),
            built.big.map { it.key to (it.target as Kovcheg.Target.Web).url },
        )
    }

    @Test
    fun `Дела — страница t, Спорт, Еда и Деньги — свои сайты`() {
        val built = Kovcheg.build(Kovcheg.FACTORY, emptyList(), nav, base, webScreens = true)
        val url = all(built).associate { it.key to (it.target as? Kovcheg.Target.Web)?.url }
        assertEquals("$base/t", url["dela"])
        assertEquals("https://sport.example.org/", url["sport"])
        assertEquals("https://eda.example.org/", url["eda"])
        assertEquals("https://dengi.example.org/", url["dengi"])
    }

    @Test
    fun `сервер не подключён — больших кнопок нет, полные адреса остаются`() {
        val built = Kovcheg.build(Kovcheg.FACTORY, emptyList(), nav, null, webScreens = true)
        assertTrue(built.big.isEmpty())
        assertTrue(all(built).any { it.key == "dom" })
        assertTrue(all(built).none { it.key == "dela" })
    }

    @Test
    fun `прежний вид не тронут — Спорт и Дела там экранами`() {
        val own = Kovcheg.own(setOf(Kovcheg.SPORT, Kovcheg.DELA), crm = false)
        val built = Kovcheg.build(Kovcheg.FACTORY, own, nav, base)
        val byKey = all(built).associate { it.key to it.target }
        assertTrue(byKey["sport"] is Kovcheg.Target.Screen)
        assertTrue(byKey["dela"] is Kovcheg.Target.Screen)
        assertTrue(byKey["today"] is Kovcheg.Target.Screen)
    }
}
