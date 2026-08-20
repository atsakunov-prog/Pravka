package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The golden set for prompt evaluation: dictated inputs with reference
// outputs. Every prompt change is measured against this instead of eyeballed.
// Items come from the owner's own history (curated by deleting bad ones) or
// from an imported JSONL ({"input": ..., "expected": ...} per line).
class EvalStore(private val context: Context) {

    data class Item(val id: Long, val input: String, val expected: String)

    data class ResultRow(val id: Long, val score: Double, val actual: String)

    private val mutex = Mutex()
    private var loaded = false
    private val items = mutableListOf<Item>()

    private fun file() = File(context.filesDir, "pravka-eval.jsonl")
    private fun resultsFile() = File(context.filesDir, "pravka-eval-results.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // JSONL is line-resilient (a torn line drops alone), but the write
        // must still be atomic so a kill can't truncate the whole set.
        runCatching {
            val f = file()
            if (!f.exists()) return
            f.readLines().forEach { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val input = o.optString("input")
                if (input.isBlank()) return@forEach
                items.add(Item(o.optLong("id"), input, o.optString("expected")))
            }
        }
    }

    private fun persist() {
        runCatching {
            StoreFiles.writeAtomic(
                file(),
                items.joinToString("\n") { i ->
                    JSONObject().put("id", i.id).put("input", i.input).put("expected", i.expected).toString()
                } + if (items.isEmpty()) "" else "\n"
            )
        }
    }

    suspend fun all(): List<Item> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); items.toList() }
    }

    /** Adds pairs, deduplicating by input text. Returns how many were new. */
    suspend fun addAll(pairs: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val known = items.map { it.input.trim() }.toHashSet()
            var nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
            var added = 0
            for ((input, expected) in pairs) {
                if (input.isBlank() || input.trim() in known) continue
                items.add(Item(nextId++, input, expected))
                known.add(input.trim())
                added++
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

    /** Imports the owner's JSONL: {"input": ..., "expected": ...} per line. */
    suspend fun importJsonl(text: String): Int {
        val pairs = text.lineSequence()
            .mapNotNull { line -> runCatching { JSONObject(line.trim()) }.getOrNull() }
            .map { it.optString("input") to it.optString("expected") }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toList()
        return addAll(pairs)
    }

    // ---- last run results ----

    fun saveRun(promptLabel: String, avgScore: Double, exact: Int, total: Int, rows: List<ResultRow>) {
        runCatching {
            val o = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("prompt", promptLabel)
                put("avg", avgScore)
                put("exact", exact)
                put("total", total)
                put("rows", org.json.JSONArray().also { arr ->
                    rows.forEach { r ->
                        arr.put(JSONObject().put("id", r.id).put("score", r.score).put("actual", r.actual.take(1000)))
                    }
                })
            }
            StoreFiles.writeAtomic(resultsFile(), o.toString())
        }
    }

    fun lastRun(): JSONObject? = runCatching {
        resultsFile().takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    }.getOrNull()
}
