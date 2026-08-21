package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Mirrors closed Засечка entries into a Notion database - the owner's second
// window on the data besides Google Sheets. Same contract as ZasechkaSync:
// the phone is the source of truth, rows are upserted by the EntryId
// property (an edit here re-sends the page), failures keep entries flagged
// and the next kick retries. Auth is an internal-integration token the owner
// pastes in the settings; the database must be shared with that integration.
class NotionSync(
    private val settings: Settings,
    private val store: ZasechkaStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val API = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        // Notion allows ~3 req/s; each entry costs 2 (query + write). A batch
        // per sync keeps well under it, leftovers go on the next kick.
        private const val BATCH = 25
    }

    private val _statusFlow = MutableStateFlow("")
    val statusFlow: StateFlow<String> = _statusFlow

    private val running = AtomicBoolean(false)

    // A permanently rejected config (bad database id, bad token) must not be
    // re-tried on every kick - the log was drowning in the same 404. The sync
    // pauses until the owner changes the token or the id.
    @Volatile private var blockedConfig: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** Pushes unsynced closed entries. Returns how many pages were upserted. */
    suspend fun syncNow(): Result<Int> {
        val token = settings.notionToken()
        val rawDb = settings.notionDb()
        if (token.isBlank() || rawDb.isBlank()) return Result.success(0)
        // The owner pastes whatever Notion gave him - a page URL, a dashed
        // UUID, a bare id. Normalize to the 32 hex chars Notion wants.
        val db = normalizeDbId(rawDb)
        if (db == null) {
            val cfg = "$token|$rawDb"
            if (blockedConfig != cfg) {
                blockedConfig = cfg
                eventLog.add("notion: «$rawDb» не похож на id базы — жду правильный в настройках")
                _statusFlow.value = "${timeNow()} · id базы не похож на Notion-id"
            }
            return Result.success(0)
        }
        val cfg = "$token|$db"
        if (cfg == blockedConfig) return Result.success(0)
        if (!running.compareAndSet(false, true)) return Result.success(0)
        try {
            return withContext(Dispatchers.IO) {
                var total = 0
                val batch = store.unsyncedNotion().take(BATCH)
                for (entry in batch) {
                    val result = runCatching { upsert(token, db, entry) }
                    if (result.isFailure) {
                        val message = result.exceptionOrNull()?.message ?: "ошибка сети"
                        // Config-level rejections never heal on retry - pause
                        // until the settings change; network blips keep retrying.
                        if (message.contains("Could not find database") ||
                            message.contains("HTTP 401")
                        ) {
                            blockedConfig = cfg
                            eventLog.add(
                                "notion: настройка отвергнута ($message) — синк на паузе до смены id/токена"
                            )
                            _statusFlow.value =
                                "${timeNow()} · база не найдена: проверь id и Connections интеграции"
                        } else {
                            eventLog.add("notion: не удалось ($message), в очереди ещё ${batch.size - total}")
                            _statusFlow.value = "${timeNow()} · не удалось: $message"
                        }
                        return@withContext Result.failure(
                            result.exceptionOrNull() ?: Exception(message)
                        )
                    }
                    store.markNotionSynced(listOf(entry.id))
                    total++
                }
                if (total > 0) {
                    eventLog.add("notion: отправлено записей $total")
                    _statusFlow.value = "${timeNow()} · записей: $total ✓"
                }
                Result.success(total)
            }
        } finally {
            running.set(false)
        }
    }

    // "https://notion.so/ws/5b11be11...?v=..." | dashed UUID | bare id -> the
    // 32 hex chars, or null when nothing id-shaped is in the string.
    private fun normalizeDbId(raw: String): String? {
        val undashed = raw.trim().substringBefore('?').replace("-", "")
        return Regex("[0-9a-fA-F]{32}").find(undashed)?.value?.lowercase(Locale.US)
    }

    private fun upsert(token: String, db: String, entry: ZasechkaStore.Entry) {
        val existing = findPage(token, db, entry.id)
        val properties = properties(entry)
        if (existing != null) {
            patch(
                "$API/pages/$existing", token,
                JSONObject().put("properties", properties),
            )
        } else {
            post(
                "$API/pages", token,
                JSONObject()
                    .put("parent", JSONObject().put("database_id", db))
                    .put("properties", properties),
            )
        }
    }

    private fun properties(entry: ZasechkaStore.Entry): JSONObject = JSONObject().apply {
        put("Дело", JSONObject().put("title", textArray(entry.title.ifBlank { "(без названия)" })))
        put(
            "Дата",
            JSONObject().put(
                "date",
                JSONObject()
                    .put("start", dateFormat.format(Date(entry.start)))
                    .put("end", dateFormat.format(Date(entry.end))),
            ),
        )
        if (entry.category.isNotBlank()) {
            put("Категория", JSONObject().put("select", JSONObject().put("name", entry.category)))
        }
        put("Клиент", JSONObject().put("rich_text", textArray(entry.client)))
        put("Минуты", JSONObject().put("number", entry.durationMin()))
        if (entry.useful > 0) put("Полезность", JSONObject().put("number", entry.useful))
        if (entry.pomodoros > 0) put("Помидоры", JSONObject().put("number", entry.pomodoros))
        put("Надиктовано", JSONObject().put("rich_text", textArray(entry.raw.take(1900))))
        put("EntryId", JSONObject().put("rich_text", textArray(entry.id.toString())))
    }

    private fun textArray(text: String): JSONArray =
        if (text.isBlank()) JSONArray()
        else JSONArray().put(
            JSONObject().put("text", JSONObject().put("content", text))
        )

    /** Page id holding this EntryId, or null - the upsert key. */
    private fun findPage(token: String, db: String, entryId: Long): String? {
        val body = JSONObject().put(
            "filter",
            JSONObject()
                .put("property", "EntryId")
                .put("rich_text", JSONObject().put("equals", entryId.toString())),
        )
        val reply = post("$API/databases/$db/query", token, body)
        return reply.optJSONArray("results")?.optJSONObject(0)?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }

    private fun post(url: String, token: String, body: JSONObject): JSONObject =
        execute(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType())),
            token,
        )

    private fun patch(url: String, token: String, body: JSONObject): JSONObject =
        execute(
            Request.Builder()
                .url(url)
                .patch(body.toString().toRequestBody("application/json".toMediaType())),
            token,
        )

    private fun execute(builder: Request.Builder, token: String): JSONObject {
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", VERSION)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw java.io.IOException(
                    "Notion HTTP ${response.code}" +
                        (message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" } ?: "")
                )
            }
            return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis()))
}
