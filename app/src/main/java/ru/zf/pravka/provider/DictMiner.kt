package ru.zf.pravka.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.DictMode
import ru.zf.pravka.data.Settings

// Mines the proofread history for RECURRING recognition errors worth a
// permanent dictionary entry, so the owner stops curating one word at a time
// via the result bar. Manual action from the Dictionary tab; suggestions are
// shown for review, nothing is added automatically.
class DictMiner(
    private val settings: Settings,
    private val client: OkHttpClient,
) {

    data class Suggestion(
        val mode: DictMode,
        val from: String,   // what recognition produces
        val to: String,     // what it should be ("" for PROTECT)
        val note: String,   // evidence, goes into the entry's note
    )

    suspend fun mine(pairs: List<Pair<String, String>>): Result<List<Suggestion>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = settings.apiKey()
                require(apiKey.isNotBlank()) { "Не задан API-ключ." }
                require(pairs.isNotEmpty()) { "История правок пуста." }

                val samples = JSONArray()
                for ((input, output) in pairs) {
                    samples.put(JSONObject().put("dictated", input).put("fixed", output))
                }
                val prompt = """
Ниже пары из истории диктовок: "dictated" — что выдало распознавание
речи, "fixed" — что в итоге исправила модель. Найди ПОВТОРЯЮЩИЕСЯ
ошибки распознавания, которые стоит закрепить словарной записью:
имена, названия, термины, которые распознавание стабильно пишет
неправильно. Одноразовые ошибки не предлагай.

Ответ — СТРОГО JSON-массив без пояснений и разметки, каждый элемент:
{"mode": "HARD" | "PROTECT", "from": "...", "to": "...", "note": "..."}
HARD: from — неправильная форма, to — правильная (автозамена).
PROTECT: from — правильное редкое слово, to — пустая строка
(защита от "исправления"). note — краткое обоснование по-русски.
Не больше 12 предложений. Если ничего повторяющегося нет — верни [].

$samples
""".trimIndent()

                val body = JSONObject().apply {
                    put("model", Settings.MODEL_SONNET)
                    put("max_tokens", 2048)
                    put("thinking", JSONObject().put("type", "disabled"))
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", prompt)
                        ),
                    )
                }
                val request = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    require(response.isSuccessful) { "API ${response.code}" }
                    val content = JSONObject(text).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") sb.append(block.optString("text"))
                    }
                    parse(sb.toString())
                }
            }
        }

    private fun parse(raw: String): List<Suggestion> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JSONArray(text.substring(start, end + 1))
        val out = mutableListOf<Suggestion>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val mode = runCatching { DictMode.valueOf(o.optString("mode")) }.getOrNull() ?: continue
            if (mode == DictMode.HINT) continue  // miner only proposes HARD/PROTECT
            val from = o.optString("from").trim()
            if (from.isEmpty()) continue
            out.add(
                Suggestion(
                    mode = mode,
                    from = from,
                    to = o.optString("to").trim(),
                    note = o.optString("note").trim(),
                )
            )
        }
        return out
    }
}
