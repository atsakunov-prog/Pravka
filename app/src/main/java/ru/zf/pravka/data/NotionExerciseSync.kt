package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Справочник упражнений — живой, из базы Notion «Упражнения» под хабом «Тело».
//
// Владелец правит зарядку в Notion: 3 сентября появились «Суставы сверху
// вниз», «Осанка: подбородок назад · скольжения по стене · грудь в проёме» и
// «Подъёмы коленей на турнике», у половины движений сменились схемы и блоки.
// Файл сборки этого не знал — строки плана не находили технику, а запасной
// список зарядки показывал пятнадцать позиций августа. Отсюда его слова:
// «вытянулось половину». Справочник должен читаться оттуда, где он живёт.
//
// Читаем ТОЛЬКО. Раз в сутки сам (вместе с планом), по кнопке — сразу.
// Прочитанное кладётся в `ExerciseBook` и на диск: на даче без сети вкладка
// открывается из кэша. Пустой ответ не принимается — это «сеть или доступ»,
// а не «упражнений больше нет».
//
// Голосовые имена и единицы подхода в базе Notion не живут: они берутся из
// файла сборки по совпадению id (id — тот же slug, что у `gen_reference.py`),
// у новых движений выводятся из названия до следующей пересборки снимка.
class NotionExerciseSync(
    private val settings: Settings,
    private val book: ExerciseBook,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PERIOD_MS = 24 * 3_600_000L
        /** Неудачную попытку не повторяем на каждом тике: раз в час хватит. */
        private const val RETRY_MS = 3_600_000L
        private const val MAX_PAGES = 5

        /** База «Упражнения». Ищется среди детей хаба; это — запас, если хаб не отдался. */
        const val DB_DEFAULT = "3b9a946becf3451485e735af2cffca10"
        const val DB_TITLE = "Упражнения"

        private val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    @Volatile private var lastError = ""
    @Volatile private var httpError = ""
    @Volatile private var lastTried = 0L

    fun lastError(): String = lastError

    /**
     * Прочитать базу и заменить справочник. Возвращает true, если приехало и
     * легло. [force] — владелец нажал кнопку: без оглядки на возраст кэша.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        book.load()
        val now = System.currentTimeMillis()
        // Возраст — по кэшу на диске, а не по таймеру в памяти: перезапуск
        // приложения не повод идти в Notion лишний раз.
        if (!force && now - book.fetchedAt < PERIOD_MS) return false
        if (!force && now - lastTried < RETRY_MS) return false
        lastTried = now
        val token = settings.notionToken().trim()
        if (token.isBlank()) {
            lastError = "Токен Notion не задан — справочник из файла сборки"
            return false
        }
        val hub = NotionPlanSync.pageId(settings.notionHub())
        return withContext(Dispatchers.IO) {
            httpError = ""
            runCatching {
                val dbId = findDatabase(hub, token) ?: DB_DEFAULT
                val rows = queryAll(dbId, token)
                val list = rows.mapNotNull { toExercise(it) }
                if (list.isEmpty()) {
                    lastError = httpError.ifBlank {
                        "База «Упражнения» ответила пусто — справочник не тронут. " +
                            "Проверь, что интеграции открыт доступ к хабу «Тело»."
                    }
                    return@runCatching false
                }
                val sorted = list.sortedWith(compareBy({ it.order }, { it.name }))
                book.replace(sorted, now, stamp.format(Date(now)))
                lastError = ""
                val charge = sorted.count { e -> e.blocks.any { it.equals("Зарядка", ignoreCase = true) } }
                eventLog.add("справочник: из Notion ${sorted.size} движений, в «Зарядке» $charge")
                true
            }.getOrElse { e ->
                lastError = e.message ?: e.javaClass.simpleName
                eventLog.add("справочник: Notion не ответил — ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    /** Построчный отчёт для кнопки «Проверить доступ»: что и сколько прочиталось. */
    suspend fun diagnose(): String {
        val token = settings.notionToken().trim()
        if (token.isBlank()) return "Справочник: токена нет — читается файл сборки."
        val hub = NotionPlanSync.pageId(settings.notionHub())
        return withContext(Dispatchers.IO) {
            httpError = ""
            val out = StringBuilder()
            val found = findDatabase(hub, token)
            out.append("База «Упражнения»: ")
                .append(
                    when {
                        found != null -> "найдена под хабом"
                        httpError.isNotBlank() -> "хаб не отдался (${httpError.take(80)}), беру id по умолчанию"
                        else -> "под хабом не нашлась, беру id по умолчанию"
                    }
                )
                .append('\n')
            httpError = ""
            val rows = queryAll(found ?: DB_DEFAULT, token)
            val list = rows.mapNotNull { toExercise(it) }
            if (list.isEmpty()) {
                out.append("Строк: 0").append(if (httpError.isNotBlank()) " — $httpError" else "")
            } else {
                val charge = list.filter { e -> e.blocks.any { it.equals("Зарядка", ignoreCase = true) } }
                    .sortedBy { it.order }
                out.append("Строк: ").append(list.size)
                    .append(", в «Зарядке» ").append(charge.size).append(": ")
                    .append(charge.joinToString(" · ") { it.name.substringBefore(":") })
            }
            out.toString()
        }
    }

    // ---- Notion REST ----

    /** База «Упражнения» среди детей хаба — child_database с таким заголовком. */
    private fun findDatabase(hubId: String, token: String): String? {
        var cursor: String? = null
        do {
            val url = buildString {
                append(API).append("/blocks/").append(hubId).append("/children?page_size=100")
                if (cursor != null) append("&start_cursor=").append(cursor)
            }
            val body = get(url, token) ?: return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val results = json.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val b = results.optJSONObject(i) ?: continue
                if (b.optString("type") != "child_database") continue
                val title = b.optJSONObject("child_database")?.optString("title").orEmpty()
                if (title.trim().equals(DB_TITLE, ignoreCase = true)) {
                    return NotionPlanSync.pageId(b.optString("id"))
                }
            }
            cursor = json.optString("next_cursor").takeIf {
                json.optBoolean("has_more") && it.isNotBlank() && it != "null"
            }
        } while (cursor != null)
        return null
    }

    /** Все страницы базы, по сотне за запрос. */
    private fun queryAll(dbId: String, token: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        var pages = 0
        do {
            val payload = JSONObject().put("page_size", 100)
            if (cursor != null) payload.put("start_cursor", cursor)
            val body = post("$API/databases/$dbId/query", token, payload.toString()) ?: break
            val json = runCatching { JSONObject(body) }.getOrNull() ?: break
            val results = json.optJSONArray("results") ?: break
            for (i in 0 until results.length()) results.optJSONObject(i)?.let { out.add(it) }
            cursor = json.optString("next_cursor").takeIf {
                json.optBoolean("has_more") && it.isNotBlank() && it != "null"
            }
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return out
    }

    /** Строка базы → упражнение. Колонки — как владелец их назвал в Notion. */
    private fun toExercise(page: JSONObject): ExerciseBook.Exercise? {
        if (page.optBoolean("archived") || page.optBoolean("in_trash")) return null
        val props = page.optJSONObject("properties") ?: return null
        val name = plain(props, "Упражнение").trim()
        if (name.isBlank()) return null
        val id = ExerciseBook.slug(name)
        val seed = book.seedById(id)
        val scheme = plain(props, "Схема")
        val order = props.optJSONObject("№")?.optDouble("number")
            ?.takeIf { !it.isNaN() }?.toInt() ?: 99
        return ExerciseBook.Exercise(
            id = id,
            order = order,
            name = name,
            scheme = scheme,
            blocks = names(props, "Блок"),
            gear = names(props, "Инвентарь"),
            targets = names(props, "Что тренирует"),
            how = plain(props, "Как делать"),
            mistakes = plain(props, "Главные ошибки"),
            progression = plain(props, "Прогрессия"),
            video = plain(props, "Видео (запрос)"),
            garmin = plain(props, "Garmin Connect"),
            notion = page.optString("url"),
            unit = seed?.unit ?: guessUnit(name, scheme),
            aliases = seed?.aliases?.takeIf { it.isNotEmpty() } ?: ExerciseBook.derivedAliases(name),
        )
    }

    /** Единица подхода для движения, которого в файле сборки нет. */
    private fun guessUnit(name: String, scheme: String): String {
        val n = name.lowercase()
        val s = scheme.lowercase()
        return when {
            n.contains("вис") || n.contains("планка") || n.contains("растяжк") ||
                Regex("^~?\\d+(?:[–-]\\d+)?\\s*сек").containsMatchIn(s) -> ExerciseBook.UNIT_SEC
            n.contains("переноск") || Regex("\\d\\s*м(?:\\s|$)").containsMatchIn(s) -> ExerciseBook.UNIT_M
            !s.contains("×") && Regex("\\d\\s*мин").containsMatchIn(s) -> ExerciseBook.UNIT_MIN
            else -> ExerciseBook.UNIT_REPS
        }
    }

    /** Текст свойства (title или rich_text) — plain_text всех кусков подряд. */
    private fun plain(props: JSONObject, key: String): String {
        val prop = props.optJSONObject(key) ?: return ""
        val array = prop.optJSONArray("rich_text") ?: prop.optJSONArray("title") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            sb.append(array.optJSONObject(i)?.optString("plain_text").orEmpty())
        }
        return sb.toString().trim()
    }

    /** Имена вариантов multi_select. */
    private fun names(props: JSONObject, key: String): List<String> {
        val array = props.optJSONObject(key)?.optJSONArray("multi_select") ?: return emptyList()
        return (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() }
        }
    }

    private fun get(url: String, token: String): String? =
        call(Request.Builder().url(url).get(), token)

    private fun post(url: String, token: String, body: String): String? =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)), token)

    private fun call(builder: Request.Builder, token: String): String? {
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
                    401 -> "Notion не принял токен (401)"
                    403 -> "Notion: 403 — у интеграции нет прав на чтение"
                    404 -> "Notion: 404 — база не найдена или интеграцию к хабу не пустили"
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
