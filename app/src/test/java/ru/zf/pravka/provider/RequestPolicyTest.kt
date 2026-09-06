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
    fun `Опус и Fable никогда не получают disabled`() {
        for (m in listOf(Settings.MODEL_OPUS, Settings.MODEL_FABLE)) {
            for (e in listOf("", "low", "medium", "high", "xhigh", "max")) {
                assertFalse("$m/$e", RequestPolicy.thinkingOff(m, e))
                assertEquals(8000, RequestPolicy.thinkingHeadroom(m, e))
            }
        }
    }
}
