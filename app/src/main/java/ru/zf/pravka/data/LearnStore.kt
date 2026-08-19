package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Pending learning suggestions ("Обучить" produced them, the owner has not
// reviewed them yet). Nothing here affects requests until approved: dict
// suggestions go to the dictionary, rule suggestions go to RulesStore.
class LearnStore(private val context: Context) {

    data class Suggestion(
        val id: Long,
        val kind: String,       // "dict" | "rule"
        // dict fields
        val mode: String = "",  // HARD | PROTECT
        val from: String = "",
        val to: String = "",
        val note: String = "",
        // rule fields
        val text: String = "",
        val exampleBefore: String = "",
        val exampleAfter: String = "",
    )

    private val mutex = Mutex()
    private var loaded = false
    private val items = mutableListOf<Suggestion>()

    private fun file() = File(context.filesDir, "pravka-learn-pending.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file()
            if (!f.exists()) {
                // First run: pre-seed the owner's known preference so it is one
                // tap away instead of waiting for the learning loop to find it.
                items.add(
                    Suggestion(
                        id = 1, kind = "rule",
                        text = "Устные перечисления («во-первых, во-вторых…») в сообщениях-перечнях оформляй нумерованным списком: каждый пункт с новой строки — «1.», «2.».",
                        exampleBefore = "во-первых надо подписать договор во-вторых согласовать сроки",
                        exampleAfter = "1. Надо подписать договор.\n2. Согласовать сроки.",
                    )
                )
                persist()
                return
            }
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                items.add(
                    Suggestion(
                        id = o.optLong("id"),
                        kind = o.optString("kind"),
                        mode = o.optString("mode"),
                        from = o.optString("from"),
                        to = o.optString("to"),
                        note = o.optString("note"),
                        text = o.optString("text"),
                        exampleBefore = o.optString("before"),
                        exampleAfter = o.optString("after"),
                    )
                )
            }
        }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            items.forEach { s ->
                array.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("kind", s.kind)
                        put("mode", s.mode)
                        put("from", s.from)
                        put("to", s.to)
                        put("note", s.note)
                        put("text", s.text)
                        put("before", s.exampleBefore)
                        put("after", s.exampleAfter)
                    }
                )
            }
            file().writeText(array.toString())
        }
    }

    suspend fun all(): List<Suggestion> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); items.toList() }
    }

    /** Adds fresh suggestions, skipping duplicates already pending. Returns added count. */
    suspend fun add(fresh: List<Suggestion>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            var nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
            var added = 0
            for (s in fresh) {
                val dup = items.any {
                    it.kind == s.kind &&
                        it.from.equals(s.from, ignoreCase = true) &&
                        it.text.equals(s.text, ignoreCase = true)
                }
                if (!dup) { items.add(s.copy(id = nextId++)); added++ }
            }
            if (added > 0) persist()
            added
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (items.removeAll { it.id == id }) persist()
        }
    }
}
