package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Mirrors closed Засечка entries into the owner's Google Sheet through a tiny
// Apps Script web app (docs/zasechka-sheets.md has the script). The phone
// stays the source of truth: rows are upserted by id, so an edit here just
// re-sends the row; a failed sync keeps entries flagged unsynced and the next
// kick retries them. No OAuth - the script URL itself is the shared secret.
class ZasechkaSync(
    private val settings: Settings,
    private val store: ZasechkaStore,
    private val client: okhttp3.OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val BATCH = 100
    }

    // Human-readable last-sync line for the tab ("14:32 · 5 строк ✓").
    private val _statusFlow = MutableStateFlow("")
    val statusFlow: StateFlow<String> = _statusFlow

    private val running = AtomicBoolean(false)
    private val kicked = AtomicBoolean(false)

    /**
     * Debounced fire-and-forget: called after every close/edit. Waits a few
     * seconds so a burst (close + new entry + owner edit) becomes one POST.
     */
    fun kickSoon(scope: CoroutineScope) {
        if (!kicked.compareAndSet(false, true)) return
        scope.launch {
            delay(5_000)
            kicked.set(false)
            syncNow()
        }
    }

    /** Pushes the Sheets mirror. Returns the row count. */
    suspend fun syncNow(): Result<Int> = syncSheets()

    private suspend fun syncSheets(): Result<Int> {
        val url = settings.zWebhook()
        if (url.isBlank()) return Result.success(0)
        if (!running.compareAndSet(false, true)) return Result.success(0)
        try {
            var total = 0
            while (true) {
                val batch = store.unsynced().take(BATCH)
                if (batch.isEmpty()) break
                val result = post(url, batch)
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "ошибка сети"
                    eventLog.add("засечка-синк: не удалось ($message), строк в очереди ${batch.size}")
                    _statusFlow.value = "${timeNow()} · не удалось: $message"
                    return Result.failure(result.exceptionOrNull() ?: Exception(message))
                }
                store.markSynced(batch.map { it.id })
                total += batch.size
                if (batch.size < BATCH) break
            }
            if (total > 0) {
                eventLog.add("засечка-синк: отправлено строк $total")
                _statusFlow.value = "${timeNow()} · строк: $total ✓"
            }
            return Result.success(total)
        } finally {
            running.set(false)
        }
    }

    private suspend fun post(url: String, batch: List<ZasechkaStore.Entry>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
                val worth = store.categories().associate { it.name.trim().lowercase() to it.value }
                val body = JSONObject().put(
                    "entries",
                    JSONArray().apply {
                        for (e in batch) {
                            put(
                                JSONObject().apply {
                                    put("id", e.id.toString())
                                    put("date", dateFormat.format(Date(e.start)))
                                    put("start", timeFormat.format(Date(e.start)))
                                    put("end", timeFormat.format(Date(e.end)))
                                    // Как и в CSV: время разнесено по двум
                                    // колонкам, чтобы сумма любой из них была
                                    // честной. Параллель в сутки не входит.
                                    put("track", if (e.parallel) "параллельно" else "основной")
                                    put("minutes", if (e.parallel) 0L else e.durationMin())
                                    put("minutes_parallel", if (e.parallel) e.durationMin() else 0L)
                                    put("title", e.title)
                                    put("category", e.category)
                                    put("client", e.client)
                                    put("useful", if (e.useful > 0) e.useful else JSONObject.NULL)
                                    // The worth of this row on the owner's
                                    // scale, and what it did to the balance.
                                    put("value", worth[e.category.trim().lowercase()] ?: 0)
                                    put(
                                        "points",
                                        String.format(
                                            Locale.US,
                                            "%.1f",
                                            (worth[e.category.trim().lowercase()] ?: 0) *
                                                e.durationMs() / 3_600_000.0,
                                        ).toDouble(),
                                    )
                                    put("source", e.source)
                                    put("raw", e.raw)
                                }
                            )
                        }
                    }
                )
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                // OkHttp follows the Apps Script 302 (POST -> GET on the
                // googleusercontent result URL) by default - exactly the dance
                // the web app expects.
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val text = response.body?.string().orEmpty()
                    // The script answers {"ok":true}; an HTML page here means
                    // the deployment is misconfigured (access not "anyone").
                    if (!text.contains("\"ok\"")) {
                        throw java.io.IOException("таблица ответила не по формату — проверь развёртывание скрипта")
                    }
                }
            }
        }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis()))
}
