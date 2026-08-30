package ru.zf.slushalka.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore(name = "slushalka")

/**
 * Настройки одним снимком.
 *
 * DataStore асинхронный, а спрашивают его в том числе из тика плеера и из
 * службы - там `runBlocking` был бы заиканием звука. Поэтому весь набор
 * держится готовым снимком в [flow], а [now] отдаёт его без ожидания.
 */
class Settings(private val context: Context, scope: CoroutineScope) {

    data class Prefs(
        /** false - DataStore ещё не ответил, значения ниже пока заводские. */
        val loaded: Boolean = false,
        val apiKey: String = "",
        // Дерево SAF, выбранное системным пикером: библиотека целиком.
        val libraryUri: String = "",
        // Чьё это устройство - имя дорожки в синхронизации позиций.
        val profile: String = "",
        val skipSec: Int = 15,
        val speed: Float = 1.0f,
        val speakAnswers: Boolean = false,
        val pauseWhileAsking: Boolean = true,
        val contextPages: Int = 5,
        // Запас против спойлера: сколько минут аудио отступить назад от
        // расчётного места, прежде чем резать текст. Привязка приблизительная,
        // и ошибаться она должна в сторону уже услышанного.
        val spoilerMarginSec: Int = 120,
        val wholeBookContext: Boolean = false,
        val autoRewind: Boolean = true,
        val syncPositions: Boolean = true,
        // Через сколько часов паузы предлагать пересказ «что было в прошлый раз».
        val recapAfterHours: Int = 8,
        val skipSilence: Boolean = false,
    )

    val flow: StateFlow<Prefs> = context.dataStore.data
        .map { p ->
            Prefs(
                loaded = true,
                apiKey = p[KEY_API] ?: "",
                libraryUri = p[KEY_LIB] ?: "",
                profile = p[KEY_PROFILE] ?: "",
                skipSec = p[KEY_SKIP] ?: 15,
                speed = p[KEY_SPEED] ?: 1.0f,
                speakAnswers = p[KEY_SPEAK] ?: false,
                pauseWhileAsking = p[KEY_PAUSE_ASK] ?: true,
                contextPages = p[KEY_PAGES] ?: 5,
                spoilerMarginSec = p[KEY_MARGIN] ?: 120,
                wholeBookContext = p[KEY_WHOLE] ?: false,
                autoRewind = p[KEY_REWIND] ?: true,
                syncPositions = p[KEY_SYNC] ?: true,
                recapAfterHours = p[KEY_RECAP_H] ?: 8,
                skipSilence = p[KEY_SKIP_SILENCE] ?: false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Prefs())

    fun now(): Prefs = flow.value

    suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    suspend fun setApiKey(v: String) = edit { it[KEY_API] = v.trim() }
    suspend fun setLibraryUri(v: String) = edit { it[KEY_LIB] = v }
    suspend fun setProfile(v: String) = edit { it[KEY_PROFILE] = v.trim() }
    suspend fun setSkipSec(v: Int) = edit { it[KEY_SKIP] = v.coerceIn(5, 60) }
    suspend fun setSpeed(v: Float) = edit { it[KEY_SPEED] = v.coerceIn(0.5f, 3.0f) }
    suspend fun setSpeakAnswers(v: Boolean) = edit { it[KEY_SPEAK] = v }
    suspend fun setPauseWhileAsking(v: Boolean) = edit { it[KEY_PAUSE_ASK] = v }
    suspend fun setContextPages(v: Int) = edit { it[KEY_PAGES] = v.coerceIn(1, 40) }
    suspend fun setSpoilerMargin(v: Int) = edit { it[KEY_MARGIN] = v.coerceIn(0, 1800) }
    suspend fun setWholeBookContext(v: Boolean) = edit { it[KEY_WHOLE] = v }
    suspend fun setAutoRewind(v: Boolean) = edit { it[KEY_REWIND] = v }
    suspend fun setSyncPositions(v: Boolean) = edit { it[KEY_SYNC] = v }
    suspend fun setRecapAfterHours(v: Int) = edit { it[KEY_RECAP_H] = v.coerceIn(1, 240) }
    suspend fun setSkipSilence(v: Boolean) = edit { it[KEY_SKIP_SILENCE] = v }

    companion object {
        const val MODEL_OPUS = "claude-opus-5"
        const val MODEL_SONNET = "claude-sonnet-5"

        /** Знаков в «странице»: стандартная машинописная - 1800. */
        const val PAGE_CHARS = 1800

        private val KEY_API: Preferences.Key<String> = stringPreferencesKey("anthropic_api_key")
        private val KEY_LIB = stringPreferencesKey("library_uri")
        private val KEY_PROFILE = stringPreferencesKey("profile")
        private val KEY_SKIP = intPreferencesKey("skip_sec")
        private val KEY_SPEED = floatPreferencesKey("speed")
        private val KEY_SPEAK = booleanPreferencesKey("speak_answers")
        private val KEY_PAUSE_ASK = booleanPreferencesKey("pause_while_asking")
        private val KEY_PAGES = intPreferencesKey("context_pages")
        private val KEY_MARGIN = intPreferencesKey("spoiler_margin_sec")
        private val KEY_WHOLE = booleanPreferencesKey("whole_book_context")
        private val KEY_REWIND = booleanPreferencesKey("auto_rewind")
        private val KEY_SYNC = booleanPreferencesKey("sync_positions")
        private val KEY_RECAP_H = intPreferencesKey("recap_after_hours")
        private val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence")
    }
}
