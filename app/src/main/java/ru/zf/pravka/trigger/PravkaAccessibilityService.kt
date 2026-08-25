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
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
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
        private const val CONVO_GAP_MS = 10L * 60 * 1000

        // Internal bookkeeping prefs, read by the Learning tab too.
        const val PREFS_INTERNAL = "pravka_internal"
        const val KEY_LAST_LEARN_BATCH = "last_learn_batch"
        const val KEY_LAST_RULES_OPT = "last_rules_opt"

        // Засечка reminder anti-spam: one morning/evening nudge per day, one
        // gap nudge per distinct gap.
        private const val KEY_Z_MORNING_DAY = "z_morning_day"
        private const val KEY_Z_EVENING_DAY = "z_evening_day"
        private const val KEY_Z_GAP_NOTIFIED = "z_gap_notified_end"
        private const val KEY_Z_BEAT_AT = "z_beat_at"
        private const val KEY_Z_ASK_AT = "z_ask_at"
        private const val KEY_Z_ASK_ID = "z_ask_entry"

        // Pomodoro survives a service restart: the deadline is on disk.
        private const val KEY_Z_POMO_ENDS = "z_pomo_ends"
        private const val KEY_Z_POMO_BREAK = "z_pomo_break"
        private const val KEY_Z_POMO_DAY_PREFIX = "z_pomo_n_"

        // Chrome flavors whose url bar the per-site watcher reads.

        // Windows that never host our text fields. Querying their node tree
        // is not just useless - during a fold/lock transition their process
        // is at its busiest, and a synchronous a11y query into it can hang
        // for the full accessibility timeout and freeze the transition (the
        // owner's 3-10s black screen on fold/unfold).
        const val RULES_OPT_PERIOD_MS = 7L * 24 * 3600 * 1000
        const val RULES_OPT_MIN_COUNT = 6
    }

    // An uncaught exception in any launched job used to kill the whole app
    // process SILENTLY (screen-off mid-take -> dead window -> node call threw ->
    // the take vanished with no journal line). Log it instead and stay alive.
    private val crashLogger = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        runCatching {
            app.eventLog.add("CRASH ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}")
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashLogger)
    private var floatingButton: FloatingButtonController? = null
    private var busy = false

    // Засечка (timesheet): its own button, its own capture session. A take
    // here never touches the focused field - the transcript goes to the
    // categorizer and the timesheet store instead.
    private var zButton: ZasechkaButtonController? = null
    private var zSession: GoogleSpeechSession? = null
    @Volatile private var zWhisperRecording = false
    // Tap on the live plate: kill the mic, type instead (confidential takes).
    @Volatile private var zTypeInstead = false
    @Volatile private var cachedZEnabled = true
    @Volatile private var cachedZGapMin = 45
    @Volatile private var cachedZCheckins = true
    @Volatile private var cachedZDayStart = 9
    @Volatile private var cachedZDayEnd = 23
    @Volatile private var zCategoriesCached: List<String> = emptyList()
    @Volatile private var zClientsCached: List<String> = emptyList()

    // Разноска: третья кнопка и свой захват. Наговор не касается ни поля, ни
    // ленты - он уезжает Опусу на разбор и оттуда делами в Todoist.
    private var rButton: RaznoskaButtonController? = null
    private var rSession: GoogleSpeechSession? = null
    @Volatile private var rWhisperRecording = false
    @Volatile private var rTypeInstead = false
    @Volatile private var cachedREnabled = true
    private var micRequestForRaznoska = false

    // Тело: четвёртая кнопка и свой захват. Одна на подходы, еду, зарядку и
    // вопросы - намерение определяет модель тем же вызовом, что и разбор. Ни
    // поля, ни ленты этот путь не касается (в ленту еда только ПРИПИСЫВАЕТСЯ,
    // и то из движка).
    private var eButton: BodyButtonController? = null
    private var eSession: GoogleSpeechSession? = null
    @Volatile private var eWhisperRecording = false
    @Volatile private var eTypeInstead = false
    @Volatile private var cachedEEnabled = true
    private var micRequestForFood = false
    // Отдых между подходами: дедлайн на диске не нужен - это минуты, а не
    // помидор, и переживать перезапуск службы ему незачем.
    @Volatile private var restUntil = 0L
    @Volatile private var cachedRestSec = 90

    // Weak cache of the last focused editable node and its text, updated on
    // TYPE_VIEW_FOCUSED / TYPE_VIEW_TEXT_CHANGED (spec 5.4: activities steal
    // focus, so triggers launched via Activity read from this cache).
    private var cachedFocus: WeakReference<AccessibilityNodeInfo>? = null
    // The field to receive dictated text, captured when recording starts -
    // the owner may switch apps while dictating, so we can't rely on focus
    // at stop time.
    private var dictationTarget: WeakReference<AccessibilityNodeInfo>? = null

    // Live streaming dictation session.
    private var googleSession: GoogleSpeechSession? = null
    private var googleStartedAt = 0L
    // Precomputed vocabulary bias and engine choice, so starting a take is
    // instant: no DataStore read and no dictionary load on the tap -> speak
    // path, which was clipping the first words.
    @Volatile private var cachedBiasing: List<String> = emptyList()
    @Volatile private var cachedEngine: String = Settings.SPEECH_GOOGLE
    // Recognition mode knobs (defaults = build 55), cached like the engine so
    // the tap -> listening path touches no storage.
    @Volatile private var cachedSegmented: Boolean = true
    @Volatile private var cachedFormatting: Boolean = false

    private val app: PravkaApp by lazy { application as PravkaApp }

    /** Автопилот Засечки: Wi-Fi-места, BT машины, «точно ещё …?». */
    val autoPilot by lazy { AutoPilot(this, app, scope) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { autoPilot.start() }
        instance = this
        // A fresh "connected" after takes were mid-flight = the process died
        // and the system rebound the service. Makes crashes visible in the log.
        app.eventLog.add("service connected")
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
            app.settings.convoContextFlow.collect { cachedConvoContext = it }
        }
        scope.launch {
            app.settings.learnPeriodHoursFlow.collect { cachedLearnPeriodH = it }
        }
        // Auto-capture off (the owner's default now) means the service does not
        // even SUBSCRIBE to text-change events: no event per keystroke in every
        // app, no event.source binder round trip on this main thread.
        scope.launch {
            app.settings.learnAutoFlow.collect {
                cachedLearnAuto = it
                applyEventSubscription(it)
            }
        }
        refreshLearnBadge()

        // Засечка: the second button, visible everywhere while enabled.
        zButton = ZasechkaButtonController(
            service = this,
            scope = scope,
            settings = app.settings,
            onShortTap = ::onZasechkaTap,
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

        // The linked chain (owner's design): drag any bubble and the others
        // trail behind on a rubber band, in order "П" - "З" - "Д" - "Т".
        val pairGap = (8 * resources.displayMetrics.density).toInt()
        floatingButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            zButton?.followTo(x, y + size + pairGap, dropped)
            rButton?.followTo(x, y + 2 * (size + pairGap), dropped)
            eButton?.followTo(x, y + 3 * (size + pairGap), dropped)
        }
        zButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            floatingButton?.followTo(x, y - size - pairGap, dropped)
            rButton?.followTo(x, y + size + pairGap, dropped)
            eButton?.followTo(x, y + 2 * (size + pairGap), dropped)
        }
        rButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            zButton?.followTo(x, y - size - pairGap, dropped)
            floatingButton?.followTo(x, y - 2 * (size + pairGap), dropped)
            eButton?.followTo(x, y + size + pairGap, dropped)
        }
        eButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            rButton?.followTo(x, y - size - pairGap, dropped)
            zButton?.followTo(x, y - 2 * (size + pairGap), dropped)
            floatingButton?.followTo(x, y - 3 * (size + pairGap), dropped)
        }
        floatingButton?.pairAnchor = anchor@{
            if (!cachedZEnabled) return@anchor null
            val (zx, zy) = zButton?.currentPosition() ?: return@anchor null
            val size = floatingButton?.buttonSizePx() ?: return@anchor null
            zx to (zy - size - pairGap)
        }
        // The "П" lives on screen permanently (owner: "пусть будет всегда") -
        // no field-following, no window watching. Without a focused field a
        // take still works: CLEAN runs and the result lands in the clipboard
        // plus a notification (the no-field path).
        floatingButton?.show()
        scope.launch {
            app.settings.zEnabledFlow.collect {
                cachedZEnabled = it
                zButton?.setEnabled(it)
            }
        }
        scope.launch {
            app.settings.rEnabledFlow.collect {
                cachedREnabled = it
                rButton?.setEnabled(it)
            }
        }
        scope.launch {
            app.settings.tEnabledFlow.collect {
                cachedEEnabled = it
                eButton?.setEnabled(it)
            }
        }
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
        restorePomodoro()
        lagExpectedAt = 0L
        lagHandler.removeCallbacks(lagTick)
        lagHandler.postDelayed(lagTick, 2_000)
    }

    // Main-thread lag sentinel. The fold black-screen class of bug is "the
    // service main thread was busy for seconds": each individual a11y event
    // stays fast, so the per-event watchdog is silent while the QUEUE lags
    // behind whatever hogged the thread. A timestamped no-op every 2s makes
    // the hog visible: it runs late, and the lag lands in the log.
    private val lagHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lagExpectedAt = 0L
    private val lagTick = object : Runnable {
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
                // Only the learning auto-capture needs these; without it the
                // whole branch (and its binder call for event.source) is dead.
                if (!cachedLearnAuto) return
                val source = event.source ?: return
                if (source.isEditable) {
                    cachedFocus = WeakReference(source)
                    // Learning auto-capture: the owner may be hand-editing a
                    // text we just delivered. Throttled: at most one field read
                    // per second, and only within the watch window of a take.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastWatchProbeAt > 1000 && now - lastDeliveryAt < ru.zf.pravka.data.EditWatchStore.WATCH_WINDOW_MS) {
                        lastWatchProbeAt = now
                        val pkg = runCatching { source.packageName?.toString() }.getOrNull()
                        val current = runCatching { source.effectiveText() }.getOrDefault("")
                        if (!pkg.isNullOrBlank() && current.isNotBlank()) {
                            scope.launch {
                                val firstEdit = app.editWatch.onFieldText(pkg, current, ::wordOverlap)
                                if (firstEdit) {
                                    app.learnLog.add(
                                        "правка замечена: поле в $pkg, ${current.length} зн. — созреет через " +
                                            "${ru.zf.pravka.data.EditWatchStore.RIPE_QUIET_MS / 60000} мин"
                                    )
                                    scheduleRipenessCheck()
                                }
                            }
                        }
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
    private val keyguardManager by lazy {
        getSystemService(android.app.KeyguardManager::class.java)
    }

    fun isLockedIdle(): Boolean {
        val locked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
        if (!locked) return false
        return googleSession == null && zSession == null && rSession == null &&
            eSession == null && !zWhisperRecording && !rWhisperRecording &&
            !eWhisperRecording && !DictationService.recording
    }

    /** Short tap: stop the active session if any, else start per the engine. */
    private fun onDictateTap() {
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

    private fun startForEngine() {
        if (!hasMicPermission()) { requestMicPermission(); return }
        // Whisper records to a file; Google is the live streaming engine.
        if (cachedEngine.startsWith("whisper")) {
            startRecordingNow()
        } else {
            startGoogleNow()
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Only an Activity can request a runtime permission; it calls back into
    // onMicPermissionGranted(), which re-dispatches by the current engine.
    private fun requestMicPermission() {
        startActivity(
            android.content.Intent(this, MicPermissionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // The permission dialog serves two buttons; remember whose tap asked for
    // it, or a Засечка tap would grant the mic and then start a Правка take.
    private var micRequestForZasechka = false

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

    // Names/terms/brands the recognizer should be biased toward - the owner's
    // dictionary (both protected forms and the correct sides of replacements).
    private suspend fun collectBiasing(): List<String> = runCatching {
        val words = LinkedHashSet<String>()
        for (e in app.dictionaryStore.all()) {
            e.from.takeIf { it.isNotBlank() }?.let { words.add(it) }
            e.to.takeIf { it.isNotBlank() }?.let { words.add(it) }
        }
        words.toList()
    }.getOrDefault(emptyList())

    fun startRecordingNow() {
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) } ?: cachedFocus
        floatingButton?.setRecording(true)
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
        lastDraftAt = 0L
        discardTake = false
        val session = GoogleSpeechSession(
            this,
            biasing = cachedBiasing,
            formatting = cachedFormatting,
            segmentedSession = cachedSegmented,
        )
        googleSession = session
        // Start listening FIRST, then dress the UI: the button, the ticker's
        // first layout, the haptic and the foreground-service notification are
        // all main-thread work, and any of it ahead of startListening() is time
        // the mic is not yet capturing.
        session.start(
            // A distinct tick the moment the recognizer is actually listening,
            // so the owner knows when to start and stops clipping first words.
            onReady = { Haptics.success(this) },
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
            onDone = { text -> onLiveDone(Settings.SPEECH_GOOGLE, text) },
            onError = { msg -> onLiveError(msg) },
            onLog = { line -> app.eventLog.add(line) },
        )
        // The tree walk to capture the target field is 1-3 binder IPCs into the
        // host app - deliberately AFTER startListening() is already issued, so
        // it can't clip the first word.
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) } ?: cachedFocus
        floatingButton?.setRecording(true)
        floatingButton?.showTicker()
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

    private var lastDraftAt = 0L
    private fun saveDraftThrottled(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDraftAt < 1200) return
        lastDraftAt = now
        app.liveDraft.save(text)
    }

    // ---- Conversation memory (owner's request): my recent takes in the SAME
    // app form a chain; a new dictation carries the chain as read-only context
    // so replies keep the thread's tone and referents. In-memory only.
    private data class ConvoEntry(val pkg: String, val at: Long, val text: String)
    private val convo = ArrayDeque<ConvoEntry>()
    @Volatile private var cachedConvoContext: Boolean = true
    @Volatile private var cachedLearnPeriodH: Int = 3
    @Volatile private var cachedLearnAuto: Boolean = false

    // The service's own event appetite, flipped with the auto-capture toggle:
    // with capture off the system stops delivering text-change events at all.
    private fun applyEventSubscription(autoCapture: Boolean) {
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
    @Volatile private var lastWatchProbeAt = 0L
    @Volatile private var lastDeliveryAt = 0L

    // The gray "отмена" bubble beside the ticker: throw the take away.
    @Volatile private var discardTake = false

    fun cancelLiveDictation() {
        if (googleSession == null) return
        discardTake = true
        app.eventLog.add("cancel requested")
        stopLiveDictation()
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
                    onZasechkaText(text)
                }.onFailure { e ->
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

    // Long press = "the text is already in the field": clean it with the
    // standard model, or run a one-tap rework on the stronger one.
    private fun showFabMenu() {
        val red = FloatingButtonController.REC_RED
        val orange = FloatingButtonController.ACCENT
        floatingButton?.toggleMenu(
            listOf(
                // Editing actions (red). A selection in the field narrows every
                // one of them to just that fragment (AccessibilityTarget keeps
                // an existing selection as the work item).
                listOf(
                    FloatingButtonController.MenuItem(getString(R.string.quick_clean), red) { runProofread(ProofreadMode.CLEAN) },
                    FloatingButtonController.MenuItem(getString(R.string.redo_shorter), red) { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_SHORTER) },
                    FloatingButtonController.MenuItem(getString(R.string.redo_longer), red) { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_LONGER) },
                    FloatingButtonController.MenuItem(getString(R.string.redo_polish), red) { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_POLISH) },
                    FloatingButtonController.MenuItem(getString(R.string.fab_menu_undo), red) { undoLast() },
                    FloatingButtonController.MenuItem("Обучить", red) { learnFromField() },
                    FloatingButtonController.MenuItem("Сброс", red) { resetStuck() },
                ),
                // AI actions (orange): work on the selection, else the whole
                // field, else the clipboard; the answer goes to the clipboard.
                listOf(
                    FloatingButtonController.MenuItem("Коротко", orange) { runAssist("summary", ru.zf.pravka.core.Prompts.ASSIST_SUMMARY) },
                    FloatingButtonController.MenuItem("Ответить", orange) { runAssist("reply", ru.zf.pravka.core.Prompts.ASSIST_REPLY) },
                    FloatingButtonController.MenuItem("Перевод", orange) { runAssist("translate", ru.zf.pravka.core.Prompts.ASSIST_TRANSLATE) },
                ),
            )
        )
    }

    // ---- Learning: the owner hand-edited our output; extract what to keep ----

    // Lazy daily batch: when ripe hand-edits accumulated and the last batch
    // was long ago, one Opus call digests them all into pending suggestions.
    // A detected edit also schedules a check for right after it ripens, so
    // the batch does not have to wait for the next dictation.
    @Volatile var learnBatchRunning = false
        private set
    private val ripenessCheck = Runnable { maybeRunLearnBatch() }
    // ONE handler instance: removeCallbacks matches by handler, so a fresh
    // Handler per call never actually debounced, and onDestroy could not
    // clear the pending posts (they pinned the dead service for 11 minutes).
    private val ripenessHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleRipenessCheck() {
        ripenessHandler.removeCallbacks(ripenessCheck)
        ripenessHandler.postDelayed(ripenessCheck, 11L * 60 * 1000)
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
                val ripe = app.editWatch.ripe(quietMs = if (force) 0L else ru.zf.pravka.data.EditWatchStore.RIPE_QUIET_MS)
                if (ripe.isEmpty()) {
                    if (force) {
                        val watched = app.editWatch.all()
                        app.learnLog.add(
                            "разбор вручную: зрелых правок нет (в наблюдении ${watched.size}, " +
                                "изменённых ${watched.count { it.editedTs > 0 }})"
                        )
                        Feedback.toast(this@PravkaAccessibilityService, "Разбирать нечего: изменённых текстов нет.")
                    }
                    return@launch
                }
                val cases = ripe.take(5).map { Triple(it.dictated, it.cleaned, it.lastSeen) }
                app.eventLog.add("learn batch: ${cases.size} edits")
                app.learnLog.add("батч-анализ: правок к разбору — ${cases.size}")
                if (force) Feedback.toast(this@PravkaAccessibilityService, "Разбираю правок: ${cases.size} (Опус)…")
                val result = app.claudeProvider.learnBatch(cases)
                result.onSuccess { proposals ->
                    internal.edit().putLong(KEY_LAST_LEARN_BATCH, System.currentTimeMillis()).apply()
                    app.editWatch.remove(ripe.take(5).map { it.id })
                    app.stats.recordAux(proposals.costUsd, proposals.tokensIn, proposals.tokensOut)
                    app.learnLog.add("батч-анализ стоил $" + "%.4f".format(java.util.Locale.US, proposals.costUsd))
                    val q = queueProposals(proposals)
                    app.eventLog.add(
                        "learn batch: dict=${proposals.dict.size} rules=${proposals.rules.size} " +
                            "auto+=${q.autoDict} pending+=${q.pendingRules}"
                    )
                    if (q.autoDict > 0 || q.pendingRules > 0) {
                        showLearnNotification(q)
                        refreshLearnBadge()
                    }
                    maybeAutoOptimizeRules(internal)
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

    /** 💡/⭐ over the button while suggestions await review; gone when done. */
    fun refreshLearnBadge() {
        scope.launch {
            val pending = app.learnStore.all()
            if (pending.isEmpty()) {
                floatingButton?.hideLearnBadge()
            } else {
                val emoji = if (pending.any { it.kind == "rule" }) "⭐" else "💡"
                floatingButton?.showLearnBadge(emoji) {
                    startActivity(
                        android.content.Intent(this@PravkaAccessibilityService, ru.zf.pravka.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    // Weekly housekeeping (owner's request): when the rule set has grown,
    // Opus consolidates it automatically - dubs merged, contradictions out,
    // no hard size cap. Runs after a successful learn batch, at
    // most once per RULES_OPT_PERIOD_MS; the result is applied directly and
    // logged (the manual button with its preview dialog stays available).
    private suspend fun maybeAutoOptimizeRules(internal: android.content.SharedPreferences) {
        val last = internal.getLong(KEY_LAST_RULES_OPT, 0L)
        if (System.currentTimeMillis() - last < RULES_OPT_PERIOD_MS) return
        val rules = app.rulesStore.all()
        if (rules.size < RULES_OPT_MIN_COUNT) return
        app.learnLog.add("автооптимизация набора правил (раз в неделю): ${rules.size} шт., запускаю Опус…")
        app.claudeProvider.optimizeRules(rules)
            .onSuccess { opt ->
                app.stats.recordAux(opt.costUsd, opt.tokensIn, opt.tokensOut)
                app.rulesStore.replaceAll(opt.rules.map { Triple(it.text, it.before, it.after) })
                internal.edit().putLong(KEY_LAST_RULES_OPT, System.currentTimeMillis()).apply()
                app.learnLog.add(
                    "АВТООПТИМИЗАЦИЯ: набор заменён, ${rules.size} → ${opt.rules.size}, " +
                        "стоила $" + "%.4f".format(java.util.Locale.US, opt.costUsd)
                )
            }
            .onFailure { e ->
                app.stats.recordError()
                app.learnLog.add("автооптимизация НЕ УДАЛАСЬ: ${e.message} (набор не тронут)")
            }
    }

    data class QueueResult(val autoDict: Int, val pendingRules: Int)

    /**
     * Owner's split (2026-08-20): dictionary findings ("Поли" -> "Полли",
     * "фор раннер" -> "Forerunner") are mechanical - they go STRAIGHT into
     * the dictionary, marked "авто" so they're easy to review or delete.
     * RULES are judgment calls - they stay pending until approved. A rule
     * proposal matching an EXISTING rule counts as a confirmation (its ×N
     * grows) instead of being silently dropped.
     */
    private suspend fun queueProposals(proposals: ru.zf.pravka.provider.ClaudeProvider.LearnProposals): QueueResult {
        val known = app.dictionaryStore.all().map { it.from.lowercase() }.toHashSet()
        var autoDict = 0
        proposals.dict
            .filter { it.from.isNotBlank() && it.from.lowercase() !in known }
            .forEach { d ->
                val mode = if (d.mode == "PROTECT") ru.zf.pravka.core.DictMode.PROTECT
                    else ru.zf.pravka.core.DictMode.HARD
                val note = listOf(d.note.trim(), "авто-обучение").filter { it.isNotBlank() }.joinToString(" · ")
                app.dictionaryStore.add(d.from, d.to, mode, note)
                autoDict++
                app.learnLog.add("В СЛОВАРЬ автоматически: ${d.from} → ${d.to} [${d.mode}]")
            }
        // New words must bias the recognizer too, same as a manual add.
        if (autoDict > 0) cachedBiasing = collectBiasing()
        // Duplicates of EXISTING rules are prevented at the source now: the
        // learn prompt carries the current rule set with a "don't re-propose"
        // instruction (the old confirm-counter never worked usefully).
        val fresh = mutableListOf<ru.zf.pravka.data.LearnStore.Suggestion>()
        for (r in proposals.rules) {
            fresh.add(
                ru.zf.pravka.data.LearnStore.Suggestion(
                    id = 0, kind = "rule", text = r.text,
                    exampleBefore = r.before, exampleAfter = r.after,
                )
            )
            app.learnLog.add("предложение (правило): ${r.text}")
        }
        return QueueResult(autoDict, app.learnStore.add(fresh))
    }

    /** One human sentence out of a learn round's outcome. */
    private fun learnSummary(q: QueueResult): String {
        val parts = mutableListOf<String>()
        if (q.autoDict > 0) parts.add("в словарь добавлено: ${q.autoDict}")
        if (q.pendingRules > 0) parts.add("правил на одобрение: ${q.pendingRules} (раздел «Обучение»)")
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun showLearnNotification(q: QueueResult) {
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
                .setContentText(learnSummary(q))
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
            )
            busy = false
            floatingButton?.setBusy(false)
            result.onSuccess { proposals ->
                app.stats.recordAux(proposals.costUsd, proposals.tokensIn, proposals.tokensOut)
                app.learnLog.add("разбор стоил $" + "%.4f".format(java.util.Locale.US, proposals.costUsd))
                val q = queueProposals(proposals)
                app.eventLog.add(
                    "learn: dict=${proposals.dict.size} rules=${proposals.rules.size} " +
                        "auto+=${q.autoDict} pending+=${q.pendingRules}"
                )
                if (q.pendingRules > 0) refreshLearnBadge()
                // This edit is analyzed - close its auto-watch so the batch
                // doesn't re-analyze the same text later.
                val closed = app.editWatch.all()
                    .filter { wordOverlap(it.lastSeen, current) > 0.5 }
                    .map { it.id }
                if (closed.isNotEmpty()) {
                    app.editWatch.remove(closed)
                    app.learnLog.add("наблюдение закрыто: разобрано вручную (${closed.size})")
                }
                if (q.autoDict == 0 && q.pendingRules == 0) {
                    Feedback.toast(this@PravkaAccessibilityService, "Ничего системного в правках не нашлось.")
                } else {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, learnSummary(q))
                }
            }.onFailure { e ->
                app.stats.recordError()
                app.eventLog.add("learn failed: ${e.message}")
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, e.message ?: "Ошибка обучения")
            }
        }
    }

    /** Jaccard word overlap in [0..1] - enough to match an edited output. */
    private fun wordOverlap(a: String, b: String): Double {
        val wa = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val wb = b.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size
    }

    // ---- AI actions: selection > field > clipboard; result to the clipboard ----

    private suspend fun assistContent(): String {
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
                app.stats.recordAux(r.costUsd, r.inputTokens, r.outputTokens)
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
    private var activeJob: kotlinx.coroutines.Job? = null

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
            // Opus thinks before it writes: no text deltas for several seconds.
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
                    modelOverride = if (strongModel) Settings.MODEL_OPUS else null,
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
                    "${if (strongModel) "(opus)" else ""}: ${outcome.javaClass.simpleName}"
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
                }
            }
            maybeRunLearnBatch()
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
    fun onZasechkaTap() {
        if (isLockedIdle()) return
        if (zSession != null) { stopZasechkaLive(); return }
        if (zWhisperRecording && DictationService.recording) {
            zButton?.setBusy(true)
            stopDictation()  // -> onRecordingSaved, routed by the flag
            return
        }
        // One microphone: while a Правка, Разноска or Еда take runs, the "З"
        // tap only nags.
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
        if (googleSession != null || DictationService.recording) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.z_busy_zasechka))
            return
        }
        if (!hasMicPermission()) {
            micRequestForZasechka = true
            requestMicPermission()
            return
        }
        startZasechkaCapture()
    }

    private fun startZasechkaCapture() {
        zButton?.hideInput()
        zButton?.hideAsk()
        if (cachedEngine.startsWith("whisper")) {
            zWhisperRecording = true
            zButton?.setRecording(true)
            // Whisper has no live words - the plate still shows, because it
            // is also the "type instead" tap target (confidential takes).
            zButton?.showTicker()
            zButton?.updateTicker("🎙 говори… (тап сюда — набрать текстом)")
            Haptics.start(this)
            startDictation()
        } else {
            startZasechkaGoogle()
        }
        // The categorizer call comes right after stop - skip its handshake.
        app.warmClaudeConnection()
    }

    private fun startZasechkaGoogle() {
        if (zSession != null) return
        if (!GoogleSpeechSession.isAvailable(this)) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.google_unavailable))
            return
        }
        val session = GoogleSpeechSession(
            this,
            // Client and category names are exactly the words these takes are
            // full of - bias the recognizer toward them, same as the dictionary.
            biasing = (cachedBiasing + zClientsCached + zCategoriesCached).distinct(),
            formatting = cachedFormatting,
            segmentedSession = cachedSegmented,
        )
        zSession = session
        session.start(
            onReady = { Haptics.success(this) },
            onPartial = { live -> zButton?.updateTicker(live) },
            // No recovery draft here: a lost 5-second take is re-spoken in
            // seconds, unlike a lost dictation paragraph.
            onCheckpoint = { },
            onDone = { text -> onZasechkaLiveDone(text) },
            onError = { msg -> onZasechkaLiveError(msg) },
            onLog = { line -> app.eventLog.add("засечка: $line") },
        )
        zButton?.setRecording(true)
        zButton?.showTicker()
        zButton?.updateTicker("🎙 говори… (тап сюда — набрать текстом)")
        Haptics.start(this)
        runCatching { startMicHold() }
    }

    private fun stopZasechkaLive() {
        val session = zSession ?: return
        zButton?.setRecording(false)
        zButton?.setBusy(true)
        session.stop()  // -> onZasechkaLiveDone
    }

    /**
     * Tap on the live plate (owner's request): the dictation stops and the
     * plate becomes a text box - some takes are typed, not said out loud.
     * Whatever the recognizer already heard prefills the box (Google live);
     * a Whisper take is discarded unheard - its audio never gets transcribed.
     */
    private fun onZasechkaPlateTap() {
        when {
            zSession != null -> {
                zTypeInstead = true
                stopZasechkaLive()   // -> onZasechkaLiveDone routes to the box
            }
            zWhisperRecording && DictationService.recording -> {
                zTypeInstead = true
                zButton?.setBusy(true)
                stopDictation()      // -> onRecordingSaved routes to the box
            }
        }
    }

    // Confidential type-in: same engine, source "text" - the ribbon and the
    // toasts behave exactly like after a voice take.
    private fun openZasechkaTypeIn(prefill: String) {
        zButton?.showInput(prefill) { typed ->
            val text = typed.trim()
            if (text.isNotEmpty()) onZasechkaText(text, source = "text")
        }
    }

    /** The mic-hold notification's Stop: finalize whichever live take runs. */
    fun stopAnyLive() {
        when {
            zSession != null -> stopZasechkaLive()
            rSession != null -> stopRaznoskaLive()
            else -> stopLiveDictation()
        }
    }

    private fun onZasechkaLiveDone(text: String) {
        zSession = null
        runCatching { stopMicHold() }
        runCatching { zButton?.hideTicker() }
        zButton?.setRecording(false)
        if (zTypeInstead) {
            zTypeInstead = false
            zButton?.setBusy(false)
            openZasechkaTypeIn(text.trim())
            return
        }
        onZasechkaText(text)
    }

    private fun onZasechkaLiveError(msg: String) {
        zSession = null
        runCatching { stopMicHold() }
        zButton?.hideTicker()
        zButton?.setRecording(false)
        zButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, msg)
    }

    /** Transcript in hand (either engine, or typed): categorize, store, confirm. */
    private fun onZasechkaText(raw: String, source: String = "voice") {
        val text = raw.trim()
        if (text.isBlank()) {
            zButton?.setBusy(false)
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
            return
        }
        if (!scope.isActive) return
        zButton?.setBusy(true)
        scope.launch {
            val outcome = runCatching { app.zasechkaEngine.record(text, source) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.eventLog.add("засечка: record threw ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            zButton?.setBusy(false)
            if (outcome == null) {
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, getString(R.string.z_record_failed))
                return@launch
            }
            zButton?.setRemind(false)
            val entry = outcome.entry
            when {
                outcome.action == "none" -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "🤷 Не записал: ${outcome.say.ifBlank { "это не про ленту" }}",
                    )
                }
                outcome.action == "stop" -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "⏹ «${entry.title}» закрыто, ${entry.durationMin()} мин",
                    )
                }
                outcome.action == "insert" && outcome.error == null -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "⤵ Вставлено: «${entry.title}» ${zTime(entry.start)}–${zTime(entry.end)}" +
                            " — обрамляющее дело продолжено",
                    )
                }
                outcome.action == "edit" -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "✏️ «${outcome.previousTitle}» → «${entry.title}» [${entry.category}]",
                    )
                }
                outcome.action == "delete" -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, "🗑 «${entry.title}» удалена")
                }
                outcome.categorized -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    val tail = listOf(entry.category, entry.client)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "⏱ ${entry.title}" + (if (tail.isBlank()) "" else " — $tail") +
                            " (с ${zTime(entry.start)})",
                    )
                }
                else -> {
                    // Saved raw: quieter success, the owner sorts it in the tab.
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        getString(R.string.z_saved_raw, outcome.error ?: ""),
                    )
                }
            }
        }
    }

    // ---- Chrome per-site time: REMOVED (owner's call) ----
    //
    // The omnibox poller queried Chrome's a11y tree every 7s while Chrome was
    // foregrounded. Even off the main thread, those are synchronous binder
    // calls into another process - fold Chrome mid-transition and the query
    // lands in a freezing app while the system's per-service interaction
    // queue waits behind it. The owner's fold black-screens correlated with
    // it, so the whole feature is out; per-app minutes from UsageStats stay.

    // ---- Помидоры: the "З" button doubles as a pomodoro timer ----

    private var pomodoroEndsAt = 0L
    private var pomodoroIsBreak = false
    private val pomodoroHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pomodoroTicker = object : Runnable {
        override fun run() {
            tickPomodoro()
            if (pomodoroEndsAt > 0) pomodoroHandler.postDelayed(this, 15_000)
        }
    }

    private fun showZasechkaMenu() {
        val goTab: () -> Unit = {
            startActivity(
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_ZASECHKA)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val openTab = ZasechkaButtonController.MenuItem("Открыть Засечку", goTab)
        // The top pill answers "что сейчас считается?" without opening the
        // app: current дело and since when (owner's request). Tap -> the tab.
        scope.launch {
            val now = System.currentTimeMillis()
            val open = runCatching { app.zasechkaStore.openEntry() }.getOrNull()
            val header = ZasechkaButtonController.MenuItem(
                if (open != null) {
                    "⏱ ${open.title.ifBlank { "без названия" }} — с ${zTime(open.start)}, ${zDur(now - open.start)}"
                } else "— сейчас ничего не идёт",
                goTab,
            )
            val undoLabel = app.zasechkaStore.undoFlow.value
            val undoItem = undoLabel?.let { label ->
                ZasechkaButtonController.MenuItem("↩︎ Отменить $label") {
                    scope.launch {
                        val undone = runCatching { app.zasechkaStore.undoLast() }.getOrNull()
                        app.zasechkaSync.kickSoon(scope)
                        Feedback.toast(
                            this@PravkaAccessibilityService,
                            if (undone != null) "↩︎ Отменено: $undone" else "Отменять нечего",
                        )
                    }
                }
            }
            val items = if (pomodoroEndsAt > 0) {
                listOfNotNull(
                    header,
                    undoItem,
                    ZasechkaButtonController.MenuItem(
                        if (pomodoroIsBreak) "Стоп: перерыв" else "Стоп: помидор"
                    ) { stopPomodoro(byUser = true) },
                    openTab,
                )
            } else {
                listOfNotNull(
                    header,
                    undoItem,
                    ZasechkaButtonController.MenuItem("🍅 25 минут") { startPomodoro(25, isBreak = false) },
                    ZasechkaButtonController.MenuItem("🍅 50 минут") { startPomodoro(50, isBreak = false) },
                    ZasechkaButtonController.MenuItem("Перерыв 5") { startPomodoro(5, isBreak = true) },
                    openTab,
                )
            }
            zButton?.showMenu(items)
        }
    }

    fun startPomodoro(minutes: Int, isBreak: Boolean) {
        pomodoroEndsAt = System.currentTimeMillis() + minutes * 60_000L
        pomodoroIsBreak = isBreak
        getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE).edit()
            .putLong(KEY_Z_POMO_ENDS, pomodoroEndsAt)
            .putBoolean(KEY_Z_POMO_BREAK, isBreak)
            .apply()
        Haptics.start(this)
        Feedback.toast(this, if (isBreak) "Перерыв $minutes мин" else "🍅 $minutes мин — поехали")
        pomodoroHandler.removeCallbacks(pomodoroTicker)
        pomodoroTicker.run()
    }

    fun stopPomodoro(byUser: Boolean) {
        clearPomodoro()
        if (byUser) Feedback.toast(this, "Таймер остановлен")
    }

    private fun clearPomodoro() {
        pomodoroEndsAt = 0
        pomodoroHandler.removeCallbacks(pomodoroTicker)
        getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE).edit()
            .remove(KEY_Z_POMO_ENDS).remove(KEY_Z_POMO_BREAK).apply()
        zButton?.setPomodoro(null, null)
    }

    private fun tickPomodoro() {
        if (pomodoroEndsAt <= 0) return
        val left = pomodoroEndsAt - System.currentTimeMillis()
        if (left <= 0) {
            completePomodoro()
            return
        }
        val minutesLeft = (left + 59_999) / 60_000
        zButton?.setPomodoro(
            minutesLeft.toString(),
            if (pomodoroIsBreak) ZasechkaButtonController.POMO_BREAK
            else ZasechkaButtonController.POMO_FOCUS,
        )
    }

    private fun completePomodoro() {
        val wasBreak = pomodoroIsBreak
        clearPomodoro()
        Haptics.success(this)
        if (wasBreak) {
            zPomodoroNotify("Перерыв кончился", "Ещё помидор?")
            return
        }
        scope.launch {
            val open = app.zasechkaStore.openEntry()
            if (open != null) app.zasechkaStore.incrementPomodoro(open.id)
            val internal = getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE)
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))
            val n = internal.getInt(KEY_Z_POMO_DAY_PREFIX + dayKey, 0) + 1
            internal.edit().putInt(KEY_Z_POMO_DAY_PREFIX + dayKey, n).apply()
            app.eventLog.add("помидор №$n готов" + (open?.let { " («${it.title}»)" } ?: ""))
            zPomodoroNotify(
                "Помидор №$n готов 🍅",
                open?.let { "«${it.title}» — сделано. Перерыв?" } ?: "Перерыв?",
            )
        }
    }

    /**
     * The deadline lives on disk, so a running pomodoro rides through app
     * updates and service restarts: still ticking -> resume the countdown;
     * finished while we were dead -> credit it (entry + day counter) and,
     * if the finish was recent, still fire the "готов" notification - the
     * owner should not lose a pomodoro to an APK install.
     */
    private fun restorePomodoro() {
        val internal = getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE)
        val ends = internal.getLong(KEY_Z_POMO_ENDS, 0L)
        if (ends <= 0) return
        pomodoroIsBreak = internal.getBoolean(KEY_Z_POMO_BREAK, false)
        val now = System.currentTimeMillis()
        if (ends > now) {
            pomodoroEndsAt = ends
            pomodoroHandler.removeCallbacks(pomodoroTicker)
            pomodoroTicker.run()
        } else {
            val wasBreak = pomodoroIsBreak
            val endedAgo = now - ends
            clearPomodoro()
            if (wasBreak) {
                if (endedAgo < 10 * 60_000L) zPomodoroNotify("Перерыв кончился", "Ещё помидор?")
                return
            }
            scope.launch {
                // The entry that was running when the bell should have rung.
                if (endedAgo < 30 * 60_000L) {
                    app.zasechkaStore.openEntry()?.let { app.zasechkaStore.incrementPomodoro(it.id) }
                }
                val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(java.util.Date(ends))
                val n = internal.getInt(KEY_Z_POMO_DAY_PREFIX + dayKey, 0) + 1
                internal.edit().putInt(KEY_Z_POMO_DAY_PREFIX + dayKey, n).apply()
                app.eventLog.add("помидор №$n дозасчитан после перезапуска")
                if (endedAgo < 10 * 60_000L) {
                    zPomodoroNotify("Помидор №$n готов 🍅", "Досчитал за время обновления. Перерыв?")
                }
            }
        }
    }

    /**
     * «Пробежка приехала: 5,2 км, пульс 152 против потолка 150» — как только
     * выгрузка увидела новую тренировку с часов. Кнопки 2/3/4 пишут feel прямо
     * в активность intervals; крайние 1 и 5 редки, за ними — во вкладку.
     */
    private suspend fun notifyArrivedWorkouts() {
        val arrived = app.icuSportSync.takeArrived()
        if (arrived.isEmpty()) return
        if (!runCatching { app.settings.sportNotify() }.getOrDefault(true)) return
        val rules = app.planStore.rulesFlow.value
        for (w in arrived.take(2)) {
            val name = ru.zf.pravka.core.SportCoach.sportName(w.type)
            val bits = mutableListOf<String>()
            if (w.km >= 0.1) bits.add(String.format(java.util.Locale.US, "%.1f км", w.km))
            if (w.minutes > 0) bits.add("${w.minutes} мин")
            if (w.avgHr > 0) bits.add("пульс ${w.avgHr}")
            val verdict = when {
                !w.type.equals("Run", true) || w.avgHr <= 0 || rules.runHrCeiling <= 0 -> ""
                w.avgHr <= rules.runHrCeiling -> "Под потолком ${rules.runHrCeiling} ✓."
                rules.greyZoneLow in 1..w.avgHr && w.avgHr <= rules.greyZoneHigh ->
                    "Серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh} — твоё же правило."
                else -> "Выше потолка ${rules.runHrCeiling}."
            }
            sportNotify(
                workoutId = w.id,
                title = "$name приехал${if (name.endsWith("а")) "а" else ""}: " + bits.joinToString(", "),
                text = (verdict + " Как самочувствие? 1 отлично … 5 развалина").trim(),
            )
        }
    }

    private fun sportNotify(workoutId: String, title: String, text: String) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-sport"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "Спорт: тренировка приехала",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            fun feelAction(feel: Int): android.app.Notification.Action {
                val intent = android.content.Intent(this, SportQuickActivity::class.java)
                    .putExtra(SportQuickActivity.EXTRA_ACTIVITY_ID, workoutId)
                    .putExtra(SportQuickActivity.EXTRA_FEEL, feel)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = android.app.PendingIntent.getActivity(
                    this, 60 + feel + workoutId.hashCode() % 1000,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val label = when (feel) {
                    2 -> "2 · хорошо"
                    3 -> "3 · норм"
                    else -> "4 · тяжело"
                }
                return android.app.Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, label, pending,
                ).build()
            }
            val open = android.app.PendingIntent.getActivity(
                this, 59,
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_SPORT)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = android.app.Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .addAction(feelAction(2))
                .addAction(feelAction(3))
                .addAction(feelAction(4))
                .setAutoCancel(true)
                .build()
            // Id от тренировки: две приехавшие не съедают друг друга.
            nm.notify(4600 + kotlin.math.abs(workoutId.hashCode() % 100), notif)
        }
    }

    private fun zPomodoroNotify(title: String, text: String) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-zasechka"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, getString(R.string.z_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            fun quick(what: String, code: Int): android.app.PendingIntent =
                android.app.PendingIntent.getActivity(
                    this, code,
                    android.content.Intent(this, ZasechkaQuickActivity::class.java)
                        .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, what)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notif = android.app.Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tile)
                .addAction(
                    android.app.Notification.Action.Builder(
                        null as android.graphics.drawable.Icon?, "Перерыв 5",
                        quick(ZasechkaQuickActivity.W_BREAK5, 7),
                    ).build()
                )
                .addAction(
                    android.app.Notification.Action.Builder(
                        null as android.graphics.drawable.Icon?, "🍅 25",
                        quick(ZasechkaQuickActivity.W_POMO25, 8),
                    ).build()
                )
                .setAutoCancel(true)
                .build()
            nm.notify(45, notif)
        }
    }

    private fun zTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun zDur(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} ч ${min % 60} м" else "$min м"
    }

    // ---- Засечка reminders: the button itself nags about the gaps ----

    private val zReminderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val zReminderTick = object : Runnable {
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
            zasechkaReminderCheck()
            zReminderHandler.postDelayed(this, 5 * 60_000L)
        }
    }

    private fun zasechkaReminderCheck() {
        val gapMin = cachedZGapMin
        scope.launch {
            // Reminders die with the toggle or with a zero interval.
            if (!cachedZEnabled || gapMin <= 0) {
                zButton?.setRemind(false)
                return@launch
            }
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            // Running LOSSES are not "busy": the amber pulse keeps nagging,
            // the evening nudge and the hourly wink stay for real дела only.
            val open = app.zasechkaStore.openEntry()?.takeIf { it.source != "gap" }
            val internal = getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE)
            val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date(now))

            // Outside the active day the button never pulses; the one evening
            // nudge asks to close a still-running entry.
            if (hour >= cachedZDayEnd || hour < cachedZDayStart) {
                zButton?.setRemind(false)
                if (open != null && hour >= cachedZDayEnd &&
                    internal.getString(KEY_Z_EVENING_DAY, "") != todayKey
                ) {
                    internal.edit().putString(KEY_Z_EVENING_DAY, todayKey).apply()
                    // The evening nudge doubles as the day's phone summary.
                    val phoneDay = app.phoneStore.daysFlow.value[ru.zf.pravka.data.phoneDayKey(now)]
                    val phoneLine = phoneDay?.let {
                        "\nЭкран: ${zDur(it.screenMs)} · подъёмов ${it.pickups} · отвлечений ${it.glances}"
                    }.orEmpty()
                    zNotify(
                        getString(R.string.z_notify_evening_title),
                        getString(R.string.z_notify_evening_text, open.title) + phoneLine,
                    )
                }
                return@launch
            }
            if (open != null) {
                zButton?.setRemind(false)
                checkInOnOpenEntry(open, now, internal)
                // Hourly heartbeat (owner's request): the button winks once an
                // hour and says out loud what is being counted right now -
                // trust in the robot comes from glanceability, not silence.
                // A freshly started дело (<10 мин) doesn't need it: he just
                // dictated it himself.
                if (now - internal.getLong(KEY_Z_BEAT_AT, 0L) >= 60 * 60_000L) {
                    internal.edit().putLong(KEY_Z_BEAT_AT, now).apply()
                    if (now - open.start >= 10 * 60_000L) {
                        zButton?.blinkOnce()
                        Feedback.toast(
                            this@PravkaAccessibilityService,
                            "⏱ «${open.title.ifBlank { "без названия" }}» — идёт ${zDur(now - open.start)} (с ${zTime(open.start)})",
                        )
                    }
                }
                return@launch
            }
            val todays = app.zasechkaStore.forRange(ru.zf.pravka.data.dayStartMs(now), now)
            if (todays.isEmpty()) {
                zButton?.setRemind(true)
                if (internal.getString(KEY_Z_MORNING_DAY, "") != todayKey) {
                    internal.edit().putString(KEY_Z_MORNING_DAY, todayKey).apply()
                    zNotify(
                        getString(R.string.z_notify_morning_title),
                        getString(R.string.z_notify_morning_text),
                    )
                }
                return@launch
            }
            val lastEnd = todays.maxOf { it.end }
            val gapMs = now - lastEnd
            if (gapMs >= gapMin * 60_000L) {
                zButton?.setRemind(true)
                // One notification per distinct gap; the pulse keeps nagging.
                if (internal.getLong(KEY_Z_GAP_NOTIFIED, 0L) != lastEnd) {
                    internal.edit().putLong(KEY_Z_GAP_NOTIFIED, lastEnd).apply()
                    zNotify(
                        getString(R.string.z_notify_gap_title, zTime(lastEnd)),
                        getString(R.string.z_notify_gap_text, gapMs / 60_000L),
                    )
                }
            } else {
                zButton?.setRemind(false)
            }
        }
    }

    /**
     * «Всё ещё «Обед»?» - every category carries the owner's typical length
     * for it; when the running дело outlives that, the button winks and asks.
     * «Да» resets the clock (ask again after another base period), «Нет»
     * starts a new take on the spot. Never on a locked screen (the bubble
     * would sit unseen above the keyguard) and never for the auto-filled
     * «Потери» - losses are not a дело to confirm.
     */
    private suspend fun checkInOnOpenEntry(
        open: ru.zf.pravka.data.ZasechkaStore.Entry,
        now: Long,
        prefs: android.content.SharedPreferences,
    ) {
        if (!cachedZCheckins || open.source == "gap") return
        if (googleSession != null || zSession != null || DictationService.recording) return
        if (runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)) return
        val baseMin = app.zasechkaStore.categories()
            .firstOrNull { it.name.equals(open.category, ignoreCase = true) }
            ?.baseMin ?: 0
        if (baseMin <= 0) return
        val baseMs = baseMin * 60_000L
        // The clock runs from the last answer for THIS entry, or from its start.
        val since = if (prefs.getLong(KEY_Z_ASK_ID, 0L) == open.id) {
            prefs.getLong(KEY_Z_ASK_AT, open.start)
        } else open.start
        if (now - since < baseMs) return
        prefs.edit()
            .putLong(KEY_Z_ASK_ID, open.id)
            .putLong(KEY_Z_ASK_AT, now)
            .apply()
        val name = open.title.ifBlank { open.category.ifBlank { "дело" } }
        Haptics.start(this)
        zButton?.showAsk(
            question = "Всё ещё «$name»? Идёт ${zDur(now - open.start)}",
            onYes = {
                getSharedPreferences(PREFS_INTERNAL, MODE_PRIVATE).edit()
                    .putLong(KEY_Z_ASK_ID, open.id)
                    .putLong(KEY_Z_ASK_AT, System.currentTimeMillis())
                    .apply()
                Feedback.toast(this, "Ок, считаем дальше")
            },
            // Straight into a new take: the mic starts, and a tap on the live
            // plate switches to typing if the answer is private.
            onNo = { onZasechkaTap() },
        )
        app.eventLog.add("засечка: спросил «всё ещё $name?» (база $baseMin мин)")
    }

    private fun zNotify(title: String, text: String) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-zasechka"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, getString(R.string.z_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val open = android.app.PendingIntent.getActivity(
                this, 5,
                android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_ZASECHKA)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val record = android.app.PendingIntent.getActivity(
                this, 6,
                android.content.Intent(this, ZasechkaQuickActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = android.app.Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                // Evening summaries run to several lines.
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .addAction(
                    android.app.Notification.Action.Builder(
                        null as android.graphics.drawable.Icon?,
                        getString(R.string.z_record_action),
                        record,
                    ).build()
                )
                .setAutoCancel(true)
                .build()
            nm.notify(44, notif)
        }
    }

    // ---- Разноска: тап -> наговор -> дела в Todoist (ни поля, ни ленты) ----

    /** Кнопка «Д» (и вкладка «Дела»): старт наговора, второй тап — разбор. */
    fun onRaznoskaTap() {
        if (isLockedIdle()) return
        if (rSession != null) { stopRaznoskaLive(); return }
        if (rWhisperRecording && DictationService.recording) {
            rButton?.setBusy(true)
            stopDictation()  // -> onRecordingSaved, routed by the flag
            return
        }
        // Один микрофон на все три кнопки: чужую запись эта не перехватывает.
        if (googleSession != null || zSession != null || zWhisperRecording ||
            eSession != null || eWhisperRecording || DictationService.recording
        ) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.r_busy))
            return
        }
        if (!hasMicPermission()) {
            micRequestForRaznoska = true
            requestMicPermission()
            return
        }
        startRaznoskaCapture()
    }

    private fun startRaznoskaCapture() {
        rButton?.hideInput()
        rButton?.hidePlate()
        if (cachedEngine.startsWith("whisper")) {
            rWhisperRecording = true
            rButton?.setRecording(true)
            // У Whisper живых слов нет, но плашка нужна: это ещё и цель тапа
            // «набрать текстом».
            rButton?.showTicker()
            rButton?.updateTicker("🎙 наговори дела… (тап сюда — набрать текстом)")
            Haptics.start(this)
            startDictation()
        } else {
            startRaznoskaGoogle()
        }
        // Пока владелец говорит: греем сокет к API и подтягиваем каталог
        // проектов, чтобы к моменту «стоп» всё было под рукой.
        app.warmClaudeConnection()
        scope.launch { runCatching { app.raznoskaEngine.warmCatalog() } }
    }

    // Имена проектов и меток Todoist: распознаватель должен слышать «Стеллар»
    // и «Мармакс», а не «стеллаж» и «Марк Макс».
    private fun raznBiasing(): List<String> = runCatching {
        app.todoistStore.projectsFlow.value.map { it.name } + app.todoistStore.labelsFlow.value
    }.getOrDefault(emptyList())

    private fun startRaznoskaGoogle() {
        if (rSession != null) return
        if (!GoogleSpeechSession.isAvailable(this)) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.google_unavailable))
            return
        }
        val session = GoogleSpeechSession(
            this,
            biasing = (cachedBiasing + zClientsCached + raznBiasing()).distinct(),
            formatting = cachedFormatting,
            segmentedSession = cachedSegmented,
        )
        rSession = session
        session.start(
            onReady = { Haptics.success(this) },
            onPartial = { live -> rButton?.updateTicker(live) },
            // Наговор длиннее засечки, но короче диктовки главы: черновик на
            // диск не пишем, повторить его дешевле, чем чинить.
            onCheckpoint = { },
            onDone = { text -> onRaznoskaLiveDone(text) },
            onError = { msg -> onRaznoskaLiveError(msg) },
            onLog = { line -> app.eventLog.add("разноска: $line") },
        )
        rButton?.setRecording(true)
        rButton?.showTicker()
        rButton?.updateTicker("🎙 наговори дела… (тап сюда — набрать текстом)")
        Haptics.start(this)
        runCatching { startMicHold() }
    }

    private fun stopRaznoskaLive() {
        val session = rSession ?: return
        rButton?.setRecording(false)
        rButton?.setBusy(true)
        session.stop()  // -> onRaznoskaLiveDone
    }

    /** Тап по живой плашке: микрофон замолчал, дальше набираем текстом. */
    private fun onRaznoskaTickerTap() {
        when {
            rSession != null -> {
                rTypeInstead = true
                stopRaznoskaLive()
            }
            rWhisperRecording && DictationService.recording -> {
                rTypeInstead = true
                rButton?.setBusy(true)
                stopDictation()
            }
        }
    }

    private fun openRaznoskaTypeIn(prefill: String) {
        rButton?.showInput(prefill = prefill, hint = "Дела текстом") { typed ->
            val text = typed.trim()
            if (text.isNotEmpty()) onRaznoskaText(text)
        }
    }

    private fun onRaznoskaLiveDone(text: String) {
        rSession = null
        runCatching { stopMicHold() }
        runCatching { rButton?.hideTicker() }
        rButton?.setRecording(false)
        if (rTypeInstead) {
            rTypeInstead = false
            rButton?.setBusy(false)
            openRaznoskaTypeIn(text.trim())
            return
        }
        onRaznoskaText(text)
    }

    private fun onRaznoskaLiveError(msg: String) {
        rSession = null
        runCatching { stopMicHold() }
        rButton?.hideTicker()
        rButton?.setRecording(false)
        rButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, msg)
    }

    /** Текст наговора в руках: Опус разбирает, плашка показывает результат. */
    private fun onRaznoskaText(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            rButton?.setBusy(false)
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
            return
        }
        if (!scope.isActive) return
        rButton?.setBusy(true)
        scope.launch {
            val result = runCatching { app.raznoskaEngine.split(text) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.eventLog.add("разноска: split бросил ${e.javaClass.simpleName}: ${e.message}")
                    Result.failure(e)
                }
            rButton?.setBusy(false)
            val draft = result.getOrElse { e ->
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(
                    this@PravkaAccessibilityService,
                    e.message ?: getString(R.string.r_split_failed),
                    long = true,
                )
                return@launch
            }
            Haptics.success(this@PravkaAccessibilityService)
            if (draft.tasks.isEmpty()) {
                // Дел не нашлось - наговор всё равно записан: заметки видно
                // во вкладке «Дела», ничего не пропало.
                Feedback.toast(
                    this@PravkaAccessibilityService,
                    if (draft.notes.isBlank()) "Дел в наговоре не нашлось"
                    else "Дел нет — записал в заметки",
                )
                return@launch
            }
            showRaznoskaPlate(draft.id)
        }
    }

    /**
     * Плашка разбора: кружок у дела - отметка (по умолчанию отмечено всё),
     * «✎» - правка формулировки на месте, тап по строке - дело целиком в
     * «Делах», «ОК» - добавить отмеченные. Ничего не уезжает до «ОК».
     */
    private fun showRaznoskaPlate(draftId: Long) {
        val draft = app.raznoskaStore.byId(draftId) ?: return
        val tasks = draft.live
        val waiting = tasks.count { !it.sent }
        if (waiting == 0) return
        val rows = tasks.map { task ->
            val meta = mutableListOf<String>()
            if (task.projectName.isNotBlank()) meta.add("#" + task.projectName)
            if (task.labels.isNotEmpty()) meta.add(task.labels.joinToString(" ") { "@" + it })
            if (task.repeat.isNotBlank()) meta.add(task.repeat)
            else if (task.due.isNotBlank()) meta.add(raznDate(task.due))
            if (task.priority != ru.zf.pravka.core.ParsedTask.P4) meta.add(task.priorityLabel)
            if (task.projectName.isBlank()) meta.add("проект не выбран")
            RaznoskaButtonController.PlateRow(
                id = task.id,
                title = task.content,
                meta = meta.joinToString(" · "),
                warn = if (task.duplicateOf.isBlank()) "" else "⚠ похоже: " + task.duplicateOf,
                sent = task.sent,
            )
        }
        rButton?.showTasks(
            header = "РАЗНОСКА · " + raznCount(waiting),
            rows = rows,
            onEdit = { id -> editRaznoskaTask(draftId, id) },
            onOpen = { openTodoistTab() },
            onSend = { ids -> sendRaznoskaTasks(draftId, ids) },
        )
    }

    /** «ОК» на плашке: одной отправкой уезжают все отмеченные дела. */
    private fun sendRaznoskaTasks(draftId: Long, taskIds: List<Long>) {
        if (taskIds.isEmpty()) return
        rButton?.setBusy(true)
        scope.launch {
            val outcome = runCatching { app.raznoskaEngine.sendOnly(draftId, taskIds) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ru.zf.pravka.core.RaznoskaEngine.SendOutcome(
                        0, taskIds.size, e.message ?: "не отправилось",
                    )
                }
            rButton?.setBusy(false)
            reportRaznoskaSend(outcome.created, outcome.failed, outcome.error)
        }
    }

    /** ✎ на плашке: правка формулировки на месте. Пусто = вычеркнуть дело. */
    private fun editRaznoskaTask(draftId: Long, taskId: Long) {
        val task = app.raznoskaStore.byId(draftId)?.tasks?.firstOrNull { it.id == taskId } ?: return
        rButton?.hidePlate()
        rButton?.showInput(
            prefill = task.content,
            hint = "Кто: что сделать",
            // Отмена не должна съедать разбор - плашка возвращается как была.
            onCancel = { showRaznoskaPlate(draftId) },
        ) { typed ->
            scope.launch {
                app.raznoskaEngine.editText(draftId, taskId, typed)
                showRaznoskaPlate(draftId)
            }
        }
    }

    /** «3 дела» / «1 дело» — плашка и тосты говорят по-русски. */
    private fun raznCount(n: Int): String {
        val word = when {
            n % 10 == 1 && n % 100 != 11 -> "дело"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "дела"
            else -> "дел"
        }
        return "$n $word"
    }

    /** «2026-08-25» → «25 авг». */
    private fun raznDate(iso: String): String = runCatching {
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
        if (parsed == null) iso
        else java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(parsed)
    }.getOrDefault(iso)

    private fun openTodoistTab() {
        startActivity(
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODOIST)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Отправка в Todoist. Уже созданные дела пропускаются внутри движка, так
     * что повторное «ОК» после потери сети ничего не удваивает.
     */
    private fun sendRaznoska(draftIds: List<Long>) {
        if (draftIds.isEmpty()) return
        rButton?.setBusy(true)
        scope.launch {
            var created = 0
            var failed = 0
            var error = ""
            for (id in draftIds) {
                val outcome = runCatching { app.raznoskaEngine.send(id) }
                    .getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        ru.zf.pravka.core.RaznoskaEngine.SendOutcome(
                            0, 1, e.message ?: "не отправилось",
                        )
                    }
                created += outcome.created
                failed += outcome.failed
                if (error.isBlank() && outcome.error.isNotBlank()) error = outcome.error
            }
            rButton?.setBusy(false)
            reportRaznoskaSend(created, failed, error)
        }
    }

    /**
     * Итог отправки одним местом: успех - записка с ручкой отмены, частичный
     * успех и провал - тост, из которого понятно, где остались дела.
     */
    private fun reportRaznoskaSend(created: Int, failed: Int, error: String) {
        when {
            failed == 0 && created > 0 -> {
                Haptics.success(this)
                // Записка вместо тоста: у неё есть ручка отмены.
                rButton?.showNote(
                    text = "✓ " + raznCount(created) + " в Todoist",
                    actionLabel = "↩︎",
                ) { undoRaznoska() }
            }
            created > 0 -> {
                Haptics.error(this)
                Feedback.toast(
                    this,
                    "Отправлено $created, осталось $failed: $error",
                    long = true,
                )
            }
            else -> {
                Haptics.error(this)
                Feedback.toast(
                    this,
                    "Не отправилось ($error). Дела ждут во вкладке «Дела».",
                    long = true,
                )
            }
        }
    }

    private fun resplitRaznoska(draftId: Long) {
        rButton?.setBusy(true)
        scope.launch {
            val result = runCatching { app.raznoskaEngine.resplit(draftId) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Result.failure(e)
                }
            rButton?.setBusy(false)
            result.onSuccess { draft ->
                Haptics.success(this@PravkaAccessibilityService)
                if (draft.tasks.isEmpty()) {
                    Feedback.toast(this@PravkaAccessibilityService, "Дел так и не нашлось")
                } else {
                    showRaznoskaPlate(draft.id)
                }
            }.onFailure { e ->
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(
                    this@PravkaAccessibilityService,
                    e.message ?: "Не вышло разобрать заново",
                    long = true,
                )
            }
        }
    }

    /**
     * Разноска чужого текста: дайджест из чата, расшифровка встречи, письмо.
     * Тот же разбор, только наговор пришёл не голосом. Длинную стену режем -
     * иначе один разбор стоил бы как день работы.
     */
    fun raznoskaFromText(raw: String) {
        val limit = 60_000
        val text = raw.trim().take(limit)
        if (text.isBlank()) {
            Haptics.error(this)
            Feedback.toast(this, "Нечего разбирать — текста нет")
            return
        }
        if (raw.trim().length > limit) {
            Feedback.toast(this, "Текст длинный: взял первые ${limit / 1000} тыс. знаков")
        }
        onRaznoskaText(text)
    }

    /** «Разобрать текст» в меню «Д»: выделение → поле → буфер обмена. */
    private fun raznoskaFromSelection() {
        scope.launch {
            val text = runCatching { assistContent() }.getOrDefault("")
            raznoskaFromText(text)
        }
    }

    /** «↩︎ Отменить отправку»: только что созданные дела уходят из Todoist. */
    private fun undoRaznoska() {
        rButton?.setBusy(true)
        scope.launch {
            val outcome = runCatching { app.raznoskaEngine.undoLast() }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ru.zf.pravka.core.RaznoskaEngine.UndoOutcome(0, 1, 0L)
                }
            rButton?.setBusy(false)
            when {
                outcome.deleted == 0 -> {
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(this@PravkaAccessibilityService, "Отменять нечего")
                }
                outcome.failed == 0 -> {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "↩︎ " + raznCount(outcome.deleted) + " убрано из Todoist",
                    )
                    // Дела снова ждут - показываем разбор, чтобы поправить и
                    // отправить заново.
                    if (outcome.draftId != 0L) showRaznoskaPlate(outcome.draftId)
                }
                else -> {
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "Убрал ${outcome.deleted}, ${outcome.failed} не поддались — глянь в Todoist",
                        long = true,
                    )
                }
            }
        }
    }

    private fun showRaznoskaMenu() {
        scope.launch {
            runCatching { app.raznoskaStore.load() }
            val pending = app.raznoskaStore.pending()
            val waiting = pending.sumOf { it.pendingCount }
            val items = mutableListOf<RaznoskaButtonController.MenuItem>()
            if (waiting > 0) {
                val newest = pending.first()
                items.add(
                    RaznoskaButtonController.MenuItem("✓ Отправить ${raznCount(waiting)}") {
                        sendRaznoska(pending.map { it.id })
                    }
                )
                items.add(
                    RaznoskaButtonController.MenuItem("Показать разбор") {
                        showRaznoskaPlate(newest.id)
                    }
                )
                items.add(
                    RaznoskaButtonController.MenuItem("Разобрать заново") {
                        resplitRaznoska(newest.id)
                    }
                )
            }
            if (app.raznoskaEngine.undoAvailable()) {
                items.add(
                    RaznoskaButtonController.MenuItem(
                        "↩︎ Отменить отправку (" + app.raznoskaEngine.undoCount() + ")"
                    ) { undoRaznoska() }
                )
            }
            items.add(
                RaznoskaButtonController.MenuItem("Разобрать текст") { raznoskaFromSelection() }
            )
            items.add(
                RaznoskaButtonController.MenuItem("Набрать текстом") { openRaznoskaTypeIn("") }
            )
            items.add(RaznoskaButtonController.MenuItem("Открыть «Дела»") { openTodoistTab() })
            rButton?.showMenu(items)
        }
    }

    // ---- Еда: тап -> сказал, что съел -> КБЖУ -> дневник ----

    /** Кнопка «Е»: старт наговора, второй тап — разбор по КБЖУ. */
    fun onFoodTap() {
        if (isLockedIdle()) return
        if (eSession != null) { stopFoodLive(); return }
        if (eWhisperRecording && DictationService.recording) {
            eButton?.setBusy(true)
            stopDictation()  // -> onRecordingSaved, routed by the flag
            return
        }
        // Один микрофон на все четыре кнопки: чужую запись эта не перехватывает.
        if (googleSession != null || zSession != null || zWhisperRecording ||
            rSession != null || rWhisperRecording || DictationService.recording
        ) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.e_busy))
            return
        }
        if (!hasMicPermission()) {
            micRequestForFood = true
            requestMicPermission()
            return
        }
        startFoodCapture()
    }

    private fun startFoodCapture() {
        eButton?.hideInput()
        eButton?.hidePlate()
        if (cachedEngine.startsWith("whisper")) {
            eWhisperRecording = true
            eButton?.setRecording(true)
            eButton?.showTicker()
            eButton?.updateTicker("🎙 подходы, еда, зарядка… (тап сюда — набрать текстом)")
            Haptics.start(this)
            startDictation()
        } else {
            startFoodGoogle()
        }
        // Пока владелец говорит: греем сокет к API и подтягиваем всё, что
        // уезжает в промпт — справочники, план на сегодня, прошлый раз.
        app.warmClaudeConnection()
        scope.launch { runCatching { app.sportStore.load() } }
        scope.launch { runCatching { app.foodStore.load() } }
        scope.launch { runCatching { app.strengthStore.load() } }
        scope.launch { runCatching { app.planStore.load() } }
        scope.launch { runCatching { app.exerciseBook.load() } }
    }

    private fun startFoodGoogle() {
        if (eSession != null) return
        if (!GoogleSpeechSession.isAvailable(this)) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.google_unavailable))
            return
        }
        val session = GoogleSpeechSession(
            this,
            // Гоблет, сплит, РДЛ, свинги, паллоф, лопаточные, творог, кускус —
            // распознаватель должен слышать ИХ, а не «гоблин» и «кус-кус».
            biasing = (cachedBiasing + bodyBiasing()).distinct(),
            formatting = cachedFormatting,
            segmentedSession = cachedSegmented,
        )
        eSession = session
        session.start(
            onReady = { Haptics.success(this) },
            onPartial = { live -> eButton?.updateTicker(live) },
            // Приём пищи — две фразы: черновик на диск не пишем, повторить
            // дешевле, чем чинить (то же решение, что у Разноски).
            onCheckpoint = { },
            onDone = { text -> onFoodLiveDone(text) },
            onError = { msg -> onFoodLiveError(msg) },
            onLog = { line -> app.eventLog.add("еда: $line") },
        )
        eButton?.setRecording(true)
        eButton?.showTicker()
        eButton?.updateTicker("🎙 подходы, еда, зарядка… (тап сюда — набрать текстом)")
        Haptics.start(this)
        runCatching { startMicHold() }
    }

    private fun stopFoodLive() {
        val session = eSession ?: return
        eButton?.setRecording(false)
        eButton?.setBusy(true)
        session.stop()  // -> onFoodLiveDone
    }

    /** Тап по живой плашке: микрофон замолчал, дальше набираем текстом. */
    private fun onFoodTickerTap() {
        when {
            eSession != null -> {
                eTypeInstead = true
                stopFoodLive()
            }
            eWhisperRecording && DictationService.recording -> {
                eTypeInstead = true
                eButton?.setBusy(true)
                stopDictation()
            }
        }
    }

    private fun openFoodTypeIn(prefill: String) {
        eButton?.showInput(prefill = prefill, hint = "Подходы, еда, зарядка") { typed ->
            val text = typed.trim()
            if (text.isNotEmpty()) onFoodText(text)
        }
    }

    private fun onFoodLiveDone(text: String) {
        eSession = null
        runCatching { stopMicHold() }
        runCatching { eButton?.hideTicker() }
        eButton?.setRecording(false)
        if (eTypeInstead) {
            eTypeInstead = false
            eButton?.setBusy(false)
            openFoodTypeIn(text.trim())
            return
        }
        onFoodText(text)
    }

    private fun onFoodLiveError(msg: String) {
        eSession = null
        runCatching { stopMicHold() }
        eButton?.hideTicker()
        eButton?.setRecording(false)
        eButton?.setBusy(false)
        Haptics.error(this)
        Feedback.toast(this, msg)
    }

    // Следующая диктовка кнопки идёт через СТАРЫЙ роутер Тела (подходы,
    // зарядка, вопрос) — взводится пунктом меню «Тело голосом». По умолчанию
    // кнопка теперь ЕДА напрямую: владелец спорт наговаривает во вкладке.
    @Volatile private var eRouteNext = false

    /**
     * Сказанное в руках. Кнопка — теперь ЕДА напрямую: без роутера намерений,
     * дешевле и однозначно; рацион в промпте имеет жёсткий приоритет — «мой
     * обычный завтрак» разворачивается в весь набор сам. Спорт и зарядка
     * голосом — пункт меню «Тело голосом» (прежний роутер).
     *
     * Неудача разбора не стоит владельцу его слов: фраза ложится
     * неразобранной (addRaw) и переигрывается из вкладки.
     */
    private fun onFoodText(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            eButton?.setBusy(false)
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.dictation_empty))
            return
        }
        if (!scope.isActive) return
        eButton?.setBusy(true)
        if (eRouteNext) {
            eRouteNext = false
            scope.launch {
                val result = runCatching { app.bodyEngine.hear(text, "voice") }
                    .getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        app.eventLog.add("тело: hear бросил ${e.javaClass.simpleName}: ${e.message}")
                        Result.failure(e)
                    }
                eButton?.setBusy(false)
                val outcome = result.getOrElse { e ->
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        (e.message ?: getString(R.string.e_parse_failed)) +
                            " Сказанное сохранено — можно разобрать заново.",
                        long = true,
                    )
                    return@launch
                }
                Haptics.success(this@PravkaAccessibilityService)
                showBodyPlate(outcome)
            }
            return
        }
        scope.launch {
            val result = runCatching { app.foodEngine.parse(text, source = "voice") }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Result.failure(e)
                }
            eButton?.setBusy(false)
            result.fold(
                onSuccess = { parsed ->
                    Haptics.success(this@PravkaAccessibilityService)
                    showFoodPlate(parsed.meal.id)
                },
                onFailure = { e ->
                    // Слова не теряем: неразобранное ждёт во вкладке Спорта.
                    runCatching { app.strengthStore.addRaw(text, "food") }
                    Haptics.error(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        (e.message ?: "Еду не разобрал") +
                            " — сохранено; спорт голосом теперь в меню кнопки.",
                        long = true,
                    )
                },
            )
        }
    }

    /** Плашка по разобранному: у каждого вида свой вид, кнопка одна. */
    private fun showBodyPlate(outcome: ru.zf.pravka.core.BodyEngine.Outcome) {
        when {
            outcome.strength != null -> showStrengthPlate(outcome.strength.session.id, outcome.strength)
            outcome.meal != null -> showFoodPlate(outcome.meal.id)
            outcome.gtg != null -> {
                val streak = app.strengthStore.streak(
                    ru.zf.pravka.data.dayKey(System.currentTimeMillis())
                )
                eButton?.showNote(
                    "✓ " + outcome.headline() + " · цепочка " + streak + " дн.",
                    "Спорт",
                    onAction = { openSportTab() },
                )
            }
            outcome.feel > 0 || outcome.knee.isNotBlank() ->
                eButton?.showNote("✓ " + outcome.headline(), null, onAction = null)
            outcome.question.isNotBlank() -> askCoach(outcome.question)
            else -> {
                Feedback.toast(
                    this,
                    (outcome.note.ifBlank { "Не понял, что это" }) +
                        " — сказанное сохранено, посмотри во «Спорте»",
                    long = true,
                )
            }
        }
    }

    /**
     * Подходы на плашке: у каждого упражнения дельта к прошлому разу — это и
     * есть весь смысл журнала. Подтверждать нечего: записано уже, «ОК» тут был
     * бы шансом потерять данные. Вместо него чипы отдыха.
     */
    private fun showStrengthPlate(
        sessionId: Long,
        logged: ru.zf.pravka.core.StrengthEngine.Logged?,
    ) {
        val session = app.strengthStore.sessionById(sessionId) ?: return
        if (session.exercises.isEmpty()) return
        val deltas = logged?.deltas?.associateBy { it.name }.orEmpty()
        val rows = session.exercises.mapIndexed { index, e ->
            val delta = deltas[e.name]
            BodyButtonController.PlateRow(
                index = index,
                title = e.name,
                meta = e.compact() + (if (e.note.isBlank()) "" else " · " + e.note),
                delta = delta?.let {
                    if (it.previous.isBlank()) "первый раз"
                    else it.text + " (было " + it.previous + ")"
                }.orEmpty(),
                deltaUp = delta?.up,
            )
        }
        val rest = cachedRestSec
        eButton?.showBody(
            header = session.title.uppercase(java.util.Locale("ru")),
            rows = rows,
            footer = "подходов ${session.setCount}",
            note = logged?.unknown?.takeIf { it.isNotEmpty() }
                ?.let { "не узнал: " + it.joinToString(", ") }.orEmpty(),
            onEditItem = { index -> editStrengthRow(sessionId, index) },
            onDropItem = { index -> dropStrengthRow(sessionId, index) },
            onOpen = { openSportTab() },
            onConfirm = null,
            chips = listOf(60, rest, 120).distinct().map { seconds ->
                BodyButtonController.Chip("⏱ $seconds") { startRest(seconds) }
            },
        )
    }

    /** «✎» у упражнения: поправить вес — КБЖУ подходов от него не зависит. */
    private fun editStrengthRow(sessionId: Long, index: Int) {
        val session = app.strengthStore.sessionById(sessionId) ?: return
        val exercise = session.exercises.getOrNull(index) ?: return
        eButton?.showInput(
            prefill = exercise.compact(),
            hint = exercise.name + " — подходы",
            onCancel = { showStrengthPlate(sessionId, null) },
        ) { typed ->
            scope.launch {
                val rows = parseSetsByHand(typed, exercise)
                if (rows == null) {
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "Не понял. Пиши как «4x10 16» или «10, 9, 8 @16»",
                        long = true,
                    )
                } else {
                    app.strengthEngine.editRows(sessionId, exercise.exerciseId, rows)
                }
                showStrengthPlate(sessionId, null)
            }
        }
    }

    /**
     * Руками подходы пишутся коротко: «4x10 16», «10, 9, 8 @16», «40».
     * Разбирается на телефоне — за правку веса платить моделью незачем.
     */
    private fun parseSetsByHand(
        typed: String,
        exercise: ru.zf.pravka.data.StrengthStore.ExerciseLog,
    ): List<ru.zf.pravka.data.StrengthStore.SetRow>? {
        val text = typed.trim().lowercase().replace(',', ' ')
        if (text.isBlank()) return emptyList()   // пусто = убрать упражнение
        // Вес — то, что после «@» или последнее число, если есть «х»/«x».
        val weight = Regex("@\\s*(\\d+(?:[.,]\\d+)?)").find(text)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val body = text.substringBefore('@').trim()
        val cross = Regex("^(\\d+)\\s*[xх*]\\s*(\\d+)(?:\\s+(\\d+(?:[.,]\\d+)?))?$").find(body)
        if (cross != null) {
            val sets = cross.groupValues[1].toIntOrNull() ?: return null
            val amount = cross.groupValues[2].toIntOrNull() ?: return null
            val inline = cross.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0
            val kg = if (weight > 0) weight else inline
            if (sets !in 1..30 || amount !in 1..3000) return null
            return List(sets) {
                ru.zf.pravka.data.StrengthStore.SetRow(amount, kg, "")
            }
        }
        val numbers = Regex("\\d+").findAll(body).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        return numbers.filter { it in 1..3000 }.map {
            ru.zf.pravka.data.StrengthStore.SetRow(it, weight, "")
        }.ifEmpty { null }
    }

    private fun dropStrengthRow(sessionId: Long, index: Int) {
        val session = app.strengthStore.sessionById(sessionId) ?: return
        val exercise = session.exercises.getOrNull(index) ?: return
        scope.launch {
            app.strengthStore.dropExercise(sessionId, exercise.exerciseId)
            val left = app.strengthStore.sessionById(sessionId)
            if (left == null || left.exercises.isEmpty()) {
                eButton?.hidePlate()
                Feedback.toast(this@PravkaAccessibilityService, "Упражнений не осталось")
            } else {
                showStrengthPlate(sessionId, null)
            }
        }
    }

    // ---- Отдых между подходами ----

    private val restHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val restTick = object : Runnable {
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

    /** Отдых из карточки дня во вкладке: считает всё равно кнопка. */
    fun startRestFromTab(seconds: Int) = startRest(seconds)

    private fun startRest(seconds: Int) {
        restHandler.removeCallbacks(restTick)
        restUntil = System.currentTimeMillis() + seconds * 1000L
        Haptics.start(this)
        restHandler.post(restTick)
    }

    private fun stopRest() {
        restHandler.removeCallbacks(restTick)
        restUntil = 0L
        eButton?.setRest(0)
    }

    /** Вопрос голосом: Опус отвечает, ответ ложится запиской у кнопки. */
    private fun askCoach(question: String) {
        eButton?.setBusy(true)
        scope.launch {
            val answer = runCatching { app.sportCoach.ask(question) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ru.zf.pravka.core.SportCoach.Answer("", 0.0, e.message ?: "не вышло")
                }
            eButton?.setBusy(false)
            if (answer.error.isNotBlank()) {
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, answer.error, long = true)
                return@launch
            }
            // Ответ бывает в несколько абзацев — на плашке он не поместится, и
            // растягивать её на пол-экрана незачем: первые строки здесь,
            // целиком во вкладке «Спорт».
            eButton?.showNote(
                answer.text.replace('\n', ' ').take(180),
                "Целиком",
                holdMs = 30_000,
                onAction = { openSportTab() },
            )
        }
    }

    /** Голосовые имена упражнений и продуктов для смещения распознавателя. */
    private fun bodyBiasing(): List<String> =
        runCatching { app.bodyEngine.biasing() }.getOrDefault(emptyList())

    /**
     * Тарелка на плашке: позиции с граммами и КБЖУ, «✎» правит вес на месте,
     * «✕» убирает позицию, «✓ В дневник» записывает приём. До подтверждения
     * приём в сумму дня не идёт и наружу не уезжает — но на диске он уже есть.
     */
    private fun showFoodPlate(mealId: Long) {
        val meal = app.foodStore.byId(mealId) ?: return
        if (meal.items.isEmpty()) return
        val rows = meal.items.mapIndexed { index, item ->
            BodyButtonController.PlateRow(
                index = index,
                title = item.name,
                meta = listOfNotNull(
                    if (item.grams > 0) "${item.grams} г" else null,
                    "${item.kcal} ккал",
                    "Б${item.protein} Ж${item.fat} У${item.carbs}",
                    item.sureness.takeIf { it.isNotBlank() && it != "точно" },
                ).joinToString(" · "),
            )
        }
        eButton?.showBody(
            header = meal.kind.uppercase(java.util.Locale("ru")) +
                (if (meal.source == "barcode") " · ШТРИХКОД" else ""),
            rows = rows,
            footer = "${meal.kcal} ккал · Б${meal.protein} Ж${meal.fat} У${meal.carbs}",
            note = meal.note,
            onEditItem = { index -> editFoodItem(mealId, index) },
            onDropItem = { index -> dropFoodItem(mealId, index) },
            onOpen = { openFoodTab() },
            onConfirm = { confirmFood(mealId) },
            confirmLabel = "✓ В дневник",
        )
    }

    /** «✎» у позиции: правим вес, КБЖУ пересчитывается пропорционально. */
    private fun editFoodItem(mealId: Long, index: Int) {
        val meal = app.foodStore.byId(mealId) ?: return
        val item = meal.items.getOrNull(index) ?: return
        eButton?.showInput(
            prefill = if (item.grams > 0) item.grams.toString() else "",
            hint = "${item.name} — сколько граммов",
            onCancel = { showFoodPlate(mealId) },
        ) { typed ->
            val grams = typed.filter { it.isDigit() }.toIntOrNull()
            scope.launch {
                if (grams != null && grams > 0) {
                    app.foodEngine.rescaleItem(mealId, index, grams)
                } else {
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "Не понял вес — оставил как было",
                    )
                }
                showFoodPlate(mealId)
            }
        }
    }

    private fun dropFoodItem(mealId: Long, index: Int) {
        scope.launch {
            app.foodEngine.dropItem(mealId, index)
            if (app.foodStore.byId(mealId) == null) {
                Haptics.success(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, "Приём убран целиком")
                eButton?.hidePlate()
            } else {
                showFoodPlate(mealId)
            }
        }
    }

    /** «✓ В дневник»: приём в день, а оттуда в ленту и в intervals.icu. */
    private fun confirmFood(mealId: Long) {
        eButton?.setBusy(true)
        scope.launch {
            val outcome = runCatching { app.foodEngine.confirm(mealId) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ru.zf.pravka.core.FoodEngine.ConfirmOutcome(null, "", e.message ?: "не вышло")
                }
            eButton?.setBusy(false)
            val meal = outcome.meal
            if (meal == null) {
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, "Приём не нашёлся")
                return@launch
            }
            Haptics.success(this@PravkaAccessibilityService)
            val day = app.foodStore.dayTotal(ru.zf.pravka.data.dayKey(meal.ts))
            val targets = runCatching { app.settings.foodTargets() }.getOrNull()
            val target = targets?.kcal ?: 0
            val tail = buildString {
                append(
                    when {
                        target > 0 && day.kcal <= target -> "за день ${day.kcal} из $target ккал"
                        target > 0 -> "за день ${day.kcal} ккал, цель $target"
                        else -> "за день ${day.kcal} ккал"
                    }
                )
                // Белок — его настоящий рычаг («накачаться впервые в жизни»),
                // и добирают его сознательно: остаток полезнее суммы.
                val proteinTarget = targets?.protein ?: 0
                if (proteinTarget > 0 && day.protein < proteinTarget) {
                    append(" · Б ещё ").append(proteinTarget - day.protein)
                }
            }
            eButton?.showNote(
                "✓ ${meal.kcal} ккал · $tail",
                "↩︎",
                onAction = { undoFood(mealId) },
            )
            if (outcome.icuError.isNotBlank()) {
                app.eventLog.add("еда: в intervals.icu не уехало — ${outcome.icuError}")
            }
        }
    }

    /**
     * «↩︎» на записке: приём выходит из дня, а разбор остаётся ждать — плашка
     * возвращается, чтобы поправить и записать заново. Совсем убрать приём
     * можно во вкладке «Еда».
     */
    private fun undoFood(mealId: Long) {
        scope.launch {
            val meal = app.foodEngine.unconfirm(mealId)
            Haptics.success(this@PravkaAccessibilityService)
            if (meal == null) {
                Feedback.toast(this@PravkaAccessibilityService, "Приём не нашёлся")
                return@launch
            }
            Feedback.toast(this@PravkaAccessibilityService, "↩︎ Из дня убран, разбор ждёт")
            showFoodPlate(mealId)
        }
    }

    private fun openFoodTab(action: String = "") {
        startActivity(
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_FOOD)
                .apply {
                    if (action.isNotBlank()) {
                        putExtra(ru.zf.pravka.MainActivity.EXTRA_FOOD_ACTION, action)
                    }
                }
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openSportTab() {
        startActivity(
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_SPORT)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun showFoodMenu() {
        scope.launch {
            runCatching { app.foodStore.load() }
            runCatching { app.strengthStore.load() }
            runCatching { app.planStore.load() }
            val today = ru.zf.pravka.data.dayKey(System.currentTimeMillis())
            val items = mutableListOf<BodyButtonController.MenuItem>()

            // Первой строкой — что сегодня по плану. Чаще всего кнопку держат
            // именно за этим: «а что у меня сегодня».
            val planned = app.planStore.mainOf(today)
            items.add(
                BodyButtonController.MenuItem(
                    planned?.let { p ->
                        "▶ " + p.name + (if (p.minutes > 0) " · ${p.minutes} мин" else "")
                    } ?: "План на сегодня не найден"
                ) { openSportTab() }
            )

            // Отдых: идёт — оборвать, не идёт — запустить.
            if (restUntil > System.currentTimeMillis()) {
                val left = ((restUntil - System.currentTimeMillis()) / 1000L).toInt()
                items.add(BodyButtonController.MenuItem("⏱ Отдых $left сек — стоп") { stopRest() })
            } else {
                items.add(
                    BodyButtonController.MenuItem("⏱ Отдых $cachedRestSec сек") {
                        startRest(cachedRestSec)
                    }
                )
            }

            // Зарядка одной кнопкой, без модели и без токенов. Подпись говорит
            // ровно одно: отметить или уже отмечено. Цепочка рядом — она и есть
            // то, что не должно рваться. Висы и негативы отсюда не пишутся: их
            // числа наговариваются или ставятся во вкладке, и мешать их с
            // отметкой «делал» было ровно той путаницей, из которой всё
            // непонимание и росло.
            val gtg = app.strengthStore.gtgOn(today)
            val streak = app.strengthStore.streak(today)
            if (gtg?.charged == true) {
                items.add(
                    BodyButtonController.MenuItem("✓ Зарядка сделана · цепочка $streak дн.") {
                        openSportTab()
                    }
                )
            } else {
                items.add(
                    BodyButtonController.MenuItem(
                        "Зарядка сделана" +
                            (if (streak > 0) " · цепочка $streak дн." else " · цепочка начнётся")
                    ) { markCharged() }
                )
            }

            val session = app.strengthStore.sessionsOn(today).firstOrNull { !it.empty }
            if (session != null) {
                // Куда уехал журнал — прямо в подписи: «ждём часы» это ответ на
                // вопрос «а записалось ли», а пустая строка — нет.
                val route = app.strengthEngine.routeOf(session)
                items.add(
                    BodyButtonController.MenuItem(
                        "Подходы сегодня (${session.setCount}) · " +
                            (if (route.tone == 1) "уехало" else route.headline.lowercase())
                    ) { showStrengthPlate(session.id, null) }
                )
            }

            val food = app.foodStore.dayTotal(today)
            val target = runCatching { app.settings.foodTargets().kcal }.getOrDefault(0)
            items.add(
                BodyButtonController.MenuItem(
                    if (food.empty) "Еды сегодня не записано"
                    else "Еда: ${food.kcal}" + (if (target > 0) " из $target" else "") + " ккал"
                ) { openFoodTab() }
            )

            val pending = app.foodStore.pending()
            if (pending.isNotEmpty()) {
                items.add(
                    BodyButtonController.MenuItem("Показать разбор еды (${pending.size})") {
                        showFoodPlate(pending.first().id)
                    }
                )
            }
            items.add(BodyButtonController.MenuItem("Набрать текстом") { openFoodTypeIn("") })
            // Долгое нажатие — дороги еды без слов (его просьба): снять
            // тарелку или штрихкод. Открывают Тело (Е) с автозапуском.
            items.add(
                BodyButtonController.MenuItem("📷 Сфоткать тарелку") { openFoodTab("photo") }
            )
            items.add(
                BodyButtonController.MenuItem("▥ Штрихкод") { openFoodTab("barcode") }
            )
            items.add(
                BodyButtonController.MenuItem("🎙 Тело голосом (подходы, зарядка)") {
                    eRouteNext = true
                    onFoodTap()
                }
            )
            items.add(BodyButtonController.MenuItem("Открыть «Спорт»") { openSportTab() })
            items.add(BodyButtonController.MenuItem("Открыть «Еду»") { openFoodTab() })
            eButton?.showMenu(items)
        }
    }

    /** «Зарядка сделана»: одна отметка, ноль токенов, цепочка не рвётся. */
    private fun markCharged() {
        scope.launch {
            val day = app.bodyEngine.chargedToday()
            val streak = app.strengthStore.streak(day.date)
            Haptics.success(this@PravkaAccessibilityService)
            eButton?.showNote("✓ Зарядка сделана · цепочка $streak дн.", null, onAction = null)
        }
    }

    private fun reparseFood(mealId: Long) {
        eButton?.setBusy(true)
        scope.launch {
            val result = runCatching { app.foodEngine.reparse(mealId) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Result.failure(e)
                }
            eButton?.setBusy(false)
            result.onSuccess { parsed ->
                Haptics.success(this@PravkaAccessibilityService)
                showFoodPlate(parsed.meal.id)
            }.onFailure { e ->
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(
                    this@PravkaAccessibilityService,
                    e.message ?: "Разобрать заново не вышло",
                    long = true,
                )
            }
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
            val n = (floatingButton?.windowCount() ?: 0) + (zButton?.windowCount() ?: 0) +
                (rButton?.windowCount() ?: 0) + (eButton?.windowCount() ?: 0)
            app.eventLog.add("смена конфигурации: наших окон $n")
        }
    }

    private val configHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val configSettled = Runnable {
        floatingButton?.onConfigurationChanged()
        zButton?.onConfigurationChanged()
        rButton?.onConfigurationChanged()
        eButton?.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { autoPilot.stop() }
        ripenessHandler.removeCallbacks(ripenessCheck)
        zReminderHandler.removeCallbacks(zReminderTick)
        pomodoroHandler.removeCallbacks(pomodoroTicker)
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
        floatingButton?.destroy()
        floatingButton = null
        zButton?.destroy()
        zButton = null
        rButton?.destroy()
        rButton = null
        restHandler.removeCallbacks(restTick)
        eButton?.destroy()
        eButton = null
        scope.cancel()
        super.onDestroy()
    }
}
