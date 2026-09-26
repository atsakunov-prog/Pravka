package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Settings

// С чего дуга начинает и что в журнале правок считать замером
// (docs/telo.md, «Прогресс по кромке»).
class PaceSeedTest {

    @Test
    fun `Опус 5_5 дороже и на знак, и на старте - думает даже над короткой`() {
        // Журнал правок 27.07–26.09: старт (сеть, первый токен) у моделей один,
        // но Опус 5.5 на medium думает ~160 токенов ещё до текста — это и есть
        // его лишнее основание; и около токена на знак сверху — цена знака.
        val opus = PaceSeed.guess(Settings.MODEL_OPUS)
        val sonnet = PaceSeed.guess(Settings.MODEL_SONNET)
        assertTrue("на знак Опус дороже втрое", opus.msPerChar > sonnet.msPerChar * 2.5)
        assertTrue("и стартует позже", opus.baseMs > sonnet.baseMs + 1_000)
        assertEquals("снято на medium", "medium", opus.effort)
    }

    @Test
    fun `отношение моделей растёт с длиной - одним числом его не передать`() {
        // Владелец: «опус на том же объёме будет в 1.2 раза медленнее» — на деле
        // вдвое на средней фразе (140 знаков) и больше на длинной.
        fun ms(model: String, chars: Int) = Pace.estimate(PaceSeed.prior(model), chars).toDouble()
        val mid = ms(Settings.MODEL_OPUS, 140) / ms(Settings.MODEL_SONNET, 140)
        assertEquals(2.0, mid, 0.15)
        val short = ms(Settings.MODEL_OPUS, 40) / ms(Settings.MODEL_SONNET, 40)
        val long = ms(Settings.MODEL_OPUS, 600) / ms(Settings.MODEL_SONNET, 600)
        assertTrue("короткая $short, длинная $long", long > short + 0.5)
    }

    @Test
    fun `прикидка на снятом усилии - ровно замер, на других - от него`() {
        val o = Settings.MODEL_OPUS
        assertEquals(3_170.0 + 14.2 * 140, Pace.estimate(PaceSeed.prior(o, "medium"), 140).toDouble(), 2.0)
        assertEquals(Pace.estimate(PaceSeed.prior(o, "medium"), 140), Pace.estimate(PaceSeed.prior(o, ""), 140))
        assertTrue(Pace.estimate(PaceSeed.prior(o, "xhigh"), 140) > Pace.estimate(PaceSeed.prior(o, "medium"), 140) * 1.5)
        // Опус 5 — своя строка, а не Сонет по незнакомству.
        assertTrue(PaceSeed.guess(Settings.MODEL_OPUS_5) != PaceSeed.guess(Settings.MODEL_SONNET))
    }

    @Test
    fun `перенос прямой на другую модель - двумя коэффициентами`() {
        val s = Settings.MODEL_SONNET
        val o = Settings.MODEL_OPUS
        // Дорога на Сонете шла ровно по заводской прямой Сонета — на Опусе
        // она должна пойти ровно по заводской прямой Опуса.
        val sonnetLine = Pace.line(PaceSeed.prior(s))!!
        val moved = Pace.line(PaceSeed.transfer(sonnetLine, s, "", o, "medium"))!!
        assertEquals(3_170.0, moved.baseMs, 5.0)
        assertEquals(14.2, moved.msPerChar, 0.05)
        // Дорога на Сонете вдвое медленнее заводской (длинный промпт, JSON) —
        // и на Опусе она вдвое медленнее заводской Опуса: форма дороги своя.
        val slowRoad = Pace.Line(n = 40.0, meanMs = 0.0, baseMs = 3_860.0, msPerChar = 9.0, straight = true)
        val slowMoved = Pace.line(PaceSeed.transfer(slowRoad, s, "", o, "medium"))!!
        assertEquals(6_340.0, slowMoved.baseMs, 10.0)
        assertEquals(28.4, slowMoved.msPerChar, 0.1)
        // Соседка без длины (одно среднее) — уровень переносится, наклон — заводской Опуса.
        val flat = Pace.Line(n = 20.0, meanMs = 2_560.0, baseMs = 2_560.0, msPerChar = 0.0, straight = false)
        val flatMoved = Pace.line(PaceSeed.transfer(flat, s, "", o, "medium"))!!
        assertEquals(14.2, flatMoved.msPerChar, 0.05)
        assertEquals(5_070.0, Pace.estimate(PaceSeed.transfer(flat, s, "", o, "medium"), 134).toDouble(), 80.0)
    }

