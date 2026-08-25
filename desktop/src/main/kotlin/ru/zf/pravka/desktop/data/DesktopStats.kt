package ru.zf.pravka.desktop.data

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.data.UsageStats

// Счётчики расхода воркстанции: stats.json. Ровно те же поля, что копит
// телефонный Stats, включая расход по дням в микродолларах - иначе сводка
// после синхронизации не сложится.
class DesktopStats(dir: File = Paths.dir) : UsageStats {

    private val store = JsonFile(File(dir, "stats.json"))

    data class Snapshot(
        val total: Long = 0,
        val errors: Long = 0,
        val unchanged: Long = 0,
        val charsProcessed: Long = 0,
        val latencySumMs: Long = 0,
        val latencyCount: Long = 0,
        val tokensIn: Long = 0,
        val tokensOut: Long = 0,
        val costTodayUsd: Double = 0.0,
        val costTotalUsd: Double = 0.0,
    ) {
        val averageLatencyMs: Long get() = if (latencyCount > 0) latencySumMs / latencyCount else 0
    }

    private val _snapshot = MutableStateFlow(read())
    val snapshotFlow: StateFlow<Snapshot> = _snapshot

    override suspend fun recordSuccess(
        mode: ProofreadMode,
        latencyMs: Long,
        charsIn: Int,
        changed: Boolean,
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
    ) {
        store.edit { o ->
            o.put("total", o.optLong("total") + 1)
            o.put(mode.name.lowercase(), o.optLong(mode.name.lowercase()) + 1)
            if (!changed) o.put("unchanged", o.optLong("unchanged") + 1)
            o.put("chars", o.optLong("chars") + charsIn)
            o.put("latency_sum", o.optLong("latency_sum") + latencyMs)
            o.put("latency_count", o.optLong("latency_count") + 1)
            addSpend(o, tokensIn, tokensOut, costUsd)
        }
        _snapshot.value = read()
    }

    override suspend fun recordError() {
        store.edit { o -> o.put("errors", o.optLong("errors") + 1) }
        _snapshot.value = read()
    }

    override suspend fun recordAux(costUsd: Double, tokensIn: Int, tokensOut: Int) {
        store.edit { o -> addSpend(o, tokensIn, tokensOut, costUsd) }
        _snapshot.value = read()
    }

    // Деньги - целыми микродолларами по календарным дням ("cost_2026-08-25"),
    // как на телефоне: так суммы за день/неделю/месяц переживают перезапуск и
    // складываются с телефонными без пересчёта.
    private fun addSpend(o: org.json.JSONObject, tokensIn: Int, tokensOut: Int, costUsd: Double) {
        o.put("tokens_in", o.optLong("tokens_in") + tokensIn)
        o.put("tokens_out", o.optLong("tokens_out") + tokensOut)
        val micros = (costUsd * 1_000_000).toLong()
        val key = dayKey()
        o.put(key, o.optLong(key) + micros)
        o.put("cost_total_micros", o.optLong("cost_total_micros") + micros)
    }

    private fun dayKey(date: LocalDate = LocalDate.now()) = "cost_$date"

    private fun read() = Snapshot(
        total = store.long("total", 0),
        errors = store.long("errors", 0),
        unchanged = store.long("unchanged", 0),
        charsProcessed = store.long("chars", 0),
        latencySumMs = store.long("latency_sum", 0),
        latencyCount = store.long("latency_count", 0),
        tokensIn = store.long("tokens_in", 0),
        tokensOut = store.long("tokens_out", 0),
        costTodayUsd = store.long(dayKey(), 0) / 1_000_000.0,
        costTotalUsd = store.long("cost_total_micros", 0) / 1_000_000.0,
    )
}
