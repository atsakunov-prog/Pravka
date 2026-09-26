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
    fun `Опус дороже на знак, но стартует не медленнее Сонета`() {
        val opus = PaceSeed.guess(Settings.MODEL_OPUS)
        val sonnet = PaceSeed.guess(Settings.MODEL_SONNET)
        // Ради этого дуга и считает по модели: постоянная часть у них общая
        // (сеть, кэш), а генерация у Опуса заметно медленнее.
        assertTrue("на знак Опус дороже", opus.msPerChar > sonnet.msPerChar * 2)
        assertTrue("а стартует не медленнее", opus.baseMs <= sonnet.baseMs)
    }

    @Test
    fun `на короткой фразе модели почти совпадают, на длинной расходятся втрое`() {
        fun ms(model: String, chars: Int) = Pace.estimate(PaceSeed.prior(model), chars)
        val shortOpus = ms(Settings.MODEL_OPUS, 60)
        val shortSonnet = ms(Settings.MODEL_SONNET, 60)
        assertTrue("на короткой разница невелика", Math.abs(shortOpus - shortSonnet) < 1_500)
        val longOpus = ms(Settings.MODEL_OPUS, 900)
        val longSonnet = ms(Settings.MODEL_SONNET, 900)
        assertTrue("на длинной — в разы", longOpus > longSonnet * 2)
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