    @Test
    fun `незнакомая модель не получает скорость Опуса`() {
        assertEquals(PaceSeed.guess(Settings.MODEL_SONNET), PaceSeed.guess("gpt-нечто"))
    }

    @Test
    fun `режимы журнала ложатся на дорогу Правки, чужие — никуда`() {
        for (mode in listOf("CLEAN", "BUSINESS", "SOFTEN", "ASSIST_REPLY", "ASSIST_TRANSLATE")) {
            assertEquals(mode, ModelRoute.PRAVKA.key, PaceSeed.route(mode))
        }
        // Чужая запись лучше пропадёт, чем ляжет замером не на ту дорогу.
        assertNull(PaceSeed.route("ZASECHKA"))
        assertNull(PaceSeed.route(""))
    }

    @Test
    fun `замер засчитывается только запросам по сети`() {
        assertTrue(PaceSeed.counts(Settings.MODEL_OPUS))
        assertTrue(PaceSeed.counts(Settings.MODEL_FABLE))
        // Опыт августа: модель считала на самом телефоне, к ожиданию
        // сетевого ответа её время отношения не имеет.
        assertTrue(!PaceSeed.counts("gemini-nano"))
        assertTrue(!PaceSeed.counts(""))
    }

    @Test
    fun `короткое имя модели читается`() {
        assertEquals("opus-5-5", PaceSeed.shortModel(Settings.MODEL_OPUS))
        assertEquals("sonnet-5", PaceSeed.shortModel(Settings.MODEL_SONNET))
        assertEquals("fable-5-1", PaceSeed.shortModel(Settings.MODEL_FABLE))
        // Строка настроек называет все три модели и цену знака у каждой.
        val line = PaceSeed.factoryLine()
        assertTrue(line, line.contains("opus-5") && line.contains("sonnet-5"))
        assertTrue(line, line.indexOf("opus-5") < line.indexOf("sonnet-5"))
    }

    @Test
    fun `усилие растягивает прикидку, «по умолчанию» — это действующее усилие`() {
        fun ms(model: String, effort: String) = Pace.estimate(PaceSeed.prior(model, effort), 300)
        val o = Settings.MODEL_OPUS
        assertTrue(ms(o, "low") < ms(o, "medium"))
        assertTrue(ms(o, "medium") < ms(o, "high"))
        assertTrue(ms(o, "high") < ms(o, "xhigh"))
        assertTrue(ms(o, "xhigh") < ms(o, "max"))
        // У Опуса 5.5 пустое усилие — medium, у остальных — high.
        assertEquals(ms(o, "medium"), ms(o, ""))
        assertEquals(PaceSeed.effortFactor(Settings.MODEL_FABLE, "high"), PaceSeed.effortFactor(Settings.MODEL_FABLE, ""), 0.0)
        // Сонет до high не думает — усилие ему время не меняет; xhigh включает мысли.
        assertEquals(1.0, PaceSeed.effortFactor(Settings.MODEL_SONNET, "low"), 0.0)
        assertEquals(1.0, PaceSeed.effortFactor(Settings.MODEL_SONNET, "high"), 0.0)
        assertTrue(PaceSeed.effortFactor(Settings.MODEL_SONNET, "xhigh") > 1.0)
    }
}
