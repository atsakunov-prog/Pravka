package ru.zf.pravka

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.zf.pravka.core.DictionaryApplier
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PaceStore
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Recordings
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.LiveDraft
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.data.WavFile
import ru.zf.pravka.data.ArchiveSync.Domain as ArchiveDomain
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
    /** Микрофон держит Правка и отдаёт звук распознавателю (`provider/MicFeed.kt`). */
    @Volatile var speechOwnMic: Boolean = true
    /** Когда звенеть «говори» (`core/ReadyChime.kt`). */
    @Volatile var readyChime: ru.zf.pravka.core.ReadyChime.Mode = ru.zf.pravka.core.ReadyChime.Mode.HEADSET
    /** Через сколько звенеть «говори» в наушники: от начала тейка и от переключения, мс. */
    @Volatile var chimeAfterStartMs: Long = ru.zf.pravka.core.ReadyChime.AFTER_START_MS
    @Volatile var chimeAfterSwitchMs: Long = ru.zf.pravka.core.ReadyChime.AFTER_SWITCH_MS

    override fun onCreate() {
        super.onCreate()
        // Где база — решается первым, до любого стора: все они открывают свои
        // файлы в DataRoot.dir, и здесь же закрепляется переезд, подготовленный
        // кнопкой «Перенести базу в папку» (пока в папки ещё никто не пишет).
        ru.zf.pravka.data.DataRoot.init(this)
        // Падение процесса — в crash.log рядом с базой, синхронно: журнал
        // событий пишет асинхронно и до диска не доезжает. Служба при
        // следующем подъёме назовёт причину в «Обновлениях и службе».
        ru.zf.pravka.data.CrashLog.install(this)
        ru.zf.pravka.data.DataRoot.startNote.takeIf { it.isNotBlank() }?.let { eventLog.add("база: $it") }
        // Кто пользуется — сразу за местом базы: от профиля зависят первые же
        // шаги старта (сеять ли наличные владельца, какие режимы живы).
        profileStore.init().takeIf { it.isNotBlank() }?.let { eventLog.add(it) }
        // Входы выключенных режимов из чужих меню («Поделиться», выделение, NFC) —
        // выключены в системе; следим за профилем, чтобы тумблер действовал сразу.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            profileStore.flow.collect { p -> ru.zf.pravka.trigger.ModeEntries.sync(this@PravkaApp, p) }
        }
        if (ru.zf.pravka.data.DataRoot.where.value == ru.zf.pravka.data.DataRoot.Where.FOLDER_NO_ACCESS) {
            eventLog.add("база: папка ${ru.zf.pravka.data.DataRoot.dir(this)} недоступна — нет доступа к файлам")
            ru.zf.pravka.data.DataRoot.notifyNoAccess(this)
        }
        // Копии на диск: раз в час их снимает тик службы, но старт процесса -
        // после обновления APK или перезагрузки телефона - тоже хороший момент
        // (и единственный, если служба доступности почему-то выключена).
        ru.zf.pravka.data.Backups.tick(this) { line -> eventLog.add(line) }
        appScope.launch { settings.phoneMicOnlyFlow.collect { phoneMicOnly = it } }
        appScope.launch { settings.speechOwnMicFlow.collect { speechOwnMic = it } }
        appScope.launch { settings.readyChimeFlow.collect { readyChime = it } }
        appScope.launch { settings.chimeAfterStartFlow.collect { chimeAfterStartMs = it } }
        appScope.launch { settings.chimeAfterSwitchFlow.collect { chimeAfterSwitchMs = it } }
        // Тейк ушёл с молчащего облака на пакет — сбой связи «в деле», в журнал связи.
        ru.zf.pravka.provider.GoogleSpeechSession.cloudEventSink = { why ->
            netProber.live(ru.zf.pravka.core.NetProbe.Target.GOOGLE, why)
        }
        // Чистка — на Опус (18.09.2026), даже если в «Моделях» стоял явный Сонет;
        // затем все дороги Опуса и Fable — на Опус 5.5 с новыми усилиями (22.09);
        // затем чистка — на Сонет 5.5 (30.09). Порядок важен: последняя снимает
        // Опус, который первая кладёт на чистой установке.
        appScope.launch {
            runCatching { settings.migratePravkaToOpus() }
            runCatching { settings.migrateToOpus55() }
            runCatching { settings.migratePravkaToSonnet55() }
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
        // Переразбор истории (core/HistoryFixes.kt): правка разбора, пришедшая
        // с этой сборкой, один раз проходит по накопленному и выводит его заново
        // из сырья — вчерашний пуш не остаётся вчерашним разбором.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ru.zf.pravka.core.HistoryFixes.runPending(this@PravkaApp) { line -> eventLog.add(line) } }
        }
        // Деньги: записи со слов владельца (наличные мимо выписок) — в журнал, один раз.
        // Только у самого владельца: у Марианны это были бы чужие 3,88 млн.
        if (profileStore.owner && profileStore.has(ru.zf.pravka.data.Profile.Mode.MONEY)) {
            appScope.launch { runCatching { moneyEngine.seedManual() } }
        }
        // Общие Деньги через облако семьи (data/MoneyCloudSync.kt): правка
        // уходит через полминуты тишины, а не с каждым нажатием; дальше —
        // тик службы раз в пять минут. Нет входа или Деньги выключены — молчит.
        appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            moneyStore.stateFlow.drop(1).debounce(30_000L)
                .collect { runCatching { moneyCloudSync.sync("правка") } }
        }
        // Архив на компе: пока он подключён, каждый ввод режима уходит через
        // несколько секунд тишины (data/ArchiveSync.kt). Не подключён — сторы
        // не будятся и ничего не слушается.
        appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            archiveSync.link.collectLatest { link -> if (link != null) watchArchive() }
        }
        // Почерк значков (версия 3) — один на процесс: приложение перерисуется
        // само (`Glyphs.gemini` — состояние Compose), кнопки перечитывает служба.
        appScope.launch {
            settings.iconsGeminiFlow.collect { ru.zf.pravka.ui.Glyphs.gemini = it }
        }
        // Режим отладки: транспорт пишет каждый запрос к Claude целиком в
        // свой лог, пока тумблер включён (Настройки → Общее).
        appScope.launch {
            settings.debugLogFlow.collect { on ->
                claudeProvider.requestLogger = if (on) ({ text -> requestLog.add(text) }) else null
            }
        }
        // Сколько идёт запрос — в историю, а ход запроса — тому, кто его
        // показывает (20.09.2026). Та же одна точка на все дороги: здесь
        // известны и дорога, и модель с усилием, и длина входа.
        claudeProvider.workStart = { w ->
            var expect = paceStore.expect(w.route, w.kind, w.photo, w.model, w.effort, w.chars)
            // Развилка «З» — лишь первая секунда: следом почти всегда идёт
            // разбор Засечки той же фразы. Отсчёт обещает оба шага, иначе на
            // кнопке «1,4… 0», а потом заново «9,8… 0» (26.09.2026).
            if (w.route == ModelRoute.ZASECHKA_FORK.key) {
                expect += paceStore.expectNext(ModelRoute.ZASECHKA.key, w.chars)
            }
            workWatcher?.invoke(w.route, expect, false, true)
            liveWork.value = LiveWork(w.route, expect, android.os.SystemClock.uptimeMillis())
        }
        // Прошлое дуги — из журнала правок, один раз после обновления
        // (владелец, 20.09.2026: «ты не взял всю статистику, а только начал её
        // собирать. Посмотри, в аппе есть логи»). Журнал — мегабайты, поэтому
        // не на старте службы и не на главном потоке: фоновая корутина.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { paceStore.seedFromHistory(historyLog) }
        }
        claudeProvider.workDone = { w, ms, ok, cache ->
            // Замер пишем только с удачного ответа: время упавшего запроса —
            // это время сети, а не время модели.
            if (ok) paceStore.record(w.route, w.kind, w.photo, w.model, w.effort, w.chars, ms, cache)
            workWatcher?.invoke(w.route, 0L, true, ok)
            liveWork.update { cur -> if (cur?.route == w.route) null else cur }
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
     * Кто показывает ход запроса: служба ставит сюда отсчёт секунд на
     * занятой кнопке (до 26.09.2026 — дугу по кромке диска). Отдельным полем,
     * а не подпиской в транспорте: на `workStart` поле одно, и хранилище
     * истории уже его заняло.
     */
    var workWatcher: ((route: String, expectMs: Long, done: Boolean, ok: Boolean) -> Unit)? = null

    /**
     * Запрос к Claude, который идёт прямо сейчас, — для приложения (версия 3,
     * 26.09.2026): строка «Причёсываю · ещё 6 с» и секунды в кружке строки
     * ввода считаются из того же обещания, что и секунды на кнопке. Отдельный
     * поток рядом с [workWatcher]: поле у службы одно, и отнимать его у
     * кнопок ради вкладки нельзя. [startedAt] — `SystemClock.uptimeMillis`.
     */
    data class LiveWork(val route: String, val expectMs: Long, val startedAt: Long)

    val liveWork = kotlinx.coroutines.flow.MutableStateFlow<LiveWork?>(null)

    val settings by lazy { Settings(this) }
    /** Кто пользуется установкой и какие режимы включены (data/Profile.kt). */
    internal val profileStore by lazy { ru.zf.pravka.data.ProfileStore(this) }
    /** История «сколько идёт запрос» по тройкам «дорога + модель + усилие». */
    val paceStore by lazy { PaceStore(this) }
    val promptStore by lazy { PromptStore(this) }
    val stats by lazy { Stats(this) }
    val dictionaryStore by lazy { DictionaryStore(this) }
    val historyLog by lazy { HistoryLog(this) }
    val transcriptionLog by lazy { TranscriptionLog(this) }
    val liveDraft by lazy { LiveDraft(this) }
    val eventLog by lazy { EventLog(this) }

    /** Подписки архива на сторы — живут, пока архив подключён (отключили — collectLatest их снимет). */
    private suspend fun watchArchive(): Unit = kotlinx.coroutines.coroutineScope {
        val p = profileStore
        fun watch(domain: ArchiveDomain, flow: kotlinx.coroutines.flow.Flow<*>) {
            launch { flow.drop(1).collect { archiveSync.poke(domain) } }
        }
        TranscriptionLog.onAppend = { archiveSync.poke(ArchiveDomain.PRAVKA) }
        HistoryLog.onAppend = { archiveSync.poke(ArchiveDomain.PRAVKA) }
        if (p.has(ru.zf.pravka.data.Profile.Mode.ZASECHKA)) {
            watch(ArchiveDomain.ZASECHKA, zasechkaStore.entriesFlow)
            watch(ArchiveDomain.ZASECHKA, zasechkaStore.categoriesFlow)
            watch(ArchiveDomain.PHONE, phoneStore.daysFlow)
        }
        if (p.has(ru.zf.pravka.data.Profile.Mode.FOOD)) watch(ArchiveDomain.FOOD, foodStore.mealsFlow)
        if (p.has(ru.zf.pravka.data.Profile.Mode.SPORT)) {
            watch(ArchiveDomain.STRENGTH, strengthStore.sessionsFlow)
            watch(ArchiveDomain.STRENGTH, strengthStore.gtgFlow)
            watch(ArchiveDomain.STRENGTH, strengthStore.rawFlow)
            watch(ArchiveDomain.SPORT, sportStore.talksFlow)
        }
        if (p.has(ru.zf.pravka.data.Profile.Mode.MONEY)) watch(ArchiveDomain.MONEY, moneyStore.stateFlow)
        // Первый проход — сразу после подключения или старта: всё, что не дошло.
        launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { archiveSync.tick() } }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            TranscriptionLog.onAppend = null
            HistoryLog.onAppend = null
        }
    }
    /** Лог запросов к Claude в режиме отладки: один запрос — десятки килобайт, потолок 4 МБ. */
    val requestLog by lazy { EventLog(this, "claude-requests.log", maxBytes = 4L * 1024 * 1024) }

    val httpClient by lazy {
        OkHttpClient.Builder()
            // 10 с, а не 5 (26.09.2026): через VPN рукопожатие с api.anthropic.com
            // бывает дольше пяти секунд, и живое соединение считалось мёртвым.
            .connectTimeout(10, TimeUnit.SECONDS)
            // Spec 6.1 said 25s, sized for the proxy. Without streaming the
            // API returns the whole body only after generation completes, and
            // real long dictations (5000+ chars) already hit 25s.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // UI-independent scope: learning accept/reject must survive tab switches
    // and the settings screen closing (rememberCoroutineScope dies with them -
    // that was the "принял четыре правила, записалось одно" bug).
    // Необработанная ошибка в корутине этого scope раньше убивала ВЕСЬ
    // процесс — вместе со службой доступности и диском на экране, и без
    // строки в журнале. У службы такой перехватчик есть с лета; здесь его не
    // было. Теперь — как у неё: строка в журнал, стек в crash.log, процесс жив.
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                runCatching {
                    ru.zf.pravka.data.CrashLog.note(this, "фон приложения", e, fatal = false)
                    val own = e.stackTrace.firstOrNull { it.className.startsWith("ru.zf.pravka") }
                    eventLog.add(
                        "CRASH (фон приложения) ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}" +
                            (if (own != null) " ← $own" else "")
                    )
                }
            }
    )
    val learnLog by lazy { ru.zf.pravka.data.EventLog(this, "learning.log") }
    val rulesStore by lazy { ru.zf.pravka.data.RulesStore(this) }
    val learnStore by lazy { ru.zf.pravka.data.LearnStore(this) }
    val editWatch by lazy { ru.zf.pravka.data.EditWatchStore(this) }
    /** Журнал правок руками: надиктовано → модель → владелец, навсегда. */
    val corrections by lazy { ru.zf.pravka.data.CorrectionsLog(this) }
    val evalStore by lazy { ru.zf.pravka.data.EvalStore(this) }
    val claudeProvider by lazy {
        ClaudeProvider(settings, promptStore, httpClient, rulesStore).also { p ->
            p.transportLog = { line ->
                eventLog.add(line)
                // Не достучались или соединение умерло молча — это сбой связи «в деле»:
                // в журнал связи и меткой на полосе Claude (`NetProber.live`).
                if (line.contains("не достучались") || line.contains("соединение мёртвое")) {
                    netProber.live(ru.zf.pravka.core.NetProbe.Target.CLAUDE, line.removePrefix("claude: "))
                }
            }
            p.author = {
                profileStore.current?.let { ru.zf.pravka.core.Prompts.Author(it.name, it.female, it.owner) }
                    ?: ru.zf.pravka.core.Prompts.Author.OWNER
            }
        }
    }
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
    /** Звук тейков Google — переразобрать фразу заново (30.09.2026). */
    val takeAudio by lazy { ru.zf.pravka.data.TakeAudio(this) }

    // Засечка (timesheet): store, the Sheets mirror, phrase -> entry pipeline.
    // Правила разбора Засечки: набор, одобренный владельцем, едет в каждый
    // разбор. Ночное самообучение, которое их предлагало, снято (08.09.2026):
    // «все паттерны уже найдены», а ⭐ над «З» раздражала.
    val zasechkaRules by lazy { ru.zf.pravka.data.RulesStore(this, "zasechka-rules.json") }

    val zasechkaStore by lazy {
        ru.zf.pravka.data.ZasechkaStore(this).also { store ->
            store.logger = { line -> eventLog.add(line) }
            store.freshCategories = {
                if (profileStore.owner) ru.zf.pravka.data.ZasechkaStore.DEFAULT_CATEGORIES
                else ru.zf.pravka.data.ZasechkaStore.neutralCategories()
            }
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

    // Деньги: журнал трат и выписок (money.json) — незаменимые данные, как
    // лента. Там же — правила справочника, которые владелец вписал или
    // запомнил ответами; заводские — в assets (moneyFactoryRules ниже).
    /** Сырьё выписок: каждый загруженный файл как есть, в `imports/` базы (data/ImportArchive.kt). */
    internal val importArchive by lazy { ru.zf.pravka.data.ImportArchive { ru.zf.pravka.data.DataRoot.dir(this) } }
    /** Образцы денежных уведомлений для разборщиков новых банков (data/PushSamples.kt). */
    internal val pushSamples by lazy { ru.zf.pravka.data.PushSamples(this) }
    val moneyStore by lazy { ru.zf.pravka.data.MoneyStore(this) { eventLog.add(it) } }
    val moneyExport by lazy { ru.zf.pravka.data.MoneyExport(this, moneyStore) }
    val cbrRates by lazy { ru.zf.pravka.data.CbrRates(httpClient) { eventLog.add(it) } }
    val moneyEngine by lazy {
        ru.zf.pravka.core.MoneyEngine(
            claude = claudeProvider,
            dictionary = DictionaryApplier(dictionaryStore),
            dictionaryStore = dictionaryStore,
            store = moneyStore,
            rates = cbrRates,
            stats = stats,
            eventLog = eventLog,
            // Хозяин трат — пользователь установки. Заводские файлы Денег
            // (справочник получателей, остатки и счета ЗФ, наличные) написаны
            // про владельца и его семью: чужой установке их не показываем и в
            // Claude не отправляем.
            owner = { profileStore.current?.id ?: "user" },
            factory = { if (profileStore.owner) moneyFactoryRules else emptyList() },
            factoryBalances = { if (profileStore.owner) moneyFactoryBalances else emptyList() },
            factoryAccountsText = {
                if (!profileStore.owner) ""
                else runCatching { assets.open("money_balances.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
            },
            factoryManual = {
                if (!profileStore.owner) ""
                else runCatching { assets.open("money_manual.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
            },
            keepImport = { bytes -> importArchive.keep(bytes) },
        )
    }

    /**
     * Заводской справочник получателей (владелец, 23.09.2026: «сделай всё в
     * публичном репозитории»). Читается один раз; не прочитался — пусто, и
     * работают правила владельца и безличные.
     */
    // Облако семьи (provider/FamilyCloud.kt) — домашний сервер по WebDAV
    // (data/HomeServer.kt); Google Drive снят 26.09 («Не надо с drive.
    // Только с личным облаком»). Обмен Деньгами — data/MoneyCloudSync.kt,
    // копии базы — data/CloudBackup.kt. Свой клиент: выгрузка первого журнала
    // — мегабайты по мобильной сети, а диск сервера засыпает через 20 минут
    // простоя — первый ответ после сна ждёт раскрутки, отсюда запас по чтению.
    private val webdavHttp by lazy {
        httpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    internal val homeServer by lazy { ru.zf.pravka.data.HomeServer(this, ru.zf.pravka.provider.WebDav(webdavHttp)) }

    /** Проверка связи с облаками: журнал строками, замеры для картинки, сам проверяющий. */
    val netLog by lazy { EventLog(this, "net.log") }
    val netProbeStore by lazy { ru.zf.pravka.data.NetProbeStore(this) }
    internal val netProber by lazy { ru.zf.pravka.provider.NetProber(this, settings, netProbeStore, homeServer, netLog) }

    /** Облако семьи: домашний сервер, если задан; нет — null, обмен и копии молчат. */
    fun familyCloud(): ru.zf.pravka.provider.FamilyCloud? = homeServer.saved.value?.let { homeServer.cloud }

    internal val moneyCloudSync by lazy {
        ru.zf.pravka.data.MoneyCloudSync(
            context = this,
            store = moneyStore,
            cloud = { familyCloud() },
            profile = { profileStore.current },
            reconcile = { moneyEngine.reconcile() },
            log = { eventLog.add(it) },
        )
    }

    /**
     * Архив на домашнем компе (data/ArchiveSync.kt, docs/arkhiv.md): каждый
     * ввод — событием на сервер. Откуда брать записи — здесь: режим,
     * выключенный в профиле, свой стор не будит.
     */
    internal val archiveSync by lazy {
        ru.zf.pravka.data.ArchiveSync(
            context = this,
            http = httpClient,
            sources = archiveSources,
            scope = appScope,
            log = { eventLog.add(it) },
        )
    }

    private val archiveSources = object : ru.zf.pravka.data.ArchiveSync.Sources {
        override val profile: String? get() = profileStore.current?.id
        override val appVersion: String get() = BuildConfig.VERSION_NAME

        override suspend fun units(
            domain: ArchiveDomain,
            clock: ru.zf.pravka.core.ArchiveEvents.Clock,
        ): List<ru.zf.pravka.core.ArchiveEvents.Item> {
            val ev = ru.zf.pravka.core.ArchiveEvents
            return when (domain) {
                ArchiveDomain.ZASECHKA -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.ZASECHKA)) return emptyList()
                    val all = zasechkaStore.all()
                    val now = System.currentTimeMillis()
                    ev.zasechkaDays(all, clock, now) +
                        ev.zasechkaReference(zasechkaStore.categories(), zasechkaStore.clients())
                }
                ArchiveDomain.PHONE -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.ZASECHKA)) return emptyList()
                    phoneStore.trackedApps()  // поднимает стор с диска
                    val labels = phoneStore.labelsFlow.value
                    phoneStore.daysFlow.value.map { (date, day) -> ev.phoneDay(date, day, labels) }
                }
                ArchiveDomain.FOOD -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.FOOD)) return emptyList()
                    foodStore.load().map { ev.meal(it, clock) } + ev.norms(ru.zf.pravka.core.Micronutrients.ALL)
                }
                ArchiveDomain.STRENGTH -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.SPORT)) return emptyList()
                    strengthStore.load()
                    strengthStore.sessionsFlow.value.map { ev.session(it) } +
                        strengthStore.gtgFlow.value.filter { it.any }.map { ev.gtg(it) } +
                        strengthStore.rawFlow.value.map { ev.strengthTake(it, clock) }
                }
                ArchiveDomain.MONEY -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.MONEY)) return emptyList()
                    val st = moneyStore.load()
                    // Черновик до «ОК» — ещё не факт: уйдёт, когда станет записью или будет вычеркнут.
                    st.entries.filter { !it.draft }.map { ev.moneyEntry(it, clock) } +
                        ev.moneyReference(st, clock) +
                        st.takes.map { ev.moneyTake(it, clock) } +
                        st.pushes.map { ev.moneyPush(it, clock) }
                }
                ArchiveDomain.SPORT -> {
                    if (!profileStore.has(ru.zf.pravka.data.Profile.Mode.SPORT)) return emptyList()
                    sportStore.load()
                    sportStore.talksFlow.value.map { ev.talk(it, clock) }
                }
                ArchiveDomain.PRAVKA ->
                    corrections.all().map {
                        ev.correction(it.id, it.ts, it.pkg, it.dictated, it.cleaned, it.edited, it.result, clock)
                    }
            }
        }
    }

    /** Ночная копия базы — ещё и в облако семьи, `Правка/Копии базы` (data/CloudBackup.kt). */
    internal val cloudBackup by lazy {
        ru.zf.pravka.data.CloudBackup(
            context = this,
            cloud = { familyCloud() },
            connectedAt = { homeServer.saved.value?.at ?: 0L },
            user = { profileStore.current?.id ?: "user" },
            log = { eventLog.add(it) },
        )
    }

    /** Заводские остатки счетов — снимок владельца (`assets/money_balances.txt`). */
    val moneyFactoryBalances: List<ru.zf.pravka.core.MoneyCashflow.Anchor> by lazy {
        runCatching {
            ru.zf.pravka.core.MoneyCashflow.parseAnchors(assets.open("money_balances.txt").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }

    val moneyFactoryRules: List<ru.zf.pravka.core.MoneyRules.Rule> by lazy {
        runCatching {
            assets.open("money_payees.txt").bufferedReader().use { it.readText() }
                .let { ru.zf.pravka.core.MoneyRules.parseText(it) }
                .also { r -> if (r.errors.isNotEmpty()) eventLog.add("деньги: заводской справочник — ${r.errors}") }
                .rules
        }.getOrElse { e -> eventLog.add("деньги: заводской справочник не прочитался — ${e.message}"); emptyList() }
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
        ru.zf.pravka.data.PhoneSweeper(
            this, phoneStore, zasechkaStore, settings, eventLog, zasechkaSync, appScope,
            // Нашёл ночь — автопилот решает про дело по подъёму (сборы детей в будни).
            witness = { ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.autoWitness() },
        )
    }

    // Самообновление: раз в сутки смотрит ветку apk-builds, тянет APK и
    // предлагает поставить. Живёт на тике службы (см. zReminderTick).
    val updates by lazy {
        ru.zf.pravka.data.Updates(this, httpClient, settings, eventLog)
    }

    // intervals.icu: workouts land in the ribbon, Garmin sleep annotates it
    // (или пишет сон сам, если телефон ночи не увидел). Свидетель швов —
    // автопилот службы: тренировка от двери начинается с потери сети места.
    val icuSweeper by lazy {
        ru.zf.pravka.data.IcuSweeper(
            settings, zasechkaStore, httpClient, eventLog, zasechkaSync, appScope,
            witness = { ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.autoWitness() },
            wakeHint = {
                getSharedPreferences("pravka_internal", MODE_PRIVATE)
                    .getLong(ru.zf.pravka.data.PhoneSweeper.KEY_WAKE_HINT, 0L)
            },
        )
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
