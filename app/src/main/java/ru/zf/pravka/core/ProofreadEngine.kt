package ru.zf.pravka.core

import ru.zf.pravka.data.DebugLog
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.Stats
import ru.zf.pravka.target.TextTarget

// The single orchestrator every trigger goes through (spec section 4):
// read from the target -> apply HARD dictionary replacements -> collect the
// {DICT} hint block -> call the provider -> clean the reply -> write back
// (clipboard fallback) -> increment dictionary hits, record debug log,
// history file, usage counters, undo stack.
class ProofreadEngine(
    private val claude: ProofreadProvider,
    private val nano: ProofreadProvider,
    private val settings: ru.zf.pravka.data.Settings,
    private val clipboardFallback: TextTarget,
    private val stats: Stats,
    private val dictionary: DictionaryApplier,
    private val dictionaryStore: DictionaryStore,
    private val history: HistoryLog,
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

        val prepared = dictionary.prepare(input)

        // Owner picks the model explicitly; Nano applies to CLEAN only and
        // pushes partially corrected text back into the field as chunks
        // complete. On a Nano failure - automatic fallback to Claude
        // (spec 6.3), the result banner then shows which provider answered.
        val useNano = mode == ProofreadMode.CLEAN && settings.cleanModel() == ru.zf.pravka.data.Settings.MODEL_NANO
        val primary = if (useNano) nano else claude
        val onPartial: (suspend (String) -> Unit)? =
            if (useNano) { partial -> runCatching { target.write(partial) } } else null

        val rawResult = primary.proofread(prepared.text, mode, prepared.dictBlock, onPartial).getOrElse { primaryError ->
            if (useNano) {
                // The Nano failure must stay visible even when the fallback
                // succeeds - otherwise "why does everything go through
                // Sonnet?" is undiagnosable from the history.
                val nanoMessage = primaryError.message ?: "Nano: неизвестная ошибка"
                log(mode, input, "", 0, nano.id, prepared.firedIds, nanoMessage)
                history.append(mode.name, nano.id, "gemini-nano", 0, 0, 0, 0.0, false, input, "", "fallback to Claude: $nanoMessage")
                claude.proofread(prepared.text, mode, prepared.dictBlock).getOrElse { claudeError ->
                    val message = "Nano: ${primaryError.message}\nClaude: ${claudeError.message}"
                    log(mode, input, "", 0, "nano+claude", prepared.firedIds, message)
                    history.append(mode.name, "nano+claude", "", 0, 0, 0, 0.0, false, input, "", message)
                    stats.recordError()
                    return Outcome.Failed(message)
                }
            } else {
                val message = primaryError.message ?: "Неизвестная ошибка"
                log(mode, input, "", 0, primary.id, prepared.firedIds, message)
                history.append(mode.name, primary.id, "", 0, 0, 0, 0.0, false, input, "", message)
                stats.recordError()
                return Outcome.Failed(message)
            }
        }

        val cleaned = ResponseCleaner.clean(rawResult.text, prepared.text)
        if (cleaned == null) {
            log(mode, input, rawResult.text, rawResult.latencyMs, rawResult.providerId, prepared.firedIds, "response corrupted")
            history.append(
                mode.name, rawResult.providerId, rawResult.modelId, rawResult.latencyMs,
                rawResult.inputTokens, rawResult.outputTokens, rawResult.costUsd,
                false, input, rawResult.text, "response corrupted",
            )
            stats.recordError()
            return Outcome.Failed("Модель вернула испорченный ответ, текст не тронут.")
        }

        // "changed" compares against the ORIGINAL input: a HARD replacement
        // alone must still count as a change and be written back.
        val result = rawResult.copy(
            text = cleaned,
            changed = cleaned != input,
            appliedDictEntries = prepared.firedIds,
        )
        log(mode, input, cleaned, result.latencyMs, result.providerId, prepared.firedIds, null)
        history.append(
            mode.name, result.providerId, result.modelId, result.latencyMs,
            result.inputTokens, result.outputTokens, result.costUsd,
            result.changed, input, cleaned, null,
        )
        stats.recordSuccess(mode, result.latencyMs, input.length, result.changed, result.inputTokens, result.outputTokens, result.costUsd)
        dictionaryStore.incrementHits(prepared.firedIds)

        if (!result.changed) return Outcome.Unchanged(result)

        return if (target.write(cleaned)) {
            UndoStack.push(before = input, after = cleaned)
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
        firedIds: List<Long>,
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
                appliedDictEntries = firedIds,
                error = error,
            )
        )
    }
}
