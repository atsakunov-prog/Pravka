package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Звуки диктовки: «говори», «принял», «отвалились» (владелец, 29.09.2026:
// «очень мелодичный, приятный звук»; второе издание — «очень высокий звук.
// Чуть помелодичнее и чуть тише. И с таким эхом»).
class ReadyChimeTest {

    private val sr = ReadyChime.SAMPLE_RATE
    private val all = listOf(
        "говори" to (ReadyChime.render() to ReadyChime.READY_BODY_MS),
        "принял" to (ReadyChime.renderStop() to ReadyChime.STOP_BODY_MS),
        "отвалились" to (ReadyChime.renderLost() to ReadyChime.LOST_BODY_MS),
    )

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
        for ((name, v) in all) {
            val (pcm, bodyMs) = v
            assertEquals(name, sr * (ReadyChime.LEAD_MS + bodyMs) / 1000, pcm.size)
            // Тишина впереди: наушники глотают начало звука после подъёма канала.
            val lead = sr * ReadyChime.LEAD_MS / 1000
            assertTrue(name, (0 until lead).all { pcm[it].toInt() == 0 })
            // Кончается нулём — без щелчка.
            assertEquals(name, 0, pcm.last().toInt())
        }
    }

    @Test
    fun `громкость - не выше пика и без перегруза, и тише прежнего`() {
        val limit = (ReadyChime.PEAK * Short.MAX_VALUE).toInt()
        for ((name, v) in all) {
            val peak = v.first.maxOf { abs(it.toInt()) }
            assertTrue("$name: пик $peak выше $limit", peak <= limit + 1)
            // И звук действительно есть, а не почти тишина.
            assertTrue(name, peak >= limit - 1)
        }
        // Первое издание било на 0,34 шкалы: «чуть тише»; второе — 0,24: «спокойнее и тише».
        assertTrue(ReadyChime.PEAK < 0.24)
    }

    @Test
    fun `ноты - на октаву ниже первого издания`() {
        // Первое издание — соль и ре второй-третьей октавы (784 и 1175 Гц): «очень высокий».
        assertTrue(ReadyChime.READY_HZ.all { it < 700.0 })
        assertTrue(ReadyChime.STOP_HZ.all { it < 700.0 })
        assertTrue(ReadyChime.LOST_HZ < 700.0 && ReadyChime.LOST_END_HZ < ReadyChime.LOST_HZ)
        // «Говори» — вверх, «принял» — вниз: не спутать, не глядя на экран.
        val up = ReadyChime.READY_HZ
        assertTrue((1 until up.size).all { up[it] > up[it - 1] })
        val down = ReadyChime.STOP_HZ
        assertTrue((1 until down.size).all { down[it] < down[it - 1] })
    }

    @Test
    fun `у звука есть эхо - хвост после нот, затухающий к концу`() {
        for ((name, v) in all) {
            val pcm = v.first
            val lead = sr * ReadyChime.LEAD_MS / 1000
            val peak = pcm.maxOf { abs(it.toInt()) }.toDouble()
            // Через полсекунды после удара ноты давно отзвучали бы сухими —
            // слышно там только эхо и зал.
            val tail = rms(pcm, lead + sr * 600 / 1000, sr * 200 / 1000)
            assertTrue("$name: хвоста нет (${tail / peak})", tail > peak * 0.01)
            // И он гаснет, а не гудит до конца.
            val end = rms(pcm, pcm.size - sr * 150 / 1000, sr * 150 / 1000)
            assertTrue("$name: хвост не гаснет", end < tail)
        }
    }

    @Test
    fun `три звука - разные`() {
        val sounds = all.map { it.second.first }
        for (i in sounds.indices) for (j in i + 1 until sounds.size) {
            assertFalse(sounds[i].contentEquals(sounds[j]))
        }
    }

    private fun rms(pcm: ShortArray, from: Int, len: Int): Double {
        var sum = 0.0
        for (i in from until (from + len).coerceAtMost(pcm.size)) sum += pcm[i].toDouble() * pcm[i]
        return sqrt(sum / len)
    }
}
