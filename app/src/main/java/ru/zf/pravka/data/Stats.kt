package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.zf.pravka.core.ProofreadMode

private val Context.statsDataStore by preferencesDataStore(name = "stats")

// Persistent usage counters. Only numbers live here - no text of any fix
// is ever persisted (spec section 14).
class Stats(private val context: Context) {

    data class Snapshot(
        val total: Long,
        val clean: Long,
        val business: Long,
        val soften: Long,
        val unchanged: Long,
        val errors: Long,
        val charsProcessed: Long,
        val latencySumMs: Long,
        val latencyCount: Long,
        val tokensIn: Long,
        val tokensOut: Long,
        /** Сколько входа пришло из кэша промпта и сколько в него записано — видно, работает ли кэш. */
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
        val costTodayUsd: Double,
        val costWeekUsd: Double,
        val costMonthUsd: Double,
        val costTotalUsd: Double,
    ) {
        val averageLatencyMs: Long get() = if (latencyCount > 0) latencySumMs / latencyCount else 0
    }

    private object Keys {
        val TOTAL = longPreferencesKey("total")
        val CLEAN = longPreferencesKey("clean")
        val BUSINESS = longPreferencesKey("business")
        val SOFTEN = longPreferencesKey("soften")
        val UNCHANGED = longPreferencesKey("unchanged")
        val ERRORS = longPreferencesKey("errors")
        val CHARS = longPreferencesKey("chars")
        val LATENCY_SUM = longPreferencesKey("latency_sum")
        val LATENCY_COUNT = longPreferencesKey("latency_count")
        val TOKENS_IN = longPreferencesKey("tokens_in")
        val TOKENS_OUT = longPreferencesKey("tokens_out")
        val CACHE_READ = longPreferencesKey("cache_read_tokens")
        val CACHE_WRITE = longPreferencesKey("cache_write_tokens")
        val COST_TOTAL = longPreferencesKey("cost_total_micros")
    }

