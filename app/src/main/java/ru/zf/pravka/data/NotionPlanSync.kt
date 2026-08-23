package ru.zf.pravka.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// Правила блока из Notion.
//
// Разделение источников такое, как его описал владелец сам: «Notion —
// оперативка и знания, intervals.icu — календарь и факт». Скелет дня приезжает
// из календаря (структура), а ПРАВИЛА живут прозой на странице блока, и правит
// их он руками: потолок пульса 150, каденс 168+, серая зона 160–165, три
// пробежки в неделю максимум, 48 часов между ними, рамп-тест только на плюсовом
// TSB, светофор колена, правило отмены («первым выпадает бег, силовые не
// двигаются никогда»).
//
// Читаем ТОЛЬКО. Ничего в Notion не пишем: там его знания, и хозяин им он.
//
// Страницу блока не спрашиваем в настройках — ищем сами: под страницей-хабом
// «Тело» берём самую свежую дочернюю, чья заголовок начинается на «Блок». Так
// появление «Блока 4» подхватывается без правки настроек, а «Блок 3 v2» сам
// вытесняет «Блок 3».
//
// Раз в сутки — этого достаточно: правила блока меняются раз в месяц, а трафик
// на даче дорог. Текст страницы кладётся в кэш целиком (он же уезжает в промпт
// разбора тренировок), а числа из него вынимает Сонет отдельным вызовом.
class NotionPlanSync(
    private val settings: Settings,
    private val store: PlanStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private const val PERIOD_MS = 24 * 3_600_000L
        // Сколько запросов за один проход не жалко: страница блока плюс её
        // таблицы. Дальше обрываем — лучше неполные правила, чем висящая
        // выгрузка на плохой сети.
        private const val MAX_REQUESTS = 8
    }

    @Volatile private var lastRun = 0L
    @Volatile private var lastError = ""
    private var requests = 0

    fun lastError(): String = lastError

    suspend fun configured(): Boolean = settings.notionToken().isNotBlank()

    /** Страница блока и её текст — то, что дальше разбирает Сонет. */
    data class BlockPage(val pageId: String, val title: String, val text: String)

    /**
     * Прочитать свежую страницу блока. Возвращает null, если токена нет, сеть
     * молчит или страниц «Блок …» под хабом не нашлось — во всех этих случаях
     * кэш остаётся как был.
     */
    suspend fun fetchBlockPage(force: Boolean = false): BlockPage? {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < PERIOD_MS) return null
        val token = settings.notionToken().trim()
        if (token.isBlank()) {
            lastError = "Токен Notion не задан — правила блока читать нечем"
            return null
        }
        val hub = settings.notionHub().trim().replace("-", "")
        return withContext(Dispatchers.IO) {
            requests = 0
            runCatching {
                val page = newestBlockChild(hub, token)
                if (page == null) {
                    lastError = "Под страницей-хабом не нашлось страниц «Блок …»"
                    return@runCatching null
                }
                val text = pageText(page.first, token)
                if (text.isBlank()) {
                    lastError = "Страница блока прочиталась пустой"
                    return@runCatching null
                }
                lastRun = now
                lastError = ""
                eventLog.add("план: правила из Notion — «${page.second}», ${text.length} зн.")
                BlockPage(page.first, page.second, text)
            }.getOrElse { e ->
                lastError = e.message ?: e.javaClass.simpleName
                eventLog.add("план: Notion не ответил — ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * Сохранить разобранные правила в кэш. Разбор делает вызывающий (ему
     * доступна модель), а этот класс отвечает только за дорогу к Notion.
     */
    suspend fun store(rules: PlanStore.Rules) = store.setRules(rules)

    // ---- Notion REST ----

    /** Самая свежая дочерняя страница хаба, чей заголовок начинается на «Блок». */
    private fun newestBlockChild(hubId: String, token: String): Pair<String, String>? {
        val body = get("$API/blocks/$hubId/children?page_size=100", token) ?: return null
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return null
        // Notion отдаёт детей в порядке страницы, а не по свежести. Владелец
        // дописывает новый блок в конец — берём ПОСЛЕДНИЙ подходящий.
        var found: Pair<String, String>? = null
        for (i in 0 until results.length()) {
            val b = results.optJSONObject(i) ?: continue
            if (b.optString("type") != "child_page") continue
            val title = b.optJSONObject("child_page")?.optString("title").orEmpty()
            if (!title.trimStart().startsWith("Блок", ignoreCase = true)) continue
            found = b.optString("id").replace("-", "") to title
        }
        return found
    }

    /** Блоки страницы в плоский текст: заголовки, абзацы, списки и таблицы. */
    private fun pageText(pageId: String, token: String): String {
        val out = StringBuilder()
        appendBlocks(pageId, token, out, depth = 0)
        return out.toString().trim()
    }

    private fun appendBlocks(blockId: String, token: String, out: StringBuilder, depth: Int) {
        if (depth > 2 || requests >= MAX_REQUESTS) return
        val body = get("$API/blocks/$blockId/children?page_size=100", token) ?: return
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return
        for (i in 0 until results.length()) {
            val b = results.optJSONObject(i) ?: continue
            val type = b.optString("type")
            val text = richText(b.optJSONObject(type)?.optJSONArray("rich_text"))
            when (type) {
                "heading_1", "heading_2", "heading_3" ->
                    if (text.isNotBlank()) out.append("\n## ").append(text).append('\n')
                "paragraph" -> if (text.isNotBlank()) out.append(text).append('\n')
                "bulleted_list_item", "numbered_list_item" ->
                    if (text.isNotBlank()) out.append("- ").append(text).append('\n')
                "to_do" -> if (text.isNotBlank()) out.append("- [ ] ").append(text).append('\n')
                "quote", "callout" -> if (text.isNotBlank()) out.append("> ").append(text).append('\n')
                "table" -> {
                    // Штатная неделя и светофор колена живут таблицами — без
                    // них правил считай что нет, поэтому за ними идём внутрь.
                    appendBlocks(b.optString("id"), token, out, depth + 1)
                    out.append('\n')
                }
                "table_row" -> {
                    val cells = b.optJSONObject("table_row")?.optJSONArray("cells")
                    if (cells != null) {
                        val row = (0 until cells.length()).map { j ->
                            richText(cells.optJSONArray(j))
                        }.filter { it.isNotBlank() }
                        if (row.isNotEmpty()) out.append(row.joinToString(" | ")).append('\n')
                    }
                }
                // Дочерние страницы и базы не разворачиваем: справочник у нас
                // свой, статический, а лезть в него по сети незачем.
                "child_page", "child_database", "link_to_page" -> Unit
                else -> if (text.isNotBlank()) out.append(text).append('\n')
            }
        }
    }

    private fun richText(array: JSONArray?): String {
        if (array == null) return ""
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            sb.append(t.optString("plain_text"))
        }
        return sb.toString().trim()
    }

    private fun get(url: String, token: String): String? {
        if (requests >= MAX_REQUESTS) return null
        requests++
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", VERSION)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                lastError = when (response.code) {
                    401 -> "Notion не принял токен"
                    404 -> "Notion: страница не найдена или интеграции не дали к ней доступ"
                    429 -> "Notion просит подождать (429)"
                    else -> "Notion: HTTP ${response.code}"
                }
                return null
            }
            return response.body?.string()
        }
    }
}
