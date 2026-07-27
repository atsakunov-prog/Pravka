package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    // Floating button position, stored separately per screen size (the
    // foldable has two - folded and unfolded, spec 5.3). side: left/right,
    // y as a fraction of the available height.
    suspend fun fabPosition(screenKey: String): Pair<String, Float> {
        val prefs = context.dataStore.data.first()
        val side = prefs[stringPreferencesKey("fab_side_$screenKey")] ?: "right"
        val y = prefs[floatPreferencesKey("fab_y_$screenKey")] ?: 0.45f
        return side to y
    }

    suspend fun setFabPosition(screenKey: String, side: String, yFraction: Float) {
        context.dataStore.edit {
            it[stringPreferencesKey("fab_side_$screenKey")] = side
            it[floatPreferencesKey("fab_y_$screenKey")] = yFraction
        }
    }
}
