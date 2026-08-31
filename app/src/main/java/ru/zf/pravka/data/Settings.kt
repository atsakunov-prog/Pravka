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
        const val MODEL_OPUS = "claude-opus-5"   // redo chips + разбор Засечки

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
        private val KEY_LEARN_AUTO = booleanPreferencesKey("learn_auto_capture")

        // Засечка (timesheet).
        private val KEY_Z_ENABLED = booleanPreferencesKey("z_enabled")
        private val KEY_STACK_IDLE = booleanPreferencesKey("buttons_stack_idle")
        private val KEY_Z_GAP_MIN = intPreferencesKey("z_gap_min")
        private val KEY_Z_DAY_START = intPreferencesKey("z_day_start")
        private val KEY_Z_DAY_END = intPreferencesKey("z_day_end")
        private val KEY_Z_WEBHOOK = stringPreferencesKey("z_webhook_url")
        private val KEY_Z_CALLS = booleanPreferencesKey("z_calls_to_ribbon")
        private val KEY_Z_CALL_CATEGORY = stringPreferencesKey("z_call_category")
        private val KEY_Z_IMMERSIVE_MIN = intPreferencesKey("z_immersive_min")
        private val KEY_Z_CHECKINS = booleanPreferencesKey("z_checkins")
        // Разноска: третья кнопка «Д» (она про дела).
        private val KEY_R_ENABLED = booleanPreferencesKey("r_enabled")
        private val KEY_ICU_ATHLETE = stringPreferencesKey("icu_athlete_id")
        private val KEY_ICU_KEY = stringPreferencesKey("icu_api_key")
        private val KEY_TODOIST_TOKEN = stringPreferencesKey("todoist_token")

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
        private val KEY_Z_PARALLEL_AUTO = booleanPreferencesKey("z_parallel_auto")
        // Самообновление из ветки apk-builds.
        private val KEY_UPD_AUTO = booleanPreferencesKey("upd_auto")
        private val KEY_UPD_MOBILE = booleanPreferencesKey("upd_mobile")
        private val KEY_UPD_URL = stringPreferencesKey("upd_url")
        private val KEY_ANALYSIS_NIGHTLY = booleanPreferencesKey("analysis_nightly")
        private val KEY_ANALYSIS_CONTEXT = stringPreferencesKey("analysis_context")
        private val KEY_AUTO_PLACES = stringPreferencesKey("auto_places")
        private val KEY_AUTO_SEEN = stringPreferencesKey("auto_seen_ssids")
        private val KEY_AUTO_CAR_BT = stringPreferencesKey("auto_car_bt")
        private val KEY_AUTO_ARRIVE = booleanPreferencesKey("auto_arrive_close")
        private val KEY_AUTO_LEAVE_ASK = booleanPreferencesKey("auto_leave_ask")
        private val KEY_AUTO_CAR_ASK = booleanPreferencesKey("auto_car_ask")
        private val KEY_AUTO_STILL_ASK = booleanPreferencesKey("auto_still_ask")
        private val KEY_NOTION_DIARY = booleanPreferencesKey("notion_diary_push")

        const val FAB_SIZE_DEFAULT = 48
        const val FAB_ALPHA_DEFAULT = 0.35f

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

    // Todoist: личный API-токен (Todoist → Настройки → Интеграции →
    // Разработчик). Прямой REST, без посредников - как и у intervals.icu.
    val todoistTokenFlow = context.dataStore.data.map { it[KEY_TODOIST_TOKEN] ?: "" }
    suspend fun todoistToken(): String = todoistTokenFlow.first()
    suspend fun setTodoistToken(value: String) {
        context.dataStore.edit { it[KEY_TODOIST_TOKEN] = value.trim() }
    }

    // Разноска: кнопка «Д» на экране. Включена по умолчанию - она и есть
    // третий режим; выключается тем же тумблером, что и «З».
    val rEnabledFlow = context.dataStore.data.map { it[KEY_R_ENABLED] ?: true }
    suspend fun setREnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_R_ENABLED] = value }
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
    val tEnabledFlow = context.dataStore.data.map { it[KEY_T_ENABLED] ?: true }
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

    /**
     * Звонки и пожиратели внимания — ПАРАЛЛЕЛЬНЫМ треком. Прежние врезки
     * владелец выключил по делу: «очень сильно засоряет ленту, и не всегда это
     * потеря — я готовил еду и смотрел про часы». Они резали дело и отнимали у
     * него минуты. Теперь не режут и не отнимают: ложатся поверх, вторым
     * треком, и еда остаётся едой — поэтому включено по умолчанию. Сон-вставка
     * живёт отдельно и не выключается.
     */
    val zParallelAutoFlow = context.dataStore.data.map { it[KEY_Z_PARALLEL_AUTO] ?: true }
    suspend fun setZParallelAuto(value: Boolean) {
        context.dataStore.edit { it[KEY_Z_PARALLEL_AUTO] = value }
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

    suspend fun removeAutoSeen(ssid: String) {
        context.dataStore.edit { prefs ->
            val o = runCatching { org.json.JSONObject(prefs[KEY_AUTO_SEEN].orEmpty()) }
                .getOrDefault(org.json.JSONObject())
            o.remove(ssid)
            prefs[KEY_AUTO_SEEN] = o.toString()
        }
    }

    /** Имя Bluetooth-устройства машины: подключился — «сел в машину?». */
    val autoCarBtFlow = context.dataStore.data.map { it[KEY_AUTO_CAR_BT] ?: "" }
    suspend fun setAutoCarBt(value: String) {
        context.dataStore.edit { it[KEY_AUTO_CAR_BT] = value.trim() }
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
     * Слушать ТОЛЬКО встроенный микрофон телефона: Bluetooth машины и
     * наушники не перехватывают диктовку. Владелец: «когда еду в машине,
     * Правка меня не слышит» — салонный микрофон далеко и глухо.
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
}
