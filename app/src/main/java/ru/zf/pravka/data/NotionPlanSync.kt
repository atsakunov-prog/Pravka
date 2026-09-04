package ru.zf.pravka.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
// Читаем ДВЕ страницы, и это не жадность:
//
//   ХАБ «Тело: велоформа и сила» — постоянное знание. Светофор колена, правило
//   отмены, пять принципов, три цели, потолок бега 150. Оно живёт там месяцами
//   и меняется редко.
//   СТРАНИЦА БЛОКА под хабом — специфика текущих недель: штатная неделя,
//   лимиты этого блока. Она сменяется каждый месяц-полтора.
//
// Раньше читалась только вторая, и если её не находили — правил не было вообще,
// хотя половина их лежала на хабе. Теперь хаб — не «путь к блоку», а источник
// сам по себе: даже без страницы блока правила есть.
//
// Страницу блока не спрашиваем в настройках — ищем сами: сперва среди дочерних
// хаба, потом через поиск Notion (он видит всё, к чему интеграцию пустили, даже
// если к хабу — нет). Так появление «Блока 4» подхватывается без правки
// настроек, а «Блок 3 v2» сам вытесняет «Блок 3».
//
// С сентября 2026 страница зовётся «План v3 — с 7 сентября: …», а старые блоки
// уехали в дочернюю страницу «Архив». Поэтому подходит и «План…», а прямой
// ребёнок хаба всегда сильнее найденного поиском: поиск по слову «Блок» иначе
// приносил «Блок 3 v2» из архива — и вкладка неделю жила по старым правилам.
//
// Раз в сутки — этого достаточно: правила блока меняются раз в месяц, а трафик
// на даче дорог. Текст страниц кладётся в кэш целиком (проза уезжает в контекст
// вопроса тренеру), а числа из него вынимает Сонет отдельным вызовом.
class NotionPlanSync(
    private val settings: Settings,
    private val store: PlanStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PERIOD_MS = 24 * 3_600_000L
        // Сколько запросов за один проход не жалко. Раз в сутки, ответы мелкие,
        // а таблиц на двух страницах штук восемь — каждая это отдельный заход
        // за строками. Дальше обрываем: лучше неполные правила, чем висящая
        // выгрузка на плохой сети.
        private const val MAX_REQUESTS = 40

        private val DASHED = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private val PLAIN = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")

        /**
         * Id страницы из чего угодно: голого id, дефисного UUID, ссылки со
         * слагом заголовка. Владелец жмёт в Notion «Copy link» — и получает
         * `…/Блок-3-v2-3c2c4ffca2d581aba7e6c3726fdf5762?pvs=4`, где хвост
         * `?pvs=4` и цифры из заголовка тоже шестнадцатеричные. Поэтому не
         * «отфильтровать все hex-символы», а найти цельный id: сперва дефисный
         * UUID, потом ровно 32 символа между не-hex.
         */
        fun pageId(raw: String): String {
            val body = raw.trim().substringBefore('?').substringBefore('#')
            DASHED.findAll(body).lastOrNull()?.let {
                return it.value.replace("-", "").lowercase()
            }
            PLAIN.findAll(body).lastOrNull()?.let { return it.value.lowercase() }
            return body.replace("-", "").lowercase()
        }
    }

    @Volatile private var lastRun = 0L
    @Volatile private var lastError = ""
    @Volatile private var httpError = ""
    private var requests = 0

    fun lastError(): String = lastError

    suspend fun configured(): Boolean = settings.notionToken().isNotBlank()

    /** Страница блока и её текст — то, что дальше разбирает Сонет. */
    data class BlockPage(val pageId: String, val title: String, val text: String)

    /**
     * Прочитать правила. Возвращает null, если токена нет, сеть молчит или ни
     * хаб, ни страница блока не прочитались — во всех этих случаях кэш
     * остаётся как был.
     */
    suspend fun fetchBlockPage(force: Boolean = false): BlockPage? {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < PERIOD_MS) return null
        val token = settings.notionToken().trim()
        if (token.isBlank()) {
            lastError = "Токен Notion не задан — правила блока читать нечем"
            return null
        }
        val hub = pageId(settings.notionHub())
        return withContext(Dispatchers.IO) {
            requests = 0
            httpError = ""
            runCatching {
                // Хаб — постоянное знание. Если он открыт интеграции, правила
                // есть уже здесь, независимо от страницы блока.
                val hubText = pageText(hub, token)
                val block = findBlockPage(hub, token)
                val blockText = block?.let { pageText(it.first, token) }.orEmpty()
                // Страница недели — самая оперативная: «Неделя 24–30.08 —
                // спина-протокол» с запретами, заменами и условиями тестов.
                // Владелец пишет новую каждую неделю — берём последнюю.
                val week = newestChild(hub, token) { isWeekTitle(it) }
                val weekText = week?.let { pageText(it.first, token) }.orEmpty()

                if (hubText.isBlank() && blockText.isBlank() && weekText.isBlank()) {
                    lastError = httpError.ifBlank {
                        "Notion ответил, но обе страницы прочитались пустыми. " +
                            "Проверь, что интеграции открыт доступ: страница → " +
                            "«…» → Connections → добавь интеграцию."
                    }
                    return@runCatching null
                }

                val text = buildString {
                    if (hubText.isNotBlank()) {
                        append("# Постоянные правила (хаб «Тело»)\n")
                        append(hubText).append("\n\n")
                    }
                    if (blockText.isNotBlank()) {
                        append("# Текущий блок: ").append(block!!.second).append('\n')
                        append(blockText).append("\n\n")
                    }
                    if (weekText.isNotBlank()) {
                        // Неделя — ПОСЛЕДНЕЙ: она самая свежая, и при споре с
                        // блоком побеждать должна она.
                        append("# Текущая неделя: ").append(week!!.second).append('\n')
                        append(weekText)
                    }
                }.trim()

                lastRun = now
                // Блока нет, но хаб прочитался — это рабочее состояние, а не
                // ошибка. Говорим об этом отдельной строкой, не красной.
                lastError = if (block == null && httpError.isNotBlank()) httpError else ""
                val title = week?.second ?: block?.second ?: "Тело: велоформа и сила"
                eventLog.add(
                    "план: правила из Notion — «$title», ${text.length} зн." +
                        if (block == null) " (страницы блока не нашлось)" else ""
                )
                BlockPage(block?.first ?: hub, title, text)
            }.getOrElse { e ->
                lastError = e.message ?: e.javaClass.simpleName
                eventLog.add("план: Notion не ответил — ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * Что Notion отвечает на самом деле — построчно. Владельцу нужен не «не
     * нашлось», а «токен принят, хаб отдал 404»: первое непонятно что делать,
     * второе указывает пальцем в Connections.
     */
    suspend fun diagnose(): String {
        val token = settings.notionToken().trim()
        if (token.isBlank()) {
            return "Токена нет. Notion → My integrations → New integration " +
                "(тип Internal, права только чтение) → скопируй Internal " +
                "Integration Secret (ntn_…) сюда."
        }
        val hubRaw = settings.notionHub()
        val hub = pageId(hubRaw)
        return withContext(Dispatchers.IO) {
            requests = 0
            httpError = ""
            val out = StringBuilder()
            // 1. Токен.
            val me = get("$API/users/me", token)
            if (me == null) {
                out.append("Токен: ").append(httpError.ifBlank { "не ответил" }).append('\n')
                out.append("Дальше проверять нечем — сперва токен.")
                return@withContext out.toString()
            }
            val botName = runCatching { JSONObject(me).optString("name") }.getOrNull().orEmpty()
            out.append("Токен: принят").append(if (botName.isNotBlank()) " ($botName)" else "")
                .append('\n')

            // 2. Хаб.
            httpError = ""
            val hubBody = get("$API/blocks/$hub/children?page_size=100", token)
            if (hubBody == null) {
                out.append("Хаб $hub: ").append(httpError.ifBlank { "не ответил" }).append('\n')
                out.append("Так бывает, когда интеграцию не пустили на страницу. ")
                out.append("Открой в Notion «Тело: велоформа и сила» → «…» в правом ")
                out.append("верхнем углу → Connections → выбери свою интеграцию. ")
                out.append("Доступ наследуется вниз, дочерние страницы открывать не надо.\n")
            } else {
                val results = runCatching { JSONObject(hubBody).optJSONArray("results") }
                    .getOrNull()
                out.append("Хаб: прочитан, блоков ").append(results?.length() ?: 0).append('\n')
                val pages = mutableListOf<String>()
                for (i in 0 until (results?.length() ?: 0)) {
                    val b = results!!.optJSONObject(i) ?: continue
                    if (b.optString("type") != "child_page") continue
                    pages.add(b.optJSONObject("child_page")?.optString("title").orEmpty())
                }
                out.append("Дочерние страницы: ")
                    .append(if (pages.isEmpty()) "нет" else pages.joinToString(" · "))
                    .append('\n')
            }

            // 3. Страница плана: сперва прямой ребёнок хаба, потом поиск — он
            // видит только то, к чему интеграцию пустили.
            httpError = ""
            val found = findBlockPage(hub, token)
            out.append("Страница плана: ")
                .append(found?.second ?: httpError.ifBlank { "ничего не нашлось" })
                .append('\n')
            val week = newestChild(hub, token) { isWeekTitle(it) }
            out.append("Страница недели: ")
                .append(week?.second ?: "не нашлось")
                .append('\n')

            // 4. Что в итоге прочиталось.
            httpError = ""
            val hubText = pageText(hub, token)
            val blockText = found?.let { pageText(it.first, token) }.orEmpty()
            out.append("Прочитано знаков: хаб ").append(hubText.length)
                .append(", блок ").append(blockText.length)
            if (hubText.isBlank() && blockText.isBlank()) {
                out.append("\nПравила брать негде. Дай интеграции доступ к хабу — ")
                out.append("на нём уже есть светофор колена, правило отмены и потолок бега.")
            }
            out.toString()
        }
    }

    /**
     * Сохранить разобранные правила в кэш. Разбор делает вызывающий (ему
     * доступна модель), а этот класс отвечает только за дорогу к Notion.
     */
    suspend fun store(rules: PlanStore.Rules) = store.setRules(rules)

    // ---- Notion REST ----

    /**
     * Страница блока: сперва среди дочерних хаба, потом поиском. Поиск —
     * не роскошь: он находит страницу, даже если к хабу интеграцию не пустили,
     * а к блоку — пустили (или наоборот, если блок вынесли из-под хаба).
     */
    private fun findBlockPage(hubId: String, token: String): Pair<String, String>? =
        newestBlockChild(hubId, token) ?: searchBlockPage(token, hubId)

    /** Самая свежая дочерняя страница хаба, чей заголовок начинается на «Блок». */
    private fun newestBlockChild(hubId: String, token: String): Pair<String, String>? =
        newestChild(hubId, token) { isBlockTitle(it) }

    /**
     * Последняя дочерняя страница хаба с подходящим заголовком. Notion отдаёт
     * детей в порядке страницы, а не по свежести; владелец дописывает новое в
     * конец — берём ПОСЛЕДНЕЕ подходящее.
     */
    private fun newestChild(
        hubId: String,
        token: String,
        matches: (String) -> Boolean,
    ): Pair<String, String>? {
        var found: Pair<String, String>? = null
        var cursor: String? = null
        do {
            val url = buildString {
                append(API).append("/blocks/").append(hubId).append("/children?page_size=100")
                if (cursor != null) append("&start_cursor=").append(cursor)
            }
            val body = get(url, token) ?: return found
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return found
            val results = json.optJSONArray("results") ?: return found
            for (i in 0 until results.length()) {
                val b = results.optJSONObject(i) ?: continue
                if (b.optString("type") != "child_page") continue
                val title = b.optJSONObject("child_page")?.optString("title").orEmpty()
                if (!matches(title)) continue
                found = pageId(b.optString("id")) to title
            }
            cursor = json.optString("next_cursor").takeIf {
                json.optBoolean("has_more") && it.isNotBlank() && it != "null"
            }
        } while (cursor != null)
        return found
    }

    private class Hit(val id: String, val title: String, val parent: String, val edited: String)

    /**
     * Поиск страницы «План …» или «Блок …» по всему, к чему пустили
     * интеграцию. Прямой ребёнок хаба сильнее любой свежести: страница из
     * «Архива» под хабом тоже находится поиском, но текущей быть не может.
     * Среди прочих — самая свежая по времени правки.
     */
    private fun searchBlockPage(token: String, hubId: String = ""): Pair<String, String>? {
        val hits = mutableListOf<Hit>()
        for (query in listOf("План", "Блок")) {
            val payload = JSONObject().apply {
                put("query", query)
                put("filter", JSONObject().apply {
                    put("value", "page")
                    put("property", "object")
                })
                put("sort", JSONObject().apply {
                    put("direction", "descending")
                    put("timestamp", "last_edited_time")
                })
                put("page_size", 20)
            }
            val body = post("$API/search", token, payload.toString()) ?: continue
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
                ?: continue
            for (i in 0 until results.length()) {
                val p = results.optJSONObject(i) ?: continue
                if (p.optString("object") != "page") continue
                if (p.optBoolean("archived") || p.optBoolean("in_trash")) continue
                val title = pageTitleOf(p)
                if (!isBlockTitle(title)) continue
                val parent = p.optJSONObject("parent")?.optString("page_id").orEmpty()
                    .replace("-", "").lowercase()
                hits.add(Hit(pageId(p.optString("id")), title, parent, p.optString("last_edited_time")))
            }
        }
        if (hits.isEmpty()) return null
        val best = hits.filter { hubId.isNotBlank() && it.parent == hubId }.maxByOrNull { it.edited }
            ?: hits.maxByOrNull { it.edited }!!
        return best.id to best.title
    }

    /**
     * Заголовок страницы из объекта поиска. У страницы вне базы свойство
     * называется «title», у страницы в базе — как угодно, поэтому ищем по типу.
     */
    private fun pageTitleOf(page: JSONObject): String {
        val props = page.optJSONObject("properties") ?: return ""
        for (key in props.keys()) {
            val prop = props.optJSONObject(key) ?: continue
            if (prop.optString("type") != "title") continue
            return richText(prop.optJSONArray("title"))
        }
        return ""
    }

    /**
     * «Это страница плана?» — «Блок 3 v2», «План v3 — с 7 сентября». Заголовок
     * бывает с эмодзи, неразрывным пробелом или «№» впереди — отрезаем всё,
     * что не буква, и только потом сравниваем. Слово должно быть целым:
     * «Планка» и «Блокнот» — не план. Страницы со словом «архив» не берём.
     */
    private fun isBlockTitle(title: String): Boolean {
        val cleaned = title.dropWhile { !it.isLetter() }
        if (cleaned.contains("архив", ignoreCase = true)) return false
        return PLAN_TITLE.containsMatchIn(cleaned)
    }

    private val PLAN_TITLE = Regex("^(?:Блок|Block|План|Plan)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /** «Неделя 24–30.08 — спина-протокол» и любые будущие недельные страницы. */
    private fun isWeekTitle(title: String): Boolean {
        val cleaned = title.dropWhile { !it.isLetter() }
        return cleaned.startsWith("Недел", ignoreCase = true) ||
            cleaned.startsWith("Week", ignoreCase = true)
    }

    /** Блоки страницы в плоский текст: заголовки, абзацы, списки и таблицы. */
    private fun pageText(pageId: String, token: String): String {
        val out = StringBuilder()
        appendBlocks(pageId, token, out, depth = 0)
        return out.toString().trim()
    }

    private fun appendBlocks(blockId: String, token: String, out: StringBuilder, depth: Int) {
        // Три уровня: страница → тоггл → список → подпункты. Глубже правил не
        // прячут, а от циклов synced-блоков бережёт лимит запросов.
        if (depth > 3 || requests >= MAX_REQUESTS) return
        var cursor: String? = null
        do {
            val url = buildString {
                append(API).append("/blocks/").append(blockId).append("/children?page_size=100")
                if (cursor != null) append("&start_cursor=").append(cursor)
            }
            val body = get(url, token) ?: return
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return
            val results = json.optJSONArray("results") ?: return
            for (i in 0 until results.length()) {
                val b = results.optJSONObject(i) ?: continue
                val type = b.optString("type")
                val text = richText(b.optJSONObject(type)?.optJSONArray("rich_text"))
                when (type) {
                    "heading_1", "heading_2", "heading_3" ->
                        if (text.isNotBlank()) out.append("\n## ").append(text).append('\n')
                    "paragraph" -> if (text.isNotBlank()) out.append(text).append('\n')
                    "bulleted_list_item", "numbered_list_item" -> {
                        if (text.isNotBlank()) out.append("- ").append(text).append('\n')
                        // Вложенные пункты: «потолок 150» бывает подпунктом.
                        if (b.optBoolean("has_children")) {
                            appendBlocks(b.optString("id"), token, out, depth + 1)
                        }
                    }
                    "to_do" -> if (text.isNotBlank()) out.append("- [ ] ").append(text).append('\n')
                    "quote", "callout" -> {
                        if (text.isNotBlank()) out.append("> ").append(text).append('\n')
                        if (b.optBoolean("has_children")) {
                            appendBlocks(b.optString("id"), token, out, depth + 1)
                        }
                    }
                    "table" -> {
                        // Штатная неделя и светофор колена живут таблицами — без
                        // них правил считай что нет, поэтому за ними идём внутрь.
                        appendBlocks(b.optString("id"), token, out, depth + 1)
                        out.append('\n')
                    }
                    // Тогглы и колонки — контейнеры: их текст это заголовок, а
                    // содержимое живёт в детях. Правила, спрятанные в тоггл,
                    // раньше терялись молча.
                    "toggle" -> {
                        if (text.isNotBlank()) out.append("\n## ").append(text).append('\n')
                        appendBlocks(b.optString("id"), token, out, depth + 1)
                    }
                    "column_list", "column", "synced_block" ->
                        appendBlocks(b.optString("id"), token, out, depth + 1)
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
            cursor = json.optString("next_cursor").takeIf {
                json.optBoolean("has_more") && it.isNotBlank() && it != "null"
            }
        } while (cursor != null && requests < MAX_REQUESTS)
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

    private fun get(url: String, token: String): String? =
        call(Request.Builder().url(url).get(), token)

    private fun post(url: String, token: String, body: String): String? =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)), token)

    /**
     * Один запрос. Ошибку кладём в [httpError], а не в [lastError]: вызывающий
     * решает, ошибка это или рабочее «страницы блока нет». Раньше здесь было
     * наоборот, и настоящий 404 подменялся фразой «не нашлось страниц» —
     * владельцу оставалось гадать, что чинить.
     */
    private fun call(builder: Request.Builder, token: String): String? {
        if (requests >= MAX_REQUESTS) {
            httpError = "Notion: слишком много запросов за проход"
            return null
        }
        requests++
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", VERSION)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val hint = runCatching {
                    JSONObject(response.body?.string().orEmpty()).optString("message")
                }.getOrNull().orEmpty()
                httpError = when (response.code) {
                    401 -> "Notion не принял токен (401). Нужен Internal Integration " +
                        "Secret, он начинается на ntn_"
                    403 -> "Notion: 403 — у интеграции нет прав на чтение"
                    404 -> "Notion: 404 — страница не найдена или интеграцию к ней не " +
                        "пустили. Страница → «…» → Connections → добавь интеграцию"
                    429 -> "Notion просит подождать (429)"
                    else -> "Notion: HTTP ${response.code}" +
                        if (hint.isNotBlank()) " — ${hint.take(120)}" else ""
                }
                return null
            }
            return response.body?.string()
        }
    }
}
