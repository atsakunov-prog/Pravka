package ru.zf.pravka.core

interface ProofreadProvider {
    val id: String
    suspend fun isAvailable(): Boolean

    // dictBlock is the pre-filtered {DICT} content assembled by the engine
    // (only entries that actually occur in the input, spec 7.3).
    // onPartial, when provided, receives progressively corrected versions of
    // the FULL text (corrected prefix + raw remainder) as chunks complete -
    // used by chunking providers for live replacement in the field.
    suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String = "",
        onPartial: (suspend (String) -> Unit)? = null,
    ): Result<ProofreadResult>
}
