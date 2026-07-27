package ru.zf.pravka.core

interface ProofreadProvider {
    val id: String
    suspend fun isAvailable(): Boolean

    // dictBlock is the pre-filtered {DICT} content assembled by the engine
    // (only entries that actually occur in the input, spec 7.3).
    suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String = "",
    ): Result<ProofreadResult>
}
