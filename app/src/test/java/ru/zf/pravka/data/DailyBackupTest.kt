package ru.zf.pravka.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Суточная копия базы (25.09.2026): ночью, раз в сутки, пропущенная ночь
// наверстывается; хранятся две недели и по копии на месяц; архив сам по
// себе база — с паспортом, без почасовых копий и без того, что не база.
class DailyBackupTest {

    private val h = 3_600_000L
    private val p = DailyBackup.Policy

    @Test
    fun `первая копия — сразу, сколько бы ни было времени`() {
        assertTrue(p.due(now = 100 * h, lastAt = 0L, hour = 14, charging = false))
    }

    @Test
    fun `ночью на зарядке — да, днём после вчерашней ночной — нет`() {
        val last = 1_000 * h
        assertTrue(p.due(now = last + 23 * h, lastAt = last, hour = 2, charging = true))
        assertFalse(p.due(now = last + 23 * h, lastAt = last, hour = 2, charging = false))
        assertFalse(p.due(now = last + 23 * h, lastAt = last, hour = 14, charging = true))
    }

    @Test
    fun `к пяти утра — и без зарядки`() {
        val last = 1_000 * h
        assertTrue(p.due(now = last + 24 * h, lastAt = last, hour = 5, charging = false))
    }

    @Test
    fun `вторую за ночь не снимает`() {
        val last = 1_000 * h
        assertFalse(p.due(now = last + 1 * h, lastAt = last, hour = 4, charging = true))
    }

    @Test
    fun `пропущенная ночь наверстывается днём после тридцати часов`() {
        val last = 1_000 * h
        assertFalse(p.due(now = last + 29 * h, lastAt = last, hour = 9, charging = false))
        assertTrue(p.due(now = last + 30 * h, lastAt = last, hour = 9, charging = false))
    }

    @Test
    fun `имя — латиницей, кириллица и пробелы не ломают файл`() {
        assertEquals("pravka-sasha-2026-09-25.zip", p.fileName("sasha", LocalDate.parse("2026-09-25")))
        assertEquals("pravka-user-2026-09-25.zip", p.fileName("Мила", LocalDate.parse("2026-09-25")))
        assertEquals("pravka-guest_2-2026-09-25.zip", p.fileName("Guest 2", LocalDate.parse("2026-09-25")))
    }

    @Test
    fun `две недели каждый день, дальше по первой копии месяца, чужие файлы целы`() {
        val today = LocalDate.parse("2026-09-25")
        val days = (0L until 70L).map { today.minusDays(it) }
        val names = days.map { p.fileName("sasha", it) } + listOf("notes.txt", p.fileName("marianna", today))

        val drop = p.prune(names, today).toSet()
        val kept = names.filter { it !in drop }

        // Последние 14 дней — все.
        for (d in 0L until 14L) assertTrue(p.fileName("sasha", today.minusDays(d)) in kept)
        // Старше — по самой ранней копии месяца (сентябрь: 12-е уже в двух неделях, раньше — 1-е).
        assertTrue(p.fileName("sasha", LocalDate.parse("2026-09-01")) in kept)
        assertTrue(p.fileName("sasha", LocalDate.parse("2026-08-01")) in kept)
        assertTrue(p.fileName("sasha", LocalDate.parse("2026-07-18")) in kept)  // самая ранняя июльская из 70 дней
        assertFalse(p.fileName("sasha", LocalDate.parse("2026-09-05")) in kept)
        assertFalse(p.fileName("sasha", LocalDate.parse("2026-08-15")) in kept)
        // Чужое и копии другого человека не трогаются.
        assertTrue("notes.txt" in kept)
        assertTrue(p.fileName("marianna", today) in kept)
    }

    @Test
    fun `год назад — уходит`() {
        val today = LocalDate.parse("2026-09-25")
        val old = p.fileName("sasha", LocalDate.parse("2025-09-01"))
        val yearAgoMonth = p.fileName("sasha", LocalDate.parse("2025-10-01"))
        val drop = p.prune(listOf(old, yearAgoMonth), today)
        assertEquals(listOf(old), drop)
    }

    // --- архив ---------------------------------------------------------------

    private val tmp: File = Files.createTempDirectory("daily").toFile()
    @After fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun `архив — база с паспортом, без почасовых копий, prev и не-базы`() {
        val root = File(tmp, "files").apply { mkdirs() }
        fun put(rel: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(rel) }
        put("zasechka.json")
        put("zasechka.json.prev")
        put("food/1.jpg")
        put("datastore/settings.preferences_pb")
        put("backups/zasechka-2026-09-25-03.json")
        put("zasechka-backups/zasechka-2026-09-24.json")
        put("models/ggml.bin")
        put("recordings/rec_1.wav")
        put("before-move-2026-09-24-1530/zasechka.json")
        val out = File(tmp, "out/pravka-sasha-2026-09-25.zip").apply { parentFile.mkdirs() }

        val n = DailyBackup.zip(root, out, DbMove.Passport(token = "backup", createdAt = 1L, device = "Fold"))

        val names = ZipFile(out).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertEquals(3, n)
        assertEquals(
            setOf("zasechka.json", "food/1.jpg", "datastore/settings.preferences_pb", DbMove.PASSPORT),
            names,
        )
        assertFalse(File(tmp, "out/pravka-sasha-2026-09-25.zip.tmp").exists())
    }
}
