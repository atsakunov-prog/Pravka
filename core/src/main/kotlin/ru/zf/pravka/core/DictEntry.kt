package ru.zf.pravka.core

enum class DictMode { HARD, HINT, PROTECT }

data class DictEntry(
    val id: Long = 0,
    val from: String,          // what speech recognition produces
    val to: String = "",       // what it should be; empty for PROTECT
    val mode: DictMode,
    val note: String = "",     // explanation, goes into the prompt for HINT
    val hits: Int = 0,         // how many times the entry fired
    val enabled: Boolean = true,
    val createdAt: Long,
)
