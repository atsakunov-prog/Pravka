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
    private var undoChip: UndoChipController? = null
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
        undoChip = UndoChipController(this, ::undoLast)
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
                // App or window switched: the field the chip would undo is
                // gone; only keep the button when a field is still focused.
                undoChip?.dismiss()
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
            // Something was written into the field - offer a one-tap undo
            // for a few seconds on top of the long-press menu item.
            if (outcome is ProofreadEngine.Outcome.Applied) undoChip?.show()
        }
    }

    // Visual feedback: highlight the whole field the moment proofreading
    // starts, so it is obvious what is being processed. The engine reads
    // the entire field regardless of selection.
    private fun selectAllInFocusedField() {
        val node = focusedEditableNode() ?: return
        val length = node.text?.length ?: return
        if (length == 0) return
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
        undoChip?.dismiss()
        floatingButton?.onConfigurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        undoChip?.dismiss()
        undoChip = null
        floatingButton?.destroy()
        floatingButton = null
        scope.cancel()
        super.onDestroy()
    }
}
