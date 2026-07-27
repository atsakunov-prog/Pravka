package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(private val context: Context) {

    companion object {
        const val MODEL_SONNET = "claude-sonnet-5"
        const val MODEL_HAIKU = "claude-haiku-4-5"

        private val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_CLEAN_MODEL = stringPreferencesKey("clean_model")
    }

    val apiKeyFlow = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val cleanModelFlow = context.dataStore.data.map { it[KEY_CLEAN_MODEL] ?: MODEL_SONNET }

    suspend fun apiKey(): String = apiKeyFlow.first()

    // Model for CLEAN mode; BUSINESS/SOFTEN are always Sonnet (spec section 10).
    suspend fun cleanModel(): String = cleanModelFlow.first()

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value.trim() }
    }

    suspend fun setCleanModel(value: String) {
        context.dataStore.edit { it[KEY_CLEAN_MODEL] = value }
    }
}
