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
    @Volatile private var cachedZDayStart = 9
    @Volatile private var cachedZDayEnd = 23
    @Volatile private var zCategoriesCached: List<String> = emptyList()
    @Volatile private var zClientsCached: List<String> = emptyList()

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

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        // The linked pair (owner's design): drag either bubble and the other
        // trails behind on a rubber band; the "П" always docks above the "З".
        val pairGap = (8 * resources.displayMetrics.density).toInt()
        floatingButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            zButton?.followTo(x, y + size + pairGap, dropped)
        }
        zButton?.onDragged = { x, y, dropped ->
            val size = floatingButton?.buttonSizePx() ?: 0
            floatingButton?.followTo(x, y - size - pairGap, dropped)
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
        scope.launch { app.settings.zGapMinFlow.collect { cachedZGapMin = it } }
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
        return googleSession == null && zSession == null &&
            !zWhisperRecording && !DictationService.recording
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
        if (micRequestForZasechka) {
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
        // One microphone: while a Правка take runs, the "З" tap only nags.
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
        if (zSession != null) stopZasechkaLive() else stopLiveDictation()
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
            val items = if (pomodoroEndsAt > 0) {
                listOf(
                    header,
                    ZasechkaButtonController.MenuItem(
                        if (pomodoroIsBreak) "Стоп: перерыв" else "Стоп: помидор"
                    ) { stopPomodoro(byUser = true) },
                    openTab,
                )
            } else {
                listOf(
                    header,
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A focusable type-in box must not sit above the keyguard through a
        // display switch - fold closes it (the draft is a sentence, not a loss).
        // This one is immediate: removing a window helps the transition.
        zButton?.hideInput()
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
            val n = (floatingButton?.windowCount() ?: 0) + (zButton?.windowCount() ?: 0)
            app.eventLog.add("смена конфигурации: наших окон $n")
        }
    }

    private val configHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val configSettled = Runnable {
        floatingButton?.onConfigurationChanged()
        zButton?.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        ripenessHandler.removeCallbacks(ripenessCheck)
        zReminderHandler.removeCallbacks(zReminderTick)
        pomodoroHandler.removeCallbacks(pomodoroTicker)
        lagHandler.removeCallbacks(lagTick)
        configHandler.removeCallbacks(configSettled)
        googleSession?.stop()
        googleSession = null
        zSession?.stop()
        zSession = null
        runCatching { stopMicHold() }
        floatingButton?.destroy()
        floatingButton = null
        zButton?.destroy()
        zButton = null
        scope.cancel()
        super.onDestroy()
    }
}
