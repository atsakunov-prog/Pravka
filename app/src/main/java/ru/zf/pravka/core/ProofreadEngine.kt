package ru.zf.pravka.core

import ru.zf.pravka.data.DebugLog
import ru.zf.pravka.target.TextTarget

// The single orchestrator every trigger goes through (spec section 4).
// Reads text from the target, calls the provider, cleans the reply,
// writes the result back (clipboard fallback), records the debug log.
// Dictionary hard-replacements and the undo stack arrive in later stages.
class ProofreadEngine(
    private val provider: ProofreadProvider,
    private val clipboardFallback: TextTarget,
) {

    companion object {
        const val MIN_INPUT_LENGTH = 15
    }

    sealed interface Outcome {
        /** Result written back into the field. */
        data class Applied(val result: ProofreadResult) : Outcome

        /** Could not (or was not allowed to) write back; result is in the clipboard. */
        data class CopiedToClipboard(val result: ProofreadResult) : Outcome

        /** The text was already clean. */
        data class Unchanged(val result: ProofreadResult) : Outcome

        /** Input empty or shorter than MIN_INPUT_LENGTH - refusal buzz, no request made. */
        object Rejected : Outcome

        /** Request or post-processing failed; [message] is user-facing Russian text. */
        data class Failed(val message: String) : Outcome
    }

    suspend fun proofread(target: TextTarget, mode: ProofreadMode): Outcome {
        val input = target.read()?.trim().orEmpty()
        if (input.length < MIN_INPUT_LENGTH) return Outcome.Rejected

        val rawResult = provider.proofread(input, mode).getOrElse { e ->
            val message = e.message ?: "Неизвестная ошибка"
            log(mode, input, output = "", latency = 0, provider = provider.id, error = message)
            return Outcome.Failed(message)
        }

        val cleaned = ResponseCleaner.clean(rawResult.text, input)
        if (cleaned == null) {
            log(mode, input, rawResult.text, rawResult.latencyMs, rawResult.providerId, error = "response corrupted")
            return Outcome.Failed("Модель вернула испорченный ответ, текст не тронут.")
        }

        val result = rawResult.copy(text = cleaned, changed = cleaned != input)
        log(mode, input, cleaned, result.latencyMs, result.providerId, error = null)

        if (!result.changed) return Outcome.Unchanged(result)

        return if (target.write(cleaned)) {
            Outcome.Applied(result)
        } else {
            clipboardFallback.write(cleaned)
            Outcome.CopiedToClipboard(result)
        }
    }

    private fun log(
        mode: ProofreadMode,
        input: String,
        output: String,
        latency: Long,
        provider: String,
        error: String?,
    ) {
        DebugLog.add(
            DebugLog.Entry(
                timestamp = System.currentTimeMillis(),
                mode = mode,
                providerId = provider,
                latencyMs = latency,
                input = input,
                output = output,
                appliedDictEntries = emptyList(),
                error = error,
            )
        )
    }
}
