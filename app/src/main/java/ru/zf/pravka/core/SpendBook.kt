package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Траты Claude телефона по дням — для одной книги трат на сервере Дел (14.10.2026, контракт
 * `server/contract/dela-spend.json`, аудит — `docs/claude-spend.md`).
 *
 * Владелец: «значок денег Claude (все запросы в Claude) на главный экран… проаудировать, что мы всё учитываем, и
 * систематизировать лучше по задачам». Stats знает $ по дороге и дню, но не знает модели, пачки и выхода, а пишут в
 * него сами режимы — и не все (Засечка на старте дела, оплаченный, но не разобранный ответ). Эта книга пишется в ОДНОЙ
 * точке — транспорте Claude (каждый ответ) и опросе пачек: строка «день · дорога · модель» с вызовами, токенами и $.
 * Телефон отдаёт её серверу операцией `spend.put` итогами дней (повтор безвреден), а сервер складывает с тратами
 * служб компа и показывает экран «Claude: траты» и красную точку недельных советов.
 *
 * Здесь — только правила (под JVM-тестом `SpendBookTest`): свёртка, срок, что шлём, значок. Файл и сеть —
 * `data/ClaudeSpend.kt`.
 */
object SpendBook {
    const val FEATURE = "spend"
    const val SERVICE = "phone"
    const val KEY = "phone"
    /** Сколько дней книга живёт на телефоне — как Stats (62): дальше сервер уже всё знает. */
    const val KEEP_DAYS = 62
    /** Вызов без дороги (старый путь без тега) — в «прочее». */
    const val OTHER = "other"
    /** Пачка (Message Batches) — половина цены. */
    const val BATCH_DISCOUNT = 0.5
    /** Разница меньше десятой цента между Stats и книгой — округление, а не потерянный вызов. */
    const val LEGACY_EPS = 0.001
    /** Строк в одной операции — предел сервера (spend.ROWS_MAX). */
    const val ROWS_MAX = 3000

    data class Row(
        val day: String,
        val route: String,
        val model: String,
        val calls: Int = 0,
        val batchCalls: Int = 0,
        val input: Long = 0,
        val output: Long = 0,
        /** Запись кэша — у телефона все точки кэша часовые (ClaudeProvider, ClaudeBatches: ttl 1h). */
        val cacheWrite1h: Long = 0,
        val cacheRead: Long = 0,
        val usdMicros: Long = 0,
        /** Те же токены без скидки пачки. */
        val usdListMicros: Long = 0,
    ) {
        val key: String get() = "$day|$route|$model"
        val usd: Double get() = usdMicros / 1_000_000.0
    }

