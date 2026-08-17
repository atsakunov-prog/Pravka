package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.DictEntry
import ru.zf.pravka.core.DictMode

// The dictionary is the most valuable data in the app - it must survive
// APK updates and be trivially exportable. Stored as a human-readable JSON
// file in the app's private storage (same format as export/import), written
// atomically. Deviation from spec section 4 (Room): a JSON file IS the
// exchange format the owner already works with, one storage instead of two.
class DictionaryStore(private val context: Context) {

    companion object {
        const val FORMAT = "pravka-dictionary"
        private const val FILE_NAME = "dictionary.json"
        private const val SEED_ASSET = "dictionary_seed.json"
    }

    private val mutex = Mutex()
    private var loaded = false
    private var entries = mutableListOf<DictEntry>()
    private var nextId = 1L
    private var seedVersion = 1

    private val _entriesFlow = MutableStateFlow<List<DictEntry>>(emptyList())
    val entriesFlow: StateFlow<List<DictEntry>> = _entriesFlow

    private val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun all(): List<DictEntry> = mutex.withLock {
        ensureLoaded()
        entries.toList()
    }

    suspend fun add(from: String, to: String, mode: DictMode, note: String): DictEntry = mutex.withLock {
        ensureLoaded()
        val entry = DictEntry(
            id = nextId++,
            from = from.trim(),
            to = to.trim(),
            mode = mode,
            note = note.trim(),
            createdAt = System.currentTimeMillis(),
        )
        entries.add(entry)
        persist()
        entry
    }

    suspend fun update(entry: DictEntry): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry
            persist()
        }
    }

    suspend fun delete(id: Long): Unit = mutex.withLock {
        ensureLoaded()
        if (entries.removeAll { it.id == id }) persist()
    }

    suspend fun incrementHits(ids: Collection<Long>): Unit = mutex.withLock {
        ensureLoaded()
        if (ids.isEmpty()) return@withLock
        val idSet = ids.toSet()
        entries = entries.map { e ->
            if (e.id in idSet) e.copy(hits = e.hits + 1) else e
        }.toMutableList()
        // A hit counter is cosmetic: serialize under the mutex, but let the
        // file write happen on the DiskWriter thread instead of holding the
        // hot-path caller (and the mutex) through a full-dictionary rewrite.
        val json = toJson(entries).toString(2)
        _entriesFlow.value = entries.toList()
        DiskWriter.post {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    suspend fun exportJson(): String = mutex.withLock {
        ensureLoaded()
        toJson(entries).toString(2)
    }

    /** Adds entries missing locally (matched by from+mode). Returns how many were added. */
    suspend fun importJson(text: String): Int = mutex.withLock {
        ensureLoaded()
        val incoming = parseEntries(JSONObject(text))
        val existing = entries.map { it.from.lowercase() to it.mode }.toHashSet()
        var added = 0
        for (e in incoming) {
            if ((e.from.lowercase() to e.mode) !in existing) {
                entries.add(e.copy(id = nextId++, createdAt = System.currentTimeMillis()))
                added++
            }
        }
        if (added > 0) persist()
        added
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val seedRoot = runCatching {
                JSONObject(context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() })
            }.getOrNull()
            val assetSeedVersion = seedRoot?.optInt("seedVersion", 1) ?: 1

            val fileRoot = if (file.exists()) {
                runCatching { JSONObject(file.readText()) }.getOrNull()
            } else null

            entries = (fileRoot ?: seedRoot)
                ?.let { runCatching { parseEntries(it) }.getOrNull() }
                .orEmpty()
                .mapIndexed { i, e -> if (e.id == 0L) e.copy(id = (i + 1).toLong()) else e }
                .toMutableList()
            nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
            seedVersion = fileRoot?.optInt("seedVersion", 1) ?: assetSeedVersion

            // New factory words must reach existing installs too: when the
            // bundled seed is newer than what this dictionary last saw, merge
            // the entries missing locally (deleted-by-owner entries do NOT
            // resurrect unless the seed version was bumped again).
            if (fileRoot != null && seedRoot != null && assetSeedVersion > seedVersion) {
                val existing = entries.map { it.from.lowercase() to it.mode }.toHashSet()
                for (e in parseEntries(seedRoot)) {
                    if ((e.from.lowercase() to e.mode) !in existing) {
                        entries.add(e.copy(id = nextId++, createdAt = System.currentTimeMillis()))
                    }
                }
                seedVersion = assetSeedVersion
                persistBlocking()
            }
            if (fileRoot == null) {
                seedVersion = assetSeedVersion
                persistBlocking()
            }
        }
        loaded = true
        _entriesFlow.value = entries.toList()
    }

    private suspend fun persist() {
        withContext(Dispatchers.IO) { persistBlocking() }
        _entriesFlow.value = entries.toList()
    }

    private fun persistBlocking() {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(toJson(entries).toString(2))
        tmp.renameTo(file)
    }

    private fun parseEntries(root: JSONObject): MutableList<DictEntry> {
        val array = root.getJSONArray("entries")
        val result = mutableListOf<DictEntry>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val from = o.optString("from").trim()
            if (from.isEmpty()) continue
            val mode = runCatching { DictMode.valueOf(o.optString("mode", "HARD")) }.getOrNull() ?: continue
            result.add(
                DictEntry(
                    id = o.optLong("id", 0),
                    from = from,
                    to = o.optString("to", "").trim(),
                    mode = mode,
                    note = o.optString("note", "").trim(),
                    hits = o.optInt("hits", 0),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            )
        }
        return result
    }

    private fun toJson(list: List<DictEntry>): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", 1)
        put("seedVersion", seedVersion)
        put(
            "entries",
            JSONArray().apply {
                for (e in list) {
                    put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("from", e.from)
                            put("to", e.to)
                            put("mode", e.mode.name)
                            put("note", e.note)
                            put("hits", e.hits)
                            put("enabled", e.enabled)
                            put("createdAt", e.createdAt)
                        }
                    )
                }
            }
        )
    }
}
