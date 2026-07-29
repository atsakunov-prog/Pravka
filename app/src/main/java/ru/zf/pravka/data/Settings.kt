package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(private val context: Context) {

    companion object {
        const val MODEL_SONNET = "claude-sonnet-5"
        const val MODEL_HAIKU = "claude-haiku-4-5"
        const val MODEL_NANO = "gemini-nano"

        // Dictation engines.
        const val SPEECH_GOOGLE = "google"          // live streaming, Gboard's engine
        const val SPEECH_YANDEX = "yandex"          // live streaming, Yandex SpeechKit (cloud)
        const val SPEECH_WHISPER_SMALL = "whisper-small"
        const val SPEECH_WHISPER_BASE = "whisper-base"
        const val SPEECH_NANO = "nano"

        private val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_CLEAN_MODEL = stringPreferencesKey("clean_model")
        private val KEY_FAB_SIZE = intPreferencesKey("fab_size_dp")
        private val KEY_FAB_ALPHA = floatPreferencesKey("fab_alpha")
        private val KEY_SPEECH_ENGINE = stringPreferencesKey("speech_engine")
        private val KEY_YANDEX_API_KEY = stringPreferencesKey("yandex_api_key")
        private val KEY_YANDEX_FOLDER = stringPreferencesKey("yandex_folder_id")

        const val FAB_SIZE_DEFAULT = 48
        const val FAB_ALPHA_DEFAULT = 0.35f
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

    val speechEngineFlow = context.dataStore.data.map { it[KEY_SPEECH_ENGINE] ?: SPEECH_GOOGLE }
    suspend fun speechEngine(): String = speechEngineFlow.first()
    suspend fun setSpeechEngine(value: String) {
        context.dataStore.edit { it[KEY_SPEECH_ENGINE] = value }
    }

    // Yandex SpeechKit credentials - entered on-device, stored only here.
    val yandexApiKeyFlow = context.dataStore.data.map { it[KEY_YANDEX_API_KEY] ?: "" }
    val yandexFolderFlow = context.dataStore.data.map { it[KEY_YANDEX_FOLDER] ?: "" }
    suspend fun yandexApiKey(): String = yandexApiKeyFlow.first()
    suspend fun yandexFolder(): String = yandexFolderFlow.first()
    suspend fun setYandexApiKey(value: String) {
        context.dataStore.edit { it[KEY_YANDEX_API_KEY] = value.trim() }
    }
    suspend fun setYandexFolder(value: String) {
        context.dataStore.edit { it[KEY_YANDEX_FOLDER] = value.trim() }
    }

    val fabSizeFlow = context.dataStore.data.map { it[KEY_FAB_SIZE] ?: FAB_SIZE_DEFAULT }
    val fabAlphaFlow = context.dataStore.data.map { it[KEY_FAB_ALPHA] ?: FAB_ALPHA_DEFAULT }

    suspend fun setFabSize(dp: Int) {
        context.dataStore.edit { it[KEY_FAB_SIZE] = dp.coerceIn(36, 72) }
    }

    suspend fun setFabAlpha(alpha: Float) {
        context.dataStore.edit { it[KEY_FAB_ALPHA] = alpha.coerceIn(0.15f, 1f) }
    }

    // Floating button position - free placement, stored as x/y fractions of
    // the screen, separately per screen size (the foldable has two).
    suspend fun fabPosition(screenKey: String): Pair<Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("fab_x_$screenKey")] ?: 0.92f
        val y = prefs[floatPreferencesKey("fab_y_$screenKey")] ?: 0.45f
        return x to y
    }

    suspend fun setFabPosition(screenKey: String, xFraction: Float, yFraction: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("fab_x_$screenKey")] = xFraction
            it[floatPreferencesKey("fab_y_$screenKey")] = yFraction
        }
    }
}
