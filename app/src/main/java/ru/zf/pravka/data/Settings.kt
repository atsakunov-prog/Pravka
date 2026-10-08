package ru.zf.pravka.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.zf.pravka.core.PlaceDeal
import ru.zf.pravka.core.StackGeometry

// Файл — в папке базы (DataRoot), а не намертво в filesDir: ключи, модели и
// тумблеры едут вместе с лентой, когда базу копируют на другой телефон.
private val Context.dataStore get() = DataRoot.preferences(this, "settings")

class Settings(private val context: Context) {

    companion object {
        // Каталог моделей. Кто где работает — не здесь: заводские значения
        // дорог лежат в ModelRoute, выбор владельца читается modelChoice().
        const val MODEL_SONNET = "claude-sonnet-5-5"
        // Прежний Сонет (до 30.09.2026): не в каталоге выбора, но живёт в
        // хранилище старых сборок, в журналах и в батчах, отправленных до
        // обновления.
        const val MODEL_SONNET_5 = "claude-sonnet-5"
        const val MODEL_OPUS = "claude-opus-5-5"
        // Прежний Опус (до 22.09.2026): не в каталоге выбора, но живёт в
        // хранилище старых сборок и в батчах, отправленных до обновления.
        const val MODEL_OPUS_5 = "claude-opus-5"
        const val MODEL_FABLE = "claude-fable-5-1"

        // Dictation engines.
        const val SPEECH_GOOGLE = "google"          // live streaming, Gboard's engine
        // Тот же Google по сетевому пути — не выбор движка, а метка тейка в
        // «Расшифровках», чтобы офлайн-пакет и сеть сравнивались по журналу.
        const val SPEECH_GOOGLE_NET = "google-net"
        // Фраза, разобранная заново из сохранённого звука (`SpeechReplay`,
        // 30.09.2026), — тоже не движок, а метка строки в «Расшифровках».
        const val SPEECH_GOOGLE_REPLAY = "google-replay"
        const val SPEECH_WHISPER_SMALL = "whisper-small"
        const val SPEECH_WHISPER_BASE = "whisper-base"

        private val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_FAB_SIZE = intPreferencesKey("fab_size_dp")
        // Правка 4.0 (07.10.2026): «Сегодня» — время сна, погода, календари,
        // отметки в ленте и месячный бюджет для полосы на плашке Денег.
        private val KEY_TODAY_BEDTIME = intPreferencesKey("today_bedtime_min")
        private val KEY_WEATHER_CITY = stringPreferencesKey("weather_city")
        private val KEY_TODAY_CALENDARS = stringPreferencesKey("today_calendars_json")
        private val KEY_TODAY_MARKS = stringPreferencesKey("today_marks")
        private val KEY_TODAY_STATE = booleanPreferencesKey("today_state_line")
        private val KEY_MONEY_MONTH_BUDGET = longPreferencesKey("money_month_budget_rub")
        /** Время сна с завода — 23:30 (DESIGN §11.5 SleepRow). */
        const val TODAY_BEDTIME_DEFAULT = 23 * 60 + 30
        /** Город погоды с завода — пустой ряд никому не нужен, а живёт владелец в Москве. */
        const val WEATHER_CITY_DEFAULT = "Москва"
        /** Отметки, которые с завода прикрепляются к ленте «Сегодня». */
        const val TODAY_MARKS_DEFAULT = "sport,food,money,dela"
        private val KEY_FAB_ALPHA = floatPreferencesKey("fab_alpha")
        private val KEY_TICKER_WIDTH = intPreferencesKey("ticker_width_dp")
        /** Прожил одну сборку (26.09): «снизу» да/нет. Читается только ради перехода на [KEY_TICKER_PLACE]. */
        private val KEY_TICKER_BOTTOM = booleanPreferencesKey("ticker_bottom")
        private val KEY_TICKER_PLACE = stringPreferencesKey("ticker_place")
        private val KEY_TICKER_DENSITY = floatPreferencesKey("ticker_density")
        private val KEY_SPEECH_ENGINE = stringPreferencesKey("speech_engine")
        private val KEY_SPEECH_SEGMENTED = booleanPreferencesKey("speech_segmented")
        private val KEY_SPEECH_FORMATTING = booleanPreferencesKey("speech_formatting")
        private val KEY_SPEECH_BIASING = booleanPreferencesKey("speech_biasing")
        private val KEY_SPEECH_NETWORK = booleanPreferencesKey("speech_network")
        private val KEY_SPEECH_OWN_MIC = booleanPreferencesKey("speech_own_mic")
        private val KEY_READY_CHIME = stringPreferencesKey("ready_chime")
        private val KEY_CHIME_AFTER_START = longPreferencesKey("chime_after_start_ms")
        private val KEY_NET_PROBE = booleanPreferencesKey("net_probe")
        private val KEY_NET_PROBE_MIN = intPreferencesKey("net_probe_interval_min")
        private val KEY_CHIME_AFTER_SWITCH = longPreferencesKey("chime_after_switch_ms")
        private val KEY_PROSE_MODE = booleanPreferencesKey("prose_mode")
        private val KEY_CONVO_CONTEXT = booleanPreferencesKey("convo_context")
        private val KEY_RULES_IN_PROSE = booleanPreferencesKey("rules_in_prose")
        private val KEY_RULES_IN_PROMPT = booleanPreferencesKey("rules_in_prompt")
        private val KEY_NIGHT_REVIEW = booleanPreferencesKey("night_review_enabled")
        private val KEY_NIGHT_DAILY = booleanPreferencesKey("night_daily_enabled")
        private val KEY_NIGHT_REVIEW_HOUR = intPreferencesKey("night_review_hour")
        private val KEY_PROMPT_TUNE = booleanPreferencesKey("prompt_tune_enabled")
        private val KEY_NIGHT_BUDGET = intPreferencesKey("night_budget_usd")
        private val KEY_MIGRATED_OPUS = booleanPreferencesKey("migrated_pravka_opus_1")
        private val KEY_MIGRATED_OPUS_55 = booleanPreferencesKey("migrated_opus_5_5")
        private val KEY_MIGRATED_PRAVKA_SONNET_55 = booleanPreferencesKey("migrated_pravka_sonnet_5_5")
        private val KEY_MIGRATED_SPEECH_NET = booleanPreferencesKey("migrated_speech_network_1")
        private val KEY_PLAN_RULES_LAST_RUN = longPreferencesKey("plan_rules_last_run")
        // Когда служба последний раз перезапускала сама себя из-за невидимых окон (OverlayWatch).
        private val KEY_OVERLAY_RESTART_AT = longPreferencesKey("overlay_restart_at")
        private val KEY_DEBUG_LOG = booleanPreferencesKey("debug_log")
        // Суточная копия базы ночью (DailyBackup) — с завода включена.
        private val KEY_DAILY_BACKUP = booleanPreferencesKey("daily_backup")
        private val KEY_LEARN_PERIOD_H = intPreferencesKey("learn_period_hours")
        private val KEY_LEARN_AUTO = booleanPreferencesKey("learn_auto_capture")

        // Засечка (timesheet).
        private val KEY_Z_ENABLED = booleanPreferencesKey("z_enabled")
        private val KEY_STACK_IDLE = booleanPreferencesKey("buttons_stack_idle")
        private val KEY_DISK = booleanPreferencesKey("buttons_disk")
        private val KEY_DISK_TUCK = booleanPreferencesKey("disk_auto_tuck")
        // С 28.09.2026 окна на время складывания не снимаются (см. keepOnFoldFlow).
        private val KEY_KEEP_ON_FOLD = booleanPreferencesKey("keep_windows_on_fold")
        private val KEY_DISK_LIGHT = booleanPreferencesKey("disk_light_glass")
        private val KEY_DISK_GAP = intPreferencesKey("disk_gap_dp")
        private val KEY_DISK_GEAR = intPreferencesKey("disk_gear_pct")
        private val KEY_DISK_PLATE_ALPHA = floatPreferencesKey("disk_plate_alpha")
        private val KEY_DISK_FACE_ALPHA = floatPreferencesKey("disk_face_alpha")
        private val KEY_DISK_SOCKET_ALPHA = floatPreferencesKey("disk_socket_alpha")
        private val KEY_DISK_FROST = booleanPreferencesKey("disk_frost")
        private val KEY_DISK_RAIL = booleanPreferencesKey("disk_rail")
        private val KEY_DISK_INERTIA = booleanPreferencesKey("disk_inertia")
        private val KEY_DISK_ROLL = floatPreferencesKey("disk_roll_k")
        // Плашки приложения: те же слои, что у стекла, и своя темнота.
        private val KEY_CARD_DARK = floatPreferencesKey("card_darken")
        private val KEY_CARD_BEVEL = booleanPreferencesKey("card_bevel")
        private val KEY_CARD_LIGHT = booleanPreferencesKey("card_light")
        private val KEY_CARD_GRAIN = booleanPreferencesKey("card_grain")
        private val KEY_APP_GLOW = floatPreferencesKey("app_glow")
        private val KEY_ICONS_GEMINI = booleanPreferencesKey("icons_gemini")
        private val KEY_APP_GLOW_MOTION = booleanPreferencesKey("app_glow_motion")
        private val KEY_Z_GAP_MIN = intPreferencesKey("z_gap_min")
        private val KEY_Z_DAY_START = intPreferencesKey("z_day_start")
        private val KEY_Z_DAY_END = intPreferencesKey("z_day_end")
        private val KEY_Z_WEBHOOK = stringPreferencesKey("z_webhook_url")
        private val KEY_Z_CALLS = booleanPreferencesKey("z_calls_to_ribbon")
        private val KEY_Z_CALL_CATEGORY = stringPreferencesKey("z_call_category")
        private val KEY_Z_CALLS_CUT = booleanPreferencesKey("z_calls_cut")
        private val KEY_Z_CALL_FAMILY = stringPreferencesKey("z_call_family")
        private val KEY_Z_IMMERSIVE_MIN = intPreferencesKey("z_immersive_min")
        private val KEY_Z_CHECKINS = booleanPreferencesKey("z_checkins")
        private val KEY_Z_QUIET = booleanPreferencesKey("z_quiet")
        // Разноска: третья кнопка «Д» (она про дела).
        private val KEY_R_ENABLED = booleanPreferencesKey("r_enabled")
        // Деньги: кнопка «₽» и тумблер «+ ЗФ» во вкладке.
        private val KEY_M_ENABLED = booleanPreferencesKey("m_enabled")
        private val KEY_M_WITH_ZF = booleanPreferencesKey("m_with_zf")
        private val KEY_M_PUSH = booleanPreferencesKey("m_push")
        // Образцы денежных уведомлений для разборщиков новых банков — только по тумблеру.
        private val KEY_M_PUSH_SAMPLES = booleanPreferencesKey("m_push_samples")
        private val KEY_M_SCOPE_P = booleanPreferencesKey("m_scope_personal")
        private val KEY_M_SCOPE_Z = booleanPreferencesKey("m_scope_zf")
        private val KEY_ICU_ATHLETE = stringPreferencesKey("icu_athlete_id")
        private val KEY_ICU_KEY = stringPreferencesKey("icu_api_key")
        private val KEY_TODOIST_TOKEN = stringPreferencesKey("todoist_token")
        private val KEY_DELA_BACKEND = stringPreferencesKey("dela_backend")
        private val KEY_DELA_PLACES_SENT = stringPreferencesKey("dela_places_sent")

        // Спорт: вкладка живёт кэшем intervals.icu, глубина - в днях.
        private val KEY_SPORT_DAYS = intPreferencesKey("sport_days")
        // Еда: кнопка «Е», цели КБЖУ и две дороги наружу.
        private val KEY_E_ENABLED = booleanPreferencesKey("e_enabled")
        private val KEY_FOOD_KCAL = intPreferencesKey("food_target_kcal")
        private val KEY_FOOD_PROTEIN = intPreferencesKey("food_target_protein")
        private val KEY_FOOD_FAT = intPreferencesKey("food_target_fat")
        private val KEY_FOOD_CARBS = intPreferencesKey("food_target_carbs")
        private val KEY_FOOD_TO_ICU = booleanPreferencesKey("food_to_icu")
        private val KEY_FOOD_TO_RIBBON = booleanPreferencesKey("food_to_ribbon")

        // Notion: правила блока читаются оттуда — владелец их там правит.
        // Справочник упражнений НЕ отсюда: он статическим файлом в assets.
        private val KEY_NOTION_TOKEN = stringPreferencesKey("notion_token")
        private val KEY_NOTION_HUB = stringPreferencesKey("notion_hub_page")
        // Тело: кнопка «Т» и таймер отдыха между подходами.
        private val KEY_T_ENABLED = booleanPreferencesKey("t_enabled")
        private val KEY_REST_SEC = intPreferencesKey("rest_timer_sec")
        private val KEY_GOAL_WEIGHT = intPreferencesKey("body_goal_weight")
        private val KEY_SPORT_NOTIFY = booleanPreferencesKey("sport_notify_arrived")
        private val KEY_MODE_ICONS = booleanPreferencesKey("mode_icons_on_buttons")
        private val KEY_PHONE_MIC_ONLY = booleanPreferencesKey("phone_mic_only")
        // z_auto_inserts - мёртвый ключ прежних врезок, которые резали ленту.
        // Поведения, которым он управлял, больше нет, поэтому и читать его
        // нельзя: выключенный тумблер прошлой механики молча погасил бы новую.
        // Самообновление из ветки apk-builds.
        private val KEY_UPD_AUTO = booleanPreferencesKey("upd_auto")
        private val KEY_UPD_MOBILE = booleanPreferencesKey("upd_mobile")
        private val KEY_UPD_URL = stringPreferencesKey("upd_url")
        private val KEY_UPD_BRANCH = stringPreferencesKey("upd_branch")
        private val KEY_ANALYSIS_NIGHTLY = booleanPreferencesKey("analysis_nightly")
        private val KEY_ANALYSIS_CONTEXT = stringPreferencesKey("analysis_context")
        private val KEY_AUTO_PLACES = stringPreferencesKey("auto_places")
        private val KEY_AUTO_SEEN = stringPreferencesKey("auto_seen_ssids")
        private val KEY_AUTO_VISIBLE = stringPreferencesKey("auto_visible_ssids")
        private val KEY_AUTO_PLACE_DEALS = stringPreferencesKey("auto_place_deals")
        /** Заводские дела мест — см. [autoPlaceDealsFlow]. */
        private val FACTORY_PLACE_DEALS = mapOf("Летово" to PlaceDeal("Забираю Серёжу", "Семья"))
        private val KEY_NFC_TAGS = stringPreferencesKey("nfc_tags")
        private val KEY_AUTO_CAR_BT = stringPreferencesKey("auto_car_bt")
        private val KEY_AUTO_CAR_BT_ADDR = stringPreferencesKey("auto_car_bt_addr")
        private val KEY_AUTO_CAR_START = booleanPreferencesKey("auto_car_start")
        private val KEY_AUTO_ARRIVE = booleanPreferencesKey("auto_arrive_close")
        private val KEY_AUTO_LEAVE_ASK = booleanPreferencesKey("auto_leave_ask")
        private val KEY_AUTO_CAR_ASK = booleanPreferencesKey("auto_car_ask")
        private val KEY_AUTO_STILL_ASK = booleanPreferencesKey("auto_still_ask")
        private val KEY_AUTO_WALK_START = booleanPreferencesKey("auto_walk_start")
        private val KEY_AUTO_CAL_ON = booleanPreferencesKey("auto_cal_on")
        private val KEY_AUTO_CAL_CATEGORY = stringPreferencesKey("auto_cal_category")
        private val KEY_AUTO_CALENDARS = stringPreferencesKey("auto_calendars")
        private val KEY_AUTO_WAKE_DEAL = stringPreferencesKey("auto_wake_deal")
        private val KEY_AUTO_PLATES = booleanPreferencesKey("auto_plates")
        private val KEY_AUTO_BEDTIME = booleanPreferencesKey("auto_bedtime_close")
        /** Заводское дело по подъёму в будни — см. [autoWakeDealFlow]. */
        private val FACTORY_WAKE_DEAL = PlaceDeal("Сборы детей", "Семья")
        private val KEY_NOTION_DIARY = booleanPreferencesKey("notion_diary_push")
        private val KEY_NOTION_LIFE = booleanPreferencesKey("notion_life_push")
        private val KEY_NOTION_LIFE_HUB = stringPreferencesKey("notion_life_hub")

        const val FAB_SIZE_DEFAULT = 48
        const val FAB_ALPHA_DEFAULT = 0.35f
        /** Ширина бегущей строки у кнопок, dp (владелец, 15.09: «размер плашки по горизонтали — в общие настройки»). */
        const val TICKER_WIDTH_DEFAULT = 340
        const val DELA_BACKEND_TODOIST = "todoist"
        const val DELA_BACKEND_SERVER = "dela"
        const val TICKER_WIDTH_MIN = 160
        const val TICKER_WIDTH_MAX = 900

        /**
         * Просвет диска, dp: от шестерёнки до кнопок его полтора
         * (`DiskGeometry.ringRadius`), поэтому он и есть ручка «размер
         * диска» — тарелка растёт вместе с кольцом.
         */
        const val DISK_GAP_DEFAULT = 8
        const val DISK_GAP_MIN = 2
        const val DISK_GAP_MAX = 28

        /**
         * Охота диска катиться при переезде. Половина настоящего качения:
         * на полной единице кольцо за один взмах через экран уходит на
         * полтора оборота, и кнопки успевают уехать из-под руки.
         */
        const val DISK_ROLL_DEFAULT = 0.5f

        /**
         * Насколько плашки приложения темнее заводского тона. Владелец
         * (20.09.2026): «плашки в самом приложении стали другие, но мне
         * понравилось. Давай их только сделаем потемнее и с такими же
         * эффектами, как и диск».
         */
        const val CARD_DARK_DEFAULT = 0.26f

        /**
         * Стекло диска с завода — тёмное (версия 3, владелец 26.09.2026:
         * «сделать дефолтным скин тёмного цвета»). С 19.09 было светлым —
         * бумага; теперь всё приложение — ночь, и тарелка под кнопками та
         * же ночь. Кто выбрал «Светлее» сам, тот при нём и остался: ключ
         * записан, заводское его не трогает.
         */
        const val DISK_LIGHT_DEFAULT = false

        /**
         * Почерк значков с завода — «как у Gemini» (Material Symbols Rounded;
         * владелец, 26.09.2026: «может, попробуем иконки ещё в стиле Gemini?»).
         * Попробовать — значит увидеть сразу; наш штрих — второе положение.
         */
        const val ICONS_GEMINI_DEFAULT = true

        // Заводские цели КБЖУ: посчитаны по Миффлину-Сан-Жеору для владельца
        // (86 кг, 180 см, 1982) при умеренной активности, белок 1,8 г/кг.
        // Тренировки в этот расчёт НЕ входят: их видно отдельно, а еда под
        // тренировку добирается сознательно.
        const val FOOD_KCAL_DEFAULT = 2500
        const val FOOD_PROTEIN_DEFAULT = 160
        const val FOOD_FAT_DEFAULT = 80
        const val FOOD_CARBS_DEFAULT = 280
        const val SPORT_DAYS_DEFAULT = 120
        const val REST_SEC_DEFAULT = 90
        // Цель веса из его же дорожной карты: «было 93, цель 80».
        const val GOAL_WEIGHT_DEFAULT = 80

        // Страница-хаб «Тело: велоформа и сила» в Notion. Приложение само
        // находит под ней самую свежую страницу «Блок …» — так новый блок
        // подхватывается без правки настроек.
        const val NOTION_HUB_DEFAULT = "3a8c4ffca2d58181a09be74696775c3e"
    }

