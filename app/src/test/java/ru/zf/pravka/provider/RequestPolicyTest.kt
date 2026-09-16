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
            assertEquals(8000, RequestPolicy.thinkingHeadroom(Settings.MODEL_SONNET, e))
        }
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
        // В батче запас под мысли шире; Сонету без мыслей запаса нет и там.
        assertEquals(32_000, RequestPolicy.batchThinkingHeadroom(Settings.MODEL_FABLE, "high"))
        assertEquals(0, RequestPolicy.batchThinkingHeadroom(Settings.MODEL_SONNET, ""))
    }

    @Test
    fun `Опус и Fable никогда не получают disabled`() {
        for (m in listOf(Settings.MODEL_OPUS, Settings.MODEL_FABLE)) {
            for (e in listOf("", "low", "medium", "high", "xhigh", "max")) {
                assertFalse("$m/$e", RequestPolicy.thinkingOff(m, e))
                assertEquals(8000, RequestPolicy.thinkingHeadroom(m, e))
            }
        }
    }
}
