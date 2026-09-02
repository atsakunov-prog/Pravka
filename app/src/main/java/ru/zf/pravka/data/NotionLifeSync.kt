package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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

/**
 * Вся жизнь — в Notion, раз в час, сама. Владелец: «чтобы засечка уходила в
 * notion, чтобы я не выгружал csv с жизнью (время, еда, спорт и т.п.)».
 *
 * СТРУКТУРУ ПРИДУМЫВАТЬ НЕ ПРИШЛОСЬ — она уже стояла. Под хабом «Правка:
 * разборы» его аналитик (проект в Клоде) за две недели построил ровно то, что
 * нужно, и даже подписал поля «для синхронизатора»:
 *
 *   Засечка        — журнал событий, строка на событие всех доменов. Схема
 *                    один в один повторяет CSV «всей жизни»: Домен, Бюджет,
 *                    Источник, EntryId, Носитель ID и связь «Носитель».
 *   Дни            — строка на сутки: покрытие, минуты по категориям, сон,
 *                    экран параллелью, еда. Плюс ЕГО поля — «Дети дома»,
 *                    «Марианна дома днём», «Якорь утра», «Заметка дня»: их
 *                    заполняет человек, и мы к ним не прикасаемся.
 *   Паттерны       — библиотека паттернов и гипотез со статусами. Источник
 *                    правды по статусам — аналитик, не приложение.
 *   Подтверждения  — единственное, чего не хватало, и он просил именно это:
 *                    «отдельно паттерны и подтверждения, потому что я хочу
 *                    работать над этим». Одна строка — одна датированная
 *                    улика: слово Саши из приложения, повторная находка
 *                    ночного поиска, проверка аналитика в разборе.
 *   Разборы, Стоп-сигналы — аналитика и владельца; приложение туда не пишет.
 *
 * Мы — гости в его базах, поэтому правила те же, что у «Дневника»: трогаем
 * только свои колонки, чужие не переписываем, чужие строки не удаляем.
 *
 * КАК УСТРОЕНО. Раз в час — полный обход: по каждому событию считается снимок
 * полей и его хеш; изменилось — в очередь. Очередь разгребается на каждом
 * пятиминутном тике, пачкой, с паузой между запросами: Notion пускает три
 * запроса в секунду, и первый заезд — шесть сотен строк ленты — растянется на
 * час, а дальше в час набегает пара десятков правок. Пары «ключ — страница»
 * живут в файле, чтобы не спрашивать Notion «а есть ли уже такая строка» на
 * каждое событие; при пустом файле карта один раз восстанавливается из самой
 * базы, иначе переустановка наплодила бы дублей.
 *
 * Лента постоянно перенумеровывает куски (полночь, врезки, склейки), и
 * страница исчезнувшей записи должна исчезать вместе с ней — иначе база
 * зарастёт призраками, а сутки перестанут сходиться. Такие страницы
 * архивируются (это наши строки, не его).
 */
