package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Каталог дорог к моделям и разбор сохранённого выбора. Заводские значения
// здесь — это то, что раньше было зашито в вызовы: тест держит их на месте,
// чтобы правка каталога не переключила молча Засечку на Сонет.
class ModelRoutesTest {

    @Test
    fun `заводские модели каждой дороги есть в каталоге`() {
        for (route in ModelRoute.entries) {
            assertTrue(route.name, route.defaultModel in Models.ALL)
            assertTrue(route.name, route.defaultEffort in Models.EFFORTS)
        }
    }

    @Test
    fun `ключи дорог уникальны — иначе две дороги делили бы одну настройку`() {
        val keys = ModelRoute.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `заводские — Опус 5_5 с усилиями владельца от 22_09`() {
        assertEquals("claude-opus-5-5", Settings.MODEL_OPUS)
        // Правка и спорт — medium, засечка и еда — xhigh, Дела держат high.
        val expected = mapOf(
            ModelRoute.PRAVKA to "medium",
            ModelRoute.PRAVKA_STRONG to "medium",
            ModelRoute.PRAVKA_LEARN to "medium",
            ModelRoute.ZASECHKA to "xhigh",
            ModelRoute.FOOD to "xhigh",
            ModelRoute.BODY to "medium",
            ModelRoute.RAZNOSKA to "high",
            // Разборы — Опус 5.5 на max вместо Fable.
            ModelRoute.NIGHT_REVIEW to "max",
            ModelRoute.NIGHT_CHECK to "max",
            ModelRoute.SHADOW_JUDGE to "max",
            ModelRoute.PROMPT_TUNE to "max",
            ModelRoute.PATTERNS_DUPES to "max",
        )
        for ((route, effort) in expected) {
            assertEquals(route.name, Settings.MODEL_OPUS, route.defaultModel)
            assertEquals(route.name, effort, route.defaultEffort)
        }
        assertEquals(Settings.MODEL_SONNET, ModelRoute.BODY_LIGHT.defaultModel)
        // Развилка Засечки (26.09.2026): одно слово из четырёх — Сонет на low,
        // секунда до разбора Опусом.
        assertEquals(Settings.MODEL_SONNET, ModelRoute.ZASECHKA_FORK.defaultModel)
        assertEquals("low", ModelRoute.ZASECHKA_FORK.defaultEffort)
        // Fable нигде не заводской, но остаётся в каталоге выбора.
        assertTrue(ModelRoute.entries.none { it.defaultModel == Settings.MODEL_FABLE })
        assertTrue(Settings.MODEL_FABLE in Models.ALL)
    }

    @Test
    fun `явный Опус 5 в хранилище читается как Опус 5_5, не как заводская`() {
        val c = ModelChoice.of(ModelRoute.RAZNOSKA, Settings.MODEL_OPUS_5, "low")
        assertEquals(Settings.MODEL_OPUS, c.model)
        assertEquals("low", c.effort)
        assertFalse(Settings.MODEL_OPUS_5 in Models.ALL)
    }

    @Test
    fun `ряд усилия в меню «П» — три допустимых, заводское чистки среди них`() {
        assertEquals(listOf("medium", "high", "xhigh"), Models.PRAVKA_QUICK_EFFORTS)
        assertTrue(Models.PRAVKA_QUICK_EFFORTS.all { it in Models.EFFORTS })
        assertTrue(ModelRoute.PRAVKA.defaultEffort in Models.PRAVKA_QUICK_EFFORTS)
    }

    @Test
    fun `пустое хранилище даёт заводское`() {
        val c = ModelChoice.of(ModelRoute.ZASECHKA, null, null)
        assertEquals(ModelChoice.defaultOf(ModelRoute.ZASECHKA), c)
        assertTrue(c.isDefaultFor(ModelRoute.ZASECHKA))
    }

    @Test
    fun `сохранённый выбор читается`() {
        val c = ModelChoice.of(ModelRoute.ZASECHKA, Settings.MODEL_FABLE, "xhigh")
        assertEquals(Settings.MODEL_FABLE, c.model)
        assertEquals("xhigh", c.effort)
        assertFalse(c.isDefaultFor(ModelRoute.ZASECHKA))
    }

    @Test
    fun `модель не из каталога откатывается к заводской, а не уезжает в запрос`() {
        val c = ModelChoice.of(ModelRoute.PRAVKA, "claude-3-opus-20240229", "turbo")
        assertEquals(Settings.MODEL_OPUS, c.model)
        assertEquals("medium", c.effort)
    }

    @Test
    fun `пустое усилие значит «не передавать» и допустимо`() {
        assertEquals("", ModelChoice.of(ModelRoute.PATTERNS_DUPES, null, "").effort)
        assertEquals("по умолчанию", Models.effortLabel(""))
    }
}
