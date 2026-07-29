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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var floatingButton: FloatingButtonController? = null
    private var resultBar: ResultBarController? = null
    private var busy = false

    // Weak cache of the last focused editable node and its text, updated on
    // TYPE_VIEW_FOCUSED / TYPE_VIEW_TEXT_CHANGED (spec 5.4: activities steal
    // focus, so triggers launched via Activity read from this cache).
    private var cachedFocus: WeakReference<AccessibilityNodeInfo>? = null
    var cachedFocusText: String? = null
        private set

    // The field to receive dictated text, captured when recording starts -
    // the owner may switch apps while dictating, so we can't rely on focus
    // at stop time.
    private var dictationTarget: WeakReference<AccessibilityNodeInfo>? = null

    // Live Google (streaming) dictation session, when that engine is active.
    private var googleSession: GoogleSpeechSession? = null
    private var googleStartedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        resultBar = ResultBarController(this, ::undoLast, ::addPairToDictionary)
        floatingButton = FloatingButtonController(
            service = this,
            scope = scope,
            settings = (application as PravkaApp).settings,
            onShortTap = ::onDictateTap,
            onLongPress = { runProofread(ProofreadMode.CLEAN) },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    cachedFocus = WeakReference(source)
                    cachedFocusText = source.text?.toString()
                    floatingButton?.show()
                } else {
                    floatingButton?.hide()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source ?: return
                if (source.isEditable) {
                    cachedFocus = WeakReference(source)
                    cachedFocusText = source.text?.toString()
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

    /** External triggers (quick settings tile) land here too. */
    fun trigger(mode: ProofreadMode) = runProofread(mode)

    fun triggerUndo() = undoLast()

    // ---- Dictation (short tap): record -> transcribe -> insert -> fix ----

    /** Short tap: stop the active session if any, else start per the engine. */
    private fun onDictateTap() {
        if (googleSession != null) { stopGoogleDictation(); return }
        if (DictationService.recording) {
            floatingButton?.setBusy(true)
            stopDictation()  // DictationService calls back onRecordingSaved()
            return
        }
        // Starting: the engine choice is a suspend read.
        scope.launch {
            if (isGoogleEngine()) beginGoogleDictation() else beginDictation()
        }
    }

    private suspend fun isGoogleEngine(): Boolean =
        (application as PravkaApp).settings.speechEngine() == Settings.SPEECH_GOOGLE

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

    private fun beginDictation() {
        if (!hasMicPermission()) { requestMicPermission(); return }
        startRecordingNow()
    }

    private suspend fun beginGoogleDictation() {
        if (!hasMicPermission()) { requestMicPermission(); return }
        startGoogleNow()
    }

    /** Called by MicPermissionActivity after the permission is granted. */
    fun onMicPermissionGranted() {
        scope.launch {
            if (isGoogleEngine()) startGoogleNow() else startRecordingNow()
        }
    }

    // Names/terms/brands the recognizer should be biased toward - the owner's
    // dictionary (both protected forms and the correct sides of replacements).
    private suspend fun collectBiasing(): List<String> = runCatching {
        val app = application as PravkaApp
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

    private suspend fun startGoogleNow() {
        if (googleSession != null) return
        if (!GoogleSpeechSession.isAvailable(this)) {
            Haptics.error(this)
            Feedback.toast(this, getString(R.string.google_unavailable))
            return
        }
        val biasing = collectBiasing()
        dictationTarget = focusedEditableNode()?.let { WeakReference(it) } ?: cachedFocus
        floatingButton?.setRecording(true)
        floatingButton?.showTicker()
        Haptics.start(this)
        // Foreground-mic holder so the recognizer survives app switches. If it
        // can't start (rare FGS restrictions), recognition still works while
        // Правка is foregrounded, so don't abort the session over it.
        runCatching { startMicHold() }
        googleStartedAt = SystemClock.elapsedRealtime()
        lastDraftAt = 0L
        val app = application as PravkaApp
        val session = GoogleSpeechSession(this, biasing = biasing)
        googleSession = session
        session.start(
            // Live text feeds the on-screen ticker; throttle it to disk (~1.2s)
            // and force a durable save at every finalized segment, so an
            // interrupted take (phone dies, killed) can still be recovered.
            onPartial = { live ->
                floatingButton?.updateTicker(live)
                saveDraftThrottled(live)
            },
            onCheckpoint = { text -> app.liveDraft.save(text) },
            onDone = { text -> onGoogleDone(text) },
            onError = { msg -> onGoogleError(msg) },
            onLog = { line -> app.eventLog.add(line) },
        )
    }

    private var lastDraftAt = 0L
    private fun saveDraftThrottled(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDraftAt < 1200) return
        lastDraftAt = now
        (application as PravkaApp).liveDraft.save(text)
    }

    /** Second tap or the notification's Stop button: finalize the session. */
    fun stopGoogleDictation() {
        val session = googleSession ?: return
        (application as PravkaApp).eventLog.add("stop requested")
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(true)
        session.stop()  // -> onGoogleDone
    }

    private fun onGoogleDone(text: String) {
        googleSession = null
        stopMicHold()
        floatingButton?.hideTicker()
        floatingButton?.setRecording(false)
        floatingButton?.setBusy(false)
        val app = application as PravkaApp
        val wall = SystemClock.elapsedRealtime() - googleStartedAt
        app.transcriptionLog.append(
            engine = Settings.SPEECH_GOOGLE,
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
            return
        }
        scope.launch { insertDictated(text) }
    }

    private fun onGoogleError(msg: String) {
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
        val app = application as PravkaApp
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
        val app = application as PravkaApp
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
    private suspend fun insertDictated(text: String) {
        val node = (dictationTarget?.get()?.takeIf { it.refresh() && it.isEditable })
            ?: focusedEditableNode()
        if (node == null) {
            ru.zf.pravka.target.ClipboardTarget(this).write(text)
            Feedback.toast(this, getString(R.string.dictation_to_clipboard))
            return
        }
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        // Append at the end by default. After our own ACTION_SET_TEXT the field
        // often reports the cursor back at 0, which made a follow-up dictation
        // land at the START of the phrase. Only honour a genuine mid-text cursor
        // (>0 and not already at the end); otherwise append.
        val selEnd = node.textSelectionEnd
        val cursor = if (selEnd in 1..existing.length) selEnd else existing.length
        val needsSpaceBefore = cursor > 0 && !existing[cursor - 1].isWhitespace()
        val insert = (if (needsSpaceBefore) " " else "") + text
        val newText = existing.substring(0, cursor) + insert + existing.substring(cursor)
        val spanStart = cursor + (if (needsSpaceBefore) 1 else 0)
        val spanEnd = spanStart + text.length

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            // Some fields reject a big ACTION_SET_TEXT. Put the fragment on the
            // clipboard and try to PASTE it into the field automatically, so it
            // still lands in the box without a manual paste.
            ru.zf.pravka.target.ClipboardTarget(this).write(insert)
            val pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            if (!pasted) Feedback.toast(this, getString(R.string.dictation_to_clipboard))
            else Haptics.success(this)
            return
        }
        node.refresh()
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, spanStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, spanEnd.coerceAtMost(newText.length))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        Haptics.success(this)
        // Fix the just-inserted fragment if the field is focused (it usually
        // is - the owner tapped stop right in it). AccessibilityTarget reads
        // the selection, so only the dictated span is proofread.
        if (focusedEditableNode() == node) runProofread(ProofreadMode.CLEAN)
    }

    private fun runProofread(mode: ProofreadMode) {
        if (busy) return
        val app = application as PravkaApp
        scope.launch {
            busy = true
            floatingButton?.setBusy(true)
            Haptics.start(this@PravkaAccessibilityService)
            selectAllInFocusedField()
            val outcome = app.engine.proofread(AccessibilityTarget(this@PravkaAccessibilityService), mode)
            floatingButton?.setBusy(false)
            busy = false
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
        val app = application as PravkaApp
        scope.launch {
            val store = app.dictionaryStore
            val existing = store.all().map { it.from.lowercase() to it.mode }.toHashSet()
            if ((correct.lowercase() to ru.zf.pravka.core.DictMode.PROTECT) !in existing) {
                store.add(correct, "", ru.zf.pravka.core.DictMode.PROTECT, "")
            }
            if ((wrong.lowercase() to ru.zf.pravka.core.DictMode.HARD) !in existing) {
                store.add(wrong, correct, ru.zf.pravka.core.DictMode.HARD, "")
            }
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
