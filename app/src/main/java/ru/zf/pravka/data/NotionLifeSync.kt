package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.core.NotionLifeSchema
import ru.zf.pravka.core.PhoneDaySummary
import ru.zf.pravka.provider.batchAnswer
import ru.zf.pravka.provider.submitBatch

/**
 * Вся жизнь — в Notion, раз в час, сама. Владелец: «чтобы засечка уходила в
 * notion, чтобы я не выгружал csv с жизнью» и потом: «сделай, чтобы оттуда
 * можно было всегда взять актуальную структуру жизни, чтобы она
 * синхронизировалась аппом всегда».
 *
 * СТРУКТУРА — В КОДЕ (`core/NotionLifeSchema`), и это главное отличие от
 * первой версии. Тогда всё лежало в одной базе «Засечка» с полем «Домен»: и
 * дела ленты, и параллели, и еда, и тренировки, и зарядка — тридцать колонок,
 * у каждой строки заполнено пять. Владелец: «захожу, а там какой-то мусор по
 * времени, а еда и тело — огрызками. ужас». Теперь под хабом «Правка:
 * разборы» у каждого домена своя база:
 *
 *   Засечка      — только лента, строка на дело, минуты складываются в 1440.
 *   Дни          — сутки одной строкой: лента, телефон (YouTube, Telegram,
 *                  Claude, звонки), сон, еда, тело. Плюс ЕГО поля — «Дети
 *                  дома», «Марианна дома днём», «Якорь утра», «Заметка дня»,
 *                  которых нет в схеме и которые мы поэтому не трогаем.
 *   Еда          — приём с КБЖУ и составом.
 *   Тренировки   — активность с часов.
 *   Силовые      — сессия подходов голосом.
 *   Зарядка      — день GTG.
 *   Справочник   — структура как она настроена: категории с ценностью часа,
 *                  приложения по дням, цели питания, состояние синка и что
 *                  идёт сейчас.
 *   Паттерны, Подтверждения — библиотека аналитика; приложение приносит туда
 *                  кандидатов и вердикты владельца, статусы не трогает.
 *
 * Приложение само находит базы под хабом по названиям, создаёт недостающие и
 * достраивает недостающие колонки, поэтому структура в Notion всегда ровно
 * такая, как в коде. Мы гости в его пространстве: трогаем только свои
 * колонки, чужие строки не удаляем.
 *
 * КАК УСТРОЕНО. Раз в час — полный обход: по каждому событию считается снимок
 * полей и его хеш; изменилось — в очередь. Очередь разгребается на каждом
 * пятиминутном тике пачкой с паузой между запросами: Notion пускает три
 * запроса в секунду. Пары «ключ — страница» живут в файле; при пустом файле
 * карта восстанавливается из самих баз, иначе переустановка наплодила бы
 * дублей. Строка события, которого в приложении больше нет, архивируется —
 * иначе база зарастёт призраками, а сутки перестанут сходиться.
 *
 * ПЕРЕЕЗД со старой раскладки: строки еды, тренировок, силовых и зарядки,
 * лежавшие в «Засечке», архивируются, их ключи забываются — и те же события
 * приезжают в свои базы заново. Устаревшие колонки «Засечки» и «Дней»
 * убираются один раз, когда переезд закончен.
 */
