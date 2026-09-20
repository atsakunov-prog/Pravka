package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Сколько идёт запрос — по своей истории (docs/telo.md, «Прогресс по кромке»).
// Прямая «основание + наклон × символы» на каждую пару «дорога + модель».
class PaceTest {

    /** Дорога, где запрос идёт 2 секунды плюс 5 мс на символ. */
    private fun honest(points: Int, base: Long = 2000L, perChar: Double = 5.0): Pace.Acc {
        var acc = Pace.Acc()
        for (i in 0 until points) {
            val chars = 100 + (i % 10) * 120
            acc = Pace.add(acc, chars, base + (perChar * chars).toLong())
        }
        return acc
    }

    @Test
    fun `без единого замера обещаем вслепую`() {
        assertEquals(Pace.BLIND_MS, Pace.estimate(null, 500))
        assertEquals(Pace.BLIND_MS, Pace.estimate(Pace.Acc(), 500))
    }

    @Test
    fun `пока замеров мало - простое среднее, а не прямая`() {
        var acc = Pace.Acc()
        acc = Pace.add(acc, 100, 1_000)
        acc = Pace.add(acc, 900, 9_000)
        assertTrue(acc.n < Pace.MIN_FOR_LINE)
        // Двух точек хватило бы на прямую формально, но не по существу:
        // держимся среднего, пока история не набралась.
        val short = Pace.estimate(acc, 100)
        val long = Pace.estimate(acc, 900)
        assertEquals("на двух замерах длина ещё не влияет", short, long)
        assertTrue(short in 4_000..6_000)
    }

    @Test
    fun `на истории прямая ловит и основание, и наклон`() {
        val acc = honest(points = 40)
        assertEquals(2_500.0, Pace.estimate(acc, 100).toDouble(), 250.0)
        assertEquals(7_000.0, Pace.estimate(acc, 1000).toDouble(), 500.0)
        assertTrue(Pace.estimate(acc, 1000) > Pace.estimate(acc, 100))
    }

    @Test
    fun `отрицательный наклон - это шум, берём среднее`() {
        var acc = Pace.Acc()
        // «Чем длиннее, тем быстрее» законом не бывает: так выглядит кэш,
        // поймавший длинный запрос, и строить на этом прямую нельзя.
        for (i in 0 until 20) acc = Pace.add(acc, 100 + i * 100, 9_000L - i * 300L)
        val short = Pace.estimate(acc, 100)
        val long = Pace.estimate(acc, 2000)
        assertEquals(short, long)
    }

    @Test
    fun `смена модели забывается за неделю работы`() {
        // Длина тут одна на все замеры: проверяем именно забывание, а не
        // прямую — при постоянной длине прямой строить не из чего.
        var acc = Pace.Acc()
        repeat(60) { acc = Pace.add(acc, 500, 20_000L) }   // медленная модель
        val before = Pace.estimate(acc, 500)
        assertEquals(20_000.0, before.toDouble(), 500.0)
        repeat(120) { acc = Pace.add(acc, 500, 2_000L) }   // владелец сменил на быструю
        val after = Pace.estimate(acc, 500)
        assertTrue("старое должно потускнеть", after < before / 2)
        assertTrue("но и не забыться мгновенно", after > 2_000L)
    }

    @Test
    fun `дикий замер не уводит оценку за край`() {
        var acc = Pace.Acc()
        for (i in 0 until 20) acc = Pace.add(acc, 200, 3_000)
        acc = Pace.add(acc, 200, 10_000_000)   // сеть умерла и ответ пришёл через час
        assertTrue(Pace.estimate(acc, 200) <= Pace.MAX_MS)
    }

