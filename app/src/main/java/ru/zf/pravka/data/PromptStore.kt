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
        CLEAN_NANO("clean_nano"),
        BUSINESS("business"),
        SOFTEN("soften");

        companion object {
            fun of(mode: ProofreadMode, forNano: Boolean): PromptId = when (mode) {
                ProofreadMode.CLEAN -> if (forNano) CLEAN_NANO else CLEAN_CLAUDE
                ProofreadMode.BUSINESS -> BUSINESS
                ProofreadMode.SOFTEN -> SOFTEN
            }
        }
    }

    fun factory(id: PromptId): String = when (id) {
        PromptId.CLEAN_CLAUDE -> Prompts.CLEAN_CLAUDE
        PromptId.CLEAN_NANO -> Prompts.CLEAN_NANO
        PromptId.BUSINESS -> Prompts.BUSINESS
        PromptId.SOFTEN -> Prompts.SOFTEN
    }

    fun overrideFlow(id: PromptId): Flow<String?> =
        context.promptDataStore.data.map { it[stringPreferencesKey(id.storageKey)] }

    suspend fun effective(id: PromptId): String =
        overrideFlow(id).first() ?: factory(id)

    suspend fun effective(mode: ProofreadMode, forNano: Boolean = false): String =
        effective(PromptId.of(mode, forNano))

    suspend fun setOverride(id: PromptId, text: String) {
        context.promptDataStore.edit { it[stringPreferencesKey(id.storageKey)] = text }
    }

    /** "Вернуть заводской": removes the override, factory text applies again. */
    suspend fun resetToFactory(id: PromptId) {
        context.promptDataStore.edit { it.remove(stringPreferencesKey(id.storageKey)) }
    }
}
