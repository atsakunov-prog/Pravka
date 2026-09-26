package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ночная калибровка секунд (владелец, 26.09.2026: «раз в день ночью она может
// запускать этот расчёт… просто пересчитывать всю популяцию»).
class PaceTuneTest {

    private val prior = Pace.prior(2_000.0, 10.0)
    private val lengths = intArrayOf(40, 70, 134, 134, 283, 90, 590, 60, 200, 134)
    private val minute = 60_000L

    /** Детерминированный «шум»: без Random, чтобы тест не мигал. */
    private fun noise(i: Int, amp: Long): Long = ((i * 7919L) % 17 - 8) * amp / 8

    private fun history(n: Int, ms: (Int, Int) -> Long, cache: (Int) -> Boolean? = { true }): List<PaceTune.Sample> {
        var at = 1_000_000_000L
        return (0 until n).map { i ->
            at += 5 * minute
            val chars = lengths[i % lengths.size]
            PaceTune.Sample(at, chars, ms(i, chars), cache(i))
        }
    }

    @Test
    fun `меньше двадцати замеров - калибровать не по чему`() {
        assertNull(PaceTune.tune(history(19, { _, c -> 2_000L + 10L * c }), prior))
    }

    @Test
    fun `прогон заводским способом - ровно то же, что копилось на ходу`() {
        // Ночь не должна придумывать другие числа из тех же замеров: заводской
        // прогон всей истории совпадает с тем, что дорога накопила сама.
        val samples = history(60, { i, c -> 3_000L + 20L * c + noise(i, 400) })
        var online: Pace.Road? = null
        for (s in samples) online = Pace.learn(online, prior, s.chars, s.ms, s.cache, s.at)
        val (replayed, _) = PaceTune.replay(samples, prior, null, Pace.Tune())
        assertEquals(online!!.acc, replayed.acc)
        assertEquals(online.errAbs, replayed.errAbs, 1e-6)
    }

    @Test
    fun `ровная дорога остаётся на заводском`() {
        // Правда — прямая с небольшим шумом: заводской способ уже хорош, и
        // менять его ради выигрыша в пределах шума незачем.
        val r = PaceTune.tune(history(300, { i, c -> 2_500L + 12L * c + noise(i, 200) }), prior)!!
        assertTrue(r.miss <= r.factoryMiss)
        assertEquals(300, r.n)
    }

    @Test
    fun `дорога, которая меняется, получает быстрое забывание`() {
        // Каждые сто запросов скорость прыгает вдвое (сменили модель, API то
        // перегружен, то нет): кто быстрее забывает, тот меньше промахивается.
        val samples = history(600, { i, c -> (if ((i / 100) % 2 == 0) 2_000L else 5_000L) + 10L * c })
        val r = PaceTune.tune(samples, prior)!!
        assertEquals(0.97, r.tune.decay, 1e-9)
        assertTrue("выбранный ${r.miss} против заводского ${r.factoryMiss}", r.miss < r.factoryMiss * 0.9)
    }

    @Test
    fun `длинный хвост - обещание сдвигается к медиане`() {
        // Обычно 3 с, но каждый пятый ответ — 9 с (Опус задумался). Среднее
        // тянет обещание вверх, и отсчёт промахивается на обычных; медиана
        // ближе к тому, что бывает чаще.
        val samples = history(300, { i, _ -> if (i % 5 == 0) 9_000L else 3_000L })
        val r = PaceTune.tune(samples, prior)!!
        assertTrue("сдвиг ${r.tune.shift}", r.tune.shift < -300.0)
        assertTrue(r.miss < r.factoryMiss * 0.9)
    }

    @Test
    fun `калибровка пора раз в сутки ночью, а проспал - через тридцать часов`() {
        val h = 3_600_000L
        val now = 100 * h
        assertEquals(false, PaceTune.due(now, now - 10 * h, 3))
        assertTrue(PaceTune.due(now, now - 21 * h, 3))
        assertEquals(false, PaceTune.due(now, now - 21 * h, 14))
        assertTrue(PaceTune.due(now, now - 31 * h, 14))
        // Не было ни разу — сразу, в любой час.
        assertTrue(PaceTune.due(now, 0L, 14))
    }

    @Test
    fun `способ словами`() {
        assertEquals(69, PaceTune.halfLife(Pace.DECAY))
        assertEquals(23, PaceTune.halfLife(0.97))
        val text = PaceTune.describe(Pace.Tune(decay = 0.97, clip = false, cold = true, shift = -400.0))
        assertTrue(text, text.contains("23") && text.contains("не режет") && text.contains("-0.4"))
    }

    @Test
    fun `кандидатов двенадцать, заводской среди них`() {
        assertEquals(12, PaceTune.candidates.size)
        assertTrue(Pace.Tune() in PaceTune.candidates)
    }
}
