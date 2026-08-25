package ru.zf.pravka.data

import java.io.File
import java.util.UUID
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
class DictionaryStore(
    private val dir: File,
    // Заводской словарь: на телефоне это asset, на воркстанции - ресурс рядом
    // с приложением. Читается лениво и только когда файла ещё нет.
    private val seedJson: () -> String? = { null },
) {

    companion object {
        const val FORMAT = "pravka-dictionary"
        /** Сколько дней держим надгробия, чтобы удаление точно доехало. */
        const val TOMBSTONE_DAYS = 90L
        private const val FILE_NAME = "dictionary.json"
        const val SEED_ASSET = "dictionary_seed.json"
    }

    private val mutex = Mutex()
    private var loaded = false
    private var entries = mutableListOf<DictEntry>()
    private var nextId = 1L
    private var seedVersion = 1

    private val _entriesFlow = MutableStateFlow<List<DictEntry>>(emptyList())
    val entriesFlow: StateFlow<List<DictEntry>> = _entriesFlow

    private val file: File get() = File(dir, FILE_NAME)

    /** Живые записи: удалённые (надгробия) наружу не показываются. */
    suspend fun all(): List<DictEntry> = mutex.withLock {
        ensureLoaded()
        entries.filter { !it.deleted }
    }

    /** Всё, включая надгробия, - это нужно только синхронизации. */
    suspend fun allForSync(): List<DictEntry> = mutex.withLock {
        ensureLoaded()
        entries.toList()
    }

    suspend fun add(from: String, to: String, mode: DictMode, note: String): DictEntry = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val entry = DictEntry(
            id = nextId++,
            uid = UUID.randomUUID().toString(),
            from = from.trim(),
            to = to.trim(),
            mode = mode,
            note = note.trim(),
            createdAt = now,
            updatedAt = now,
        )
        entries.add(entry)
        persist()
        entry
    }

    suspend fun update(entry: DictEntry): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry.copy(updatedAt = System.currentTimeMillis())
            persist()
        }
    }

    /**
     * Удаление - это надгробие, а не пропажа строки: иначе второе устройство
     * при следующей синхронизации вернуло бы запись обратно. Совсем из файла
     * надгробия уходят через TOMBSTONE_DAYS дней (см. ensureLoaded).
     */
    suspend fun delete(id: Long): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            entries[index] = entries[index].copy(deleted = true, updatedAt = System.currentTimeMillis())
            persist()
        }
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
        persist()
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
            val seedRoot = runCatching { seedJson()?.let { JSONObject(it) } }.getOrNull()
            val assetSeedVersion = seedRoot?.optInt("seedVersion", 1) ?: 1

            // A corrupt dictionary quarantines to .corrupt instead of staying
            // in place: the reseed below then can't overwrite the owner's data.
            val fileRoot = StoreFiles.readOrQuarantine(file) { JSONObject(it) }

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
                persistQueued()
            }
            if (fileRoot == null) {
                seedVersion = assetSeedVersion
                persistQueued()
            }

            // Устойчивые идентификаторы для записей, созданных до
            // синхронизации, и уборка старых надгробий - один раз при загрузке.
            var touched = false
            entries = entries.map { e ->
                if (e.uid.isBlank()) {
                    touched = true
                    e.copy(uid = java.util.UUID.randomUUID().toString())
                } else e
            }.toMutableList()
            val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 24 * 60 * 60 * 1000
            if (entries.removeAll { it.deleted && it.updatedAt < cutoff }) touched = true
            if (touched) persistQueued()
        }
        loaded = true
        publish()
    }

    // EVERY dictionary write goes through the single DiskWriter thread, with
    // the JSON serialized while the mutex is still held: state order == write
    // order, and two writers can never interleave on the tmp file. (The old
    // split - persistBlocking on Dispatchers.IO racing incrementHits' posted
    // write - was the one way to corrupt this file.)
    private fun persist() {
        persistQueued()
        publish()
    }

    // Наружу - только живые записи: надгробия существуют ради синхронизации,
    // и им нечего делать ни в списке словаря, ни в подсказке модели.
    private fun publish() {
        _entriesFlow.value = entries.filter { !it.deleted }
    }

    /**
     * Слияние с тем, что приехало из общей таблицы. Спор решается временем
     * правки: чья запись новее, та и права. Совпадения ищутся сперва по uid,
     * а если его нет - по паре "слышится + режим": так словарь телефона и
     * заводской словарь воркстанции сходятся, а не удваиваются.
     *
     * Счётчик срабатываний берём наибольший: он косметический, а складывать
     * приросты значило бы вести на каждом устройстве ещё и учёт отправленного.
     *
     * @return сколько записей изменилось локально.
     */
    suspend fun mergeFromSync(incoming: List<DictEntry>): Int = mutex.withLock {
        ensureLoaded()
        var changed = 0
        for (remote in incoming) {
            if (remote.from.isBlank() && !remote.deleted) continue
            val index = entries.indexOfFirst {
                (remote.uid.isNotBlank() && it.uid == remote.uid) ||
                    (it.from.equals(remote.from, ignoreCase = true) && it.mode == remote.mode)
            }
            if (index < 0) {
                if (remote.deleted) continue  // надгробие записи, которой у нас и не было
                entries.add(
                    remote.copy(
                        id = nextId++,
                        uid = remote.uid.ifBlank { java.util.UUID.randomUUID().toString() },
                    )
                )
                changed++
                continue
            }
            val local = entries[index]
            if (remote.updatedAt <= local.updatedAt) {
                // Наша версия свежее - но uid надо свести, иначе следующая
                // синхронизация опять будет искать по названию.
                if (local.uid != remote.uid && remote.uid.isNotBlank() && remote.updatedAt > 0) {
                    entries[index] = local.copy(uid = remote.uid)
                    changed++
                }
                continue
            }
            entries[index] = remote.copy(
                id = local.id,
                uid = remote.uid.ifBlank { local.uid },
                hits = maxOf(local.hits, remote.hits),
            )
            changed++
        }
        if (changed > 0) persist()
        changed
    }

    private fun persistQueued() {
        val json = toJson(entries).toString(2)
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
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
                    uid = o.optString("uid", ""),
                    from = from,
                    to = o.optString("to", "").trim(),
                    mode = mode,
                    note = o.optString("note", "").trim(),
                    hits = o.optInt("hits", 0),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0L)),
                    deleted = o.optBoolean("deleted", false),
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
                            put("uid", e.uid)
                            put("from", e.from)
                            put("to", e.to)
                            put("mode", e.mode.name)
                            put("note", e.note)
                            put("hits", e.hits)
                            put("enabled", e.enabled)
                            put("createdAt", e.createdAt)
                            put("updatedAt", e.updatedAt)
                            if (e.deleted) put("deleted", true)
                        }
                    )
                }
            }
        )
    }
}
