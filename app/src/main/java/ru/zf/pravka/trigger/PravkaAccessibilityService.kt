package ru.zf.pravka.trigger

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        resultBar = ResultBarController(this, ::undoLast, ::addPairToDictionary)
        floatingButton = FloatingButtonController(
            service = this,
            scope = scope,
            settings = (application as PravkaApp).settings,
            onMode = ::runProofread,
            onUndo = ::undoLast,
            onOpenApp = {
                startActivity(
                    android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
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
        resultBar?.dismiss()
        resultBar = null
        floatingButton?.destroy()
        floatingButton = null
        scope.cancel()
        super.onDestroy()
    }
}
