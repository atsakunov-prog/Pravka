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

    // ---- Дорога целиком (26.09.2026: «важно, чтобы было именно точнее») ----

    private val flat = Pace.prior(2_000.0, 10.0)
    private val hour = 60 * 60_000L

    /** Длины как у владельца: медиана 134, хвост до шестисот (журнал распознавания). */
    private val lengths = intArrayOf(40, 70, 134, 134, 283, 90, 590, 60, 200, 134)

    @Test
    fun `холодный кэш учится своей добавкой, тёплые не страдают`() {
        // Правда: 2 с + 10 мс на знак, холодный запрос — ещё полторы секунды.
        var road: Pace.Road? = null
        var now = 1_000_000_000L
        for (i in 0 until 120) {
            val cold = i % 4 == 0
            now += if (cold) 2 * hour else 5 * 60_000L
            val chars = lengths[i % lengths.size]
            val ms = 2_000L + 10L * chars + (if (cold) 1_500L else 0L)
            road = Pace.learn(road, flat, chars, ms, cache = !cold, now = now)
        }
        val warm = Pace.expect(road, flat, 134, now + 60_000L)
        val cold = Pace.expect(road, flat, 134, now + 2 * hour)
        assertEquals(3_340.0, warm.toDouble(), 250.0)
        assertEquals(1_500.0, (cold - warm).toDouble(), 400.0)
    }

    @Test
    fun `дорога, где кэш всегда холодный, обещает ровно своё`() {
        // Еда: раз в несколько часов, кэш холодный каждый раз. Добавка не
        // должна считаться дважды — ни в прямой, ни сверху.
        var road: Pace.Road? = null
        var now = 1_000_000_000L
        for (i in 0 until 60) {
            now += 3 * hour
            val chars = lengths[i % lengths.size]
            road = Pace.learn(road, flat, chars, 5_000L + 20L * chars, cache = false, now = now)
        }
        val next = Pace.expect(road, flat, 134, now + 3 * hour)
        assertEquals(5_000.0 + 20 * 134, next.toDouble(), 400.0)
    }

    @Test
    fun `минута плохой сети не уводит прямую`() {
        var road: Pace.Road? = null
        var now = 1_000_000_000L
        repeat(40) { i ->
            now += 60_000L
            road = Pace.learn(road, flat, 134, 3_000L + (i % 3) * 100L, cache = true, now = now)
        }
        val before = Pace.expect(road, flat, 134, now + 60_000L)
        now += 60_000L
        road = Pace.learn(road, flat, 134, 60_000L, cache = true, now = now)
        val after = Pace.expect(road, flat, 134, now + 60_000L)
        assertTrue("выброс сдвинул на ${after - before} мс", after - before < 300)
        // А промах этого запроса честно записан целиком — его не прячут.
        assertTrue(road!!.miss!! > 2_000.0)
    }

    @Test
    fun `настоящая перемена всё равно доходит`() {
        // Модель стала вдвое медленнее — это не выброс, а новая правда.
        var road: Pace.Road? = null
        var now = 1_000_000_000L
        repeat(40) { now += 60_000L; road = Pace.learn(road, flat, 134, 3_000L, cache = true, now = now) }
        repeat(200) { now += 60_000L; road = Pace.learn(road, flat, 134, 6_000L, cache = true, now = now) }
        assertEquals(6_000.0, Pace.expect(road, flat, 134, now + 60_000L).toDouble(), 500.0)
    }

    @Test
    fun `промах падает, пока дорога учится`() {
        // Заводская прикидка нарочно мимо: правда — 5 с + 30 мс на знак.
        var road: Pace.Road? = null
        var now = 1_000_000_000L
        var early = 0.0
        for (i in 0 until 60) {
            now += 60_000L
            val chars = lengths[i % lengths.size]
            road = Pace.learn(road, flat, chars, 5_000L + 30L * chars, cache = true, now = now)
            if (i == 2) early = road!!.miss!!
        }
        val late = road!!.miss!!
        assertTrue("было $early, стало $late", late < early / 4)
        // И знак говорит, в какую сторону врали: прикидка обещала меньше.
        assertTrue(Pace.learn(null, flat, 134, 9_000L, cache = null, now = now).bias!! > 0)
    }

    @Test
    fun `холодным считается дорога после часа тишины`() {
        val now = 10 * hour
        assertTrue(Pace.coldLikely(null, now))
        assertTrue(Pace.coldLikely(Pace.Road(), now))
        assertEquals(false, Pace.coldLikely(Pace.Road(lastAt = now - 10 * 60_000L), now))
        assertTrue(Pace.coldLikely(Pace.Road(lastAt = now - hour), now))
    }

    @Test
    fun `замеры без длины встают на типичную длину и не врут среднему`() {
        var zero = Pace.Acc()
        repeat(50) { zero = Pace.add(zero, 0, 7_000L) }
        val moved = Pace.relocate(zero, 134.0, 4.0)
        assertEquals(4.0, moved.n, 1e-9)
        assertEquals(7_000L, Pace.estimate(moved, 134))
        // Новые замеры с длиной дают наклон, старое держит середину.
        var acc = moved
        for (i in 0 until 20) {
            val chars = lengths[i % lengths.size]
            acc = Pace.add(acc, chars, 4_000L + 20L * chars)
        }
        assertTrue(Pace.estimate(acc, 590) > Pace.estimate(acc, 40) + 5_000)
    }
}
