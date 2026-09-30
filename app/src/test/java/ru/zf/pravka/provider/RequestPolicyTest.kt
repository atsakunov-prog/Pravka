package ru.zf.pravka.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.Settings

// Кому выключать размышления и кому оставлять запас под них. Ошибка здесь —
// это 400 на каждой диктовке (Fable с «disabled», Опус с xhigh+disabled), а
// не тихая деградация, поэтому правило закреплено тестом.
class RequestPolicyTest {

    @Test
    fun `Сонет без явного усилия — без размышлений, как было`() {
        assertTrue(RequestPolicy.thinkingOff(Settings.MODEL_SONNET, ""))
        assertEquals(0, RequestPolicy.thinkingHeadroom(Settings.MODEL_SONNET, ""))
    }

    @Test
    fun `Сонет до high включительно — тоже без размышлений`() {
        for (e in listOf("low", "medium", "high")) {
            assertTrue(e, RequestPolicy.thinkingOff(Settings.MODEL_SONNET, e))
        }
    }

    @Test
    fun `xhigh и max включают размышления Сонету`() {
        for (e in listOf("xhigh", "max")) {
            assertFalse(e, RequestPolicy.thinkingOff(Settings.MODEL_SONNET, e))
            assertEquals(16_000, RequestPolicy.thinkingHeadroom(Settings.MODEL_SONNET, e))
        }
    }

    @Test
    fun `чем выключать мысли — between_tools Сонету 5_5, disabled прежнему Сонету 5, остальным ничего`() {
        // Сонет 5.5 на «disabled» отвечает 400 — это была бы каждая чистка.
        for (e in listOf("", "low", "medium", "high", " high ")) {
            assertEquals(e, "between_tools", RequestPolicy.thinkingOffType(Settings.MODEL_SONNET, e))
            assertEquals(e, "disabled", RequestPolicy.thinkingOffType(Settings.MODEL_SONNET_5, e))
        }
        // between_tools на xhigh/max — тоже 400: там мысли включены, поле опускается.
        for (e in listOf("xhigh", "max")) {
            assertEquals(e, null, RequestPolicy.thinkingOffType(Settings.MODEL_SONNET, e))
            assertEquals(e, null, RequestPolicy.thinkingOffType(Settings.MODEL_SONNET_5, e))
        }
        // Никакая другая модель between_tools не принимает, а Опусу 5.5 и Fable 400 и за disabled.
        for (m in listOf(Settings.MODEL_OPUS, Settings.MODEL_OPUS_5, Settings.MODEL_FABLE)) {
            for (e in listOf("", "low", "medium", "high", "xhigh", "max")) {
                assertEquals("$m/$e", null, RequestPolicy.thinkingOffType(m, e))
            }
        }
        // Прежний Сонет без мыслей — и без запаса под них.
        assertEquals(0, RequestPolicy.thinkingHeadroom(Settings.MODEL_SONNET_5, "high"))
    }

    @Test
    fun `бюджет ответа — по длине переменной части, с полом и потолком`() {
        // 1000 знаков → ~500 токенов, +30 % и 300 — ниже пола 1024.
        assertEquals(1024, RequestPolicy.maxTokens(Settings.MODEL_SONNET, "", 1000))
        // 10 000 знаков → 5001 токен → 6501 + 300 = 6801; Опусу плюс 8000 на мысли.
        assertEquals(6801, RequestPolicy.maxTokens(Settings.MODEL_SONNET, "", 10_000))
        assertEquals(14_801, RequestPolicy.maxTokens(Settings.MODEL_OPUS, "", 10_000))
        // Картинка — ещё 1600 токенов на оценку входа.
        assertEquals(6801 + 1600 * 13 / 10, RequestPolicy.maxTokens(Settings.MODEL_SONNET, "", 10_000, images = 1))
        assertEquals(16_384, RequestPolicy.maxTokens(Settings.MODEL_OPUS, "", 100_000))
        // xhigh (засечка, еда): вдвое больше места под мысли, потолок растёт с ним.
        assertEquals(22_801, RequestPolicy.maxTokens(Settings.MODEL_OPUS, "xhigh", 10_000))
        assertEquals(24_384, RequestPolicy.maxTokens(Settings.MODEL_OPUS, "xhigh", 100_000))
        // В батче запас под мысли шире; Сонету без мыслей запаса нет и там.
        assertEquals(32_000, RequestPolicy.batchThinkingHeadroom(Settings.MODEL_FABLE, "high"))
        // Разборы на max — 64 тысячи.
        assertEquals(64_000, RequestPolicy.batchThinkingHeadroom(Settings.MODEL_OPUS, "max"))
        assertEquals(0, RequestPolicy.batchThinkingHeadroom(Settings.MODEL_SONNET, ""))
    }

    @Test
    fun `Опус и Fable никогда не получают disabled`() {
        for (m in listOf(Settings.MODEL_OPUS, Settings.MODEL_FABLE)) {
            for (e in listOf("", "low", "medium", "high", "xhigh", "max")) {
                assertFalse("$m/$e", RequestPolicy.thinkingOff(m, e))
                assertEquals(if (e == "xhigh" || e == "max") 16_000 else 8000, RequestPolicy.thinkingHeadroom(m, e))
            }
        }
    }
}
