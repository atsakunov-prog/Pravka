package ru.zf.pravka.target

import android.view.accessibility.AccessibilityNodeInfo

// Placeholders that some apps report as the field's *text* while it is actually
// empty (messengers are the usual offenders, and they often set no hint
// metadata at all).
private val COMMON_PLACEHOLDERS = setOf(
    "сообщение", "сообщение…", "сообщение...",
    "введите сообщение", "напишите сообщение", "написать сообщение",
    "message", "type a message", "aa",
    "поиск", "search", "введите текст", "текст сообщения",
)

/**
 * The field's real content, with a placeholder treated as empty.
 *
 * `isShowingHintText` alone is not enough - it is simply false in several apps
 * that still surface their hint as the node's text, which is how "Сообщение"
 * ended up glued to the front of a dictation. Both the write path (inserting
 * dictated text) and the read path (what CLEAN proofreads) must agree on this,
 * so the rule lives here rather than in either caller.
 */
fun AccessibilityNodeInfo.effectiveText(): String {
    val raw = text?.toString().orEmpty()
    if (raw.isEmpty() || isShowingHintText) return if (isShowingHintText) "" else raw
    val trimmed = raw.trim()
    val hint = hintText?.toString()?.trim()
    val description = contentDescription?.toString()?.trim()
    val isPlaceholder = (!hint.isNullOrEmpty() && trimmed.equals(hint, ignoreCase = true)) ||
        (!description.isNullOrEmpty() && trimmed.equals(description, ignoreCase = true)) ||
        trimmed.lowercase() in COMMON_PLACEHOLDERS
    return if (isPlaceholder) "" else raw
}
