package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Learned rules: short imperative instructions the owner APPROVED after a
// "Обучить" round (his edits vs our output, analyzed by Opus). Enabled rules
// ride into every CLEAN request as a permanent block in the uncached slot -
// this is how the model "remembers" the owner's systematic preferences.
class RulesStore(private val context: Context) {

    data class Rule(
        val id: Long,
        val text: String,
        val enabled: Boolean = true,
        val createdTs: Long = 0L,
        // A mini before/after from the owner's actual edit: few-shot for the
        // model, and lets the owner judge what the rule really does.
        val exampleBefore: String = "",
        val exampleAfter: String = "",
    )

    private val mutex = Mutex()
    private var loaded = false
    private val rules = mutableListOf<Rule>()

    private fun file() = File(context.filesDir, "pravka-rules.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file()
            if (!f.exists()) return
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                rules.add(
                    Rule(
                        id = o.optLong("id"),
                        text = text,
                        enabled = o.optBoolean("enabled", true),
                        createdTs = o.optLong("created"),
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
            rules.forEach { r ->
                array.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("text", r.text)
                        put("enabled", r.enabled)
                        put("created", r.createdTs)
                        put("before", r.exampleBefore)
                        put("after", r.exampleAfter)
                    }
                )
            }
            file().writeText(array.toString())
        }
    }

    suspend fun all(): List<Rule> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); rules.toList() }
    }

    suspend fun add(text: String, exampleBefore: String = "", exampleAfter: String = "") =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val t = text.trim()
                if (t.isNotEmpty() && rules.none { it.text.equals(t, ignoreCase = true) }) {
                    val id = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                    rules.add(
                        Rule(
                            id, t, enabled = true, createdTs = System.currentTimeMillis(),
                            exampleBefore = exampleBefore.take(160), exampleAfter = exampleAfter.take(160),
                        )
                    )
                    persist()
                }
            }
        }

    suspend fun setEnabled(id: Long, on: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = rules.indexOfFirst { it.id == id }
            if (i >= 0) { rules[i] = rules[i].copy(enabled = on); persist() }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (rules.removeAll { it.id == id }) persist()
        }
    }

    /**
     * The prompt block of enabled rules (empty string when none). Capped so a
     * long-lived collection can't crowd out the actual text.
     */
    suspend fun enabledBlock(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val active = rules.filter { it.enabled }
            if (active.isEmpty()) return@withLock ""
            val sb = StringBuilder("Постоянные правила владельца (соблюдай):\n")
            var used = 0
            for (r in active) {
                val cost = r.text.length + r.exampleBefore.length + r.exampleAfter.length
                if (used + cost > 2000) break
                sb.append("- ").append(r.text).append('\n')
                if (r.exampleBefore.isNotBlank() && r.exampleAfter.isNotBlank()) {
                    sb.append("  Пример: «").append(r.exampleBefore).append("» → «")
                        .append(r.exampleAfter).append("»\n")
                }
                used += cost
            }
            sb.toString().trim()
        }
    }
}
