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
//
// Selection-aware (owner's request): a real selection inside the field means
// "fix only this" - read() returns the selected fragment and write() splices
// the fix back into the untouched rest of the field. A bare cursor (or the
// service's own select-all highlight covering the whole field) means the
// whole field is the work item, as before.
class AccessibilityTarget(private val service: PravkaAccessibilityService) : TextTarget {

    private var node: AccessibilityNodeInfo? = null
    private var fullText: String = ""
    private var selStart: Int = -1
    private var selEnd: Int = -1

    // Field-level before/after of the last successful write - what undo
    // must restore even when only a fragment was fixed.
    private var fullBefore: String = ""
    private var fullAfter: String = ""

    private val hasFragmentSelection: Boolean
        get() = selStart in 0 until selEnd &&
            selEnd <= fullText.length &&
            !(selStart == 0 && selEnd == fullText.length)

    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        val n = service.focusedEditableNode() ?: return@withContext null
        node = n
        fullText = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        selStart = n.textSelectionStart
        selEnd = n.textSelectionEnd
        if (hasFragmentSelection) fullText.substring(selStart, selEnd) else fullText
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.Main) {
        val n = node ?: return@withContext false
        if (!n.refresh() || !n.isEditable) return@withContext false

        // Never write into a field the user has already left.
        val currentFocus = service.focusedEditableNode()
        if (currentFocus == null || currentFocus != n) return@withContext false

        // The engine trims what it reads, so restore the selection's own
        // edge whitespace - otherwise words merge at the splice points.
        val newFull: String
        val cursor: Int
        if (hasFragmentSelection) {
            val original = fullText.substring(selStart, selEnd)
            val lead = original.takeWhile { it.isWhitespace() }
            val trail = original.takeLastWhile { it.isWhitespace() }
            val replacement = lead + text + trail
            newFull = fullText.replaceRange(selStart, selEnd, replacement)
            cursor = selStart + replacement.length
        } else {
            newFull = text
            cursor = text.length
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newFull,
            )
        }
        // ACTION_SET_TEXT is flaky in WebView and some Compose fields -
        // ALWAYS check the return value (spec 5.2).
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            fullBefore = fullText
            fullAfter = newFull
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        ok
    }

    // Undo must restore the whole field, not just the fragment: a later
    // undo reads the field fresh (no selection) and matches by full text.
    override fun undoPair(input: String, output: String): Pair<String, String> =
        if (fullAfter.isNotEmpty() || fullBefore.isNotEmpty()) fullBefore to fullAfter else input to output

    override fun isExplicitFragment(): Boolean = hasFragmentSelection
}
