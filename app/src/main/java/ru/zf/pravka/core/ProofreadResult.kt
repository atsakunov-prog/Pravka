package ru.zf.pravka.core

data class ProofreadResult(
    val text: String,
    val providerId: String,
    val latencyMs: Long,
    val changed: Boolean,
    val appliedDictEntries: List<Long>,  // ids of dictionary entries that fired
)
