package ru.zf.pravka.data

import ru.zf.pravka.core.ProofreadMode

// Счётчики расхода, которые ведёт ProofreadEngine. Реализация хранит их где
// умеет: телефон - в DataStore, воркстанция - в файле рядом с настройками.
interface UsageStats {

    suspend fun recordSuccess(
        mode: ProofreadMode,
        latencyMs: Long,
        charsIn: Int,
        changed: Boolean,
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
    )

    suspend fun recordError()

    /** Расход вспомогательных вызовов (майнер словаря, разбор наговора). */
    suspend fun recordAux(costUsd: Double, tokensIn: Int, tokensOut: Int)
}
