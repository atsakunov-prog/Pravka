package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.Prompts

private val Context.promptDataStore by preferencesDataStore(name = "prompts")

// Owner-editable prompt overrides (spec section 7). Factory texts stay as
// constants in Prompts.kt; DataStore holds only the overrides, so an APK
// update can refresh factory texts without touching the owner's edits.
class PromptStore(private val context: Context) {

    enum class PromptId(val storageKey: String) {
        CLEAN_CLAUDE("clean_claude"),
        BUSINESS("business"),
        SOFTEN("soften"),
        PROSE("prose"),
        // Meeting transcripts (Whisper on the owner's computer) - used only
        // by the "copy full prompt" button, never sent from the app itself.
        MEETING("meeting"),
        // Разноска: наговор -> дела в Todoist (Опус).
        TASKS("tasks"),
        // Еда: сказанное -> КБЖУ (Сонет).
        FOOD("food"),
        // Спорт: вопрос по своим тренировкам (Опус).
        COACH("coach"),
        // Тренер-консультант: короткий вопрос про упражнение (Сонет).
        TRAINER("trainer"),
        // Тело: один микрофон на подходы, еду, зарядку и вопросы (Сонет).
        BODY("body"),
        // Правила блока: проза Notion -> числа (Сонет).
        RULES("rules"),
        // Паттерны: ночной поиск повторов по всему логу (Опус). Разборы
        // владелец делает сам в чате — здесь только охота за повторами.
        PATTERNS("patterns"),
        // Запрос, который владелец копирует в чат вместе с выгрузкой CSV:
        // туда подставляются его подтверждённые и отклонённые паттерны.
        CHAT_HANDOFF("chat_handoff");

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
        PromptId.FOOD -> Prompts.FOOD
        PromptId.COACH -> Prompts.COACH
        PromptId.TRAINER -> Prompts.TRAINER
        PromptId.BODY -> Prompts.BODY
        PromptId.RULES -> Prompts.RULES
        PromptId.PATTERNS -> Prompts.PATTERNS
        PromptId.CHAT_HANDOFF -> Prompts.CHAT_HANDOFF
    }

    fun overrideFlow(id: PromptId): Flow<String?> =
        context.promptDataStore.data.map { it[stringPreferencesKey(id.storageKey)] }

    suspend fun effective(id: PromptId): String =
        overrideFlow(id).first() ?: factory(id)

    suspend fun effective(mode: ProofreadMode): String =
        effective(PromptId.of(mode))

    suspend fun setOverride(id: PromptId, text: String) {
        context.promptDataStore.edit { it[stringPreferencesKey(id.storageKey)] = text }
    }

    /** "Вернуть заводской": removes the override, factory text applies again. */
    suspend fun resetToFactory(id: PromptId) {
        context.promptDataStore.edit { it.remove(stringPreferencesKey(id.storageKey)) }
    }
}
