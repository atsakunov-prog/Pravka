package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BJJ с часов приходит в intervals ходьбой (01.10.2026) — телефон чинит тип и имя. */
class IcuFixesTest {

    private val want = IcuFixes.Fix("Other", "BJJ: борьба")

    @Test
    fun `ходьба с именем профиля часов — Other и BJJ`() {
        assertEquals(want, IcuFixes.fix("Walk", "Mixed Martial Arts"))
        assertEquals(want, IcuFixes.fix("Walk", " mixed martial arts "))
    }

    @Test
    fun `тип уже поправлен руками — остаётся имя`() {
        // 9 сентября владелец сменил тип сам, имя осталось от часов.
        assertEquals(want, IcuFixes.fix("Other", "Mixed Martial Arts"))
    }

    @Test
    fun `имя приняли, тип нет — правило узнаёт и во второй раз`() {
        assertEquals(want, IcuFixes.fix("Walk", "BJJ: борьба"))
    }

    @Test
    fun `всё на месте — не трогаем`() {
        assertNull(IcuFixes.fix("Other", "BJJ: борьба"))
    }

    @Test
    fun `настоящая прогулка и своё имя — не BJJ`() {
        assertNull(IcuFixes.fix("Walk", "Kutuzovsky Walking"))
        assertNull(IcuFixes.fix("Walk", ""))
        assertNull(IcuFixes.fix("Other", "Борьба с Сашей"))
        assertFalse(IcuFixes.isBjj("Mixed Martial Arts 2"))
    }

    @Test
    fun `PUT частичный — только тип и имя`() {
        val p = IcuFixes.payload(want)
        assertEquals(setOf("type", "name"), p.keys().asSequence().toSet())
        assertEquals("Other", p.getString("type"))
        assertEquals("BJJ: борьба", p.getString("name"))
    }

    @Test
    fun `ответ intervals — сменился ли тип`() {
        assertTrue(IcuFixes.accepted("""{"id":"i1","type":"Other","name":"BJJ: борьба"}""", want))
        assertFalse(IcuFixes.accepted("""{"id":"i1","type":"Walk","name":"BJJ: борьба"}""", want))
        assertTrue(IcuFixes.accepted("", want))
        assertTrue(IcuFixes.accepted("""{"id":"i1"}""", want))
    }

    @Test
    fun `лента — ходьба становится спортом, чужая категория остаётся`() {
        assertEquals("Спорт: прочее", IcuFixes.ribbonCategory("Передвижение: пешком"))
        assertEquals("Спорт: прочее", IcuFixes.ribbonCategory(""))
        assertEquals("Спорт: прочее", IcuFixes.ribbonCategory("Спорт: прочее"))
        assertEquals("Спорт: силовая", IcuFixes.ribbonCategory("Спорт: силовая"))
    }
}
