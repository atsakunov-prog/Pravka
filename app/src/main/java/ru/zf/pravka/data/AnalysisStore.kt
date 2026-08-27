package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Итоги: разборы жизненного лога, которые пишет Опус по ночам.
 *
 * Отправка идёт БАТЧЕМ (в два раза дешевле обычного вызова), а батч — вещь
 * асинхронная: заявка уходит ночью, ответ приезжает через минуты или часы.
 * Поэтому запись живёт двумя состояниями: сначала «ждёт» с идентификатором
 * батча, потом «готов» с текстом. Опрос делает тик службы.
 *
 * Хеш входных данных хранится рядом с текстом нарочно: по нему видно, что
 * разбор сделан ровно по этим данным, и можно не гонять модель второй раз
 * по тому же периоду.
 */
class AnalysisStore(private val context: Context) {

    companion object {
        private const val FILE_NAME = "analysis.json"
        // Разборы — это тексты по несколько килобайт. Год храним спокойно,
        // а список в интерфейсе всё равно листается.
        private const val KEEP = 200
    }

    data class Report(
        val id: Long,
        /** daily | weekly | deep */
        val mode: String,
        val from: String,
        val to: String,
        val createdAt: Long,
        /** «ждёт» пока батч считается; «готов»; «не вышло». */
        val status: String,
        val batchId: String = "",
        val text: String = "",
        val error: String = "",
        val model: String = "",
        val costUsd: Double = 0.0,
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
        val inputHash: String = "",
        /** Сколько знаков ушло в модель — видно, не раздулся ли период. */
        val inputChars: Int = 0,
    ) {
        val pending: Boolean get() = status == "ждёт"
        val ready: Boolean get() = status == "готов"

        fun title(): String = when (mode) {
            "daily" -> "День $from"
            "weekly" -> "Неделя $from — $to"
            else -> "Период $from — $to"
        }
    }

    /**
     * Паттерн, который модель уже называла. Живёт между разборами: в следующий
     * уезжает блоком <known_patterns>, и модель обязана сказать по каждому —
     * подтвердился, ослаб, исчез или данных не хватило. Владелец: «мы же не
     * разбираем один день как сферический конь в вакууме».
     */
    data class Pattern(
        val text: String,
        val firstSeen: String,
        val lastSeen: String,
        val times: Int,
        val points: Int,
        val confidence: String,
    ) {
        /** Ключ сравнения: формулировку модель повторяет не буква в букву. */
        fun key(): String = text.lowercase()
            .replace(Regex("[^а-яёa-z0-9 ]"), " ")
            .split(' ').filter { it.length > 3 }.take(5).sorted().joinToString(" ")
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _reportsFlow = MutableStateFlow<List<Report>>(emptyList())
    val reportsFlow: StateFlow<List<Report>> = _reportsFlow

    private val _patternsFlow = MutableStateFlow<List<Pattern>>(emptyList())
    val patternsFlow: StateFlow<List<Pattern>> = _patternsFlow

    var logger: ((String) -> Unit)? = null

    suspend fun load() = mutex.withLock { ensureLoaded() }

    /** Заявка ушла в батч: запись появляется сразу, ещё без текста. */
    suspend fun addPending(
        mode: String,
        from: String,
        to: String,
        batchId: String,
        model: String,
        inputHash: String,
        inputChars: Int,
    ): Report = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val report = Report(
            id = now,
            mode = mode,
            from = from,
            to = to,
            createdAt = now,
            status = "ждёт",
            batchId = batchId,
            model = model,
            inputHash = inputHash,
            inputChars = inputChars,
        )
        _reportsFlow.value = (listOf(report) + _reportsFlow.value).take(KEEP)
        persist()
        report
    }

    suspend fun complete(
        id: Long,
        text: String,
        costUsd: Double,
        tokensIn: Int,
        tokensOut: Int,
    ): Report? = mutex.withLock {
        ensureLoaded()
        var out: Report? = null
        _reportsFlow.value = _reportsFlow.value.map { r ->
            if (r.id != id) r else r.copy(
                status = "готов",
                text = text.trim(),
                costUsd = costUsd,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                error = "",
            ).also { out = it }
        }
        persist()
        out
    }

    suspend fun fail(id: Long, message: String) = mutex.withLock {
        ensureLoaded()
        _reportsFlow.value = _reportsFlow.value.map { r ->
            if (r.id != id) r else r.copy(status = "не вышло", error = message.take(300))
        }
        persist()
    }

