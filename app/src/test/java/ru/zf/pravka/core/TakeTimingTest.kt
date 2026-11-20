package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TakeTimingTest {

    @Test
    fun `слова двух строк по времени, сеть отдельно от счёта, стоп и ожидание`() {
        val t = TakeTiming(startAt = 10_000)
        // Google: первое слово на 0,6 с, дальше по слову в 0,4 с
        t.google(10_600, "иван")
        t.google(11_000, "иван привет")
        t.google(11_400, "иван привет скажи")
        // комп: те же слова на 0,5 с позже; строка досчитана до звука, прозвучавшего за 0,3 с до прихода
        t.pc(11_100, 1, audioAt = 10_800, passMs = 180)
        t.pc(11_500, 2, audioAt = 11_200, passMs = 170)
        t.pc(11_900, 3, audioAt = 11_600, passMs = 190)
        t.chunk(rttMs = 260, serverMs = 180)
        t.chunk(rttMs = 240, serverMs = 170)
        t.chunk(rttMs = 300, serverMs = 190)
        t.stop(12_000)
        t.googleFinal(12_700)
        t.pcFinal(13_100, waited = 400, serverMs = 350)
        val mux = LineMux(pcMode = true).apply {
            onGoogle("иван", 10_600)
            onPc("иван", 11_100)
            close(12_000)
        }
        val s = t.summary(line = "pc", inserted = "pc", mux = mux)
        assertEquals(600L, s.getLong("g_first"))
        assertEquals(1_100L, s.getLong("pc_first"))
        assertEquals(500L, s.getLong("word_lag_med"))
        assertEquals(300L, s.getLong("pc_lag_med"))
        assertEquals(260L, s.getLong("rtt_med"))
        assertEquals(180L, s.getLong("srv_med"))
        assertEquals(80L, s.getLong("net_med"))
        assertEquals(3, s.getInt("chunks"))
        assertEquals(180L, s.getLong("pass_med"))
        assertEquals(2_000L, s.getLong("stop"))
        assertEquals(700L, s.getLong("stop_g"))
        assertEquals(1_100L, s.getLong("stop_pc"))
        assertEquals(400L, s.getLong("wait"))
        assertEquals(900L, s.getLong("pc_shown_ms"))
        assertEquals(3, s.getJSONArray("g_words").length())
        assertEquals(1_900L, s.getJSONArray("pc_words").getLong(2))
    }

    @Test
    fun `только Google — без полей компа`() {
        val t = TakeTiming(0)
        t.google(500, "привет как дела")
        t.stop(2_000)
        t.googleFinal(2_400)
        val s = t.summary(line = "google", inserted = "google", mux = null)
        assertEquals(500L, s.getLong("g_first"))
        assertEquals(400L, s.getLong("stop_g"))
        assertFalse(s.has("pc_first") || s.has("rtt_med") || s.has("word_lag_med"))
        // три слова пришли разом — у всех одно время
        assertEquals(500L, s.getJSONArray("g_words").getLong(2))
    }
}
