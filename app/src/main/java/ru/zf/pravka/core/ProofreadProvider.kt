package ru.zf.pravka.core

interface ProofreadProvider {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun proofread(input: String, mode: ProofreadMode): Result<ProofreadResult>
}
