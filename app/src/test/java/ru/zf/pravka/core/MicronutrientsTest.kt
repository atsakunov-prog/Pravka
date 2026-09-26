package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Справочник веществ — единственный источник норм для полосок, для Notion и
// для книги Excel. Сломать его можно тихо: переименовать ключ (и потерять
// старые приёмы на диске), поставить предел ниже нормы (и красить перебором
// то, что только что добрали), забыть «зачем» (и оставить владельца с голой
// цифрой, из которой ничего не следует). Поэтому проверяется сам справочник,
// а не только арифметика вокруг него.
class MicronutrientsTest {

    @Test
    fun `ключи уникальны, нормы положительны, предел не ниже нормы`() {
        val ids = Micronutrients.ALL.map { it.id }
        assertEquals("ключи веществ обязаны быть уникальны", ids.size, ids.distinct().size)
        for (n in Micronutrients.ALL) {
            assertTrue("${n.id}: пустой ключ", n.id.isNotBlank())
            assertTrue("${n.name}: норма должна быть > 0", n.norm > 0)
            assertTrue("${n.name}: единица не задана", n.unit in setOf("мкг", "мг", "г"))
            assertTrue(
                "${n.name}: предел ${n.ceiling} ниже нормы ${n.norm} — светофор светил бы перебором на норме",
                n.ceiling == 0.0 || n.ceiling >= n.norm,
            )
            assertTrue("${n.name}: не сказано, зачем он", n.why.length > 10)
            assertTrue("${n.name}: не сказано, где брать", n.source.length > 5)
            assertTrue("${n.name}: чужая группа", n.group in setOf("витамины", "минералы", "прочее"))
        }
    }

    @Test
    fun `у каждого вещества норма попадает внутрь полоски, а не в край`() {
        // Засечка нормы — весь смысл полоски: она обязана быть видна, а не
        // упираться в границу, иначе перебор не отличить от «ровно норма».
        for (n in Micronutrients.ALL) {
            val notch = n.norm / n.scale()
            assertTrue("${n.name}: засечка нормы на $notch — у самого края", notch in 0.3..0.95)
        }
    }

    @Test
    fun `светофор - мало, средне, достаточно, перебор`() {
        val d = Micronutrients.byId("vd")!!   // норма 15 мкг, предел 100
        assertEquals(Micronutrients.Level.LOW, Micronutrients.level(d, 0.0))
        assertEquals(Micronutrients.Level.LOW, Micronutrients.level(d, 7.0))
        assertEquals(Micronutrients.Level.MID, Micronutrients.level(d, 9.0))
        assertEquals(Micronutrients.Level.OK, Micronutrients.level(d, 12.0))
        assertEquals(Micronutrients.Level.OK, Micronutrients.level(d, 50.0))
        assertEquals(Micronutrients.Level.OVER, Micronutrients.level(d, 120.0))
        assertEquals("мало", Micronutrients.word(Micronutrients.Level.LOW))
    }

    @Test
    fun `у натрия норма это потолок, а не цель`() {
        val na = Micronutrients.byId("na")!!   // 1500 адекватно, 2300 предел
        // Ноль натрия — это не дефицит, о котором надо кричать красным.
        assertEquals(Micronutrients.Level.OK, Micronutrients.level(na, 0.0))
        assertEquals(Micronutrients.Level.OK, Micronutrients.level(na, 1400.0))
        assertEquals(Micronutrients.Level.MID, Micronutrients.level(na, 1900.0))
        assertEquals(Micronutrients.Level.OVER, Micronutrients.level(na, 2600.0))
        // И в список «мало» он не попадает никогда.
        assertFalse(Micronutrients.lacking(emptyMap()).any { it.id == "na" })
    }

    @Test
    fun `сумма складывает свои ключи и молча выбрасывает чужие`() {
        val total = Micronutrients.sum(
            listOf(
                mapOf("ca" to 120.0, "vd" to 2.0),
                mapOf("ca" to 300.0, "хром" to 40.0, "b12" to 0.0),
            )
        )
        assertEquals(420.0, total["ca"]!!, 0.001)
        assertEquals(2.0, total["vd"]!!, 0.001)
        assertNull("чужой ключ не должен доехать до дневника", total["хром"])
        assertNull("ноль — это отсутствие, а не значение", total["b12"])
    }

    @Test
    fun `витамины едут за весом порции`() {
        val item = MealItem(
            name = "Творог 5%", grams = 200, kcal = 234, protein = 33, fat = 10, carbs = 3,
            micro = mapOf("ca" to 240.0, "b12" to 2.0),
        )
        val half = item.scaledTo(100)
        assertEquals(120.0, half.micro["ca"]!!, 0.001)
        assertEquals(1.0, half.micro["b12"]!!, 0.001)
        // Позиция без веса (таблетка) пересчёту не поддаётся и остаётся собой.
        val pill = MealItem(
            name = "Витамин D3 2000 МЕ", grams = 0, kcal = 0, protein = 0, fat = 0, carbs = 0,
            micro = mapOf("vd" to 50.0), pill = true,
        )
        assertEquals(50.0, pill.scaledTo(100).micro["vd"]!!, 0.001)
        assertTrue(pill.scaledTo(100).pill)
    }

    @Test
    fun `мало и перебор считаются по справочнику`() {
        val totals = mapOf("vd" to 3.0, "ca" to 1100.0, "vc" to 2500.0)
        val low = Micronutrients.lacking(totals).map { it.id }
        assertTrue("витамина D три микрограмма — это мало", "vd" in low)
        assertFalse("кальций добран", "ca" in low)
        assertEquals(listOf("vc"), Micronutrients.over(totals).map { it.id })
    }

    @Test
    fun `заметное отсекает шум`() {
        // 3 % нормы кальция — это не данные, а округление модели.
        val kept = Micronutrients.notable(mapOf("ca" to 30.0, "vd" to 5.0))
        assertEquals(setOf("vd"), kept.keys)
    }

    @Test
    fun `количества пишутся по величине`() {
        assertEquals("3400", Micronutrients.amount(3400.0))
        assertEquals("15", Micronutrients.amount(15.0))
        assertEquals("1,3", Micronutrients.amount(1.3))
        assertEquals("0,25", Micronutrients.amount(0.25))
        assertEquals("2,4", Micronutrients.amount(2.4))
    }

    @Test
    fun `короткая строка идёт в порядке справочника и знает единицы`() {
        val s = Micronutrients.short(mapOf("ca" to 270.0, "vd" to 50.0))
        assertEquals("Витамин D 50 мкг · Кальций 270 мг", s)
    }

    @Test
    fun `блок промпта называет каждый ключ с единицей и нормой`() {
        val block = Micronutrients.promptBlock()
        for (n in Micronutrients.ALL) {
            assertTrue("в промпте нет ключа ${n.id}", block.contains("- ${n.id} — ${n.name}"))
        }
    }
}
