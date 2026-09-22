package ru.zf.pravka.trigger

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.core.UndoStack
import ru.zf.pravka.data.Settings
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.target.AccessibilityTarget
import ru.zf.pravka.target.effectiveText
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// The core of the app (spec 5.2): reads and writes the focused editable field,
// keeps a cache of the last focused node (for triggers that steal focus) and
// hosts the floating "П" button - the single visible trigger, Whisper-style:
// the service does NOT request the system accessibility button (the two
// buttons duplicated each other; the owner chose the floating one).
class PravkaAccessibilityService : AccessibilityService() {
    companion object {
        var instance: PravkaAccessibilityService? = null
            private set

        // A reply chain: entries closer than this are one conversation.
        internal const val CONVO_GAP_MS = 10L * 60 * 1000

        /** Бегущая строка, пока движок ещё не слышит, и когда уже слышит. */
        private const val HINT_WAIT = "секунду…"
        private const val HINT_SPEAK = "говори"

        /** Окно двойного тапа по «З» на локскрине. */
        internal const val LOCK_DOUBLE_TAP_MS = 1_500L
        /** Сколько якорь времени ждёт свой тейк (см. `onZasechkaTap`). */
        const val Z_ANCHOR_TTL_MS = 2 * 60_000L

        /**
         * Потолок записи, начатой с заблокированного экрана. Владелец: «через
         * 40 секунд вообще, потому что я больше и не говорю». Разблокировал —
         * потолок снимается: он тут, и говорит сколько нужно.
         */
        internal const val LOCKED_TAKE_CAP_MS = 40_000L

        /**
         * Через столько без касаний кнопки собираются в стопку. Полминуты —
         * не мгновение (успеть додумать, куда жать) и не вечность.
         */
        internal const val STACK_IDLE_MS = 30_000L

        // Internal bookkeeping prefs, read by the Learning tab too.
        const val PREFS_INTERNAL = "pravka_internal"
        const val KEY_LAST_LEARN_BATCH = "last_learn_batch"

        /** Окно захвата правок после доставки и его продление на каждую правку. */
        const val CAPTURE_MS = 10L * 60 * 1000
        const val CAPTURE_EXTEND_MS = 5L * 60 * 1000
        /** Тишина после последней правки, после которой её разбирают. */
        const val DIGEST_QUIET_MS = 30L * 1000

        // Засечка reminder anti-spam: one morning/evening nudge per day, one
        // gap nudge per distinct gap.
        internal const val KEY_Z_MORNING_DAY = "z_morning_day"
        internal const val KEY_Z_EVENING_DAY = "z_evening_day"
        /** Сборка, на которой служба поднималась в прошлый раз (см. `ServiceNotifPermission.kt`). */
        internal const val KEY_SEEN_BUILD = "svc_seen_build"
        /** На какой сборке уже просили вернуть уведомления — раз на сборку. */
        internal const val KEY_NOTIF_NUDGED_BUILD = "notif_nudged_build"
        internal const val KEY_Z_GAP_NOTIFIED = "z_gap_notified_end"
        internal const val KEY_Z_BEAT_AT = "z_beat_at"
        internal const val KEY_Z_ASK_AT = "z_ask_at"
        internal const val KEY_Z_ASK_ID = "z_ask_entry"

        // Chrome flavors whose url bar the per-site watcher reads.

        // Windows that never host our text fields. Querying their node tree
        // is not just useless - during a fold/lock transition their process
        // is at its busiest, and a synchronous a11y query into it can hang
        // for the full accessibility timeout and freeze the transition (the
        // owner's 3-10s black screen on fold/unfold).
    }

