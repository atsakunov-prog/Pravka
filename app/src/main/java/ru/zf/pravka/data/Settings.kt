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
        private val KEY_LEARN_PERIOD_H = intPreferencesKey("learn_period_hours")

        // Засечка (timesheet).
        private val KEY_Z_ENABLED = booleanPreferencesKey("z_enabled")
        private val KEY_Z_GAP_MIN = intPreferencesKey("z_gap_min")
        private val KEY_Z_DAY_START = intPreferencesKey("z_day_start")
        private val KEY_Z_DAY_END = intPreferencesKey("z_day_end")
        private val KEY_Z_WEBHOOK = stringPreferencesKey("z_webhook_url")
        private val KEY_Z_CALLS = booleanPreferencesKey("z_calls_to_ribbon")
        private val KEY_Z_CALL_CATEGORY = stringPreferencesKey("z_call_category")
        private val KEY_Z_IMMERSIVE_MIN = intPreferencesKey("z_immersive_min")
        private val KEY_ICU_ATHLETE = stringPreferencesKey("icu_athlete_id")
        private val KEY_ICU_KEY = stringPreferencesKey("icu_api_key")

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

    // How often the auto-learning batch may run (hours). Owner-picked.
    val learnPeriodHoursFlow = context.dataStore.data.map { it[KEY_LEARN_PERIOD_H] ?: 3 }
    suspend fun setLearnPeriodHours(value: Int) {
        context.dataStore.edit { it[KEY_LEARN_PERIOD_H] = value.coerceIn(1, 24) }
    }

    // Conversation context: recent takes in the same app ride along with the
    // next dictation, so replies keep the thread's tone and referents.
    val convoContextFlow = context.dataStore.data.map { it[KEY_CONVO_CONTEXT] ?: true }
    suspend fun setConvoContext(value: Boolean) {
        context.dataStore.edit { it[KEY_CONVO_CONTEXT] = value }
    }

    // ---- Засечка (timesheet) ----

    /** The always-on timesheet button. */
    val zEnabledFlow = context.dataStore.data.map { it[KEY_Z_ENABLED] ?: true }
    suspend fun setZEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_ENABLED] = value }
    }

    /** Remind after this many minutes without a running entry; 0 = never. */
    val zGapMinFlow = context.dataStore.data.map { it[KEY_Z_GAP_MIN] ?: 45 }
    suspend fun setZGapMin(value: Int) {
        context.dataStore.edit { it[KEY_Z_GAP_MIN] = value.coerceIn(0, 240) }
    }

    // Active-day window: reminders fire only inside it; the morning nudge at
    // its start, the "закрыть день" one after its end.
    val zDayStartFlow = context.dataStore.data.map { it[KEY_Z_DAY_START] ?: 9 }
    suspend fun setZDayStart(value: Int) {
        context.dataStore.edit { it[KEY_Z_DAY_START] = value.coerceIn(0, 23) }
    }

    val zDayEndFlow = context.dataStore.data.map { it[KEY_Z_DAY_END] ?: 23 }
    suspend fun setZDayEnd(value: Int) {
        context.dataStore.edit { it[KEY_Z_DAY_END] = value.coerceIn(1, 24) }
    }

    /** Apps Script web-app URL; blank = Sheets mirror off. */
    val zWebhookFlow = context.dataStore.data.map { it[KEY_Z_WEBHOOK] ?: "" }
    suspend fun zWebhook(): String = zWebhookFlow.first()
    suspend fun setZWebhook(value: String) {
        context.dataStore.edit { it[KEY_Z_WEBHOOK] = value.trim() }
    }

    /** Calls >= 1 min land in the ribbon (needs the call-log permission). */
    val zCallsFlow = context.dataStore.data.map { it[KEY_Z_CALLS] ?: true }
    suspend fun setZCalls(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_CALLS] = value }
    }

    val zCallCategoryFlow = context.dataStore.data.map { it[KEY_Z_CALL_CATEGORY] ?: "Звонки" }
    suspend fun setZCallCategory(value: String) {
        context.dataStore.edit { it[KEY_Z_CALL_CATEGORY] = value.trim().ifEmpty { "Звонки" } }
    }

    /** An attention-eater session shorter than this stays out of the ribbon. */
    val zImmersiveMinFlow = context.dataStore.data.map { it[KEY_Z_IMMERSIVE_MIN] ?: 3 }
    suspend fun setZImmersiveMin(value: Int) {
        context.dataStore.edit { it[KEY_Z_IMMERSIVE_MIN] = value.coerceIn(1, 30) }
    }

    // intervals.icu: workouts into the ribbon, Garmin sleep as an annotation.
    val icuAthleteFlow = context.dataStore.data.map { it[KEY_ICU_ATHLETE] ?: "" }
    suspend fun icuAthlete(): String = icuAthleteFlow.first()
    suspend fun setIcuAthlete(value: String) {
        context.dataStore.edit { it[KEY_ICU_ATHLETE] = value.trim() }
    }

    val icuKeyFlow = context.dataStore.data.map { it[KEY_ICU_KEY] ?: "" }
    suspend fun icuKey(): String = icuKeyFlow.first()
    suspend fun setIcuKey(value: String) {
        context.dataStore.edit { it[KEY_ICU_KEY] = value.trim() }
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

    // The Засечка button has its own spot (default: below Правка's default),
    // stored the same per-screen way.
    suspend fun zFabPosition(screenKey: String): Pair<Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("zfab_x_$screenKey")] ?: 0.92f
        val y = prefs[floatPreferencesKey("zfab_y_$screenKey")] ?: 0.62f
        return x to y
    }

    suspend fun setZFabPosition(screenKey: String, xFraction: Float, yFraction: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("zfab_x_$screenKey")] = xFraction
            it[floatPreferencesKey("zfab_y_$screenKey")] = yFraction
        }
    }
}