    suspend fun delete(id: Long) = mutex.withLock {
        ensureLoaded()
        _reportsFlow.value = _reportsFlow.value.filterNot { it.id == id }
        persist()
    }

    /**
     * Слить свежие паттерны с прежними: тот же паттерн — счётчик подтверждений
     * растёт, новый — добавляется, пропавший НЕ удаляется сразу (модель должна
     * увидеть его в следующий раз и сказать, что он исчез). Дольше трёх
     * разборов без подтверждения — забываем, иначе список станет свалкой.
     */
    suspend fun mergePatterns(fresh: List<Pattern>, date: String) = mutex.withLock {
        ensureLoaded()
        val old = _patternsFlow.value
        val out = old.toMutableList()
        for (f in fresh) {
            val at = out.indexOfFirst { it.key() == f.key() }
            if (at >= 0) {
                val prev = out[at]
                out[at] = prev.copy(
                    text = f.text,
                    lastSeen = date,
                    times = prev.times + 1,
                    points = f.points,
                    confidence = f.confidence,
                )
            } else {
                out.add(f.copy(firstSeen = date, lastSeen = date, times = 1))
            }
        }
        // Три разбора не виден — отпускаем.
        _patternsFlow.value = out
            .filter { it.lastSeen >= shiftDays(date, -21) || it.lastSeen == date }
            .sortedByDescending { it.times }
            .take(12)
        persist()
    }

    private fun shiftDays(date: String, days: Int): String = runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.time = fmt.parse(date)!!
        cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        fmt.format(cal.time)
    }.getOrDefault(date)

    fun pending(): List<Report> = _reportsFlow.value.filter { it.pending }

    /** Последний разбор этого режима — по нему решается, пора ли новый. */
    fun latest(mode: String): Report? = _reportsFlow.value.firstOrNull { it.mode == mode }

    // ---- диск ----

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val root = StoreFiles.readOrQuarantine(file) { JSONObject(it) } ?: return
        val list = mutableListOf<Report>()
        root.optJSONArray("reports")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                list.add(
                    Report(
                        id = o.optLong("id"),
                        mode = o.optString("mode"),
                        from = o.optString("from"),
                        to = o.optString("to"),
                        createdAt = o.optLong("createdAt"),
                        status = o.optString("status"),
                        batchId = o.optString("batchId"),
                        text = o.optString("text"),
                        error = o.optString("error"),
                        model = o.optString("model"),
                        costUsd = o.optDouble("cost", 0.0),
                        tokensIn = o.optInt("tin"),
                        tokensOut = o.optInt("tout"),
                        inputHash = o.optString("hash"),
                        inputChars = o.optInt("chars"),
                    )
                )
            }
        }
        _reportsFlow.value = list.sortedByDescending { it.createdAt }
        val pats = mutableListOf<Pattern>()
        root.optJSONArray("patterns")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                pats.add(
                    Pattern(
                        text = o.optString("text"),
                        firstSeen = o.optString("first"),
                        lastSeen = o.optString("last"),
                        times = o.optInt("times", 1),
                        points = o.optInt("points"),
                        confidence = o.optString("conf"),
                    )
                )
            }
        }
        _patternsFlow.value = pats
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("reports", JSONArray().apply {
                _reportsFlow.value.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("mode", r.mode)
                        put("from", r.from)
                        put("to", r.to)
                        put("createdAt", r.createdAt)
                        put("status", r.status)
                        put("batchId", r.batchId)
                        put("text", r.text)
                        put("error", r.error)
                        put("model", r.model)
                        put("cost", r.costUsd)
                        put("tin", r.tokensIn)
                        put("tout", r.tokensOut)
                        put("hash", r.inputHash)
                        put("chars", r.inputChars)
                    })
                }
            })
            put("patterns", JSONArray().apply {
                _patternsFlow.value.forEach { pt ->
                    put(JSONObject().apply {
                        put("text", pt.text)
                        put("first", pt.firstSeen)
                        put("last", pt.lastSeen)
                        put("times", pt.times)
                        put("points", pt.points)
                        put("conf", pt.confidence)
                    })
                }
            })
        }
        runCatching { StoreFiles.writeAtomic(file, root.toString()) }
            .onFailure { logger?.invoke("итоги: не записалось — ${it.message}") }
    }
}
