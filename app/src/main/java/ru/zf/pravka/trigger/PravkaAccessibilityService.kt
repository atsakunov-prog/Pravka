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
    private var resultBar: ResultBarController? = null
    private var busy = false

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
        resultBar = ResultBarController(this, ::undoLast, ::addPairToDictionary, ::redoWithDirective)
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
        refreshLearnBadge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    cachedFocus = WeakReference(source)
                    floatingButton?.show()
                } else {
                    floatingButton?.hide()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
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
                                    app.learnLog.add("правка замечена: поле в $pkg, ${current.length} зн. — созреет через 10 мин")
                                    scheduleRipenessCheck()
                                }
                            }
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Toasts and our own overlays fire this event too - ignore
                // ourselves, or the result bar dies the moment it appears
                // (the owner never saw it at all before this check).
                if (event.packageName == packageName) return
                // App or window switched: the field the bar describes is
                // gone; only keep the button when a field is still focused.
                resultBar?.dismissIfStale()
                // While a take is live the button is pinned visible in every app
                // and hide() early-returns anyway, so the tree walk below would
                // be pure waste - and the owner is expected to switch apps
                // mid-dictation, which is exactly when it would block the
                // recognizer's callbacks.
                if (googleSession != null || DictationService.recording) return
                if (liveFocusedEditableNode() != null) floatingButton?.show()
                else floatingButton?.hide()
            }
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

    /** Short tap: stop the active session if any, else start per the engine. */
    private fun onDictateTap() {
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

    /** Called by MicPermissionActivity after the permission is granted. */
    fun onMicPermissionGranted() = startForEngine()

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

    private fun convoRemember(pkg: String?, text: String) {
        if (pkg.isNullOrBlank() || text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        // A new chain starts after a long gap or in another app.
        val last = convo.lastOrNull()
        if (last != null && (last.pkg != pkg || now - last.at > CONVO_GAP_MS)) convo.clear()
        convo.addLast(ConvoEntry(pkg, now, text.take(500)))
        while (convo.size > 6) convo.removeFirst()
    }

    private fun convoContextFor(pkg: String?): String {
        if (!cachedConvoContext || pkg.isNullOrBlank()) return ""
        val now = SystemClock.elapsedRealtime()
        val last = convo.lastOrNull() ?: return ""
        if (last.pkg != pkg || now - last.at > CONVO_GAP_MS) return ""
        val recent = convo.filter { it.pkg == pkg }.takeLast(4)
        if (recent.isEmpty()) return ""
        val sb = StringBuilder("Мои предыдущие сообщения в этом разговоре (только для тона и связности, их не менять):\n")
        var used = 0
        for (e in recent) {
            if (used + e.text.length > 800) break
            sb.append("— ").append(e.text).append('\n')
            used += e.text.length
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
        if (text.isNotBlank()) scope.launch { insertDictated(text) }
        val wall = SystemClock.elapsedRealtime() - googleStartedAt
        app.transcriptionLog.append(
            engine = engine,
            audioMs = wall,
            transcribeMs = 0,
            text = text,
            error = if (text.isBlank()) "пустой результат" else null,
        )
        // Delivered (and logged to the transcripts) - the recovery draft is no
        // longer needed.
        app.liveDraft.clear()
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
        floatingButton?.setBusy(true)
        val target = ru.zf.pravka.target.PlainTextTarget(text)
        val outcome = runCatching { app.engine.proofread(target, ProofreadMode.CLEAN) }
            .getOrElse { e ->
                app.eventLog.add("cleanWithoutField threw ${e.javaClass.simpleName}")
                ProofreadEngine.Outcome.Failed(e.message ?: "Неизвестная ошибка")
            }
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
    private var learnBatchRunning = false
    private val ripenessCheck = Runnable { maybeRunLearnBatch() }

    private fun scheduleRipenessCheck() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.removeCallbacks(ripenessCheck)
        h.postDelayed(ripenessCheck, 11L * 60 * 1000)
    }

    /** The learning tab's "Разобрать сейчас": no 12h gate, no quiet wait. */
    fun runLearnBatchNow() = maybeRunLearnBatch(force = true)

    private fun maybeRunLearnBatch(force: Boolean = false) {
        if (learnBatchRunning) return
        scope.launch {
            val internal = getSharedPreferences("pravka_internal", MODE_PRIVATE)
            val last = internal.getLong("last_learn_batch", 0L)
            if (!force && System.currentTimeMillis() - last < 12L * 3600 * 1000) return@launch
            val ripe = app.editWatch.ripe(quietMs = if (force) 0L else 10L * 60 * 1000)
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
            learnBatchRunning = true
            val cases = ripe.take(5).map { Triple(it.dictated, it.cleaned, it.lastSeen) }
            app.eventLog.add("learn batch: ${cases.size} edits")
            app.learnLog.add("батч-анализ: правок к разбору — ${cases.size}")
            val result = app.claudeProvider.learnBatch(cases)
            learnBatchRunning = false
            result.onSuccess { proposals ->
                internal.edit().putLong("last_learn_batch", System.currentTimeMillis()).apply()
                app.editWatch.remove(ripe.take(5).map { it.id })
                val added = queueProposals(proposals)
                app.eventLog.add("learn batch: dict=${proposals.dict.size} rules=${proposals.rules.size} pending+=$added")
                if (added > 0) {
                    showLearnNotification(added)
                    refreshLearnBadge()
                }
            }.onFailure { e ->
                app.eventLog.add("learn batch failed: ${e.message}")
                app.learnLog.add("батч-анализ НЕ УДАЛСЯ: ${e.message} (правки не потеряны)")
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

    /**
     * Dedups against the dictionary and rules, stores the rest as pending.
     * A rule proposal matching an EXISTING rule counts as a confirmation
     * (its ×N grows) instead of being silently dropped.
     */
    private suspend fun queueProposals(proposals: ru.zf.pravka.provider.ClaudeProvider.LearnProposals): Int {
        val known = app.dictionaryStore.all().map { it.from.lowercase() }.toHashSet()
        val fresh = mutableListOf<ru.zf.pravka.data.LearnStore.Suggestion>()
        proposals.dict
            .filter { it.from.lowercase() !in known }
            .forEach { d ->
                fresh.add(
                    ru.zf.pravka.data.LearnStore.Suggestion(
                        id = 0, kind = "dict", mode = d.mode,
                        from = d.from, to = d.to, note = d.note,
                    )
                )
                app.learnLog.add("предложение (словарь): ${d.from} → ${d.to} [${d.mode}]")
            }
        for (r in proposals.rules) {
            if (app.rulesStore.confirm(r.text)) {
                app.learnLog.add("правило ПОДТВЕРДИЛОСЬ: ${r.text}")
                continue
            }
            fresh.add(
                ru.zf.pravka.data.LearnStore.Suggestion(
                    id = 0, kind = "rule", text = r.text,
                    exampleBefore = r.before, exampleAfter = r.after,
                )
            )
            app.learnLog.add("предложение (правило): ${r.text}")
        }
        return app.learnStore.add(fresh)
    }

    private fun showLearnNotification(count: Int) {
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
                .setContentText("Предложений из твоих правок: $count — открой раздел «Обучение».")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(3, notif)
        }
    }

    private fun learnFromField() {
        if (busy) return
        scope.launch {
            val node = focusedEditableNode()
            val current = node?.let { runCatching { it.effectiveText() }.getOrDefault("") }.orEmpty()
            if (current.isBlank()) {
                Feedback.toast(this@PravkaAccessibilityService, "Нет текста в поле — открой поле с поправленным текстом.")
                return@launch
            }
            // Find the journal entry whose OUTPUT this text is an edit of:
            // word-overlap similarity against recent outputs.
            val recent = kotlinx.coroutines.withContext(Dispatchers.IO) { app.historyLog.readPairs(30) }
            val match = recent.maxByOrNull { (_, out) -> wordOverlap(out, current) }
            val overlap = match?.let { wordOverlap(it.second, current) } ?: 0.0
            if (match == null || overlap < 0.4) {
                Feedback.toast(this@PravkaAccessibilityService, "Не нашёл в истории версию, из которой сделан этот текст.")
                return@launch
            }
            if (match.second.trim() == current.trim()) {
                Feedback.toast(this@PravkaAccessibilityService, "Текст не отличается от версии Правки — учиться не на чем.")
                return@launch
            }
            busy = true
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
                val added = queueProposals(proposals)
                app.eventLog.add("learn: dict=${proposals.dict.size} rules=${proposals.rules.size} pending+=$added")
                if (added > 0) refreshLearnBadge()
                if (added == 0) {
                    Feedback.toast(this@PravkaAccessibilityService, "Ничего системного в правках не нашлось.")
                } else {
                    Haptics.success(this@PravkaAccessibilityService)
                    Feedback.toast(
                        this@PravkaAccessibilityService,
                        "Предложений: $added — одобри их в Правке (раздел «Обучение»).",
                    )
                }
            }.onFailure { e ->
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
        scope.launch {
            busy = true
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            val content = assistContent()
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
                .getOrElse { Result.failure(it) }
            floatingButton?.hideTicker()
            floatingButton?.setBusy(false)
            busy = false
            result.onSuccess { r ->
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
                app.eventLog.add("assist $tag failed: ${e.message}")
                Haptics.error(this@PravkaAccessibilityService)
                Feedback.toast(this@PravkaAccessibilityService, e.message ?: "Ошибка")
            }
        }
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
        scope.launch {
            busy = true
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            // The pinned (dictation) path arrives with its selection already set.
            if (pinnedNode == null) selectAllInFocusedField()
            // Stream the corrected text across the ticker while it generates -
            // the first words appear well under a second after stop, instead of
            // a silent spinner for the whole generation. Deltas arrive on an IO
            // thread; the ticker is a View, so hop to main.
            floatingButton?.showTicker()
            // Opus thinks before it writes: no text deltas for several seconds.
            // Show a pulse so the wait doesn't read as a hang.
            if (strongModel) floatingButton?.updateTicker("…")
            val onDelta: (String) -> Unit = { partial ->
                scope.launch { floatingButton?.updateTicker(partial) }
            }
            // A throw anywhere below must never leave busy=true forever (a
            // wedged button until service restart) - degrade to Failed.
            val outcome = runCatching {
                app.engine.proofread(
                    AccessibilityTarget(this@PravkaAccessibilityService, pinnedNode), mode, onDelta,
                    directive = directive,
                    modelOverride = if (strongModel) Settings.MODEL_OPUS else null,
                    conversationContext = conversationContext,
                )
            }.getOrElse { e ->
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
                }
            }
            maybeRunLearnBatch()
            // The post-fix result bar is gone (owner: it covered the keyboard).
            // Undo lives in the long-press FAB menu; the word diff and quick
            // add-to-dictionary went with the bar.
        }
    }

    // Result-bar action: the model "fixed" a correct word - protect the
    // dictated form and hard-replace the wrong one back on future runs.
    private fun addPairToDictionary(correct: String, wrong: String) {
        scope.launch {
            val store = app.dictionaryStore
            val existing = store.all().map { it.from.lowercase() to it.mode }.toHashSet()
            if ((correct.lowercase() to ru.zf.pravka.core.DictMode.PROTECT) !in existing) {
                store.add(correct, "", ru.zf.pravka.core.DictMode.PROTECT, "")
            }
            if ((wrong.lowercase() to ru.zf.pravka.core.DictMode.HARD) !in existing) {
                store.add(wrong, correct, ru.zf.pravka.core.DictMode.HARD, "")
            }
            cachedBiasing = collectBiasing()  // new words bias the recognizer too
            Haptics.success(this@PravkaAccessibilityService)
            Feedback.toast(
                this@PravkaAccessibilityService,
                getString(R.string.dict_pair_added, correct, wrong),
            )
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The foldable changes configuration on fold/unfold - reposition.
        resultBar?.dismiss()
        floatingButton?.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        googleSession?.stop()
        googleSession = null
        runCatching { stopMicHold() }
        resultBar?.dismiss()
        resultBar = null
        floatingButton?.destroy()
        floatingButton = null
        scope.cancel()
        super.onDestroy()
    }
}
