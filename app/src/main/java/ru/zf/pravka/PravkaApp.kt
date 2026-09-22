package ru.zf.pravka

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.zf.pravka.core.DictionaryApplier
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.PaceStore
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Recordings
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.LiveDraft
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.data.WavFile
import android.os.SystemClock
import java.io.File
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.DictMiner
import ru.zf.pravka.provider.WhisperProvider
import ru.zf.pravka.target.ClipboardTarget

// Plain service locator - the dependency graph is small enough
// that a DI framework would be an unjustified dependency (spec section 14).
class PravkaApp : Application() {

    /**
     * Кто слушает диктовку (true — телефон, false — Bluetooth-гарнитура) —
     * кэш для горячих дорог записи: DictationService стартует на главном
     * потоке, читать DataStore там нельзя. Значение держит коллектор ниже,
     * по умолчанию телефон; переключает значок между «П» и «З».
     */
    @Volatile var phoneMicOnly: Boolean = true

    override fun onCreate() {
        super.onCreate()
        // Копии на диск: раз в час их снимает тик службы, но старт процесса -
        // после обновления APK или перезагрузки телефона - тоже хороший момент
        // (и единственный, если служба доступности почему-то выключена).
        ru.zf.pravka.data.Backups.tick(this) { line -> eventLog.add(line) }
        appScope.launch { settings.phoneMicOnlyFlow.collect { phoneMicOnly = it } }
        // Чистка — на Опус (18.09.2026), даже если в «Моделях» стоял явный Сонет;
        // затем все дороги Опуса и Fable — на Опус 5.5 с новыми усилиями (22.09).
        appScope.launch {
            runCatching { settings.migratePravkaToOpus() }
            runCatching { settings.migrateToOpus55() }
        }
        // Распознавание — по сетевому пути Google (22.09.2026): владелец выбрал
        // облачный движок главным, а лежащее в DataStore старое «офлайн»
        // сильнее нового заводского.
        appScope.launch { runCatching { settings.migrateSpeechToNetwork() } }
        // Тень снята (18.09.2026; владелец: «убери эту тень, она снова запустилась
        // и ест деньги»): её прогоны, застрявшие активными, закрываются, движка
        // больше нет. Затем — ревизия батчей у Anthropic: всё идущее, за чем в
        // приложении нет живого прогона, гасится.
        appScope.launch { runCatching { ru.zf.pravka.core.NightSweep.onStart(this@PravkaApp) } }
        // Режим отладки: транспорт пишет каждый запрос к Claude целиком в
        // свой лог, пока тумблер включён (Настройки → Общее).
        appScope.launch {
            settings.debugLogFlow.collect { on ->
                claudeProvider.requestLogger = if (on) ({ text -> requestLog.add(text) }) else null
            }
        }
        // Сколько идёт запрос — в историю, а ход запроса — тому, кто его
        // показывает (20.09.2026). Та же одна точка на все дороги: здесь
        // известны и дорога, и модель, и длина входа.
        claudeProvider.workStart = { route, model, chars ->
            val expect = paceStore.expect(route, model, chars)
            workWatcher?.invoke(route, expect, false, true)
        }
        // Прошлое дуги — из журнала правок, один раз после обновления
        // (владелец, 20.09.2026: «ты не взял всю статистику, а только начал её
        // собирать. Посмотри, в аппе есть логи»). Журнал — мегабайты, поэтому
        // не на старте службы и не на главном потоке: фоновая корутина.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { paceStore.seedFromHistory(historyLog) }
        }
        claudeProvider.workDone = { route, model, chars, ms, ok ->
            // Замер пишем только с удачного ответа: время упавшего запроса —
            // это время сети, а не время модели.
            if (ok) paceStore.record(route, model, chars, ms)
            workWatcher?.invoke(route, 0L, true, ok)
        }
        // Кэш промпта виден в статистике: транспорт отдаёт расход каждого ответа,
        // сюда падают токены чтения и записи кэша со всех дорог сразу.
        claudeProvider.usageObserver = { r ->
            appScope.launch {
                stats.recordCache(r.cacheReadTokens, r.cacheWriteTokens)
                // И по дороге: доля входа из кэша в строке экрана «$» — единственная
                // проверка, что точка кэша не просто стоит в запросе, а читается.
                stats.recordRouteUsage(r.route, r.inputTokens + r.cacheWriteTokens + r.cacheReadTokens, r.cacheReadTokens)
            }
        }
    }

    /**
     * Кто показывает ход запроса: служба ставит сюда свою дугу на стекле
     * диска. Отдельным полем, а не подпиской в транспорте: на `workStart`
     * поле одно, и хранилище истории уже его заняло.
     */
    var workWatcher: ((route: String, expectMs: Long, done: Boolean, ok: Boolean) -> Unit)? = null

    val settings by lazy { Settings(this) }
    /** История «сколько идёт запрос» по парам «дорога + модель». */
    val paceStore by lazy { PaceStore(this) }
    val promptStore by lazy { PromptStore(this) }
    val stats by lazy { Stats(this) }
    val dictionaryStore by lazy { DictionaryStore(this) }
    val historyLog by lazy { HistoryLog(this) }
    val transcriptionLog by lazy { TranscriptionLog(this) }
    val liveDraft by lazy { LiveDraft(this) }
    val eventLog by lazy { EventLog(this) }
    /** Лог запросов к Claude в режиме отладки: один запрос — десятки килобайт, потолок 4 МБ. */
    val requestLog by lazy { EventLog(this, "claude-requests.log", maxBytes = 4L * 1024 * 1024) }

    val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Spec 6.1 said 25s, sized for the proxy. Without streaming the
            // API returns the whole body only after generation completes, and
            // real long dictations (5000+ chars) already hit 25s.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // UI-independent scope: learning accept/reject must survive tab switches
    // and the settings screen closing (rememberCoroutineScope dies with them -
    // that was the "принял четыре правила, записалось одно" bug).
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
    val learnLog by lazy { ru.zf.pravka.data.EventLog(this, "learning.log") }
    val rulesStore by lazy { ru.zf.pravka.data.RulesStore(this) }
    val learnStore by lazy { ru.zf.pravka.data.LearnStore(this) }
    val editWatch by lazy { ru.zf.pravka.data.EditWatchStore(this) }
    /** Журнал правок руками: надиктовано → модель → владелец, навсегда. */
    val corrections by lazy { ru.zf.pravka.data.CorrectionsLog(this) }
    val evalStore by lazy { ru.zf.pravka.data.EvalStore(this) }
    val claudeProvider by lazy { ClaudeProvider(settings, promptStore, httpClient, rulesStore) }
    // Ночной разбор: батчи Anthropic, память прогонов и движок (core/NightReview.kt).
    val claudeBatches by lazy { ru.zf.pravka.provider.ClaudeBatches(settings, httpClient) }
    val nightReviewStore by lazy { ru.zf.pravka.data.NightReviewStore(this) }
    /**
     * Единый журнал ночных автоматов (17.09.2026; владелец: «единый лог,
     * который будет показывать, что работает, что нет»): разбор, тень, правка
     * промпта и эвал пишут сюда, а не в общий лог службы, — читается в
     * «Разборах» и уходит в лог для Claude Code.
     */
    val nightLog by lazy { EventLog(this, "night.log") }
    /** Когда служба последний раз запускала ночные тики: нет тика двадцать минут — автоматы стоят, табло скажет. */
    @Volatile var lastNightTickMs: Long = 0L
    val nightReview by lazy {
        ru.zf.pravka.core.NightReview(
            settings, claudeBatches, nightReviewStore, dictionaryStore, rulesStore, historyLog,
            transcriptionLog, corrections, promptStore, stats, nightLog,
        )
    }
    // Недельная правка промпта: идеи недели → предложение → измерение → принять или нет (core/PromptTuner.kt).
    val promptVersions by lazy { ru.zf.pravka.data.PromptVersions(this) }
    val promptTuner by lazy {
        ru.zf.pravka.core.PromptTuner(
            settings, claudeBatches, nightReviewStore, promptVersions, promptStore, historyLog, corrections,
            DictionaryApplier(dictionaryStore), claudeProvider, stats, nightLog,
        )
    }
    // Ручное сравнение трёх моделей (Сонет · Опус · Опус low) на диктовках периода — только кнопкой (core/ModelCompare.kt).
    val modelCompare by lazy {
        ru.zf.pravka.core.ModelCompare(
            settings, claudeBatches, nightReviewStore, historyLog, DictionaryApplier(dictionaryStore), claudeProvider, stats, nightLog,
        )
    }
    val dictMiner by lazy { DictMiner(settings, httpClient, stats) }
    val whisperProvider by lazy { WhisperProvider(this, settings) }
    val recordings by lazy { Recordings(this) }

    // Засечка (timesheet): store, the Sheets mirror, phrase -> entry pipeline.
    // Правила разбора Засечки: набор, одобренный владельцем, едет в каждый
    // разбор. Ночное самообучение, которое их предлагало, снято (08.09.2026):
    // «все паттерны уже найдены», а ⭐ над «З» раздражала.
    val zasechkaRules by lazy { ru.zf.pravka.data.RulesStore(this, "zasechka-rules.json") }

    val zasechkaStore by lazy {
        ru.zf.pravka.data.ZasechkaStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
        }
    }
    val zasechkaSync by lazy {
        ru.zf.pravka.data.ZasechkaSync(settings, zasechkaStore, httpClient, eventLog)
    }
    val zasechkaEngine by lazy {
        ru.zf.pravka.core.ZasechkaEngine(
            claudeProvider, zasechkaStore, stats, eventLog, zasechkaSync, appScope,
            zasechkaRules,
        )
    }

    // Todoist: список дел владельца и обратная запись времени в задачу.
    val todoistStore by lazy { ru.zf.pravka.data.TodoistStore(this) }
    val todoistSync by lazy {
        ru.zf.pravka.data.TodoistSync(settings, todoistStore, zasechkaStore, httpClient, eventLog)
    }

    // Разноска: наговор -> дела в Todoist. Разобранное лежит на диске до
    // того, как Todoist его примет (raznoska.json).
    val raznoskaStore by lazy { ru.zf.pravka.data.RaznoskaStore(this) }
    val raznoskaRoutes by lazy { ru.zf.pravka.data.RaznoskaRoutes(this) }
    val raznoskaEngine by lazy {
        ru.zf.pravka.core.RaznoskaEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            store = raznoskaStore,
            routes = raznoskaRoutes,
            todoistStore = todoistStore,
            todoistSync = todoistSync,
            stats = stats,
            eventLog = eventLog,
        )
    }

    // Спорт: кэш тренировочной жизни из intervals.icu и разбор своих
    // тренировок. Сама выгрузка - вторая дорога к тому же API, отдельная от
    // IcuSweeper: тот пишет в ленту, а этот в кэш, который можно потерять.
    val sportStore by lazy { ru.zf.pravka.data.SportStore(this) }
    val icuSportSync by lazy {
        ru.zf.pravka.data.IcuSportSync(settings, sportStore, httpClient, eventLog)
    }

    // Еда: дневник приёмов с КБЖУ. Незаменимые данные - как лента.
    val foodStore by lazy {
        ru.zf.pravka.data.FoodStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
        }
    }
    val openFoodFacts by lazy { ru.zf.pravka.data.OpenFoodFacts(httpClient) }
    val foodEngine by lazy {
        ru.zf.pravka.core.FoodEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            store = foodStore,
            ration = rationBook,
            sportStore = sportStore,
            icu = icuSportSync,
            zasechkaStore = zasechkaStore,
            offf = openFoodFacts,
            settings = settings,
            stats = stats,
            eventLog = eventLog,
        )
    }
    val sportCoach by lazy {
        ru.zf.pravka.core.SportCoach(
            claude = claudeProvider,
            store = sportStore,
            foodStore = foodStore,
            planStore = planStore,
            strengthStore = strengthStore,
            zasechkaStore = zasechkaStore,
            settings = settings,
            stats = stats,
            eventLog = eventLog,
        )
    }

    // Тело: справочники, журнал силовых, план на день.
    //
    // Справочник упражнений читается живым из базы Notion (раз в сутки, кэш на
    // диске), файл в assets — семя и запас без сети: карточка тренировки
    // открывается каждый день, в том числе в подвале на даче. Рацион — пока
    // только из assets. Оба собираются из Notion скриптом tools/gen_reference.py.
    // Паттерны: ночной поиск повторов снят (владелец, 15.09.2026: «паттерны
    // убираем, и поиск их убираем»). Стор остался — в нём лежат найденные
    // раньше паттерны и вердикты владельца, их читает синк Notion.
    val analysisStore by lazy { ru.zf.pravka.data.AnalysisStore(this).also { it.logger = { l -> eventLog.add(l) } } }

    val exerciseBook by lazy { ru.zf.pravka.data.ExerciseBook(this) }
    val rationBook by lazy { ru.zf.pravka.data.RationBook(this) }

    // Журнал силовых — самое незаменимое здесь: подходов нет больше НИГДЕ.
    val strengthStore by lazy {
        ru.zf.pravka.data.StrengthStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
        }
    }

    // План: скелет дня из календаря intervals, правила блока из Notion.
    val planStore by lazy { ru.zf.pravka.data.PlanStore(this) }
    val notionPlanSync by lazy {
        ru.zf.pravka.data.NotionPlanSync(settings, planStore, httpClient, eventLog)
    }
    // Справочник упражнений — живой из базы Notion «Упражнения»; файл сборки
    // остаётся семенем и запасом без сети или без токена.
    val notionExerciseSync by lazy {
        ru.zf.pravka.data.NotionExerciseSync(settings, exerciseBook, httpClient, eventLog)
    }
    // Автогалочки в его базу «Дневник» под хабом «Тело» — зарядка, сделано,
    // feel, колено, вес. Он забросил её тикать руками ровно тогда, когда всё
    // это стал наговаривать сюда. Второе место записи в Notion — базы
    // «Правка: разборы» (notionLifeSync ниже).
    val notionDiarySync by lazy {
        ru.zf.pravka.data.NotionDiarySync(
            settings = settings,
            strengthStore = strengthStore,
            planStore = planStore,
            foodStore = foodStore,
            sportStore = sportStore,
            client = httpClient,
            eventLog = eventLog,
        )
    }
    // Вся жизнь в Notion раз в час — лента, еда, тренировки, силовые,
    // зарядка, дни с телефоном, справочник, паттерны и подтверждения — в
    // базы под хабом «Правка: разборы». Структура баз описана в
    // core/NotionLifeSchema и достраивается приложением само. Владелец:
    // «чтобы оттуда можно было всегда взять актуальную структуру жизни».
    val notionLifeSync by lazy {
        ru.zf.pravka.data.NotionLifeSync(
            context = this,
            settings = settings,
            rows = lifeRows,
            analysis = analysisStore,
            client = httpClient,
            eventLog = eventLog,
            provider = claudeProvider,
            stats = stats,
        )
    }
    // Строки всей жизни по базам — одним сборщиком для Notion и для книги
    // Excel, чтобы выгрузка и синк не разошлись ни на строку.
    val lifeRows by lazy {
        ru.zf.pravka.data.LifeRows(zasechkaStore, foodStore, sportStore, strengthStore, phoneStore)
    }
    // Единственная выгрузка: вся жизнь одной книгой xlsx, лист на базу Notion
    // («Ещё → Выгрузки»). Владелец: «в Excel мне как-то удобнее».
    val lifeExport by lazy { ru.zf.pravka.data.LifeExport(this, lifeRows) }
    val planSync by lazy {
        ru.zf.pravka.core.PlanSync(
            icu = icuSportSync,
            notion = notionPlanSync,
            exercises = notionExerciseSync,
            claude = claudeProvider,
            store = planStore,
            stats = stats,
            eventLog = eventLog,
        )
    }
    val strengthEngine by lazy {
        ru.zf.pravka.core.StrengthEngine(
            store = strengthStore,
            book = exerciseBook,
            planStore = planStore,
            icu = icuSportSync,
            eventLog = eventLog,
        )
    }
    val trafficLight by lazy {
        ru.zf.pravka.core.TrafficLight(sportStore, planStore, strengthStore)
    }

    // Один микрофон на подходы, еду, зарядку и вопросы: намерение решает
    // модель тем же вызовом, что и разбор.
    val bodyEngine by lazy {
        ru.zf.pravka.core.BodyEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            strengthStore = strengthStore,
            strengthEngine = strengthEngine,
            foodEngine = foodEngine,
            book = exerciseBook,
            ration = rationBook,
            planStore = planStore,
            stats = stats,
            eventLog = eventLog,
        )
    }

    // The phone layer: app time, pickups, distractions, calls - counted per
    // day; only сон crosses into the ribbon via the sweeper.
    val phoneStore by lazy { ru.zf.pravka.data.PhoneStore(this) }
    val phoneSweeper by lazy {
        ru.zf.pravka.data.PhoneSweeper(this, phoneStore, zasechkaStore, settings, eventLog, zasechkaSync, appScope)
    }

    // Самообновление: раз в сутки смотрит ветку apk-builds, тянет APK и
    // предлагает поставить. Живёт на тике службы (см. zReminderTick).
    val updates by lazy {
        ru.zf.pravka.data.Updates(this, httpClient, settings, eventLog)
    }

    // intervals.icu: workouts land in the ribbon, Garmin sleep annotates it.
    val icuSweeper by lazy {
        ru.zf.pravka.data.IcuSweeper(settings, zasechkaStore, httpClient, eventLog, zasechkaSync, appScope)
    }

    // The connection pool keeps sockets ~5 min; after a longer gap the CLEAN
    // request pays DNS+TCP+TLS (~300-800ms on LTE). A dictation lasts seconds,
    // so warming the connection when a take STARTS makes the stop->fix hop skip
    // the handshake entirely. Any response counts - only the socket matters.
    @Volatile private var lastWarmAt = 0L
    fun warmClaudeConnection() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWarmAt < 4 * 60 * 1000L) return  // pool still warm
        lastWarmAt = now
        val request = okhttp3.Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .head()
            .build()
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }

    // File-based transcription: for saved recordings and retries. The live
    // Google engine can't read a file, so saved files go through Whisper.
    // Every attempt is logged with metrics (engine, audio length, time, chars).
    suspend fun transcribeDictation(file: File): Result<String> {
        // Ask Whisper which model will really run, so the logged engine matches
        // the one that did the work.
        val fileEngine = whisperProvider.resolveEngine()
        val started = SystemClock.elapsedRealtime()
        val result = whisperProvider.transcribe(file, fileEngine)
        val elapsed = SystemClock.elapsedRealtime() - started
        transcriptionLog.append(
            engine = fileEngine,
            audioMs = WavFile.durationMs(file),
            transcribeMs = elapsed,
            text = result.getOrNull().orEmpty(),
            error = result.exceptionOrNull()?.message,
        )
        return result
    }

    val engine by lazy {
        ProofreadEngine(
            claude = claudeProvider,
            clipboardFallback = ClipboardTarget(this),
            stats = stats,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            history = historyLog,
        )
    }
}
