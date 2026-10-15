package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кэш головы разбора надиктовки — только когда он прочитается (аудит 15.10.2026, №67): одиночный разбор не пишет
 * часовой кэш (запись вдвое дороже входа, читать некому), второй в серии за 55 минут с той же головой — пишет, дальше —
 * читают.
 */
class RaznoskaCacheTest {

    private val min = 60_000L
    private val head = RaznoskaCache.hash("правила и справочник")

    @Test
    fun `одиночный — без кэша`() {
        assertFalse(RaznoskaCache.decide(1_000 * min, null, head))
    }

    @Test
    fun `второй за 54 минуты с той же головой — с кэшем, за 56 — нет`() {
        val last = RaznoskaCache.Last(at = 1_000 * min, head = head, cached = false)
        assertTrue(RaznoskaCache.decide(1_054 * min, last, head))
        assertFalse(RaznoskaCache.decide(1_056 * min, last, head))
    }

    @Test
    fun `другая голова — без кэша`() {
        val last = RaznoskaCache.Last(at = 1_000 * min, head = RaznoskaCache.hash("другой справочник"), cached = true)
        assertFalse(RaznoskaCache.decide(1_001 * min, last, head))
    }

    @Test
    fun `часы назад — не верим`() {
        val last = RaznoskaCache.Last(at = 1_000 * min, head = head, cached = true)
        assertFalse(RaznoskaCache.decide(999 * min, last, head))
    }

    @Test
    fun `серия — память пишется после каждого разбора`() {
        val mem = object : RaznoskaCache.Memory {
            var l: RaznoskaCache.Last? = null
            override fun read() = l
            override fun write(last: RaznoskaCache.Last) { l = last }
        }
        val runs = listOf(0L, 20 * min, 40 * min, 200 * min)
        val got = runs.map { t ->
            val on = RaznoskaCache.decide(t, mem.read(), head)
            mem.write(RaznoskaCache.Last(t, head, on))
            on
        }
        // Первый — без, второй пишет, третий читает; через три часа — снова одиночный.
        assertEquals(listOf(false, true, true, false), got)
    }

    @Test
    fun `память в файле — туда и обратно, хэш без текста`() {
        val l = RaznoskaCache.Last(123L, head, true)
        assertEquals(l, RaznoskaCache.fromJson(RaznoskaCache.toJson(l)))
        assertNull(RaznoskaCache.fromJson(org.json.JSONObject()))
        assertEquals(32, head.length)
        assertNotEquals(head, RaznoskaCache.hash("правила и справочник "))
        assertFalse(RaznoskaCache.toJson(l).toString().contains("справочник"))
    }
}
