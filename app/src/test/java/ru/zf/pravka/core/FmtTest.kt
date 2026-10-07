package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Числа и даты Правки 4.0 (DESIGN §3.9–3.10): «1 074 898» узким пробелом,
// «−3 577» настоящим минусом, «1 ч 40 м», «пн, 5 октября».
class FmtTest {

    private val t = ' '
    private val m = '−'

    @Test
    fun `тысячи через узкий неразрывный пробел`() {
        assertEquals("1${t}074${t}898", Fmt.num(1_074_898))
        assertEquals("1${t}545", Fmt.num(1545))
        assertEquals("655", Fmt.num(655))
        assertEquals("0", Fmt.num(0))
    }

    @Test
    fun `минус - настоящий знак`() {
        assertEquals("${m}3${t}577", Fmt.num(-3577))
        assertEquals("${m}1", Fmt.points(-1))
        assertEquals("+84", Fmt.points(84))
        assertEquals("+325${t}000", Fmt.signed(325_000))
        assertEquals("0", Fmt.signed(0))
    }

    @Test
    fun `длительности без «мин»`() {
        assertEquals("45 м", Fmt.dur(45))
        assertEquals("1 ч 40 м", Fmt.dur(100))
        assertEquals("7 ч 25 м", Fmt.dur(445))
        assertEquals("2 ч", Fmt.dur(120))
        assertEquals("0 м", Fmt.dur(0))
        assertEquals("0 м", Fmt.dur(-5))
    }

    @Test
    fun `будущее округляется до пяти минут, прошлое - точно`() {
        assertEquals("4 ч 40 м", Fmt.durFuture(279))
        assertEquals("4 ч 35 м", Fmt.durFuture(277))
        assertEquals("1 ч 10 м", Fmt.durFuture(69))
        assertEquals("4 ч 37 м", Fmt.dur(277))
    }

    @Test
    fun `даты в пяти форматах`() {
        val d = LocalDate.of(2026, 10, 5)
        assertEquals("Понедельник", Fmt.weekdayTitle(d))
        assertEquals("понедельник, 5 октября", Fmt.dayLong(d))
        assertEquals("пн, 5 октября", Fmt.dayList(d))
        assertEquals("пн, 5 окт", Fmt.dayShort(d))
        assertEquals("5 октября · сегодня", Fmt.overline(d, d))
        assertEquals("пн, 28 сент", Fmt.dayShort(LocalDate.of(2026, 9, 28)))
        assertEquals("ср, 4 нояб", Fmt.dayShort(LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun `время из минут суток`() {
        assertEquals("23:30", Fmt.hmOfMin(23 * 60 + 30))
        assertEquals("00:00", Fmt.hmOfMin(1440))
        assertEquals("09:05", Fmt.hmOfMin(545))
    }

    @Test
    fun `рубли из копеек`() {
        assertEquals("${m}3${t}577", Fmt.rub(-357_700))
        assertEquals("1${t}240", Fmt.rub(124_049))
    }
}