    /** Имя дороги для сервера: строчные, только «a-z 0-9 _ . -» — пусто или чужое — «прочее». */
    fun task(route: String): String {
        val r = route.trim().lowercase(Locale.ROOT)
        val ok = r.isNotEmpty() && r.length <= 64 && r[0].isLetterOrDigit() &&
            r.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }
        return if (ok) r else OTHER
    }

    /** Прибавить один ответ (или пачку ответов одной модели) к строке дня. usd — уже со скидкой пачки. */
    fun add(
        book: Map<String, Row>,
        day: String,
        route: String,
        model: String,
        input: Long,
        output: Long,
        cacheWrite: Long,
        cacheRead: Long,
        usd: Double,
        batch: Boolean,
        calls: Int = 1,
    ): Map<String, Row> {
        val k = Row(day, task(route), model.trim().take(64))
        val old = book[k.key] ?: k
        val micros = Math.round(usd * 1_000_000)
        val list = if (batch) Math.round(usd / BATCH_DISCOUNT * 1_000_000) else micros
        val row = old.copy(
            calls = old.calls + calls,
            batchCalls = old.batchCalls + if (batch) calls else 0,
            input = old.input + input.coerceAtLeast(0),
            output = old.output + output.coerceAtLeast(0),
            cacheWrite1h = old.cacheWrite1h + cacheWrite.coerceAtLeast(0),
            cacheRead = old.cacheRead + cacheRead.coerceAtLeast(0),
            usdMicros = old.usdMicros + micros.coerceAtLeast(0),
            usdListMicros = old.usdListMicros + list.coerceAtLeast(0),
        )
        return book + (row.key to row)
    }

    /** Только последние [KEEP_DAYS] дней: oldest — первый день, который ещё держим (ISO). */
    fun prune(book: Map<String, Row>, oldest: String): Map<String, Row> = book.filterValues { it.day >= oldest }

    fun toJson(book: Map<String, Row>): JSONArray = JSONArray().apply {
        for (r in book.values.sortedBy { it.key }) {
            put(
                JSONObject().put("d", r.day).put("r", r.route).put("m", r.model).put("c", r.calls).put("b", r.batchCalls)
                    .put("i", r.input).put("o", r.output).put("w", r.cacheWrite1h).put("cr", r.cacheRead)
                    .put("u", r.usdMicros).put("l", r.usdListMicros),
            )
        }
    }

    fun fromJson(a: JSONArray?): Map<String, Row> {
        val out = LinkedHashMap<String, Row>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val r = Row(
                o.optString("d"), o.optString("r"), o.optString("m"), o.optInt("c"), o.optInt("b"), o.optLong("i"),
                o.optLong("o"), o.optLong("w"), o.optLong("cr"), o.optLong("u"), o.optLong("l"),
            )
            if (r.day.length == 10) out[r.key] = r
        }
        return out
    }

    /** Stats по дороге и дню — то, что было до книги (и запас, если транспорт чего-то не видел). */
    data class Legacy(val day: String, val route: String, val usd: Double)

    /**
     * Строки дня для сервера. Точные — из книги; сверх них — разница со Stats по той же дороге и дню (неточной строкой
     * без модели): так дни до этой сборки и то, что пишет только режим, не пропадают, а когда книга уже знает больше
     * Stats, разницы нет. Сумма дня в Stats без дороги (старые сборки) — «прочее», только если книга в тот день молчит.
     */
    fun wire(book: Collection<Row>, legacy: List<Legacy>, legacyTotals: Map<String, Double>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (r in book.sortedBy { it.key }) out += rowJson(r)
        val bookRoute = book.groupBy { it.day to it.route }.mapValues { (_, v) -> v.sumOf { it.usd } }
        val bookDays = book.map { it.day }.toSet()
        val legacyRoute = legacy.groupBy { it.day to task(it.route) }.mapValues { (_, v) -> v.sumOf { it.usd } }
        for ((k, usd) in legacyRoute.toSortedMap(compareBy({ it.first }, { it.second }))) {
            val diff = usd - (bookRoute[k] ?: 0.0)
            if (diff >= LEGACY_EPS) out += inexact(k.first, k.second, diff)
        }
        for ((day, total) in legacyTotals.toSortedMap()) {
            if (day in bookDays) continue
            val routes = legacy.filter { it.day == day }.sumOf { it.usd }
            val diff = total - routes
            if (diff >= LEGACY_EPS) out += inexact(day, OTHER, diff, dayRest = true)
        }
        return out
    }

    private fun rowJson(r: Row): JSONObject = JSONObject()
        .put("day", r.day).put("service", SERVICE).put("task", r.route).put("model", r.model).put("key", KEY)
        .put("calls", r.calls).put("batch_calls", r.batchCalls).put("input", r.input).put("output", r.output)
        .put("cache_write", 0).put("cache_write_1h", r.cacheWrite1h).put("cache_read", r.cacheRead)
        .put("usd", r.usdMicros / 1_000_000.0).put("usd_list", r.usdListMicros / 1_000_000.0).put("exact", true)

    /**
     * Неточная строка: только $ (Stats). Модель — пусто; у остатка дня без дороги — метка «legacy», чтобы он не
     * столкнулся с разницей по дороге «прочее» того же дня (ключ строки на сервере — дорога и модель).
     */
    private fun inexact(day: String, route: String, usd: Double, dayRest: Boolean = false): JSONObject {
        val v = Math.round(usd * 1_000_000) / 1_000_000.0
        return JSONObject().put("day", day).put("service", SERVICE).put("task", route)
            .put("model", if (dayRest) "legacy" else "").put("key", KEY).put("calls", 0).put("usd", v).put("usd_list", v)
            .put("exact", false)
    }

    /** Ключ строки на сервере (без дня): «дорога|модель». */
    fun keyOf(o: JSONObject): String = o.optString("task") + "|" + o.optString("model")

    /**
     * Что слать за день: строки, а для ключей, которые уходили прошлый раз и пропали, — нули (сервер заменяет строку,
     * а не удаляет: без нуля пропавшая разница со Stats осталась бы там навсегда).
     */
    fun dayRows(day: String, rows: List<JSONObject>, sentKeys: Set<String>): List<JSONObject> {
        val now = rows.map(::keyOf).toSet()
        val zeros = (sentKeys - now).sorted().map { k ->
            val (task, model) = k.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            JSONObject().put("day", day).put("service", SERVICE).put("task", task).put("model", model).put("key", KEY)
                .put("calls", 0).put("usd", 0).put("usd_list", 0).put("exact", false)
        }
        return rows + zeros
    }

    /** Отпечаток дня: поменялся — день уходит заново. */
    fun hash(rows: List<JSONObject>): String =
        rows.map { it.toString() }.sorted().joinToString("\n").hashCode().toUInt().toString(36)

    /** Операция `spend.put` (без op_id: итог дня заменяет строку, повтор безвреден). */
    fun putOp(src: String, rows: List<JSONObject>): JSONObject =
        JSONObject().put("op", "spend.put").put("src", src.trim().take(64).ifBlank { "телефон" }).put("rows", JSONArray(rows))

    // ------------------------------------------------------------ значок

    /** Поле `spend` синка: $ за 7 дней, сегодня, блоки «Телефон» и «Сервер», последние советы. */
    data class Badge(
        val week: Double,
        val today: Double,
        val weekPhone: Double,
        val weekServer: Double,
        val tipsId: String?,
        val tipsCount: Int,
        val unseen: Boolean,
    )

    fun badge(o: JSONObject?): Badge? {
        if (o == null) return null
        val t = o.optJSONObject("tips")
        return Badge(
            week = o.optDouble("week", 0.0), today = o.optDouble("today", 0.0),
            weekPhone = o.optDouble("week_phone", 0.0), weekServer = o.optDouble("week_server", 0.0),
            tipsId = t?.optString("id")?.ifBlank { null }, tipsCount = t?.optInt("count") ?: 0,
            unseen = t?.optBoolean("unseen") == true,
        )
    }

    fun badgeJson(b: Badge): JSONObject = JSONObject().put("week", b.week).put("today", b.today)
        .put("week_phone", b.weekPhone).put("week_server", b.weekServer)
        .put("tips", b.tipsId?.let { JSONObject().put("id", it).put("count", b.tipsCount).put("unseen", b.unseen) } ?: JSONObject.NULL)

    /**
     * Красная точка над значком: есть непрочитанные советы недели. Открыл их на телефоне — точка гаснет сразу
     * ([seenLocally] — id открытых), не дожидаясь, пока сервер ответит на `spend.seen`.
     */
    fun dot(b: Badge?, seenLocally: String?): Boolean = b != null && b.unseen && b.tipsId != null && b.tipsId != seenLocally

    /** $ по-русски: «$4,20», от $100 — без центов, меньше цента — «$0,004». */
    fun money(usd: Double): String = when {
        usd >= 100 -> "$" + String.format(Locale.ROOT, "%.0f", usd)
        usd >= 0.01 || usd <= 0.0 -> "$" + String.format(Locale.ROOT, "%.2f", usd.coerceAtLeast(0.0)).replace('.', ',')
        else -> "$" + String.format(Locale.ROOT, "%.3f", usd).replace('.', ',')
    }

    /** Подсказка значка: «Claude: $21,04 за 7 дней (телефон $15,20, сервер $5,84)». */
    fun hint(b: Badge?): String = if (b == null) "траты Claude" else
        "траты Claude: ${money(b.week)} за 7 дней (телефон ${money(b.weekPhone)}, сервер ${money(b.weekServer)})" +
            if (b.unseen) ", есть советы" else ""

    // ------------------------------------------------------------ экран

    /** Строка экрана: служба, задача или модель — $ и вызовы. */
    data class Line(val title: String, val sub: String, val usd: Double, val calls: Int)

    /** Блок «Телефон» или «Сервер» (владелец: «сбор в одном месте, плюс отдельно серверные траты»). */
    data class Block(val key: String, val title: String, val usd: Double, val services: List<Line>, val tasks: List<Line>)

    data class Period(
        val usd: Double,
        val calls: Int,
        val batchCalls: Int,
        val cacheRead: Long,
        val cacheWrite: Long,
        val cacheSaved: Double,
        val batchSaved: Double,
        val inexactUsd: Double,
        val blocks: List<Block>,
        val models: List<Line>,
    )

    data class Tip(val where: String, val title: String, val why: String, val how: String, val saveMonth: Double, val risk: String)

    data class Screen(
        val today: String,
        val periods: Map<String, Period>,
        val prevWeek: Double,
        val trend: List<Pair<String, Double>>,
        val sources: List<Line>,
        val gaps: List<String>,
        val tipsId: String?,
        val tipsWeek: String,
        val tipsNote: String,
        val tips: List<Tip>,
        val tipsSeen: Boolean,
        /**
         * Что на сервере Дел стоит защитой от петли (аудит №12, поле `guard.stopped` вида `spend`): задача, почему, с
         * какого времени. Пусто — ничего не стоит (или сервер старый, поля нет).
         */
        val stopped: List<GuardStop> = emptyList(),
    )

    /** Задача Дел, остановленная защитой от петли: «Продолжать» — операция `claude.guard` ([guardOp]). */
    data class GuardStop(val task: String, val title: String, val why: String, val at: String)

    /**
     * «Продолжать» (аудит №12): снять остановку и час пороги втрое — владелец; сервер проверит сам. Только при поле
     * `guard` вида: старый сервер операции не знает.
     */
    fun guardOp(task: String, opId: String = java.util.UUID.randomUUID().toString()): JSONObject =
        JSONObject().put("op", "claude.guard").put("op_id", opId).put("action", "resume").put("task", task)

    /** «Сервер: остановлено защитой — Правка словами: 31 вызов за 10 минут (порог 30) · с 14:05». */
    fun guardLine(g: GuardStop): String =
        "Сервер: остановлено защитой — " + g.title.ifBlank { g.task } + (if (g.why.isNotBlank()) ": " + g.why else "") +
            (g.at.takeIf { it.length >= 16 }?.let { " · с " + it.substring(11, 16) } ?: "")

    /** Периоды экрана: ключ вида сервера → подпись. */
    val PERIODS = listOf("today" to "Сегодня", "week" to "7 дней", "month" to "30 дней")

    private fun lines(a: JSONArray?, title: (JSONObject) -> String, sub: (JSONObject) -> String): List<Line> =
        (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.map {
            Line(title(it), sub(it), it.optDouble("usd", 0.0), it.optInt("calls"))
        }

    private fun period(o: JSONObject?): Period {
        val p = o ?: JSONObject()
        val blocks = (0 until (p.optJSONArray("blocks")?.length() ?: 0)).mapNotNull { p.optJSONArray("blocks")!!.optJSONObject(it) }.map { b ->
            val phone = b.optString("block") == "phone"
            Block(
                key = b.optString("block"), title = b.optString("title"), usd = b.optDouble("usd", 0.0),
                services = lines(b.optJSONArray("services"), { it.optString("title") }, { modelsOf(it) }),
                // У телефона одна служба — задача без её имени; у сервера — «Встречи · Чистка расшифровки».
                tasks = lines(b.optJSONArray("tasks"), { if (phone) it.optString("title") else it.optString("service_title") + " · " + it.optString("title") }, { modelsOf(it) }),
            )
        }
        return Period(
            usd = p.optDouble("usd", 0.0), calls = p.optInt("calls"), batchCalls = p.optInt("batch_calls"),
            cacheRead = p.optLong("cache_read"), cacheWrite = p.optLong("cache_write"),
            cacheSaved = p.optDouble("cache_saved", 0.0), batchSaved = p.optDouble("batch_saved", 0.0),
            inexactUsd = p.optDouble("inexact_usd", 0.0), blocks = blocks,
            models = lines(p.optJSONArray("models"), { modelTitle(it.optString("model")) }, { "" }),
        )
    }

    private fun modelsOf(o: JSONObject): String {
        val a = o.optJSONArray("models") ?: return ""
        return (0 until a.length()).map { modelTitle(a.optString(it)) }.joinToString(", ")
    }

    /** «claude-opus-5-5» → «Opus 5.5»; пусто — «модель не записана». */
    fun modelTitle(m: String): String {
        if (m.isBlank() || m == "legacy") return "модель не записана"
        val parts = m.removePrefix("claude-").split('-')
        val name = parts.firstOrNull().orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
        val ver = parts.drop(1).takeWhile { it.all(Char::isDigit) && it.length <= 2 }.joinToString(".")
        return if (ver.isBlank()) name else "$name $ver"
    }

    /** Вид `spend` сервера → то, что рисует экран. Чего нет — пусто, экран не падает. */
    fun screen(o: JSONObject): Screen {
        val ps = o.optJSONObject("periods") ?: JSONObject()
        val trend = o.optJSONArray("trend")
        val t = o.optJSONObject("tips")
        val ta = t?.optJSONArray("tips")
        val gaps = o.optJSONArray("gaps")
        return Screen(
            today = o.optString("today"),
            periods = PERIODS.associate { (k, _) -> k to period(ps.optJSONObject(k)) },
            prevWeek = o.optDouble("prev_week", 0.0),
            trend = (0 until (trend?.length() ?: 0)).mapNotNull { trend!!.optJSONObject(it) }.map { it.optString("day") to it.optDouble("usd", 0.0) },
            sources = lines(o.optJSONArray("sources"), { it.optString("src") }, { s ->
                val titles = s.optJSONArray("titles")
                (0 until (titles?.length() ?: 0)).joinToString(", ") { titles!!.optString(it) } + " · по " + s.optString("day").takeLast(5).let { d -> d.takeLast(2) + "." + d.take(2) }
            }),
            gaps = (0 until (gaps?.length() ?: 0)).map { gaps!!.optString(it) },
            tipsId = t?.optString("id")?.ifBlank { null },
            tipsWeek = t?.optString("week").orEmpty(),
            tipsNote = t?.optString("note").orEmpty(),
            tips = (0 until (ta?.length() ?: 0)).mapNotNull { ta!!.optJSONObject(it) }.map {
                Tip(it.optString("where"), it.optString("title"), it.optString("why"), it.optString("how"),
                    it.optDouble("save_usd_month", 0.0), it.optString("risk"))
            },
            tipsSeen = t != null && !t.isNull("seen_at") && t.optString("seen_at").isNotBlank(),
            stopped = o.optJSONObject("guard")?.optJSONArray("stopped").let { a ->
                (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.mapNotNull { g ->
                    val task = g.optString("task").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    GuardStop(task, g.optString("title").takeIf { it != "null" }.orEmpty(), g.optString("why").takeIf { it != "null" }.orEmpty(),
                        g.optString("at").takeIf { it != "null" }.orEmpty())
                }
            },
        )
    }

    /** Свои траты за последние [days] дней по книге телефона (экран без сети): $ и строки по дороге, дороже — выше. */
    fun local(book: Collection<Row>, fromDay: String): Pair<Double, List<Pair<String, Double>>> {
        val rows = book.filter { it.day >= fromDay }
        val byRoute = rows.groupBy { it.route }.mapValues { (_, v) -> v.sumOf { it.usd } }.toList().sortedByDescending { it.second }
        return rows.sumOf { it.usd } to byRoute
    }
}
