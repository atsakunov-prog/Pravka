package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    }

    val snapshotFlow: Flow<Snapshot> = context.statsDataStore.data.map { p ->
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
        )
    }

    suspend fun recordSuccess(mode: ProofreadMode, latencyMs: Long, charsIn: Int, changed: Boolean) {
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
        }
    }

    suspend fun recordError() {
        context.statsDataStore.edit { p ->
            p[Keys.ERRORS] = (p[Keys.ERRORS] ?: 0) + 1
        }
    }
}
