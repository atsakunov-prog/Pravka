package ru.zf.pravka.core

interface ProofreadProvider {
    val id: String

    // dictBlock is the pre-filtered {DICT} content assembled by the engine
    // (only entries that actually occur in the input, spec 7.3).
    // onDelta, when set, receives the ACCUMULATED reply text as it streams in -
    // display-only (the ticker); the final write still happens once, from the
    // returned result.
    suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String = "",
        onDelta: ((String) -> Unit)? = null,
    ): Result<ProofreadResult>
}