    // Cost is stored as integer micro-dollars per calendar day
    // ("cost_20260727" -> 1234), so today/week/month sums survive restarts.
    private fun dayKey(daysAgo: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return String.format(
            Locale.US, "cost_%04d%02d%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * Ведро дороги за день: route_<дорога>_YYYYMMDD, микродоллары, живёт 62 дня,
     * как и cost_. Владелец (17.09.2026): «за день шесть долларов, и растёт
     * каждый день — что-то не то»; общая сумма на это не отвечает, а разложенная
     * по дорогам — отвечает сразу (Засечка на Fable — две трети дня).
     */
    private fun routeKey(route: String, daysAgo: Int = 0): String =
        "route_" + route + "_" + dayKey(daysAgo).removePrefix("cost_")

    private fun MutablePreferences.addRoute(route: String, micros: Long) {
        if (route.isBlank() || micros == 0L) return
        val k = longPreferencesKey(routeKey(route))
        this[k] = (this[k] ?: 0) + micros
    }

    /** Деньги по дорогам за [days] дней, дороже — первыми: ответ на «куда уходят деньги». */
    suspend fun routeCosts(days: Int): List<Pair<String, Double>> {
        val prefs = context.statsDataStore.data.first()
        val from = dayKey(days.coerceIn(1, 62) - 1).removePrefix("cost_")
        val sums = HashMap<String, Long>()
        for ((k, v) in prefs.asMap()) {
            val name = k.name
            if (!name.startsWith("route_") || name.length < 16) continue
            val date = name.takeLast(8)
            if (date < from) continue
            val route = name.substring(6, name.length - 9)
            sums[route] = (sums[route] ?: 0L) + ((v as? Long) ?: 0L)
        }
        return sums.entries.sortedByDescending { it.value }.map { it.key to it.value / 1_000_000.0 }
    }

    private fun daysSinceMonday(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return (dow + 5) % 7  // Monday -> 0, Sunday -> 6
    }

    private fun dayOfMonth(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    val snapshotFlow: Flow<Snapshot> = context.statsDataStore.data.map { p ->
        fun costMicros(daysBack: Int): Long =
            (0..daysBack).sumOf { p[longPreferencesKey(dayKey(it))] ?: 0L }
        Snapshot(
            total = p[Keys.TOTAL] ?: 0,
            clean = p[Keys.CLEAN] ?: 0,
            business = p[Keys.BUSINESS] ?: 0,
            soften = p[Keys.SOFTEN] ?: 0,
            unchanged = p[Keys.UNCHANGED] ?: 0,
            errors = p[Keys.ERRORS] ?: 0,
            charsProcessed = p[Keys.CHARS] ?: 0,
            latencySumMs = p[Keys.LATENCY_SUM] ?: 0,
            latencyCount = p[Keys.LATENCY_COUNT] ?: 0,
            tokensIn = p[Keys.TOKENS_IN] ?: 0,
            tokensOut = p[Keys.TOKENS_OUT] ?: 0,
            cacheReadTokens = p[Keys.CACHE_READ] ?: 0,
            cacheWriteTokens = p[Keys.CACHE_WRITE] ?: 0,
            costTodayUsd = costMicros(0) / 1_000_000.0,
            costWeekUsd = costMicros(daysSinceMonday()) / 1_000_000.0,
            costMonthUsd = costMicros(dayOfMonth() - 1) / 1_000_000.0,
            costTotalUsd = (p[Keys.COST_TOTAL] ?: 0) / 1_000_000.0,
        )
    }

    suspend fun recordSuccess(
        mode: ProofreadMode,
        latencyMs: Long,
        charsIn: Int,
        changed: Boolean,
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
        /** Ключ дороги (ModelRoute.key) — для разложения денег по дорогам. */
        route: String = "",
    ) {
        context.statsDataStore.edit { p ->
            p[Keys.TOTAL] = (p[Keys.TOTAL] ?: 0) + 1
            val modeKey = when (mode) {
                ProofreadMode.CLEAN -> Keys.CLEAN
                ProofreadMode.BUSINESS -> Keys.BUSINESS
                ProofreadMode.SOFTEN -> Keys.SOFTEN
            }
            p[modeKey] = (p[modeKey] ?: 0) + 1
            if (!changed) p[Keys.UNCHANGED] = (p[Keys.UNCHANGED] ?: 0) + 1
            p[Keys.CHARS] = (p[Keys.CHARS] ?: 0) + charsIn
            p[Keys.LATENCY_SUM] = (p[Keys.LATENCY_SUM] ?: 0) + latencyMs
            p[Keys.LATENCY_COUNT] = (p[Keys.LATENCY_COUNT] ?: 0) + 1
            p[Keys.TOKENS_IN] = (p[Keys.TOKENS_IN] ?: 0) + tokensIn
            p[Keys.TOKENS_OUT] = (p[Keys.TOKENS_OUT] ?: 0) + tokensOut
            val micros = (costUsd * 1_000_000).toLong()
            val todayKey = longPreferencesKey(dayKey(0))
            p[todayKey] = (p[todayKey] ?: 0) + micros
            p[Keys.COST_TOTAL] = (p[Keys.COST_TOTAL] ?: 0) + micros
            p.addRoute(route, micros)
            pruneOldDayKeys(p)
        }
    }

    /**
     * Токены кэша промпта — отдельными счётчиками ко ВСЕМ дорогам (16.09.2026,
     * владелец: «кэширование — супер-тема для экономии, давай сделаем везде,
     * где эффективно»). Пишет транспорт через ClaudeProvider.usageObserver и
     * батчи ночного разбора; на экране стоимости — доля входа из кэша.
     */
    suspend fun recordCache(readTokens: Int, writeTokens: Int) {
        if (readTokens <= 0 && writeTokens <= 0) return
        context.statsDataStore.edit { p ->
            p[Keys.CACHE_READ] = (p[Keys.CACHE_READ] ?: 0) + readTokens
            p[Keys.CACHE_WRITE] = (p[Keys.CACHE_WRITE] ?: 0) + writeTokens
        }
    }

    /** Cost/token accounting for non-proofread API calls: assist actions,
     *  learning (Opus), the dictionary miner and eval runs. Money and tokens
     *  land in the same counters the owner reads in Статистика. */
    suspend fun recordAux(costUsd: Double, tokensIn: Int, tokensOut: Int, route: String = "") {
        context.statsDataStore.edit { p ->
            p[Keys.TOKENS_IN] = (p[Keys.TOKENS_IN] ?: 0) + tokensIn
            p[Keys.TOKENS_OUT] = (p[Keys.TOKENS_OUT] ?: 0) + tokensOut
            val micros = (costUsd * 1_000_000).toLong()
            val todayKey = longPreferencesKey(dayKey(0))
            p[todayKey] = (p[todayKey] ?: 0) + micros
            p[Keys.COST_TOTAL] = (p[Keys.COST_TOTAL] ?: 0) + micros
            p.addRoute(route, micros)
            pruneOldDayKeys(p)
        }
    }

    // Day buckets used to accumulate forever (one key per day, deserialized
    // on every stats read). Anything past the month view is already rolled
    // into COST_TOTAL - drop it. Runs at most once per day.
    private fun pruneOldDayKeys(p: MutablePreferences) {
        val marker = stringPreferencesKey("cost_prune_marker")
        val today = dayKey(0)
        if (p[marker] == today) return
        p[marker] = today
        val cutoff = dayKey(62)
        val cutoffDate = cutoff.removePrefix("cost_")
        p.asMap().keys
            .filter {
                (it.name.length == cutoff.length && it.name.startsWith("cost_2") && it.name < cutoff) ||
                    (it.name.startsWith("route_") && it.name.length >= 16 && it.name.takeLast(8) < cutoffDate)
            }
            .forEach { p.remove(longPreferencesKey(it.name)) }
    }

    /**
     * Деньги на модель по суткам, свежий день первым. Нужны разбору «Итогов»:
     * в ленте видно, сколько часов ушло в «Систематизацию», а здесь — сколько
     * это стоило деньгами. Две цифры рядом и есть та самая улика, которую
     * владелец сам называет «паттерн сначала»: сначала построить систему,
     * потом делать дело. Ведро на день живёт 62 суток, дальше не спрашивать.
     */
    suspend fun dailyCosts(days: Int): List<Pair<String, Double>> {
        val prefs = context.statsDataStore.data.first()
        return (0 until days.coerceIn(1, 62)).map { back ->
            val key = dayKey(back)
            val date = key.removePrefix("cost_")
            val iso = date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
            iso to ((prefs[longPreferencesKey(key)] ?: 0L) / 1_000_000.0)
        }
    }

    /**
     * Потолок дня для ночных автоматов сравнивают с этим, а не с costTodayUsd:
     * в три ночи «сегодня» — три часа и ноль долларов, и потолок не ловил ничего
     * (18.09.2026: день на $11,58 — и разбор ночью стартовал как ни в чём не
     * бывало). Максимум из вчера и сегодня: ночью это вчерашний день, днём —
     * текущий.
     */
    suspend fun costRecentDayUsd(): Double = dailyCosts(2).maxOf { it.second }

    suspend fun recordError() {
        context.statsDataStore.edit { p ->
            p[Keys.ERRORS] = (p[Keys.ERRORS] ?: 0) + 1
        }
    }
}
