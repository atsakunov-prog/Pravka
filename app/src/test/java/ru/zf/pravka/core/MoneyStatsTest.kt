package ru.zf.pravka.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Статистика вкладки: периоды, сравнение, дни, прогноз, тренд, магазины,
// регулярные. Суммы вымышленные.
class MoneyStatsTest {

    private fun at(d: String, h: Int = 12) = MoneyStats.startOf(LocalDate.parse(d)) + h * 3_600_000L

    private fun e(id: String, d: String, rub: Long, cat: String, what: String = id) = MoneyEntry(
        id = id, owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = at(d), rubKop = rub * 100, what = what, category = cat,
    )

    private val entries = listOf(
        e("a", "2026-09-21", -1_000, "groceries", "VV_KKM 1183.4"),
        e("b", "2026-09-22", -500, "groceries", "vkusvill"),
        e("c", "2026-09-22", -300, "groceries", "Яндекс Лавка"),
        e("d", "2026-09-23", -2_000, "cafe", "Кофемания"),
        e("z", "2026-09-23", -10_000, "zf", "Партнёр"),
        e("s", "2026-09-23", -7_000, "spouse", "Жена"),
        e("u", "2026-09-23", -400, "", "Непонятно кто"),
        e("i", "2026-09-15", 100_000, "inc_zf", "Зарплата"),
        e("p", "2026-09-14", -900, "groceries", "Перекрёсток"),
    )

    @Test fun weekAndMonthPeriods() {
        val w = MoneyStats.of(MoneyStats.Kind.WEEK, LocalDate.parse("2026-09-23"))
        assertEquals(LocalDate.parse("2026-09-21"), w.firstDay)
        assertEquals(7, w.days)
        val m = MoneyStats.of(MoneyStats.Kind.MONTH, LocalDate.parse("2026-09-23"))
        assertEquals(30, m.days)
        assertEquals(LocalDate.parse("2026-08-01"), m.prev().firstDay)
    }

    @Test fun totalsSkipServiceAndCountUnknown() {
        val w = MoneyStats.of(MoneyStats.Kind.WEEK, LocalDate.parse("2026-09-23"))
        val t = MoneyStats.totals(entries, w, MoneyScope.PERSONAL)
        // 1000+500+300+2000 и неразложенные 400; ЗФ и «между нами» — нет.
        assertEquals(420_000L, t.spentKop)
        assertEquals(90_000L, t.prevSpentKop)
        assertEquals(0L, t.incomeKop)
        assertEquals(1_420_000L, MoneyStats.totals(entries, w, MoneyScope.BOTH).spentKop)
        val m = MoneyStats.of(MoneyStats.Kind.MONTH, LocalDate.parse("2026-09-23"))
        assertEquals(10_000_000L, MoneyStats.totals(entries, m, MoneyScope.PERSONAL).incomeKop)
    }

    @Test fun dailyAndPace() {
        val w = MoneyStats.of(MoneyStats.Kind.WEEK, LocalDate.parse("2026-09-23"))
        assertEquals(listOf(100_000L, 80_000L, 240_000L, 0L, 0L, 0L, 0L), MoneyStats.daily(entries, w, MoneyScope.PERSONAL))
        val pace = MoneyStats.pace(entries, w, MoneyScope.PERSONAL, LocalDate.parse("2026-09-23"))
        assertEquals(3, pace.daysPassed)
        assertEquals(140_000L, pace.perDayKop)
        assertEquals(980_000L, pace.forecastKop)
        assertNull(MoneyStats.pace(entries, w.prev(), MoneyScope.PERSONAL, LocalDate.parse("2026-09-23")).forecastKop)
    }

    @Test fun merchantsFoldStores() {
        val w = MoneyStats.of(MoneyStats.Kind.WEEK, LocalDate.parse("2026-09-23"))
        val groceries = MoneyStats.categories(entries, w, MoneyScope.PERSONAL).first { it.key == "groceries" }
        assertEquals(listOf("ВкусВилл" to 150_000L, "Яндекс Лавка" to 30_000L), groceries.merchants.map { it.name to it.kop })
        assertEquals(90_000L, groceries.prevKop)
        assertEquals("MAGAZIN", MoneyMerchants.canonical("MAGAZIN 382"))
    }

    @Test fun recurringNeedsThreeSimilarMonths() {
        val list = listOf(
            e("n1", "2026-06-05", -23_000, "help", "Няня"), e("n2", "2026-07-05", -23_000, "help", "Няня"),
            e("n3", "2026-08-05", -25_000, "help", "Няня"), e("x1", "2026-07-01", -100, "cafe", "Кафе"),
            e("x2", "2026-08-01", -9_000, "cafe", "Кафе"), e("x3", "2026-09-01", -300, "cafe", "Кафе"),
        )
        val r = MoneyStats.recurring(list, LocalDate.parse("2026-09-23"), MoneyScope.PERSONAL)
        assertEquals(listOf("Няня"), r.map { it.name })
        assertEquals(3, r[0].months)
        assertTrue(MoneyStats.trend(list, LocalDate.parse("2026-09-23"), 6, MoneyScope.PERSONAL).size == 6)
    }

    @Test fun contextStartsAtFirstMonthAndRoundsRubles() {
        val text = MoneyContext.build(entries, LocalDate.parse("2026-09-23"))
        // Данные с сентября: месяцев до него в таблице нет — это не «ноль трат».
        assertTrue(text.contains("категория | 2026-09\n"))
        assertTrue(!text.contains("2026-08"))
        assertTrue(text.contains("Продукты"))
        assertTrue(!text.contains(",00"))
    }
}
