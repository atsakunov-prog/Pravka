package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Звуки диктовки: «говори», «принял», «отвалились». Четвёртое издание
// (владелец, 29.09.2026: «звук не слышен теперь. Должен быть как такой мягкий
// тройной быстрый щелчок»).
class ReadyChimeTest {

    private val sr = ReadyChime.SAMPLE_RATE
    private val ready = ReadyChime.render()
    private val stop = ReadyChime.renderStop()
    private val lost = ReadyChime.renderLost()

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
    fun `звуки - нужной длины, с тишиной впереди и без щелчков на краях`() {
        val all = listOf(
            ready to ReadyChime.READY_BODY_MS,
            stop to ReadyChime.STOP_BODY_MS,
            lost to ReadyChime.LOST_BODY_MS,
        )
        for ((pcm, bodyMs) in all) {
            assertEquals(sr * (ReadyChime.LEAD_MS + bodyMs) / 1000, pcm.size)
            // Тишина впереди: наушники глотают начало звука после подъёма канала.
            val lead = sr * ReadyChime.LEAD_MS / 1000
            assertTrue((0 until lead).all { pcm[it].toInt() == 0 })
            // Кончается нулём — без щелчка на краю.
            assertEquals(0, pcm.last().toInt())
        }
    }

    @Test
    fun `громкость - ровно до своего пика`() {
        val cases = listOf(ready to ReadyChime.CLICK_PEAK, stop to ReadyChime.CLICK_PEAK, lost to ReadyChime.LOST_PEAK)
        for ((pcm, peak) in cases) {
            val max = pcm.maxOf { abs(it.toInt()) }
            val limit = (peak * Short.MAX_VALUE).toInt()
            assertTrue("пик $max выше $limit", max <= limit + 1)
            assertTrue(max >= limit - 1)
        }
        // Третье издание (0,15 и почти чистый тон 330–440 Гц) в наушниках не было слышно;
        // 30.09 щелчки попросили «чуть-чуть погромче» четвёртого издания (0,40).
        assertTrue(ReadyChime.CLICK_PEAK > 0.40)
        // Громче — но с запасом до полной шкалы: хвост комнаты не должен резаться.
        assertTrue(ReadyChime.CLICK_PEAK <= 0.8)
    }

    @Test
    fun `говори - три быстрых щелчка, принял - два`() {
        assertEquals(3, bursts(ready))
        assertEquals(2, bursts(stop))
        // Быстро: весь «говори» короче полусекунды.
        assertTrue(ReadyChime.LEAD_MS + ReadyChime.READY_BODY_MS <= 500)
    }

    @Test
    fun `частоты - в середине полосы наушников, вверх и вниз`() {
        // Костная проводимость и узкий канал гарнитуры звучат лучше всего между 0,8 и 3 кГц.
        val band = 800.0..3_000.0
        assertTrue(ReadyChime.READY_HZ.all { it in band })
        assertTrue(ReadyChime.STOP_HZ.all { it in band })
        assertTrue(ReadyChime.LOST_HZ in 400.0..3_000.0 && ReadyChime.LOST_END_HZ in 400.0..3_000.0)
        val up = ReadyChime.READY_HZ
        assertTrue((1 until up.size).all { up[it] > up[it - 1] })
        val down = ReadyChime.STOP_HZ
        assertTrue((1 until down.size).all { down[it] < down[it - 1] })
        assertTrue(ReadyChime.LOST_END_HZ < ReadyChime.LOST_HZ)
    }

    @Test
    fun `три звука - разные`() {
        assertFalse(ready.contentEquals(stop))
        assertFalse(ready.contentEquals(lost))
        assertFalse(stop.contentEquals(lost))
    }

    /** Сколько ударов в звуке: громкость по 5 мс, считаем подъёмы выше трети пика. */
    private fun bursts(pcm: ShortArray): Int {
        val frame = sr * 5 / 1000
        val rms = (0 until pcm.size / frame).map { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += pcm[i].toDouble() * pcm[i]
            sqrt(sum / frame)
        }
        val top = rms.maxOrNull() ?: return 0
        var count = 0
        var above = false
        for (v in rms) {
            if (!above && v > top / 3) {
                count++
                above = true
            } else if (above && v < top / 6) {
                above = false
            }
        }
        return count
    }
}
