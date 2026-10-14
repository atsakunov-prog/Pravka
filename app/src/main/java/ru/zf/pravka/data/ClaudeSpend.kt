package ru.zf.pravka.data

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.SpendBook
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.Pricing

/**
 * Куда падает каждый ответ Claude телефона (14.10.2026). Не Stats: туда пишут сами режимы и не все, а модели, пачки и
 * выхода по дню там нет. Точек три, и ни одна не в службе доступности (её трогать нельзя):
 * - транспорт [ru.zf.pravka.provider.ClaudeProvider] — каждый ответ потока (`usageObserver` в PravkaApp);
 * - опрос пачек (ночной разбор, правка промпта, судья) — [batch];
 * - DictMiner — свой запрос мимо транспорта — [call].
 * Пока книга не встала (холодный старт), хук молчит: трата всё равно уже в Stats и дойдёт до сервера разницей.
 */
internal object SpendHook {
    @Volatile
    var sink: ClaudeSpend? = null

    fun call(route: String, model: String, input: Int, output: Int, cacheWrite: Int, cacheRead: Int) {
        sink?.record(route, model, input, output, cacheWrite, cacheRead)
    }

    fun batch(route: String, items: List<ClaudeBatches.Item>, sentModel: String) {
        sink?.recordBatch(route, items, sentModel)
    }
}

/**
 * Книга трат Claude телефона и её дорога на сервер Дел (`core/SpendBook.kt` — правила, контракт
 * `server/contract/dela-spend.json`). Файл `claude-spend.json` в `DataRoot.dir`: строки «день · дорога · модель»
 * (62 дня), что и с каким отпечатком уже ушло, последний значок с сервера и какие советы открыты здесь.
 *
 * Сервер: раз в полчаса (и после синка Дел, если давно не слал) — дни, у которых поменялся отпечаток, одной
 * операцией `spend.put` мимо очереди (итог дня, повтор безвреден). Значок — поле `spend` синка Дел.
 */
internal class ClaudeSpend(private val app: PravkaApp, private val scope: CoroutineScope, private val log: (String) -> Unit) {

    private val file: File get() = File(DataRoot.dir(app), FILE)
    private val mutex = Mutex()
    private var loaded = false
    private var book: Map<String, SpendBook.Row> = emptyMap()
    /** День → (отпечаток, ключи строк), ушедшие на сервер. */
    private val sent = HashMap<String, Pair<String, Set<String>>>()
    private var pushedAt = 0L
    private var oldServerAt = 0L

    private val _badge = MutableStateFlow<SpendBook.Badge?>(null)
    /** Значок на «Сегодня»: $ за 7 дней, блоки, есть ли непрочитанные советы — последнее слово сервера. */
    val badge: StateFlow<SpendBook.Badge?> = _badge

    private val _seen = MutableStateFlow("")
    /** id советов, которые владелец открыл на этом телефоне: точка гаснет сразу, не дожидаясь сервера. */
    val seenTips: StateFlow<String> = _seen

    private var started = false

    /** Снимки экранов (Robolectric, `app/src/shots`): вид сервера без сервера. В приложении всегда null. */
    @Volatile
    internal var shotView: JSONObject? = null

    /** Снимкам — советы снова «не открыты»: точка над значком видна в каждом круге. */
    internal fun shotUnseen() {
        _seen.value = ""
    }

    fun start() {
        if (started) return
        started = true
        SpendHook.sink = this
        scope.launch(Dispatchers.IO) {
            runCatching { mutex.withLock { ensureLoaded() } }
            delay(START_DELAY_MS)
            while (true) {
                runCatching { push("тик") }.onFailure { e -> log("траты Claude: ${e.javaClass.simpleName}: ${e.message}") }
                delay(TICK_MS)
            }
        }
    }

    // ------------------------------------------------------------ запись

    fun record(route: String, model: String, input: Int, output: Int, cacheWrite: Int, cacheRead: Int) {
        val usd = Pricing.costUsd(model, input, output, cacheWrite, cacheRead)
        add(route, model, input.toLong(), output.toLong(), cacheWrite.toLong(), cacheRead.toLong(), usd, batch = false, calls = 1)
    }