class NotionLifeSync(
    private val context: Context,
    private val settings: Settings,
    private val zasechka: ZasechkaStore,
    private val food: FoodStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val analysis: AnalysisStore,
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
        /** Сколько последних суток пересчитываем в «Дни». */
        private const val DAYS_BACK = 14
        /** Полный день по правилу хаба: покрытие основного трека ≥ 1370 минут. */
        private const val FULL_DAY_MIN = 1370L

        /** Хаб «Правка: разборы» — под ним живут все базы. */
        const val HUB_DEFAULT = "3cdc4ffca2d581568abad6839b74784c"

        // Известные id баз под хабом - страховка, если хаб не открыт
        // интеграции и список его детей не читается.
        private val KNOWN_DBS = mapOf(
            "Засечка" to "5b11be1184494f0197b05ffffe357504",
            "Дни" to "39a031d4ac724ed28d4876e79319e202",
            "Паттерны" to "92b78780453e4ed1852b01e205022465",
            "Подтверждения" to "c5fc6ee8258e433a990812dc8f1c1427",
        )

        /** Выше этого пересечения слов дубль очевиден и без модели. */
        private const val HARD_MATCH = 0.45
        /** Сверка формулировок — раз в сутки, не чаще: новое приносит ночной поиск. */
        private const val DUPE_MS = 20 * 3_600_000L
        /** Батч не ответил и за это время — считаем потерянным и спросим заново. */
        private const val DUPE_GIVEUP_MS = 30 * 3_600_000L

        private val WEEKDAYS = listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")
        private val MEAL_KINDS = setOf("завтрак", "обед", "ужин", "перекус")
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
    )

    private val queue = ArrayList<Job>()

    @Volatile private var lastError = ""
    @Volatile private var blockedConfig: String? = null
    private val _statusFlow = MutableStateFlow("")
    val statusFlow: StateFlow<String> = _statusFlow

    fun lastError(): String = lastError
    fun pending(): Int = queue.size

    private val dateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val hm = SimpleDateFormat("HH:mm", Locale.US)

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
        if (!force && cfg == blockedConfig) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            return withContext(Dispatchers.IO) {
                loadState()
                val now = System.currentTimeMillis()
                if (!ensureDbs(token, hub)) return@withContext false
                val due = force || state.lastScan == 0L || now - state.lastScan >= SCAN_MS
                if (due) {
                    if (!ensureMaps(token)) return@withContext false
                    dupeStep(token)
                    scan()
                    state.lastScan = now
                    saveState()
                }
                val done = drain(token, cfg)
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
     * и переезд. Хаб не читается (не открыт интеграции) — берём известные id,
     * а в журнал пишем, чего не хватает.
     */
    private fun ensureDbs(token: String, hub: String): Boolean {
        if (state.dbs.size >= KNOWN_DBS.size) return true
        val found = HashMap<String, String>()
        var cursor: String? = null
        var ok = true
        do {
            val url = "$API/blocks/$hub/children?page_size=100" +
                (cursor?.let { "&start_cursor=$it" } ?: "")
            val reply = runCatching { get(url, token) }.getOrElse { e ->
                ok = false
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
        if (!ok) {
            eventLog.add(
                "жизнь → Notion: хаб не читается ($lastError) — беру известные id баз. " +
                    "Открой интеграции страницу «Правка: разборы» (… → Connections)."
            )
        }
        for ((name, known) in KNOWN_DBS) {
            state.dbs[name] = found[name] ?: state.dbs[name] ?: known
        }
        return true
    }

    // ---- Карта страниц ----

    /**
     * Восстановить «ключ → страница» из самих баз. Один раз на базу: дальше
     * карта живёт в файле. Без этого переустановка приложения означала бы
     * вторую копию всех шестисот строк.
     */
    private suspend fun ensureMaps(token: String): Boolean {
        val plan = listOf(
            "Засечка" to { p: JSONObject -> richText(p, "EntryId") },
            "Дни" to { p: JSONObject -> p.optJSONObject("Дата")?.optJSONObject("date")?.optString("start")?.take(10)?.let { "day:$it" }.orEmpty() },
            "Паттерны" to { p: JSONObject -> titleText(p, "Паттерн").let { if (it.isBlank()) "" else "pat:" + patternKey(it) } },
            "Подтверждения" to { p: JSONObject -> richText(p, "Ключ") },
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
                        // 404 на базу — это про доступ; остальное — сеть, повторим.
                        eventLog.add("жизнь → Notion: база «$name» не читается — $lastError")
                        return false
                    }
                val results = reply.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val page = results.optJSONObject(i) ?: continue
                    val props = page.optJSONObject("properties") ?: continue
                    val key = keyOf(props)
                    if (key.isNotBlank()) state.pages[key] = page.optString("id")
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
        val pDb = state.dbs["Паттерны"] ?: return
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
        val wanted = HashSet<String>()

        // 1. Лента, основной трек первым: параллели ссылаются на носителей.
        val zDb = state.dbs["Засечка"] ?: return
        for (e in closed.sortedWith(compareBy<ZasechkaStore.Entry> { it.parallel }.thenBy { it.start })) {
            val key = "t${e.id}"
            wanted.add(key)
            enqueue(key, zDb, ribbonProps(e, all, now))
        }
        // 2. Еда, тренировки, силовые, зарядка, комментарии.
        for (m in food.mealsFlow.value.filter { it.confirmed }) {
            val key = "f${m.id}"; wanted.add(key); enqueue(key, zDb, mealProps(m))
        }
        for (w in sport.workoutsFlow.value) {
            val key = "w${w.id.ifBlank { w.start.toString() }}"; wanted.add(key); enqueue(key, zDb, workoutProps(w))
        }
        for (s in strength.sessionsFlow.value.filter { !it.empty || it.done }) {
            val key = "s${s.date}"; wanted.add(key); enqueue(key, zDb, sessionProps(s))
        }
        for (g in strength.gtgFlow.value.filter { it.any }) {
            val key = "g${g.date}"; wanted.add(key); enqueue(key, zDb, gtgProps(g))
        }
        for (r in strength.rawFlow.value.filter { it.kind == "comment" }) {
            val key = "c${r.ts}"; wanted.add(key); enqueue(key, zDb, commentProps(r))
        }
        // 3. Призраки: страницы событий, которых в приложении больше нет.
        for (key in state.pages.keys.toList()) {
            if (key.startsWith("day:") || key.startsWith("pat:") || key.startsWith("conf:")) continue
            if (key !in wanted) enqueue(key, zDb, null)
        }
        // 4. Дни — последние две недели, сегодняшний пересчитывается каждый час.
        val dDb = state.dbs["Дни"]
        if (dDb != null) {
            val today = dayKey(now)
            var date = today
            repeat(DAYS_BACK + 1) {
                enqueue("day:$date", dDb, dayProps(date, closed, now))
                date = dayBefore(date)
            }
        }
        // 5. Паттерны приложения и подтверждения к ним.
        val pDb = state.dbs["Паттерны"]
        val cDb = state.dbs["Подтверждения"]
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

    // ---- Разгребание очереди ----

    private suspend fun drain(token: String, cfg: String): Boolean {
        if (queue.isEmpty()) return false
        var sent = 0
        var pageIdWaits = 0
        var rejected = 0
        val iter = queue.iterator()
        // Отвергнутые тоже стоят запроса, поэтому считаются в бюджет тика:
        // иначе пачка кривых строк выгребла бы всю очередь за один заход.
        while (iter.hasNext() && sent + rejected < BUDGET) {
            val job = iter.next()
            val result = runCatching { push(token, job) }
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "сеть"
                lastError = message
                if (message.contains("HTTP 401") || message.contains("HTTP 403") ||
                    message.contains("HTTP 404")
                ) {
                    blockedConfig = cfg
                    eventLog.add("жизнь → Notion: $message — синк на паузе до смены токена или доступа")
                    _statusFlow.value = "${timeNow()} · $message"
                    return sent > 0
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
            if (job.properties == null) pageIdWaits++
            sleep()
        }
        lastError = ""
        _statusFlow.value = "${timeNow()} · отправлено $sent" +
            (if (rejected > 0) ", не принято $rejected" else "") +
            (if (queue.isNotEmpty()) ", в очереди ${queue.size}" else " ✓")
        eventLog.add(
            "жизнь → Notion: отправлено $sent" +
                (if (pageIdWaits > 0) ", убрано призраков $pageIdWaits" else "") +
                (if (rejected > 0) ", не принято $rejected" else "") +
                (if (queue.isNotEmpty()) ", осталось ${queue.size}" else "")
        )
        return sent > 0
    }

    private fun push(token: String, job: Job) {
        val existing = state.pages[job.key]
        if (job.properties == null) {
            if (existing != null) {
                patch("$API/pages/$existing", token, JSONObject().put("archived", true))
                state.pages.remove(job.key)
                state.hashes.remove(job.key)
            }
            return
        }
        // Связь с носителем ставится в момент отправки: страница носителя
        // могла появиться только что, в этой же пачке.
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
        // вернётся в очередь. Носителя могло не быть в карте: дело ещё идёт
        // (открытые в Notion не уезжают), и параллель поверх него осталась бы
        // навсегда без связи — с «Носитель ID», но с пустой клеткой рядом.
        // Такой строке хеш не пишем: следующий обход поставит её снова и
        // доставит связь, как только дело закроется.
        if (whole) state.hashes[job.key] = job.hash else state.hashes.remove(job.key)
    }

    /**
     * Служебные поля «__host» и «__pattern» превращаются в relation по карте.
     * Второе значение — доставлена ли строка ЦЕЛИКОМ: ложь, если связь ждала
     * страницы, которой в карте ещё нет.
     */
    private fun withRelations(job: Job): Pair<JSONObject, Boolean> {
        val props = JSONObject(job.properties!!.toString())
        var whole = true
        fun link(placeholder: String, field: String) {
            val key = props.optString(placeholder)
            if (key.isBlank()) return
            // Дубль ссылается на тот паттерн, который повторяет: точка должна
            // лечь под каноническую формулировку, а не под её двойника.
            val pid = state.dupes[key]?.takeIf { it.isNotBlank() } ?: state.pages[key]
            if (pid == null) {
                whole = false
                return
            }
            props.put(field, JSONObject().put("relation", JSONArray().put(JSONObject().put("id", pid))))
        }
        link("__host", "Носитель")
        link("__pattern", "Паттерн")
        // «__dupe» — уже готовый id страницы, а не ключ в карте.
        props.optString("__dupe").takeIf { it.isNotBlank() }?.let { pid ->
            props.put("Дубль чего", JSONObject().put("relation", JSONArray().put(JSONObject().put("id", pid))))
        }
        props.remove("__host")
        props.remove("__pattern")
        props.remove("__dupe")
        return props to whole
    }

    // ---- Снимки полей: Засечка ----

    private fun ribbonProps(e: ZasechkaStore.Entry, all: List<ZasechkaStore.Entry>, now: Long): JSONObject {
        val host = if (e.parallel) zasechka.hostOf(e, all, now) else null
        val p = JSONObject()
        p.put("Дело", title(e.title.ifBlank { e.category.ifBlank { "без названия" } }))
        p.put("Дата", dateRange(e.start, e.end))
        // Сутки владельца отдельным полем без времени. SQL-слой Notion отдаёт
        // «Дату» в UTC и режет день в 03:00 по Москве: сложенные по нему сутки
        // выходили 1376 и 1506 вместо 1440 - на ровном месте, из-за пояса.
        p.put("День", dateDay(dayKey(e.start)))
        p.put("EntryId", rich("t${e.id}"))
        p.put("Домен", select(if (e.parallel) "таймшит∥" else "таймшит"))
        p.put("Бюджет", JSONObject().put("checkbox", !e.parallel))
        p.put("Источник", select(zasechka.sourceKind(e)))
        if (e.category.isNotBlank()) p.put("Категория", select(e.category))
        p.put("Клиент", rich(e.client))
        p.put("Минуты", number(if (e.parallel) e.durationMin() else zasechka.budgetMinutes(e, now)))
        p.put("Надиктовано", rich(e.raw.substringBefore("\nКБЖУ:").trim()))
        p.put(
            "Детали",
            rich(
                listOfNotNull(
                    e.category.takeIf { it.isNotBlank() },
                    e.client.takeIf { it.isNotBlank() },
                    host?.let { "поверх «${it.title}»" },
                ).joinToString(" · ")
            ),
        )
        p.put("Носитель ID", rich(host?.let { "t${it.id}" }.orEmpty()))
        p.put("Поверх", rich(host?.title.orEmpty()))
        if (host != null) p.put("__host", "t${host.id}")
        if (e.useful > 0) p.put("Полезность", number(e.useful))
        if (e.pomodoros > 0) p.put("Помидоры", number(e.pomodoros))
        // «Garmin: сон 7.2 ч, счёт 82» — приписка на строке сна.
        if (e.category.equals("Сон", ignoreCase = true)) {
            sleepFromNote(e.raw)?.let { (h, score) ->
                p.put("Сон ч", number(h))
                if (score > 0) p.put("Сон счёт", number(score))
            }
        }
        return p
    }

    private fun mealProps(m: FoodStore.Meal): JSONObject = JSONObject().apply {
        put("Дело", title(m.kind.ifBlank { "приём" }))
        put("Дата", dateSingle(m.ts))
        put("День", dateDay(dayKey(m.ts)))
        put("EntryId", rich("f${m.id}"))
        put("Домен", select("еда"))
        put("Категория", select("Еда"))
        put("Бюджет", JSONObject().put("checkbox", false))
        put("Источник", select("manual"))
        put("Детали", rich(m.shortList))
        put("Надиктовано", rich(m.raw))
        put("Ккал", number(m.kcal))
        put("Белок", number(m.protein))
        put("Жиры", number(m.fat))
        put("Углеводы", number(m.carbs))
        if (m.kind.lowercase() in MEAL_KINDS) put("Приём", select(m.kind.lowercase()))
    }

    private fun workoutProps(w: SportStore.Workout): JSONObject = JSONObject().apply {
        val name = ru.zf.pravka.core.SportCoach.sportName(w.type) +
            (if (w.name.isNotBlank() && !w.name.equals(w.type, true)) " · ${w.name}" else "")
        put("Дело", title(name))
        put("Дата", dateSingle(w.start))
        put("День", dateDay(dayKey(w.start)))
        put("EntryId", rich("w${w.id.ifBlank { w.start.toString() }}"))
        put("Домен", select("тренировка"))
        put("Категория", select(sportCategory(w.type)))
        put("Бюджет", JSONObject().put("checkbox", false))
        put("Источник", select("auto"))
        put("Минуты", number(w.minutes))
        if (w.km >= 0.1) put("Км", number(Math.round(w.km * 10.0) / 10.0))
        if (w.avgHr > 0) put("Пульс", number(w.avgHr))
        if (w.avgWatts > 0) put("Ватт", number(w.avgWatts))
        if (w.load > 0) put("Load", number(w.load))
        if (w.feel > 0) put("Самочувствие", number(w.feel))
        put(
            "Детали",
            rich(
                listOfNotNull(
                    if (w.km >= 0.1) String.format(Locale.US, "%.1f км", w.km) else null,
                    if (w.avgHr > 0) "пульс ${w.avgHr}" else null,
                    if (w.avgWatts > 0) "${w.avgWatts} Вт" else null,
                    if (w.load > 0) "load ${w.load}" else null,
                ).joinToString(" · ")
            ),
        )
    }

    private fun sessionProps(s: StrengthStore.Session): JSONObject = JSONObject().apply {
        put("Дело", title(s.title.ifBlank { s.block.ifBlank { "силовая" } }))
        put("Дата", dateDay(s.date))
        put("День", dateDay(s.date))
        put("EntryId", rich("s${s.date}"))
        put("Домен", select("силовая"))
        put("Категория", select("Спорт: силовая"))
        put("Бюджет", JSONObject().put("checkbox", false))
        put("Источник", select("manual"))
        if (s.minutes > 0) put("Минуты", number(s.minutes))
        if (s.feel in 1..5) put("Самочувствие", number(s.feel))
        put("Детали", rich(s.exercises.joinToString("; ") { "${it.name} ${it.compact()}" }))
        put("Надиктовано", rich(s.note))
    }

    private fun gtgProps(g: StrengthStore.GtgDay): JSONObject = JSONObject().apply {
        put("Дело", title(g.status()))
        put("Дата", dateDay(g.date))
        put("День", dateDay(g.date))
        put("EntryId", rich("g${g.date}"))
        put("Домен", select("зарядка"))
        put("Категория", select("Спорт: прочее"))
        put("Бюджет", JSONObject().put("checkbox", false))
        put("Источник", select("manual"))
        put("Детали", rich(g.line(withNote = false).removePrefix("Зарядка: ")))
        put("Надиктовано", rich(g.note))
    }

    private fun commentProps(r: StrengthStore.RawTake): JSONObject = JSONObject().apply {
        put("Дело", title("к тренировке"))
        put("Дата", dateSingle(r.ts))
        put("День", dateDay(dayKey(r.ts)))
        put("EntryId", rich("c${r.ts}"))
        put("Домен", select("комментарий"))
        put("Категория", select("Комментарий"))
        put("Бюджет", JSONObject().put("checkbox", false))
        put("Источник", select("manual"))
        put("Надиктовано", rich(r.text))
    }

    /**
     * Категория ленты для тренировки из часов. Без неё сто с лишним строк
     * (вся еда, весь Garmin, зарядка) оставались без категории, и фильтр
     * «Спорт: *» их не видел — нашёл разбор, а не я.
     */
    private fun sportCategory(type: String): String = when (type) {
        "Run", "TrailRun", "VirtualRun" -> "Спорт: бег"
        "Ride", "VirtualRide", "GravelRide", "MountainBikeRide" -> "Спорт: вело"
        "WeightTraining" -> "Спорт: силовая"
        "Walk", "Hike" -> "Передвижение: пешком"
        else -> "Спорт: прочее"
    }

    // ---- Снимок полей: Дни ----

    /**
     * Сутки одной строкой — то, что аналитик иначе считал бы из ленты сам.
     * Правила счёта — из хаба: полный день ≥ 1370 минут покрытия; «Работа»
     * здесь строго по категории «Работа: *» (клиентские встречи из
     * «Социального» — суждение аналитика, приложение его не подменяет).
     */
    private fun dayProps(date: String, closed: List<ZasechkaStore.Entry>, now: Long): JSONObject? {
        val start = dayStartOf(date)
        val end = start + 86_400_000L
        val main = closed.filter { !it.parallel && it.start >= start && it.start < end }
        val par = closed.filter { it.parallel && it.start >= start && it.start < end }
        val meals = food.mealsFlow.value.filter { it.confirmed && dayKey(it.ts) == date }
        // День до начала ведения ленты: ни минуты покрытия, ни одного приёма
        // еды. Строка из одних нулей — мусор в базе разбора (нашёл разбор:
        // «мусорная строка 19 августа»), цифры часов её не оправдывают.
        if (main.isEmpty() && meals.isEmpty()) return null
        fun mins(pred: (ZasechkaStore.Entry) -> Boolean): Long =
            main.filter(pred).sumOf { zasechka.budgetMinutes(it, now) }
        fun cat(prefix: String) = mins { it.category.startsWith(prefix, ignoreCase = true) }
        fun titled(vararg words: String) = mins { e -> words.any { e.title.contains(it, ignoreCase = true) } }
        fun parApp(vararg words: String): Long =
            par.filter { e -> words.any { e.title.contains(it, ignoreCase = true) } }.sumOf { it.durationMin(now) }

        val coverage = main.sumOf { zasechka.budgetMinutes(it, now) }
        val p = JSONObject()
        p.put("День", title(humanDay(date)))
        p.put("Дата", dateDay(date))
        p.put("День недели", select(weekday(date)))
        p.put("Покрытие мин", number(coverage))
        p.put("Полный день", JSONObject().put("checkbox", coverage >= FULL_DAY_MIN))
        p.put("Работа", number(cat("Работа")))
        p.put("Систематизация", number(cat("Систематизация")))
        p.put("Потери", number(cat("Потери")))
        p.put("Не размечено", number(cat("Не размечено")))
        p.put("Семья", number(cat("Семья")))
        p.put("Быт", number(cat("Быт")))
        p.put("Дорога", number(cat("Передвижение")))
        p.put("Отдых", number(cat("Отдых")))
        p.put("Спорт", number(cat("Спорт")))
        p.put("Еда мин", number(cat("Еда")))
        p.put("Туалет", number(titled("туалет")))
        p.put("Диван", number(titled("диван")))
        p.put("Соло", number(mins { it.category.equals("Секс: соло", ignoreCase = true) }))
        val yt = parApp("youtube", "ютуб")
        val cl = parApp("claude", "клод")
        val tg = parApp("telegram", "телеграм")
        p.put("YouTube", number(yt))
        p.put("Claude", number(cl))
        p.put("Telegram", number(tg))
        p.put("Экран параллель", number(yt + cl + tg))
        // Внешних рабочих контактов: рабочие звонки плюс любая работа с
        // названным клиентом.
        p.put(
            "Контактов",
            number(
                main.count {
                    it.category.equals("Работа: звонки", ignoreCase = true) ||
                        (it.category.startsWith("Работа", ignoreCase = true) && it.client.isNotBlank())
                }
            ),
        )
        // Ночной сон — тот, что КОНЧИЛСЯ в этот день; цепочка тянется назад
        // через полночь, потому что лента режет каждую ночь на два куска.
        val wake = closed
            .filter { !it.parallel && it.category.equals("Сон", ignoreCase = true) && it.end in start until end && it.end - start < 14 * 3_600_000L }
            .maxByOrNull { it.end }
        if (wake != null) {
            var head: ZasechkaStore.Entry = wake
            var total = 0L
            var guard = 0
            while (guard++ < 6) {
                total += head.durationMin(now)
                val prev = closed.firstOrNull {
                    !it.parallel && it.category.equals("Сон", ignoreCase = true) &&
                        kotlin.math.abs(it.end - head.start) < 60_000L
                } ?: break
                head = prev
            }
            p.put("Сон мин", number(total))
            p.put("Отбой", rich(hm.format(Date(head.start))))
            p.put("Подъём", rich(hm.format(Date(wake.end))))
            // Первое целевое действие после подъёма: не сон, не туалет, не
            // еда, не телефон и не пустота.
            val first = main
                .filter { it.start >= wake.end }
                .sortedBy { it.start }
                .firstOrNull { e ->
                    val c = e.category.lowercase()
                    !(c == "сон" || c == "не размечено" || c == "потери" || c == "еда") &&
                        !e.title.contains("туалет", ignoreCase = true) &&
                        !e.title.contains("телефон", ignoreCase = true)
                }
            if (first != null) p.put("Первое действие через", number((first.start - wake.end) / 60_000L))
            sleepFromNote(wake.raw)?.let { (h, score) ->
                p.put("Сон Garmin ч", number(h))
                if (score > 0) p.put("Сон счёт", number(score))
            }
        }
        sport.healthFlow.value.firstOrNull { it.date == date }?.let { h ->
            if (h.sleepHours > 0) p.put("Сон Garmin ч", number(Math.round(h.sleepHours * 10.0) / 10.0))
            if (h.sleepScore > 0) p.put("Сон счёт", number(h.sleepScore))
        }
        if (meals.isNotEmpty()) {
            p.put("Ккал", number(meals.sumOf { it.kcal }))
            p.put("Белок", number(meals.sumOf { it.protein }))
            p.put("Приёмов", number(meals.size))
        }
        return p
    }

    // ---- Снимок полей: Паттерны и Подтверждения ----

    /**
     * Паттерн приложения в библиотеке аналитика. Статус — его поле, не наше:
     * при создании ставим «Кандидат», дальше не трогаем. Наше — «Заявлено
     * приложением»: сколько точек нашёл ночной поиск.
     */
    private fun patternProps(pt: AnalysisStore.Pattern, isNew: Boolean): JSONObject = JSONObject().apply {
        put("Заявлено приложением", number(pt.points))
        if (isNew) {
            put("Паттерн", title(pt.text))
            put("Источник", select("Приложение"))
            put("Тип", select("Паттерн"))
            put("Статус", select("Кандидат"))
            if (pt.firstSeen.isNotBlank()) put("Первое появление", dateDay(pt.firstSeen))
            confidenceOption(pt.confidence)?.let { put("Уверенность", select(it)) }
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
            put("Подтверждение", title("$who: $what · ${humanDay(date)}"))
            put("Дата", dateDay(date))
            put("Кто", select(who))
            put("Вердикт", select(what))
            put(
                "Улика",
                rich(
                    if (verdict) "Вердикт в приложении по паттерну «${pt.text}»."
                    else "Ночной поиск увидел снова: ${pt.points} точек, уверенность ${pt.confidence.ifBlank { "не указана" }}, всего раз ${pt.times}."
                ),
            )
            put("Ключ", rich(key))
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

    // ---- Кирпичи Notion ----

    private fun title(text: String) = JSONObject().put("title", textArray(text))
    private fun rich(text: String) = JSONObject().put("rich_text", textArray(text))
    private fun select(name: String) = JSONObject().put("select", JSONObject().put("name", name.take(100)))
    private fun number(v: Number) = JSONObject().put("number", v)
    private fun dateRange(start: Long, end: Long) = JSONObject().put(
        "date", JSONObject().put("start", dateTime.format(Date(start))).put("end", dateTime.format(Date(end))),
    )
    private fun dateSingle(ms: Long) = JSONObject().put("date", JSONObject().put("start", dateTime.format(Date(ms))))
    private fun dateDay(date: String) = JSONObject().put("date", JSONObject().put("start", date))

    private fun textArray(text: String): JSONArray =
        if (text.isBlank()) JSONArray()
        else JSONArray().put(JSONObject().put("text", JSONObject().put("content", text.take(1900))))

    /** «Garmin: сон 7.2 ч, счёт 82» → 7.2 и 82. */
    private fun sleepFromNote(raw: String): Pair<Double, Int>? {
        val m = Regex("""сон\s+([\d.,]+)\s*ч""", RegexOption.IGNORE_CASE).find(raw) ?: return null
        val hours = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val score = Regex("""счёт\s+(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours to score
    }

    private fun dayStartOf(date: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.let { dayStartMs(it.time) }
    }.getOrNull() ?: dayStartMs(System.currentTimeMillis())

    private fun weekday(date: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartOf(date) }
        return WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun humanDay(date: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartOf(date) }
        val dd = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
        return "$dd.$mm · ${weekday(date)}"
    }

    private fun timeNow(): String = hm.format(Date(System.currentTimeMillis()))

    private suspend fun sleep() = delay(PAUSE_MS)

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
            o.optJSONObject("dupes")?.let { d -> d.keys().forEach { k -> state.dupes[k] = d.optString(k) } }
            state.dupeBatch = o.optString("dupeBatch")
            state.dupeAt = o.optLong("dupeAt")
            o.optJSONArray("dupeKeys")?.let { a -> for (i in 0 until a.length()) state.dupeKeys.add(a.optString(i)) }
            o.optJSONArray("dupeIds")?.let { a -> for (i in 0 until a.length()) state.dupeIds.add(a.optString(i)) }
            state.lastScan = o.optLong("lastScan")
        }
    }

    private fun saveState() {
        val o = JSONObject()
            .put("pages", JSONObject(state.pages as Map<*, *>))
            .put("hashes", JSONObject(state.hashes as Map<*, *>))
            .put("dbs", JSONObject(state.dbs as Map<*, *>))
            .put("mapped", JSONArray(state.mapped.toList()))
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
        state.dupeBatch = ""; state.dupeAt = 0L; state.dupeKeys.clear(); state.dupeIds.clear()
        state.lastScan = 0L
        queue.clear()
        blockedConfig = null
        saveState()
    }
}
