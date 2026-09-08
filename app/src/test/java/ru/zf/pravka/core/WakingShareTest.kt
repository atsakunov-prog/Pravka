package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test

// Итоги дня Засечки без сна: бодрствование, доля категории от него и подпись
// строки. Правило из docs/zasechka.md («Как выглядит день»): сон в строки не
// идёт, проценты считаются от чистого времени.
class WakingShareTest {

    @Test
    fun `сон не входит в бодрствование, в любом регистре`() {
        val minutes = mapOf("Сон" to 420L, "работа: текущая" to 300L, "еда" to 60L)
        assertEquals(360L, WakingShare.awakeMinutes(minutes))
        assertEquals(360L, WakingShare.awakeMinutes(minutes - "Сон" + ("сон" to 500L)))
    }

    @Test
    fun `сон узнаётся как в Отчёте`() {
        assertEquals(true, WakingShare.isSleep("сон"))
        assertEquals(true, WakingShare.isSleep(" Сон "))
        assertEquals(false, WakingShare.isSleep("отдых"))
        assertEquals(false, WakingShare.isSleep(""))
    }

    @Test
    fun `процент — от бодрствования, округлённый до целого`() {
        assertEquals(50, WakingShare.percent(300, 600))
        assertEquals(17, WakingShare.percent(100, 600))   // 16.67 → 17
        assertEquals(33, WakingShare.percent(200, 600))   // 33.33 → 33
        assertEquals(100, WakingShare.percent(600, 600))
    }

    @Test
    fun `пустой день и пустая строка не делят на ноль`() {
        assertEquals(0, WakingShare.percent(0, 600))
        assertEquals(0, WakingShare.percent(30, 0))
        assertEquals("", WakingShare.label(0, 600))
        assertEquals("", WakingShare.label(30, 0))
    }

    @Test
    fun `подпись — проценты, а крошка не путается с пустой строкой`() {
        assertEquals("50%", WakingShare.label(300, 600))
        assertEquals("<1%", WakingShare.label(2, 600))    // 0.33 → 0 → «<1%»
        assertEquals("1%", WakingShare.label(3, 600))     // 0.5 → 1
    }
}
