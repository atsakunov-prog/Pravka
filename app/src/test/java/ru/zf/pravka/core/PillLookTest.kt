package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Вид и движение пилюли диктовки (docs/pravka.md, «Пилюля диктовки»): цвет
// режима, но спокойнее и прозрачнее; всплытие с одним «оп» сверху.
class PillLookTest {

    private val orange = 0xFFEA580C.toInt()  // «П»
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)
    private fun chroma(c: Int) = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))

    @Test
    fun `смесь - концы и середина`() {
        assertEquals(orange, PillLook.mix(orange, PillLook.INK, 0f))
        assertEquals(PillLook.INK and 0xFFFFFF, PillLook.mix(orange, PillLook.INK, 1f) and 0xFFFFFF)
        // Альфа — от первого цвета.
        assertEquals(0xFF, (PillLook.mix(orange, 0x00000000, 0.5f) ushr 24))
    }

    @Test
    fun `спокойнее - это глубже, а не бледнее`() {
        val start = PillLook.bodyStart(orange)
        val end = PillLook.bodyEnd(orange)
        // Насыщенность и яркость ниже чистого цвета: белый текст читается.
        assertTrue(chroma(start) < chroma(orange) && chroma(end) < chroma(orange))
        assertTrue(luma(start) < luma(orange) && luma(end) < luma(orange))
        // Тон узнаётся: красный канал — по-прежнему главный.
        assertTrue(r(start) > g(start) && r(start) > b(start))
        assertTrue(r(end) > g(end) && r(end) > b(end))
    }

    @Test
    fun `градиент - к кружку гуще, а сам кружок ярче всего`() {
        val start = PillLook.bodyStart(orange)
        val end = PillLook.bodyEnd(orange)
        val orb = PillLook.orb(orange)
        assertTrue(chroma(end) > chroma(start))
        assertTrue(chroma(orb) > chroma(end))
    }

    @Test
    fun `плотность с завода прозрачнее прежней строки`() {
        assertTrue(PillLook.DENSITY_DEFAULT < 0.82f)
        assertTrue(PillLook.DENSITY_DEFAULT in PillLook.DENSITY_MIN..PillLook.DENSITY_MAX)
    }

    @Test
    fun `на прозрачном стекле край и свечение остаются`() {
        assertTrue(PillLook.rimAlpha(PillLook.DENSITY_MIN) > 0.1f)
        assertTrue(PillLook.glowAlpha(PillLook.DENSITY_MIN) > 0.1f)
        // И растут с плотностью, но не выше заливки.
        assertTrue(PillLook.glowAlpha(1f) > PillLook.glowAlpha(0.3f))
        assertTrue(PillLook.glowAlpha(1f) <= 1f && PillLook.rimAlpha(1f) <= 1f)
    }

    @Test
    fun `пружина - из нуля в единицу`() {
        assertEquals(0f, PillLook.spring(0f), 1e-6f)
        assertEquals(1f, PillLook.spring(1f), 0.01f)
    }

    @Test
    fun `пружина проскакивает один раз - оп назад`() {
        val samples = (0..1000).map { PillLook.spring(it / 1000f) }
        val peak = samples.max()
        assertTrue("проскок есть — иначе «оп» не видно", peak > 1.05f)
        assertEquals(1f + PillLook.overshoot(), peak, 0.01f)
        // Второй проскок — уже не виден глазу (меньше двух процентов пути).
        val peakAt = samples.indexOf(peak)
        val dipAt = (peakAt until samples.size).first { samples[it] < 1f }
        val later = samples.drop(dipAt).max()
        assertTrue(later - 1f < 0.02f)
    }

    @Test
    fun `запас окна вмещает проскок`() {
        assertTrue(PillLook.overshoot() * PillLook.RISE_DP <= PillLook.ROOM_DP)
    }

    @Test
    fun `сверху - запас под пилюлей вмещает проскок вниз`() {
        // Сверху путь длиннее (из-под строки состояния целиком), и проскок с ним.
        assertTrue(PillLook.topTravelDp() > PillLook.HEIGHT_DP)
        assertTrue(PillLook.overshoot() * PillLook.topTravelDp() <= PillLook.TOP_ROOM_DP)
    }

    @Test
    fun `пилюля видна целиком к первой трети`() {
        assertEquals(0f, PillLook.enterAlpha(0f), 0f)
        assertEquals(1f, PillLook.enterAlpha(0.3f), 1e-6f)
        assertEquals(1f, PillLook.enterAlpha(1f), 0f)
    }

    @Test
    fun `волна - в тишине значок, от голоса растёт`() {
        val quiet = PillLook.bars(0f)
        val loud = PillLook.bars(1f)
        assertTrue("середина длиннее краёв", quiet[1] > quiet[0] && quiet[1] > quiet[2])
        for (i in 0..2) assertTrue(loud[i] > quiet[i])
        assertTrue(loud.all { it <= 1f })
    }
}
