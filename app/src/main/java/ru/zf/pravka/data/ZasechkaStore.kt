package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Засечка: the owner's timesheet. One file holds everything - the entries,
// the category list and the client list - because they change together and
// a single mutex keeps them consistent. Same persistence discipline as the
// dictionary (the other irreplaceable store): atomic writes, quarantine on
// corruption, every write through the DiskWriter thread.
//
// The timeline model: entries are back-to-back by default. A new entry
// CLOSES the previous open one at its own start time, so the day reads as a
// continuous ribbon; explicit "закрыть день"/breaks just close without
// opening. end == 0 means "still going".
class ZasechkaStore(private val context: Context) {

    companion object {
        const val FORMAT = "pravka-zasechka"
        private const val FILE_NAME = "zasechka.json"

        // Starter set - fully editable in the tab. Deliberately life-wide,
        // not work-only: the owner tracks the whole day, meals included.
        val DEFAULT_CATEGORIES = listOf(
            "Встречи",
            "Контент",
            "Операционка",
            "Почта и мессенджеры",
            "Планирование",
            "Личное",
            "Семья",
            "Еда",
            "Спорт",
            "Дорога",
            "Отдых",
            "Перерыв",
        )
    }

    data class Entry(
        val id: Long,
        val start: Long,          // epoch ms
        val end: Long,            // epoch ms; 0 = the entry is still open
        val raw: String,          // what the owner actually said
        val title: String,        // short activity name (model or owner)
        val category: String,     // one of the category list ("" = unsorted)
        val client: String,       // "" when none
        val useful: Int,          // 1..5, 0 = not rated
        val source: String,       // "voice" | "text" | "edit"
        val synced: Boolean,      // delivered to the Sheets webhook
        val createdAt: Long,
    ) {
        val open: Boolean get() = end == 0L
        fun durationMin(now: Long = System.currentTimeMillis()): Long =
            (((if (open) now else end) - start).coerceAtLeast(0L)) / 60_000L
    }

    private val mutex = Mutex()
    private var loaded = false
    private var entries = mutableListOf<Entry>()
    private var categories = mutableListOf<String>()
    private var clients = mutableListOf<String>()
    private var lastId = 0L

    private val _entriesFlow = MutableStateFlow<List<Entry>>(emptyList())
    val entriesFlow: StateFlow<List<Entry>> = _entriesFlow
    private val _categoriesFlow = MutableStateFlow<List<String>>(emptyList())
    val categoriesFlow: StateFlow<List<String>> = _categoriesFlow
    private val _clientsFlow = MutableStateFlow<List<String>>(emptyList())
    val clientsFlow: StateFlow<List<String>> = _clientsFlow

