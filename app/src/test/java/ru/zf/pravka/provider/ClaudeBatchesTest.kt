package ru.zf.pravka.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.Settings

// Форма запроса в батче — по RequestPolicy: Fable размышляет всегда и thinking
// ей не передаётся; Сонету на обычном усилии мысли выключены.
class ClaudeBatchesTest {

    @Test
    fun `параметры для Fable — без thinking, с усилием`() {
        val p = ClaudeBatches.params(Settings.MODEL_FABLE, "high", 8000, "система", "вопрос")
        assertEquals(Settings.MODEL_FABLE, p.getString("model"))
        assertFalse(p.has("thinking"))
        assertEquals("high", p.getJSONObject("output_config").getString("effort"))
        assertEquals(8000 + 8000, p.getInt("max_tokens"))
        assertEquals("система", p.getString("system"))
        assertEquals("вопрос", p.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `параметры для Сонета — thinking выключен`() {
        val p = ClaudeBatches.params(Settings.MODEL_SONNET, "", 2000, "", "вопрос")
        assertEquals("disabled", p.getJSONObject("thinking").getString("type"))
        assertFalse(p.has("output_config"))
        assertFalse(p.has("system"))
    }

    @Test
    fun `строка результатов батча и тело одиночного ответа читаются одинаково`() {
        val line = JSONObject(
            """{"custom_id":"dict","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{\"a\":1}"}],
               "stop_reason":"end_turn","usage":{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":5,"cache_creation_input_tokens":0}}}}"""
        )
        val item = ClaudeBatches.parseItem(line)
        assertTrue(item.ok)
        assertEquals("dict", item.customId)
        assertEquals("{\"a\":1}", item.text)
        assertEquals(100, item.inputTokens)
        assertEquals(5, item.cacheRead)

        val refused = ClaudeBatches.parseItem(JSONObject("""{"custom_id":"x","result":{"type":"succeeded","message":{"content":[],"stop_reason":"refusal"}}}"""))
        assertFalse(refused.ok)
        assertEquals("модель отказалась отвечать (refusal)", refused.failure)

        val errored = ClaudeBatches.parseItem(JSONObject("""{"custom_id":"y","result":{"type":"errored","error":{"type":"invalid_request","message":"bad"}}}"""))
        assertFalse(errored.ok)
        assertEquals("bad", errored.failure)

        val single = ClaudeBatches.parseItem(JSONObject("""{"id":"msg_1","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""))
        assertTrue(single.ok)
        assertEquals("ok", single.text)
    }
}
