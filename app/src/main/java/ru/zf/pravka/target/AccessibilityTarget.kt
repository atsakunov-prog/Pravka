package ru.zf.pravka.target

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.trigger.PravkaAccessibilityService

// Reads and writes the focused editable field via the accessibility service
// (spec 5.2). Remembers the node it read from; refuses to write if focus has
// moved to another field by the time the reply arrives (spec section 8) -
// the engine then falls back to the clipboard.
class AccessibilityTarget(private val service: PravkaAccessibilityService) : TextTarget {

    private var node: AccessibilityNodeInfo? = null

    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        val n = service.focusedEditableNode() ?: return@withContext null
        node = n
        if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.Main) {
        val n = node ?: return@withContext false
        if (!n.refresh() || !n.isEditable) return@withContext false

        // Never write into a field the user has already left.
        val currentFocus = service.focusedEditableNode()
        if (currentFocus == null || currentFocus != n) return@withContext false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        // ACTION_SET_TEXT is flaky in WebView and some Compose fields -
        // ALWAYS check the return value (spec 5.2).
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        ok
    }
}
