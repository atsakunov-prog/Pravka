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
        val skipSec: Int = 10,
        val speed: Float = 1.0f,
        val speakAnswers: Boolean = false,
        val pauseWhileAsking: Boolean = true,
        // Запас против спойлера: сколько минут аудио отступить назад от
        // расчётного места, прежде чем резать текст. Привязка приблизительная,
        // и ошибаться она должна в сторону уже услышанного.
        val spoilerMarginSec: Int = 120,
        val autoRewind: Boolean = true,
        val syncPositions: Boolean = true,
        // Через сколько часов паузы предлагать пересказ «что было в прошлый раз».
        val recapAfterHours: Int = 8,
        val skipSilence: Boolean = false,
        // Читалка.
        val readerFont: String = FONT_SERIF,
        val readerSize: Int = 19,
        val readerLineHeight: Float = 1.5f,
        val readerMargin: Int = 20,
        val readerJustify: Boolean = true,
        val readerTheme: String = THEME_AUTO,
        val readerKeepAwake: Boolean = true,
        /** true - листание постранично, false - обычная прокрутка. */
        val readerPaged: Boolean = false,
        // Обновление приложения.
        val updateUrl: String = DEFAULT_UPDATE_URL,
        val updateAuto: Boolean = true,
        /** Сверять переход «звук → текст» распознаванием последних секунд. */
        val refineOnSwitch: Boolean = true,
        // Модели: кто отвечает на вопрос по книге и кто пересказывает, и с
        // каким усилием (пусто — параметр не передаётся, решает API). Заводские
        // — те, что были зашиты: Опус на вопрос, Сонет на пересказ.
        val askModel: String = MODEL_OPUS,
        val askEffort: String = "",
        // Сколько книги показывать модели (имя AskEngine.Scope) и держать ли
        // контекст в кэше час, чтобы разговор из десяти вопросов не оплачивал
        // книгу десять раз. Оба выбираются в самом окне вопроса и помнятся.
        val askScope: String = "",
        val askCache: Boolean = true,
        val recapModel: String = MODEL_SONNET,
        val recapEffort: String = "",
        // Справочник по книге считается пакетным запросом (Batch API, вдвое
        // дешевле): заводская — Опус, книга целиком ему по силам.
        val guideModel: String = MODEL_OPUS,
        // Каталог Флибусты: адрес сайта. Меняется на зеркало, когда основной
        // не открывается, - поэтому настройка, а не константа.
        val flibustaUrl: String = DEFAULT_FLIBUSTA_URL,
        // Озвучка книги без записи системным синтезом: темп и голос. Голос -
        // имя из движка (у Google это «ru-ru-x-…»), пусто - какой движок даст.
        val ttsRate: Float = 1.0f,
        val ttsVoice: String = "",
        // Советник в каталоге: кто отвечает, с каким усилием, ходить ли в интернет.
        // Заводская модель — Fable 5.1: владелец просил именно её.
        val adviseModel: String = MODEL_FABLE,
        val adviseEffort: String = "",
        val adviseWeb: Boolean = true,
    )

    val flow: StateFlow<Prefs> = context.dataStore.data
        .map { p ->
            Prefs(
                loaded = true,
                apiKey = p[KEY_API] ?: "",
                libraryUri = p[KEY_LIB] ?: "",
                profile = p[KEY_PROFILE] ?: "",
                skipSec = p[KEY_SKIP] ?: 10,
                speed = p[KEY_SPEED] ?: 1.0f,
                speakAnswers = p[KEY_SPEAK] ?: false,
                pauseWhileAsking = p[KEY_PAUSE_ASK] ?: true,
                spoilerMarginSec = p[KEY_MARGIN] ?: 120,
                autoRewind = p[KEY_REWIND] ?: true,
                syncPositions = p[KEY_SYNC] ?: true,
                recapAfterHours = p[KEY_RECAP_H] ?: 8,
                skipSilence = p[KEY_SKIP_SILENCE] ?: false,
                readerFont = p[KEY_R_FONT] ?: FONT_SERIF,
                readerSize = p[KEY_R_SIZE] ?: 19,
                readerLineHeight = p[KEY_R_LINE] ?: 1.5f,
                readerMargin = p[KEY_R_MARGIN] ?: 20,
                readerJustify = p[KEY_R_JUSTIFY] ?: true,
                readerTheme = p[KEY_R_THEME] ?: THEME_AUTO,
                readerKeepAwake = p[KEY_R_AWAKE] ?: true,
                readerPaged = p[KEY_R_PAGED] ?: false,
                updateUrl = p[KEY_UPD_URL] ?: DEFAULT_UPDATE_URL,
                updateAuto = p[KEY_UPD_AUTO] ?: true,
                refineOnSwitch = p[KEY_REFINE] ?: true,
                // Модель не из каталога (снятая с API, опечатка старой сборки)
                // откатывается к заводской, а не уезжает в запрос за 404.
                askModel = p[KEY_ASK_MODEL]?.takeIf { it in MODELS } ?: MODEL_OPUS,
                askEffort = p[KEY_ASK_EFFORT]?.takeIf { it in EFFORTS } ?: "",
                // Старый тумблер «вся книга до этого места» переезжает в объём:
                // кто его включал, получает тот же объём и в новом окне.
                askScope = p[KEY_ASK_SCOPE] ?: if (p[KEY_WHOLE] == true) "WHOLE" else "",
                askCache = p[KEY_ASK_CACHE] ?: true,
                recapModel = p[KEY_RECAP_MODEL]?.takeIf { it in MODELS } ?: MODEL_SONNET,
                recapEffort = p[KEY_RECAP_EFFORT]?.takeIf { it in EFFORTS } ?: "",
                guideModel = p[KEY_GUIDE_MODEL]?.takeIf { it in MODELS } ?: MODEL_OPUS,
                flibustaUrl = p[KEY_FLIBUSTA]?.takeIf { it.isNotBlank() } ?: DEFAULT_FLIBUSTA_URL,
                ttsRate = p[KEY_TTS_RATE] ?: 1.0f,
                ttsVoice = p[KEY_TTS_VOICE] ?: "",
                adviseModel = p[KEY_ADVISE_MODEL]?.takeIf { it in MODELS } ?: MODEL_FABLE,
                adviseEffort = p[KEY_ADVISE_EFFORT]?.takeIf { it in EFFORTS } ?: "",
                adviseWeb = p[KEY_ADVISE_WEB] ?: true,
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
    suspend fun setSpoilerMargin(v: Int) = edit { it[KEY_MARGIN] = v.coerceIn(0, 1800) }
    suspend fun setAutoRewind(v: Boolean) = edit { it[KEY_REWIND] = v }
    suspend fun setSyncPositions(v: Boolean) = edit { it[KEY_SYNC] = v }
    suspend fun setRecapAfterHours(v: Int) = edit { it[KEY_RECAP_H] = v.coerceIn(1, 240) }
    suspend fun setSkipSilence(v: Boolean) = edit { it[KEY_SKIP_SILENCE] = v }
    suspend fun setReaderFont(v: String) = edit { it[KEY_R_FONT] = v }
    suspend fun setReaderSize(v: Int) = edit { it[KEY_R_SIZE] = v.coerceIn(12, 34) }
    suspend fun setReaderLineHeight(v: Float) = edit { it[KEY_R_LINE] = v.coerceIn(1.0f, 2.4f) }
    suspend fun setReaderMargin(v: Int) = edit { it[KEY_R_MARGIN] = v.coerceIn(0, 64) }
    suspend fun setReaderJustify(v: Boolean) = edit { it[KEY_R_JUSTIFY] = v }
    suspend fun setReaderTheme(v: String) = edit { it[KEY_R_THEME] = v }
    suspend fun setReaderKeepAwake(v: Boolean) = edit { it[KEY_R_AWAKE] = v }
    suspend fun setReaderPaged(v: Boolean) = edit { it[KEY_R_PAGED] = v }
    suspend fun setUpdateUrl(v: String) = edit { it[KEY_UPD_URL] = v.trim() }
    suspend fun setUpdateAuto(v: Boolean) = edit { it[KEY_UPD_AUTO] = v }
    suspend fun setRefineOnSwitch(v: Boolean) = edit { it[KEY_REFINE] = v }
    suspend fun setAskModel(v: String) = edit { if (v in MODELS) it[KEY_ASK_MODEL] = v }
    suspend fun setAskEffort(v: String) = edit { if (v in EFFORTS) it[KEY_ASK_EFFORT] = v }
    suspend fun setAskScope(v: String) = edit { it[KEY_ASK_SCOPE] = v }
    suspend fun setAskCache(v: Boolean) = edit { it[KEY_ASK_CACHE] = v }
    suspend fun setGuideModel(v: String) = edit { if (v in MODELS) it[KEY_GUIDE_MODEL] = v }
    suspend fun setRecapModel(v: String) = edit { if (v in MODELS) it[KEY_RECAP_MODEL] = v }
    suspend fun setRecapEffort(v: String) = edit { if (v in EFFORTS) it[KEY_RECAP_EFFORT] = v }
    suspend fun setFlibustaUrl(v: String) = edit { it[KEY_FLIBUSTA] = v.trim() }
    suspend fun setTtsRate(v: Float) = edit { it[KEY_TTS_RATE] = v.coerceIn(0.5f, 2.5f) }
    suspend fun setTtsVoice(v: String) = edit { it[KEY_TTS_VOICE] = v }
    suspend fun setAdviseModel(v: String) = edit { if (v in MODELS) it[KEY_ADVISE_MODEL] = v }
    suspend fun setAdviseEffort(v: String) = edit { if (v in EFFORTS) it[KEY_ADVISE_EFFORT] = v }
    suspend fun setAdviseWeb(v: Boolean) = edit { it[KEY_ADVISE_WEB] = v }

    companion object {
        const val MODEL_OPUS = "claude-opus-5"
        const val MODEL_SONNET = "claude-sonnet-5"
        const val MODEL_FABLE = "claude-fable-5-1"

        /** Что можно выбрать в настройках; порядок — от дешёвой к дорогой. */
        val MODELS = listOf(MODEL_SONNET, MODEL_OPUS, MODEL_FABLE)

        fun modelLabel(model: String): String = when (model) {
            MODEL_SONNET -> "Сонет 5"
            MODEL_OPUS -> "Опус 5"
            MODEL_FABLE -> "Fable 5.1"
            else -> model
        }

        /** output_config.effort; пустая строка — не передавать (API берёт high). */
        val EFFORTS = listOf("", "low", "medium", "high", "xhigh", "max")

        fun effortLabel(effort: String): String = if (effort.isBlank()) "по умолчанию" else effort

        /** Знаков в «странице»: стандартная машинописная - 1800. */
        const val PAGE_CHARS = 1800

        /**
         * Где приложение ищет свежую сборку. Каждый пуш в ветку `slushalka`
         * уезжает в ветку `apk-builds` вместе со СВОИМ файлом версий - его и
         * читаем. Лежащий рядом `build-info.txt` - файл Правки: до 06.09 обе
         * линии писали его по очереди, и Слушалка предлагала «версию 358» с
         * чужим APK внутри.
         */
        const val DEFAULT_UPDATE_URL =
            "https://raw.githubusercontent.com/atsakunov-prog/Pravka/apk-builds/slushalka-build-info.txt"

        /** Адрес каталога Флибусты. Ленты OPDS лежат под `/opds`. */
        const val DEFAULT_FLIBUSTA_URL = "https://flibusta.is"

        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_MONO = "mono"

        // Тема читалки живёт отдельно от темы приложения: читают и днём на
        // свету, и ночью в постели, и переключать это хочется одним тапом.
        const val THEME_AUTO = "auto"
        const val THEME_PAPER = "paper"
        const val THEME_SEPIA = "sepia"
        const val THEME_GREY = "grey"
        const val THEME_BLACK = "black"

        private val KEY_API: Preferences.Key<String> = stringPreferencesKey("anthropic_api_key")
        private val KEY_LIB = stringPreferencesKey("library_uri")
        private val KEY_PROFILE = stringPreferencesKey("profile")
        private val KEY_SKIP = intPreferencesKey("skip_sec")
        private val KEY_SPEED = floatPreferencesKey("speed")
        private val KEY_SPEAK = booleanPreferencesKey("speak_answers")
        private val KEY_PAUSE_ASK = booleanPreferencesKey("pause_while_asking")
        private val KEY_MARGIN = intPreferencesKey("spoiler_margin_sec")
        /** Старый тумблер «вся книга»; читается только ради переезда в askScope. */
        private val KEY_WHOLE = booleanPreferencesKey("whole_book_context")
        private val KEY_REWIND = booleanPreferencesKey("auto_rewind")
        private val KEY_SYNC = booleanPreferencesKey("sync_positions")
        private val KEY_RECAP_H = intPreferencesKey("recap_after_hours")
        private val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        private val KEY_R_FONT = stringPreferencesKey("reader_font")
        private val KEY_R_SIZE = intPreferencesKey("reader_size")
        private val KEY_R_LINE = floatPreferencesKey("reader_line")
        private val KEY_R_MARGIN = intPreferencesKey("reader_margin")
        private val KEY_R_JUSTIFY = booleanPreferencesKey("reader_justify")
        private val KEY_R_THEME = stringPreferencesKey("reader_theme")
        private val KEY_R_AWAKE = booleanPreferencesKey("reader_keep_awake")
        private val KEY_R_PAGED = booleanPreferencesKey("reader_paged")
        private val KEY_UPD_URL = stringPreferencesKey("update_url")
        private val KEY_UPD_AUTO = booleanPreferencesKey("update_auto")
        private val KEY_REFINE = booleanPreferencesKey("refine_on_switch")
        private val KEY_ASK_MODEL = stringPreferencesKey("ask_model")
        private val KEY_ASK_EFFORT = stringPreferencesKey("ask_effort")
        private val KEY_ASK_SCOPE = stringPreferencesKey("ask_scope")
        private val KEY_ASK_CACHE = booleanPreferencesKey("ask_cache")
        private val KEY_GUIDE_MODEL = stringPreferencesKey("guide_model")
        private val KEY_RECAP_MODEL = stringPreferencesKey("recap_model")
        private val KEY_RECAP_EFFORT = stringPreferencesKey("recap_effort")
        private val KEY_FLIBUSTA = stringPreferencesKey("flibusta_url")
        private val KEY_TTS_RATE = floatPreferencesKey("tts_rate")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_ADVISE_MODEL = stringPreferencesKey("advise_model")
        private val KEY_ADVISE_EFFORT = stringPreferencesKey("advise_effort")
        private val KEY_ADVISE_WEB = booleanPreferencesKey("advise_web")
    }
}