    @Test
    fun `дуга идёт ровно до срока и дальше только ползёт`() {
        val t = 4_000L
        assertEquals(0f, Pace.progress(0, t), 0.001f)
        assertEquals(0.45f, Pace.progress(t / 2, t), 0.01f)
        // К сроку дуга почти полная: раньше она стояла на трёх четвертях, и
        // владелец читал это как «полоса отстаёт от работы».
        assertEquals(0.9f, Pace.progress(t, t), 0.01f)
        // После срока — ползёт и НИКОГДА не доходит до края: полоса, упёршаяся
        // в конец и замершая, читается как «повисло».
        val late = Pace.progress(t * 3, t)
        assertTrue(late > 0.9f)
        assertTrue(late < 1f)
        assertTrue(Pace.progress(t * 100, t) > late)
        // И даже через сто сроков круг не замкнётся: замкнуть его может
        // только пришедший ответ.
        assertEquals(Pace.CEILING, Pace.progress(t * 100, t), 0f)
        assertTrue(Pace.CEILING < 1f)
    }

    @Test
    fun `дорога рассказывает о себе числами, а не на словах`() {
        assertEquals(null, Pace.line(null))
        val acc = honest(points = 40)
        val l = Pace.line(acc)!!
        assertTrue("на истории должна быть прямая", l.straight)
        assertEquals(2_000.0, l.baseMs, 300.0)
        assertEquals(5.0, l.msPerChar, 0.5)
        assertTrue(l.n > 30.0)
        // Мало замеров — прямой нет, но среднее есть и честно так названо.
        val few = Pace.add(Pace.Acc(), 300, 3_000)
        assertEquals(false, Pace.line(few)!!.straight)
        assertEquals(3_000.0, Pace.line(few)!!.meanMs, 1.0)
    }

    @Test
    fun `заводская прикидка отдаёт ровно ту прямую, что в неё положили`() {
        val acc = Pace.prior(1_400.0, 19.0)
        val l = Pace.line(acc)!!
        assertTrue("прикидка должна быть прямой, иначе короткие получат время длинных", l.straight)
        assertEquals(1_400.0, l.baseMs, 1.0)
        assertEquals(19.0, l.msPerChar, 0.01)
        assertEquals(1_400L + 19 * 150, Pace.estimate(acc, 150))
        assertTrue(l.n >= Pace.MIN_FOR_LINE)
        // Пустая дорога с прикидкой — уже не вслепую.
        assertEquals(Pace.estimate(acc, 150), Pace.estimate(Pace.mix(null, acc), 150))
        assertTrue(Pace.estimate(Pace.mix(null, acc), 900) > Pace.BLIND_MS)
    }

    @Test
    fun `прикидка тает и к дюжине своих замеров исчезает совсем`() {
        // Прикидка «медленно» (4 с + 20 мс на знак) против настоящих
        // 1 с + 2 мс: нарочно мимо, чтобы было видно, кто кого тянет.
        val guess = Pace.prior(4_000.0, 20.0)
        var own = Pace.Acc()
        fun seen(chars: Int) = Pace.estimate(Pace.mix(own, guess), chars)
        val blind = seen(300)
        assertEquals("пока замеров нет — живём прикидкой", 10_000.0, blind.toDouble(), 1.0)

        repeat(4) { i -> own = Pace.add(own, 100 + i * 200, 1_000L + 2 * (100 + i * 200)) }
        val early = seen(300)
        assertTrue("первые замеры уже сдвинули оценку", early < blind * 0.8)

        repeat(12) { i -> own = Pace.add(own, 100 + (i % 6) * 200, 1_000L + 2 * (100 + (i % 6) * 200)) }
        val settled = seen(300)
        // Дюжина своих замеров — прикидки в оценке больше нет вообще.
        assertEquals("осталась ровно своя прямая", 1_600.0, settled.toDouble(), 60.0)
        assertEquals(Pace.estimate(own, 300), settled)
        // И число замеров — своё, а не своё плюс заводское.
        assertTrue(own.n > Pace.PRIOR_FADE_AT)
        // Чуть меньше шестнадцати: каждый новый замер приглушает прошлые.
        assertEquals(16.0, own.n, 1.5)
    }

    @Test
    fun `без ожидания дуги нет`() {
        assertEquals(0f, Pace.progress(1_000, 0), 0f)
        assertEquals(0f, Pace.progress(-5, 4_000), 0f)
    }
}
