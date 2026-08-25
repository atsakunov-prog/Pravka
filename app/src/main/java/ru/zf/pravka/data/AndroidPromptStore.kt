package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.promptDataStore by preferencesDataStore(name = "prompts")

// Телефонная реализация PromptStore: правки владельца лежат в DataStore.
class AndroidPromptStore(private val context: Context) : PromptStore {

    // Время правки живёт отдельным файлом: заводить ради одного числа
    // миграцию DataStore не стоит, а синхронизации оно нужно.
    val syncMeta = PromptSyncMeta(context.filesDir)

    override fun overrideFlow(id: PromptStore.PromptId): Flow<String?> =
        context.promptDataStore.data.map { it[stringPreferencesKey(id.storageKey)] }

    override suspend fun setOverride(id: PromptStore.PromptId, text: String) {
        context.promptDataStore.edit { it[stringPreferencesKey(id.storageKey)] = text }
        syncMeta.touch(id.storageKey)
    }

    override suspend fun resetToFactory(id: PromptStore.PromptId) {
        context.promptDataStore.edit { it.remove(stringPreferencesKey(id.storageKey)) }
        syncMeta.touch(id.storageKey)
    }
}