    /** Ответы пачки — по модели каждого (незнакомая прайсу — та, которой пачка уходила), со скидкой. */
    fun recordBatch(route: String, items: List<ClaudeBatches.Item>, sentModel: String) {
        for ((model, group) in items.groupBy { it.model.takeIf(Pricing::knows) ?: sentModel }) {
            val usd = group.sumOf { Pricing.costUsd(model, it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead) } *
                SpendBook.BATCH_DISCOUNT
            add(
                route, model, group.sumOf { it.inputTokens.toLong() }, group.sumOf { it.outputTokens.toLong() },
                group.sumOf { it.cacheWrite.toLong() }, group.sumOf { it.cacheRead.toLong() }, usd, batch = true, calls = group.size,
            )
        }
    }

    private fun add(route: String, model: String, input: Long, output: Long, cw: Long, cr: Long, usd: Double, batch: Boolean, calls: Int) {
        val day = LocalDate.now().toString()
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                book = SpendBook.add(book, day, route, model, input, output, cw, cr, usd, batch, calls)
                persist()
            }
        }
    }

    // ------------------------------------------------------------ сервер

    /** Синк Дел принёс ответ: значок — сразу; давно не слали — и свои дни. */
    suspend fun onSync(resp: JSONObject) {
        val b = SpendBook.badge(resp.optJSONObject("spend"))
        if (b != null) {
            mutex.withLock {
                ensureLoaded()
                if (b != _badge.value) {
                    _badge.value = b
                    persist()
                }
            }
        }
        if (System.currentTimeMillis() - pushedAt > PUSH_EVERY_MS) runCatching { push("синк") }
    }

    private fun serverReady(): Boolean {
        if (app.delaSync.link.value == null) return false
        if (System.currentTimeMillis() - oldServerAt < OLD_RETRY_MS) return false
        val features = app.delaStore.view.value.features
        return features.isEmpty() || SpendBook.FEATURE in features
    }

    /** Дни, у которых поменялся отпечаток, — на сервер. true — ушло всё (или нечего слать). */
    suspend fun push(why: String): Boolean = withContext(Dispatchers.IO) {
        if (!serverReady()) return@withContext false
        val (legacy, totals) = runCatching { app.stats.routeDays(SpendBook.KEEP_DAYS) }.getOrDefault(emptyList<SpendBook.Legacy>() to emptyMap())
        // Отпечаток и ключи — по строкам дня без нулей; нули для пропавших ключей только едут вместе с ними.
        val hashes = HashMap<String, Pair<String, Set<String>>>()
        val byDay = mutex.withLock {
            ensureLoaded()
            val rows = SpendBook.wire(book.values, legacy, totals).groupBy { it.optString("day") }
            val out = LinkedHashMap<String, List<JSONObject>>()
            for ((day, r) in rows.toSortedMap()) {
                val prev = sent[day]
                val h = SpendBook.hash(r)
                hashes[day] = h to r.map(SpendBook::keyOf).toSet()
                if (prev?.first != h) out[day] = SpendBook.dayRows(day, r, prev?.second.orEmpty())
            }
            out
        }
        pushedAt = System.currentTimeMillis()
        if (byDay.isEmpty()) return@withContext true
        val days = byDay.keys.toList()
        // Дни целиком в одной операции — до предела строк сервера.
        val chunks = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var n = 0
        for (d in days) {
            val size = byDay.getValue(d).size
            if (cur.isNotEmpty() && n + size > SpendBook.ROWS_MAX) { chunks += cur; cur = ArrayList(); n = 0 }
            cur += d; n += size
        }
        if (cur.isNotEmpty()) chunks += cur
        val src = src()
        for (chunk in chunks) {
            val op = SpendBook.putOp(src, chunk.flatMap { byDay.getValue(it) })
            val res = try {
                app.delaSync.rawOps(JSONArray().put(op)).optJSONObject(0) ?: JSONObject()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("траты Claude: не ушли на сервер ($why) — ${e.message ?: e.javaClass.simpleName}")
                return@withContext false
            }
            if (!res.optBoolean("ok")) {
                val err = res.optString("error")
                if (err.contains("нет такой операции")) {
                    if (oldServerAt == 0L) log("траты Claude: сервер Дел ещё без книги трат — обнови его на компе (install-dela.ps1)")
                    oldServerAt = System.currentTimeMillis()
                } else {
                    log("траты Claude: сервер не принял ($why) — $err")
                }
                return@withContext false
            }
            mutex.withLock {
                for (d in chunk) sent[d] = hashes.getValue(d)
                persist()
            }
        }
        log("траты Claude: на сервер ${days.size} дн. ($why)")
        true
    }

    /** Экран «Claude: траты»: вид сервера целиком. null — сервера нет или он старый. */
    suspend fun view(): JSONObject? {
        shotView?.let { return it }
        if (app.delaSync.link.value == null) return null
        val features = app.delaStore.view.value.features
        if (features.isNotEmpty() && SpendBook.FEATURE !in features) return null
        return app.delaSync.rawView("spend").takeIf { it.optBoolean("ok") }
    }

    /** Владелец открыл советы: точка гаснет здесь сразу, на сервере — операцией (не вышло — повторим при следующем открытии). */
    suspend fun markSeen(id: String) {
        if (id.isBlank()) return
        mutex.withLock {
            ensureLoaded()
            if (_seen.value != id) {
                _seen.value = id
                persist()
            }
        }
        runCatching {
            app.delaSync.rawOps(JSONArray().put(JSONObject().put("op", "spend.seen").put("id", id)))
        }.onFailure { e -> log("траты Claude: «советы прочитаны» не ушло — ${e.message ?: e.javaClass.simpleName}") }
    }

    /** Свои траты за 7 дней по книге телефона — экрану без сервера. */
    suspend fun localWeek(): Pair<Double, List<Pair<String, Double>>> = mutex.withLock {
        ensureLoaded()
        SpendBook.local(book.values, LocalDate.now().minusDays(6).toString())
    }

    // ------------------------------------------------------------ файл

    private fun src(): String {
        val name = android.os.Build.MODEL.orEmpty().ifBlank { "телефон" }
        return "$name · ${app.moneyCloudSync.device.take(6)}".take(64)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        book = SpendBook.fromJson(o.optJSONArray("rows"))
        o.optJSONObject("sent")?.let { s ->
            for (d in s.keys()) {
                val e = s.optJSONObject(d) ?: continue
                val ks = e.optJSONArray("k")
                sent[d] = e.optString("h") to (0 until (ks?.length() ?: 0)).map { ks!!.optString(it) }.toSet()
            }
        }
        _badge.value = SpendBook.badge(o.optJSONObject("badge"))
        _seen.value = o.optString("seen")
    }

    private fun persist() {
        val oldest = LocalDate.now().minusDays((SpendBook.KEEP_DAYS - 1).toLong()).toString()
        book = SpendBook.prune(book, oldest)
        sent.keys.removeAll { it < oldest }
        val o = JSONObject().put("v", 1).put("rows", SpendBook.toJson(book))
            .put("sent", JSONObject().apply {
                for ((d, e) in sent) put(d, JSONObject().put("h", e.first).put("k", JSONArray(e.second.sorted())))
            })
            .put("seen", _seen.value)
        _badge.value?.let { o.put("badge", SpendBook.badgeJson(it)) }
        val text = o.toString()
        val target = file
        DiskWriter.post { StoreFiles.writeAtomic(target, text) }
    }

    companion object {
        const val FILE = "claude-spend.json"
        private const val START_DELAY_MS = 90_000L
        private const val TICK_MS = 30 * 60_000L
        private const val PUSH_EVERY_MS = 3 * 60 * 60_000L
        private const val OLD_RETRY_MS = 6 * 60 * 60_000L
    }
}