    val apiKeyFlow = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }

    suspend fun apiKey(): String = apiKeyFlow.first()

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value.trim() }
    }

    // ---- Модели: какая и с каким усилием на каждой дороге в Claude ----
    //
    // Ключи — по имени дороги (`model_pravka`, `effort_zasechka`), значения
    // — строки API. Заводское значение НЕ пишется в хранилище: пока владелец
    // не трогал дорогу, ключа нет, и смена заводского в новой сборке
    // подхватывается сама. Выбор проверяется при чтении (ModelChoice.of):
    // снятая с API модель откатывается к заводской, а не ловит 404.

    private fun modelKey(route: ModelRoute) = stringPreferencesKey("model_" + route.key)
    private fun effortKey(route: ModelRoute) = stringPreferencesKey("effort_" + route.key)

    fun modelChoiceFlow(route: ModelRoute): Flow<ModelChoice> = context.dataStore.data.map { prefs ->
        ModelChoice.of(route, prefs[modelKey(route)], prefs[effortKey(route)])
    }

    /** Модель и усилие для дороги — читается перед каждым запросом. */
    suspend fun modelChoice(route: ModelRoute): ModelChoice = modelChoiceFlow(route).first()

    /**
     * Сколько дорог владелец увёл от заводского — строка «Модели» в меню
     * настроек пишет «своих: 2» вместо полотна из семнадцати дорог.
     */
    fun modelChoicesChangedFlow(): Flow<Int> = context.dataStore.data.map { prefs ->
        ModelRoute.entries.count { route ->
            !ModelChoice.of(route, prefs[modelKey(route)], prefs[effortKey(route)]).isDefaultFor(route)
        }
    }

    suspend fun setModel(route: ModelRoute, model: String) {
        if (model !in Models.ALL) return
        context.dataStore.edit { it[modelKey(route)] = model }
    }

    suspend fun setEffort(route: ModelRoute, effort: String) {
        if (effort !in Models.EFFORTS) return
        context.dataStore.edit { it[effortKey(route)] = effort }
    }

    /** Заводское: ключи снимаются, а не переписываются (см. выше, почему). */
    suspend fun resetModelChoice(route: ModelRoute) {
        context.dataStore.edit {
            it.remove(modelKey(route))
            it.remove(effortKey(route))
        }
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

    // Подсказывать распознавателю слова словаря (EXTRA_BIASING_STRINGS). Владелец
    // (15.09.2026) сравнивает скорость с клавиатурой Google, а список
    // подсказок — единственное, чем наш вызов того же движка отличается от
    // неё; тумблер — чтобы проверить на слух, не он ли тормозит.
    val speechBiasingFlow = context.dataStore.data.map { it[KEY_SPEECH_BIASING] ?: true }
    suspend fun setSpeechBiasing(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_BIASING] = value }
    }

    // Путь распознавания Google (16.09.2026, заводское изменено 22.09.2026).
    // Сетевой путь — тот, каким идёт голосовой ввод клавиатуры Google на
    // русском (пиксельная модель Assistant voice typing русского не знает):
    // серверная модель чётче офлайн-пакета на именах, редких словах и
    // английских терминах, а без сети система сама падает на пакет. Офлайн —
    // работает без сети, голос не уходит с телефона.
    //
    // Заводское теперь СЕТЬ. Владелец (22.09.2026): «гугловский движок
    // облачный — и важно, чтобы он был главным, а то чуть-чуть ухудшилось
    // качество распознавания». Сравнение он провёл, выбор сделан; офлайн
    // остаётся тумблером и запасом, на который система падает сама.
    val speechNetworkFlow = context.dataStore.data.map { it[KEY_SPEECH_NETWORK] ?: true }
    suspend fun setSpeechNetwork(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_NETWORK] = value }
    }

    /**
     * Микрофон держит Правка, распознаватель получает её звук (`MicFeed`,
     * 28.09.2026; владелец: «отходил далеко в наушниках… телефон просто
     * переставал это слышать… переключения — мнимые»). С завода — да;
     * выключено — прежняя дорога: распознаватель слушает сам, а мы только
     * просим маршрут. Откат одним движением, если своя запись где-то сломает
     * распознавание.
     */
    val speechOwnMicFlow = context.dataStore.data.map { it[KEY_SPEECH_OWN_MIC] ?: true }
    suspend fun setSpeechOwnMic(value: Boolean) {
        context.dataStore.edit { it[KEY_SPEECH_OWN_MIC] = value }
    }

    /**
     * Звук «говори» (`core/ReadyChime.kt`, 29.09.2026; владелец: «я могу не
     * смотреть даже на телефон, поэтому мне точно нужен фидбэк… его можно
     * выключить, конечно, но он по-хорошему должен идти в наушники всегда»).
     * С завода — в наушниках.
     */
    val readyChimeFlow = context.dataStore.data.map { ru.zf.pravka.core.ReadyChime.Mode.fromKey(it[KEY_READY_CHIME]) }
    suspend fun setReadyChime(mode: ru.zf.pravka.core.ReadyChime.Mode) {
        context.dataStore.edit { it[KEY_READY_CHIME] = mode.key }
    }

    /**
     * Через сколько звенеть «говори» в наушники (01.10.2026; владелец: «в
     * настройках ползунок, через сколько секунд его делать, и я подберу»):
     * от начала тейка и от переключения на наушники посреди тейка, мс.
     * Не раньше, чем тейк слышит (`ReadyChime.readyDelayMs`).
     */
    val chimeAfterStartFlow = context.dataStore.data.map { it[KEY_CHIME_AFTER_START] ?: ru.zf.pravka.core.ReadyChime.AFTER_START_MS }
    suspend fun setChimeAfterStart(ms: Long) {
        context.dataStore.edit { it[KEY_CHIME_AFTER_START] = ms.coerceIn(0L, ru.zf.pravka.core.ReadyChime.AFTER_MAX_MS) }
    }
    /**
     * Проверка связи с облаками (`provider/NetProber.kt`, 01.10.2026; владелец:
     * «тумблер, чтобы включался пинг каждые 5 минут… мне надо где-то неделю
     * это проверять»). С завода включена — неделя наблюдения началась сразу;
     * раз в 15 минут, ночью с 00:00 до 08:00 — раз в полчаса (`NetProbe.intervalMin`).
     */
    val netProbeFlow = context.dataStore.data.map { it[KEY_NET_PROBE] ?: true }
    suspend fun setNetProbe(on: Boolean) {
        context.dataStore.edit { it[KEY_NET_PROBE] = on }
    }
    /** Как часто проверять, минут (`NetProbe.INTERVALS_MIN`). */
    val netProbeIntervalFlow = context.dataStore.data.map {
        it[KEY_NET_PROBE_MIN]?.takeIf { m -> m in ru.zf.pravka.core.NetProbe.INTERVALS_MIN } ?: ru.zf.pravka.core.NetProbe.INTERVAL_DEFAULT_MIN
    }
    suspend fun setNetProbeInterval(minutes: Int) {
        context.dataStore.edit { it[KEY_NET_PROBE_MIN] = minutes }
    }
    val chimeAfterSwitchFlow = context.dataStore.data.map { it[KEY_CHIME_AFTER_SWITCH] ?: ru.zf.pravka.core.ReadyChime.AFTER_SWITCH_MS }
    suspend fun setChimeAfterSwitch(ms: Long) {
        context.dataStore.edit { it[KEY_CHIME_AFTER_SWITCH] = ms.coerceIn(0L, ru.zf.pravka.core.ReadyChime.AFTER_MAX_MS) }
    }

    /**
     * Сетевой путь — один раз и на уже стоящем телефоне. Смена заводского
     * значения сама по себе ничего не переключает: в DataStore лежит выбор,
     * сделанный 16.09, и он сильнее любого нового «по умолчанию». Ровно та же
     * история, что с чисткой на Опусе ([migratePravkaToOpus]).
     */
    suspend fun migrateSpeechToNetwork() {
        context.dataStore.edit { p ->
            if (p[KEY_MIGRATED_SPEECH_NET] == true) return@edit
            p[KEY_MIGRATED_SPEECH_NET] = true
            if (p[KEY_SPEECH_NETWORK] != true) p[KEY_SPEECH_NETWORK] = true
        }
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

    // Режим отладки (15.09.2026): каждый запрос к Claude целиком — в
    // отдельный лог, чтобы владелец мог выгрузить и посмотреть, не уезжает ли
    // в модель лишнего. Выключен — транспорт ничего не пишет.
    val mPushSamplesFlow = context.dataStore.data.map { it[KEY_M_PUSH_SAMPLES] ?: false }
    suspend fun setMPushSamples(value: Boolean) {
        context.dataStore.edit { it[KEY_M_PUSH_SAMPLES] = value }
    }

    val dailyBackupFlow = context.dataStore.data.map { it[KEY_DAILY_BACKUP] ?: true }
    suspend fun setDailyBackup(value: Boolean) {
        context.dataStore.edit { it[KEY_DAILY_BACKUP] = value }
    }

    val debugLogFlow = context.dataStore.data.map { it[KEY_DEBUG_LOG] ?: false }
    suspend fun setDebugLog(value: Boolean) {
        context.dataStore.edit { it[KEY_DEBUG_LOG] = value }
    }

    // Formatting rules are usually message-oriented and would fight the prose
    // directive - off in prose mode unless the owner flips this.
    // Постоянные правила владельца в промпте CLEAN (16.09.2026). Выключено:
    // разбор 1200 чисток до и после появления правил (29.08) не показал
    // разницы в пунктуации и длине предложений, а из 54 правил в запрос
    // попадали только первые восемь — потолок RulesStore.PROMPT_CAP — и они
    // повторяют сам промпт. Единственный видимый эффект — приветствие с «!».
    // Тумблер в настройках Правки возвращает блок целиком.
    val rulesInPromptFlow = context.dataStore.data.map { it[KEY_RULES_IN_PROMPT] ?: false }
    suspend fun setRulesInPrompt(value: Boolean) {
        context.dataStore.edit { it[KEY_RULES_IN_PROMPT] = value }
    }

    // Ночной разбор (16.09.2026). Владелец: «ежедневный разбор… чтобы каждую
    // ночь делал всё, что ты сейчас сделал». Включён с завода — он его и
    // просил; час — три ночи: телефон на зарядке, никто не диктует, батч
    // успевает к утру. Расписание и политика — core/NightReviewPolicy.kt.
    val nightReviewEnabledFlow = context.dataStore.data.map { it[KEY_NIGHT_REVIEW] ?: true }
    suspend fun setNightReviewEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_NIGHT_REVIEW] = value }
    }

    /**
     * Разбор суток каждую ночь — отдельно от недельного (18.09.2026; владелец:
     * «может, нам оставить только недельный?»). Заводское — выключен: ослышка
     * становится словарной, когда повторяется, а за сутки она редко повторится;
     * шесть ночей по $0,6–0,7 давали в основном ту же картину, что одна
     * недельная. Кнопка «Сутки» в плашке «Ночью» работает независимо от тумблера.
     */
    val nightDailyEnabledFlow = context.dataStore.data.map { it[KEY_NIGHT_DAILY] ?: false }
    suspend fun setNightDailyEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_NIGHT_DAILY] = value }
    }

    val nightReviewHourFlow = context.dataStore.data.map { it[KEY_NIGHT_REVIEW_HOUR] ?: 3 }
    suspend fun setNightReviewHour(value: Int) {
        context.dataStore.edit { it[KEY_NIGHT_REVIEW_HOUR] = value.coerceIn(0, 23) }
    }

    // Недельная правка промпта (17.09.2026): в ночь на субботу Fable читает
    // идеи недели, предлагает правку CLEAN, новый промпт измеряется и
    // принимается только если лучше; через неделю — откат, если правок руками
    // стало больше. Движок — core/PromptTuner.kt, политика — core/PromptTunePolicy.kt.
    val promptTuneEnabledFlow = context.dataStore.data.map { it[KEY_PROMPT_TUNE] ?: true }
    suspend fun setPromptTuneEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_PROMPT_TUNE] = value }
    }

    // Дневной потолок для ночных автоматов (17.09.2026; владелец: «за день
    // шесть долларов… уже восемь!»). Когда расход за сутки по всему
    // приложению выше потолка, автоматы сами не стартуют, а идущая чистка
    // тени или измерение промпта останавливаются до следующих суток; ручной
    // запуск кнопкой потолок не смотрит. Заводское — $4.
    val nightBudgetUsdFlow = context.dataStore.data.map { it[KEY_NIGHT_BUDGET] ?: 4 }
    suspend fun setNightBudgetUsd(value: Int) {
        context.dataStore.edit { it[KEY_NIGHT_BUDGET] = value.coerceIn(1, 50) }
    }

    /**
     * Разовая миграция 18.09.2026: чистка — на Опус. Заводское поменялось в
     * ModelRoutes, но у дороги мог стоять явный выбор «Сонет» с прошлых сборок —
     * владелец просил включить Опус, а не оставить как было. Явный Fable или
     * уже Опус не трогаем. Метка — чтобы миграция не спорила с его будущим
     * возвратом на Сонет.
     */
    suspend fun migratePravkaToOpus() {
        context.dataStore.edit { p ->
            if (p[KEY_MIGRATED_OPUS] == true) return@edit
            p[KEY_MIGRATED_OPUS] = true
            val key = modelKey(ModelRoute.PRAVKA)
            if (p[key] == null || p[key] == MODEL_SONNET_5) p[key] = MODEL_OPUS
        }
    }

    /**
     * Разовая миграция 22.09.2026: Опус 5.5 и новые усилия по режимам. Владелец
     * просил «везде заменить», поэтому на дорогах, где заводская теперь Опус 5.5,
     * явный выбор прежнего Опуса или Fable снимается целиком — вместе с
     * усилием — и дорога берёт новые заводские (правка medium, засечка и еда
     * xhigh, спорт medium, разборы max). Явный Сонет — осознанный выбор
     * дешёвой модели (веер переключает на него чистку) — не трогаем.
     * Метка — чтобы миграция не спорила с его будущими настройками.
     */
    suspend fun migrateToOpus55() {
        context.dataStore.edit { p ->
            if (p[KEY_MIGRATED_OPUS_55] == true) return@edit
            p[KEY_MIGRATED_OPUS_55] = true
            for (route in ModelRoute.entries) {
                if (route.defaultModel != MODEL_OPUS) continue
                val saved = p[modelKey(route)]
                if (saved == null || saved == MODEL_OPUS_5 || saved == MODEL_OPUS || saved == MODEL_FABLE) {
                    p.remove(modelKey(route))
                    p.remove(effortKey(route))
                }
            }
        }
    }

    /**
     * Разовая миграция 30.09.2026: чистка — на Сонет 5.5. Владелец: «надо
     * заменить Сонет 5 на Сонет 5.5. И давай сделаем его дефолтным для правки
     * пока». Заводская дороги поменялась в ModelRoutes, но в хранилище у
     * чистки лежит явный Опус — от [migratePravkaToOpus] или от веера, — и он
     * сильнее любой заводской. Снимается один раз, вместе с усилием (у Опуса
     * medium, у Сонета заводское high); дальше выбор владельца не трогаем.
     * Стоит после двух прежних: на чистой установке первая из них кладёт
     * Опус, а эта его снимает.
     */
    suspend fun migratePravkaToSonnet55() {
        context.dataStore.edit { p ->
            if (p[KEY_MIGRATED_PRAVKA_SONNET_55] == true) return@edit
            p[KEY_MIGRATED_PRAVKA_SONNET_55] = true
            p.remove(modelKey(ModelRoute.PRAVKA))
            p.remove(effortKey(ModelRoute.PRAVKA))
        }
    }

    // Когда последний раз читались правила блока из Notion — переживает
    // перезапуск: в день с семью сборками правила перечитывались и разбирались
    // Опусом семь раз (16.09.2026), потому что метка жила в памяти процесса.
    suspend fun planRulesLastRun(): Long = context.dataStore.data.map { it[KEY_PLAN_RULES_LAST_RUN] ?: 0L }.first()
    suspend fun setPlanRulesLastRun(ms: Long) {
        context.dataStore.edit { it[KEY_PLAN_RULES_LAST_RUN] = ms }
    }

    // Самоперезапуск службы из-за окон, которые система не показывает
    // (`OverlayWatch`, 28.09.2026), — метка переживает сам перезапуск: иначе
    // сторож «не чаще раза в полчаса» забывал бы, что перезапуск только что был.
    suspend fun overlayRestartAt(): Long = context.dataStore.data.map { it[KEY_OVERLAY_RESTART_AT] ?: 0L }.first()
    suspend fun setOverlayRestartAt(ms: Long) {
        context.dataStore.edit { it[KEY_OVERLAY_RESTART_AT] = ms }
    }

    val rulesInProseFlow = context.dataStore.data.map { it[KEY_RULES_IN_PROSE] ?: false }
    suspend fun setRulesInProse(value: Boolean) {
        context.dataStore.edit { it[KEY_RULES_IN_PROSE] = value }
    }

    // Auto-capture of hand-edits. Default OFF (owner: the rule set is complete
    // and nothing new is found) - and it is the ONLY reason the service needs
    // typeViewTextChanged, i.e. an event from every keystroke in every app plus
    // a binder round trip for event.source on the service main thread.
    val learnAutoFlow = context.dataStore.data.map { it[KEY_LEARN_AUTO] ?: false }
    suspend fun setLearnAuto(value: Boolean) {
        context.dataStore.edit { it[KEY_LEARN_AUTO] = value }
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
    /**
     * Собирать кнопки в стопку, когда их долго не трогают. Владелец просил
     * сам, поэтому по умолчанию включено — но тумблер обязателен: четыре
     * кнопки, внезапно уехавшие друг под друга, без объяснения выглядят как
     * поломка.
     */
    val stackIdleFlow = context.dataStore.data.map { it[KEY_STACK_IDLE] ?: true }
    suspend fun setStackIdle(value: Boolean) {
        context.dataStore.edit { it[KEY_STACK_IDLE] = value }
    }

    /**
     * Диск вместо стопки (владелец, 19.09.2026): четыре кнопки по кольцу
     * вокруг шестерёнки, крутится пальцем, у края виден наполовину. Включён с
     * завода — владелец хочет посмотреть; тумблер обязателен: «если не
     * получится, откатим» должно быть одним движением, без пересборки.
     */
    val diskModeFlow = context.dataStore.data.map { it[KEY_DISK] ?: true }
    suspend fun setDiskMode(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK] = value }
    }

    /**
     * Автоуборка диска (владелец, 19.09.2026): «что-то написал в еде, и через
     * 30 секунд диск пришёл к ближайшему краю и прилепился, так что остались
     * только засечка и правка». Полминуты без касаний — к ближайшему краю и
     * домой. Тумблер обязателен: выдвинутый руками диск, который сам уезжает,
     * без объяснения выглядит как поломка.
     */
    val diskTuckFlow = context.dataStore.data.map { it[KEY_DISK_TUCK] ?: true }
    suspend fun setDiskTuck(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK_TUCK] = value }
    }

    // Не снимать кнопки на время складывания. Опыт того же дня (владелец,
    // 28.09.2026: «давай попробуем… если не блокируется экран, то диск спокойно
    // перерисовывается») — и итог: «Наконец-то всё сработало. Я включил
    // тумблер, и всё работает как раньше». На Android 17 окна, снятые и
    // повешенные посреди складывания с блокировкой, система больше не
    // показывала до перезапуска службы; нетронутые переход проводит сам.
    // Поэтому с завода — не снимать; «снимать, как с лета» (так уходила
    // чернота на пять секунд) — откат одним тумблером, если чернота вернётся.
    val keepOnFoldFlow = context.dataStore.data.map { it[KEY_KEEP_ON_FOLD] ?: true }
    suspend fun setKeepOnFold(value: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_ON_FOLD] = value }
    }

    /**
     * Светлое стекло диска (владелец, 19.09.2026, ночь): «давай его сделаем
     * наоборот, светлее, чем бэкграунд. А то теряется иногда. И сделаем
     * тумблер в настройках: светлее/темнее». С 26.09.2026 с завода снова
     * тёмное ([DISK_LIGHT_DEFAULT]); бумага — второе положение, потому что
     * фон под диском бывает любой, и какое стекло на нём не теряется, видно
     * только на самом телефоне. Числа обеих шкурок — `core/DiskLook.kt`.
     */
    val diskLightFlow = context.dataStore.data.map { it[KEY_DISK_LIGHT] ?: DISK_LIGHT_DEFAULT }
    suspend fun setDiskLight(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK_LIGHT] = value }
    }

    /**
     * Ручки вида диска (владелец, 19.09.2026: «и нужно всё это в настройки.
     * Прозрачность, размер»). Раньше эти числа жили в `core/DiskLook.kt` и
     * правились только пересборкой — а подобрать их можно лишь на живом
     * экране, глядя на свой фон.
     *
     * Плотности хранятся как `Float?`: НЕТ ключа — «как посчитается»
     * (формула из `DiskLook`, она следит и за прозрачностью кнопок, и за
     * тем, какое стекло). Есть ключ — владелец двинул ползунок, и дальше
     * слово за ним. Поэтому у плотностей нет «заводского числа»: заводское —
     * это сама формула, и до первого касания ползунка ничего не застывает.
     * [resetDiskLook] убирает ключи и возвращает всё в счёт.
     */
    val diskGapFlow = context.dataStore.data.map { it[KEY_DISK_GAP] ?: DISK_GAP_DEFAULT }
    suspend fun setDiskGap(dp: Int) {
        context.dataStore.edit { it[KEY_DISK_GAP] = dp.coerceIn(DISK_GAP_MIN, DISK_GAP_MAX) }
    }

    val diskGearFlow = context.dataStore.data.map { it[KEY_DISK_GEAR] ?: StackGeometry.GEAR_PCT_DEFAULT }
    suspend fun setDiskGear(percent: Int) {
        context.dataStore.edit {
            it[KEY_DISK_GEAR] = percent.coerceIn(StackGeometry.GEAR_PCT_MIN, StackGeometry.GEAR_PCT_MAX)
        }
    }

    /** Плотность стекла; null — считать по `DiskLook.plateAlpha`. */
    val diskPlateAlphaFlow = context.dataStore.data.map { it[KEY_DISK_PLATE_ALPHA] }
    suspend fun setDiskPlateAlpha(value: Float) {
        context.dataStore.edit { it[KEY_DISK_PLATE_ALPHA] = value.coerceIn(0f, 1f) }
    }

    /** Плотность лица кнопки на диске; null — считать по `DiskLook.faceAlpha`. */
    val diskFaceAlphaFlow = context.dataStore.data.map { it[KEY_DISK_FACE_ALPHA] }
    suspend fun setDiskFaceAlpha(value: Float) {
        context.dataStore.edit { it[KEY_DISK_FACE_ALPHA] = value.coerceIn(0.2f, 1f) }
    }

    /** Плотность тени кнопки на стекле; null — считать по `DiskLook.socketAlpha`. */
    val diskSocketAlphaFlow = context.dataStore.data.map { it[KEY_DISK_SOCKET_ALPHA] }
    suspend fun setDiskSocketAlpha(value: Float) {
        context.dataStore.edit { it[KEY_DISK_SOCKET_ALPHA] = value.coerceIn(0f, 0.6f) }
    }

    /**
     * Матовое стекло — зерно по тарелке. Системное размытие фона сюда не
     * годится: оно размывает прямоугольник окна, а окно у круглой тарелки
     * квадратное (см. `DiskLook.frostAlpha`). Тумблер — владелец просил
     * выключение отдельно от всего остального.
     */
    val diskFrostFlow = context.dataStore.data.map { it[KEY_DISK_FROST] ?: true }
    suspend fun setDiskFrost(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK_FROST] = value }
    }

    /** Рельс — канавка по кольцу, на котором сидят кнопки. */
    val diskRailFlow = context.dataStore.data.map { it[KEY_DISK_RAIL] ?: true }
    suspend fun setDiskRail(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK_RAIL] = value }
    }

    /** Инерция: диск катится, пока его везут. */
    val diskInertiaFlow = context.dataStore.data.map { it[KEY_DISK_INERTIA] ?: true }
    suspend fun setDiskInertia(value: Boolean) {
        context.dataStore.edit { it[KEY_DISK_INERTIA] = value }
    }

    /** Насколько охотно катится: 1,0 — качение без проскальзывания. */
    val diskRollFlow = context.dataStore.data.map { it[KEY_DISK_ROLL] ?: DISK_ROLL_DEFAULT }
    suspend fun setDiskRoll(value: Float) {
        context.dataStore.edit { it[KEY_DISK_ROLL] = value.coerceIn(0f, 1.5f) }
    }

    /** Насколько затемнять плашки приложения (0 — заводской тон). */
    val cardDarkFlow = context.dataStore.data.map { it[KEY_CARD_DARK] ?: CARD_DARK_DEFAULT }
    suspend fun setCardDark(value: Float) {
        context.dataStore.edit { it[KEY_CARD_DARK] = value.coerceIn(0f, 0.6f) }
    }

    /** Фаска по кромке плашки — та же, что у кнопок на стекле. */
    val cardBevelFlow = context.dataStore.data.map { it[KEY_CARD_BEVEL] ?: true }
    suspend fun setCardBevel(value: Boolean) {
        context.dataStore.edit { it[KEY_CARD_BEVEL] = value }
    }

    /** Свет сверху на плашке. */
    val cardLightFlow = context.dataStore.data.map { it[KEY_CARD_LIGHT] ?: true }
    suspend fun setCardLight(value: Boolean) {
        context.dataStore.edit { it[KEY_CARD_LIGHT] = value }
    }

    /** Зерно на плашке — тот же иней, что на стекле диска. */
    val cardGrainFlow = context.dataStore.data.map { it[KEY_CARD_GRAIN] ?: true }
    suspend fun setCardGrain(value: Boolean) {
        context.dataStore.edit { it[KEY_CARD_GRAIN] = value }
    }

    /**
     * Свечение режима во вкладке (версия 3): сила 0..1, ноль — выключено.
     * Числа света — `core/ModeGlow.kt`; ползунок — «Плашки приложения».
     */
    val appGlowFlow = context.dataStore.data.map { it[KEY_APP_GLOW] ?: ru.zf.pravka.core.ModeGlow.DEFAULT }
    suspend fun setAppGlow(value: Float) {
        context.dataStore.edit { it[KEY_APP_GLOW] = value.coerceIn(0f, 1f) }
    }

    /** Свет режима плывёт и переливается; выключено — стоит на месте. */
    val appGlowMotionFlow = context.dataStore.data.map { it[KEY_APP_GLOW_MOTION] ?: true }
    suspend fun setAppGlowMotion(value: Boolean) {
        context.dataStore.edit { it[KEY_APP_GLOW_MOTION] = value }
    }

    /**
     * Почерк значков приложения (версия 3): true — как у Gemini, false — наш
     * штрих (`ui/Glyphs.kt`). Кнопки на стекле и знак в пилюле его не
     * слушают — там всегда наш (владелец: «на кнопках мои значки мне
     * нравились, давай оставим их»).
     */
    val iconsGeminiFlow = context.dataStore.data.map { it[KEY_ICONS_GEMINI] ?: ICONS_GEMINI_DEFAULT }
    suspend fun setIconsGemini(value: Boolean) {
        context.dataStore.edit { it[KEY_ICONS_GEMINI] = value }
    }

    /** Вернуть вид диска в счёт: размеры — к заводским, плотности — к формулам. */
    suspend fun resetDiskLook() {
        context.dataStore.edit {
            it.remove(KEY_DISK_GAP)
            it.remove(KEY_DISK_GEAR)
            it.remove(KEY_DISK_PLATE_ALPHA)
            it.remove(KEY_DISK_FACE_ALPHA)
            it.remove(KEY_DISK_SOCKET_ALPHA)
            it.remove(KEY_DISK_ROLL)
        }
    }

    /**
     * Где стоит диск: центр долями рабочей области экрана и поворот в
     * градусах, отдельно на каждый размер экрана (у складного их два).
     * Умолчание — за правым краем на высоте «П»: первая же расстановка
     * докует его к краю.
     */
    suspend fun diskPlace(screenKey: String): Triple<Float, Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("disk_x_$screenKey")] ?: 1f
        val y = prefs[floatPreferencesKey("disk_y_$screenKey")] ?: 0.45f
        val r = prefs[floatPreferencesKey("disk_r_$screenKey")] ?: 0f
        return Triple(x, y, r)
    }

    suspend fun setDiskPlace(screenKey: String, xFraction: Float, yFraction: Float, rotation: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("disk_x_$screenKey")] = xFraction
            it[floatPreferencesKey("disk_y_$screenKey")] = yFraction
            it[floatPreferencesKey("disk_r_$screenKey")] = rotation
        }
    }

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

    /**
     * «Всё ещё …?» - when a running дело outlives its category's typical
     * length, the button winks and asks. One switch kills all of them.
     */
    val zCheckinsFlow = context.dataStore.data.map { it[KEY_Z_CHECKINS] ?: true }
    suspend fun setZCheckins(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_CHECKINS] = value }
    }

    /**
     * «Засечка молча» как выбрал человек; null — не выбирал. Что это значит на
     * деле, решает `Profile.zasechkaQuiet` (не выбирал — молчит у всех, кроме
     * владельца); читать — `app.zQuietFlow`, не этот поток.
     */
    val zQuietSetFlow: Flow<Boolean?> = context.dataStore.data.map { it[KEY_Z_QUIET] }
    suspend fun setZQuiet(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_QUIET] = value }
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

    /**
     * Звонки режут дело (06.10.2026, `core/CallRules.kt`): разговор от двух минут
     * встаёт в ленту врезкой с категорией по собеседнику, дело продолжается
     * после. С завода — да: владелец попросил сам. Выключено — звонки только в
     * суточных счётчиках, как с 05.09.
     */
    val zCallsCutFlow = context.dataStore.data.map { it[KEY_Z_CALLS_CUT] ?: true }
    suspend fun setZCallsCut(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_CALLS_CUT] = value }
    }

    /** Кто — семья для звонков: имена контактов через запятую («Марианна, Папа»). */
    val zCallFamilyFlow = context.dataStore.data.map { it[KEY_Z_CALL_FAMILY] ?: ru.zf.pravka.core.CallRules.FAMILY_DEFAULT }
    suspend fun setZCallFamily(value: String) {
        context.dataStore.edit { it[KEY_Z_CALL_FAMILY] = value.trim() }
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

    // Todoist: личный API-токен (Todoist → Настройки → Интеграции →
    // Разработчик). Прямой REST, без посредников - как и у intervals.icu.
    val todoistTokenFlow = context.dataStore.data.map { it[KEY_TODOIST_TOKEN] ?: "" }
    suspend fun todoistToken(): String = todoistTokenFlow.first()
    suspend fun setTodoistToken(value: String) {
        context.dataStore.edit { it[KEY_TODOIST_TOKEN] = value.trim() }
    }

    // Куда ходит режим «Дела» (03.10.2026, docs/dela-server.md): «todoist» —
    // как было, «dela» — домашний сервер Дел. Todoist пока не удалён — выбор в
    // «Подключениях», чтобы было куда откатиться. С завода — Todoist: Дела
    // включаются сами, когда владелец отсканировал QR сервера.
    val delaBackendFlow = context.dataStore.data.map { it[KEY_DELA_BACKEND] ?: DELA_BACKEND_TODOIST }
    suspend fun delaBackend(): String = delaBackendFlow.first()
    suspend fun setDelaBackend(value: String) {
        context.dataStore.edit { it[KEY_DELA_BACKEND] = value }
    }

    /**
     * Места автопилота, которые сервер Дел уже знает (`user.settings.places`,
     * 06.10.2026): строкой «дом|Летово». Иная строка — места поменялись, их
     * пора отдать серверу, чтобы разбор в вебе ставил «когда приеду» тем же словом.
     */
    suspend fun delaPlacesSent(): String = context.dataStore.data.first()[KEY_DELA_PLACES_SENT].orEmpty()
    suspend fun setDelaPlacesSent(value: String) {
        context.dataStore.edit { it[KEY_DELA_PLACES_SENT] = value }
    }

    // Разноска: кнопка «Д» на экране. Включена по умолчанию - она и есть
    // третий режим; выключается тем же тумблером, что и «З».
    val rEnabledFlow = context.dataStore.data.map { it[KEY_R_ENABLED] ?: true }
    suspend fun setREnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_R_ENABLED] = value }
    }

    // Деньги: кнопка «₽» на экране (23.09.2026). Включена с завода — владелец
    // попросил её четвёртой на диске; выключается тумблером в настройках Денег.
    val mEnabledFlow = context.dataStore.data.map { it[KEY_M_ENABLED] ?: true }
    suspend fun setMEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_M_ENABLED] = value }
    }

    /**
     * Итоги Денег с ЗФ или без: «расходы ЗФ… включая ZF или не включая ZF,
     * потому что это мой доход основной» (владелец, 23.09.2026). С завода —
     * без: вкладка сначала отвечает, сколько стоит семья.
     */
    val mWithZfFlow = context.dataStore.data.map { it[KEY_M_WITH_ZF] ?: false }
    suspend fun setMWithZf(value: Boolean) {
        context.dataStore.edit { it[KEY_M_WITH_ZF] = value }
    }

    /**
     * Ловить пуши Т-Банка и чата «Плати по миру» (владелец, 23.09.2026). С
     * завода включено, но без «Доступа к уведомлениям» ничего не делает —
     * тумблер для того, чтобы выключить, не отзывая доступ.
     */
    /**
     * Кнопки «Личное · ЗФ» наверху Денег (владелец, 23.09.2026). С завода —
     * личное; «ЗФ» с завода — как был прежний тумблер «+ ЗФ».
     */
    val mScopePersonalFlow = context.dataStore.data.map { it[KEY_M_SCOPE_P] ?: true }
    val mScopeZfFlow = context.dataStore.data.map { it[KEY_M_SCOPE_Z] ?: (it[KEY_M_WITH_ZF] ?: false) }
    suspend fun setMScope(personal: Boolean, zf: Boolean) {
        context.dataStore.edit { it[KEY_M_SCOPE_P] = personal; it[KEY_M_SCOPE_Z] = zf }
    }

    val mPushFlow = context.dataStore.data.map { it[KEY_M_PUSH] ?: true }
    suspend fun setMPush(value: Boolean) {
        context.dataStore.edit { it[KEY_M_PUSH] = value }
    }

    // ---- Notion: правила блока ----

    /**
     * Внутренний токен интеграции Notion (ntn_…). Только чтение: приложение
     * берёт оттуда правила блока и ничего туда не пишет.
     */
    val notionTokenFlow = context.dataStore.data.map { it[KEY_NOTION_TOKEN] ?: "" }
    suspend fun notionToken(): String = notionTokenFlow.first()
    suspend fun setNotionToken(value: String) {
        context.dataStore.edit { it[KEY_NOTION_TOKEN] = value.trim() }
    }

    val notionHubFlow = context.dataStore.data.map { it[KEY_NOTION_HUB] ?: NOTION_HUB_DEFAULT }
    suspend fun notionHub(): String = notionHubFlow.first()
    suspend fun setNotionHub(value: String) {
        context.dataStore.edit {
            it[KEY_NOTION_HUB] = value.trim().ifEmpty { NOTION_HUB_DEFAULT }
        }
    }

    // ---- Тело: силовые, зарядка, GTG ----

    /** Кнопка «Т»: одна на подходы, еду и зарядку — намерение решает модель. */
    /**
     * Зелёная кнопка тела/еды на стекле. С завода ВЫКЛЮЧЕНА (владелец,
     * 19.09.2026 вечер: «уберём кружок спорт») — на стекле остаются «П», «З»
     * и «Д». Тумблер в настройках Еды возвращает её четвёртой.
     */
    val tEnabledFlow = context.dataStore.data.map { it[KEY_T_ENABLED] ?: false }
    suspend fun setTEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_T_ENABLED] = value }
    }

    /** Отдых между подходами по умолчанию, секунды. */
    val restSecFlow = context.dataStore.data.map { it[KEY_REST_SEC] ?: REST_SEC_DEFAULT }
    suspend fun setRestSec(value: Int) {
        context.dataStore.edit { it[KEY_REST_SEC] = value.coerceIn(30, 300) }
    }

    /** Цель веса, кг — карточка «цели» меряет дорогу к ней. */
    val goalWeightFlow = context.dataStore.data.map { it[KEY_GOAL_WEIGHT] ?: GOAL_WEIGHT_DEFAULT }
    suspend fun setGoalWeight(value: Int) {
        context.dataStore.edit { it[KEY_GOAL_WEIGHT] = value.coerceIn(40, 200) }
    }

    /** Уведомление, когда часы прислали тренировку: вердикт по правилам + feel. */
    // ---- Итоги: ночной разбор жизненного лога ----

    /** Ночью отправлять разбор вчерашнего дня, в воскресенье — недели. */
    val analysisNightlyFlow = context.dataStore.data.map { it[KEY_ANALYSIS_NIGHTLY] ?: true }
    suspend fun analysisNightly(): Boolean = analysisNightlyFlow.first()
    suspend fun setAnalysisNightly(value: Boolean) {
        context.dataStore.edit { it[KEY_ANALYSIS_NIGHTLY] = value }
    }

    /**
     * Известный контекст периода прозой: «школьные каникулы», «отпуск до
     * 11.08», «болел». Уезжает в блок <meta>: правило промпта — сначала
     * контекст, потом диагноз, иначе каникулы читаются как развал.
     */
    val analysisContextFlow = context.dataStore.data.map { it[KEY_ANALYSIS_CONTEXT] ?: "" }
    suspend fun analysisContext(): String = analysisContextFlow.first()
    suspend fun setAnalysisContext(value: String) {
        context.dataStore.edit { it[KEY_ANALYSIS_CONTEXT] = value.trim() }
    }

    // ---- Автопилот Засечки: места по Wi-Fi, машина по Bluetooth ----

    /** Именованные места: SSID → имя («дом», «дача»). JSON-объект строкой. */
    val autoPlacesFlow = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_PLACES].orEmpty()
        if (raw.isBlank()) emptyMap()
        else runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.optString(k) }
        }.getOrDefault(emptyMap())
    }

    suspend fun addAutoPlace(ssid: String, name: String) {
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_PLACES].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            o.put(ssid, name.trim())
            prefs[KEY_AUTO_PLACES] = o.toString()
        }
    }

    suspend fun removeAutoPlace(ssid: String) {
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_PLACES].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            o.remove(ssid)
            prefs[KEY_AUTO_PLACES] = o.toString()
        }
    }

    /**
     * Сети, которые телефон видел: SSID → когда последний раз. Владелец:
     * «пускай он спрашивает про разные Wi-Fi — что это за место». Из этого
     * списка в настройках и называют места; сами по себе они ничего не делают.
     */
    val autoSeenFlow = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_SEEN].orEmpty()
        if (raw.isBlank()) emptyMap()
        else runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.optLong(k) }
        }.getOrDefault(emptyMap())
    }

    suspend fun addAutoSeen(ssid: String, at: Long) {
        if (ssid.isBlank()) return
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_SEEN].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            o.put(ssid, at)
            // Держим двадцать последних: список для глаз, а не архив.
            if (o.length() > 20) {
                val oldest = o.keys().asSequence().minByOrNull { o.optLong(it) }
                if (oldest != null) o.remove(oldest)
            }
            prefs[KEY_AUTO_SEEN] = o.toString()
        }
    }

    /**
     * То же, но пачкой: скан эфира отдаёт сразу десяток сетей, и делать по
     * записи в хранилище на каждую — лишняя работа диску.
     */
    suspend fun addAutoSeenAll(ssids: List<String>, at: Long) {
        val clean = ssids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_SEEN].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            clean.forEach { o.put(it, at) }
            while (o.length() > 20) {
                val oldest = o.keys().asSequence().minByOrNull { o.optLong(it) } ?: break
                o.remove(oldest)
            }
            prefs[KEY_AUTO_SEEN] = o.toString()
        }
    }

    suspend fun removeAutoSeen(ssid: String) {
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_SEEN].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            o.remove(ssid)
            prefs[KEY_AUTO_SEEN] = o.toString()
        }
    }

    /**
     * Места, которые ловятся ПО ВИДИМОСТИ, а не по подключению. Владелец:
     * «или с летово: приехал, когда телефон ВИДИТ сеть». К школьной сети он
     * не подключается — пароля нет и не надо, — но она появляется в эфире
     * ровно тогда, когда он приехал. Всё остальное (дом) ловится по
     * подключению: это точнее и не зависит от радиуса.
     */
    val autoVisibleFlow = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_VISIBLE].orEmpty()
        if (raw.isBlank()) emptySet()
        else runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun setAutoVisible(ssid: String, on: Boolean) {
        if (ssid.isBlank()) return
        context.dataStore.edit { prefs ->
            val cur = runCatching {
                val a = org.json.JSONArray(prefs[KEY_AUTO_VISIBLE].orEmpty())
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList()).toMutableSet()
            if (on) cur.add(ssid) else cur.remove(ssid)
            prefs[KEY_AUTO_VISIBLE] = org.json.JSONArray(cur.toList()).toString()
        }
    }

    /**
     * Дело, которое место начинает само по приезду: имя места → название и
     * категория. Владелец (08.09.2026): «вышел из машины и через некоторое
     * время подсоединился к Wi-Fi Летова — ставится „Летово, забираю Серёжу“,
     * потому что скорее всего это оно». Ключ — ИМЯ места, не SSID: у одного
     * места может быть две сети, а дело одно. Заводское значение — ровно этот
     * случай; правится в настройках автопилота. Пустая строка в хранилище —
     * владелец всё убрал, заводское не возвращается.
     */
    val autoPlaceDealsFlow = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_PLACE_DEALS]
        if (raw == null) FACTORY_PLACE_DEALS
        else runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val d = o.optJSONObject(k)
                PlaceDeal(d?.optString("title").orEmpty(), d?.optString("category").orEmpty())
            }
        }.getOrDefault(emptyMap())
    }

    /** Пустое [title] — у места дела больше нет. */
    suspend fun setAutoPlaceDeal(place: String, title: String, category: String) {
        if (place.isBlank()) return
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_AUTO_PLACE_DEALS]?.let { raw ->
                runCatching { org.json.JSONObject(raw) }.getOrNull()
            } ?: org.json.JSONObject().also { o ->
                // Первая правка: заводское переезжает в хранилище целиком,
                // иначе оно бы исчезло вместе с ключом.
                for ((k, v) in FACTORY_PLACE_DEALS) {
                    o.put(k, org.json.JSONObject().put("title", v.title).put("category", v.category))
                }
            }
            // Одно место — одна запись, без регистра.
            val same = cur.keys().asSequence().filter { it.equals(place.trim(), ignoreCase = true) }.toList()
            same.forEach { cur.remove(it) }
            if (title.isNotBlank()) {
                cur.put(
                    place.trim(),
                    org.json.JSONObject().put("title", title.trim()).put("category", category.trim()),
                )
            }
            prefs[KEY_AUTO_PLACE_DEALS] = cur.toString()
        }
    }

    // ---- Метки NFC: наклейка = засечка ----

    /**
     * Метки владельца. На самой наклейке лежит только идентификатор — что
     * она делает, живёт здесь и правится без перезаписи метки.
     */
    val nfcTagsFlow = context.dataStore.data.map { prefs ->
        NfcTag.listFromJson(prefs[KEY_NFC_TAGS].orEmpty())
    }

    /** Добавить или заменить метку по её идентификатору. */
    suspend fun saveNfcTag(tag: NfcTag) {
        context.dataStore.edit { prefs ->
            val cur = NfcTag.listFromJson(prefs[KEY_NFC_TAGS].orEmpty()).toMutableList()
            val i = cur.indexOfFirst { it.id == tag.id }
            if (i >= 0) cur[i] = tag else cur.add(tag)
            prefs[KEY_NFC_TAGS] = NfcTag.listToJson(cur)
        }
    }

    suspend fun removeNfcTag(id: String) {
        context.dataStore.edit { prefs ->
            val cur = NfcTag.listFromJson(prefs[KEY_NFC_TAGS].orEmpty()).filter { it.id != id }
            prefs[KEY_NFC_TAGS] = NfcTag.listToJson(cur)
        }
    }

    /**
     * Bluetooth-устройство машины: имя для глаз и адрес для узнавания.
     * Имя система отдаёт не всегда (нет кэша, нет BLUETOOTH_CONNECT), адрес
     * приходит с каждым ACL-событием — по нему машина узнаётся надёжно.
     */
    val autoCarBtFlow = context.dataStore.data.map { it[KEY_AUTO_CAR_BT] ?: "" }
    val autoCarBtAddrFlow = context.dataStore.data.map { it[KEY_AUTO_CAR_BT_ADDR] ?: "" }
    suspend fun setAutoCarBt(value: String, address: String = "") {
        context.dataStore.edit {
            it[KEY_AUTO_CAR_BT] = value.trim()
            it[KEY_AUTO_CAR_BT_ADDR] = address.trim()
        }
    }

    /**
     * Машина подключилась — начать «Поездку на машине» сразу, без вопроса.
     * Владелец: «просто всегда переключать текущее дело на передвижение на
     * машине». Выключено — остаётся старый вопрос «сел в машину?».
     */
    val autoCarStartFlow = context.dataStore.data.map { it[KEY_AUTO_CAR_START] ?: true }
    suspend fun setAutoCarStart(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAR_START] = value }
    }

    /** Приезд в известную сеть закрывает открытое «Передвижение» сам. */
    val autoArriveFlow = context.dataStore.data.map { it[KEY_AUTO_ARRIVE] ?: true }
    suspend fun setAutoArrive(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_ARRIVE] = value }
    }

    val autoLeaveAskFlow = context.dataStore.data.map { it[KEY_AUTO_LEAVE_ASK] ?: true }
    suspend fun setAutoLeaveAsk(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_LEAVE_ASK] = value }
    }

    val autoCarAskFlow = context.dataStore.data.map { it[KEY_AUTO_CAR_ASK] ?: true }
    suspend fun setAutoCarAsk(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAR_ASK] = value }
    }

    val autoStillAskFlow = context.dataStore.data.map { it[KEY_AUTO_STILL_ASK] ?: true }
    suspend fun setAutoStillAsk(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_STILL_ASK] = value }
    }

    /**
     * Телефон двадцать минут в движении после отъезда, машины нет — начать
     * «Дорогу пешком» с момента отъезда самому. Владелец (27.09.2026): «вышел
     * из дома и телефон двигается минут двадцать — это передвижение пешком».
     * Выключено — остаётся только вопрос «уехал?».
     */
    val autoWalkStartFlow = context.dataStore.data.map { it[KEY_AUTO_WALK_START] ?: true }
    suspend fun setAutoWalkStart(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_WALK_START] = value }
    }

    /**
     * Встречи из календаря телефона — в ленту как работа (владелец,
     * 27.09.2026: «брать мой календарь и ставить встречи как „работу“»).
     * Категория — выбор владельца, заводская — созвоны; календари — набор
     * имён, пустой — только основной календарь аккаунта.
     */
    val autoCalOnFlow = context.dataStore.data.map { it[KEY_AUTO_CAL_ON] ?: true }
    suspend fun setAutoCalOn(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAL_ON] = value }
    }

    val autoCalCategoryFlow = context.dataStore.data.map {
        it[KEY_AUTO_CAL_CATEGORY]?.takeIf { c -> c.isNotBlank() }
            ?: ru.zf.pravka.core.CalendarRules.DEFAULT_CATEGORY
    }
    suspend fun setAutoCalCategory(value: String) {
        context.dataStore.edit { it[KEY_AUTO_CAL_CATEGORY] = value.trim() }
    }

    /** null — не выбирали (только основной календарь); пустой набор — ничего не смотреть. */
    // ---- «Сегодня» (Правка 4.0) ----

    /** Время сна, минуты от полуночи: отсюда «до сна 4 ч 40 м» и «дела не влезают на N м». */
    val todayBedtimeFlow = context.dataStore.data.map { it[KEY_TODAY_BEDTIME] ?: TODAY_BEDTIME_DEFAULT }
    suspend fun setTodayBedtime(min: Int) {
        // После полуночи — минутами сверх суток (01:00 — 1500): сон того же дня.
        context.dataStore.edit { it[KEY_TODAY_BEDTIME] = min.coerceIn(0, 26 * 60) }
    }

    /** Город погоды на «Сегодня»; пусто — ряд погоды не показывается. */
    val weatherCityFlow = context.dataStore.data.map { it[KEY_WEATHER_CITY] ?: WEATHER_CITY_DEFAULT }
    suspend fun setWeatherCity(city: String) {
        context.dataStore.edit { it[KEY_WEATHER_CITY] = city.trim() }
    }

    /**
     * Календари в ленте «Сегодня»: null — те же, что выбраны для встреч
     * автопилота ([autoCalendarsFlow]); пустой набор — ни одного.
     */
    val todayCalendarsFlow: kotlinx.coroutines.flow.Flow<Set<String>?> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_TODAY_CALENDARS] ?: return@map null
        runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
    }
    suspend fun setTodayCalendars(names: Set<String>?) {
        context.dataStore.edit {
            if (names == null) it.remove(KEY_TODAY_CALENDARS)
            else it[KEY_TODAY_CALENDARS] = org.json.JSONArray(names.toList()).toString()
        }
    }

    /** Какие отметки прикреплять к ленте «Сегодня»: sport, food, money, dela. */
    val todayMarksFlow = context.dataStore.data.map { prefs ->
        (prefs[KEY_TODAY_MARKS] ?: TODAY_MARKS_DEFAULT).split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }
    suspend fun setTodayMarks(marks: Set<String>) {
        context.dataStore.edit { it[KEY_TODAY_MARKS] = marks.joinToString(",") }
    }

    /**
     * Тихая строка состояния под днём недели «Сегодня» — сон, HRV, форма
     * (баг №13, 07.10.2026: «не будет ли нагружать? Подумай»). С завода
     * включена; мешает — выключается в настройках «Сегодня».
     */
    val todayStateFlow = context.dataStore.data.map { it[KEY_TODAY_STATE] ?: true }
    suspend fun setTodayState(on: Boolean) {
        context.dataStore.edit { it[KEY_TODAY_STATE] = on }
    }

    /**
     * Месячный бюджет трат, рубли; 0 — не задан, и полоса на плашке Денег не
     * рисуется (лимит дня = бюджет / дней в месяце, DESIGN §8).
     */
    val moneyMonthBudgetFlow = context.dataStore.data.map { it[KEY_MONEY_MONTH_BUDGET] ?: 0L }
    suspend fun setMoneyMonthBudget(rub: Long) {
        context.dataStore.edit { it[KEY_MONEY_MONTH_BUDGET] = rub.coerceAtLeast(0L) }
    }

    val autoCalendarsFlow: kotlinx.coroutines.flow.Flow<Set<String>?> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_CALENDARS]
        if (raw == null) null
        else runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Дело по подъёму в будни (владелец, 27.09.2026: «дело по подъёму в будни
     * — сборы детей с пн по пт»): телефон нашёл ночь, подъём в будний день
     * между пятью и десятью утра — с момента подъёма начинается это дело.
     * Заводское — «Сборы детей» [Семья]; null — владелец убрал, заводское
     * не возвращается (пустая строка в хранилище).
     */
    val autoWakeDealFlow: kotlinx.coroutines.flow.Flow<PlaceDeal?> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTO_WAKE_DEAL]
        if (raw == null) FACTORY_WAKE_DEAL
        else runCatching {
            val o = org.json.JSONObject(raw)
            PlaceDeal(o.optString("title"), o.optString("category"))
        }.getOrNull()?.takeIf { it.title.isNotBlank() }
    }

    /** Пустое [title] — дела по подъёму больше нет. */
    suspend fun setAutoWakeDeal(title: String, category: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_WAKE_DEAL] = if (title.isBlank()) "" else {
                org.json.JSONObject().put("title", title.trim()).put("category", category.trim()).toString()
            }
        }
    }

    /**
     * Вопросы автопилота — плашкой сверху (строкой пилюли «З»), когда экран
     * включён и открыт; пуш тогда ложится в шторку тихо. Владелец
     * (28.09.2026): «давай всё переведём в эти плашки сверху». Выключено —
     * только пуши, как раньше.
     */
    val autoPlatesFlow = context.dataStore.data.map { it[KEY_AUTO_PLATES] ?: true }
    suspend fun setAutoPlates(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_PLATES] = value }
    }

    /**
     * Отбой по телефону (владелец, 27.09.2026: «зарядку телефона, бездвижение
     * и выключенный экран — как отбой»): поставил на зарядку вечером, экран
     * погас, двадцать минут без движения — вечернее дело закрывается моментом
     * отбоя, а утренний сон начинается не раньше него.
     */
    val autoBedtimeFlow = context.dataStore.data.map { it[KEY_AUTO_BEDTIME] ?: true }
    suspend fun setAutoBedtime(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_BEDTIME] = value }
    }

    /** [current] — что смотрится сейчас (с заводским «только основной» уже раскрытым). */
    suspend fun setAutoCalendar(name: String, on: Boolean, current: Set<String>) {
        if (name.isBlank()) return
        context.dataStore.edit { prefs ->
            val cur = current.toMutableSet()
            if (on) cur.add(name) else cur.remove(name)
            // Пустой набор пишем явно: «[]» — ничего не смотреть, а не «с завода».
            prefs[KEY_AUTO_CALENDARS] = org.json.JSONArray(cur.toList()).toString()
        }
    }

    /**
     * Кто слушает диктовку. true — встроенный микрофон телефона: Bluetooth
     * машины и наушники диктовку не перехватывают (владелец: «когда еду в
     * машине, Правка меня не слышит» — салонный микрофон далеко и глухо).
     * false — Bluetooth-гарнитура: перед тейком поднимается SCO и слушает её
     * микрофон (`provider/MicRouting.kt`). Переключается кружком в веере
     * шестерёнки над «П» (`trigger/StackSettingsController.kt`) и тумблером
     * в Общих — это одно и то же состояние.
     */
    val phoneMicOnlyFlow = context.dataStore.data.map { it[KEY_PHONE_MIC_ONLY] ?: true }
    suspend fun setPhoneMicOnly(value: Boolean) {
        context.dataStore.edit { it[KEY_PHONE_MIC_ONLY] = value }
    }

    /** Иконки вместо букв «П/З/Д/Т» на плавающих кнопках — как в нижней ленте. */
    val modeIconsFlow = context.dataStore.data.map { it[KEY_MODE_ICONS] ?: false }
    suspend fun setModeIcons(value: Boolean) {
        context.dataStore.edit { it[KEY_MODE_ICONS] = value }
    }

    val sportNotifyFlow = context.dataStore.data.map { it[KEY_SPORT_NOTIFY] ?: true }
    suspend fun sportNotify(): Boolean = sportNotifyFlow.first()
    suspend fun setSportNotify(value: Boolean) {
        context.dataStore.edit { it[KEY_SPORT_NOTIFY] = value }
    }

    /** Автогалочки в базу «Дневник» Notion: зарядка, сделано, feel, колено, вес. */
    val notionDiaryFlow = context.dataStore.data.map { it[KEY_NOTION_DIARY] ?: true }
    suspend fun notionDiary(): Boolean = notionDiaryFlow.first()
    suspend fun setNotionDiary(value: Boolean) {
        context.dataStore.edit { it[KEY_NOTION_DIARY] = value }
    }

    /**
     * Вся жизнь в Notion раз в час: лента, еда, тренировки, силовые, зарядка,
     * дни, паттерны с подтверждениями. Владелец: «чтобы я не выгружал csv».
     */
    val notionLifeFlow = context.dataStore.data.map { it[KEY_NOTION_LIFE] ?: true }
    suspend fun notionLife(): Boolean = notionLifeFlow.first()
    suspend fun setNotionLife(value: Boolean) {
        context.dataStore.edit { it[KEY_NOTION_LIFE] = value }
    }

    /** Хаб «Правка: разборы» — под ним приложение ищет свои базы по названиям. */
    val notionLifeHubFlow = context.dataStore.data.map {
        it[KEY_NOTION_LIFE_HUB]?.ifBlank { null } ?: NotionLifeSync.HUB_DEFAULT
    }
    suspend fun notionLifeHub(): String = notionLifeHubFlow.first()
    suspend fun setNotionLifeHub(value: String) {
        context.dataStore.edit { it[KEY_NOTION_LIFE_HUB] = value.trim() }
    }

    // ---- Спорт (вкладка на кэше intervals.icu) ----

    /** Сколько дней тренировок и здоровья держим в кэше вкладки «Спорт». */
    val sportDaysFlow = context.dataStore.data.map { it[KEY_SPORT_DAYS] ?: SPORT_DAYS_DEFAULT }
    suspend fun sportDays(): Int = sportDaysFlow.first()
    suspend fun setSportDays(value: Int) {
        context.dataStore.edit { it[KEY_SPORT_DAYS] = value.coerceIn(14, 400) }
    }

    // ---- Еда ----

    /** Кнопка «Е» на экране: сказал, что съел — получил КБЖУ. */
    val eEnabledFlow = context.dataStore.data.map { it[KEY_E_ENABLED] ?: true }
    suspend fun setEEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_E_ENABLED] = value }
    }

    val foodKcalFlow = context.dataStore.data.map { it[KEY_FOOD_KCAL] ?: FOOD_KCAL_DEFAULT }
    val foodProteinFlow = context.dataStore.data.map { it[KEY_FOOD_PROTEIN] ?: FOOD_PROTEIN_DEFAULT }
    val foodFatFlow = context.dataStore.data.map { it[KEY_FOOD_FAT] ?: FOOD_FAT_DEFAULT }
    val foodCarbsFlow = context.dataStore.data.map { it[KEY_FOOD_CARBS] ?: FOOD_CARBS_DEFAULT }

    suspend fun foodTargets(): Targets = Targets(
        kcal = foodKcalFlow.first(),
        protein = foodProteinFlow.first(),
        fat = foodFatFlow.first(),
        carbs = foodCarbsFlow.first(),
    )

    data class Targets(val kcal: Int, val protein: Int, val fat: Int, val carbs: Int)

    suspend fun setFoodTargets(kcal: Int, protein: Int, fat: Int, carbs: Int) {
        context.dataStore.edit {
            it[KEY_FOOD_KCAL] = kcal.coerceIn(800, 6000)
            it[KEY_FOOD_PROTEIN] = protein.coerceIn(0, 400)
            it[KEY_FOOD_FAT] = fat.coerceIn(0, 300)
            it[KEY_FOOD_CARBS] = carbs.coerceIn(0, 800)
        }
    }

    /** КБЖУ дня уезжает в wellness intervals.icu (там эти поля пустуют). */
    val foodToIcuFlow = context.dataStore.data.map { it[KEY_FOOD_TO_ICU] ?: true }
    suspend fun foodToIcu(): Boolean = foodToIcuFlow.first()
    suspend fun setFoodToIcu(value: Boolean) {
        context.dataStore.edit { it[KEY_FOOD_TO_ICU] = value }
    }

    /**
     * Съеденное приписывается к записи «Еда» в ленте Засечки. Приписывается -
     * и только: сама лента новых записей от еды не отращивает, её инварианты
     * трогать нельзя (см. README).
     */
    val foodToRibbonFlow = context.dataStore.data.map { it[KEY_FOOD_TO_RIBBON] ?: true }
    suspend fun foodToRibbon(): Boolean = foodToRibbonFlow.first()
    suspend fun setFoodToRibbon(value: Boolean) {
        context.dataStore.edit { it[KEY_FOOD_TO_RIBBON] = value }
    }

    val fabSizeFlow = context.dataStore.data.map { it[KEY_FAB_SIZE] ?: FAB_SIZE_DEFAULT }
    val fabAlphaFlow = context.dataStore.data.map { it[KEY_FAB_ALPHA] ?: FAB_ALPHA_DEFAULT }

    suspend fun setFabSize(dp: Int) {
        context.dataStore.edit { it[KEY_FAB_SIZE] = dp.coerceIn(36, 72) }
    }

    suspend fun setFabAlpha(alpha: Float) {
        context.dataStore.edit { it[KEY_FAB_ALPHA] = alpha.coerceIn(0.15f, 1f) }
    }

    // Ширина бегущей строки — одна на все четыре кнопки; на экране режется
    // так, чтобы кнопка и поле рядом с ней остались видны.
    val tickerWidthFlow = context.dataStore.data.map { it[KEY_TICKER_WIDTH] ?: TICKER_WIDTH_DEFAULT }
    suspend fun setTickerWidth(dp: Int) {
        context.dataStore.edit { it[KEY_TICKER_WIDTH] = dp.coerceIn(TICKER_WIDTH_MIN, TICKER_WIDTH_MAX) }
    }

    // Где всплывает пилюля диктовки (владелец, 26.09.2026, по образцу Gemini).
    // Сначала было «снизу над клавиатурой», тем же днём: «пускай сверху
    // вылезает!» — сверху стало заводским; «Снизу» осталось выбором, «У
    // кнопки» — прежнее место, откат одним движением. Кто успел выбрать «У
    // кнопки» в сборке с тумблером да/нет, там и остаётся.
    val tickerPlaceFlow = context.dataStore.data.map { p ->
        ru.zf.pravka.core.PillGeometry.Place.fromKey(p[KEY_TICKER_PLACE])
            ?: if (p[KEY_TICKER_BOTTOM] == false) ru.zf.pravka.core.PillGeometry.Place.BESIDE
            else ru.zf.pravka.core.PillGeometry.Place.TOP
    }
    suspend fun setTickerPlace(place: ru.zf.pravka.core.PillGeometry.Place) {
        context.dataStore.edit { it[KEY_TICKER_PLACE] = place.key }
    }

    // Плотность стекла пилюли: «прозрачнее… просто прозрачность очень сильно
    // повысить». Текст и кружок голоса ею не гаснут — только стекло.
    val tickerDensityFlow = context.dataStore.data.map { it[KEY_TICKER_DENSITY] ?: ru.zf.pravka.core.PillLook.DENSITY_DEFAULT }
    suspend fun setTickerDensity(value: Float) {
        context.dataStore.edit {
            it[KEY_TICKER_DENSITY] = value.coerceIn(ru.zf.pravka.core.PillLook.DENSITY_MIN, ru.zf.pravka.core.PillLook.DENSITY_MAX)
        }
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

    // Разноска стоит третьей в связке: по умолчанию под «З».
    suspend fun rFabPosition(screenKey: String): Pair<Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("rfab_x_$screenKey")] ?: 0.92f
        val y = prefs[floatPreferencesKey("rfab_y_$screenKey")] ?: 0.75f
        return x to y
    }

    suspend fun setRFabPosition(screenKey: String, xFraction: Float, yFraction: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("rfab_x_$screenKey")] = xFraction
            it[floatPreferencesKey("rfab_y_$screenKey")] = yFraction
        }
    }

    // Деньги — в связке после «Д»: по умолчанию под ней.
    suspend fun mFabPosition(screenKey: String): Pair<Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("mfab_x_$screenKey")] ?: 0.92f
        val y = prefs[floatPreferencesKey("mfab_y_$screenKey")] ?: 0.88f
        return x to y
    }

    suspend fun setMFabPosition(screenKey: String, xFraction: Float, yFraction: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("mfab_x_$screenKey")] = xFraction
            it[floatPreferencesKey("mfab_y_$screenKey")] = yFraction
        }
    }

    // Тело стоит четвёртым в связке: по умолчанию под «Д».
    suspend fun eFabPosition(screenKey: String): Pair<Float, Float> {
        val prefs = context.dataStore.data.first()
        val x = prefs[floatPreferencesKey("efab_x_$screenKey")] ?: 0.92f
        val y = prefs[floatPreferencesKey("efab_y_$screenKey")] ?: 0.88f
        return x to y
    }

    suspend fun setEFabPosition(screenKey: String, xFraction: Float, yFraction: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("efab_x_$screenKey")] = xFraction
            it[floatPreferencesKey("efab_y_$screenKey")] = yFraction
        }
    }

    // ---- Обновления ----

    // Проверять раз в сутки и тянуть новую сборку самому. Включено: смысл
    // затеи в том, чтобы владелец не ходил за APK руками.
    val updAutoFlow = context.dataStore.data.map { it[KEY_UPD_AUTO] ?: true }
    suspend fun setUpdAuto(value: Boolean) {
        context.dataStore.edit { it[KEY_UPD_AUTO] = value }
    }

    // Сборка весит десятки мегабайт - по умолчанию качаем только по Wi-Fi,
    // а на мобильной сети показываем уведомление и ждём тапа.
    val updMobileFlow = context.dataStore.data.map { it[KEY_UPD_MOBILE] ?: false }
    suspend fun setUpdMobile(value: Boolean) {
        context.dataStore.edit { it[KEY_UPD_MOBILE] = value }
    }

    // Откуда брать build-info.txt. Пусто = адрес по умолчанию (ветка
    // apk-builds этого репозитория); поле нужно на случай переезда.
    val updUrlFlow = context.dataStore.data.map { it[KEY_UPD_URL].orEmpty() }
    suspend fun setUpdUrl(value: String) {
        context.dataStore.edit { it[KEY_UPD_URL] = value.trim() }
    }

    /**
     * Из какой ВЕТКИ принимать обновления. Пусто = из своей, той, из которой
     * собрана стоящая сборка (BuildConfig.BUILD_BRANCH).
     *
     * Поле обязано быть: работа переезжает между ветками, и после слияния в
     * основную линию имя ветки перестанет совпадать — обновления молча
     * прекратились бы. Здесь их возвращают одной строкой, без новой сборки.
     */
    val updBranchFlow = context.dataStore.data.map { it[KEY_UPD_BRANCH].orEmpty() }
    suspend fun setUpdBranch(value: String) {
        context.dataStore.edit { it[KEY_UPD_BRANCH] = value.trim() }
    }
}
