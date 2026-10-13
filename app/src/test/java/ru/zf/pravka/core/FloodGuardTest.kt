package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защита от флуда (13.10.2026): «если он сломался и начал слать кучу запросов,
 * то приложение дёргает рубильник». Пороги — от самой плотной настоящей жизни
 * (17 диктовок за десять минут, 27 за час), а не на глаз.
 */
class FloodGuardTest {

    private val t0 = 1_000_000_000L
    private val sec = 1000L

    private fun hits(route: String, n: Int, everyMs: Long, from: Long = t0) =
        List(n) { FloodGuard.Hit(from + it * everyMs, route) }

    @Test
    fun `самые плотные десять минут владельца — не флуд`() {
        // 17 диктовок «П» за десять минут и 8 разборов Засечки (по два запроса).
        val h = hits("pravka", 16, 30 * sec) + hits("zasechka_fork", 8, 70 * sec) + hits("zasechka", 8, 70 * sec)
        assertNull(FloodGuard.check(h, "pravka", t0 + 9 * 60 * sec, relaxed = false))
    }

    @Test
    fun `одна дорога в петле — сработало`() {
        val h = hits("zasechka", 30, 15 * sec)
        val trip = FloodGuard.check(h, "zasechka", t0 + 30 * 15 * sec, relaxed = false)
        assertNotNull(trip)
        assertEquals("zasechka", trip!!.route)
        assertEquals(31, trip.count)
        assertEquals(10, trip.windowMin)
    }

    @Test
    fun `телефон в ленте — свой тесный порог`() {
        val h = hits("zasechka_phone", 12, 40 * sec)
        assertNotNull(FloodGuard.check(h, "zasechka_phone", t0 + 12 * 40 * sec, relaxed = false))
        assertNull(FloodGuard.check(hits("zasechka_phone", 3, 3 * 60 * sec), "zasechka_phone", t0 + 9 * 60 * sec, relaxed = false))
    }

    @Test
    fun `сравнение моделей пачкой по кнопке — не флуд`() {
        val h = hits("compare", 48, 10 * sec)
        assertNull(FloodGuard.check(h, "compare", t0 + 48 * 10 * sec, relaxed = false))
    }

    @Test
    fun `много дорог понемногу — общий порог`() {
        val routes = listOf("pravka", "zasechka", "money", "food")
        val h = (0 until 80).map { FloodGuard.Hit(t0 + it * 7 * sec, routes[it % 4]) }
        val trip = FloodGuard.check(h, "pravka", t0 + 80 * 7 * sec, relaxed = false)
        assertNotNull(trip)
        assertTrue(trip!!.total)
        assertEquals(81, trip.count)
    }

    @Test
    fun `за час — свой потолок`() {
        // 251 запрос ровно раз в 14 секунд — в десять минут влезает 43, в час — все.
        val routes = listOf("pravka", "zasechka", "money", "food", "body")
        val h = (0 until 250).map { FloodGuard.Hit(t0 + it * 14 * sec, routes[it % 5]) }
        val trip = FloodGuard.check(h, "pravka", t0 + 250 * 14 * sec, relaxed = false)
        assertNotNull(trip)
        assertEquals(60, trip!!.windowMin)
    }

    @Test
    fun `после «всё ок» — втрое на час`() {
        val h = hits("zasechka", 31, 15 * sec)
        assertNull(FloodGuard.check(h, "zasechka", t0 + 31 * 15 * sec, relaxed = true))
        assertNotNull(FloodGuard.check(hits("zasechka", 90, 5 * sec), "zasechka", t0 + 90 * 5 * sec, relaxed = true))
    }

    @Test
    fun `старше часа не считается`() {
        val h = hits("pravka", 100, sec)
        assertTrue(FloodGuard.prune(h, t0 + 2 * 3600 * sec).isEmpty())
    }

    @Test
    fun `словами — дорога, число, окно и кто ещё`() {
        val names = mapOf("zasechka_phone" to "Телефон в ленте", "pravka" to "Чистка диктовки")
        val name = { k: String -> names[k] ?: k }
        val t = FloodGuard.Trip("zasechka_phone", 13, 10, listOf("zasechka_phone" to 13, "pravka" to 4))
        assertEquals("«Телефон в ленте» — 13 запросов за 10 минут (ещё: «Чистка диктовки» 4)", FloodGuard.words(t, name))
        val total = FloodGuard.Trip("pravka", 81, 10, listOf("pravka" to 40, "zasechka_phone" to 3), total = true)
        assertEquals("81 запрос за 10 минут, больше всего — «Чистка диктовки» 40", FloodGuard.words(total, name))
    }
}
