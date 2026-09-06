package ru.zf.pravka.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.Stats
import ru.zf.pravka.target.TextTarget

// The single orchestrator every trigger goes through (spec section 4):
// read from the target -> apply HARD dictionary replacements -> collect the
// {DICT} hint block -> call the provider -> clean the reply -> write back
// (clipboard fallback) -> increment dictionary hits, history file,
// usage counters, undo stack.
class ProofreadEngine(
    private val claude: ProofreadProvider,
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

    suspend fun proofread(
        target: TextTarget,
        mode: ProofreadMode,
        onDelta: ((String) -> Unit)? = null,
        directive: String = "",
        strong: Boolean = false,
        // Recent takes from the same conversation (owner's request): read-only
        // context so a reply keeps the thread's tone and referents.
        conversationContext: String = "",
    ): Outcome {
        val input = target.read()?.trim().orEmpty()
        // A deliberately selected fragment may be a single word - the
        // minimum-length guard only protects against accidental triggers.
        if (input.isEmpty()) return Outcome.Rejected
        if (input.length < MIN_INPUT_LENGTH && !target.isExplicitFragment()) return Outcome.Rejected

        val prepared = dictionary.prepare(input)

        // One provider; the model is the owner's setting (Sonnet by default -
        // Haiku simplified too much and the Nano experiment was a dead end).
        val rawResult = claude.proofread(
            prepared.text, mode, prepared.dictBlock, onDelta,
            directive = directive,
            // Two different envelopes downstream: field context carries the
            // "seam punctuation" instruction, conversation context the
            // "tone/gender/referents" one. Merging them (the old way) put the
            // conversation under the seam instruction and neutered it.
            contextBefore = target.contextBefore(),
            strong = strong,
            conversationContext = conversationContext,
        ).getOrElse { error ->
            val message = error.message ?: "Неизвестная ошибка"
            history.append(mode.name, claude.id, "", 0, 0, 0, 0.0, false, input, "", message)
            stats.recordError()
            return Outcome.Failed(message)
        }

        // Directive rewrites legally move length far outside the sanity gate.
        val cleaned = ResponseCleaner.clean(rawResult.text, prepared.text, lenient = directive.isNotBlank())
        if (cleaned == null) {
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
        // Deliver the text FIRST - the user is waiting on it - then journal.
        // Journaling used to sit between the network reply and the field write,
        // adding disk latency at exactly the wrong moment.
        val outcome: Outcome = when {
            !result.changed -> Outcome.Unchanged(result)
            target.write(cleaned) -> {
                val (undoBefore, undoAfter) = target.undoPair(input, cleaned)
                UndoStack.push(before = undoBefore, after = undoAfter)
                // Owner's request: every delivered result also lands on the
                // clipboard - insurance against a flaky field write and a free
                // way to paste the same text elsewhere.
                clipboardFallback.write(cleaned)
                Outcome.Applied(result)
            }
            else -> {
                clipboardFallback.write(cleaned)
                Outcome.CopiedToClipboard(result)
            }
        }

        history.append(
            mode.name, result.providerId, result.modelId, result.latencyMs,
            result.inputTokens, result.outputTokens, result.costUsd,
            result.changed, input, cleaned, null,
            cacheWriteTokens = result.cacheWriteTokens,
            cacheReadTokens = result.cacheReadTokens,
        )
        journalScope.launch {
            stats.recordSuccess(mode, result.latencyMs, input.length, result.changed, result.inputTokens, result.outputTokens, result.costUsd)
            dictionaryStore.incrementHits(prepared.firedIds)
        }

        return outcome
    }

    // Bookkeeping that nothing user-visible waits on.
    private val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
