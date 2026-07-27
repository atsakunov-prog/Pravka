package ru.zf.pravka.data

import ru.zf.pravka.core.ProofreadMode

// In-process ring buffer of the last 20 proofread operations.
// Deliberately not persisted (spec section 14): dies with the process.
object DebugLog {

    const val CAPACITY = 20

    data class Entry(
        val timestamp: Long,
        val mode: ProofreadMode,
        val providerId: String,
        val latencyMs: Long,
        val input: String,
        val output: String,
        val appliedDictEntries: List<Long>,
        val error: String? = null,
    )

    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun add(entry: Entry) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()
}
