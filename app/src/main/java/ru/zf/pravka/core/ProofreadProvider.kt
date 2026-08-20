package ru.zf.pravka.core

interface ProofreadProvider {
    val id: String

    // dictBlock is the pre-filtered {DICT} content assembled by the engine
    // (only entries that actually occur in the input, spec 7.3).
    // onDelta, when set, receives the ACCUMULATED reply text as it streams in -
    // display-only (the ticker); the final write still happens once, from the
    // returned result.
    // directive: extra task on top of the fix (style modes, redo chips).
    // contextBefore: read-only text standing before a mid-field insert.
    // conversationContext: the owner's previous takes in the same chat -
    //   separate from contextBefore because its INSTRUCTION differs (tone,
    //   gender, referents - not seam punctuation).
    // modelOverride: redo chips run on a stronger model.
    suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String = "",
        onDelta: ((String) -> Unit)? = null,
        directive: String = "",
        contextBefore: String = "",
        modelOverride: String? = null,
        conversationContext: String = "",
    ): Result<ProofreadResult>
}
