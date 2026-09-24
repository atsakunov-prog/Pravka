package ru.zf.pravka.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.DriveBackup.Policy.Do

// Ночная копия базы — ещё и в семейный Drive (25.09.2026): едет последний
// снятый архив один раз, по Wi-Fi (трое суток без него — по мобильной), в
// Drive неделя каждый день и по копии на месяц, каждый телефон чистит свои.
class DriveBackupTest {

    private val day = 86_400_000L
    private val p = DriveBackup.Policy
    private val now = 1_790_000_000_000L

    @Test fun `новый архив по Wi-Fi едет сразу`() {
        assertEquals(Do.UPLOAD, p.decide("pravka-sasha-2026-09-25.zip", "pravka-sasha-2026-09-24.zip", now - day, now, metered = false, force = false))
    }

    @Test fun `уехавший второй раз не едет`() {
        assertEquals(Do.NOTHING, p.decide("pravka-sasha-2026-09-25.zip", "pravka-sasha-2026-09-25.zip", now, now, metered = false, force = true))
        assertEquals(Do.NOTHING, p.decide("", "", 0L, now, metered = false, force = true))
    }

    @Test fun `по мобильной ждёт Wi-Fi, но не дольше трёх суток`() {
        val a = "pravka-sasha-2026-09-25.zip"
        assertEquals(Do.WAIT_WIFI, p.decide(a, "pravka-sasha-2026-09-24.zip", now - day, now, metered = true, force = false))
        assertEquals(Do.UPLOAD, p.decide(a, "pravka-sasha-2026-09-21.zip", now - 3 * day, now, metered = true, force = false))
        // Кнопка не ждёт.
        assertEquals(Do.UPLOAD, p.decide(a, "pravka-sasha-2026-09-24.zip", now - day, now, metered = true, force = true))
    }

    @Test fun `в Drive неделя и по месяцу, только свои`() {
        val today = LocalDate.of(2026, 9, 25)
        val sasha = (0L until 40L).map { "pravka-sasha-${today.minusDays(it)}.zip" }
        val marianna = (0L until 40L).map { "pravka-marianna-${today.minusDays(it)}.zip" }
        val drop = p.prune(sasha + marianna + "Правка.txt", "sasha", today)
        assertTrue("чужие копии не наши", drop.none { it.contains("marianna") })
        val kept = (sasha - drop.toSet()).toSet()
        // Неделя подряд плюс самая ранняя копия каждого прежнего месяца в окне.
        val week = (0L until 7L).map { "pravka-sasha-${today.minusDays(it)}.zip" }
        assertEquals((week + "pravka-sasha-2026-09-01.zip" + "pravka-sasha-2026-08-17.zip").toSet(), kept)
    }

    @Test fun `секреты установки в копию не идут`() {
        assertTrue(DbMove.notDatabase(DbMove.SECRETS))
    }
}
