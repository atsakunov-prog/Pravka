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
    fun `заводские — как было зашито до настроек`() {
        assertEquals(Settings.MODEL_SONNET, ModelRoute.PRAVKA.defaultModel)
        assertEquals(Settings.MODEL_OPUS, ModelRoute.PRAVKA_STRONG.defaultModel)
        assertEquals(Settings.MODEL_OPUS, ModelRoute.ZASECHKA.defaultModel)
        assertEquals(Settings.MODEL_OPUS, ModelRoute.BODY.defaultModel)
        assertEquals(Settings.MODEL_SONNET, ModelRoute.BODY_LIGHT.defaultModel)
        assertEquals(Settings.MODEL_OPUS, ModelRoute.PATTERNS.defaultModel)
        assertEquals("high", ModelRoute.PATTERNS.defaultEffort)
        assertEquals(Settings.MODEL_FABLE, ModelRoute.PATTERNS_DUPES.defaultModel)
        assertEquals("medium", ModelRoute.PATTERNS_DUPES.defaultEffort)
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
        assertEquals(Settings.MODEL_SONNET, c.model)
        assertEquals("", c.effort)
    }

    @Test
    fun `пустое усилие значит «не передавать» и допустимо`() {
        assertEquals("", ModelChoice.of(ModelRoute.PATTERNS, null, "").effort)
        assertEquals("по умолчанию", Models.effortLabel(""))
    }
}