    private val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun all(): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.toList()
    }

    suspend fun categories(): List<String> = mutex.withLock {
        ensureLoaded()
        categories.toList()
    }

    suspend fun clients(): List<String> = mutex.withLock {
        ensureLoaded()
        clients.toList()
    }

    suspend fun setCategories(value: List<String>): Unit = mutex.withLock {
        ensureLoaded()
        categories = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        persist()
    }

    suspend fun setClients(value: List<String>): Unit = mutex.withLock {
        ensureLoaded()
        clients = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        persist()
    }

    /** The entry currently running, if any. */
    suspend fun openEntry(): Entry? = mutex.withLock {
        ensureLoaded()
        entries.lastOrNull { it.open }
    }

    /**
     * Starts a new entry at [start] and closes the open one (if any) at that
     * same moment - the ribbon stays continuous. A retroactive start earlier
     * than the open entry's own start clamps its end to its start (a 0-minute
     * entry the owner can delete) rather than going negative.
     */
    suspend fun startEntry(
        start: Long,
        raw: String,
        title: String,
        category: String,
        client: String,
        useful: Int,
        source: String,
    ): Entry = mutex.withLock {
        ensureLoaded()
        closeOpenLocked(start)
        val entry = Entry(
            id = nextId(),
            start = start,
            end = 0L,
            raw = raw.trim(),
            title = title.trim(),
            category = category.trim(),
            client = client.trim(),
            useful = useful.coerceIn(0, 5),
            source = source,
            synced = false,
            createdAt = System.currentTimeMillis(),
        )
        entries.add(entry)
        entries.sortBy { it.start }
        persist()
        entry
    }

    /** Closes the running entry at [at]; null when nothing was open. */
    suspend fun closeOpen(at: Long): Entry? = mutex.withLock {
        ensureLoaded()
        val closed = closeOpenLocked(at)
        if (closed != null) persist()
        closed
    }

    private fun closeOpenLocked(at: Long): Entry? {
        val index = entries.indexOfLast { it.open }
        if (index < 0) return null
        val open = entries[index]
        val closed = open.copy(end = at.coerceAtLeast(open.start), synced = false)
        entries[index] = closed
        return closed
    }

    /**
     * A phone-detected interruption (attention-eater session, a call) lands
     * in the ribbon retroactively. If an open entry covers [start], it is cut
     * at [start]; with [resumePrevious] (calls) a copy of it reopens at [end]
     * - the conversation pauses the work, it does not kill it. An open entry
     * that STARTED inside the interruption wins instead: the auto entry is
     * clamped to its start and nothing is spliced (the owner spoke - the
     * owner is right).
     *
     * Returns null when an equal auto entry is already there (re-sweep).
     */
    suspend fun insertInterruption(
        start: Long,
        end: Long,
        title: String,
        category: String,
        resumePrevious: Boolean,
    ): Entry? = mutex.withLock {
        ensureLoaded()
        if (end <= start) return@withLock null
        if (entries.any {
                it.source == "auto" && it.title == title && kotlin.math.abs(it.start - start) < 60_000
            }
        ) return@withLock null
        var actualEnd = end
        var resumeTemplate: Entry? = null
        val openIndex = entries.indexOfLast { it.open }
        if (openIndex >= 0) {
            val open = entries[openIndex]
            if (open.start <= start) {
                entries[openIndex] = open.copy(end = start.coerceAtLeast(open.start), synced = false)
                if (resumePrevious) resumeTemplate = open
            } else if (open.start < end) {
                actualEnd = open.start
                if (actualEnd <= start) return@withLock null
            }
        }
        val entry = Entry(
            id = nextId(),
            start = start,
            end = actualEnd,
            raw = "",
            title = title.trim(),
            category = category.trim(),
            client = "",
            useful = 0,
            source = "auto",
            synced = false,
            createdAt = System.currentTimeMillis(),
        )
        entries.add(entry)
        resumeTemplate?.let { t ->
            entries.add(
                t.copy(
                    id = nextId(),
                    start = actualEnd,
                    end = 0L,
                    source = "auto",
                    synced = false,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        entries.sortBy { it.start }
        persist()
        entry
    }

    /** Full replace by id. Any content change makes the row sync again. */
    suspend fun update(entry: Entry): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry.copy(synced = false)
            entries.sortBy { it.start }
            persist()
        }
    }

    suspend fun delete(id: Long): Unit = mutex.withLock {
        ensureLoaded()
        if (entries.removeAll { it.id == id }) persist()
    }

    /** Entries overlapping [from, to) - for the day view and the digests. */
    suspend fun forRange(from: Long, to: Long): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.filter { e ->
            val effectiveEnd = if (e.open) Long.MAX_VALUE else e.end
            e.start < to && effectiveEnd > from
        }
    }

    /** Closed entries the Sheets mirror has not seen yet, oldest first. */
    suspend fun unsynced(): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.filter { !it.open && !it.synced }
    }

    suspend fun markSynced(ids: Collection<Long>): Unit = mutex.withLock {
        ensureLoaded()
        if (ids.isEmpty()) return@withLock
        val idSet = ids.toSet()
        entries = entries.map { if (it.id in idSet) it.copy(synced = true) else it }.toMutableList()
        persist()
    }

    // ---- CSV export (same share pattern as the transcription metrics) ----

    suspend fun shareCsvIntent(): Intent {
        val list = all()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val csv = buildString {
            append("date,start,end,minutes,title,category,client,useful,raw\n")
            for (e in list) {
                append(dateFormat.format(Date(e.start))).append(',')
                append(timeFormat.format(Date(e.start))).append(',')
                append(if (e.open) "" else timeFormat.format(Date(e.end))).append(',')
                append(if (e.open) "" else e.durationMin().toString()).append(',')
                append(csvEscape(e.title)).append(',')
                append(csvEscape(e.category)).append(',')
                append(csvEscape(e.client)).append(',')
                append(if (e.useful > 0) e.useful.toString() else "").append(',')
                append(csvEscape(e.raw)).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-zasechka.csv")
        withContext(Dispatchers.IO) { out.writeText(csv) }
        return shareFileIntent(context, out, "text/csv")
    }

    private fun csvEscape(s: String): String {
        if (s.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return s
        return "\"" + s.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + "\""
    }

    // ---- persistence ----

    // Ids must stay unique across process restarts; wall-clock ms is unique
    // for a single human, and the max() guard survives a clock step back.
    private fun nextId(): Long {
        val id = System.currentTimeMillis().coerceAtLeast(lastId + 1)
        lastId = id
        return id
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val root = StoreFiles.readOrQuarantine(file) { JSONObject(it) }
            categories = root?.optJSONArray("categories")?.toStringList()
                ?.toMutableList() ?: DEFAULT_CATEGORIES.toMutableList()
            clients = root?.optJSONArray("clients")?.toStringList()?.toMutableList() ?: mutableListOf()
            entries = mutableListOf()
            root?.optJSONArray("entries")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val start = o.optLong("start", 0)
                    if (start <= 0) continue
                    entries.add(
                        Entry(
                            id = o.optLong("id", 0),
                            start = start,
                            end = o.optLong("end", 0),
                            raw = o.optString("raw", ""),
                            title = o.optString("title", ""),
                            category = o.optString("category", ""),
                            client = o.optString("client", ""),
                            useful = o.optInt("useful", 0),
                            source = o.optString("source", "voice"),
                            synced = o.optBoolean("synced", false),
                            createdAt = o.optLong("createdAt", start),
                        )
                    )
                }
            }
            entries.sortBy { it.start }
            lastId = entries.maxOfOrNull { it.id } ?: 0L
            if (root == null) persistQueued()
        }
        loaded = true
        publish()
    }

    private fun publish() {
        _entriesFlow.value = entries.toList()
        _categoriesFlow.value = categories.toList()
        _clientsFlow.value = clients.toList()
    }

    private fun persist() {
        persistQueued()
        publish()
    }

    private fun persistQueued() {
        val json = toJson().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", 1)
        put("categories", JSONArray(categories))
        put("clients", JSONArray(clients))
        put(
            "entries",
            JSONArray().apply {
                for (e in entries) {
                    put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("start", e.start)
                            put("end", e.end)
                            put("raw", e.raw)
                            put("title", e.title)
                            put("category", e.category)
                            put("client", e.client)
                            put("useful", e.useful)
                            put("source", e.source)
                            put("synced", e.synced)
                            put("createdAt", e.createdAt)
                        }
                    )
                }
            }
        )
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

/** Start of the local calendar day containing [at]. */
fun dayStartMs(at: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = at
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