    // An uncaught exception in any launched job used to kill the whole app
    // process SILENTLY (screen-off mid-take -> dead window -> node call threw ->
    // the take vanished with no journal line). Log it instead and stay alive.
    internal val crashLogger = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        runCatching {
            app.eventLog.add("CRASH ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}")
        }
    }
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashLogger)
    internal var floatingButton: FloatingButtonController? = null
    internal var busy = false

    // Засечка (timesheet): its own button, its own capture session. A take
    // here never touches the focused field - the transcript goes to the
    // categorizer and the timesheet store instead.
    internal var zButton: ZasechkaButtonController? = null
    internal var zSession: GoogleSpeechSession? = null
    @Volatile internal var zWhisperRecording = false
    // Tap on the live plate: kill the mic, type instead (confidential takes).
    @Volatile internal var zTypeInstead = false
    // Комментарий к делу из меню «З»: id записи, куда уедет ближайший тейк;
    // 0 — обычная запись в ленту. Сбрасывается на старте обычного тейка, чтобы
    // брошенное окно ввода не увело следующую фразу в чужой комментарий.
    @Volatile internal var zCommentFor = 0L
    /**
     * Якорь времени ближайшего тейка Засечки (см. `onZasechkaTap`): с какого
     * момента и до какого считать сказанное. Ноль — «сейчас», как обычно.
     */
    @Volatile internal var zAnchorStart = 0L
    @Volatile internal var zAnchorEnd = 0L
    /** Запись, которую правит ближайший тейк «З» (микрофон в редакторе записи); 0 — обычный тап. */
    @Volatile internal var zEditTargetId = 0L
    @Volatile internal var zAnchorSetAt = 0L
    /** Серая «отмена» у «З»: ближайший итог тейка выбрасывается. */
    @Volatile internal var zDiscard = false
    @Volatile internal var cachedZEnabled = true
    @Volatile internal var cachedStackIdle = true
    @Volatile internal var cachedZGapMin = 45
    @Volatile internal var cachedZCheckins = true
    @Volatile internal var cachedZDayStart = 9
    @Volatile internal var cachedZDayEnd = 23
    @Volatile internal var zCategoriesCached: List<String> = emptyList()
    @Volatile internal var zClientsCached: List<String> = emptyList()

    // Разноска: третья кнопка и свой захват. Наговор не касается ни поля, ни
    // ленты - он уезжает Опусу на разбор и оттуда делами в Todoist.
    internal var rButton: RaznoskaButtonController? = null
    internal var rSession: GoogleSpeechSession? = null
    @Volatile internal var rWhisperRecording = false
    @Volatile internal var rTypeInstead = false
    /** Серая «отмена» у «Д»: ближайший итог тейка выбрасывается. */
    @Volatile internal var rDiscard = false
    @Volatile internal var cachedREnabled = true
    internal var micRequestForRaznoska = false

    // Тело: четвёртая кнопка и свой захват. Одна на подходы, еду, зарядку и
    // вопросы - намерение определяет модель тем же вызовом, что и разбор. Ни
    // поля, ни ленты этот путь не касается (в ленту еда только ПРИПИСЫВАЕТСЯ,
    // и то из движка).
    internal var eButton: BodyButtonController? = null
    /** Ручка под хвостом: галочка, выпускает и убирает «Д» и «Е». */
    internal var tailHandle: StackHandleController? = null
    /**
     * Шестерёнка над «П» с веером быстрых настроек (модель чистки, микрофон,
     * обновления, «спрятать всё») и красная точка, в которую всё сжимается.
     * Заменила верхнюю ручку с многоточием и плашку микрофона между «П» и «З»
     * (владелец, 18.09.2026: «убираем грязь из стекла кнопок»).
     */
    internal var stackSettings: StackSettingsController? = null
    /**
     * Диск (владелец, 19.09.2026): те же четыре кнопки по кольцу вокруг
     * шестерёнки, крутится пальцем, у края виден наполовину. Включается
     * тумблером «Диск вместо стопки» в Общих; выключен — прежняя стопка с
     * ручкой. Пока настройка не прочитана — стопка.
     */
    internal var disk: DiskController? = null

    /**
     * Каким цветом идёт дуга: цветом той кнопки, чья работа сейчас. Дорога
     * без своей кнопки (ночные разборы, эвалы) берёт цвет Правки — это её
     * хозяйство.
     */
    private fun workColour(route: String): Int = when {
        route.startsWith("zasechka") -> ZasechkaButtonController.AMBER
        route.startsWith("raznoska") || route.startsWith("dela") -> RaznoskaButtonController.INK
        route.startsWith("body") || route.startsWith("sport") ||
            route.startsWith("food") || route.startsWith("eda") -> BodyButtonController.INK
        else -> FloatingButtonController.ACCENT
    }
    @Volatile internal var cachedDiskMode = false
    private var diskModeApplied = false
    /** Автоуборка диска: полминуты без касаний — к ближайшему краю и домой. */
    @Volatile internal var cachedDiskTuck = true
    /** Утопить диск после долгого простоя и через сколько минут. */
    @Volatile internal var cachedDiskSink = true
    @Volatile internal var cachedDiskSinkMin = Settings.DISK_SINK_MIN_DEFAULT
    internal var eSession: GoogleSpeechSession? = null
    @Volatile internal var eWhisperRecording = false
    @Volatile internal var eTypeInstead = false
    /** Серая «отмена» у «Т»: ближайший итог тейка выбрасывается. */
    @Volatile internal var eDiscard = false
    /** «Е» с завода выключена (19.09.2026): до чтения настройки считаем так же. */
    @Volatile internal var cachedEEnabled = false
    internal var micRequestForFood = false
    // Отдых между подходами: дедлайн на диске не нужен - это минуты, и
    // переживать перезапуск службы ему незачем.
    @Volatile internal var restUntil = 0L
    @Volatile internal var cachedRestSec = 90

    // Weak cache of the last focused editable node and its text, updated on
    // TYPE_VIEW_FOCUSED / TYPE_VIEW_TEXT_CHANGED (spec 5.4: activities steal
    // focus, so triggers launched via Activity read from this cache).
    internal var cachedFocus: WeakReference<AccessibilityNodeInfo>? = null
    // The field to receive dictated text, captured when recording starts -
    // the owner may switch apps while dictating, so we can't rely on focus
    // at stop time.
    internal var dictationTarget: WeakReference<AccessibilityNodeInfo>? = null

    // Live streaming dictation session.
    internal var googleSession: GoogleSpeechSession? = null
    internal var googleStartedAt = 0L

    /**
     * Движок уже слышит. Между тапом и этим мигом он ГЛУХ (будится процесс
     * службы, поднимается модель), и владелец в это окно успевает сказать
     * первые слова в никуда. Совсем окно не убрать — микрофон не наш, — но
     * молчать о нём нельзя: бегущая строка говорит «секунду…», а на готовности
     * меняется на «говори» (вместе с тиком, который был и раньше).
     */
    private var googleReady = false
    // Precomputed vocabulary bias and engine choice, so starting a take is
    // instant: no DataStore read and no dictionary load on the tap -> speak
    // path, which was clipping the first words.
    @Volatile internal var cachedBiasing: List<String> = emptyList()
    @Volatile internal var cachedEngine: String = Settings.SPEECH_GOOGLE
    // Recognition mode knobs (defaults = build 55), cached like the engine so
    // the tap -> listening path touches no storage.
    @Volatile internal var cachedSegmented: Boolean = true
    @Volatile internal var cachedFormatting: Boolean = false
    @Volatile internal var cachedBiasingOn: Boolean = true
    // Путь распознавания Google: офлайн-пакет (заводское) или системный с сетью.
    @Volatile internal var cachedNetwork: Boolean = false
    /** Ширина бегущей строки, dp — общая для «П», «З», «Д» и «Т» (Settings.tickerWidthFlow). */
    @Volatile internal var cachedTickerWidthDp: Int = Settings.TICKER_WIDTH_DEFAULT

    internal val app: PravkaApp by lazy { application as PravkaApp }

    /** Автопилот Засечки: Wi-Fi-места, BT машины, «точно ещё …?». */
    val autoPilot by lazy { AutoPilot(this, app, scope) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { autoPilot.start() }
        instance = this
        // A fresh "connected" after takes were mid-flight = the process died
        // and the system rebound the service. Makes crashes visible in the log.
        app.eventLog.add("service connected")
        // Сборка, обновление и уведомления — в журнал; выключенные уведомления
        // после обновления — вопрос владельцу (ServiceNotifPermission.kt).
        runCatching { checkNotificationsAfterUpdate() }
        floatingButton = FloatingButtonController(
            service = this,
            scope = scope,
            settings = app.settings,
            onShortTap = ::onDictateTap,
            onLongPress = ::showFabMenu,
        )
        // Warm everything the tap -> listening path needs, so that path touches
        // no storage at all.
        scope.launch { cachedBiasing = collectBiasing() }
        scope.launch {
            app.settings.speechEngineFlow.collect { cachedEngine = it }
        }
        scope.launch {
            app.settings.speechSegmentedFlow.collect { cachedSegmented = it }
        }
        scope.launch {
            app.settings.speechFormattingFlow.collect { cachedFormatting = it }
        }
        scope.launch {
            app.settings.speechBiasingFlow.collect { cachedBiasingOn = it }
        }
        scope.launch {
            app.settings.speechNetworkFlow.collect {
                cachedNetwork = it
                // Первый прогрев — как только известен путь: служба
                // распознавания просыпается заранее, и первому тейку не
                // приходится ждать её на своих первых словах.
                warmSpeech()
            }
        }
        scope.launch {
            app.settings.tickerWidthFlow.collect {
                cachedTickerWidthDp = it
                // Открытая строка перестраивается сразу — настройку крутят, глядя на неё.
                floatingButton?.repositionTickerIfVisible()
                zButton?.repositionTickerIfVisible()
                rButton?.repositionTickerIfVisible()
                eButton?.repositionTickerIfVisible()
            }
        }
        scope.launch {
            app.settings.convoContextFlow.collect { cachedConvoContext = it }
        }
        // Автообучение снято (владелец, 15.09.2026: «он уже обучился
        // достаточно, оставить только по кнопке»): служба не подписывается на
        // события текста вообще — ни события на каждое нажатие клавиши, ни
        // binder-вызова за event.source на главном потоке. Учиться можно
        // «Обучить» из меню «П» и «Разобрать сейчас» во вкладке Обучение.
        applyEventSubscription(false)
        refreshLearnBadge()

        // Засечка: the second button, visible everywhere while enabled.
        zButton = ZasechkaButtonController(
            service = this,
            scope = scope,
            settings = app.settings,
            onShortTap = { onZasechkaTap() },
            onLongPress = ::showZasechkaMenu,
        )
        zButton?.onTickerTap = ::onZasechkaPlateTap

        // Разноска: третья кнопка того же семейства.
        rButton = RaznoskaButtonController(
            service = this,
            scope = scope,
            settings = app.settings,
            onShortTap = ::onRaznoskaTap,
            onLongPress = ::showRaznoskaMenu,
        )
        rButton?.onTickerTap = ::onRaznoskaTickerTap

        // Еда: четвёртая кнопка того же семейства.
        eButton = BodyButtonController(
            service = this,
            scope = scope,
            settings = app.settings,
            onShortTap = ::onFoodTap,
            onLongPress = ::showFoodMenu,
        )
        eButton?.onTickerTap = ::onFoodTickerTap

        // Серая ручка под хвостом, с галочкой: выпускает и убирает «Д» и «Е».
        tailHandle = StackHandleController(this, scope, app.settings).also { h ->
            h.onTap = {
                touched()
                if (stacked) expandButtons() else collapseButtons()
            }
        }

        // Шестерёнка над «П» (StackSettingsController): веер быстрых
        // настроек и точка «всё убрано». Заменила верхнюю ручку с
        // многоточием и плашку микрофона — владелец: «убираем грязь из стекла
        // кнопок: наушники и верхнюю с тремя точками». Ставит её на место
        // refreshHandles.
        stackSettings = StackSettingsController(this, scope, app.settings).also { s ->
            s.onTouched = { touched() }
            s.onHideAll = { setAllHidden(true) }
            s.onShowAll = { setAllHidden(false) }
            // Палец на шестерёнке — тоже палец на диске: вторым он делает щипок.
            s.onRawTouch = { rx, ry, action -> disk?.finger("head", rx, ry, action) }
            // Голову таскают, как кнопку, и за ней едет вся цепочка. Иначе,
            // когда всё убрано, точка единственная на экране — и приросла бы
            // к месту навсегда. Координаты «П» считает сама шестерёнка,
            // ровно обратно своему moveTo: после броска голова остаётся там,
            // где палец её отпустил, без доводки и прыжка.
            s.onDragged = { hx, hy, dropped ->
                touched()
                if (cachedDiskMode) {
                    // Диск: голова — его центр; переезд и докование считает он.
                    disk?.onHeadDragged(hx, hy, dropped)
                } else {
                    val size = floatingButton?.buttonSizePx() ?: 0
                    val (px, py) = s.buttonOrigin(hx, hy, size)
                    followChain(null, px, py, dropped)
                }
            }
        }

        // The linked chain (owner's design): drag any bubble and the others
        // trail behind, in order "П" - "З" - "Д" - "Т". Бусы, а не строй:
        // каждая едет на своей пружине, и чем дальше звено от пальца
        // (`link`), тем мягче пружина — хвост приезжает последним
        // (`core/ChainPhysics.kt`). Смещения считает slotOffset.
        // Перетаскивание больше НЕ разворачивает стопку: спрятанное должно
        // оставаться спрятанным, куда бы связку ни увезли. Раньше здесь
        // стоял expandButtons(), и «Д» с «Е» выскакивали от любого сдвига
        // пальцем — то есть спрятать их надолго было попросту нельзя.
        // Порядок связки: П · З · Д · Е (если включена). Один обработчик на
        // всех: тянут кнопку — остальные встают по своим слотам от неё;
        // спрятанные (стопка сложена) лежат под «З».
        chainButtons().forEach { b ->
            b.onDragged = { x, y, dropped ->
                touched()
                followChain(b, x, y, dropped)
            }
        }
        // Пока бусы догоняют, ручка и шестерёнка едут за ними кадр в кадр —
        // иначе галочка встала бы на будущее место хвоста раньше него.
        val chainFrame: () -> Unit = { refreshHandles() }
        chainButtons().forEach { it.onFrame = chainFrame }
        floatingButton?.pairAnchor = anchor@{
            // На диске «П» появляется в своём слоте кольца.
            if (cachedDiskMode) return@anchor floatingButton?.let { disk?.slotOrigin(it) }
            if (!cachedZEnabled) return@anchor null
            val (zx, zy) = zButton?.currentPosition() ?: return@anchor null
            zx to (zy - slotOffset(1))
        }
        // Диск (`DiskController`): те же четыре кнопки по кольцу вокруг
        // шестерёнки. Палец на кнопке крутит диск, а не везёт кнопку —
        // контроллеры в режиме диска отдают касание сюда (`onRingDrag`).
        disk = DiskController(this, scope, app.settings).also { d ->
            d.head = stackSettings
            d.onTouched = { touched() }
            d.buttonSize = { floatingButton?.buttonSizePx() ?: 0 }
            // Порядок по кольцу — тот же, что в связке: П · З · Д · Е.
            floatingButton?.let { b -> d.add(b) { true } }
            zButton?.let { b -> d.add(b) { cachedZEnabled } }
            rButton?.let { b -> d.add(b) { cachedREnabled } }
            eButton?.let { b -> d.add(b) { cachedEEnabled } }
            chainButtons().forEach { b ->
                b.onRingDrag = { rx, ry, lx, ly, action -> d.onRingDrag(b, rx, ry, lx, ly, action) }
            }
        }
        // Кнопка слышит: громкость микрофона раздаём всем кнопкам, пульсирует
        // та, что пишет (владелец, 20.09.2026). Поле у сессии общее — микрофон
        // один, живая сессия в любой миг одна.
        GoogleSpeechSession.levelSink = { level ->
            chainButtons().forEach { it.setLevel(level) }
        }
        // Дуга прогресса по кромке стекла (владелец, 20.09.2026): запрос к
        // модели пошёл — по кромке побежала полоса, ожидание считает
        // `core/Pace.kt` по своей истории. Приходит это с потока запроса,
        // поэтому на главный кладём руками.
        app.workWatcher = { route, expect, done, ok ->
            chromeHandler.post {
                // Диск читаем уже на главном: между запросом и кадром его
                // могли выключить тумблером «Круг · Стопка».
                disk?.let { d ->
                    if (done) d.finishWork(ok) else d.startWork(expect, workColour(route))
                }
            }
        }
        // The "П" lives on screen permanently (owner: "пусть будет всегда") -
        // no field-following, no window watching. Without a focused field a
        // take still works: CLEAN runs and the result lands in the clipboard
        // plus a notification (the no-field path).
        floatingButton?.show()
        chromeHandler.post(chromeTicker)
        scope.launch {
            app.settings.zEnabledFlow.collect {
                cachedZEnabled = it
                zButton?.setEnabled(it)
                // setEnabled показывает кнопку — а набор мог быть убран в
                // верхнюю ручку: возвращаем её обратно в ручку.
                if (allHidden) zButton?.setStacked(true)
                refreshHandles()
            }
        }
        scope.launch {
            app.settings.rEnabledFlow.collect {
                cachedREnabled = it
                rButton?.setEnabled(it)
                // setEnabled показывает кнопку — а набор мог быть убран в
                // верхнюю ручку: возвращаем её обратно в ручку.
                if (allHidden) rButton?.setStacked(true)
                refreshHandles()
            }
        }
        scope.launch {
            app.settings.tEnabledFlow.collect {
                cachedEEnabled = it
                eButton?.setEnabled(it)
                // setEnabled показывает кнопку — а набор мог быть убран в
                // верхнюю ручку: возвращаем её обратно в ручку.
                if (allHidden) eButton?.setStacked(true)
                refreshHandles()
            }
        }
        scope.launch {
            app.settings.stackIdleFlow.collect {
                cachedStackIdle = it
                // Выключили при сложенных кнопках — разложить сейчас же,
                // иначе тумблер выглядит сломанным. Убранный в верхнюю ручку
                // набор это не касается: его убрали руками и вернуть должны
                // тоже руками.
                if (!it && stacked && !allHidden) expandButtons()
            }
        }
        scope.launch {
            app.settings.diskModeFlow.collect { on ->
                if (diskModeApplied && on == cachedDiskMode) return@collect
                diskModeApplied = true
                cachedDiskMode = on
                applyDiskMode(on)
            }
        }
        scope.launch { app.settings.diskTuckFlow.collect { cachedDiskTuck = it } }
        scope.launch { app.settings.diskSinkFlow.collect { cachedDiskSink = it } }
        scope.launch { app.settings.diskSinkMinutesFlow.collect { cachedDiskSinkMin = it } }
        scope.launch { app.settings.restSecFlow.collect { cachedRestSec = it } }
        scope.launch {
            app.settings.modeIconsFlow.collect {
                ModeGlyphs.icons = it
                floatingButton?.refreshGlyph()
                zButton?.refreshGlyph()
                rButton?.refreshGlyph()
                eButton?.refreshGlyph()
            }
        }
        // Справочники в память заранее: разбор подходов не должен ждать чтения
        // с диска, а список движений — это пятьдесят килобайт один раз.
        scope.launch { runCatching { app.exerciseBook.load() } }
        scope.launch { runCatching { app.rationBook.load() } }
        scope.launch { runCatching { app.strengthStore.load() } }
        scope.launch { runCatching { app.planStore.load() } }
        scope.launch { app.settings.zGapMinFlow.collect { cachedZGapMin = it } }
        scope.launch { app.settings.zCheckinsFlow.collect { cachedZCheckins = it } }
        scope.launch { app.settings.zDayStartFlow.collect { cachedZDayStart = it } }
        scope.launch { app.settings.zDayEndFlow.collect { cachedZDayEnd = it } }
        // Force-load the store once, then keep the recognizer bias lists warm.
        scope.launch {
            app.zasechkaStore.all()
            launch {
                app.zasechkaStore.categoriesFlow.collect { list ->
                    zCategoriesCached = list.map { it.name }
                }
            }
            launch { app.zasechkaStore.clientsFlow.collect { zClientsCached = it } }
        }
        zReminderHandler.postDelayed(zReminderTick, 60_000)
        lagExpectedAt = 0L
        lagHandler.removeCallbacks(lagTick)
        lagHandler.postDelayed(lagTick, 2_000)
    }

    // Main-thread lag sentinel. The fold black-screen class of bug is "the
    // service main thread was busy for seconds": each individual a11y event
    // stays fast, so the per-event watchdog is silent while the QUEUE lags
    // behind whatever hogged the thread. A timestamped no-op every 2s makes
    // the hog visible: it runs late, and the lag lands in the log.
    internal val lagHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var lagExpectedAt = 0L
    internal val lagTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (lagExpectedAt > 0) {
                val lag = now - lagExpectedAt
                if (lag > 700) app.eventLog.add("⚠️ главный поток службы вис ~$lag мс")
            }
            lagExpectedAt = now + 2_000
            lagHandler.postDelayed(this, 2_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Watchdog: the a11y pipeline serializes on this method - anything
        // slow here stalls system transitions. Slow events land in the log
        // so the next freeze report comes with a culprit attached.
        val startedAt = SystemClock.uptimeMillis()
        handleAccessibilityEvent(event)
        val took = SystemClock.uptimeMillis() - startedAt
        if (took > 200) {
            runCatching {
                app.eventLog.add("МЕДЛЕННОЕ a11y-событие ${event.eventType} из ${event.packageName}: $took мс")
            }
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // The button no longer follows fields (owner: "пусть будет
                // всегда") - the focus event only feeds the insert-target cache.
                val source = event.source ?: return
                if (source.isEditable) cachedFocus = WeakReference(source)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // События текста приходят только в окне захвата после доставки
                // (armCapture) — вне его служба на них даже не подписана.
                if (SystemClock.elapsedRealtime() > captureUntil) return
                val source = event.source ?: return
                if (source.isEditable) {
                    cachedFocus = WeakReference(source)
                    // Владелец правит и сразу шлёт (15.09: «лаг всего одна секунда,
                    // иначе я просто правил и отправлял»): читаем поле почти на
                    // каждое нажатие (раз в 300 мс) и ещё раз через 300 мс после
                    // последнего — последнее состояние перед отправкой должно быть
                    // увидено. Опустевшее поле — это отправка: разбор сразу.
                    val now = SystemClock.elapsedRealtime()
                    ripenessHandler.removeCallbacks(trailingProbe)
                    ripenessHandler.postDelayed(trailingProbe, 300)
                    if (now - lastWatchProbeAt > 300) {
                        lastWatchProbeAt = now
                        probeWatchedField(source)
                    }
                }
            }
            // TYPE_WINDOW_STATE_CHANGED is no longer even subscribed to: the
            // "П" lives on screen permanently (owner's call), so nothing needs
            // to know which app is in front - and window storms during fold
            // transitions no longer reach this service at all.
        }
    }

    /** Focused editable node: live focus first, then the cache (spec 5.4). */
    fun focusedEditableNode(): AccessibilityNodeInfo? {
        liveFocusedEditableNode()?.let { return it }
        val cached = cachedFocus?.get() ?: return null
        return if (cached.refresh() && cached.isEditable) cached else null
    }

    private fun liveFocusedEditableNode(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    /** Insert/write diagnostics land in the same exportable dictation journal. */
    fun logEvent(line: String) = app.eventLog.add(line)

    /** External triggers (quick settings tile) land here too. */
    fun trigger(mode: ProofreadMode) = runProofread(mode)

    fun triggerUndo() = undoLast()

    // ---- Dictation (short tap): record -> transcribe -> insert -> fix ----

    /**
     * Pocket guard (owner's request): on the lockscreen both bubbles ignore
     * touches COMPLETELY while idle - an accidental press must not start a
     * take, open a menu or drag a button around. A take that is ALREADY
     * running keeps its button alive: locking the screen mid-dictation is
     * normal walking usage, and the stop-tap must still land.
     */
    internal val keyguardManager by lazy {
        getSystemService(android.app.KeyguardManager::class.java)
    }

    fun isLockedIdle(): Boolean {
        val locked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
        if (!locked) return false
        return !micBusy()
    }

    /**
     * Микрофон занят тейком — любого режима и любого движка. Одна точка на
     * всех, кто про это спрашивает: карманный сторож на замке, прогрев движка
     * и «перезагрузить микрофон».
     */
    fun micBusy(): Boolean =
        googleSession != null || zSession != null || rSession != null || eSession != null ||
            zWhisperRecording || rWhisperRecording || eWhisperRecording ||
            DictationService.recording

    /**
     * Палец ЛЁГ на кнопку — будим движок распознавания, не дожидаясь, чем
     * касание кончится. Владелец (22.09.2026): «первые несколько слов он не
     * слышит». Между `startListening()` и готовностью движок глух, и дороже
     * всего там разбудить процесс службы распознавания — эту часть и платим
     * заранее, пока палец ещё на стекле. Дёшево и идемпотентно: тёплый клиент
     * живёт две минуты и продлевается сам.
     */
    fun warmSpeech() {
        if (cachedEngine.startsWith("whisper")) return
        if (micBusy()) return
        GoogleSpeechSession.warmUp(this, cachedNetwork) { line -> app.eventLog.add(line) }
    }

    /** Short tap: stop the active session if any, else start per the engine. */
    private fun onDictateTap() {
        touched()
        if (isLockedIdle()) return
        // One microphone, one take at a time: a Засечка recording must be
        // stopped from its own button, not silently hijacked by this one.
        if (zSession != null || zWhisperRecording) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.z_busy_pravka))
            return
        }
        if (rSession != null || rWhisperRecording) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.r_busy_pravka))
            return
        }
        if (eSession != null || eWhisperRecording) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.e_busy_pravka))
            return
        }
        if (googleSession != null) { stopLiveDictation(); return }
        if (DictationService.recording) {
            floatingButton?.setBusy(true)
            stopDictation()  // DictationService calls back onRecordingSaved()
            return
        }
        // No suspend hop here: the engine is cached, so a tap starts listening
        // on this very main-loop message.
        startForEngine()
    }

    internal fun startForEngine() {
        if (!hasMicPermission()) { requestMicPermission(); return }
        // Whisper records to a file; Google is the live streaming engine.
        if (cachedEngine.startsWith("whisper")) {
            startRecordingNow()
        } else {
            startGoogleNow()
        }
    }

    internal fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Only an Activity can request a runtime permission; it calls back into
    // onMicPermissionGranted(), which re-dispatches by the current engine.
    internal fun requestMicPermission() {
        startActivity(
            android.content.Intent(this, MicPermissionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // The permission dialog serves two buttons; remember whose tap asked for
    // it, or a Засечка tap would grant the mic and then start a Правка take.
    internal var micRequestForZasechka = false

    /** Called by MicPermissionActivity after the permission is granted. */
    fun onMicPermissionGranted() {
        if (micRequestForFood) {
            micRequestForFood = false
            startFoodCapture()
        } else if (micRequestForRaznoska) {
            micRequestForRaznoska = false
            startRaznoskaCapture()
        } else if (micRequestForZasechka) {
            micRequestForZasechka = false
            startZasechkaCapture()
        } else {
            startForEngine()
        }
    }

    // Names/terms/brands the recognizer should be biased toward: ТОЛЬКО верные
    // формы — защищённые слова и правые части замен и подсказок, не больше 40
    // (ослышки в списке учили движок ошибаться, длинный список тормозил старт).
    // Порядок внутри сорока — `core/BiasingList.kt`: слова владельца впереди
    // семени, латиница наравне. Одна строка в журнал — видно, что подсказки
    // владельца доехали, а не вытеснены заводскими.
    private suspend fun collectBiasing(): List<String> = runCatching {
        val store = app.dictionaryStore
        val built = ru.zf.pravka.core.BiasingList.build(store.all(), isSeed = store::isSeed)
        app.eventLog.add("подсказки движку: ${built.describe()}")
        built.strings
    }.getOrDefault(emptyList())

    fun startRecordingNow() {
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) } ?: cachedFocus
        probeFieldEdits(dictationTarget?.get())
        discardTake = false
        floatingButton?.setRecording(true)
        // Серая «отмена» — и у записи через Whisper: файл выбрасывается
        // нерасшифрованным.
        floatingButton?.showCancelBubble { cancelLiveDictation() }
        Haptics.start(this)
        startDictation()
    }

    // ---- Live Google (streaming) dictation ----

    private fun startGoogleNow() {
        if (googleSession != null) return
        if (!GoogleSpeechSession.isAvailable(this)) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.google_unavailable))
            return
        }
        googleStartedAt = SystemClock.elapsedRealtime()
        googleReady = false
        lastDraftAt = 0L
        discardTake = false
        // Путь фиксируем на старте: настройку могут переключить посреди тейка,
        // а в «Расшифровках» тейк должен значиться тем путём, которым шёл.
        val network = cachedNetwork
        val session = GoogleSpeechSession(
            this,
            biasing = if (cachedBiasingOn) cachedBiasing else emptyList(),
            formatting = cachedFormatting,
            segmentedSession = cachedSegmented,
            network = network,
        )
        googleSession = session
        // Start listening FIRST, then dress the UI: the button, the ticker's
        // first layout, the haptic and the foreground-service notification are
        // all main-thread work, and any of it ahead of startListening() is time
        // the mic is not yet capturing.
        session.start(
            // A distinct tick the moment the recognizer is actually listening,
            // so the owner knows when to start and stops clipping first words.
            onReady = {
                googleReady = true
                floatingButton?.updateTicker(HINT_SPEAK, force = true)
                Haptics.success(this)
            },
            // Live text feeds the on-screen ticker; a throttled copy goes to disk
            // so an interrupted take (phone dies, killed) can still be recovered.
            onPartial = { live ->
                floatingButton?.updateTicker(live)
                saveDraftThrottled(live)
            },
            // Finalized chunk: persist immediately (LiveDraft skips it if the
            // partial already wrote the same string).
            onCheckpoint = { text ->
                lastDraftAt = SystemClock.elapsedRealtime()
                app.liveDraft.save(text)
            },
            onDone = { text ->
                onLiveDone(if (network) Settings.SPEECH_GOOGLE_NET else Settings.SPEECH_GOOGLE, text)
            },
            onError = { msg -> onLiveError(msg) },
            onLog = { line -> app.eventLog.add(line) },
        )
        // The tree walk to capture the target field is 1-3 binder IPCs into the
        // host app - deliberately AFTER startListening() is already issued, so
        // it can't clip the first word.
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) } ?: cachedFocus
        probeFieldEdits(dictationTarget?.get())
        floatingButton?.setRecording(true)
        floatingButton?.showTicker()
        // Строка открывается пустой, и эта пустота врёт: слышать движок
        // начинает позже. Пишем в неё, чего ждём, — если он уже успел
        // отозваться, там к этому мигу стоит «говори».
        if (!googleReady) floatingButton?.updateTicker(HINT_WAIT)
        floatingButton?.showCancelBubble { cancelLiveDictation() }
        Haptics.start(this)
        // Foreground-mic holder so the recognizer survives app switches. If it
        // can't start (rare FGS restrictions), recognition still works while
        // Правка is foregrounded, so don't abort the session over it.
        runCatching { startMicHold() }
        // Warm the TLS connection to the API now, so the CLEAN request after
        // stop skips the handshake.
        app.warmClaudeConnection()
    }

    internal var lastDraftAt = 0L
    private fun saveDraftThrottled(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDraftAt < 1200) return
        lastDraftAt = now
        app.liveDraft.save(text)
    }

    // ---- Conversation memory (owner's request): my recent takes in the SAME
    // app form a chain; a new dictation carries the chain as read-only context
    // so replies keep the thread's tone and referents. In-memory only.
    internal data class ConvoEntry(val pkg: String, val at: Long, val text: String)
    internal val convo = ArrayDeque<ConvoEntry>()
    @Volatile internal var cachedConvoContext: Boolean = true
    @Volatile internal var cachedLearnPeriodH: Int = 3
    @Volatile internal var cachedLearnAuto: Boolean = false

    // The service's own event appetite, flipped with the auto-capture toggle:
    // with capture off the system stops delivering text-change events at all.
    internal fun applyEventSubscription(autoCapture: Boolean) {
        runCatching {
            val info = serviceInfo ?: return
            val wanted =
                if (autoCapture) {
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                } else {
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
                }
            if (info.eventTypes != wanted) {
                info.eventTypes = wanted
                serviceInfo = info
                app.eventLog.add(
                    if (autoCapture) "события: фокус + текст (авторазбор вкл)"
                    else "события: только фокус (авторазбор выкл)"
                )
            }
        }
    }

    private fun convoRemember(pkg: String?, text: String) {
        if (pkg.isNullOrBlank() || text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        // A new chain starts after a long gap or in another app.
        val last = convo.lastOrNull()
        if (last != null && (last.pkg != pkg || now - last.at > CONVO_GAP_MS)) convo.clear()
        convo.addLast(ConvoEntry(pkg, now, text.take(500)))
        while (convo.size > 6) convo.removeFirst()
    }

    // The chain remembers the RAW dictation at insert time; once the CLEAN
    // lands, swap in the cleaned text - that is what actually stands in the
    // chat, and what the next take's context should quote.
    private fun convoUpdateLast(pkg: String?, cleaned: String) {
        if (pkg.isNullOrBlank() || cleaned.isBlank()) return
        val last = convo.lastOrNull() ?: return
        if (last.pkg == pkg) {
            convo.removeLast()
            convo.addLast(last.copy(text = cleaned.take(500)))
        }
    }

    private fun convoContextFor(pkg: String?): String {
        if (!cachedConvoContext || pkg.isNullOrBlank()) return ""
        val now = SystemClock.elapsedRealtime()
        val last = convo.lastOrNull() ?: return ""
        if (last.pkg != pkg || now - last.at > CONVO_GAP_MS) return ""
        // Deliberately tight (owner's request): every request already carries
        // the template, the dictionary and the rules - the conversation tail
        // is a tone/gender hint, not a transcript. Two most recent takes,
        // each clipped, ~400 chars ceiling total.
        val recent = convo.filter { it.pkg == pkg }.takeLast(2)
        if (recent.isEmpty()) return ""
        // Bare lines: the prompt assembler wraps them in the <разговор>
        // envelope with its own instruction - no header needed here.
        val sb = StringBuilder()
        var used = 0
        for (e in recent) {
            val clipped = e.text.take(240)
            if (used + clipped.length > 400) break
            sb.append("— ").append(clipped).append('\n')
            used += clipped.length
        }
        return sb.toString().trim()
    }

    // Edit-watch throttling: probing the field on every keystroke would be
    // wasteful, and irrelevant once no delivery is recent.
    @Volatile internal var lastWatchProbeAt = 0L
    @Volatile internal var lastDeliveryAt = 0L

    // The gray "отмена" bubble beside the ticker: throw the take away.
    @Volatile internal var discardTake = false

    fun cancelLiveDictation() {
        when {
            googleSession != null -> {
                discardTake = true
                app.eventLog.add("cancel requested")
                stopLiveDictation()
            }
            // Запись «П» через Whisper: чужие флаги не стоят, значит файл наш.
            DictationService.recording && !zWhisperRecording && !rWhisperRecording && !eWhisperRecording -> {
                discardTake = true
                app.eventLog.add("cancel requested (whisper)")
                floatingButton?.setBusy(true)
                stopDictation()  // -> onRecordingSaved выбросит файл
            }
        }
    }

    /** Second tap or the notification's Stop button: finalize the live take. */
    fun stopLiveDictation() {
        val session = googleSession ?: return
        // Acknowledge the tap before anything else touches storage.
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(true)
        app.eventLog.add("stop requested")
        session.stop()  // -> onLiveDone
    }

    private fun onLiveDone(engine: String, text: String) {
        googleSession = null
        // Every step below runs on the binder callback that delivers the take:
        // one throw here (screen off -> dead window/FGS token) used to kill the
        // process before the transcript was even journaled. Nothing on this
        // path is allowed to take the text down with it.
        runCatching { stopMicHold() }
        runCatching { floatingButton?.hideTicker() }
        runCatching { floatingButton?.hideCancelBubble() }
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(false)
        if (discardTake) {
            // The gray "отмена" bubble: nothing is inserted or journaled as a
            // take; the draft goes too. Deliberate discard, not a lost take.
            discardTake = false
            app.eventLog.add("take discarded (${text.length} ch)")
            app.liveDraft.clear()
            Feedback.toast(this, "Отменено")
            return
        }
        // Get the text into the field first - the owner is waiting on it. The
        // journals are queued on a background thread, so they cost nothing here,
        // but they still go after the insert is under way.
        //
        // If the SERVICE was destroyed mid-take (system rebind), the scope is
        // cancelled and this launch is a silent no-op - the take would vanish.
        // Keep the recovery draft in that case: that is exactly what it is for.
        val scopeAlive = scope.isActive
        if (text.isNotBlank() && scopeAlive) scope.launch { insertDictated(text) }
        val wall = SystemClock.elapsedRealtime() - googleStartedAt
        app.transcriptionLog.append(
            engine = engine,
            audioMs = wall,
            transcribeMs = 0,
            text = text,
            error = if (text.isBlank()) "пустой результат" else null,
        )
        // Delivered (and logged to the transcripts) - the recovery draft is no
        // longer needed. Unless the service died mid-take: then nothing was
        // delivered and the draft is the only surviving copy.
        if (scopeAlive) app.liveDraft.clear()
        else app.eventLog.add("сервис погиб посреди тейка — черновик сохранён для восстановления")
        if (text.isBlank()) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
        }
    }

    private fun onLiveError(msg: String) {
        googleSession = null
        stopMicHold()
        floatingButton?.hideTicker()
        floatingButton?.hideCancelBubble()
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, msg)
    }

    /** DictationService hands the finished recording here (same process). */
    fun onRecordingSaved(file: File?) {
        // A Засечка take on the Whisper engine comes through the same service;
        // the flag set at start routes it away from the field-insert path.
        if (zWhisperRecording) {
            zWhisperRecording = false
            zButton?.setRecording(false)
            zButton?.hideCancelBubble()
            // Серая «отмена»: файл выбрасывается нерасшифрованным, якоря и
            // адресат комментария сбрасываются — следующая фраза уже не про них.
            if (zDiscard) {
                zDiscard = false
                zTypeInstead = false
                zCommentFor = 0L
                zAnchorStart = 0L
                zAnchorEnd = 0L
                zEditTargetId = 0L
                file?.let { app.recordings.delete(it.name) }
                zButton?.hideTicker()
                zButton?.setBusy(false)
                app.eventLog.add("засечка: наговор отменён (whisper)")
                Feedback.toast(this, "Отменено")
                return
            }
            // Plate tap mid-take: the audio is discarded UNTRANSCRIBED (the
            // whole point is confidentiality) and the type-in box opens.
            if (zTypeInstead) {
                zTypeInstead = false
                file?.let { app.recordings.delete(it.name) }
                zButton?.hideTicker()
                zButton?.setBusy(false)
                openZasechkaTypeIn("")
                return
            }
            zButton?.hideTicker()
            if (file == null) {
                zCommentFor = 0L
                zButton?.setBusy(false)
                Haptics.error(this)
                Feedback.toast(this, getString(R.string.dictation_empty))
                return
            }
            zButton?.setBusy(true)
            scope.launch {
                val result = app.transcribeDictation(file)
                result.onSuccess { text ->
                    app.recordings.delete(file.name)
                    // Тейк из меню «Комментарий к…» уходит не в разбор ленты,
                    // а в поле комментария записи, тем же флагом, что и тут.
                    if (zCommentFor > 0L) onZasechkaCommentText(text) else onZasechkaText(text)
                }.onFailure { e ->
                    zCommentFor = 0L
                    zButton?.setBusy(false)
                    // Audio stays in Recordings, like a failed Правка take.
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        getString(R.string.dictation_saved_for_retry, e.message ?: ""),
                    )
                }
            }
            return
        }
        // Разноска на Whisper приходит тем же путём; свой флаг уводит её и от
        // поля, и от ленты.
        if (rWhisperRecording) {
            rWhisperRecording = false
            rButton?.setRecording(false)
            rButton?.hideCancelBubble()
            if (rDiscard) {
                rDiscard = false
                rTypeInstead = false
                file?.let { app.recordings.delete(it.name) }
                rButton?.hideTicker()
                rButton?.setBusy(false)
                app.eventLog.add("разноска: наговор отменён (whisper)")
                Feedback.toast(this, "Отменено")
                return
            }
            if (rTypeInstead) {
                rTypeInstead = false
                file?.let { app.recordings.delete(it.name) }
                rButton?.hideTicker()
                rButton?.setBusy(false)
                openRaznoskaTypeIn("")
                return
            }
            rButton?.hideTicker()
            if (file == null) {
                rButton?.setBusy(false)
                Haptics.error(this)
                Feedback.toast(this, getString(R.string.dictation_empty))
                return
            }
            rButton?.setBusy(true)
            scope.launch {
                val result = app.transcribeDictation(file)
                result.onSuccess { text ->
                    app.recordings.delete(file.name)
                    onRaznoskaText(text)
                }.onFailure { e ->
                    rButton?.setBusy(false)
                    // Аудио остаётся в «Записях», как у неудачной Правки.
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        getString(R.string.dictation_saved_for_retry, e.message ?: ""),
                    )
                }
            }
            return
        }
        // Еда на Whisper — тем же путём, со своим флагом.
        if (eWhisperRecording) {
            eWhisperRecording = false
            eButton?.setRecording(false)
            eButton?.hideCancelBubble()
            if (eDiscard) {
                eDiscard = false
                eTypeInstead = false
                file?.let { app.recordings.delete(it.name) }
                eButton?.hideTicker()
                eButton?.setBusy(false)
                app.eventLog.add("еда: наговор отменён (whisper)")
                Feedback.toast(this, "Отменено")
                return
            }
            if (eTypeInstead) {
                eTypeInstead = false
                file?.let { app.recordings.delete(it.name) }
                eButton?.hideTicker()
                eButton?.setBusy(false)
                openFoodTypeIn("")
                return
            }
            eButton?.hideTicker()
            if (file == null) {
                eButton?.setBusy(false)
                Haptics.error(this)
                Feedback.toast(this, getString(R.string.dictation_empty))
                return
            }
            eButton?.setBusy(true)
            scope.launch {
                val result = app.transcribeDictation(file)
                result.onSuccess { text ->
                    app.recordings.delete(file.name)
                    onFoodText(text)
                }.onFailure { e ->
                    eButton?.setBusy(false)
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        getString(R.string.dictation_saved_for_retry, e.message ?: ""),
                    )
                }
            }
            return
        }
        floatingButton?.setRecording(false)
        floatingButton?.hideCancelBubble()
        if (discardTake) {
            // Серая «отмена» у записи через Whisper: файл выбрасывается
            // нерасшифрованным, в поле ничего не идёт.
            discardTake = false
            file?.let { app.recordings.delete(it.name) }
            floatingButton?.setBusy(false)
            app.eventLog.add("take discarded (whisper)")
            Feedback.toast(this, "Отменено")
            return
        }
        if (file == null) {
            floatingButton?.setBusy(false)
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
            return
        }
        floatingButton?.setBusy(true)
        scope.launch {
            val result = app.transcribeDictation(file)
            floatingButton?.setBusy(false)
            result.onSuccess { text ->
                app.recordings.delete(file.name)  // transcribed - drop the audio
                insertDictated(text)
            }.onFailure { e ->
                // Audio stays in Recordings for a later retry (Wispr-style).
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(
                    this@PravkaAccessibilityService,
                    getString(R.string.dictation_saved_for_retry, e.message ?: ""),
                )
            }
        }
    }

    /** Retry a saved recording from the app's "Записи" screen. */
    fun retryRecording(file: File, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            app.transcribeDictation(file)
                .onSuccess { text ->
                    app.recordings.delete(file.name)
                    insertDictated(text)
                    onDone(true, text)
                }
                .onFailure { e -> onDone(false, e.message ?: "") }
        }
    }

    // Insert dictated text into the remembered field at the cursor, select
    // it, then run CLEAN over just that fragment. Re-acquires the live focused
    // field if the remembered one went stale (long take / app switch), and
    // tries an automatic PASTE before giving up to the clipboard - the owner
    // shouldn't have to paste large dictations by hand.
    private suspend fun insertDictated(rawText: String) {
        // Dictated formatting commands ("с новой строки", "абзац") become real
        // breaks locally - instant and free, before the model ever sees them.
        app.eventLog.add("insert: begin len=${rawText.length}")
        val text = ru.zf.pravka.core.VoiceCommands.apply(rawText)
        // Node calls throw when the window died mid-take (screen off, app
        // killed) - that must degrade to the no-field path, not crash.
        val pinned = runCatching { dictationTarget?.get()?.takeIf { it.refresh() && it.isEditable } }
            .onFailure { app.eventLog.add("insert: pinned threw ${it.javaClass.simpleName}") }
            .getOrNull()
        val node = pinned ?: runCatching { focusedEditableNode() }
            .onFailure { app.eventLog.add("insert: focus lookup threw ${it.javaClass.simpleName}") }
            .getOrNull()
        app.eventLog.add(
            "insert: node=${if (pinned != null) "pinned" else if (node != null) "focus" else "NONE"} len=${text.length}"
        )
        if (node == null) {
            cleanWithoutField(text)
            return
        }
        // A placeholder counts as empty - shared with the read path so CLEAN
        // agrees about it too (see target/NodeText.kt). Reading a node whose
        // window just died throws - degrade to the no-field path.
        val state = runCatching { node.effectiveText() to node.textSelectionEnd }
            .onFailure { app.eventLog.add("insert: node read threw ${it.javaClass.simpleName}") }
            .getOrNull()
        if (state == null) {
            cleanWithoutField(text)
            return
        }
        // Append at the end by default. After our own ACTION_SET_TEXT the field
        // often reports the cursor back at 0, which made a follow-up dictation
        // land at the START of the phrase. Only honour a genuine mid-text cursor
        // (>0 and not already at the end); otherwise append.
        val (existing, selEnd) = state
        val cursor = if (selEnd in 1..existing.length) selEnd else existing.length
        val needsSpaceBefore = cursor > 0 && !existing[cursor - 1].isWhitespace()
        val insert = (if (needsSpaceBefore) " " else "") + text
        val newText = existing.substring(0, cursor) + insert + existing.substring(cursor)
        val spanStart = cursor + (if (needsSpaceBefore) 1 else 0)
        val spanEnd = spanStart + text.length

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val setOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs) }
            .getOrDefault(false)
        if (!setOk) {
            // Some fields reject a big ACTION_SET_TEXT. Put the fragment on the
            // clipboard and try to PASTE it into the field automatically, so it
            // still lands in the box without a manual paste.
            ru.zf.pravka.target.ClipboardTarget(this).write(insert)
            val pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            app.eventLog.add("insert: SET_TEXT rejected -> paste=$pasted")
            if (!pasted) Feedback.toast(this, getString(R.string.dictation_to_clipboard))
            else Haptics.success(this)
            return
        }
        runCatching { node.refresh() }
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, spanStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, spanEnd.coerceAtMost(newText.length))
        }
        val selected = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            .getOrDefault(false)
        app.eventLog.add(
            "insert: ok existing=${existing.length} selection=$selected -> clean=${selected || existing.isEmpty()}"
        )
        Haptics.success(this)
        // Fix the just-inserted fragment ON THIS NODE. The proofread path must
        // not re-derive the target from input focus - after an app switch that
        // is a different field in a different app, and CLEAN would rewrite it.
        // If the selection could not be set AND the field held other text, skip
        // CLEAN entirely: with a collapsed cursor the whole field would be
        // selected and pre-existing paragraphs rewritten, not just the take.
        if (selected || existing.isEmpty()) {
            // Conversation context: previous takes in THIS app (built before the
            // current take joins the chain, so it isn't its own context).
            val pkg = runCatching { node.packageName?.toString() }.getOrNull()
            val convoCtx = convoContextFor(pkg)
            convoRemember(pkg, text)
            if (convoCtx.isNotBlank()) app.eventLog.add("convo context: ${convoCtx.length} ch")
            runProofread(
                ProofreadMode.CLEAN, pinnedNode = node,
                conversationContext = convoCtx, watchDictated = text,
            )
        }
    }

    // No editable field survived the take (folded phone, app died): still run
    // the full CLEAN, then clipboard + a notification that holds the text -
    // a walk-dictated paragraph must not depend on a 3-second toast.
    private suspend fun cleanWithoutField(text: String) {
        // Same single-flight guard as the field path: external triggers must
        // not start a second proofread while this one runs.
        busy = true
        floatingButton?.setBusy(true)
        val target = ru.zf.pravka.target.PlainTextTarget(text)
        val outcome = runCatching { app.engine.proofread(target, ProofreadMode.CLEAN) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) { busy = false; throw e }
                app.eventLog.add("cleanWithoutField threw ${e.javaClass.simpleName}")
                ProofreadEngine.Outcome.Failed(e.message ?: "Неизвестная ошибка")
            }
        busy = false
        floatingButton?.setBusy(false)
        val final = target.result ?: text
        ru.zf.pravka.target.ClipboardTarget(this).write(final)
        showNoFieldNotification(final)
        Haptics.success(this)
        Feedback.toast(this, getString(R.string.nofield_done))
        if (outcome is ProofreadEngine.Outcome.Failed) {
            // Raw text is still on the clipboard and in the notification.
            Feedback.toast(this, outcome.message)
        }
    }

    private fun showNoFieldNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channelId = "pravka-results"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, getString(R.string.nofield_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 2,
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.nofield_title))
            .setContentText(text.take(120))
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(43, notif)
    }

    /** Result-bar chips and the FAB menu: one-tap redo on a stronger model. */
    fun redoWithDirective(directive: String) =
        runProofread(ProofreadMode.CLEAN, directive = directive, strongModel = true)

    /**
     * Меню кнопки «П». Владелец: «из кнопок я использую только чистку,
     * причесать стиль, обучить и сброс».
     *
     * Отсюда убраны «Короче», «Длиннее», «Откатить», «Коротко», «Ответить»,
     * «Перевод». Это не поломка — все они живы на панели результата и в
     * приложении; в меню их не стало, потому что меню из десяти пунктов, из
     * которых нужны четыре, стоит дороже, чем отсутствующая строка: каждый
     * раз он читает десять, чтобы выбрать одну.
     */
    internal fun showFabMenu() {
        touched()
        val red = FloatingButtonController.REC_RED
        val accent = FloatingButtonController.ACCENT
        // Первой строкой — что с кнопкой прямо сейчас. Владелец: «на каждой
        // должно быть открыть, закрыть и что-то текущее». Для Правки текущее
        // это состояние работы: идёт ли она и есть ли что откатить. Поле
        // читать отсюда нельзя — это чужой процесс и запрос через биндер;
        // хватает того, что стопка отката непуста, а совпадение с текстом
        // проверит сам откат и честно скажет, если не сошлось.
        val canUndo = !UndoStack.isEmpty()
        val state = when {
            busy -> "⋯ Идёт правка"
            canUndo -> "↩︎ Откатить последнюю"
            else -> "Готово к правке"
        }
        floatingButton?.toggleMenu(
            listOf(
                listOf(
                    FloatingButtonController.MenuItem(state, accent) {
                        if (canUndo && !busy) undoLast()
                    },
                    FloatingButtonController.MenuItem(getString(R.string.quick_clean), red) { runProofread(ProofreadMode.CLEAN) },
                    FloatingButtonController.MenuItem("Чистка буфера", red) { cleanClipboardIntoField() },
                    FloatingButtonController.MenuItem(getString(R.string.redo_polish), red) { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_POLISH) },
                    FloatingButtonController.MenuItem("Обучить", red) { learnFromField() },
                    FloatingButtonController.MenuItem("Сброс", red) { resetStuck() },
                    FloatingButtonController.MenuItem("Открыть Правку", red) { openPravkaPrompts() },
                    FloatingButtonController.MenuItem("Настройки", red) { openSettingsTab() },
                    FloatingButtonController.MenuItem("Закрыть", red) { floatingButton?.hideMenu() },
                ),
            )
        )
    }

    /**
     * «Чистка буфера» из меню «П» (владелец, 16.09.2026): «брать то, что
     * сейчас в буфере, править и вставлять в активный текстбокс».
     *
     * Буфер служба сама НЕ читает: с Android 10 его отдают только окну в
     * фокусе или клавиатуре, а службе доступности в фоне приходит null.
     * Поэтому вставляет чужое приложение — ACTION_PASTE в поле под курсором
     * (фокус у него, ему буфер отдадут), а мы по разнице текста «до/после»
     * (`TextSpans.insertedSpan`) находим вставленный кусок, выделяем ровно
     * его и пускаем ту же чистку, что после диктовки: стрим прямо в поле,
     * только по выделению, история и деньги — как обычно. Поля под курсором
     * нет — пробуем прочитать буфер сами (вдруг фокус у нашего окна) и уйти
     * дорогой «без поля»: буфер и уведомление.
     */
    private fun cleanClipboardIntoField() {
        if (busy) return
        busy = true
        scope.launch {
            val svc = this@PravkaAccessibilityService
            val node = runCatching { focusedEditableNode() }.getOrNull()
            if (node == null) {
                busy = false
                val text = runCatching { ru.zf.pravka.target.ClipboardTarget(svc).read() }
                    .getOrNull()?.trim().orEmpty()
                if (text.isEmpty()) {
                    app.eventLog.add("clipboard clean: no field, clipboard unreadable or empty")
                    Haptics.error(svc)
                    Feedback.toast(svc, "Нет поля под курсором — поставь курсор в текстбокс и повтори.")
                } else {
                    app.eventLog.add("clipboard clean: no field -> clean without field len=${text.length}")
                    cleanWithoutField(text)
                }
                return@launch
            }
            // Node calls throw when the window died — must not wedge busy.
            val before = runCatching { Triple(node.effectiveText(), node.textSelectionStart, node.textSelectionEnd) }
                .getOrNull()
            if (before == null) {
                busy = false
                Haptics.error(svc)
                Feedback.toast(svc, "Поле не читается — открой его заново и повтори.")
                return@launch
            }
            val (existing, selStart, selEnd) = before
            val pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            if (!pasted) {
                busy = false
                app.eventLog.add("clipboard clean: paste rejected")
                Haptics.error(svc)
                Feedback.toast(svc, "Поле не приняло вставку из буфера.")
                return@launch
            }
            // Вставляет чужой процесс, поле дорисовывается не мгновенно —
            // ждём изменения текста до трёх раз. Узел после вставки может
            // протухнуть (refresh() = false): тогда берём живой фокус заново,
            // иначе прочитаем старый текст и решим, что вставки не было.
            var live: AccessibilityNodeInfo = node
            var after: String? = null
            for (wait in longArrayOf(150L, 250L, 400L)) {
                kotlinx.coroutines.delay(wait)
                val fresh = runCatching { live.refresh() }.getOrDefault(false)
                if (!fresh) live = runCatching { focusedEditableNode() }.getOrNull() ?: live
                after = runCatching { live.effectiveText() }.getOrNull()
                if (after != null && after != existing) break
            }
            if (after == null || after == existing) {
                busy = false
                app.eventLog.add("clipboard clean: nothing pasted (after==before)")
                Haptics.error(svc)
                Feedback.toast(svc, "В буфере нет текста — вставлять нечего.")
                return@launch
            }
            val span = ru.zf.pravka.core.TextSpans.insertedSpan(existing, after, selStart, selEnd)
            if (span == null) {
                busy = false
                app.eventLog.add("clipboard clean: span not found existing=${existing.length} after=${after.length}")
                Haptics.error(svc)
                Feedback.toast(svc, "Вставил, но границы вставки не нашёл — чистку не запускал.")
                return@launch
            }
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, span.first)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, (span.last + 1).coerceAtMost(after.length))
            }
            val selected = runCatching { live.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
                .getOrDefault(false)
            app.eventLog.add(
                "clipboard clean: pasted existing=${existing.length} after=${after.length} " +
                    "span=${span.first}..${span.last + 1} selected=$selected"
            )
            // Дальше — обычная чистка по выделению; runProofread ставит busy сам
            // и без точки приостановки между этими двумя строками.
            busy = false
            // Как у диктовки: без выделения чистить можно только пустое до того
            // поле — иначе перепишем чужие абзацы, а не вставку.
            if (selected || existing.isEmpty()) {
                runProofread(ProofreadMode.CLEAN, pinnedNode = live)
            } else {
                Haptics.error(svc)
                Feedback.toast(svc, "Вставил, но выделить кусок не удалось — всё поле чистить не стал.")
            }
        }
    }

    /** «Открыть Правку»: приложение на экране промптов — там он их и правит. */
    private fun openPravkaPrompts() {
        runCatching {
            startActivity(
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(
                        ru.zf.pravka.MainActivity.EXTRA_TAB,
                        ru.zf.pravka.MainActivity.TAB_PROMPTS,
                    )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- Learning: the owner hand-edited our output; extract what to keep ----

    // Lazy daily batch: when ripe hand-edits accumulated and the last batch
    // was long ago, one Opus call digests them all into pending suggestions.
    // A detected edit also schedules a check for right after it ripens, so
    // the batch does not have to wait for the next dictation.
    @Volatile var learnBatchRunning = false
        private set
    internal val ripenessCheck = Runnable { maybeRunLearnBatch() }
    // ONE handler instance: removeCallbacks matches by handler, so a fresh
    // Handler per call never actually debounced, and onDestroy could not
    // clear the pending posts (they pinned the dead service for 11 minutes).
    internal val ripenessHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal fun scheduleRipenessCheck() {
        ripenessHandler.removeCallbacks(ripenessCheck)
        ripenessHandler.postDelayed(ripenessCheck, 11L * 60 * 1000)
    }

    // ---- Захват правок владельца: окно после доставки, разбор по тишине ----
    //
    // Владелец (15.09.2026): «как именно он следит за тем, что я правлю? там
    // раньше был крутой механизм». Механизм тот же — EditWatchStore помнит
    // доставленное и ловит правку по событиям текста, — но подписка на события
    // теперь НЕ постоянная (постоянная стоила плавности при складывании и по
    // умолчанию была выключена, то есть не работала): служба подписывается
    // ровно после доставки, на CAPTURE_MS, продлевает окно, пока владелец
    // правит, и отписывается тишиной. Разбор — не Опусом по расписанию, а
    // локально: через DIGEST_QUIET_MS после последней правки или сразу, как
    // только поле опустело (сообщение отправлено). Одно слово → в словарь
    // (core/EditDiff.kt), сложнее → в журнал правок очередью для «Разобрать
    // сейчас». Разбор идёт по сохранённому состоянию поля, само поле к этому
    // моменту может быть уже пустым.
    @Volatile internal var captureUntil = 0L
    private val disarmCapture = object : Runnable {
        override fun run() {
            val left = captureUntil - SystemClock.elapsedRealtime()
            if (left <= 0) applyEventSubscription(false)
            else ripenessHandler.postDelayed(this, left + 500)
        }
    }
    private val digestRunnable = Runnable { digestEdits(DIGEST_QUIET_MS) }

    internal fun armCapture() {
        captureUntil = SystemClock.elapsedRealtime() + CAPTURE_MS
        applyEventSubscription(true)
        ripenessHandler.removeCallbacks(disarmCapture)
        ripenessHandler.postDelayed(disarmCapture, CAPTURE_MS + 500)
    }

    internal fun scheduleDigest() {
        ripenessHandler.removeCallbacks(digestRunnable)
        ripenessHandler.postDelayed(digestRunnable, DIGEST_QUIET_MS + 500)
    }

    /** Дочитать поле через паузу после последнего события — хвост правки. */
    private val trailingProbe = Runnable { cachedFocus?.get()?.let { probeWatchedField(it) } }

    /**
     * Одно чтение поля в окне захвата: непустое — сравнить с доставленным и
     * запомнить как последнее состояние; пустое — сообщение отправлено, правка
     * устоялась, разбираем немедленно по сохранённому состоянию.
     */
    private fun probeWatchedField(source: AccessibilityNodeInfo) {
        val pkg = runCatching { source.packageName?.toString() }.getOrNull() ?: return
        val current = runCatching { source.effectiveText() }.getOrDefault("")
        if (current.isBlank()) {
            digestEdits(0L)
            return
        }
        scope.launch {
            val firstEdit = app.editWatch.onFieldText(pkg, current, ::wordOverlap)
            if (firstEdit) app.learnLog.add("правка замечена: поле в $pkg, ${current.length} зн.")
            // Пока правит — окно захвата продлевается, а разбор ждёт тишины.
            captureUntil = SystemClock.elapsedRealtime() + CAPTURE_EXTEND_MS
            scheduleDigest()
        }
    }

    /**
     * Разбор устоявшихся правок: одно слово → словарь без модели, остальное → в
     * журнал правок очередью для Опуса по кнопке. Зовётся по тишине после
     * правки, по опустевшему полю и перед каждым новым тейком.
     */
    private fun digestEdits(quietMs: Long) {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val quiet = app.editWatch.quietEdited(quietMs)
                for (entry in quiet) {
                    val edited = entry.lastSeen
                    val sub = ru.zf.pravka.core.EditDiff.singleSubstitution(entry.baseline, edited)
                    val known = app.dictionaryStore.all()
                    when {
                        sub != null && known.any { it.from.equals(sub.from, ignoreCase = true) } -> {
                            app.corrections.append(entry.pkg, entry.dictated, entry.cleaned, edited, "same:${sub.from}", done = true)
                        }
                        // Та же основа, другое окончание («Папа → Пап») — правка под
                        // контекст, не ослышка: HARD из неё переписывал каждое «папа»
                        // во всех текстах (16.09). В словарь без модели не идёт —
                        // в очередь «Разобрать сейчас», где Опус видит контекст.
                        sub != null && sub.inflection -> {
                            app.corrections.append(entry.pkg, entry.dictated, entry.cleaned, edited, "pending", done = false)
                            app.learnLog.add("правка формы слова (${sub.from} → ${sub.to}) — не словарь, в очередь «Разобрать сейчас»")
                        }
                        sub != null -> {
                            val mode = if (sub.similar) ru.zf.pravka.core.DictMode.HARD else ru.zf.pravka.core.DictMode.HINT
                            app.dictionaryStore.add(
                                sub.from, sub.to, mode,
                                if (mode == ru.zf.pravka.core.DictMode.HINT) "владелец предпочитает это слово" else "правка руками",
                            )
                            app.corrections.append(
                                entry.pkg, entry.dictated, entry.cleaned, edited,
                                "dict:$mode:${sub.from}→${sub.to}", done = true,
                            )
                            app.learnLog.add("В СЛОВАРЬ из правки руками: ${sub.from} → ${sub.to} [$mode]")
                            app.eventLog.add("edit→dict: ${sub.from} → ${sub.to} [$mode]")
                            cachedBiasing = collectBiasing()
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                Feedback.toast(this@PravkaAccessibilityService, "Словарь: «${sub.from}» → «${sub.to}»")
                            }
                        }
                        else -> {
                            app.corrections.append(entry.pkg, entry.dictated, entry.cleaned, edited, "pending", done = false)
                            app.learnLog.add("правка руками сложнее одного слова — в очередь «Разобрать сейчас» (${entry.pkg})")
                        }
                    }
                    app.editWatch.markDigested(entry.id, edited)
                }
            }.onFailure { app.eventLog.add("digest edits failed: ${it.message}") }
        }
    }

    /** The learning tab's "Разобрать сейчас": no 12h gate, no quiet wait. */
    fun runLearnBatchNow() = maybeRunLearnBatch(force = true)

    private fun maybeRunLearnBatch(force: Boolean = false) {
        if (learnBatchRunning) return
        // Set BEFORE the launch: the old check-then-launch gap (the flag was
        // set only after several suspensions) let a proofread's trailing call
        // and the tab's "Разобрать сейчас" run TWO Opus batches over the same
        // edits - double spend, double rule confirmations.
        learnBatchRunning = true
        scope.launch {
            try {
                val internal = getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE)
                val last = internal.getLong(KEY_LAST_LEARN_BATCH, 0L)
                if (!force && System.currentTimeMillis() - last < cachedLearnPeriodH * 3600_000L) return@launch
                // Очередь — сложные правки из журнала (CorrectionsLog): одно
                // слово в словарь уходит само, Опусу достаётся то, что диффом
                // не разобрать. Устоявшиеся, но ещё не разобранные — досыпаем
                // прямо здесь, не дожидаясь таймера тишины.
                if (app.editWatch.quietEdited(0L).isNotEmpty()) {
                    digestEdits(0L)
                    kotlinx.coroutines.delay(1500)
                }
                val pending = app.corrections.pending().takeLast(8)
                if (pending.isEmpty()) {
                    if (force) {
                        app.learnLog.add("разбор вручную: сложных правок в очереди нет")
                        Feedback.toast(this@PravkaAccessibilityService, "Разбирать нечего: сложных правок нет, одиночные уже в словаре.")
                    }
                    return@launch
                }
                val cases = pending.map { Triple(it.dictated, it.cleaned, it.edited) }
                app.eventLog.add("learn batch: ${cases.size} edits")
                app.learnLog.add("батч-анализ: правок к разбору — ${cases.size}")
                if (force) Feedback.toast(this@PravkaAccessibilityService, "Разбираю правок: ${cases.size} (Опус)…")
                val result = app.claudeProvider.learnBatch(cases, app.dictionaryStore.all())
                result.onSuccess { proposals ->
                    internal.edit().putLong(KEY_LAST_LEARN_BATCH, System.currentTimeMillis()).apply()
                    app.stats.recordAux(proposals.costUsd, proposals.tokensIn, proposals.tokensOut, route = ru.zf.pravka.data.ModelRoute.PRAVKA_LEARN.key)
                    app.learnLog.add("батч-анализ стоил $" + "%.4f".format(java.util.Locale.US, proposals.costUsd))
                    val added = queueProposals(proposals)
                    app.corrections.markDone(pending.map { it.id }, "opus: в словарь $added")
                    app.eventLog.add("learn batch: dict=${proposals.dict.size} added=$added")
                    // Тишина после «Разобрать сейчас» читается как поломка —
                    // пустой результат тоже называется словами.
                    if (added > 0) showLearnNotification(added)
                    else if (force) Feedback.toast(this@PravkaAccessibilityService, "Ничего словарного в правках не нашлось.")
                }.onFailure { e ->
                    app.stats.recordError()
                    app.eventLog.add("learn batch failed: ${e.message}")
                    app.learnLog.add("батч-анализ НЕ УДАЛСЯ: ${e.message} (правки не потеряны)")
                }
            } finally {
                learnBatchRunning = false
            }
        }
    }

    /**
     * ЗВЁЗДОЧКИ НАД «П» БОЛЬШЕ НЕТ. Владелец: «уберём вот эту
     * звёздочку-напоминание, потому что она чуть меня раздражает».
     *
     * И он прав по сути, а не только по вкусу: предложенные правила не
     * срочны — они ждут суда неделями и ничего не портят, пока их не
     * одобрили. Значок же висел на кнопке, которую он трогает десятки раз в
     * день, и требовал внимания ровно так же, как настоящая тревога. Правила
     * никуда не делись: они на вкладке Правки, и там их видно, когда он сам
     * туда пришёл. Функция осталась — её зовут из полудюжины мест, и она
     * теперь просто снимает значок, если он откуда-то взялся.
     */
    fun refreshLearnBadge() {
        floatingButton?.hideLearnBadge()
    }

    /**
     * Находки разбора идут ПРЯМО в словарь с пометкой «авто-обучение» — их
     * легко найти и снять. Правил разбор больше не предлагает (владелец,
     * 08.09.2026: «там уже всё, что возможно, придумано, а он додумывает
     * дурацкие вещи; упор — на замены одного слова другим, и это в словарь»),
     * поэтому и очереди на одобрение у него нет: что не стало словом, не
     * стало ничем. Слово, которое в словаре уже есть, не дублируется.
     */
    private suspend fun queueProposals(proposals: ru.zf.pravka.provider.ClaudeProvider.LearnProposals): Int {
        val known = app.dictionaryStore.all().map { it.from.lowercase() }.toHashSet()
        var added = 0
        proposals.dict
            .filter { it.from.isNotBlank() && it.from.lowercase() !in known }
            .forEach { d ->
                val mode = when (d.mode) {
                    "PROTECT" -> ru.zf.pravka.core.DictMode.PROTECT
                    "HINT" -> ru.zf.pravka.core.DictMode.HINT
                    else -> ru.zf.pravka.core.DictMode.HARD
                }
                val note = listOf(d.note.trim(), "авто-обучение").filter { it.isNotBlank() }.joinToString(" · ")
                app.dictionaryStore.add(d.from, d.to, mode, note)
                added++
                app.learnLog.add("В СЛОВАРЬ автоматически: ${d.from} → ${d.to} [${d.mode}]")
            }
        // New words must bias the recognizer too, same as a manual add.
        if (added > 0) cachedBiasing = collectBiasing()
        return added
    }

    /** One human sentence out of a learn round's outcome. */
    private fun learnSummary(added: Int): String = "В словарь добавлено: $added."

    private fun showLearnNotification(added: Int) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-learning"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "Обучение Правки",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val open = android.app.PendingIntent.getActivity(
                this, 3,
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = android.app.Notification.Builder(this, channelId)
                .setContentTitle("Правка научилась новому")
                .setContentText(learnSummary(added))
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(3, notif)
        }
    }

    private fun learnFromField() {
        if (busy) return
        // Held through the probe phase too: the old late set let a proofread
        // start mid-probe and then get its guard force-cleared by this path.
        busy = true
        scope.launch {
            val node = runCatching { focusedEditableNode() }.getOrNull()
            val current = node?.let { runCatching { it.effectiveText() }.getOrDefault("") }.orEmpty()
            if (current.isBlank()) {
                busy = false
                Feedback.toast(this@PravkaAccessibilityService, "Нет текста в поле — открой поле с поправленным текстом.")
                return@launch
            }
            // Find the journal entry whose OUTPUT this text is an edit of:
            // word-overlap similarity against recent outputs.
            val recent = kotlinx.coroutines.withContext(Dispatchers.IO) { app.historyLog.readPairs(30) }
            val match = recent.maxByOrNull { (_, out) -> wordOverlap(out, current) }
            val overlap = match?.let { wordOverlap(it.second, current) } ?: 0.0
            if (match == null || overlap < 0.4) {
                busy = false
                Feedback.toast(this@PravkaAccessibilityService, "Не нашёл в истории версию, из которой сделан этот текст.")
                return@launch
            }
            if (match.second.trim() == current.trim()) {
                busy = false
                Feedback.toast(this@PravkaAccessibilityService, "Текст не отличается от версии Правки — учиться не на чем.")
                return@launch
            }
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            Feedback.toast(this@PravkaAccessibilityService, "Учусь на твоих правках (Опус)…")
            app.learnLog.add("ручной разбор («Обучить»): текст ${current.length} зн., совпадение с журналом ${"%.2f".format(overlap)}")
            val result = app.claudeProvider.learn(
                dictated = match.first,
                cleaned = match.second,
                final = current,
                known = app.dictionaryStore.all(),
            )
            busy = false
            floatingButton?.setBusy(false)
            result.onSuccess { proposals ->
                app.stats.recordAux(proposals.costUsd, proposals.tokensIn, proposals.tokensOut, route = ru.zf.pravka.data.ModelRoute.PRAVKA_LEARN.key)
                app.learnLog.add("разбор стоил $" + "%.4f".format(java.util.Locale.US, proposals.costUsd))
                val added = queueProposals(proposals)
                app.eventLog.add("learn: dict=${proposals.dict.size} added=$added")
                val pkgNow = runCatching { node?.packageName?.toString() }.getOrNull().orEmpty()
                app.corrections.append(pkgNow, match.first, match.second, current, "opus («Обучить»): в словарь $added", done = true)
                // This edit is analyzed - close its auto-watch so the batch
                // doesn't re-analyze the same text later.
                val closed = app.editWatch.all()
                    .filter { wordOverlap(it.lastSeen, current) > 0.5 }
                    .map { it.id }
                if (closed.isNotEmpty()) {
                    app.editWatch.remove(closed)
                    app.learnLog.add("наблюдение закрыто: разобрано вручную (${closed.size})")
                }
                if (added == 0) {
                    Feedback.toast(this@PravkaAccessibilityService, "Ничего словарного в правках не нашлось.")
                } else {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, learnSummary(added))
                }
            }.onFailure { e ->
                app.stats.recordError()
                app.eventLog.add("learn failed: ${e.message}")
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, e.message ?: "Ошибка обучения")
            }
        }
    }

    /**
     * Правки владельца → словарь, без слежки за полями. Перед новым тейком поле
     * читается один раз (его и так ищут как цель вставки) и сравнивается с
     * тем, что мы в него прислали: поменялось одно слово — запись в словарь
     * сразу, без модели (`core/EditDiff.kt`: похожее слово — замена, иное —
     * подсказка); сложнее — остаётся кнопке «Разобрать сейчас». Владелец
     * (15.09.2026): «должны добавляться правки, если я правлю тот текст,
     * который он уже прислал в текстбокс». Один binder-вызов не на главном
     * потоке, никаких подписок на события.
     */
    private fun probeFieldEdits(node: AccessibilityNodeInfo?) {
        if (node == null) return
        scope.launch(Dispatchers.Default) {
            val pkg = runCatching { node.packageName?.toString() }.getOrNull() ?: return@launch
            val current = runCatching { node.effectiveText() }.getOrDefault("")
            if (current.isBlank()) return@launch
            runCatching {
                // Правка, сделанная без событий (окно захвата истекло, служба
                // перезапускалась): поле сравнивается с доставленным здесь; сам
                // разбор общий, и тишины он уже не ждёт — правка устоялась.
                app.editWatch.onFieldText(pkg, current, ::wordOverlap, windowMs = 6L * 3600 * 1000)
            }.onFailure { app.eventLog.add("edit probe failed: ${it.message}") }
            if (app.editWatch.quietEdited(0L).isNotEmpty()) digestEdits(0L)
        }
    }

    /** Jaccard word overlap in [0..1] - enough to match an edited output. */
    internal fun wordOverlap(a: String, b: String): Double {
        val wa = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val wb = b.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size
    }

    // ---- AI actions: selection > field > clipboard; result to the clipboard ----

    internal suspend fun assistContent(): String {
        focusedEditableNode()?.let { node ->
            val text = runCatching { node.effectiveText() }.getOrDefault("")
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (start in 0 until end && end <= text.length) return text.substring(start, end)
            if (text.isNotBlank()) return text
        }
        return runCatching { ru.zf.pravka.target.ClipboardTarget(this).read().orEmpty() }.getOrDefault("")
    }

    private fun runAssist(tag: String, instruction: String) {
        if (busy) return
        busy = true
        activeJob = scope.launch {
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            val content = runCatching { assistContent() }.getOrDefault("")
            if (content.isBlank()) {
                busy = false
                floatingButton?.setBusy(false)
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, "Нет текста: ни выделения, ни поля, ни буфера.")
                return@launch
            }
            floatingButton?.showTicker()
            floatingButton?.updateTicker("…")
            val onDelta: (String) -> Unit = { partial ->
                scope.launch { floatingButton?.updateTicker(partial) }
            }
            val result = runCatching { app.claudeProvider.assist(instruction, content, onDelta) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Result.failure(it) }
            floatingButton?.hideTicker()
            floatingButton?.setBusy(false)
            busy = false
            result.onSuccess { r ->
                app.stats.recordAux(r.costUsd, r.inputTokens, r.outputTokens, route = ru.zf.pravka.data.ModelRoute.PRAVKA.key)
                ru.zf.pravka.target.ClipboardTarget(this@PravkaAccessibilityService).write(r.text)
                app.historyLog.append(
                    mode = "ASSIST_" + tag.uppercase(),
                    providerId = r.providerId,
                    model = r.modelId,
                    latencyMs = r.latencyMs,
                    inputTokens = r.inputTokens,
                    outputTokens = r.outputTokens,
                    costUsd = r.costUsd,
                    changed = true,
                    input = content.take(2000),
                    output = r.text,
                    error = null,
                    cacheWriteTokens = r.cacheWriteTokens,
                    cacheReadTokens = r.cacheReadTokens,
                )
                app.eventLog.add("assist $tag ok ${r.text.length} ch")
                Haptics.success(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, "Готово — ответ в буфере обмена")
            }.onFailure { e ->
                app.stats.recordError()
                app.eventLog.add("assist $tag failed: ${e.message}")
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, e.message ?: "Ошибка")
            }
        }
    }

    // The active API round trip - cancellable by the "Сброс" menu item when
    // a dead-zone network leaves the button spinning.
    internal var activeJob: kotlinx.coroutines.Job? = null

    /** Menu "Сброс": cancel whatever is in flight and unfreeze the button. */
    fun resetStuck() {
        app.eventLog.add("manual reset (button)")
        app.learnLog.add("ручной сброс кнопки")
        runCatching { activeJob?.cancel() }
        activeJob = null
        // Close the sockets too: cancelling the job alone left a zombie HTTP
        // stream billing in the background for up to 90 seconds.
        runCatching { app.claudeProvider.cancelActive() }
        runCatching { googleSession?.stop() }
        busy = false
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(false)
        floatingButton?.hideTicker()
        floatingButton?.hideCancelBubble()
        Feedback.toast(this, "Сброшено. Результат зависшего запроса, если он дойдёт, будет отброшен.")
    }

    private fun runProofread(
        mode: ProofreadMode,
        pinnedNode: AccessibilityNodeInfo? = null,
        directive: String = "",
        strongModel: Boolean = false,
        conversationContext: String = "",
        // Dictated raw text: when set and the CLEAN succeeds, the delivered
        // field goes under edit-watch so hand-edits feed the learning loop.
        watchDictated: String? = null,
    ) {
        if (busy) return
        // Set synchronously: the old set-inside-launch left a dispatch-wide
        // window where a second trigger slipped past the guard.
        busy = true
        activeJob = scope.launch {
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            // The pinned (dictation) path arrives with its selection already
            // set. Node calls throw on a dead window - that must not leave
            // busy=true forever (the wedge "Сброс" was built to rescue).
            if (pinnedNode == null) runCatching { selectAllInFocusedField() }
            // Stream the corrected text STRAIGHT INTO THE FIELD while it
            // generates (owner: "чтобы сразу ушёл в текстбокс, без плашки") -
            // the work item is replaced in place as the words arrive. The
            // ticker plate is only the fallback: fields that reject SET_TEXT
            // (WebView), a dead node, or the owner typing mid-stream flip the
            // stream back onto the ticker. Deltas arrive on an IO thread; both
            // the node write and the ticker are main-thread work, so hop.
            val target = AccessibilityTarget(this@PravkaAccessibilityService, pinnedNode)
            // Сильная модель думает перед ответом: несколько секунд без текста.
            // Show a pulse so the wait doesn't read as a hang.
            if (strongModel) {
                floatingButton?.showTicker()
                floatingButton?.updateTicker("…")
            }
            var previewAlive = true
            var lastPreviewAt = 0L
            val onDelta: (String) -> Unit = { partial ->
                scope.launch {
                    if (previewAlive) {
                        val t = SystemClock.elapsedRealtime()
                        if (t - lastPreviewAt >= 150) {
                            lastPreviewAt = t
                            if (target.preview(partial)) {
                                floatingButton?.hideTicker()
                            } else {
                                previewAlive = false
                                floatingButton?.showTicker()
                                floatingButton?.updateTicker(partial)
                            }
                        }
                    } else {
                        floatingButton?.updateTicker(partial)
                    }
                }
            }
            // A throw anywhere below must never leave busy=true forever (a
            // wedged button until service restart) - degrade to Failed.
            // EXCEPT cancellation: a job killed by "Сброс" must die silently
            // here, not run this epilogue against the job that replaced it.
            val outcome = runCatching {
                app.engine.proofread(
                    target, mode, onDelta,
                    directive = directive,
                    strong = strongModel,
                    conversationContext = conversationContext,
                )
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.eventLog.add("proofread threw ${e.javaClass.simpleName}: ${e.message}")
                ProofreadEngine.Outcome.Failed(e.message ?: "Неизвестная ошибка")
            }
            floatingButton?.hideTicker()
            floatingButton?.setBusy(false)
            busy = false
            app.eventLog.add(
                "proofread ${mode.name}${if (directive.isNotBlank()) "+redo" else ""}" +
                    "${if (strongModel) "(сильнее)" else ""}: ${outcome.javaClass.simpleName}"
            )
            Feedback.report(this@PravkaAccessibilityService, outcome)
            // Auto-capture for learning: remember what we delivered; if the
            // owner hand-edits it, the edit ripens into a learning suggestion.
            if (watchDictated != null && outcome is ProofreadEngine.Outcome.Applied) {
                val pkg = runCatching { pinnedNode?.packageName?.toString() }.getOrNull()
                if (!pkg.isNullOrBlank()) {
                    app.editWatch.watch(pkg, watchDictated, outcome.result.text)
                    lastDeliveryAt = SystemClock.elapsedRealtime()
                    convoUpdateLast(pkg, outcome.result.text)
                    // Окно захвата: события текста только теперь и только на время.
                    armCapture()
                }
            }
            // Авторазбора Опусом после чистки больше нет: батч идёт только по кнопке.
            // The post-fix result bar is gone (owner: it covered the keyboard).
            // Undo lives in the long-press FAB menu; the word diff and quick
            // add-to-dictionary went with the bar.
        }
    }

    // Visual feedback: highlight the whole field the moment proofreading
    // starts, so it is obvious what is being processed. When the user has
    // already selected a fragment, that selection is the work item - keep
    // it (AccessibilityTarget will fix only the selected part).
    private fun selectAllInFocusedField() {
        val node = focusedEditableNode() ?: return
        val length = node.text?.length ?: return
        if (length == 0) return
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start in 0 until end) return
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun undoLast() {
        if (busy) return
        scope.launch {
            val target = AccessibilityTarget(this@PravkaAccessibilityService)
            val current = target.read()
            val entry = UndoStack.matchByCurrentText(current)
            when {
                entry == null -> {
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, getString(R.string.toast_nothing_to_undo))
                }
                target.write(entry.before) -> {
                    UndoStack.remove(entry)
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, getString(R.string.toast_undone))
                }
                else -> {
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, getString(R.string.toast_undo_failed))
                }
            }
        }
    }

    // ---- Засечка: tap -> capture -> categorize -> timesheet (no field) ----

    /** The "З" button, the notification action and the tab's mic button. */
    // ---- Засечка с заблокированного экрана ----
    //
    // Карманный страж глушит все касания на локскрине: случайное нажатие в
    // кармане не должно ни начать запись, ни открыть меню. Но владелец хочет
    // диктовать НЕ разблокируя — и это правильно, ради того Засечка и есть.
    // Компромисс — двойной тап: в кармане он практически невозможен, а
    // намеренно делается за полсекунды. Только по «З»: остальным кнопкам
    // диктовать с локскрина незачем.
    internal var lockArmedAt = 0L

    // ---- Chrome per-site time: REMOVED (owner's call) ----
    //
    // The omnibox poller queried Chrome's a11y tree every 7s while Chrome was
    // foregrounded. Even off the main thread, those are synchronous binder
    // calls into another process - fold Chrome mid-transition and the query
    // lands in a freezing app while the system's per-service interaction
    // queue waits behind it. The owner's fold black-screens correlated with
    // it, so the whole feature is out; per-app minutes from UsageStats stay.

    // ---- Потолок записи с локскрина и стопка кнопок ----
    //
    // Один общий тик на две вещи: обе живут по таймеру и обе должны молчать,
    // пока таймера нет. Отдельный Handler вместо корутины сознательно — это
    // работа с окнами, ей место на главном потоке, и снимается она одной
    // строкой в onDestroy.

    internal var lockedTakeCapAt = 0L
    internal var lastTouchAt = System.currentTimeMillis()
    internal var stacked = false
    /** Все четыре убраны в верхнюю ручку. */
    private var allHidden = false
    internal val chromeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val chromeTicker = object : Runnable {
        override fun run() {
            tickChrome()
            chromeHandler.postDelayed(this, 2_000)
        }
    }

    private fun tickChrome() {
        val now = System.currentTimeMillis()
        val screenLocked = runCatching { keyguardManager?.isKeyguardLocked == true }
            .getOrDefault(false)

        // Раньше здесь стопку на локскрине разворачивало: двойной тап целится
        // в «З», а из сложенной стопки торчала «П». Теперь «З» из стопки не
        // уезжает никогда и на локскрине работает как есть — разворачивать
        // нечего, и незачем разбрасывать кнопки по экрану блокировки.

        // Потолок записи, начатой с заблокированного экрана.
        if (lockedTakeCapAt > 0) {
            val locked = screenLocked
            val recording = zSession != null || zWhisperRecording
            when {
                // Разблокировал — он тут и говорит: потолок снимаем.
                !locked -> lockedTakeCapAt = 0L
                !recording -> lockedTakeCapAt = 0L
                now >= lockedTakeCapAt -> {
                    lockedTakeCapAt = 0L
                    app.eventLog.add("засечка: запись с локскрина закрыта по потолку 40 сек")
                    onZasechkaTap()   // тот же путь, что у ручной остановки
                }
            }
        }

        // Стопка: собрать кнопки, когда их давно не трогали. Посреди работы
        // не складываем никогда — кнопка, уехавшая под другую в тот момент,
        // когда её собираются нажать, это худший из возможных сюрпризов.
        val working = busy || googleSession != null || zSession != null ||
            rSession != null || eSession != null || DictationService.recording ||
            zWhisperRecording || rWhisperRecording || eWhisperRecording
        val quiet = !working && !screenLocked && now - lastTouchAt >= STACK_IDLE_MS
        if (cachedDiskMode) {
            // Диск: полминуты без касаний — к ближайшему краю и домой, свой
            // тумблер («Автоматически убирать диск к краю»); стопочный его
            // не касается.
            if (cachedDiskTuck && quiet) disk?.tuck()
            // И глубже: не трогали минутами — диск утопает за край так, что
            // остаётся четверть кнопок (владелец, 20.09.2026). Тап по этому
            // краю его достаёт, а кнопку не нажимает.
            val longQuiet = !working && !screenLocked &&
                now - lastTouchAt >= cachedDiskSinkMin * 60_000L
            if (cachedDiskSink && longQuiet) disk?.sink()
        } else if (!stacked && cachedStackIdle && quiet) {
            collapseButtons()
        }
        // Самолечение: при старте службы позиции кнопок могло ещё не
        // быть, и стрелка тогда не нарисовалась. Тик её донесёт. Он же
        // вернёт кнопки, если складывание почему-то не доиграло свой
        // configSettled: остаться без кнопок насовсем нельзя. В стопке —
        // когда не складываем на этом тике; на диске — всегда: диск не
        // складывается, `stacked` у него не поднимается никогда.
        if (cachedDiskMode || stacked || !(cachedStackIdle && quiet)) {
            if (!folding) setFolded(false)
            refreshHandles()
        }
    }

    /** Любое касание любой кнопки — отсчёт до стопки начинается заново. */
    internal fun touched() {
        lastTouchAt = System.currentTimeMillis()
        // Трогали что угодно — утопание считается с этого мига заново.
        disk?.awake()
    }

    /**
     * Кнопки в стопку — до ДВУХ. Владелец: «сворачивание пускай будет до двух
     * кнопок: правки и засечки, я их больше всего использую. а остальные две
     * пускай прячутся в них. если я кликаю чуть ниже засечки, где стопка, то
     * они раскрываются. а сами правка и засечка работают всегда».
     *
     * Отсюда две вещи. «П» и «З» не складываются вовсе и не съедают первый
     * тап: раньше по сложенной стопке первое нажатие означало «покажи
     * кнопки», и до диктовки надо было тапнуть дважды — на двух самых частых
     * кнопках это налог на каждое использование.
     *
     * «Д» и «Е» СХЛОПЫВАЮТСЯ В «З» и прячутся совсем. Первая попытка
     * оставляла их торчать краями со сдвигом в пять точек — и они наезжали
     * на саму «З»: сдвиг был рассчитан на прежнюю раскладку, где из-под
     * ОДНОЙ кнопки выглядывали три. Владелец увидел это сразу и предложил
     * лучшее: «сделать стрелочку, как галочка, вниз, на которую нажимаешь, и
     * выпадают эти две кнопки». Так и сделано — у стрелки своя площадь, она
     * прямо говорит, что будет, и целиться в трёхмиллиметровую полоску
     * больше не надо. Спрятанная без ручки кнопка читалась бы как
     * пропавшая — а это худшее, что можно сделать с инструментом, которым
     * размечают день; ручка и есть стрелка.
     */
    internal fun collapseButtons() {
        if (cachedDiskMode) {
            // Диск не складывается — убирается к краю и домой: «П» и «З» внутрь экрана.
            disk?.tuck()
            return
        }
        if (stacked) return
        val (x, y) = floatingButton?.currentPosition() ?: return
        stacked = true
        // Первые две («П» и «З») остаются на своих местах и работают как
        // обычно; всё, что дальше по связке («Д», «Е»), схлопывается В «З» и
        // прячется: оттуда же и выедет. Раньше спрятанные
        // оставались торчать краями — и наезжали на саму «З».
        val underZ = y + slotOffset(1)
        chain().forEachIndexed { j, b ->
            if (j >= 1) b.followTo(x, underZ, true)
            b.setStacked(j >= 2)
        }
        refreshHandles()
    }

    /**
     * Убрать или вернуть ВСЁ. Верхняя ручка — единственный способ снять с
     * экрана и «П», и «З»: до неё убрать их можно было только тумблерами в
     * настройках, а иногда экран просто нужен целиком.
     *
     * Возвращается всегда полный набор из четырёх, как владелец и просил:
     * «если буду на неё нажимать, то будут выпадать все четыре, и потом
     * точно так же скрываться две». Прятать по одной — работа нижней ручки.
     */
    private fun setAllHidden(hidden: Boolean) {
        if (allHidden == hidden) return
        allHidden = hidden
        if (hidden) {
            stackSettings?.hideFan()
            chainButtons().forEach { it.setStacked(true) }
            // Значки и плашки привязаны к кнопкам: без них они висели бы
            // посреди экрана сами по себе. Убрать — значит убрать всё.
            floatingButton?.hideLearnBadge()
            floatingButton?.hideCancelBubble()
            zButton?.hideTicker()
            zButton?.hideCancelBubble()
            rButton?.hideTicker()
            rButton?.hidePlate()
            rButton?.hideCancelBubble()
            eButton?.hideTicker()
            eButton?.hidePlate()
            eButton?.hideCancelBubble()
            stacked = !cachedDiskMode
            disk?.setAllHidden(true)
        } else if (cachedDiskMode) {
            // Диск: кнопки возвращаются прямо на кольцо, тарелка — под них.
            stacked = false
            chainButtons().forEach { it.setStacked(false) }
            disk?.setAllHidden(false)
            refreshLearnBadge()
        } else {
            stacked = true      // чтобы expandButtons развёз все четыре
            expandButtons(silent = true)
            refreshLearnBadge()
        }
        refreshHandles()
        Haptics.start(this)
    }

    /**
     * Диск или стопка. Диск (владелец, 19.09.2026) — те же четыре кнопки на
     * кольце вокруг шестерёнки (`DiskController`), стопка — прежний столбик с
     * ручкой. Переключается тумблером в Общих на живой службе: владелец
     * сказал «если не получится — откатим», и откат обязан быть одним
     * движением, без пересборки.
     */
    private fun applyDiskMode(on: Boolean) {
        chainButtons().forEach { it.ringMode = on }
        stackSettings?.ringMode = on
        if (on) {
            tailHandle?.hide()
            // На диске спрятанных нет: всё, что не убрано в точку, стоит на кольце.
            stacked = false
            if (!allHidden) chainButtons().forEach { it.setStacked(false) }
            disk?.setAllHidden(allHidden)
            disk?.show()
        } else {
            disk?.hide()
            stackSettings?.clearance = 0
            // Обратно в стопку: каждая кнопка возвращается на своё сохранённое
            // место — той же дорогой, что после поворота экрана.
            chainButtons().forEach { it.onConfigurationChanged() }
        }
        refreshHandles()
    }

    /** Все кнопки связки по порядку: П · З · Д · Е — включённые и нет. */
    internal fun chainButtons(): List<RingButton> =
        listOfNotNull(floatingButton, zButton, rButton, eButton)

    /** Включённые кнопки связки по порядку — то, что реально стоит на экране. */
    internal fun chain(): List<RingButton> = listOfNotNull(
        floatingButton,
        zButton?.takeIf { cachedZEnabled },
        rButton?.takeIf { cachedREnabled },
        eButton?.takeIf { cachedEEnabled },
    )

    /** Слот кнопки с номером [index] в связке сейчас: сложено — всё после «З» лежит под «З». */
    private fun chainSlot(index: Int): Int = if (stacked) minOf(index, 1) else index

    /**
     * Связка едет за [from] — кнопкой, чей левый верхний угол теперь в
     * ([x], [y]); null — за головой над «П», тогда едут все, включая «П».
     * Бусы: чем дальше звено от пальца (`link`), тем мягче пружина.
     */
    private fun followChain(from: RingButton?, x: Int, y: Int, dropped: Boolean) {
        val list = chain()
        val i = if (from == null) 0 else list.indexOf(from)
        if (i < 0) return
        val top = y - slotOffset(chainSlot(i))
        list.forEachIndexed { j, b ->
            if (b === from) return@forEachIndexed
            val link = if (from == null) j + 1 else abs(j - i)
            b.followTo(x, top + slotOffset(chainSlot(j)), dropped, link = link)
        }
        refreshHandles()
    }

    /**
     * Экран настроек приложения — пункт «Настройки» в меню долгого нажатия
     * каждой кнопки (владелец, 19.09.2026: «в меню длинного тапа на каждую
     * кнопку тоже возможность открыть настройки»).
     */
    internal fun openSettingsTab() {
        runCatching {
            startActivity(
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Ручка и шестерёнка на местах. Ручка — под ХВОСТОМ: сложено, значит под
     * «З», разложено — под последней включённой кнопкой; причём под её
     * ТЕКУЩИМ местом, а не расчётным: пока бусы догоняют палец, хвост ещё в
     * пути, и галочка, вставшая на его будущее место раньше него, читалась бы
     * как чужая. Шестерёнка — над «П»; когда всё убрано, на её месте красная
     * точка — единственное, что видно на экране: иначе кнопки было бы не
     * вернуть.
     *
     * Нечего прятать — ручки нет: ручка от ящика, которого не существует,
     * хуже, чем её отсутствие.
     */
    internal fun refreshHandles() {
        if (folding) return
        if (cachedDiskMode) {
            // Диск: шестерёнка (или точка) стоит в его центре, ручки-галочки нет —
            // прятать под краем и без неё есть чему.
            stackSettings?.show(dot = allHidden)
            tailHandle?.hide()
            disk?.refresh()
            return
        }
        val (x, y) = floatingButton?.currentPosition() ?: return
        val size = floatingButton?.buttonSizePx() ?: return

        stackSettings?.let { s ->
            s.show(dot = allHidden)
            s.moveTo(x, y, size)
        }

        val tail = tailHandle ?: return
        val list = chain()
        // В связке две кнопки или меньше — прятать нечего, и ручки нет.
        if (allHidden || list.size <= 2) {
            tail.hide()
            return
        }
        val lastSlot = if (stacked) 1 else list.size - 1
        val (lx, ly) = list[lastSlot].currentPosition() ?: (x to (y + slotOffset(lastSlot)))
        tail.show(stacked)
        tail.moveTo(lx, ly, size, above = false)
    }

    /**
     * Смещение верха слота стопки от верха «П»: 1 — «З», 2 — «Д», 3 — «Т».
     * Просвет между кнопками одинаковый; арифметика одна на всех —
     * `core/StackGeometry.kt`, под тестами.
     */
    internal fun slotOffset(slot: Int): Int {
        val size = floatingButton?.buttonSizePx() ?: 0
        val gap = (8 * resources.displayMetrics.density).toInt()
        return StackGeometry.slotOffset(slot, size, gap)
    }

    /**
     * Развернуть обратно в цепочку. Возвращает true, если разворот и был
     * действием — тогда сам тап не должен ничего запускать: первое нажатие по
     * стопке это «покажи кнопки», а не «пиши».
     */
    internal fun expandButtons(silent: Boolean = false): Boolean {
        if (!silent) touched()
        // На диске прятаться некуда и разворачивать нечего: тап — сразу дело.
        if (cachedDiskMode) return false
        if (!stacked) return false
        val (x, y) = floatingButton?.currentPosition() ?: return false
        stacked = false
        // Сначала показать, потом развезти: тогда видно, как спрятанные
        // выезжают из-под «З», а не как они появляются готовыми на местах.
        // setStacked(false) — всем, и выключенным: иначе кнопка, включённая
        // позже тумблером, осталась бы убранной.
        chainButtons().forEach { it.setStacked(false) }
        chain().forEachIndexed { j, b -> if (j >= 1) b.followTo(x, y + slotOffset(j), true) }
        refreshHandles()
        if (!silent) Haptics.start(this)
        return true
    }

    // ---- Экран во время диктовки ----

    private var screenKeeper: android.view.View? = null

    /**
     * Пока идёт любой тейк — Правки, Засечки, Дел, Тела — экран не гаснет.
     * Владелец: «когда начитываю, Правка не держит экран: он гаснет, и
     * приходится тыкать в текстбокс». Служба не Activity и своего окна с
     * содержимым не имеет, поэтому держит крошечное невидимое окно 1×1 с
     * FLAG_KEEP_SCREEN_ON: система не гасит экран, пока видно хотя бы одно
     * окно с этим флагом, — это и есть штатный способ, устаревшие
     * SCREEN_*_WAKE_LOCK не нужны. Окно живёт ровно столько, сколько микрофон
     * (зовёт DictationService на старте и стопе), и снимается из
     * WindowManager, а не прячется: складыванию Fold каждое наше окно стоит
     * перерисовки.
     */
    fun keepScreenOn(on: Boolean) {
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        if (!on) {
            val v = screenKeeper ?: return
            screenKeeper = null
            runCatching { wm.removeView(v) }
            return
        }
        if (screenKeeper != null) return
        val v = android.view.View(this)
        val p = android.view.WindowManager.LayoutParams(
            1, 1,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        screenKeeper = v
        if (runCatching { wm.addView(v, p) }.isFailure) screenKeeper = null
    }

    // ---- Засечка reminders: the button itself nags about the gaps ----

    internal val zReminderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val zReminderTick = object : Runnable {
        override fun run() {
            // Midnight housekeeping first: a дело running across 00:00 splits
            // into yesterday's closed head and today's open tail, so the new
            // day's ribbon and totals are right from the first minutes.
            scope.launch { runCatching { app.zasechkaStore.normalize() } }
            // The sweeps next: they may close a gap (a YouTube session, a
            // call, a workout becomes an entry) that the reminder would
            // otherwise nag about. Fire-and-forget - the check reads current data.
            scope.launch { app.phoneSweeper.sweep() }
            scope.launch { app.icuSweeper.sweep() }
            // Ночной разбор: запустить назревший прогон или спросить статус
            // батча. Сам себя дросселирует (раз в 10 минут), тику не мешает.
            // Отметка тика — пульс для табло «что работает»: нет тика — стоят все.
            app.lastNightTickMs = System.currentTimeMillis()
            scope.launch {
                runCatching { app.nightReview.tick() }
                    .onFailure { app.nightLog.add("ночной разбор: тик упал: ${it.message}") }
            }
            // Недельная правка промпта — в ночь на субботу, тем же тиком.
            scope.launch {
                runCatching { app.promptTuner.tick() }
                    .onFailure { app.nightLog.add("правка промпта: тик упал: ${it.message}") }
            }
            // Сравнение моделей само не стартует — тик только докручивает начатое кнопкой.
            scope.launch {
                runCatching { app.modelCompare.tick() }
                    .onFailure { app.nightLog.add("сравнение: тик упал: ${it.message}") }
            }
            // Спорт и еда: свой кэш и своя недоставленная почта. Оба звонка
            // сами себя дросселируют (30 минут у выгрузки, «уже уехало» у
            // еды), так что пятиминутный тик может дёргать их сколько хочет.
            scope.launch {
                runCatching { app.icuSportSync.refresh() }
                // Приехало новое с часов — уведомление с вердиктом по его
                // правилам и кнопками самочувствия. Замыкает петлю feel,
                // которую иначе надо помнить самому.
                runCatching { notifyArrivedWorkouts() }
                runCatching { autoPilot.tick() }
            }
            scope.launch { runCatching { app.foodEngine.syncPending() } }
            // Дневник в Notion: галочки, feel, колено и вес уезжают сами.
            // Свой дроссель на полчаса и свой «ничего не изменилось» внутри.
            scope.launch { runCatching { app.notionDiarySync.sync() } }
            // Вся жизнь в Notion: полный обход раз в час, очередь разгребается
            // каждый тик пачкой — Notion пускает три запроса в секунду.
            scope.launch { runCatching { app.notionLifeSync.sync() } }
            // План: календарь раз в час, правила блока раз в сутки — оба
            // звонка дросселируются сами.
            scope.launch { runCatching { app.planSync.refresh() } }
            // Подходы: ждут активность от часов и уезжают, как только она
            // появится. Свой дроссель на десять минут внутри.
            scope.launch { runCatching { app.strengthEngine.syncPending() } }
            // Закрылось дело, пришедшее из Todoist - в задачу уезжает время.
            scope.launch { runCatching { app.todoistSync.flushLinks() } }
            // Копии на диск: сама проверка стоит один listFiles, копирование
            // уходит на writer-поток и случается раз в час (имя файла = часовая
            // засечка), так что тик может дёргать её сколько угодно.
            ru.zf.pravka.data.Backups.tick(this@PravkaAccessibilityService) { line ->
                app.eventLog.add(line)
            }
            // Обновления: сам решает, прошли ли сутки, сам тянет и сам говорит.
            scope.launch { runCatching { app.updates.tick() } }
            zasechkaReminderCheck()
            zReminderHandler.postDelayed(this, 5 * 60_000L)
        }
    }

    // Следующая диктовка кнопки идёт через СТАРЫЙ роутер Тела (подходы,
    // зарядка, вопрос) — взводится пунктом меню «Тело голосом». По умолчанию
    // кнопка теперь ЕДА напрямую: владелец спорт наговаривает во вкладке.
    @Volatile internal var eRouteNext = false

    // ---- Отдых между подходами ----

    internal val restHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val restTick = object : Runnable {
        override fun run() {
            val left = ((restUntil - System.currentTimeMillis()) / 1000L).toInt()
            if (left <= 0) {
                restUntil = 0L
                eButton?.setRest(0)
                Haptics.success(this@PravkaAccessibilityService)
                eButton?.showNote("Отдых кончился — следующий подход", null, holdMs = 8_000, onAction = null)
                return
            }
            eButton?.setRest(left)
            restHandler.postDelayed(this, 1000)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A focusable type-in box must not sit above the keyguard through a
        // display switch - fold closes it (the draft is a sentence, not a loss).
        // This one is immediate: removing a window helps the transition.
        zButton?.hideInput()
        rButton?.hideInput()
        rButton?.hidePlate()
        eButton?.hideInput()
        eButton?.hidePlate()
        // И обе серые ручки — с ними же. Это два лишних оверлейных окна, а
        // складывание пересчитывает и ЖДЁТ каждое наше окно: ровно из-за
        // лишних окон чернота на пять секунд приходила все три прошлых раза.
        // Ручки снимаются мгновенно и возвращаются через configSettled, когда
        // складывание уже прошло; полсекунды без ручки никто не заметит.
        folding = true
        tailHandle?.hide()
        stackSettings?.hideAll()
        disk?.setFolded(true)
        // И сами кнопки. Владелец показал, где ответ: «если все кнопки
        // сложить в три точки, то никаких проблем нет, складывается всё
        // отлично» — в журнале при этом «наших окон 0». Значит дело не в том,
        // ЧЬИ окна, а в том, сколько их: четыре — уже дорого. На полсекунды
        // перехода они не нужны никому, экран в этот момент чёрный.
        setFolded(true)
        // Repositioning ADDS work to the transition the system is running right
        // now: updateViewLayout on our overlays makes WindowManager wait for
        // them to redraw mid-fold. Do it once the fold has settled instead -
        // half a second of the buttons sitting at their old spot is invisible
        // next to a 4-second black screen.
        configHandler.removeCallbacks(configSettled)
        configHandler.postDelayed(configSettled, 600)
        // Census in the log: every overlay window we hold is a window the fold
        // transition must relayout and WAIT for. If a freeze report ever comes
        // back with a big number here, the leak is ours; a small one clears us.
        runCatching {
            val n = chainButtons().sumOf { it.windowCount() } +
                (tailHandle?.windowCount() ?: 0) + (stackSettings?.windowCount() ?: 0) +
                (disk?.windowCount() ?: 0) + (if (screenKeeper != null) 1 else 0)
            app.eventLog.add("смена конфигурации: наших окон $n")
        }
    }

    /**
     * Складывание идёт прямо сейчас. Пока флаг поднят, окон в WindowManager
     * не прибавляется: тик, случайно попавший в середину складывания, вернул
     * бы ручки обратно ровно тогда, когда система их и ждать не должна.
     */
    internal var folding = false

    /** Снять или вернуть окна всех четырёх кнопок на время складывания. */
    private fun setFolded(value: Boolean) {
        chainButtons().forEach { it.setFolded(value) }
    }

    internal val configHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val configSettled = Runnable {
        folding = false
        // Тарелка диска — раньше кнопок: порядок окон одного типа — порядок
        // добавления, а стеклу положено лежать под ними.
        disk?.setFolded(false)
        setFolded(false)
        chainButtons().forEach { it.onConfigurationChanged() }
        disk?.onConfigurationChanged()
        refreshHandles()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { autoPilot.stop() }
        ripenessHandler.removeCallbacks(ripenessCheck)
        ripenessHandler.removeCallbacks(digestRunnable)
        ripenessHandler.removeCallbacks(disarmCapture)
        ripenessHandler.removeCallbacks(trailingProbe)
        zReminderHandler.removeCallbacks(zReminderTick)
        chromeHandler.removeCallbacks(chromeTicker)
        lagHandler.removeCallbacks(lagTick)
        configHandler.removeCallbacks(configSettled)
        googleSession?.stop()
        googleSession = null
        zSession?.stop()
        zSession = null
        rSession?.stop()
        rSession = null
        eSession?.stop()
        eSession = null
        runCatching { stopMicHold() }
        keepScreenOn(false)
        floatingButton?.destroy()
        floatingButton = null
        zButton?.destroy()
        zButton = null
        rButton?.destroy()
        rButton = null
        restHandler.removeCallbacks(restTick)
        eButton?.destroy()
        eButton = null
        tailHandle?.hide()
        tailHandle = null
        stackSettings?.destroy()
        stackSettings = null
        disk?.destroy()
        disk = null
        scope.cancel()
        super.onDestroy()
    }
}
