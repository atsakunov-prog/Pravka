package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        const val MODEL_OPUS = "claude-opus-5"   // redo chips only

        // Dictation engines.
        const val SPEECH_GOOGLE = "google"          // live streaming, Gboard's engine
        const val SPEECH_WHISPER_SMALL = "whisper-small"
        const val SPEECH_WHISPER_BASE = "whisper-base"

        private val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_FAB_SIZE = intPreferencesKey("fab_size_dp")
        private val KEY_FAB_ALPHA = floatPreferencesKey("fab_alpha")
        private val KEY_SPEECH_ENGINE = stringPreferencesKey("speech_engine")
        private val KEY_SPEECH_SEGMENTED = booleanPreferencesKey("speech_segmented")
        private val KEY_SPEECH_FORMATTING = booleanPreferencesKey("speech_formatting")
        private val KEY_PROSE_MODE = booleanPreferencesKey("prose_mode")
        private val KEY_CONVO_CONTEXT = booleanPreferencesKey("convo_context")
        private val KEY_RULES_IN_PROSE = booleanPreferencesKey("rules_in_prose")

        const val FAB_SIZE_DEFAULT = 48
        const val FAB_ALPHA_DEFAULT = 0.35f
    }

    val apiKeyFlow = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }

    suspend fun apiKey(): String = apiKeyFlow.first()

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value.trim() }
    }

    val speechEngineFlow = context.dataStore.data.map { it[KEY_SPEECH_ENGINE] ?: SPEECH_GOOGLE }
    suspend fun speechEngine(): String = speechEngineFlow.first()
    suspend fun setSpeechEngine(value: String) {
        context.dataStore.edit { it[KEY_SPEECH_ENGINE] = value }
    }

    // Recognition mode knobs. Defaults are EXACTLY build 55 - the owner's
    // "распознаёт идеально" configuration: continuous session, raw word
    // stream (no recognizer formatting).
    val speechSegmentedFlow = context.dataStore.data.map { it[KEY_SPEECH_SEGMENTED] ?: true }
    suspend fun setSpeechSegmented(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_SEGMENTED] = value }
    }

    val speechFormattingFlow = context.dataStore.data.map { it[KEY_SPEECH_FORMATTING] ?: false }
    suspend fun setSpeechFormatting(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_FORMATTING] = value }
    }

    // Fiction mode: CLEAN gets the PROSE style directive (owner writes prose).
    val proseModeFlow = context.dataStore.data.map { it[KEY_PROSE_MODE] ?: false }
    suspend fun setProseMode(value: Boolean) {
        context.dataStore.edit { it[KEY_PROSE_MODE] = value }
    }

    // Formatting rules are usually message-oriented and would fight the prose
    // directive - off in prose mode unless the owner flips this.
    val rulesInProseFlow = context.dataStore.data.map { it[KEY_RULES_IN_PROSE] ?: false }
    suspend fun setRulesInProse(value: Boolean) {
        context.dataStore.edit { it[KEY_RULES_IN_PROSE] = value }
    }

    // Conversation context: recent takes in the same app ride along with the
    // next dictation, so replies keep the thread's tone and referents.
    val convoContextFlow = context.dataStore.data.map { it[KEY_CONVO_CONTEXT] ?: true }
    suspend fun setConvoContext(value: Boolean) {
        context.dataStore.edit { it[KEY_CONVO_CONTEXT] = value }
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
