package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

// Full proofread history as JSONL on the device (owner's request - he feeds
// it to a bigger model for quality analysis). This intentionally overrides
// spec section 14 "do not persist fix texts": the owner asked for exactly
// that, and the file never leaves the device except via his own share action.
class HistoryLog(private val context: Context) {

    companion object {
        private const val FILE_NAME = "history.jsonl"
        private const val MAX_BYTES = 5L * 1024 * 1024
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private val file: File by lazy { File(DataRoot.dir(context), FILE_NAME) }

    fun append(
        mode: String,
        providerId: String,
        model: String,
        latencyMs: Long,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        changed: Boolean,
        input: String,
        output: String,
        error: String?,
        cacheWriteTokens: Int = 0,
        cacheReadTokens: Int = 0,
        /** Чистка шла с директивой прозы — тень второй модели повторит её так же. */
        prose: Boolean = false,
        /** Чистка шла с директивой чипа, контекстом поля или разговором — в журнале их нет, тень такую пропускает. */
        withContext: Boolean = false,
    ) {
        // Off the caller thread (this runs right after a proofread lands):
        // DiskWriter's single thread also provides the ordering @Synchronized
        // used to. Timestamp captured here so entries carry the real time.
        val at = Date()
        DiskWriter.post {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(DataRoot.dir(context), "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val entry = JSONObject().apply {
                put("ts", timestampFormat.format(at))
                put("mode", mode)
                put("provider", providerId)
                put("model", model)
                put("latency_ms", latencyMs)
                put("input_tokens", inputTokens)
                if (cacheWriteTokens > 0) put("cache_write_tokens", cacheWriteTokens)
                if (cacheReadTokens > 0) put("cache_read_tokens", cacheReadTokens)
                put("output_tokens", outputTokens)
                put("cost_usd", costUsd)
                put("changed", changed)
                put("input", input)
                put("output", output)
                if (prose) put("prose", true)
                if (withContext) put("ctx", true)
                if (error != null) put("error", error)
            }
            file.appendText(entry.toString() + "\n")
        }
    }

    fun exists(): Boolean = file.exists() && file.length() > 0

    /**
     * Last [limit] successful CHANGED fixes as (dictated input, final output) -
     * the raw material the dictionary miner looks for recurring ASR
     * misrecognitions in.
     */
    fun readPairs(limit: Int): List<Pair<String, String>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { !it.has("error") && it.optBoolean("changed") }
                .map { it.optString("input") to it.optString("output") }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .take(limit)
                .toList()
        }.getOrElse { emptyList() }
    }

    /** Одна чистка: когда, что надиктовано, что сделала модель, чем и за сколько. */
    data class Entry(
        val tsMs: Long,
        val input: String,
        val output: String,
        val costUsd: Double = 0.0,
        val model: String = "",
        /** Шла директива прозы (записи до 16.09.2026 флага не имеют — считаются без неё). */
        val prose: Boolean = false,
        /** Был контекст поля, разговор или директива чипа — чистка невоспроизводима по журналу. */
        val withContext: Boolean = false,
    )

    /**
     * Успешные чистки CLEAN за период [fromMs, toMs), по времени — сырьё
     * ночного разбора (16.09.2026): пары «надиктовано → модель» за сутки или
     * неделю, а не хвост фиксированной длины.
     */
    fun readEntries(fromMs: Long, toMs: Long): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                if (o.has("error") || o.optString("mode") != "CLEAN") return@mapNotNull null
                val t = runCatching { timestampFormat.parse(o.optString("ts"))?.time }.getOrNull()
                    ?: return@mapNotNull null
                if (t < fromMs || t >= toMs) return@mapNotNull null
                val input = o.optString("input")
                val output = o.optString("output")
                if (input.isBlank() || output.isBlank()) null
                else Entry(
                    t, input, output,
                    costUsd = o.optDouble("cost_usd", 0.0), model = o.optString("model"),
                    prose = o.optBoolean("prose"), withContext = o.optBoolean("ctx"),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** Один запрос глазами дуги прогресса: чем, на скольких знаках, сколько шёл. */
    data class Timing(val mode: String, val model: String, val chars: Int, val ms: Long)

    /**
     * Последние [limit] удачных запросов — только замеры, без текстов.
     * Отсюда дуга прогресса берёт своё прошлое при первом запуске после
     * обновления: журнал ведётся с июля, и начинать учиться заново, имея
     * полторы тысячи записей, было бы глупо (владелец, 20.09.2026: «ты не
     * взял всю статистику, а только начал её собирать»).
     *
     * Читаем построчно и держим хвост: файл — мегабайты текста правок, а
     * нужны с каждой строки четыре числа. Упавшие запросы пропускаем — время
     * ошибки это время сети, а не время модели.
     */
    fun readTimings(limit: Int): List<Timing> {
        if (!file.exists() || limit <= 0) return emptyList()
        val tail = ArrayDeque<Timing>()
        runCatching {
            file.forEachLine { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (o.has("error")) return@forEachLine
                val model = o.optString("model")
                val ms = o.optLong("latency_ms", 0L)
                if (model.isBlank() || ms <= 0L) return@forEachLine
                tail.addLast(Timing(o.optString("mode"), model, o.optString("input").length, ms))
                if (tail.size > limit) tail.removeFirst()
            }
        }
        return tail.toList()
    }

    /**
     * Запрос журнала для ночной калибровки секунд (`core/PaceTune.kt`): когда
     * закончился, режим, модель, длина надиктованного, сколько шёл и как
     * прошёл кэш (true — прочитан, false — только записан, null — не было).
     */
    data class PaceRow(
        val at: Long,
        val mode: String,
        val model: String,
        val chars: Int,
        val ms: Long,
        val cache: Boolean?,
    )

    /**
     * ВСЕ удачные запросы журнала — и прошлый файл (`.1`, до ротации), и
     * нынешний, по порядку. Построчно и без текстов в памяти: журнал это
     * мегабайты правок. Звать не на главном потоке.
     */
    fun readPaceRows(): List<PaceRow> {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val out = ArrayList<PaceRow>()
        for (f in listOf(File(DataRoot.dir(context), "$FILE_NAME.1"), file)) {
            if (!f.exists()) continue
            runCatching {
                f.forEachLine { line ->
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (o.has("error")) return@forEachLine
                    val model = o.optString("model")
                    val ms = o.optLong("latency_ms", 0L)
                    if (model.isBlank() || ms <= 0L) return@forEachLine
                    val at = runCatching { stamp.parse(o.optString("ts"))?.time }.getOrNull() ?: return@forEachLine
                    val read = o.optInt("cache_read_tokens", 0)
                    val write = o.optInt("cache_write_tokens", 0)
                    out += PaceRow(
                        at = at,
                        mode = o.optString("mode"),
                        model = model,
                        chars = o.optString("input").length,
                        ms = ms,
                        cache = when {
                            read > 0 -> true
                            write > 0 -> false
                            else -> null
                        },
                    )
                }
            }
        }
        return out
    }

    /** Что делал сам владелец в приложении: режим, день, деньги. Без текстов. */
    data class Meta(val date: String, val mode: String, val costUsd: Double, val changed: Boolean)

    /**
     * Хвост журнала правок без самих текстов — только по чему и когда.
     * Разбору нужно понимать, чем владелец пользовался в приложении: диктовки,
     * разноска, помощник. Тексты правок в разбор не идут: они и так живут в
     * ленте, а лишний мегабайт в промпте стоит денег.
     */
    fun readMeta(limit: Int): List<Meta> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(limit).mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    Meta(
                        date = o.optString("ts").take(10),
                        mode = o.optString("mode"),
                        costUsd = o.optDouble("cost_usd", 0.0),
                        changed = o.optBoolean("changed", false),
                    )
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    fun shareIntent(): Intent = shareFileIntent(context, file, "application/json")
}
