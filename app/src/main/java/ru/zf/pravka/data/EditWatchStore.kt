package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// The auto-capture side of learning (owner's design): after a dictation is
// cleaned and inserted, the service watches the field. If the owner hand-edits
// the text, the last seen version is remembered here; ripe entries (edited,
// different, quiet for a while) are batch-analyzed by Opus and become pending
// learning suggestions. In-flight data, capped and self-pruning.
class EditWatchStore(private val context: Context) {

    data class Entry(
        val id: Long,
        val pkg: String,
        val dictated: String,
        val cleaned: String,
        val lastSeen: String,
        val createdTs: Long,
        val editedTs: Long,
    )

    private val mutex = Mutex()
    private var loaded = false
    private val entries = mutableListOf<Entry>()

    private fun file() = File(context.filesDir, "pravka-edit-watch.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file()
            if (!f.exists()) return
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                entries.add(
                    Entry(
                        id = o.optLong("id"),
                        pkg = o.optString("pkg"),
                        dictated = o.optString("dictated"),
                        cleaned = o.optString("cleaned"),
                        lastSeen = o.optString("lastSeen"),
                        createdTs = o.optLong("created"),
                        editedTs = o.optLong("edited"),
                    )
                )
            }
        }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            entries.forEach { e ->
                array.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("pkg", e.pkg)
                        put("dictated", e.dictated)
                        put("cleaned", e.cleaned)
                        put("lastSeen", e.lastSeen)
                        put("created", e.createdTs)
                        put("edited", e.editedTs)
                    }
                )
            }
            file().writeText(array.toString())
        }
    }

    /** Registers a freshly delivered fix to watch. */
    suspend fun watch(pkg: String, dictated: String, cleaned: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            prune()
            val id = (entries.maxOfOrNull { it.id } ?: 0L) + 1
            entries.add(
                Entry(
                    id = id, pkg = pkg,
                    dictated = dictated.take(2000), cleaned = cleaned.take(2000),
                    lastSeen = cleaned.take(2000),
                    createdTs = System.currentTimeMillis(), editedTs = 0L,
                )
            )
            while (entries.size > 20) entries.removeFirst()
            persist()
        }
    }

    /**
     * The owner typed in [pkg]; [current] is the field text now. Updates the
     * newest watch entry this text is recognizably an edit of.
     * [overlap] scores similarity in [0..1].
     */
    /** Returns true when this call detected the FIRST real edit of an entry. */
    suspend fun onFieldText(
        pkg: String,
        current: String,
        overlap: (String, String) -> Double,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val now = System.currentTimeMillis()
            val candidate = entries.lastOrNull {
                it.pkg == pkg && now - it.createdTs < WATCH_WINDOW_MS && overlap(it.cleaned, current) > 0.4
            } ?: return@withLock false
            if (candidate.lastSeen == current) return@withLock false
            val i = entries.indexOf(candidate)
            // Our own delivery echoes back as a TEXT_CHANGED with the cleaned
            // fragment embedded in the field's other text - that is a baseline,
            // not an owner's edit. Absorb it without arming the entry.
            if (candidate.editedTs == 0L && current.contains(candidate.cleaned.trim())) {
                // Re-baseline BOTH sides: the later Opus comparison must see
                // like-for-like (field-with-our-fragment vs field-after-edits).
                entries[i] = candidate.copy(cleaned = current.take(2000), lastSeen = current.take(2000))
                persist()
                return@withLock false
            }
            val firstEdit = candidate.editedTs == 0L
            entries[i] = candidate.copy(lastSeen = current.take(2000), editedTs = now)
            persist()
            firstEdit
        }
    }

    /** Live snapshot for the learning status card. */
    suspend fun all(): List<Entry> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); entries.toList() }
    }

    /**
     * Entries ready for the batch analysis: hand-edited, actually different
     * from what we produced, and quiet for at least [quietMs]. Read-only -
     * call [remove] after a SUCCESSFUL analysis so a failed one loses nothing.
     */
    suspend fun ripe(quietMs: Long): List<Entry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val now = System.currentTimeMillis()
            entries.filter {
                it.editedTs > 0 && now - it.editedTs > quietMs &&
                    it.lastSeen.trim() != it.cleaned.trim()
            }
        }
    }

    suspend fun remove(ids: List<Long>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (entries.removeAll { it.id in ids }) {
                prune()
                persist()
            }
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 48L * 3600 * 1000
        entries.removeAll { it.createdTs < cutoff && it.editedTs == 0L }
    }

    companion object {
        // How long after delivery an edit still counts as "editing our text".
        const val WATCH_WINDOW_MS = 15L * 60 * 1000
    }
}
