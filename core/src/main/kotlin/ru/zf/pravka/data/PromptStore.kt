package ru.zf.pravka.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.Prompts

// Owner-editable prompt overrides (spec section 7). Factory texts stay as
// constants in Prompts.kt; the store holds only the overrides, so an APK
// update can refresh factory texts without touching the owner's edits.
//
// Хранилище у каждой платформы своё (телефон - DataStore, воркстанция -
// файл), а список промптов и заводские тексты - общие, поэтому здесь
// интерфейс, а не класс.
interface PromptStore {

    enum class PromptId(val storageKey: String) {
        CLEAN_CLAUDE("clean_claude"),
        BUSINESS("business"),
        SOFTEN("soften"),
        PROSE("prose"),
        // Meeting transcripts (Whisper on the owner's computer) - used only
        // by the "copy full prompt" button, never sent from the app itself.
        MEETING("meeting"),
        // Разноска: наговор -> дела в Todoist (Опус).
        TASKS("tasks");

        companion object {
            fun of(mode: ProofreadMode): PromptId = when (mode) {
                ProofreadMode.CLEAN -> CLEAN_CLAUDE
                ProofreadMode.BUSINESS -> BUSINESS
                ProofreadMode.SOFTEN -> SOFTEN
            }
        }
    }

    fun factory(id: PromptId): String = when (id) {
        PromptId.CLEAN_CLAUDE -> Prompts.CLEAN_CLAUDE
        PromptId.BUSINESS -> Prompts.BUSINESS
        PromptId.SOFTEN -> Prompts.SOFTEN
        PromptId.PROSE -> Prompts.PROSE
        PromptId.MEETING -> Prompts.MEETING
        PromptId.TASKS -> Prompts.TASKS
    }

    fun overrideFlow(id: PromptId): Flow<String?>

    suspend fun effective(id: PromptId): String =
        overrideFlow(id).first() ?: factory(id)

    suspend fun effective(mode: ProofreadMode): String =
        effective(PromptId.of(mode))

    suspend fun setOverride(id: PromptId, text: String)

    /** "Вернуть заводской": removes the override, factory text applies again. */
    suspend fun resetToFactory(id: PromptId)
}
