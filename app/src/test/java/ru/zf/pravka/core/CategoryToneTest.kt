package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Цвет категории вида v2 — «чей это» (владелец 13.10.2026: «нравилась гамма как
// на макетах, а не светофор»): места холста, верхний уровень, устойчивость.
class CategoryToneTest {

    @Test
    fun `места холста — по верхнему уровню категории`() {
        assertEquals(0, CategoryTone.of("Работа: текущая").sp)
        assertEquals(0, CategoryTone.of("Работа: звонки").sp)
        assertEquals(1, CategoryTone.of("Быт").sp)
        assertEquals(2, CategoryTone.of("Спорт: бег").sp)
        assertEquals(3, CategoryTone.of("Семья").sp)
        assertEquals(4, CategoryTone.of("Отдых").sp)
        assertEquals(5, CategoryTone.of("Систематизация").sp)
        assertEquals(6, CategoryTone.of("Еда").sp)
        assertEquals(7, CategoryTone.of("Сон").sp)
        // Регистр и пробелы не важны.
        assertEquals(CategoryTone.of("Работа: текущая"), CategoryTone.of("  работа:планирование"))
    }

    @Test
    fun `дорога и потери — пополам с фоном, не размечено — дыра`() {
        assertEquals(CategoryTone.Tone(7, half = true), CategoryTone.of("Передвижение: транспорт"))
        assertEquals(CategoryTone.Tone(7, half = true), CategoryTone.of("Дорога"))
        assertEquals(CategoryTone.Tone(6, half = true), CategoryTone.of("Потери"))
        assertTrue(CategoryTone.of("Не размечено").gap)
    }

    @Test
    fun `прочие — устойчиво по имени и из первых семи цветов`() {
        for (name in listOf("Чтение", "Учёба", "Социальное: внешнее", "Звонки", "Гитара")) {
            val t = CategoryTone.of(name)
            assertTrue(name, t.sp in 0..6)
            assertEquals(name, t, CategoryTone.of(name.uppercase()))
        }
        // Устойчивость между запусками: хеш строки задан языком — число здесь навсегда.
        assertEquals(2, CategoryTone.of("Чтение").sp)
        assertEquals(5, CategoryTone.of("Учёба").sp)
        assertEquals(1, CategoryTone.of("Социальное: внешнее").sp)
        assertEquals(CategoryTone.of("Социальное: внешнее"), CategoryTone.of("Социальное: близкие"))
        assertEquals(7, CategoryTone.of("").sp)
    }
}
