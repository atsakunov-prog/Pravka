package ru.zf.pravka.target

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.trigger.PravkaAccessibilityService

// Reads and writes the focused editable field via the accessibility service
// (spec 5.2). Remembers the node it read from; refuses to write if focus has
// moved to another field - or if the field's TEXT changed while the reply was
// in flight (the user kept typing) - the engine then falls back to the
// clipboard instead of destroying their words.
//
// Selection-aware (owner's request): a real selection inside the field means
// "fix only this" - read() returns the selected fragment and write() splices
// the fix back into the untouched rest of the field. A bare cursor (or the
// service's own select-all highlight covering the whole field) means the
// whole field is the work item, as before.
//
// [pinnedNode]: post-dictation CLEAN must operate on the field the text was
// just inserted into, NOT whatever currently holds input focus - the owner
// may have switched apps mid-take, and re-deriving focus here used to rewrite
// a different app's field.
class AccessibilityTarget(
    private val service: PravkaAccessibilityService,
    private val pinnedNode: AccessibilityNodeInfo? = null,
) : TextTarget {

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
        val n = pinnedNode?.takeIf { it.refresh() && it.isEditable }
            ?: (if (pinnedNode != null) null else service.focusedEditableNode())
            ?: run {
                service.logEvent("read: no node (pinned=${pinnedNode != null})")
                return@withContext null
            }
        node = n
        // Shared with the dictation insert path, so a placeholder like
        // "Сообщение" is never proofread as if it were the owner's text.
        fullText = n.effectiveText()
        selStart = n.textSelectionStart
        selEnd = n.textSelectionEnd
        if (hasFragmentSelection) fullText.substring(selStart, selEnd) else fullText
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.Main) {
        val n = node ?: return@withContext false
        if (!n.refresh() || !n.isEditable) {
            service.logEvent("write: node gone or not editable")
            return@withContext false
        }

        // Never write into a field the user has already left. A pinned node is
        // its own authority - it was valid at insert time and just re-validated.
        if (pinnedNode == null) {
            val currentFocus = service.focusedEditableNode()
            if (currentFocus == null || currentFocus != n) {
                service.logEvent("write: focus moved (focus=${currentFocus != null})")
                return@withContext false
            }
        }

        // Never write over text that changed during the API round trip: the
        // reply below is a rewrite of the OLD field content, and SET_TEXT
        // replaces the whole field - anything typed in the meantime would be
        // destroyed with no undo entry containing it. Clipboard fallback is the
        // designed degradation. Whitespace-insensitive: single-line fields
        // legally flatten "\n" to a space, which is not the user typing.
        val current = n.effectiveText()
        if (normalizedWs(current) != normalizedWs(fullText)) {
            service.logEvent(
                "write: field changed mid-flight (now=${current.length} was=${fullText.length})"
            )
            return@withContext false
        }

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
        if (!ok) service.logEvent("write: SET_TEXT rejected (len=${newFull.length})")
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

    override fun contextBefore(): String =
        if (hasFragmentSelection) fullText.substring(maxOf(0, selStart - 300), selStart) else ""

    private fun normalizedWs(s: String): String = s.replace(WS, " ").trim()

    private companion object {
        val WS = Regex("\\s+")
    }
}
