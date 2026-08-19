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
                    }
                )
            }
            file().writeText(array.toString())
        }
    }

    suspend fun all(): List<Rule> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); rules.toList() }
    }

    suspend fun add(text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val t = text.trim()
            if (t.isNotEmpty() && rules.none { it.text.equals(t, ignoreCase = true) }) {
                val id = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                rules.add(Rule(id, t, enabled = true, createdTs = System.currentTimeMillis()))
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
                if (used + r.text.length > 1500) break
                sb.append("- ").append(r.text).append('\n')
                used += r.text.length
            }
            sb.toString().trim()
        }
    }
}
