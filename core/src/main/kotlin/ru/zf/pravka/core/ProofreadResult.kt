package ru.zf.pravka.core

data class ProofreadResult(
    val text: String,
    val providerId: String,
    val latencyMs: Long,
    val changed: Boolean,
    val appliedDictEntries: List<Long>,  // ids of dictionary entries that fired
    val modelId: String = "",
    val inputTokens: Int = 0,  // total: uncached + cache writes + cache reads
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
)
