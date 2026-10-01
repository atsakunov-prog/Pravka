package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.NetProbe.Mark
import ru.zf.pravka.core.NetProbe.Target

// Проверка связи с облаками (владелец, 01.10.2026: «пинг каждые 5 минут…
// отдельный лог, есть доступ или нет… нарисуем… неделю это проверять»).
class NetProbeTest {

    private val m = 60_000L

    @Test
    fun `интернет - только 204, страница вместо него - портал`() {
        assertTrue(NetProbe.verdict(Target.INTERNET, 204, null, 120).ok)
        assertFalse(NetProbe.verdict(Target.INTERNET, 200, null, 120).ok)
        assertFalse(NetProbe.verdict(Target.INTERNET, 0, "таймаут 8 с", 8000).ok)
        assertEquals("таймаут 8 с", NetProbe.verdict(Target.INTERNET, 0, "таймаут 8 с", 8000).why)
    }

    @Test
    fun `claude - 200 и 401 достучались, 403 - не пускают отсюда`() {
        assertTrue(NetProbe.verdict(Target.CLAUDE, 200, null, 300).ok)
        assertTrue(NetProbe.verdict(Target.CLAUDE, 401, null, 300).ok)
        val geo = NetProbe.verdict(Target.CLAUDE, 403, null, 300)
        assertFalse(geo.ok)
        assertTrue(geo.why.contains("VPN"))
        assertFalse(NetProbe.verdict(Target.CLAUDE, 529, null, 300).ok)
    }

    @Test
    fun `google - любой ответ двери, кроме сбоя сервера`() {
        assertTrue(NetProbe.verdict(Target.GOOGLE, 404, null, 200).ok)
        assertFalse(NetProbe.verdict(Target.GOOGLE, 503, null, 200).ok)
        assertFalse(NetProbe.verdict(Target.GOOGLE, 0, "нет соединения", 10).ok)
    }

    @Test
    fun `пора проверять - интервал с запасом на дрожание тика`() {
        assertTrue(NetProbe.due(0L, 1_000L, 5 * m))
        assertFalse(NetProbe.due(10 * m, 13 * m, 5 * m))
        // Тик пришёл на 20 с раньше пяти минут — всё равно пора.
        assertTrue(NetProbe.due(10 * m, 15 * m - 20_000, 5 * m))
    }

    @Test
    fun `полоса дня - проверка красит до следующей, но не дольше удержания`() {
        val day = 1_000 * m
        val points = listOf(
            day to Mark.OK,
            day + 5 * m to Mark.OK,
            day + 10 * m to Mark.FAIL,
            // Телефон спал час — дыра без данных.
            day + 75 * m to Mark.OK,
        )
        val s = NetProbe.segments(points, day, day + 120 * m, holdMs = 10 * m)
        // Две «есть» слились в одну.
        assertEquals(NetProbe.Segment(day, day + 10 * m, Mark.OK), s[0])
        assertEquals(NetProbe.Segment(day + 10 * m, day + 20 * m, Mark.FAIL), s[1])
        assertEquals(NetProbe.Segment(day + 75 * m, day + 85 * m, Mark.OK), s[2])
        assertEquals(3, s.size)
    }

    @Test
    fun `полоса режется краями окна`() {
        val day = 1_000 * m
        val s = NetProbe.segments(listOf(day - 2 * m to Mark.FAIL), day, day + 60 * m, holdMs = 10 * m)
        assertEquals(listOf(NetProbe.Segment(day, day + 8 * m, Mark.FAIL)), s)
        assertTrue(NetProbe.segments(emptyList(), day, day + m, m).isEmpty())
    }

    @Test
    fun `доля - медленный доступ тоже доступ, нет данных не в счёт`() {
        val share = NetProbe.share(listOf(Mark.OK, Mark.SLOW, Mark.FAIL, Mark.NONE))
        assertEquals(2 to 3, share)
        assertEquals("66 %", NetProbe.percent(share))
        assertEquals("—", NetProbe.percent(0 to 0))
        assertEquals(Mark.SLOW, NetProbe.mark(NetProbe.Hit(true, 3_500, 200, "есть")))
        assertEquals(Mark.NONE, NetProbe.mark(null))
    }

    @Test
    fun `журнал - только перемены`() {
        val ok = NetProbe.Hit(true, 300, 200, "есть")
        val bad = NetProbe.Hit(false, 8000, 0, "таймаут")
        assertNull(NetProbe.change(Target.CLAUDE, true, ok))
        assertTrue(NetProbe.change(Target.CLAUDE, true, bad)!!.contains("НЕТ ДОСТУПА"))
        assertTrue(NetProbe.change(Target.CLAUDE, false, ok)!!.contains("снова есть"))
        assertTrue(NetProbe.change(Target.CLAUDE, null, ok)!!.contains("есть"))
    }
}
