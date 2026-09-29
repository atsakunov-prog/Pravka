package ru.zf.pravka.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Звук «говори» в наушники (владелец, 29.09.2026: «очень мелодичный,
// приятный звук, когда уже понятно, что можно говорить»).
class ReadyChimeTest {

    @Test
    fun `с завода звенит только в наушниках`() {
        val mode = ReadyChime.Mode.fromKey(null)
        assertEquals(ReadyChime.Mode.HEADSET, mode)
        assertTrue(ReadyChime.shouldPlay(mode, headset = true))
        // На телефоне хватает пилюли и вибрации.
        assertFalse(ReadyChime.shouldPlay(mode, headset = false))
        assertTrue(ReadyChime.shouldPlay(ReadyChime.Mode.ALWAYS, headset = false))
        assertFalse(ReadyChime.shouldPlay(ReadyChime.Mode.OFF, headset = true))
        // Незнакомое слово в настройке — заводское, а не тишина.
        assertEquals(ReadyChime.Mode.HEADSET, ReadyChime.Mode.fromKey("мусор"))
    }

    @Test
    fun `звук - нужной длины, с тишиной впереди и без щелчков на краях`() {
        val pcm = ReadyChime.render()
        val sr = ReadyChime.SAMPLE_RATE
        assertEquals(sr * (ReadyChime.LEAD_MS + ReadyChime.BODY_MS) / 1000, pcm.size)
        // Тишина впереди: наушники глотают начало звука после подъёма канала.
        val lead = sr * ReadyChime.LEAD_MS / 1000
        assertTrue((0 until lead).all { pcm[it].toInt() == 0 })
        // Кончается нулём — без щелчка.
        assertEquals(0, pcm.last().toInt())
    }

    @Test
    fun `громкость - не выше пика и без перегруза`() {
        val pcm = ReadyChime.render()
        val peak = pcm.maxOf { abs(it.toInt()) }
        val limit = (ReadyChime.PEAK * Short.MAX_VALUE).toInt()
        assertTrue("пик $peak выше $limit", peak <= limit + 1)
        // И звук действительно есть, а не почти тишина.
        assertTrue(peak >= limit - 1)
    }
}
