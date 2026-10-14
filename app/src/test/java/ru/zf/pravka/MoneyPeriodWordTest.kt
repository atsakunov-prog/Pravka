package ru.zf.pravka

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.zf.pravka.core.MoneyStats
import java.time.LocalDate

/**
 * Период в подписи шапки Денег (MoneyV2.kt): на развороте словами, на сложенном коротко (14.10.2026: «октябрь,
 * по 14-е · личное ⌄» рядом с четырьмя значками на 411 dp уже не влезал в строку).
 */
class MoneyPeriodWordTest {

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun `идущий месяц - по сегодня, на сложенном числами`() {
        val p = MoneyStats.of(MoneyStats.Kind.MONTH, today)
        assertEquals("октябрь, по 14-е", moneyPeriodWord(p, today))
        assertEquals("1–14 окт", moneyPeriodWord(p, today, short = true))
    }

    @Test
    fun `прошедший месяц - словом на обоих экранах`() {
        val p = MoneyStats.of(MoneyStats.Kind.MONTH, LocalDate.of(2026, 9, 10))
        assertEquals("сентябрь", moneyPeriodWord(p, today))
        assertEquals("сентябрь", moneyPeriodWord(p, today, short = true))
    }

    @Test
    fun `неделя - месяц на сложенном коротко`() {
        val p = MoneyStats.of(MoneyStats.Kind.WEEK, today)
        assertEquals("12–18 октября", moneyPeriodWord(p, today))
        assertEquals("12–18 окт", moneyPeriodWord(p, today, short = true))
    }
}
