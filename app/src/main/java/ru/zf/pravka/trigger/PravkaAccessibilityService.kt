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
        val session = GoogleSpeechSession(this, biasing = cachedBiasing)
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
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(false)
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
            runProofread(ProofreadMode.CLEAN, pinnedNode = node)
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
        floatingButton?.toggleMenu(
            listOf(
                getString(R.string.quick_clean) to { runProofread(ProofreadMode.CLEAN) },
                getString(R.string.redo_shorter) to { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_SHORTER) },
                getString(R.string.redo_longer) to { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_LONGER) },
                getString(R.string.redo_polish) to { redoWithDirective(ru.zf.pravka.core.Prompts.REDO_POLISH) },
                getString(R.string.fab_menu_undo) to { undoLast() },
            )
        )
    }

    private fun runProofread(
        mode: ProofreadMode,
        pinnedNode: AccessibilityNodeInfo? = null,
        directive: String = "",
        strongModel: Boolean = false,
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
            // Something was written into the field - offer undo, the word
            // diff and quick add-to-dictionary for a few seconds (spec 9.2).
            if (outcome is ProofreadEngine.Outcome.Applied) {
                UndoStack.last()?.let { resultBar?.show(it.before, it.after) }
            }
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