class NotionLifeSync(
    private val context: Context,
    private val settings: Settings,
    private val zasechka: ZasechkaStore,
    private val food: FoodStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val analysis: AnalysisStore,
    private val phone: PhoneStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
    /** Сверка формулировок при склейке дублей паттернов; без ключа — только по словам. */
    private val provider: ru.zf.pravka.provider.ClaudeProvider? = null,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val STATE_FILE = "notion-life.json"

        /** Полный обход — раз в час, как просил владелец. */
        private const val SCAN_MS = 60 * 60_000L
        /** Сколько запросов в Notion за один тик службы. */
        private const val BUDGET = 120
        /** Пауза между запросами: лимит Notion — три в секунду. */
        private const val PAUSE_MS = 340L
        /** Сколько последних суток пересчитываем в «Дни»: досчёт телефона уходит на месяц назад. */
        private const val DAYS_BACK = 45
        /**
         * После 401/403 синк не стучится каждые пять минут — это бессмысленно
         * и шумно, — но и не молчит до смены токена: раз в час пробует снова,
         * потому что доступ чаще всего возвращают на стороне Notion.
         */
        private const val RETRY_BLOCKED_MS = 60 * 60_000L

        const val HUB_DEFAULT = NotionLifeSchema.HUB_DEFAULT

        // Базы аналитика под тем же хабом: приложение в них пишет, но их
        // структуру не ведёт. Известные id — страховка, если хаб не открыт
        // интеграции и список его детей не читается.
        private const val PATTERNS = "Паттерны"
        private const val CONFIRMATIONS = "Подтверждения"
        private val KNOWN_ANALYST_DBS = mapOf(
            PATTERNS to "92b78780453e4ed1852b01e205022465",
            CONFIRMATIONS to "c5fc6ee8258e433a990812dc8f1c1427",
        )

        /** Выше этого пересечения слов дубль очевиден и без модели. */
        private const val HARD_MATCH = 0.45
        /** Сверка формулировок — раз в сутки, не чаще: новое приносит ночной поиск. */
        private const val DUPE_MS = 20 * 3_600_000L
        /** Батч не ответил и за это время — считаем потерянным и спросим заново. */
        private const val DUPE_GIVEUP_MS = 30 * 3_600_000L

        /** Ключи событий по базам: по префиксу ключа видно, где живёт страница. */
        private val EVENT_PREFIXES = listOf("t", "f", "w", "s", "g")
        private fun dbOfKey(key: String): String? = when {
            key.startsWith("day:") || key.startsWith("pat:") || key.startsWith("conf:") ||
                key.startsWith("cat:") || key.startsWith("app:") || key.startsWith("target:") ||
                key.startsWith("sync:") -> null
            key.startsWith("t") -> NotionLifeSchema.ZASECHKA.name
            key.startsWith("f") -> NotionLifeSchema.EDA.name
            key.startsWith("w") -> NotionLifeSchema.TRENIROVKI.name
            key.startsWith("s") -> NotionLifeSchema.SILOVYE.name
            key.startsWith("g") -> NotionLifeSchema.ZARYADKA.name
            else -> null
        }
    }

    // ---- Состояние ----

    private class State {
        /** ключ события → id страницы Notion */
        val pages = HashMap<String, String>()
        /** ключ события → хеш последнего отправленного снимка */
        val hashes = HashMap<String, Int>()
        /** имя базы → id */
        val dbs = HashMap<String, String>()
        /** имя базы → карта страниц уже восстановлена из Notion */
        val mapped = HashSet<String>()
        /** имя базы → колонки схемы достроены (для текущей версии схемы) */
        val schemaOk = HashSet<String>()
        /** имя базы → устаревшие колонки убраны */
        val retired = HashSet<String>()
        /** версия схемы, под которую заведено состояние */
        var schemaVersion = 1
        /**
         * Страницы старой раскладки, которые надо заархивировать: id → ключ.
         * Пока список не пуст, устаревшие колонки не трогаем.
         */
        val legacy = HashMap<String, String>()
        /**
         * Решения о дублях паттернов: ключ находки → id страницы того
         * паттерна, который она повторяет («» = не дубль, свой). Решение
         * принимается один раз и живёт вечно: спрашивать модель об одной и
         * той же формулировке каждый час незачем.
         */
        val dupes = HashMap<String, String>()
        /** Батч сверки в работе: его id, что отправили и когда. */
        var dupeBatch = ""
        var dupeAt = 0L
        val dupeKeys = ArrayList<String>()
        val dupeIds = ArrayList<String>()
        var lastScan = 0L
    }

    private val state = State()
    private var stateLoaded = false
    private val running = AtomicBoolean(false)

    private class Job(
        val key: String,
        val db: String,
        val properties: JSONObject?,   // null = архивировать
        val hash: Int,
        /** Архив страницы старой раскладки по её id, а не по карте. */
        val archivePageId: String? = null,
    )

    private val queue = ArrayList<Job>()
    /** Базы, ответившие 404 в этом заходе: их строки откладываются до следующего обхода. */
    private val brokenDbs = HashSet<String>()

    @Volatile private var lastError = ""
    @Volatile private var blockedConfig: String? = null
    @Volatile private var blockedAt = 0L
    private val _statusFlow = MutableStateFlow("")
    val statusFlow: StateFlow<String> = _statusFlow

    fun lastError(): String = lastError
    fun pending(): Int = queue.size

    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val stampFmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)

    // ---- Вход ----

    /**
     * Один шаг: раз в час — полный обход и постановка в очередь, каждый вызов
     * — разгребание очереди на [BUDGET] запросов. Зовётся из пятиминутного
     * тика службы; [force] — с кнопки в настройках.
     */
    suspend fun sync(force: Boolean = false): Boolean {
        if (!settings.notionLife()) return false
        val token = settings.notionToken().trim()
        if (token.isBlank()) return false
        val hub = NotionPlanSync.pageId(settings.notionLifeHub())
        val cfg = "$token|$hub"
        val now = System.currentTimeMillis()
        if (!force && cfg == blockedConfig && now - blockedAt < RETRY_BLOCKED_MS) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            return withContext(Dispatchers.IO) {
                loadState()
                if (cfg != blockedConfig || force) blockedConfig = null
                if (!ensureDbs(token, hub)) return@withContext false
                val due = force || state.lastScan == 0L || now - state.lastScan >= SCAN_MS
                if (due) {
                    ensureSchema(token)
                    if (!ensureMaps(token)) return@withContext false
                    dupeStep(token)
                    scan()
                    state.lastScan = now
                    saveState()
                }
                brokenDbs.clear()
                val done = drain(token, cfg)
                // Состояние синка и «что идёт сейчас» — живые строки
                // Справочника, обновляются на каждом тике, если изменились.
                liveRows(token, cfg)
                if (due) retireColumns(token)
                saveState()
                done
            }
        } finally {
            running.set(false)
        }
    }

    // ---- Базы под хабом ----

    /**
     * Базы ищутся по названию среди детей хаба: так они переживут пересоздание
     * и переезд. Нет базы из схемы — создаётся под хабом по описанию из кода.
     * Хаб не читается (не открыт интеграции) — берём известные id, а в журнал
     * пишем, чего не хватает и что сделать.
     */
    private fun ensureDbs(token: String, hub: String): Boolean {
        val wanted = NotionLifeSchema.ALL.map { it.name } + KNOWN_ANALYST_DBS.keys
        if (wanted.all { state.dbs.containsKey(it) }) return true
        val found = HashMap<String, String>()
        var cursor: String? = null
        var hubReadable = true
        do {
            val url = "$API/blocks/$hub/children?page_size=100" +
                (cursor?.let { "&start_cursor=$it" } ?: "")
            val reply = runCatching { get(url, token) }.getOrElse { e ->
                hubReadable = false
                lastError = e.message ?: "сеть"
                null
            } ?: break
            val results = reply.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val b = results.optJSONObject(i) ?: continue
                if (b.optString("type") != "child_database") continue
                val title = b.optJSONObject("child_database")?.optString("title").orEmpty().trim()
                val id = b.optString("id").replace("-", "")
                if (title.isNotBlank() && id.isNotBlank()) found[title] = id
            }
            cursor = reply.optString("next_cursor").takeIf { reply.optBoolean("has_more") && it.isNotBlank() }
        } while (cursor != null)
        if (!hubReadable) {
            eventLog.add(
                "жизнь → Notion: хаб не читается ($lastError) — беру известные id баз. " +
                    "Открой интеграции страницу «Правка: разборы» (… → Connections), " +
                    "иначе новые базы не создать."
            )
        }
        for ((name, known) in KNOWN_ANALYST_DBS) {
            state.dbs[name] = found[name] ?: state.dbs[name] ?: known
        }
        for (db in NotionLifeSchema.ALL) {
            val id = found[db.name] ?: state.dbs[db.name] ?: db.knownId.ifBlank { null }
            if (id != null) {
                state.dbs[db.name] = id
                continue
            }
            if (!hubReadable) {
                eventLog.add("жизнь → Notion: базы «${db.name}» под хабом нет, а хаб не открыт — создать не могу")
                continue
            }
            // Создаём по описанию из кода: структура живёт в NotionLifeSchema.
            val reply = runCatching { post("$API/databases", token, NotionLifeSchema.createBody(db, hub)) }
                .getOrElse { e ->
                    lastError = e.message ?: "сеть"
                    eventLog.add("жизнь → Notion: база «${db.name}» не создалась — $lastError")
                    null
                } ?: continue
            val created = reply.optString("id").replace("-", "")
            if (created.isNotBlank()) {
                state.dbs[db.name] = created
                state.schemaOk.add(db.name)
                state.mapped.add(db.name)
                eventLog.add("жизнь → Notion: создана база «${db.name}» под хабом")
            }
        }
        saveState()
        return state.dbs.containsKey(NotionLifeSchema.ZASECHKA.name)
    }

    /**
     * Недостающие колонки достраиваются по схеме из кода — так база в Notion
     * всегда ровно такая, как описано в NotionLifeSchema, и новая колонка в
     * коде появляется в Notion сама. Существующие колонки не переписываются:
     * сменить тип живой колонке значит потерять её значения.
     */
    private fun ensureSchema(token: String) {
        if (state.schemaVersion != NotionLifeSchema.VERSION) {
            state.schemaOk.clear()
            state.retired.clear()
        }
        for (db in NotionLifeSchema.ALL) {
            if (db.name in state.schemaOk) continue
            val id = state.dbs[db.name] ?: continue
            val existing = runCatching { get("$API/databases/$id", token) }.getOrElse { e ->
                lastError = e.message ?: "сеть"
                eventLog.add("жизнь → Notion: схема «${db.name}» не читается — $lastError")
                null
            } ?: continue
            val have = existing.optJSONObject("properties")?.keys()?.asSequence()?.toSet() ?: emptySet()
            val missing = db.columns.filter { it.name !in have && it.type != "title" }
            if (missing.isNotEmpty()) {
                val props = JSONObject()
                missing.forEach { props.put(it.name, NotionLifeSchema.propertyJson(it)) }
                val ok = runCatching { patch("$API/databases/$id", token, JSONObject().put("properties", props)) }
                    .onFailure { e ->
                        lastError = e.message ?: "сеть"
                        eventLog.add("жизнь → Notion: колонки «${db.name}» не достроились — $lastError")
                    }.isSuccess
                if (!ok) continue
                eventLog.add("жизнь → Notion: «${db.name}» — достроено колонок: ${missing.size} (${missing.joinToString { it.name }})")
            }
            state.schemaOk.add(db.name)
            sleepBlocking()
        }
        if (NotionLifeSchema.ALL.all { it.name in state.schemaOk || state.dbs[it.name] == null }) {
            if (state.schemaVersion != NotionLifeSchema.VERSION) migrateState()
            state.schemaVersion = NotionLifeSchema.VERSION
        }
        saveState()
    }

    /**
     * Переезд со старой раскладки: события еды, тренировок, силовых, зарядки
     * и комментариев лежали строками в «Засечке». Их страницы уходят в архив,
     * ключи забываются — и те же события приедут в свои базы заново. Параллели
     * ленты (`t…` с треком) исчезли из приложения сами и уйдут призраками.
     */
    private fun migrateState() {
        var moved = 0
        for (key in state.pages.keys.toList()) {
            val legacyPrefix = key.startsWith("f") || key.startsWith("w") || key.startsWith("s") ||
                key.startsWith("g") || key.startsWith("c")
            if (!legacyPrefix || key.startsWith("sync:")) continue
            val pageId = state.pages.remove(key) ?: continue
            state.hashes.remove(key)
            state.legacy[pageId] = key
            moved++
        }
        // Карты еды и тела надо снять заново из НОВЫХ баз (там пусто), а не
        // считать восстановленными по старой «Засечке».
        state.mapped.removeAll(setOf(NotionLifeSchema.EDA.name, NotionLifeSchema.TRENIROVKI.name,
            NotionLifeSchema.SILOVYE.name, NotionLifeSchema.ZARYADKA.name, NotionLifeSchema.SPRAVOCHNIK.name))
        if (moved > 0) {
            eventLog.add(
                "жизнь → Notion: переезд на новую структуру — $moved строк еды и тела уйдут из " +
                    "«Засечки» в архив и приедут в свои базы"
            )
        }
    }

    /**
     * Устаревшие колонки убираются один раз, когда переезд закончен: строки
     * старой раскладки заархивированы, новые колонки достроены. Раньше —
     * нельзя: у старой сборки на телефоне эти колонки ещё в ходу.
     */
    private fun retireColumns(token: String) {
        if (state.schemaVersion != NotionLifeSchema.VERSION || state.legacy.isNotEmpty()) return
        for (db in NotionLifeSchema.ALL) {
            if (db.retired.isEmpty() || db.name in state.retired) continue
            val id = state.dbs[db.name] ?: continue
            if (db.name !in state.schemaOk) continue
            val existing = runCatching { get("$API/databases/$id", token) }.getOrNull() ?: continue
            val have = existing.optJSONObject("properties")?.keys()?.asSequence()?.toSet() ?: emptySet()
            val gone = db.retired.filter { it in have }
            if (gone.isNotEmpty()) {
                val props = JSONObject()
                gone.forEach { props.put(it, JSONObject.NULL) }
                val ok = runCatching { patch("$API/databases/$id", token, JSONObject().put("properties", props)) }
                    .onFailure { e -> eventLog.add("жизнь → Notion: старые колонки «${db.name}» не убрались — ${e.message}") }
                    .isSuccess
                if (!ok) continue
                eventLog.add("жизнь → Notion: «${db.name}» — убраны колонки старой раскладки: ${gone.joinToString()}")
            }
            state.retired.add(db.name)
            sleepBlocking()
        }
        saveState()
    }

    // ---- Карта страниц ----

    /**
     * Восстановить «ключ → страница» из самих баз. Один раз на базу: дальше
     * карта живёт в файле. Без этого переустановка приложения означала бы
     * вторую копию всех строк.
     */
    private suspend fun ensureMaps(token: String): Boolean {
        val plan = listOf<Pair<String, (JSONObject) -> String>>(
            NotionLifeSchema.ZASECHKA.name to { p -> richText(p, "EntryId") },
            NotionLifeSchema.EDA.name to { p -> richText(p, "MealId") },
            NotionLifeSchema.TRENIROVKI.name to { p -> richText(p, "WorkoutId") },
            NotionLifeSchema.SILOVYE.name to { p -> richText(p, "SessionId") },
            NotionLifeSchema.ZARYADKA.name to { p -> richText(p, "GtgId") },
            NotionLifeSchema.SPRAVOCHNIK.name to { p -> richText(p, "Ключ") },
            NotionLifeSchema.DNI.name to { p -> p.optJSONObject("Дата")?.optJSONObject("date")?.optString("start")?.take(10)?.let { "day:$it" }.orEmpty() },
            PATTERNS to { p -> titleText(p, "Паттерн").let { if (it.isBlank()) "" else "pat:" + patternKey(it) } },
            CONFIRMATIONS to { p -> richText(p, "Ключ") },
        )
        for ((name, keyOf) in plan) {
            if (name in state.mapped) continue
            val db = state.dbs[name] ?: continue
            var cursor: String? = null
            do {
                val body = JSONObject().put("page_size", 100)
                if (cursor != null) body.put("start_cursor", cursor)
                val reply = runCatching { post("$API/databases/$db/query", token, body) }
                    .getOrElse { e ->
                        lastError = e.message ?: "сеть"
                        eventLog.add("жизнь → Notion: база «$name» не читается — $lastError")
                        return false
                    }
                val results = reply.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val page = results.optJSONObject(i) ?: continue
                    val props = page.optJSONObject("properties") ?: continue
                    val key = keyOf(props)
                    if (key.isBlank()) continue
                    // В «Засечке» старой раскладки лежат и строки еды/тела с
                    // EntryId вида f…, w…: в карту ленты они не идут — это
                    // переезжающие страницы.
                    if (name == NotionLifeSchema.ZASECHKA.name && !key.startsWith("t")) {
                        if (state.pages[key] == null) state.legacy[page.optString("id")] = key
                        continue
                    }
                    state.pages[key] = page.optString("id")
                }
                cursor = reply.optString("next_cursor").takeIf { reply.optBoolean("has_more") && it.isNotBlank() }
                sleep()
            } while (cursor != null)
            state.mapped.add(name)
            saveState()
        }
        return true
    }

    private fun richText(props: JSONObject, name: String): String =
        props.optJSONObject(name)?.optJSONArray("rich_text")?.optJSONObject(0)
            ?.optString("plain_text").orEmpty()

    private fun titleText(props: JSONObject, name: String): String =
        props.optJSONObject(name)?.optJSONArray("title")?.optJSONObject(0)
            ?.optString("plain_text").orEmpty()

    // ---- Склейка дублей паттернов ----

    /**
     * Одна находка — один паттерн. Ночной поиск формулирует своими словами и
     * не знает библиотеки разбора: «работа появляется только там, где встречу
     * поставил кто-то другой» и «работа появляется только из чужого запроса» —
     * это один механизм, но в базе две строки, и вердикт владельца стоит на
     * обеих. Считать точки после такого нельзя.
     *
     * Спрашивать об этом каждый час незачем: новые формулировки приносит
     * ночной поиск, то есть раз в сутки. Поэтому шаг сверки — суточный и
     * батчем: пачка стоит половину обычной цены, ответ приходит за минуты,
     * а ждать его не жалко, потому что паттерн не срочная запись. Пока ответа
     * нет, находка в Notion не заводится — иначе двойник успел бы появиться
     * до того, как мы узнали, что он двойник.
     *
     * Явные повторы ловятся пересечением слов даром, до всякой модели.
     */
    private suspend fun dupeStep(token: String) {
        val ask = provider
        val pDb = state.dbs[PATTERNS] ?: return
        val now = System.currentTimeMillis()

        // 1. Ответ на прошлый вопрос, если он готов.
        if (state.dupeBatch.isNotBlank()) {
            val answer = ask?.batchAnswer(state.dupeBatch, Settings.MODEL_FABLE)?.getOrNull()
            if (answer != null) {
                applyDupeAnswer(answer.text)
                state.dupeBatch = ""
                state.dupeKeys.clear()
                state.dupeIds.clear()
                saveState()
            } else if (now - state.dupeAt > DUPE_GIVEUP_MS) {
                eventLog.add("паттерны: сверка не ответила за сутки — спрошу заново")
                state.dupeBatch = ""
                state.dupeKeys.clear()
                state.dupeIds.clear()
                saveState()
            }
            return
        }

        if (now - state.dupeAt < DUPE_MS) return
        val fresh = analysis.patternsFlow.value
            .filter { ("pat:" + patternKey(it.text)) !in state.dupes }
        if (fresh.isEmpty()) {
            state.dupeAt = now
            return
        }
        val library = runCatching { readPatternLibrary(token, pDb) }.getOrElse { e ->
            lastError = e.message ?: "сеть"
            return
        }
        state.dupeAt = now

        // 2. Явные повторы — сразу, даром.
        val doubtful = ArrayList<AnalysisStore.Pattern>()
        var glued = 0
        for (pt in fresh) {
            val pKey = "pat:" + patternKey(pt.text)
            val mine = state.pages[pKey]
            val best = library
                .filter { it.first != mine }
                .maxByOrNull { overlap(pt.text, it.second) }
            val score = best?.let { overlap(pt.text, it.second) } ?: 0.0
            when {
                best == null -> state.dupes[pKey] = ""
                score >= HARD_MATCH -> { state.dupes[pKey] = best.first; glued++ }
                else -> doubtful.add(pt)
            }
        }
        if (glued > 0) eventLog.add("паттерны: склеено по словам $glued")

        // 3. Спорное — одним вопросом на всю пачку.
        if (doubtful.isEmpty() || ask == null) {
            if (ask == null) doubtful.forEach { state.dupes["pat:" + patternKey(it.text)] = "" }
            saveState()
            return
        }
        val pool = library
        val system = "Ты сверяешь формулировки паттернов поведения одного человека. " +
            "Отвечаешь только JSON, без пояснений."
        val user = buildString {
            append("Библиотека уже заведённых паттернов:\n")
            pool.forEachIndexed { i, (_, text) -> append(i).append(". ").append(text.take(300)).append('\n') }
            append("\nНовые формулировки ночного поиска:\n")
            doubtful.forEachIndexed { i, pt -> append(i).append(". ").append(pt.text.take(300)).append('\n') }
            append(
                "\nДля каждой новой формулировки реши, описывает ли она ТОТ ЖЕ механизм, " +
                    "что одна из заведённых, — даже если слова совсем другие. Тот же механизм — " +
                    "это когда совпадают и условие запуска, и то, что происходит. Разные механизмы " +
                    "в одной области жизни (работа, сон, еда) — РАЗНЫЕ паттерны, как бы похоже они " +
                    "ни звучали. Слить два разных паттерна хуже, чем оставить два похожих: " +
                    "сомневаешься — отвечай -1.\n\n" +
                    "Ответь ТОЛЬКО JSON вида {\"same\": [n0, n1, ...]}, где n — номер из библиотеки " +
                    "или -1, по одному числу на каждую новую формулировку, в том же порядке."
            )
        }
        val id = ask.submitBatch(system, user, Settings.MODEL_FABLE, maxTokens = 2000, effort = "medium")
            .getOrElse { e ->
                lastError = e.message ?: "сверка не отправилась"
                eventLog.add("паттерны: сверка не отправилась — $lastError")
                saveState()
                return
            }
        state.dupeBatch = id
        state.dupeKeys.clear()
        state.dupeKeys.addAll(doubtful.map { "pat:" + patternKey(it.text) })
        state.dupeIds.clear()
        state.dupeIds.addAll(pool.map { it.first })
        eventLog.add("паттерны: спросил про ${doubtful.size} формулировок, жду батч")
        saveState()
    }

    /** Разбор ответа батча: по числу на каждую отправленную формулировку. */
    private fun applyDupeAnswer(text: String) {
        val body = text.substringAfter('{', "").let { "{" + it.substringBeforeLast('}', "") + "}" }
        val same = runCatching { JSONObject(body).optJSONArray("same") }.getOrNull()
        if (same == null) {
            eventLog.add("паттерны: сверка вернула не JSON — оставляю как есть")
            state.dupeKeys.forEach { state.dupes[it] = "" }
            return
        }
        var glued = 0
        for (i in state.dupeKeys.indices) {
            val at = if (i < same.length()) same.optInt(i, -1) else -1
            val key = state.dupeKeys[i]
            // Сама с собой находка склеиться не может: её собственная
            // страница тоже лежит в библиотеке.
            val pid = state.dupeIds.getOrNull(at)?.takeIf { it != state.pages[key] } ?: ""
            state.dupes[key] = pid
            if (pid.isNotBlank()) glued++
        }
        eventLog.add(
            "паттерны: сверка ответила, склеено $glued из ${state.dupeKeys.size}" +
                " (дубли идут точкой в свой паттерн, строкой не заводятся)"
        )
    }

    /** Вся библиотека паттернов Notion: id страницы и формулировка. */
    private fun readPatternLibrary(token: String, db: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", 100)
            if (cursor != null) body.put("start_cursor", cursor)
            val reply = post("$API/databases/$db/query", token, body)
            val results = reply.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val page = results.optJSONObject(i) ?: continue
                val props = page.optJSONObject("properties") ?: continue
                val text = titleText(props, "Паттерн")
                val id = page.optString("id")
                if (text.isNotBlank() && id.isNotBlank()) out.add(id to text)
            }
            cursor = reply.optString("next_cursor").takeIf { reply.optBoolean("has_more") && it.isNotBlank() }
        } while (cursor != null)
        return out
    }

    /**
     * Насколько две формулировки об одном. Слова длиннее трёх букв, обрезанные
     * до корня в пять букв (падежи и род иначе делают «встречу» и «встреча»
     * разными словами), и доля общих среди всех.
     */
    private fun overlap(a: String, b: String): Double {
        fun bag(t: String): Set<String> = t.lowercase()
            .replace(Regex("[^а-яёa-z0-9 ]"), " ")
            .split(' ')
            .filter { it.length > 3 }
            .map { it.take(5) }
            .toSet()
        val x = bag(a)
        val y = bag(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.count { it in y }
        return common.toDouble() / (x.size + y.size - common)
    }

    // ---- Обход: что должно лежать в Notion ----

    private suspend fun scan() {
        queue.clear()
        food.load(); sport.load(); strength.load(); analysis.load()
        val now = System.currentTimeMillis()
        val all = zasechka.all()
        val closed = all.filter { !it.open }
        val categories = zasechka.categories()
        val worth = categories.associate { it.name.trim().lowercase() to it.value }
        fun worthOf(cat: String) = worth[cat.trim().lowercase()] ?: 0
        val wanted = HashSet<String>()

        // 0. Переезд: страницы старой раскладки — в архив.
        for (pageId in state.legacy.keys) {
            queue.add(Job("legacy:$pageId", "", null, 0, archivePageId = pageId))
        }

        // 1. Лента — только основной трек, другого больше нет.
        state.dbs[NotionLifeSchema.ZASECHKA.name]?.let { zDb ->
            for (e in closed.sortedBy { it.start }) {
                val key = "t${e.id}"
                wanted.add(key)
                enqueue(key, zDb, NotionLifeSchema.ribbonRow(e, zasechka.budgetMinutes(e, now), worthOf(e.category), now))
            }
        }
        // 2. Еда, тренировки, силовые, зарядка — каждая в свою базу.
        state.dbs[NotionLifeSchema.EDA.name]?.let { db ->
            for (m in food.mealsFlow.value.filter { it.confirmed }) {
                val key = "f${m.id}"; wanted.add(key); enqueue(key, db, NotionLifeSchema.mealRow(m))
            }
        }
        state.dbs[NotionLifeSchema.TRENIROVKI.name]?.let { db ->
            for (w in sport.workoutsFlow.value) {
                val key = "w${w.id.ifBlank { w.start.toString() }}"; wanted.add(key); enqueue(key, db, NotionLifeSchema.workoutRow(w))
            }
        }
        state.dbs[NotionLifeSchema.SILOVYE.name]?.let { db ->
            for (s in strength.sessionsFlow.value.filter { !it.empty || it.done }) {
                val key = "s${s.date}"; wanted.add(key); enqueue(key, db, NotionLifeSchema.sessionRow(s))
            }
        }
        state.dbs[NotionLifeSchema.ZARYADKA.name]?.let { db ->
            for (g in strength.gtgFlow.value.filter { it.any }) {
                val key = "g${g.date}"; wanted.add(key); enqueue(key, db, NotionLifeSchema.gtgRow(g))
            }
        }
        // 3. Призраки: страницы событий, которых в приложении больше нет
        // (перенумерованная лента, удалённый приём, старые комментарии).
        for (key in state.pages.keys.toList()) {
            val db = dbOfKey(key) ?: if (key.startsWith("c")) "" else continue
            if (key !in wanted) enqueue(key, db, null)
        }
        // 4. Дни — полтора месяца назад: досчёт телефона может привезти
        // прошлые дни, а строка дня дешёвая — считается тут, в Notion едет
        // только изменившаяся.
        state.dbs[NotionLifeSchema.DNI.name]?.let { dDb ->
            val phoneDays = phone.daysFlow.value
            val labels = phone.labelsFlow.value
            val today = NotionLifeSchema.dayKey(now)
            var date = today
            repeat(DAYS_BACK + 1) {
                val row = dayRow(date, closed, phoneDays[date], labels, ::worthOf, now)
                if (row != null) enqueue("day:$date", dDb, row)
                date = dayBefore(date)
            }
        }
        // 5. Справочник: структура как настроена сейчас.
        state.dbs[NotionLifeSchema.SPRAVOCHNIK.name]?.let { db ->
            val liveKeys = HashSet<String>()
            categories.forEachIndexed { i, c ->
                val key = "cat:" + c.name.trim().lowercase()
                liveKeys.add(key)
                enqueue(key, db, NotionLifeSchema.categoryRow(c, i + 1, now))
            }
            val tracked = phone.trackedApps()
            val labels = phone.labelsFlow.value
            val todayPhone = phone.daysFlow.value[NotionLifeSchema.dayKey(now)]
            for ((pkg, category) in phone.immersiveMap()) {
                val key = "app:$pkg"
                liveKeys.add(key)
                val label = labels[pkg] ?: pkg.substringAfterLast('.')
                val minutes = ((todayPhone?.apps?.get(pkg) ?: 0L) + 30_000L) / 60_000L
                enqueue(key, db, NotionLifeSchema.appRow(pkg, label, category, pkg in tracked, minutes, now))
            }
            val t = settings.foodTargets()
            listOf(
                Triple("Калории", "kcal" to t.kcal, "ккал"),
                Triple("Белок", "protein" to t.protein, "г"),
                Triple("Жиры", "fat" to t.fat, "г"),
                Triple("Углеводы", "carbs" to t.carbs, "г"),
            ).forEachIndexed { i, (name, kv, unit) ->
                val key = "target:${kv.first}"
                liveKeys.add(key)
                enqueue(key, db, NotionLifeSchema.targetRow(name, kv.first, kv.second, unit, i + 1, now))
            }
            // Категория переименована или удалена — её строка справочника уходит.
            for (key in state.pages.keys.toList()) {
                if ((key.startsWith("cat:") || key.startsWith("app:") || key.startsWith("target:")) && key !in liveKeys) {
                    enqueue(key, db, null)
                }
            }
        }
        // 6. Паттерны приложения и подтверждения к ним.
        val pDb = state.dbs[PATTERNS]
        val cDb = state.dbs[CONFIRMATIONS]
        if (pDb != null) {
            for (pt in analysis.patternsFlow.value) {
                val pKey = "pat:" + patternKey(pt.text)
                val twin = state.dupes[pKey].orEmpty()
                // Находка, повторяющая заведённый паттерн, строкой не
                // становится — она становится точкой в нём. Иначе через
                // месяц ночного поиска библиотека это сорок строк, и сколько
                // точек у паттерна на самом деле, посчитать уже нельзя.
                if (twin.isBlank()) {
                    enqueue(pKey, pDb, patternProps(pt, isNew = state.pages[pKey] == null))
                } else if (state.pages[pKey] != null) {
                    // Двойник уже заведён (до того, как появилась сверка):
                    // сами не сливаем — это библиотека разбора, — но связь
                    // «Дубль чего» ставим, чтобы слияние было одним движением.
                    enqueue(pKey, pDb, patternProps(pt, isNew = false).put("__dupe", twin))
                }
                if (cDb == null) continue
                if (pt.judged && pt.verdictAt.isNotBlank()) {
                    val k = "conf:$pKey:v:${pt.verdictAt}:${pt.verdict}"
                    if (state.pages[k] == null) enqueue(k, cDb, confirmationProps(k, pKey, pt, verdict = true))
                }
                if (pt.lastSeen.isNotBlank() && pt.times >= 2) {
                    val k = "conf:$pKey:p:${pt.lastSeen}"
                    if (state.pages[k] == null) enqueue(k, cDb, confirmationProps(k, pKey, pt, verdict = false))
                }
            }
        }
    }

    /** В очередь — только если снимок изменился с прошлой отправки. */
    private fun enqueue(key: String, db: String, props: JSONObject?) {
        val hash = props?.toString()?.hashCode() ?: 0
        if (props == null) {
            if (state.pages[key] == null) return
        } else if (state.hashes[key] == hash && state.pages[key] != null) return
        queue.add(Job(key, db, props, hash))
    }

    /** Сутки одной строкой: лента, телефон, еда, тело — считает схема, здесь только сбор входов. */
    private fun dayRow(
        date: String,
        closed: List<ZasechkaStore.Entry>,
        phoneDay: PhoneStore.Day?,
        labels: Map<String, String>,
        worthOf: (String) -> Int,
        now: Long,
    ): JSONObject? {
        val start = NotionLifeSchema.dayStartOf(date)
        val end = start + 86_400_000L
        val main = closed.filter { it.start >= start && it.start < end }
        val meals = food.mealsFlow.value.filter { it.confirmed && NotionLifeSchema.dayKey(it.ts) == date }
        val workouts = sport.workoutsFlow.value.filter { NotionLifeSchema.dayKey(it.start) == date }
        val health = sport.healthFlow.value.firstOrNull { it.date == date }
        val session = strength.sessionsFlow.value.firstOrNull { it.date == date && (!it.empty || it.done) }
        val gtg = strength.gtgFlow.value.firstOrNull { it.date == date && it.any }
        val notes = strength.rawFlow.value
            .filter { it.kind == "comment" && NotionLifeSchema.dayKey(it.ts) == date }
            .sortedBy { it.ts }
            .joinToString("; ") { "${hm.format(Date(it.ts))} ${it.text.trim()}" }
        // Сон с часов: сперва wellness, иначе приписка Garmin на строке сна.
        val wake = main.filter { it.category.equals("Сон", ignoreCase = true) }.maxByOrNull { it.end }
        val garmin = wake?.let { sleepFromNote(it.raw) }
        val body = NotionLifeSchema.BodyDay(
            workouts = workouts.size,
            workoutMin = workouts.sumOf { it.minutes },
            strength = session != null,
            gtgStatus = gtg?.status().orEmpty(),
            notes = notes,
            weightKg = health?.weightKg ?: 0.0,
            restingHr = health?.restingHr ?: 0,
            hrv = health?.hrv ?: 0,
            steps = health?.steps ?: 0,
            sleepHours = health?.sleepHours?.takeIf { it > 0 } ?: garmin?.first ?: 0.0,
            sleepScore = health?.sleepScore?.takeIf { it > 0 } ?: garmin?.second ?: 0,
        )
        return NotionLifeSchema.dayRow(
            date = date,
            main = main,
            allClosed = closed,
            meals = meals,
            phone = PhoneDaySummary.forNotion(phoneDay, labels),
            body = body,
            budgetOf = { zasechka.budgetMinutes(it, now) },
            worthOf = worthOf,
            now = now,
        )
    }

    // ---- Живые строки Справочника: состояние синка и «сейчас» ----

    private suspend fun liveRows(token: String, cfg: String) {
        val db = state.dbs[NotionLifeSchema.SPRAVOCHNIK.name] ?: return
        if (db in brokenDbs) return
        val now = System.currentTimeMillis()
        val open = runCatching { zasechka.openEntry() }.getOrNull()
        val status = buildString {
            append("обход ${stampFmt.format(Date(state.lastScan))}")
            append(" · версия ${BuildConfig.VERSION_NAME}")
            if (queue.isNotEmpty()) append(" · в очереди ${queue.size}")
            if (state.legacy.isNotEmpty()) append(" · переезд: осталось ${state.legacy.size}")
            if (lastError.isNotBlank()) append(" · ошибка: $lastError")
        }
        // Хеш «сейчас» без секунд, иначе строка ехала бы каждый тик ради
        // одной и той же минуты начала.
        val jobs = listOf(
            Job("sync:status", db, NotionLifeSchema.statusRow(status, now), status.hashCode()),
            Job("sync:now", db, NotionLifeSchema.nowRow(open, now), (open?.id ?: 0L).hashCode() * 31 + (open?.title.hashCode() ?: 0)),
        )
        for (job in jobs) {
            if (state.hashes[job.key] == job.hash && state.pages[job.key] != null) continue
            val ok = runCatching { push(token, job) }.onFailure { e ->
                lastError = e.message ?: "сеть"
            }.isSuccess
            if (!ok) return
            sleep()
        }
    }

    // ---- Разгребание очереди ----

    private suspend fun drain(token: String, cfg: String): Boolean {
        if (queue.isEmpty()) return false
        var sent = 0
        var archived = 0
        var rejected = 0
        val iter = queue.iterator()
        // Отвергнутые тоже стоят запроса, поэтому считаются в бюджет тика:
        // иначе пачка кривых строк выгребла бы всю очередь за один заход.
        while (iter.hasNext() && sent + rejected < BUDGET) {
            val job = iter.next()
            if (job.db in brokenDbs) continue
            val result = runCatching { push(token, job) }
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "сеть"
                lastError = message
                if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
                    blockedConfig = cfg
                    blockedAt = System.currentTimeMillis()
                    eventLog.add("жизнь → Notion: $message — синк на паузе, попробую через час или после смены токена")
                    _statusFlow.value = "${timeNow()} · $message"
                    return sent > 0
                }
                // 404 на страницу или базу: чаще всего интеграцию не пустили
                // к ОДНОЙ базе. Остальные не должны стоять из-за неё.
                if (message.contains("HTTP 404")) {
                    if (job.properties == null && job.archivePageId == null) {
                        // Страницы уже нет — карта врала, забываем.
                        state.pages.remove(job.key); state.hashes.remove(job.key)
                        iter.remove()
                        continue
                    }
                    if (job.archivePageId != null) {
                        state.legacy.remove(job.archivePageId)
                        iter.remove()
                        continue
                    }
                    if (job.db.isNotBlank() && brokenDbs.add(job.db)) {
                        val name = state.dbs.entries.firstOrNull { it.value == job.db }?.key ?: job.db
                        eventLog.add(
                            "жизнь → Notion: база «$name» не отвечает ($message) — открой ей интеграцию " +
                                "(… → Connections на хабе), остальные едут дальше"
                        )
                    }
                    continue
                }
                // 400 — Notion не принял ИМЕННО ЭТУ строку: неизвестная опция
                // select, слишком длинное поле. Очередь из-за неё встать не
                // должна: одна кривая запись держала бы всю жизнь владельца
                // за дверью, а он бы видел только «не удалось». Строка
                // откладывается до следующего обхода (хеш ей не записан),
                // остальные едут дальше.
                if (message.contains("HTTP 400")) {
                    iter.remove()
                    if (rejected == 0) eventLog.add("жизнь → Notion: строка «${job.key}» не принята ($message) — пропускаю")
                    rejected++
                    sleep()
                    continue
                }
                eventLog.add("жизнь → Notion: не удалось ($message), в очереди ${queue.size}")
                _statusFlow.value = "${timeNow()} · не удалось: $message"
                return sent > 0
            }
            iter.remove()
            sent++
            if (job.properties == null) archived++
            sleep()
        }
        // Отложенные строки сломанных баз ждут следующего обхода — из очереди
        // их убираем, иначе тик за тиком будет упираться в них же.
        if (brokenDbs.isNotEmpty()) queue.removeAll { it.db in brokenDbs }
        lastError = ""
        _statusFlow.value = "${timeNow()} · отправлено $sent" +
            (if (rejected > 0) ", не принято $rejected" else "") +
            (if (queue.isNotEmpty()) ", в очереди ${queue.size}" else " ✓")
        eventLog.add(
            "жизнь → Notion: отправлено $sent" +
                (if (archived > 0) ", убрано строк $archived" else "") +
                (if (rejected > 0) ", не принято $rejected" else "") +
                (if (queue.isNotEmpty()) ", осталось ${queue.size}" else "")
        )
        return sent > 0
    }

    private fun push(token: String, job: Job) {
        if (job.archivePageId != null) {
            patch("$API/pages/${job.archivePageId}", token, JSONObject().put("archived", true))
            state.legacy.remove(job.archivePageId)
            return
        }
        val existing = state.pages[job.key]
        if (job.properties == null) {
            if (existing != null) {
                patch("$API/pages/$existing", token, JSONObject().put("archived", true))
                state.pages.remove(job.key)
                state.hashes.remove(job.key)
            }
            return
        }
        val (props, whole) = withRelations(job)
        if (existing != null) {
            patch("$API/pages/$existing", token, JSONObject().put("properties", props))
        } else {
            val reply = post(
                "$API/pages", token,
                JSONObject()
                    .put("parent", JSONObject().put("database_id", job.db))
                    .put("properties", props),
            )
            val id = reply.optString("id")
            if (id.isNotBlank()) state.pages[job.key] = id
        }
        // Хеш значит «отправлено ЦЕЛИКОМ», иначе строка больше никогда не
        // вернётся в очередь: связь могла ждать страницы, которой ещё нет.
        if (whole) state.hashes[job.key] = job.hash else state.hashes.remove(job.key)
    }

    /**
     * Служебное поле «__pattern» превращается в relation по карте. Второе
     * значение — доставлена ли строка ЦЕЛИКОМ: ложь, если связь ждала
     * страницы, которой в карте ещё нет.
     */
    private fun withRelations(job: Job): Pair<JSONObject, Boolean> {
        val props = JSONObject(job.properties!!.toString())
        var whole = true
        val key = props.optString("__pattern")
        if (key.isNotBlank()) {
            // Дубль ссылается на тот паттерн, который повторяет: точка должна
            // лечь под каноническую формулировку, а не под её двойника.
            val pid = state.dupes[key]?.takeIf { it.isNotBlank() } ?: state.pages[key]
            if (pid == null) whole = false
            else props.put("Паттерн", JSONObject().put("relation", JSONArray().put(JSONObject().put("id", pid))))
        }
        // «__dupe» — уже готовый id страницы, а не ключ в карте.
        props.optString("__dupe").takeIf { it.isNotBlank() }?.let { pid ->
            props.put("Дубль чего", JSONObject().put("relation", JSONArray().put(JSONObject().put("id", pid))))
        }
        props.remove("__pattern")
        props.remove("__dupe")
        return props to whole
    }

    // ---- Снимок полей: Паттерны и Подтверждения ----

    /**
     * Паттерн приложения в библиотеке аналитика. Статус — его поле, не наше:
     * при создании ставим «Кандидат», дальше не трогаем. Наше — «Заявлено
     * приложением»: сколько точек нашёл ночной поиск.
     */
    private fun patternProps(pt: AnalysisStore.Pattern, isNew: Boolean): JSONObject = JSONObject().apply {
        put("Заявлено приложением", NotionLifeSchema.number(pt.points))
        if (isNew) {
            put("Паттерн", NotionLifeSchema.title(pt.text))
            put("Источник", NotionLifeSchema.select("Приложение"))
            put("Тип", NotionLifeSchema.select("Паттерн"))
            put("Статус", NotionLifeSchema.select("Кандидат"))
            if (pt.firstSeen.isNotBlank()) put("Первое появление", NotionLifeSchema.dateDay(pt.firstSeen))
            confidenceOption(pt.confidence)?.let { put("Уверенность", NotionLifeSchema.select(it)) }
        }
    }

    private fun confirmationProps(key: String, patternKey: String, pt: AnalysisStore.Pattern, verdict: Boolean): JSONObject =
        JSONObject().apply {
            val date = if (verdict) pt.verdictAt else pt.lastSeen
            val who = if (verdict) "Саша" else "Приложение"
            val what = when {
                verdict && pt.accepted -> "подтверждаю"
                verdict -> "отклоняю"
                else -> "точка"
            }
            put("Подтверждение", NotionLifeSchema.title("$who: $what · ${NotionLifeSchema.humanDay(date)}"))
            put("Дата", NotionLifeSchema.dateDay(date))
            put("Кто", NotionLifeSchema.select(who))
            put("Вердикт", NotionLifeSchema.select(what))
            put(
                "Улика",
                NotionLifeSchema.rich(
                    if (verdict) "Вердикт в приложении по паттерну «${pt.text}»."
                    else "Ночной поиск увидел снова: ${pt.points} точек, уверенность ${pt.confidence.ifBlank { "не указана" }}, всего раз ${pt.times}."
                ),
            )
            put("Ключ", NotionLifeSchema.rich(key))
            put("__pattern", patternKey)
        }

    private fun confidenceOption(raw: String): String? {
        val c = raw.lowercase()
        return when {
            c.contains("высок") -> "высокая"
            c.contains("низк") && c.contains("средн") -> "низкая-средняя"
            c.contains("средн") -> "средняя"
            c.contains("низк") -> "низкая"
            else -> null
        }
    }

    /** Ключ карты паттернов: пять самых длинных слов, потому что формулировки плавают. */
    private fun patternKey(text: String): String = text.lowercase()
        .replace(Regex("[^а-яёa-z0-9 ]"), " ")
        .split(' ').filter { it.length > 3 }.take(5).sorted().joinToString(" ")

    /** «Garmin: сон 7.2 ч, счёт 82» → 7.2 и 82. */
    private fun sleepFromNote(raw: String): Pair<Double, Int>? {
        val m = Regex("""сон\s+([\d.,]+)\s*ч""", RegexOption.IGNORE_CASE).find(raw) ?: return null
        val hours = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val score = Regex("""счёт\s+(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours to score
    }

    private fun timeNow(): String = hm.format(Date(System.currentTimeMillis()))

    private suspend fun sleep() = delay(PAUSE_MS)
    private fun sleepBlocking() = Thread.sleep(PAUSE_MS)

    // ---- HTTP ----

    private fun get(url: String, token: String): JSONObject =
        execute(Request.Builder().url(url).get(), token)

    private fun post(url: String, token: String, body: JSONObject): JSONObject =
        execute(Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)), token)

    private fun patch(url: String, token: String, body: JSONObject): JSONObject =
        execute(Request.Builder().url(url).patch(body.toString().toRequestBody(JSON_TYPE)), token)

    private fun execute(builder: Request.Builder, token: String): JSONObject {
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", VERSION)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw java.io.IOException(
                    "Notion HTTP ${response.code}" + (if (message.isNotBlank()) ": ${message.take(140)}" else "")
                )
            }
            return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    // ---- Файл состояния ----

    private val stateFile: File get() = File(context.filesDir, STATE_FILE)

    private fun loadState() {
        if (stateLoaded) return
        stateLoaded = true
        val text = runCatching { stateFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(text)
            o.optJSONObject("pages")?.let { p -> p.keys().forEach { k -> state.pages[k] = p.optString(k) } }
            o.optJSONObject("hashes")?.let { h -> h.keys().forEach { k -> state.hashes[k] = h.optInt(k) } }
            o.optJSONObject("dbs")?.let { d -> d.keys().forEach { k -> state.dbs[k] = d.optString(k) } }
            o.optJSONArray("mapped")?.let { a -> for (i in 0 until a.length()) state.mapped.add(a.optString(i)) }
            o.optJSONArray("schemaOk")?.let { a -> for (i in 0 until a.length()) state.schemaOk.add(a.optString(i)) }
            o.optJSONArray("retired")?.let { a -> for (i in 0 until a.length()) state.retired.add(a.optString(i)) }
            state.schemaVersion = o.optInt("schemaVersion", 1)
            o.optJSONObject("legacy")?.let { l -> l.keys().forEach { k -> state.legacy[k] = l.optString(k) } }
            o.optJSONObject("dupes")?.let { d -> d.keys().forEach { k -> state.dupes[k] = d.optString(k) } }
            state.dupeBatch = o.optString("dupeBatch")
            state.dupeAt = o.optLong("dupeAt")
            o.optJSONArray("dupeKeys")?.let { a -> for (i in 0 until a.length()) state.dupeKeys.add(a.optString(i)) }
            o.optJSONArray("dupeIds")?.let { a -> for (i in 0 until a.length()) state.dupeIds.add(a.optString(i)) }
            state.lastScan = o.optLong("lastScan")
        }
        // Файл прошлой раскладки: обход снова с нуля — схема достроится,
        // строки еды и тела переедут, карты новых баз снимутся заново.
        if (state.schemaVersion != NotionLifeSchema.VERSION) state.lastScan = 0L
    }

    private fun saveState() {
        val o = JSONObject()
            .put("pages", JSONObject(state.pages as Map<*, *>))
            .put("hashes", JSONObject(state.hashes as Map<*, *>))
            .put("dbs", JSONObject(state.dbs as Map<*, *>))
            .put("mapped", JSONArray(state.mapped.toList()))
            .put("schemaOk", JSONArray(state.schemaOk.toList()))
            .put("retired", JSONArray(state.retired.toList()))
            .put("schemaVersion", state.schemaVersion)
            .put("legacy", JSONObject(state.legacy as Map<*, *>))
            .put("dupes", JSONObject(state.dupes as Map<*, *>))
            .put("dupeBatch", state.dupeBatch)
            .put("dupeAt", state.dupeAt)
            .put("dupeKeys", JSONArray(state.dupeKeys as List<*>))
            .put("dupeIds", JSONArray(state.dupeIds as List<*>))
            .put("lastScan", state.lastScan)
        runCatching {
            val tmp = File(stateFile.parentFile, "$STATE_FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    /** Забыть карту страниц — на случай, если базу пересоздали. */
    fun resetMaps() {
        state.pages.clear(); state.hashes.clear(); state.mapped.clear(); state.dbs.clear(); state.dupes.clear()
        state.schemaOk.clear(); state.retired.clear(); state.legacy.clear()
        state.dupeBatch = ""; state.dupeAt = 0L; state.dupeKeys.clear(); state.dupeIds.clear()
        state.lastScan = 0L
        queue.clear()
        blockedConfig = null
        saveState()
    }
}
