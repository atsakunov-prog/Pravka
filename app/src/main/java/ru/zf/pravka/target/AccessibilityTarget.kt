package ru.zf.pravka.target

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // read()/write() run on Main, preview() on a background dispatcher - the
    // shared fields are volatile so the streaming thread sees read()'s state.
    @Volatile private var node: AccessibilityNodeInfo? = null
    @Volatile private var fullText: String = ""
    @Volatile private var selStart: Int = -1
    @Volatile private var selEnd: Int = -1

    // Field-level before/after of the last successful write - what undo
    // must restore even when only a fragment was fixed.
    private var fullBefore: String = ""
    private var fullAfter: String = ""

    // Что МЫ САМИ уже писали в поле потоком — несколько последних состояний,
    // а не одно. Владелец (15.09.2026): «чистка обрывается на середине, в поле
    // половина, в буфере всё». Причина была здесь: preview-корутины бежали
    // параллельно и без очереди, поздний кусок мог записать previewedFull
    // ПОСЛЕ более длинного, а финальный write() читал поле, пока запоздавший
    // SET_TEXT ещё шёл, — видел «чужой» текст, решал, что владелец печатает,
    // и уходил в буфер, оставив в поле хвост стрима. Теперь preview и write
    // ходят в окно под одним замком, а своим считается любое из последних
    // состояний.
    private val fieldLock = Mutex()
    private val previewed = ArrayDeque<String>()
    @Volatile private var pendingPartial: String = ""

    private fun isOurs(current: String): Boolean {
        val cur = normalizedWs(current)
        if (cur == normalizedWs(fullText)) return true
        return previewed.any { normalizedWs(it) == cur }
    }

    private fun rememberPreviewed(full: String) {
        previewed.addLast(full)
        while (previewed.size > 6) previewed.removeFirst()
    }

    private val hasFragmentSelection: Boolean
        get() = selStart in 0 until selEnd &&
            selEnd <= fullText.length &&
            !(selStart == 0 && selEnd == fullText.length)

    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        // Node calls throw when the host window died (screen off, app killed);
        // that is "no field", not a crash.
        runCatching { readInner() }
            .onFailure { service.logEvent("read: threw ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun readInner(): String? {
        val n = pinnedNode?.takeIf { it.refresh() && it.isEditable }
            ?: (if (pinnedNode != null) null else service.focusedEditableNode())
            ?: run {
                service.logEvent("read: no node (pinned=${pinnedNode != null})")
                return null
            }
        node = n
        // Shared with the dictation insert path, so a placeholder like
        // "Сообщение" is never proofread as if it were the owner's text.
        fullText = n.effectiveText()
        selStart = n.textSelectionStart
        selEnd = n.textSelectionEnd
        return if (hasFragmentSelection) fullText.substring(selStart, selEnd) else fullText
    }

    /**
     * Live streaming (owner's request): the cleaned text pours straight into
     * the field while it generates, replacing the work item in place - no
     * plate. Best-effort: any failure (dead node, rejected SET_TEXT, the user
     * typing mid-stream) returns false and the caller falls back to the
     * ticker; the final [write] still decides the real outcome on its own.
     *
     * DELIBERATELY off the main thread: these are binder calls into the
     * target window every ~150 ms for the whole generation, and node calls
     * into a window that is dying mid-fold can block for seconds - exactly
     * the fold black-screen disease (see the service's fold notes). On a
     * locked screen (the owner folded/pocketed the phone mid-stream) the
     * preview stops instantly and the ticker takes over.
     */
    suspend fun preview(partial: String): Boolean {
        if (service.isLockedIdle()) return false
        pendingPartial = partial
        return withContext(Dispatchers.Default) {
            fieldLock.withLock {
                // Пока ждали замок, приехал кусок длиннее — этот уже не нужен:
                // очередь устаревших SET_TEXT только задерживает финал.
                if (pendingPartial.length > partial.length) return@withLock true
                runCatching { previewInner(partial) }
                    .onFailure { service.logEvent("preview: threw ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            }
        }
    }

    private fun previewInner(partial: String): Boolean {
        if (partial.isEmpty()) return true
        val n = node ?: return false
        if (!n.refresh() || !n.isEditable) return false
        if (pinnedNode == null) {
            val currentFocus = service.focusedEditableNode()
            if (currentFocus == null || currentFocus != n) return false
        }
        // Hands off the moment the field holds anything that is not the
        // original text or our own previous previews - the user is typing.
        val current = n.effectiveText()
        if (!isOurs(current)) return false
        val newFull =
            if (hasFragmentSelection) fullText.replaceRange(selStart, selEnd, partial)
            else partial
        if (newFull == current) return true
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFull)
        }
        // Запоминаем ДО вызова: поле меняется в момент performAction, и
        // читатель с другого потока должен уже знать, что это наше.
        rememberPreviewed(newFull)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) previewed.remove(newFull)
        return ok
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.Main) {
        // Под тем же замком, что и preview: финал ждёт, пока последний кусок
        // стрима доедет до поля, и только потом смотрит, чьё там содержимое.
        fieldLock.withLock {
            // Same as read(): a dead window must degrade to the clipboard fallback.
            runCatching { writeInner(text) }
                .onFailure { service.logEvent("write: threw ${it.javaClass.simpleName}") }
                .getOrDefault(false)
        }
    }

    private suspend fun writeInner(text: String): Boolean {
        val n = node ?: return false
        if (!n.refresh() || !n.isEditable) {
            service.logEvent("write: node gone or not editable")
            return false
        }

        // Never write into a field the user has already left. A pinned node is
        // its own authority - it was valid at insert time and just re-validated.
        //
        // КРОМЕ поля, где уже лежит наш стрим (26.09.2026, владелец: «в конце,
        // если я ухожу из текстбокса, он пишет, что не может вставить в буфер
        // обмена»). Чистка льётся в поле по ходу ответа, владелец видит текст
        // почти целиком и уходит из поля — а финал (последние слова, которые
        // стрим не успел донести из-за паузы в 150 мс между кусками) упирался
        // в «фокус ушёл»: тост «не смог вставить» при тексте, который стоит в
        // поле. Если в поле ровно наш стрим и владелец в нём ничего не менял,
        // финал дописывается в то же поле; до первого куска стрима правило
        // прежнее — в оставленное поле не пишем.
        val focusMoved = pinnedNode == null && run {
            val currentFocus = service.focusedEditableNode()
            (currentFocus == null || currentFocus != n).also { moved ->
                if (moved) service.logEvent("write: focus moved (focus=${currentFocus != null}, previews=${previewed.size})")
            }
        }
        if (focusMoved && previewed.isEmpty()) return false

        // Never write over text that changed during the API round trip: the
        // reply below is a rewrite of the OLD field content, and SET_TEXT
        // replaces the whole field - anything typed in the meantime would be
        // destroyed with no undo entry containing it. Clipboard fallback is the
        // designed degradation. Whitespace-insensitive: single-line fields
        // legally flatten "\n" to a space, which is not the user typing.
        // Our own live preview is not the user typing either.
        val current = n.effectiveText()
        // В оставленном поле — только наш стрим, не исходный текст: пустое
        // поле после «отправить» совпало бы с пустым исходным, и финал
        // вернулся бы в поле уже отправленного сообщения.
        if (focusMoved && previewed.none { normalizedWs(it) == normalizedWs(current) }) {
            service.logEvent("write: left field holds no stream of ours (len=${current.length})")
            return false
        }
        if (!isOurs(current)) {
            service.logEvent(
                "write: field changed mid-flight (now=${current.length} was=${fullText.length} " +
                    "previews=${previewed.size}, last=${previewed.lastOrNull()?.length ?: 0})"
            )
            return false
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

        // Стрим уже донёс финал целиком — это и есть доставка, писать нечего.
        if (normalizedWs(current) == normalizedWs(newFull)) {
            fullBefore = fullText
            fullAfter = newFull
            if (focusMoved) service.logEvent("write: final already in the field via stream")
            return true
        }
        if (focusMoved) service.logEvent("write: finishing our stream in the left field")

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newFull,
            )
        }
        // ACTION_SET_TEXT is flaky in WebView and some Compose fields -
        // ALWAYS check the return value (spec 5.2).
        var ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            // Один повтор через паузу: поле, только что принявшее десяток
            // потоковых SET_TEXT, иногда отвергает финальный на перерисовке.
            // В поле в этот момент хвост стрима — оставить его нельзя.
            service.logEvent("write: SET_TEXT rejected (len=${newFull.length}) — повтор")
            delay(150)
            if (n.refresh() && n.isEditable) {
                ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            if (!ok) service.logEvent("write: SET_TEXT rejected twice (len=${newFull.length})")
        }
        if (ok) {
            fullBefore = fullText
            fullAfter = newFull
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        return ok
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
