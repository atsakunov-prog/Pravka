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

    // ---- три слоя одного BJJ (05.10.2026) ----

    @Test
    fun `слова о борьбе узнаются - у владельца, в календаре, у часов`() {
        assertTrue(IcuFixes.saysBjj("бжж и занимаюсь с борьбой"))
        assertTrue(IcuFixes.saysBjj("Занятие борьбой"))
        assertTrue(IcuFixes.saysBjj("БЖЖ"))
        assertTrue(IcuFixes.saysBjj("Поездка на бразильское джиу-джитсу"))
        assertTrue(IcuFixes.saysBjj("Mixed Martial Arts"))
        assertTrue(IcuFixes.saysBjj("BJJ: борьба"))
        assertFalse(IcuFixes.saysBjj("Время с Борей"))
        assertFalse(IcuFixes.saysBjj("Сборы детей"))
    }

    @Test
    fun `часы BJJ поверх своей записи о борьбе - одно занятие, дорога - нет`() {
        assertTrue(IcuFixes.sameActivity("BJJ: борьба", "Спорт: прочее", "Занятие борьбой", "Спорт: прочее"))
        assertTrue(IcuFixes.sameActivity("BJJ: борьба", "Спорт: прочее", "BJJ: борьба", "Спорт: прочее"))
        assertTrue(IcuFixes.sameActivity("BJJ: борьба", "Спорт: прочее", "Тренировка", "Спорт: прочее"))
        assertFalse(IcuFixes.sameActivity("BJJ: борьба", "Спорт: прочее", "Поездка на велосипеде на борьбу", "Передвижение: вело"))
        assertFalse(IcuFixes.sameActivity("BJJ: борьба", "Спорт: прочее", "Систематизация Правки", "Систематизация"))
    }

    @Test
    fun `бег поверх «бегаю» - одно занятие, бег поверх работы - нет`() {
        assertTrue(IcuFixes.sameActivity("Москва Бег", "Спорт: бег", "Пробежка", "Спорт: бег"))
        assertFalse(IcuFixes.sameActivity("Москва Бег", "Спорт: бег", "Работа", "Работа: текущая"))
        // Ходьба — передвижение, не спорт: прогулку с детьми она режет, как раньше.
        assertFalse(IcuFixes.sameActivity("Москва Ходьба", "Передвижение: пешком", "Прогулка", "Передвижение: пешком"))
    }
}
